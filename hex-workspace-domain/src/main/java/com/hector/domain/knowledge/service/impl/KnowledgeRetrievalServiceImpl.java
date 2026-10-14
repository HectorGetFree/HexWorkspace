package com.hector.domain.knowledge.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.repository.IFolderRepository;
import com.hector.domain.knowledge.adapter.port.IQueryEmbeddingClient;
import com.hector.domain.knowledge.adapter.port.IRetrievalReranker;
import com.hector.domain.knowledge.adapter.port.IRetrievalVectorStore;
import com.hector.domain.knowledge.adapter.port.IRetrievalSparseStore;
import com.hector.domain.knowledge.service.ReciprocalRankFusion;
import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexStateEntity;
import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalCandidateRankVO;
import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.RetrievalDiagnosticsVO;
import com.hector.domain.knowledge.model.valobj.RetrievalExecutionVO;
import com.hector.domain.knowledge.model.valobj.RetrievalHitVO;
import com.hector.domain.knowledge.model.valobj.RetrievalOptionsVO;
import com.hector.domain.knowledge.model.valobj.RetrievalResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalRerankCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalRerankScoreVO;
import com.hector.domain.knowledge.model.valobj.RetrievalTimingVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexStateRepository;
import com.hector.domain.knowledge.repository.IServingVectorSpaceRepository;
import com.hector.domain.knowledge.service.IKnowledgeRetrievalService;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可信知识检索服务。
 * Qdrant 只负责候选召回，最终结果始终由 MySQL 的 Workspace、目录、文档和 Generation 状态复核。
 */
