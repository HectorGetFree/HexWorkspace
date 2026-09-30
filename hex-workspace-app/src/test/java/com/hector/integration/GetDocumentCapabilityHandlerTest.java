package com.hector.integration;

import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.document.model.valobj.DocumentReadResultVO;
import com.hector.domain.document.service.IDocumentService;
import com.hector.trigger.http.handler.capability.GetDocumentCapabilityHandler;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetDocumentCapabilityHandlerTest {

    @Test
    void readsExactVersionWithTrustedAuthorityAndBoundedRange() {
        IDocumentService documents = stubDocumentService();

        CapabilityResultVO result = new GetDocumentCapabilityHandler(documents).invoke(
                new CapabilityContextVO("run-1", "conversation-1", 7L, 9L, "[\"get_document\"]"),
                "{\"documentId\":11,\"versionId\":3,\"startLine\":2,\"lineCount\":2,"
                        + "\"workspaceId\":999,\"userId\":999}");

        assertEquals(CapabilityResultVO.Status.SUCCEEDED, result.status());
        assertTrue(result.dataJson().contains("\"versionId\":3"));
        assertTrue(result.dataJson().contains("第二行"));
    }

    @Test
    void rejectsInvalidRangeWithoutReadingDocument() {
        IDocumentService documents = stubDocumentService();

        CapabilityResultVO result = new GetDocumentCapabilityHandler(documents).invoke(
                new CapabilityContextVO("run-1", "conversation-1", 7L, 9L, "[\"get_document\"]"),
                "{\"documentId\":11,\"versionId\":3,\"startLine\":0,\"lineCount\":2}");

        assertEquals(CapabilityResultVO.Status.FAILED, result.status());
        assertEquals("INVALID_REQUEST", result.errorCode());
    }

    @Test
    void preservesAuthoritativeDocumentFailureCode() {
        IDocumentService documents = (IDocumentService) Proxy.newProxyInstance(
                IDocumentService.class.getClassLoader(), new Class<?>[]{IDocumentService.class},
                (proxy, method, args) -> {
                    if ("readVersionContent".equals(method.getName())) {
                        throw new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        CapabilityResultVO result = new GetDocumentCapabilityHandler(documents).invoke(
                new CapabilityContextVO("run-1", "conversation-1", 7L, 9L, "[\"get_document\"]"),
                "{\"documentId\":11,\"versionId\":3,\"startLine\":1,\"lineCount\":2}");

        assertEquals(CapabilityResultVO.Status.FAILED, result.status());
        assertEquals(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE.getCode(), result.errorCode());
        assertEquals(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE.getInfo(), result.errorMessage());
    }

    private IDocumentService stubDocumentService() {
        return (IDocumentService) Proxy.newProxyInstance(
                IDocumentService.class.getClassLoader(), new Class<?>[]{IDocumentService.class},
                (proxy, method, args) -> {
                    if ("readVersionContent".equals(method.getName())) {
                        return new DocumentReadResultVO(11L, 3L, "说明文档", 2, 3, true, "第二行\n第三行");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
