package com.hector.domain.knowledge.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.model.valobj.FolderStatusVO;
import com.hector.domain.folder.repository.IFolderRepository;
import com.hector.domain.knowledge.adapter.port.IQueryEmbeddingClient;
import com.hector.domain.knowledge.adapter.port.IRetrievalReranker;
import com.hector.domain.knowledge.adapter.port.IRetrievalVectorStore;
import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexStateEntity;
import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.RetrievalExecutionVO;
import com.hector.domain.knowledge.model.valobj.RetrievalOptionsVO;
import com.hector.domain.knowledge.model.valobj.RetrievalRerankScoreVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexStateRepository;
import com.hector.domain.knowledge.repository.IServingVectorSpaceRepository;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.model.valobj.WorkspaceStatusVO;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 可信检索必须以 MySQL 状态过滤 Qdrant 候选的单元测试。 */
class KnowledgeRetrievalServiceImplTest {

    @Test
    void shouldDropCandidateWhenFolderHasBeenDeleted() throws Exception {
        IWorkspaceRepository workspaces = mock(IWorkspaceRepository.class);
        IDocumentRepository documents = mock(IDocumentRepository.class);
        IDocumentVersionRepository versions = mock(IDocumentVersionRepository.class);
        IFolderRepository folders = mock(IFolderRepository.class);
        IKnowledgeIndexStateRepository states = mock(IKnowledgeIndexStateRepository.class);
        IKnowledgeIndexGenerationRepository generations = mock(IKnowledgeIndexGenerationRepository.class);
        IServingVectorSpaceRepository serving = mock(IServingVectorSpaceRepository.class);
        IQueryEmbeddingClient embedding = mock(IQueryEmbeddingClient.class);
        IRetrievalVectorStore vectors = mock(IRetrievalVectorStore.class);

        EmbeddingConfigEntity config = EmbeddingConfigEntity.builder().id(7L).model("test").dimension(3)
                .collectionName("test_collection").build();
        when(workspaces.findById(1L)).thenReturn(Optional.of(WorkspaceEntity.builder().id(1L).ownerUserId(2L)
                .status(WorkspaceStatusVO.ACTIVE).build()));
        when(serving.findServingConfig()).thenReturn(Optional.of(config));
        when(embedding.embedQuery(config, "查询")).thenReturn(List.of(0.1F, 0.2F, 0.3F));
        RetrievalCandidateVO candidate = new RetrievalCandidateVO("point-1", 1L, 11L, 3L, 5L,
                "generation-1", 0, List.of("标题"), "正文", 1, 1, 0.9D);
        when(vectors.searchCandidates("test_collection", 1L, List.of(0.1F, 0.2F, 0.3F), 30)).thenReturn(List.of(candidate));
        when(documents.listByWorkspaceId(1L)).thenReturn(List.of(DocumentEntity.builder().id(11L).documentCode("doc-11")
                .workspaceId(1L).parentId(21L).title("标题").status(DocumentStatusVO.ACTIVE)
                .currentVersionNo(3L).indexInputRevision(5L).build()));
        when(folders.listByWorkspaceId(1L)).thenReturn(List.of(FolderEntity.builder().id(21L).workspaceId(1L)
                .root(true).status(FolderStatusVO.DELETED).build()));
        when(states.listByDocumentIds(any())).thenReturn(List.of(KnowledgeIndexStateEntity.builder()
                .documentId(11L).activeGenerationId("generation-1").build()));
        when(generations.listByGenerationIds(any())).thenReturn(List.of(KnowledgeIndexGenerationEntity.builder()
                .generationId("generation-1").workspaceId(1L).documentId(11L).versionNo(3L)
                .indexInputRevision(5L).embeddingConfigId(7L).status(1).build()));

        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl();
        inject(service, "workspaceRepository", workspaces);
        inject(service, "documentRepository", documents);
        inject(service, "documentVersionRepository", versions);
        inject(service, "folderRepository", folders);
        inject(service, "knowledgeIndexStateRepository", states);
        inject(service, "knowledgeIndexGenerationRepository", generations);
        inject(service, "servingVectorSpaceRepository", serving);
        inject(service, "queryEmbeddingClient", embedding);
        inject(service, "retrievalVectorStore", vectors);

        RetrievalExecutionVO execution = service.retrieveWithEvaluationTrace(new RetrievalContextVO(2L, 1L), "查询", null);
        assertEquals(0, execution.result().hits().size());
        assertEquals(1, execution.diagnostics().qdrantCandidates().size());
        assertEquals(11L, execution.diagnostics().qdrantCandidates().get(0).documentId());
        assertTrue(execution.diagnostics().authorityValidCandidates().isEmpty());
        assertTrue(execution.diagnostics().finalCandidates().isEmpty());
        assertTrue(execution.timing().embeddingMs() >= 0);
        assertTrue(execution.timing().qdrantMs() >= 0);
        assertTrue(execution.timing().authorityInitialMs() >= 0);
        assertTrue(execution.timing().authorityRecheckMs() >= 0);
        assertTrue(execution.timing().totalMs() >= 0);
    }