@Service
public class KnowledgeRetrievalServiceImpl implements IKnowledgeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalServiceImpl.class);
    private static final int GENERATION_READY = 1;

    @Resource
    private IWorkspaceRepository workspaceRepository;
    @Resource
    private IDocumentRepository documentRepository;
    @Resource
    private IDocumentVersionRepository documentVersionRepository;
    @Resource
    private IFolderRepository folderRepository;
    @Resource
    private IKnowledgeIndexStateRepository knowledgeIndexStateRepository;
    @Resource
    private IKnowledgeIndexGenerationRepository knowledgeIndexGenerationRepository;
    @Resource
    private IServingVectorSpaceRepository servingVectorSpaceRepository;
    @Resource
    private IQueryEmbeddingClient queryEmbeddingClient;
    @Resource
    private IRetrievalVectorStore retrievalVectorStore;
    @Resource
    private IRetrievalReranker retrievalReranker;
    @Resource
    private IRetrievalSparseStore retrievalSparseStore;
    @Value("${hexworkspace.knowledge.hybrid.enabled:true}")
    private boolean hybridEnabled;
    /** 1.0 保持纯 Rerank；低于 1.0 时以原始 Qdrant 排名作为稳定锚点。 */
    @Value("${hexworkspace.knowledge.rerank.rerank-weight:1.0}")
    private double rerankWeight = 1D;

    @Override
    public RetrievalResultVO retrieve(RetrievalContextVO context, String query, RetrievalOptionsVO options) {
        return retrieveWithTrace(context, query, options).result();
    }

    @Override
    public RetrievalExecutionVO retrieveWithTrace(RetrievalContextVO context, String query, RetrievalOptionsVO options) {
        return execute(context, query, options, false, true);
    }

    @Override
    public RetrievalExecutionVO retrieveWithEvaluationTrace(
            RetrievalContextVO context, String query, RetrievalOptionsVO options) {
        return execute(context, query, options, true, true);
    }

    @Override
    public RetrievalExecutionVO retrieveCandidatesWithEvaluationTrace(
            RetrievalContextVO context, String query, RetrievalOptionsVO options) {
        return execute(context, query, options, true, false);
    }

    private RetrievalExecutionVO execute(
            RetrievalContextVO context, String query, RetrievalOptionsVO options,
            boolean includeDiagnostics, boolean applyRerank) {
        return execute(context, query, options, includeDiagnostics, applyRerank, hybridEnabled, false);
    }

    @Override
    public RetrievalExecutionVO retrieveWithEvaluationProfile(RetrievalContextVO context, String query,
            RetrievalOptionsVO options, boolean hybrid) {
        return execute(context, query, options, true, true, hybrid, true);
    }

    private RetrievalExecutionVO execute(RetrievalContextVO context, String query, RetrievalOptionsVO options,
            boolean includeDiagnostics, boolean applyRerank, boolean useHybrid, boolean evaluationDocumentLimit) {
        long totalStartedAt = System.nanoTime();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索内容不能为空");
        }
        RetrievalOptionsVO requestedOptions = options == null ? RetrievalOptionsVO.defaults() : options;
        RetrievalOptionsVO normalizedOptions = requestedOptions.normalized();
        log.info("可信知识检索开始 userId:{} workspaceId:{}", context.userId(), context.workspaceId());

        long initialAuthorityStartedAt = System.nanoTime();
        WorkspaceEntity workspace = requireAccessibleWorkspace(context);
        EmbeddingConfigEntity servingConfig = requireServingConfig();
        double initialAuthorityMs = elapsedMs(initialAuthorityStartedAt);
        long embeddingStartedAt = System.nanoTime();
        List<Float> queryVector = queryEmbeddingClient.embedQuery(servingConfig, query.trim());
        validateQueryVector(queryVector, servingConfig.getDimension());
        double embeddingMs = elapsedMs(embeddingStartedAt);

        long qdrantStartedAt = System.nanoTime();
        List<RetrievalCandidateVO> candidates = retrievalVectorStore.searchCandidates(
                servingConfig.getCollectionName(), workspace.getId(), queryVector, normalizedOptions.candidateLimit());
        List<RetrievalCandidateRankVO> qdrantCandidateRanks = includeDiagnostics ? candidateRanks(candidates) : List.of();
        double qdrantMs = elapsedMs(qdrantStartedAt);
        List<RetrievalCandidateRankVO> sparseRanks = List.of();
        double hybridMs = 0D;
        // 1. 保留 Dense-only 评测入口；正式检索先融合，再统一执行权限与最终状态复核。
        if (applyRerank && useHybrid) {
            long sparseStartedAt = System.nanoTime();
            try {
                var sparse = retrievalSparseStore.searchCandidates(workspace.getId(), servingConfig.getId(),
                        query.trim(), normalizedOptions.candidateLimit());
                if (includeDiagnostics) sparseRanks = candidateRanks(sparse);
                if (!sparse.isEmpty()) {
                    candidates = ReciprocalRankFusion.fuse(candidates, sparse, normalizedOptions.candidateLimit());
                }
                log.info("混合召回 workspaceId:{} sparseCount:{} fusedCount:{} sparseMs:{}",
                        workspace.getId(), sparse.size(), candidates.size(), elapsedMs(sparseStartedAt));
            } catch (RuntimeException e) {
                log.warn("稀疏召回不可用，回退 Dense workspaceId:{}", workspace.getId(), e);
            }
            hybridMs = elapsedMs(sparseStartedAt);
        }
        List<RetrievalCandidateRankVO> fusedRanks = includeDiagnostics ? candidateRanks(candidates) : List.of();
        long authorityValidationStartedAt = System.nanoTime();
        List<RetrievalHitVO> authorizedHits = validateCandidates(context, servingConfig, candidates);
        List<RetrievalCandidateRankVO> authorityValidRanks = includeDiagnostics ? hitRanks(authorizedHits) : List.of();
        double authorityInitialMs = initialAuthorityMs + elapsedMs(authorityValidationStartedAt);

        long rerankStartedAt = System.nanoTime();
        RerankExecution rerankExecution = applyRerank
                ? rerankAuthorizedHits(query.trim(), authorizedHits)
                : new RerankExecution(authorizedHits, List.of());
        double rerankMs = elapsedMs(rerankStartedAt);
        // 普通生产仅保留总 TopK；受控历史 A/B 入口显式保留冻结的文档限额。
        List<RetrievalHitVO> selectedHits = evaluationDocumentLimit
                ? limitHits(rerankExecution.hits(), normalizedOptions)
                : rerankExecution.hits().stream().limit(normalizedOptions.resultLimit()).toList();

        // 返回前重读权威状态，阻断检索过程中发生的删除、移动、版本/Generation 切换。
        long authorityRecheckStartedAt = System.nanoTime();
        EmbeddingConfigEntity finalServingConfig = requireServingConfig();
        RetrievalResultVO result;
        if (!Objects.equals(servingConfig.getId(), finalServingConfig.getId())) {
            result = RetrievalResultVO.empty();
        } else {
            Map<String, RetrievalHitVO> currentHits = validateCandidates(context, finalServingConfig, candidates).stream()
                    .collect(Collectors.toMap(this::hitKey, hit -> hit, (first, ignored) -> first));
            // 最终权威复核只能剔除已失效候选，不能重置 Rerank 已决定的稳定顺序。
            result = new RetrievalResultVO(selectedHits.stream()
                    .map(hit -> currentHits.get(hitKey(hit)))
                    .filter(Objects::nonNull)
                    .toList());
        }
        double authorityRecheckMs = elapsedMs(authorityRecheckStartedAt);
        RetrievalTimingVO timing = new RetrievalTimingVO(
                embeddingMs, qdrantMs, authorityInitialMs, rerankMs, authorityRecheckMs, elapsedMs(totalStartedAt), hybridMs);
        log.info("可信知识检索完成 userId:{} workspaceId:{} candidateCount:{} hitCount:{}",
                context.userId(), context.workspaceId(), candidates.size(), result.hits().size());
        log.info("可信知识检索阶段耗时 workspaceId:{} embeddingMs:{} qdrantMs:{} authorityInitialMs:{} rerankMs:{} authorityRecheckMs:{} totalMs:{}",
                context.workspaceId(), timing.embeddingMs(), timing.qdrantMs(), timing.authorityInitialMs(),
                timing.rerankMs(), timing.authorityRecheckMs(), timing.totalMs());
        RetrievalDiagnosticsVO diagnostics = includeDiagnostics
                ? new RetrievalDiagnosticsVO(
                        qdrantCandidateRanks, authorityValidRanks, rerankExecution.documentRanks(), hitRanks(result.hits()),
                        sparseRanks, fusedRanks, rerankExecution.chunkRanks())
                : RetrievalDiagnosticsVO.empty();
        return new RetrievalExecutionVO(result, timing, diagnostics);
    }

    private double elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000D;
    }

    /**
     * Rerank 只处理已通过首次 MySQL 权威校验的候选。服务异常或返回不完整时回退向量顺序，
     * 保证外部模型故障不改变检索可用性。
     */
    private RerankExecution rerankAuthorizedHits(String query, List<RetrievalHitVO> authorizedHits) {
        if (authorizedHits.size() < 2 || retrievalReranker == null || !retrievalReranker.isEnabled()) {
            return new RerankExecution(authorizedHits, List.of());
        }
        try {
            List<RetrievalRerankCandidateVO> candidates = new ArrayList<>();
            for (int index = 0; index < authorizedHits.size(); index++) {
                candidates.add(new RetrievalRerankCandidateVO(index, toRerankText(authorizedHits.get(index))));
            }
            List<RetrievalRerankScoreVO> scores = retrievalReranker.rerank(query, candidates);
            if (scores == null || scores.size() != authorizedHits.size()) {
                throw new IllegalStateException("Rerank 返回候选数不完整");
            }
            Map<Integer, Double> scoresByIndex = new HashMap<>();
            for (RetrievalRerankScoreVO score : scores) {
                if (score == null || score.index() == null || score.index() < 0 || score.index() >= authorizedHits.size()
                        || score.relevanceScore() == null || !Double.isFinite(score.relevanceScore())
                        || scoresByIndex.putIfAbsent(score.index(), score.relevanceScore()) != null) {
                    throw new IllegalStateException("Rerank 返回候选标识或分数无效");
                }
            }
            if (scoresByIndex.size() != authorizedHits.size()) {
                throw new IllegalStateException("Rerank 返回候选标识不完整");
            }
            List<Integer> rerankIndexes = new ArrayList<>(scoresByIndex.keySet());
            rerankIndexes.sort(Comparator
                    .comparing((Integer index) -> scoresByIndex.get(index), Comparator.reverseOrder())
                    .thenComparing(index -> authorizedHits.get(index).score(), Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparingInt(Integer::intValue));
            Map<Integer, Integer> rerankRanks = new HashMap<>();
            for (int rank = 0; rank < rerankIndexes.size(); rank++) {
                rerankRanks.put(rerankIndexes.get(rank), rank + 1);
            }
            double effectiveRerankWeight = normalizedRerankWeight();
            List<Integer> indexes = new ArrayList<>(rerankIndexes);
            indexes.sort(Comparator
                    .comparing((Integer index) -> blendedRankScore(
                            index + 1, rerankRanks.get(index), authorizedHits.size(), effectiveRerankWeight))
                    .reversed()
                    .thenComparing(index -> scoresByIndex.get(index), Comparator.reverseOrder())
                    .thenComparing(index -> authorizedHits.get(index).score(), Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparingInt(Integer::intValue));
            log.info("可信知识检索 Rerank 完成 candidateCount:{} rerankWeight:{}", authorizedHits.size(), effectiveRerankWeight);
            List<RetrievalHitVO> rankedHits = indexes.stream().map(authorizedHits::get).toList();
            Map<Long, RetrievalCandidateRankVO> firstByDocument = new LinkedHashMap<>();
            List<RetrievalCandidateRankVO> chunkRanks = new ArrayList<>();
            for (int rank = 0; rank < indexes.size(); rank++) {
                int index = indexes.get(rank);
                RetrievalHitVO hit = authorizedHits.get(index);
                chunkRanks.add(new RetrievalCandidateRankVO(hit.documentId(), hit.generationId(), hit.chunkNo(),
                        rank + 1, scoresByIndex.get(index)));
                firstByDocument.putIfAbsent(hit.documentId(),
                        new RetrievalCandidateRankVO(hit.documentId(), rank + 1, scoresByIndex.get(index)));
            }
            return new RerankExecution(rankedHits, List.copyOf(firstByDocument.values()), List.copyOf(chunkRanks));
        } catch (RuntimeException exception) {
            log.warn("可信知识检索 Rerank 失败，回退向量排序 candidateCount:{}", authorizedHits.size(), exception);
            return new RerankExecution(authorizedHits, List.of());
        }
    }

    private String toRerankText(RetrievalHitVO hit) {
        String heading = hit.headingPath() == null || hit.headingPath().isEmpty()
                ? "" : String.join(" / ", hit.headingPath());
        return "标题：" + Objects.toString(hit.title(), "")
                + "\n章节：" + heading
                + "\n正文：" + Objects.toString(hit.content(), "");
    }

    /**
     * Provider relevance score 仅在单次请求内可比较，故按名次归一化融合，避免跨请求或模型版本的分值尺度漂移。
     */
    private double blendedRankScore(int vectorRank, int rerankRank, int candidateCount, double effectiveRerankWeight) {
        if (candidateCount <= 1) {
            return 1D;
        }
        double vectorRankScore = (candidateCount - vectorRank) / (double) (candidateCount - 1);
        double rerankRankScore = (candidateCount - rerankRank) / (double) (candidateCount - 1);
        return effectiveRerankWeight * rerankRankScore + (1D - effectiveRerankWeight) * vectorRankScore;
    }

    private double normalizedRerankWeight() {
        if (Double.isFinite(rerankWeight) && rerankWeight >= 0D && rerankWeight <= 1D) {
            return rerankWeight;
        }
        log.warn("可信知识检索 Rerank 权重非法，回退纯 Rerank configuredWeight:{}", rerankWeight);
        return 1D;
    }

    private WorkspaceEntity requireAccessibleWorkspace(RetrievalContextVO context) {
        WorkspaceEntity workspace = workspaceRepository.findById(context.workspaceId())
                .orElseThrow(() -> new IllegalArgumentException("Workspace 不存在"));
        if (!workspace.isActive() || !Objects.equals(workspace.getOwnerUserId(), context.userId())) {
            throw new IllegalStateException("当前用户无权检索此 Workspace");
        }
        return workspace;
    }

    private EmbeddingConfigEntity requireServingConfig() {
        return servingVectorSpaceRepository.findServingConfig()
                .orElseThrow(() -> new IllegalStateException("当前未初始化权威向量空间，无法检索"));
    }

    /** 一次性加载同一 Workspace 的权威元数据，禁止信任 Qdrant Payload。 */
    private List<RetrievalHitVO> validateCandidates(
            RetrievalContextVO context,
            EmbeddingConfigEntity servingConfig,
            List<RetrievalCandidateVO> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        requireAccessibleWorkspace(context);
        Map<Long, DocumentEntity> documents = documentRepository.listByWorkspaceId(context.workspaceId()).stream()
                .collect(Collectors.toMap(DocumentEntity::getId, document -> document));
        Map<Long, FolderEntity> folders = folderRepository.listByWorkspaceId(context.workspaceId()).stream()
                .collect(Collectors.toMap(FolderEntity::getId, folder -> folder));
        List<Long> documentIds = candidates.stream()
                .map(RetrievalCandidateVO::documentId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, KnowledgeIndexStateEntity> states = knowledgeIndexStateRepository
                .listByDocumentIds(documentIds).stream()
                .collect(Collectors.toMap(KnowledgeIndexStateEntity::getDocumentId, state -> state));
        List<String> generationIds = candidates.stream()
                .map(RetrievalCandidateVO::generationId)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        Map<String, KnowledgeIndexGenerationEntity> generations = knowledgeIndexGenerationRepository
                .listByGenerationIds(generationIds).stream()
                .collect(Collectors.toMap(KnowledgeIndexGenerationEntity::getGenerationId, generation -> generation));

        return candidates.stream()
                .filter(candidate -> isAuthorizedCandidate(
                        candidate, context.workspaceId(), servingConfig, documents, folders, states, generations))
                .map(candidate -> toRetrievalHit(candidate, documents.get(candidate.documentId())))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RetrievalHitVO::score, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** 将索引内部版本号解析为文档版本的不可变主键，供 Capability 精确引用。 */
    private RetrievalHitVO toRetrievalHit(RetrievalCandidateVO candidate, DocumentEntity document) {
        DocumentVersionEntity version = documentVersionRepository
                .findByDocumentIdAndVersionNo(candidate.documentId(), candidate.versionNo())
                .orElse(null);
        if (version == null || version.getId() == null) {
            return null;
        }
        return new RetrievalHitVO(
                candidate.documentId(), document.getDocumentCode(), candidate.versionNo(), version.getId(),
                candidate.generationId(), candidate.chunkNo(), document.getTitle(), candidate.headingPath(),
                candidate.content(), candidate.pageStart(), candidate.pageEnd(), candidate.score());
    }

    private boolean isAuthorizedCandidate(
            RetrievalCandidateVO candidate,
            Long workspaceId,
            EmbeddingConfigEntity servingConfig,
            Map<Long, DocumentEntity> documents,
            Map<Long, FolderEntity> folders,
            Map<Long, KnowledgeIndexStateEntity> states,
            Map<String, KnowledgeIndexGenerationEntity> generations) {
        if (candidate == null || !Objects.equals(candidate.workspaceId(), workspaceId) || candidate.documentId() == null
                || candidate.versionNo() == null || candidate.indexInputRevision() == null || candidate.generationId() == null
                || candidate.chunkNo() == null || candidate.chunkNo() < 0 || candidate.content() == null || candidate.score() == null
                || !Double.isFinite(candidate.score())) {
            return false;
        }
        DocumentEntity document = documents.get(candidate.documentId());
        KnowledgeIndexStateEntity state = states.get(candidate.documentId());
        KnowledgeIndexGenerationEntity generation = generations.get(candidate.generationId());
        return document != null && document.isActive() && Objects.equals(document.getWorkspaceId(), workspaceId)
                && Objects.equals(document.getCurrentVersionNo(), candidate.versionNo())
                && Objects.equals(document.getIndexInputRevision(), candidate.indexInputRevision())
                && isFolderChainActive(document.getParentId(), workspaceId, folders)
                && state != null && Objects.equals(state.getActiveGenerationId(), candidate.generationId())
                && generation != null && generation.getStatus() == GENERATION_READY
                && Objects.equals(generation.getWorkspaceId(), workspaceId)
                && Objects.equals(generation.getDocumentId(), candidate.documentId())
                && Objects.equals(generation.getVersionNo(), candidate.versionNo())
                && Objects.equals(generation.getIndexInputRevision(), candidate.indexInputRevision())
                && Objects.equals(generation.getEmbeddingConfigId(), servingConfig.getId());
    }

    /** 从叶目录向上检查到唯一有效根目录；环、跨 Workspace 和缺失节点一律拒绝。 */
    private boolean isFolderChainActive(Long folderId, Long workspaceId, Map<Long, FolderEntity> folders) {
        Set<Long> visited = new HashSet<>();
        Long currentId = folderId;
        while (currentId != null && visited.add(currentId)) {
            FolderEntity folder = folders.get(currentId);
            if (folder == null || !folder.isActive() || !Objects.equals(folder.getWorkspaceId(), workspaceId)) {
                return false;
            }
            if (folder.isRoot()) {
                return folder.getParentId() == null;
            }
            currentId = folder.getParentId();
        }
        return false;
    }

    private void validateQueryVector(List<Float> vector, Integer dimension) {
        if (vector == null || dimension == null || vector.size() != dimension
                || vector.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
            throw new IllegalStateException("查询 Embedding 向量不符合权威向量空间约束");
        }
    }

    private List<RetrievalHitVO> limitHits(List<RetrievalHitVO> hits, RetrievalOptionsVO options) {
        Map<Long, Integer> perDocument = new HashMap<>();
        List<RetrievalHitVO> selected = new ArrayList<>();
        for (RetrievalHitVO hit : hits) {
            int currentCount = perDocument.getOrDefault(hit.documentId(), 0);
            if (currentCount >= options.maxChunksPerDocument()) {
                continue;
            }
            selected.add(hit);
            perDocument.put(hit.documentId(), currentCount + 1);
            if (selected.size() == options.resultLimit()) {
                break;
            }
        }
        return selected;
    }

    /**
     * 保留候选 Chunk 身份，供离线评测在与 BM25 相同的 Chunk 粒度做候选融合。
     * 正常用户检索不读取 diagnostics，故不改变生产召回、授权或排序结果。
     */
    private List<RetrievalCandidateRankVO> candidateRanks(List<RetrievalCandidateVO> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RetrievalCandidateRankVO> ranks = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            RetrievalCandidateVO candidate = candidates.get(index);
            if (candidate != null && candidate.documentId() != null && candidate.generationId() != null
                    && candidate.chunkNo() != null) {
                ranks.add(new RetrievalCandidateRankVO(candidate.documentId(), candidate.generationId(),
                        candidate.chunkNo(), index + 1, candidate.score()));
            }
        }
        return List.copyOf(ranks);
    }

    private List<RetrievalCandidateRankVO> hitRanks(List<RetrievalHitVO> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<RetrievalCandidateRankVO> ranks = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            RetrievalHitVO hit = hits.get(index);
            if (hit != null && hit.documentId() != null && hit.generationId() != null && hit.chunkNo() != null) {
                ranks.add(new RetrievalCandidateRankVO(hit.documentId(), hit.generationId(), hit.chunkNo(),
                        index + 1, hit.score()));
            }
        }
        return List.copyOf(ranks);
    }

    private String hitKey(RetrievalHitVO hit) {
        return hit.documentId() + ":" + hit.generationId() + ":" + hit.chunkNo();
    }

    private record RerankExecution(
            List<RetrievalHitVO> hits,
            List<RetrievalCandidateRankVO> documentRanks,
            List<RetrievalCandidateRankVO> chunkRanks) {
        private RerankExecution(List<RetrievalHitVO> hits, List<RetrievalCandidateRankVO> ranks) {
            this(hits, ranks, List.of());
        }
    }
}
