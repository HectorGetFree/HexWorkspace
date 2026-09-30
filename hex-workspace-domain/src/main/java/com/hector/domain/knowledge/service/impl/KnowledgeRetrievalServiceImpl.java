package com.hector.domain.knowledge.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.repository.IFolderRepository;
import com.hector.domain.knowledge.adapter.port.IQueryEmbeddingClient;
import com.hector.domain.knowledge.adapter.port.IRetrievalVectorStore;
import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexStateEntity;
import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.RetrievalHitVO;
import com.hector.domain.knowledge.model.valobj.RetrievalOptionsVO;
import com.hector.domain.knowledge.model.valobj.RetrievalResultVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexStateRepository;
import com.hector.domain.knowledge.repository.IServingVectorSpaceRepository;
import com.hector.domain.knowledge.service.IKnowledgeRetrievalService;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

    @Override
    public RetrievalResultVO retrieve(RetrievalContextVO context, String query, RetrievalOptionsVO options) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索内容不能为空");
        }
        RetrievalOptionsVO normalizedOptions = (options == null
                ? RetrievalOptionsVO.defaults() : options).normalized();
        log.info("可信知识检索开始 userId:{} workspaceId:{}", context.userId(), context.workspaceId());

        WorkspaceEntity workspace = requireAccessibleWorkspace(context);
        EmbeddingConfigEntity servingConfig = requireServingConfig();
        List<Float> queryVector = queryEmbeddingClient.embedQuery(servingConfig, query.trim());
        validateQueryVector(queryVector, servingConfig.getDimension());

        List<RetrievalCandidateVO> candidates = retrievalVectorStore.searchCandidates(
                servingConfig.getCollectionName(), workspace.getId(), queryVector, normalizedOptions.candidateLimit());
        List<RetrievalHitVO> authorizedHits = validateCandidates(context, servingConfig, candidates);
        List<RetrievalHitVO> selectedHits = limitHits(authorizedHits, normalizedOptions);

        // 返回前重读权威状态，阻断检索过程中发生的删除、移动、版本/Generation 切换。
        EmbeddingConfigEntity finalServingConfig = requireServingConfig();
        if (!Objects.equals(servingConfig.getId(), finalServingConfig.getId())) {
            return RetrievalResultVO.empty();
        }
        Set<String> selectedKeys = selectedHits.stream().map(this::hitKey).collect(Collectors.toSet());
        List<RetrievalHitVO> finalHits = validateCandidates(context, finalServingConfig, candidates).stream()
                .filter(hit -> selectedKeys.contains(hitKey(hit)))
                .toList();
        log.info("可信知识检索完成 userId:{} workspaceId:{} candidateCount:{} hitCount:{}",
                context.userId(), context.workspaceId(), candidates.size(), finalHits.size());
        return new RetrievalResultVO(limitHits(finalHits, normalizedOptions));
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

    private String hitKey(RetrievalHitVO hit) {
        return hit.documentId() + ":" + hit.generationId() + ":" + hit.chunkNo();
    }
}