    @Test
    void shouldRerankAuthorizedCandidatesAndKeepThatOrderAfterFinalAuthorityRecheck() throws Exception {
        IWorkspaceRepository workspaces = mock(IWorkspaceRepository.class);
        IDocumentRepository documents = mock(IDocumentRepository.class);
        IDocumentVersionRepository versions = mock(IDocumentVersionRepository.class);
        IFolderRepository folders = mock(IFolderRepository.class);
        IKnowledgeIndexStateRepository states = mock(IKnowledgeIndexStateRepository.class);
        IKnowledgeIndexGenerationRepository generations = mock(IKnowledgeIndexGenerationRepository.class);
        IServingVectorSpaceRepository serving = mock(IServingVectorSpaceRepository.class);
        IQueryEmbeddingClient embedding = mock(IQueryEmbeddingClient.class);
        IRetrievalVectorStore vectors = mock(IRetrievalVectorStore.class);
        IRetrievalReranker reranker = mock(IRetrievalReranker.class);

        EmbeddingConfigEntity config = EmbeddingConfigEntity.builder().id(7L).model("test").dimension(3)
                .collectionName("test_collection").build();
        when(workspaces.findById(1L)).thenReturn(Optional.of(WorkspaceEntity.builder().id(1L).ownerUserId(2L)
                .status(WorkspaceStatusVO.ACTIVE).build()));
        when(serving.findServingConfig()).thenReturn(Optional.of(config));
        when(embedding.embedQuery(config, "查询")).thenReturn(List.of(0.1F, 0.2F, 0.3F));
        RetrievalCandidateVO first = candidate(11L, "generation-11", 0.9D);
        RetrievalCandidateVO second = candidate(12L, "generation-12", 0.8D);
        when(vectors.searchCandidates("test_collection", 1L, List.of(0.1F, 0.2F, 0.3F), 30))
                .thenReturn(List.of(first, second));
        when(documents.listByWorkspaceId(1L)).thenReturn(List.of(
                document(11L, "doc-11"), document(12L, "doc-12")));
        when(folders.listByWorkspaceId(1L)).thenReturn(List.of(FolderEntity.builder().id(21L).workspaceId(1L)
                .root(true).status(FolderStatusVO.ACTIVE).build()));
        when(states.listByDocumentIds(any())).thenReturn(List.of(
                KnowledgeIndexStateEntity.builder().documentId(11L).activeGenerationId("generation-11").build(),
                KnowledgeIndexStateEntity.builder().documentId(12L).activeGenerationId("generation-12").build()));
        when(generations.listByGenerationIds(any())).thenReturn(List.of(
                generation(11L, "generation-11"), generation(12L, "generation-12")));
        when(versions.findByDocumentIdAndVersionNo(11L, 3L))
                .thenReturn(Optional.of(DocumentVersionEntity.builder().id(111L).build()));
        when(versions.findByDocumentIdAndVersionNo(12L, 3L))
                .thenReturn(Optional.of(DocumentVersionEntity.builder().id(112L).build()));
        when(reranker.isEnabled()).thenReturn(true);
        when(reranker.rerank(eq("查询"), any())).thenReturn(List.of(
                new RetrievalRerankScoreVO(0, 0.1D), new RetrievalRerankScoreVO(1, 0.9D)));

        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl();
        inject(service, "workspaceRepository", workspaces);
        inject(service, "documentRepository", documents);
        inject(service, "documentVersionRepository", versions);
        inject(service, "folderRepository", folders);
        inject(service, "knowledgeIndexStateRepository", states);
        inject(service, "knowledgeIndexGenerationRepository", generations);
        inject(service, "servingVectorSpaceRepository", serving);
        inject(service, "queryEmbeddingClient", embedding);
        inject(service, "retrievalVectorStore", vectors);
        inject(service, "retrievalReranker", reranker);

        RetrievalExecutionVO execution = service.retrieveWithEvaluationTrace(
                new RetrievalContextVO(2L, 1L), "查询", new RetrievalOptionsVO(30, 1, 1));

        assertEquals(1, execution.result().hits().size());
        assertEquals(12L, execution.result().hits().get(0).documentId());
        assertEquals(12L, execution.diagnostics().rerankedCandidates().get(0).documentId());
        assertEquals(0.9D, execution.diagnostics().rerankedCandidates().get(0).score());
        assertTrue(execution.timing().rerankMs() >= 0);

        inject(service, "rerankWeight", 0.25D);
        RetrievalExecutionVO blended = service.retrieveWithEvaluationTrace(
                new RetrievalContextVO(2L, 1L), "查询", new RetrievalOptionsVO(30, 1, 1));
        assertEquals(11L, blended.result().hits().get(0).documentId());

        when(reranker.rerank(eq("查询"), any())).thenThrow(new IllegalStateException("provider unavailable"));
        RetrievalExecutionVO fallback = service.retrieveWithEvaluationTrace(
                new RetrievalContextVO(2L, 1L), "查询", new RetrievalOptionsVO(30, 1, 1));
        assertEquals(11L, fallback.result().hits().get(0).documentId());
        assertTrue(fallback.diagnostics().rerankedCandidates().isEmpty());

        // C2 读取 Dense 候选时不能先调用一次生产 Rerank，否则融合实验会双重计时、双重消耗外部配额。
        clearInvocations(reranker);
        RetrievalExecutionVO candidatesOnly = service.retrieveCandidatesWithEvaluationTrace(
                new RetrievalContextVO(2L, 1L), "查询", new RetrievalOptionsVO(30, 1, 1));
        verifyNoInteractions(reranker);
        assertEquals(2, candidatesOnly.diagnostics().qdrantCandidates().size());
        assertTrue(candidatesOnly.diagnostics().rerankedCandidates().isEmpty());
    }

    private RetrievalCandidateVO candidate(Long documentId, String generationId, Double score) {
        return new RetrievalCandidateVO("point-" + documentId, 1L, documentId, 3L, 5L,
                generationId, 0, List.of("标题"), "正文", 1, 1, score);
    }

    private DocumentEntity document(Long documentId, String documentCode) {
        return DocumentEntity.builder().id(documentId).documentCode(documentCode).workspaceId(1L).parentId(21L)
                .title("标题").status(DocumentStatusVO.ACTIVE).currentVersionNo(3L).indexInputRevision(5L).build();
    }

    private KnowledgeIndexGenerationEntity generation(Long documentId, String generationId) {
        return KnowledgeIndexGenerationEntity.builder().generationId(generationId).workspaceId(1L).documentId(documentId)
                .versionNo(3L).indexInputRevision(5L).embeddingConfigId(7L).status(1).build();
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
