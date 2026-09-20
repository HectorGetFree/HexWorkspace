package com.hector.domain.knowledge.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.model.valobj.FolderStatusVO;
import com.hector.domain.folder.repository.IFolderRepository;
import com.hector.domain.knowledge.adapter.port.IQueryEmbeddingClient;
import com.hector.domain.knowledge.adapter.port.IRetrievalVectorStore;
import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexStateEntity;
import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.RetrievalResultVO;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 可信检索必须以 MySQL 状态过滤 Qdrant 候选的单元测试。 */
class KnowledgeRetrievalServiceImplTest {

    @Test
    void shouldDropCandidateWhenFolderHasBeenDeleted() throws Exception {
        IWorkspaceRepository workspaces = mock(IWorkspaceRepository.class);
        IDocumentRepository documents = mock(IDocumentRepository.class);
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
        inject(service, "folderRepository", folders);
        inject(service, "knowledgeIndexStateRepository", states);
        inject(service, "knowledgeIndexGenerationRepository", generations);
        inject(service, "servingVectorSpaceRepository", serving);
        inject(service, "queryEmbeddingClient", embedding);
        inject(service, "retrievalVectorStore", vectors);

        RetrievalResultVO result = service.retrieve(new RetrievalContextVO(2L, 1L), "查询", null);
        assertEquals(0, result.hits().size());
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
