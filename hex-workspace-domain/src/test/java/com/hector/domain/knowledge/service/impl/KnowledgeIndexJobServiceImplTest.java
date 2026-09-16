package com.hector.domain.knowledge.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.knowledge.repository.IKnowledgeIndexJobRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexOutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexJobServiceImplTest {

    @Mock
    private IKnowledgeIndexJobRepository jobRepository;

    @Mock
    private IKnowledgeIndexOutboxEventRepository outboxEventRepository;

    @InjectMocks
    private KnowledgeIndexJobServiceImpl knowledgeIndexJobService;

    @Test
    void shouldRegisterPendingJobAndOutboxForActivatedDocument() {
        when(jobRepository.saveIfAbsent(any())).thenReturn(true);
        DocumentEntity document = DocumentEntity.builder()
                .id(10L)
                .workspaceId(20L)
                .currentVersionNo(3L)
                .indexInputRevision(5L)
                .build();

        knowledgeIndexJobService.registerAutomatic(document);

        ArgumentCaptor<com.hector.domain.knowledge.model.entity.KnowledgeIndexJobEntity> jobCaptor =
                ArgumentCaptor.forClass(com.hector.domain.knowledge.model.entity.KnowledgeIndexJobEntity.class);
        verify(jobRepository).saveIfAbsent(jobCaptor.capture());
        assertEquals("auto:10:3:5", jobCaptor.getValue().getRequestKey());
        assertEquals(0, jobCaptor.getValue().getStatus().getCode());
        verify(outboxEventRepository).save(any());
    }

    @Test
    void shouldNotCreateOutboxWhenRequestKeyAlreadyExists() {
        when(jobRepository.saveIfAbsent(any())).thenReturn(false);
        DocumentEntity document = DocumentEntity.builder()
                .id(10L)
                .workspaceId(20L)
                .currentVersionNo(3L)
                .indexInputRevision(5L)
                .build();

        knowledgeIndexJobService.registerAutomatic(document);

        verify(outboxEventRepository, never()).save(any());
    }
}
