package com.hector.domain.knowledge.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexStateEntity;
import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.RetrievalCandidateRankVO;
import com.hector.domain.knowledge.model.valobj.RetrievalDiagnosticsVO;
import com.hector.domain.knowledge.model.valobj.RetrievalExecutionVO;
import com.hector.domain.knowledge.model.valobj.RetrievalOptionsVO;
import com.hector.domain.knowledge.model.valobj.RetrievalResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalTimingVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexStateRepository;
import com.hector.domain.knowledge.service.IKnowledgeRetrievalService;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.model.valobj.WorkspaceStatusVO;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 评测入口必须复用生产检索与 Active Generation 权威身份。 */
class RetrievalEvaluationServiceImplTest {

    @Test
    void retrievesUsingWorkspaceOwnerInsteadOfCallerSuppliedIdentity() {
        Fixture fixture = new Fixture();
        when(fixture.workspaces.findById(1L)).thenReturn(Optional.of(activeWorkspace()));
        when(fixture.retrieval.retrieveWithEvaluationTrace(eq(new RetrievalContextVO(9L, 1L)), eq("问题"),
                eq(new RetrievalOptionsVO(60, null, 5))))
                .thenReturn(new RetrievalExecutionVO(RetrievalResultVO.empty(), new RetrievalTimingVO(1, 2, 3, 4, 10)));

        fixture.service.retrieve(1L, "问题", 60, 5);

        verify(fixture.retrieval).retrieveWithEvaluationTrace(new RetrievalContextVO(9L, 1L), "问题",
                new RetrievalOptionsVO(60, null, 5));
    }

    @Test
    void readinessRequiresActiveGenerationWithCurrentDocumentIdentity() {
        Fixture fixture = new Fixture();
        DocumentEntity document = DocumentEntity.builder().id(11L).workspaceId(1L).documentCode("doc-a")
                .status(DocumentStatusVO.ACTIVE).currentVersionNo(3L).indexInputRevision(7L).build();
        when(fixture.workspaces.findById(1L)).thenReturn(Optional.of(activeWorkspace()));
        when(fixture.documents.findByDocumentCode("doc-a")).thenReturn(Optional.of(document));
        when(fixture.versions.findByDocumentIdAndVersionNo(11L, 3L))
                .thenReturn(Optional.of(DocumentVersionEntity.builder().id(31L).build()));
        when(fixture.states.findByDocumentId(11L)).thenReturn(Optional.of(
                KnowledgeIndexStateEntity.builder().documentId(11L).activeGenerationId("gen-a").build()));
        when(fixture.generations.findByGenerationId("gen-a")).thenReturn(Optional.of(
                KnowledgeIndexGenerationEntity.builder().generationId("gen-a").workspaceId(1L).documentId(11L)
                        .versionNo(3L).indexInputRevision(7L).status(1).build()));

        var readiness = fixture.service.readiness(1L, List.of("doc-a")).get(0);

        assertTrue(readiness.ready());
        assertTrue(readiness.versionId().equals(31L));
    }

    @Test
    void mapsInternalCandidateRanksToDocumentCodesForEvaluationOnly() {
        Fixture fixture = new Fixture();
        when(fixture.workspaces.findById(1L)).thenReturn(Optional.of(activeWorkspace()));
        when(fixture.documents.listByWorkspaceId(1L)).thenReturn(List.of(
                DocumentEntity.builder().id(11L).documentCode("doc-a").build()));
        RetrievalExecutionVO execution = new RetrievalExecutionVO(
                RetrievalResultVO.empty(), new RetrievalTimingVO(1, 2, 3, 4, 10),
                new RetrievalDiagnosticsVO(
                        List.of(new RetrievalCandidateRankVO(11L, 1, 0.9D)),
                        List.of(new RetrievalCandidateRankVO(11L, 1, 0.9D)),
                        List.of()));
        when(fixture.retrieval.retrieveWithEvaluationTrace(eq(new RetrievalContextVO(9L, 1L)), eq("问题"),
                eq(new RetrievalOptionsVO(30, null, 5)))).thenReturn(execution);

        var result = fixture.service.retrieve(1L, "问题", 30, 5);

        assertEquals("doc-a", result.diagnostics().qdrantCandidates().get(0).documentCode());
        assertEquals(1, result.diagnostics().authorityValidCandidates().get(0).rank());
        assertTrue(result.diagnostics().rerankedCandidates().isEmpty());
        assertTrue(result.diagnostics().finalCandidates().isEmpty());
    }

    @Test
    void readinessRejectsGenerationFromPreviousRevision() {
        Fixture fixture = new Fixture();
        DocumentEntity document = DocumentEntity.builder().id(11L).workspaceId(1L).documentCode("doc-a")
                .status(DocumentStatusVO.ACTIVE).currentVersionNo(3L).indexInputRevision(7L).build();
        when(fixture.workspaces.findById(1L)).thenReturn(Optional.of(activeWorkspace()));
        when(fixture.documents.findByDocumentCode("doc-a")).thenReturn(Optional.of(document));
        when(fixture.versions.findByDocumentIdAndVersionNo(11L, 3L))
                .thenReturn(Optional.of(DocumentVersionEntity.builder().id(31L).build()));
        when(fixture.states.findByDocumentId(11L)).thenReturn(Optional.of(
                KnowledgeIndexStateEntity.builder().documentId(11L).activeGenerationId("gen-old").build()));
        when(fixture.generations.findByGenerationId("gen-old")).thenReturn(Optional.of(
                KnowledgeIndexGenerationEntity.builder().generationId("gen-old").workspaceId(1L).documentId(11L)
                        .versionNo(2L).indexInputRevision(6L).status(1).build()));

        var readiness = fixture.service.readiness(1L, List.of("doc-a")).get(0);

        assertFalse(readiness.ready());
        assertTrue("GENERATION_IDENTITY_MISMATCH".equals(readiness.reason()));
    }

    private WorkspaceEntity activeWorkspace() {
        return WorkspaceEntity.builder().id(1L).ownerUserId(9L).status(WorkspaceStatusVO.ACTIVE).build();
    }

    private static class Fixture {
        private final IWorkspaceRepository workspaces = mock(IWorkspaceRepository.class);
        private final IDocumentRepository documents = mock(IDocumentRepository.class);
        private final IDocumentVersionRepository versions = mock(IDocumentVersionRepository.class);
        private final IKnowledgeIndexStateRepository states = mock(IKnowledgeIndexStateRepository.class);
        private final IKnowledgeIndexGenerationRepository generations = mock(IKnowledgeIndexGenerationRepository.class);
        private final IKnowledgeRetrievalService retrieval = mock(IKnowledgeRetrievalService.class);
        private final RetrievalEvaluationServiceImpl service = new RetrievalEvaluationServiceImpl(
                workspaces, documents, versions, states, generations, retrieval);
    }
}
