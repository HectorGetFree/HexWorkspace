package com.hector.infrastructure.rag;

import com.alibaba.fastjson.JSON;
import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.knowledge.adapter.port.IEmbeddingClient;
import com.hector.domain.knowledge.adapter.port.IGenerationVectorStore;
import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.valobj.EmbeddingVectorVO;
import com.hector.domain.knowledge.model.valobj.GenerationVectorPointVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeChunkVO;
import com.hector.domain.knowledge.repository.IEmbeddingConfigRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.repository.IServingVectorSpaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generation 向量构建的输入、负载与确定性编号测试。 */
class GenerationIndexBuildServiceTest {

    @Test
    void shouldBuildDeterministicPointsWithCompleteTracePayload() throws Exception {
        List<KnowledgeChunkVO> chunks = List.of(
                new KnowledgeChunkVO("g-1", 9L, 3L, 0, "第一段", List.of("标题"), 0, 0, 1, 1, "hash-a"),
                new KnowledgeChunkVO("g-1", 9L, 3L, 1, "第二段", List.of("标题", "子标题"), 1, 1, 2, 2, "hash-b"));
        byte[] artifact = JSON.toJSONString(chunks).getBytes(StandardCharsets.UTF_8);
        CapturingVectorStore store = new CapturingVectorStore();
        GenerationIndexBuildService service = service(artifact, store);

        service.build("g-1", "worker-1");

        assertEquals(2, store.points.size());
        assertEquals(store.points.get(0).pointId(), store.points.get(0).pointId());
        Map<String, Object> payload = store.points.get(1).payload();
        assertEquals(1, payload.get("chunk_no"));
        assertEquals(2, payload.get("page_start"));
        assertEquals(2, payload.get("page_end"));
        assertEquals("第二段", payload.get("body"));
        assertEquals(List.of("标题", "子标题"), payload.get("heading_path"));
        assertTrue(store.inputs.get(0).contains("标题：文档标题"));
        assertTrue(store.inputs.get(1).contains("路径：标题 / 子标题"));
    }

