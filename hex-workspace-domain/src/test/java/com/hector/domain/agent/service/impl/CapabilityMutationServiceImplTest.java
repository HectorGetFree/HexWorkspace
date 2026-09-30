package com.hector.domain.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.hector.domain.agent.model.entity.CapabilityMutationRecordEntity;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityMutationStatusVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.agent.repository.ICapabilityMutationRecordRepository;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.document.service.IDocumentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapabilityMutationServiceImplTest {
    @Mock
    private ICapabilityMutationRecordRepository recordRepository;
    @Mock
    private IDocumentRepository documentRepository;
    @Mock
    private IDocumentVersionRepository versionRepository;
    @Mock
    private IDocumentService documentService;
    @InjectMocks
    private CapabilityMutationServiceImpl service;

    @Test
    void sameIdentityWithDifferentFingerprintIsRejectedWithoutMutation() throws Exception {
        CapabilityContextVO context = new CapabilityContextVO(
                "run-1", "call-1", "conversation-1", 7L, 9L, "[\"update_document\"]");
        String arguments = "{\"documentId\":1,\"expectedVersionId\":3,\"content\":\"B\"}";
        String fingerprint = fingerprint(arguments);
        when(recordRepository.findByIdentity("run-1", "call-1")).thenReturn(Optional.of(
                CapabilityMutationRecordEntity.builder().runId("run-1").callId("call-1")
                        .requestFingerprint(fingerprint + "different").status(CapabilityMutationStatusVO.PROCESSING)
                        .build()));

        CapabilityResultVO result = service.updateDocument(context, arguments);

        assertEquals(CapabilityResultVO.Status.FAILED, result.status());
        assertEquals("IDEMPOTENCY_CONFLICT", result.errorCode());
        verify(documentService, never()).updateMarkdownDocument(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void succeededRecordIsReturnedWithoutCreatingAnotherVersion() throws Exception {
        CapabilityContextVO context = new CapabilityContextVO(
                "run-1", "call-1", "conversation-1", 7L, 9L, "[\"update_document\"]");
        String arguments = "{\"documentId\":1,\"expectedVersionId\":3,\"content\":\"A\"}";
        when(recordRepository.findByIdentity("run-1", "call-1")).thenReturn(Optional.of(
                CapabilityMutationRecordEntity.builder().runId("run-1").callId("call-1")
                        .requestFingerprint(fingerprint(arguments)).status(CapabilityMutationStatusVO.SUCCEEDED)
                        .documentId(1L).previousVersionId(3L).versionId(4L).build()));

        CapabilityResultVO result = service.updateDocument(context, arguments);

        assertEquals(CapabilityResultVO.Status.SUCCEEDED, result.status());
        assertEquals("{\"documentId\":1,\"previousVersionId\":3,\"versionId\":4}", result.dataJson());
        verify(documentService, never()).updateMarkdownDocument(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private String fingerprint(String arguments) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.toJSONString(JSON.parseObject(arguments), SerializerFeature.SortField)
                        .getBytes(StandardCharsets.UTF_8)));
    }
}
