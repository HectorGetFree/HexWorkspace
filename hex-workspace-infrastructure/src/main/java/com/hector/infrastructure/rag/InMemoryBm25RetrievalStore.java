package com.hector.infrastructure.rag;

import com.alibaba.fastjson.JSON;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.knowledge.adapter.port.IRetrievalSparseStore;
import com.hector.domain.knowledge.model.valobj.KnowledgeChunkVO;
import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;
import com.hector.domain.knowledge.repository.*;
import jakarta.annotation.Resource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 进程内非权威快照；完整刷新成功才发布，查询不等待冷启动构建。 */
@Component
public class InMemoryBm25RetrievalStore implements IRetrievalSparseStore {
    private static final Logger log = LoggerFactory.getLogger(InMemoryBm25RetrievalStore.class);
    @Resource private IDocumentRepository documentRepository;
    @Resource private IDocumentStorageRepository storage;
    @Resource private IKnowledgeIndexStateRepository states;
    @Resource private IKnowledgeIndexGenerationRepository generations;
    @Resource private IServingVectorSpaceRepository serving;
    @Value("${hexworkspace.knowledge.hybrid.enabled:true}") private boolean enabled;
    @Value("${hexworkspace.knowledge.hybrid.max-workspaces:8}") private int maxWorkspaces = 8;
    @Value("${hexworkspace.knowledge.hybrid.max-chunks:20000}") private int maxChunks = 20_000;
    @Value("${hexworkspace.knowledge.hybrid.max-source-chars:4000000}") private long maxSourceChars = 4_000_000;
    private final Map<Long, Slot> slots = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "bm25-snapshot-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private record Snapshot(Long configId, Bm25Index index, int chunkCount, long sourceChars, String generationHash) { }
    private static final class Slot {
        final AtomicBoolean refreshing = new AtomicBoolean();
        volatile Snapshot snapshot;
        volatile String lastError;
    }
    @Override
    public List<RetrievalCandidateVO> searchCandidates(Long workspaceId, Long configId, String query, int limit) {
        if (!enabled) return List.of();
        Slot slot;
        synchronized (slots) {
            // 1. 有界缓存；达到上限的 Workspace 继续走 Dense，不逐出正在使用的快照。
            if (!slots.containsKey(workspaceId) && slots.size() >= maxWorkspaces) return List.of();
            slot = slots.computeIfAbsent(workspaceId, ignored -> new Slot());
        }
        Snapshot snapshot = slot.snapshot;
        if (snapshot == null || !Objects.equals(snapshot.configId(), configId)) schedule(workspaceId, slot);
        return snapshot == null || !Objects.equals(snapshot.configId(), configId)
                ? List.of() : snapshot.index().search(query, limit);
    }
    @Override
    public void refreshKnownWorkspaces() {
        if (enabled) slots.forEach(this::schedule);
    }
    @Override
    public Map<String, Object> prepareAndStatus(Long workspaceId) {
        var config = serving.findServingConfig().orElseThrow();
        if (enabled) searchCandidates(workspaceId, config.getId(), "", 0);
        Slot slot = slots.get(workspaceId);
        Snapshot snapshot = slot == null ? null : slot.snapshot;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("ready", enabled && snapshot != null && Objects.equals(snapshot.configId(), config.getId()));
        status.put("servingConfigId", config.getId());
        status.put("refreshing", slot != null && slot.refreshing.get());
        status.put("chunkCount", snapshot == null ? 0 : snapshot.chunkCount());
        status.put("sourceChars", snapshot == null ? 0 : snapshot.sourceChars());
        status.put("generationHash", snapshot == null ? "" : snapshot.generationHash());
        status.put("lastRefreshError", slot == null || slot.lastError == null ? "" : slot.lastError);
        status.put("maxSourceChars", maxSourceChars);
        status.put("maxChunks", maxChunks);
        return status;
    }
    private void schedule(Long workspaceId, Slot slot) {
        if (!slot.refreshing.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { refresh(workspaceId, slot); slot.lastError = null; }
                catch (Exception e) { slot.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    log.warn("BM25 快照刷新失败，保留旧快照 workspaceId:{}", workspaceId, e); }
                finally { slot.refreshing.set(false); }
            });
        } catch (RejectedExecutionException e) { slot.refreshing.set(false); }
    }
    private void refresh(Long workspaceId, Slot slot) throws Exception {
        long started = System.nanoTime();
        var config = serving.findServingConfig().orElseThrow();
        var documents = documentRepository.listByWorkspaceId(workspaceId).stream()
                .filter(d -> d.getStatus() == DocumentStatusVO.ACTIVE).sorted(Comparator.comparing(d -> d.getId())).toList();
        var active = states.listByDocumentIds(documents.stream().map(d -> d.getId()).toList());
        var ids = active.stream().map(s -> s.getActiveGenerationId()).filter(Objects::nonNull).distinct().toList();
        var metadata = ids.isEmpty() ? List.<com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity>of()
                : generations.listByGenerationIds(ids);
        Map<Long, String> pointers = new HashMap<>();
        active.forEach(s -> pointers.put(s.getDocumentId(), s.getActiveGenerationId()));
        List<RetrievalCandidateVO> candidates = new ArrayList<>();
        List<String> loadedGenerations = new ArrayList<>();
        long chars = 0;
        // 2. 只加载当前 READY Generation 的不可变 Chunk 产物；中途失败不发布部分索引。
        for (var document : documents) {
            var generation = metadata.stream().filter(g -> Objects.equals(g.getGenerationId(), pointers.get(document.getId())))
                    .findFirst().orElse(null);
            if (generation == null || !Objects.equals(generation.getStatus(), 1)
                    || !Objects.equals(generation.getWorkspaceId(), workspaceId)
                    || !Objects.equals(generation.getDocumentId(), document.getId())
                    || !Objects.equals(generation.getVersionNo(), document.getCurrentVersionNo())
                    || !Objects.equals(generation.getIndexInputRevision(), document.getIndexInputRevision())
                    || !Objects.equals(generation.getEmbeddingConfigId(), config.getId())) continue;
            byte[] bytes = storage.download(generation.getChunkArtifactKey());
            if (bytes.length > 16_000_000 || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .equalsIgnoreCase(generation.getChunkArtifactChecksum())) throw new IllegalStateException("Chunk 产物校验失败");
            var chunks = JSON.parseArray(new String(bytes, StandardCharsets.UTF_8), KnowledgeChunkVO.class);
            if (chunks == null || !Objects.equals(chunks.size(), generation.getChunkCount()))
                throw new IllegalStateException("Chunk 数量不一致");
            loadedGenerations.add(generation.getGenerationId());
            Set<Integer> numbers = new HashSet<>();
            for (var chunk : chunks) {
                if (!Objects.equals(chunk.generationId(), generation.getGenerationId())
                        || !Objects.equals(chunk.documentId(), document.getId())
                        || !Objects.equals(chunk.versionNo(), generation.getVersionNo())
                        || chunk.chunkNo() < 0 || !numbers.add(chunk.chunkNo()) || chunk.text() == null)
                    throw new IllegalStateException("Chunk 身份不一致");
                chars += chunk.text().length();
                if (candidates.size() >= maxChunks || chars > maxSourceChars) throw new IllegalStateException("BM25 快照规模超过本地上限");
                String point = UUID.nameUUIDFromBytes((chunk.generationId() + ":" + chunk.chunkNo()).getBytes(StandardCharsets.UTF_8)).toString();
                candidates.add(new RetrievalCandidateVO(point, workspaceId, document.getId(), generation.getVersionNo(),
                        generation.getIndexInputRevision(), chunk.generationId(), chunk.chunkNo(), chunk.headingPath(),
                        chunk.text(), chunk.pageStart(), chunk.pageEnd(), 0D));
            }
        }
        // 3. 单次 volatile 发布不可变快照；并发业务失效由检索服务的双重权威校验阻断。
        String generationHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                String.join("\n", loadedGenerations.stream().sorted().toList()).getBytes(StandardCharsets.UTF_8)));
        slot.snapshot = new Snapshot(config.getId(), new Bm25Index(candidates), candidates.size(), chars, generationHash);
        log.info("BM25 快照已发布 workspaceId:{} chunks:{} chars:{} elapsedMs:{}", workspaceId,
                candidates.size(), chars, (System.nanoTime() - started) / 1_000_000D);
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
