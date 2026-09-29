package com.hector.integration;

import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalHitVO;
import com.hector.domain.knowledge.model.valobj.RetrievalResultVO;
import com.hector.domain.knowledge.service.IKnowledgeRetrievalService;
import com.hector.trigger.http.handler.capability.SearchWorkspaceCapabilityHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.RetrievalOptionsVO;

class SearchWorkspaceCapabilityHandlerTest {
    @Test
    void usesTrustedContextAndMapsOnlyPublicSearchFields() {
        RecordingRetrieval retrieval = new RecordingRetrieval(new RetrievalResultVO(List.of(
                new RetrievalHitVO(11L, "doc-11", 3L, "generation-secret", 2,
                        "产品说明", List.of("章节一"), "公开内容", 4, 4, 0.9))));

        CapabilityResultVO result = new SearchWorkspaceCapabilityHandler(retrieval).invoke(
                new CapabilityContextVO("run-1", "conversation-1", 7L, 9L, "[\"search_workspace\"]"),
                "{\"query\":\"知识\",\"topK\":5,\"userId\":\"FORGED\",\"workspaceId\":\"FORGED\"}");

        assertEquals(CapabilityResultVO.Status.SUCCEEDED, result.status());
        assertFalse(result.dataJson().contains("generation-secret"));
        assertFalse(result.dataJson().contains("FORGED"));
        assertEquals(7L, retrieval.context.userId());
        assertEquals(9L, retrieval.context.workspaceId());
        assertEquals("知识", retrieval.query);
    }

    @Test
    void returnsEmptySuccessAndRejectsInvalidArguments() {
        RecordingRetrieval retrieval = new RecordingRetrieval(RetrievalResultVO.empty());
        SearchWorkspaceCapabilityHandler handler = new SearchWorkspaceCapabilityHandler(retrieval);

        CapabilityResultVO empty = handler.invoke(context(), "{\"query\":\"无结果\"}");
        assertEquals(CapabilityResultVO.Status.SUCCEEDED, empty.status());
        assertEquals("{\"items\":[]}", empty.dataJson());

        CapabilityResultVO invalid = handler.invoke(context(), "{\"query\":\" \"}");
        assertEquals(CapabilityResultVO.Status.FAILED, invalid.status());
        assertEquals("INVALID_REQUEST", invalid.errorCode());

        CapabilityResultVO excessive = handler.invoke(context(), "{\"query\":\"知识\",\"topK\":21}");
        assertEquals(CapabilityResultVO.Status.FAILED, excessive.status());
        assertEquals("INVALID_REQUEST", excessive.errorCode());
    }

    private CapabilityContextVO context() {
        return new CapabilityContextVO("run-1", "conversation-1", 7L, 9L, "[\"search_workspace\"]");
    }

    private static class RecordingRetrieval implements IKnowledgeRetrievalService {
        private final RetrievalResultVO result;
        private RetrievalContextVO context;
        private String query;

        private RecordingRetrieval(RetrievalResultVO result) {
            this.result = result;
        }

        @Override
        public RetrievalResultVO retrieve(RetrievalContextVO context, String query, RetrievalOptionsVO options) {
            this.context = context;
            this.query = query;
            return result;
        }
    }
}