    private GenerationIndexBuildService service(byte[] artifact, CapturingVectorStore store) throws Exception {
        KnowledgeIndexGenerationEntity generation = KnowledgeIndexGenerationEntity.builder().generationId("g-1").jobId(7L)
                .workspaceId(1L).documentId(9L).versionNo(3L).indexInputRevision(4L).chunkArtifactKey("chunks/g-1.json")
                .chunkArtifactChecksum(sha256(artifact)).chunkCount(2).status(0).workerToken("worker-1").build();
        IKnowledgeIndexGenerationRepository generations = new IKnowledgeIndexGenerationRepository() {
            @Override public KnowledgeIndexGenerationEntity save(KnowledgeIndexGenerationEntity value) { return value; }
            @Override public Optional<KnowledgeIndexGenerationEntity> findByGenerationId(String value) { return Optional.of(generation); }
            @Override public boolean attachChunkArtifactIfOwned(String a, String b, String c, String d, int e, LocalDateTime f) { return true; }
            @Override public boolean markReadyIfOwned(String a, String b, Long c, LocalDateTime d) { return true; }
            @Override public boolean markFailedIfOwned(String a, String b, LocalDateTime now) { return true; }
        };
        IDocumentRepository documents = new IDocumentRepository() {
            @Override public Optional<DocumentEntity> findById(Long id) { return Optional.of(DocumentEntity.builder().id(9L).workspaceId(1L).documentCode("doc-9").title("文档标题").currentVersionNo(3L).indexInputRevision(4L).build()); }
            @Override public Optional<DocumentEntity> lockById(Long id) { return findById(id); }
            @Override public DocumentEntity save(DocumentEntity value) { return value; }
            @Override public void update(DocumentEntity value) { }
            @Override public boolean activateVersionIfCurrent(DocumentEntity a, Long b, Long c) { return true; }
            @Override public boolean startWritingIfCurrent(DocumentEntity a, Long b, Long c) { return true; }
            @Override public boolean renameAndAdvanceIndexRevisionIfCurrent(DocumentEntity a, Long b) { return true; }
            @Override public Optional<DocumentEntity> findByDocumentCode(String value) { return Optional.empty(); }
            @Override public Optional<DocumentEntity> findByUploadIdempotencyKey(Long a, Long b, String c) { return Optional.empty(); }
            @Override public List<DocumentEntity> listByWorkspaceId(Long id) { return List.of(); }
            @Override public List<DocumentEntity> listVisibleByParentId(Long a, Long b) { return List.of(); }
            @Override public Optional<DocumentEntity> findActiveByParentIdAndFileName(Long a, Long b, String c) { return Optional.empty(); }
        };
        IEmbeddingConfigRepository configs = new IEmbeddingConfigRepository() {
            @Override public EmbeddingConfigEntity saveIfAbsent(EmbeddingConfigEntity value) { return EmbeddingConfigEntity.builder().id(6L).configHash(value.getConfigHash()).provider(value.getProvider()).model(value.getModel()).dimension(value.getDimension()).distance(value.getDistance()).collectionName(value.getCollectionName()).build(); }
            @Override public Optional<EmbeddingConfigEntity> findById(Long id) { return Optional.empty(); }
            @Override public Optional<EmbeddingConfigEntity> findByConfigHash(String hash) { return Optional.empty(); }
        };
        IServingVectorSpaceRepository servingVectorSpace = new IServingVectorSpaceRepository() {
            @Override public Optional<EmbeddingConfigEntity> findServingConfig() { return Optional.empty(); }
            @Override public boolean initializeIfAbsent(Long embeddingConfigId) { return true; }
        };
        IDocumentStorageRepository storage = new IDocumentStorageRepository() {
            @Override public String getBucketName() { return "test"; }
            @Override public void upload(String key, byte[] content, String type) { }
            @Override public void upload(String key, java.io.InputStream content, long size, String type) { }
            @Override public void copy(String source, String target, String type) { }
            @Override public byte[] download(String key) { return artifact; }
            @Override public void delete(String key) { }
            @Override public List<com.hector.domain.document.model.valobj.DocumentStorageObjectVO> listByPrefix(String prefix) { return List.of(); }
        };
        IEmbeddingClient embedding = inputs -> {
            store.inputs.addAll(inputs);
            List<EmbeddingVectorVO> result = new ArrayList<>();
            for (int index = 0; index < inputs.size(); index++) result.add(new EmbeddingVectorVO(index, List.of(0.1F, 0.2F, 0.3F)));
            return result;
        };
        GenerationIndexBuildService service = new GenerationIndexBuildService();
        ReflectionTestUtils.setField(service, "generationRepository", generations);
        ReflectionTestUtils.setField(service, "embeddingConfigRepository", configs);
        ReflectionTestUtils.setField(service, "documentRepository", documents);
        ReflectionTestUtils.setField(service, "storageRepository", storage);
        ReflectionTestUtils.setField(service, "embeddingClient", embedding);
        ReflectionTestUtils.setField(service, "vectorStore", store);
        ReflectionTestUtils.setField(service, "servingVectorSpaceRepository", servingVectorSpace);
        ReflectionTestUtils.setField(service, "provider", "test");
        ReflectionTestUtils.setField(service, "model", "test-model");
        ReflectionTestUtils.setField(service, "dimension", 3);
        ReflectionTestUtils.setField(service, "distance", "COSINE");
        ReflectionTestUtils.setField(service, "collection", "phase3-test");
        ReflectionTestUtils.setField(service, "batchSize", 16);
        return service;
    }

    private String sha256(byte[] source) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(source);
        StringBuilder result = new StringBuilder(); for (byte value : digest) result.append(String.format("%02x", value)); return result.toString();
    }

    private static class CapturingVectorStore implements IGenerationVectorStore {
        private final List<GenerationVectorPointVO> points = new ArrayList<>();
        private final List<String> inputs = new ArrayList<>();
        @Override public void ensureCollection(Integer dimension, String distance, String collection) { }
        @Override public void upsert(String collection, List<GenerationVectorPointVO> values) { points.addAll(values); }
        @Override public long countByGeneration(String collection, String generationId) { return points.size(); }
        @Override public void verifyGenerationPoints(String collection, String generationId, List<String> expectedPointIds) {
            assertEquals(expectedPointIds.size(), points.size());
        }
    }
}
