package com.hector.integration;

import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationRerankResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalTimingVO;
import com.hector.domain.knowledge.service.IRetrievalEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RAG 评测内部入口必须只接受独立的 service token。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "hexworkspace.rag-evaluation.token=eval-test-token")
class RetrievalEvaluationControllerAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FixtureEvaluationService service;

    @BeforeEach
    void reset() {
        service.invocations = 0;
        service.candidateLimit = null;
        service.topK = null;
        service.rerankInvocations = 0;
    }

    @Test
    void rejectsMissingTokenBeforeEvaluationService() throws Exception {
        mockMvc.perform(post("/internal/v1/evaluation/retrieval")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnauthorized());
        assertEquals(0, service.invocations);
    }

    @Test
    void rejectsWrongTokenBeforeEvaluationService() throws Exception {
        mockMvc.perform(post("/internal/v1/evaluation/retrieval")
                        .header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnauthorized());
        assertEquals(0, service.invocations);
    }

    @Test
    void acceptsEvaluationTokenWithoutFrontendJwt() throws Exception {
        mockMvc.perform(post("/internal/v1/evaluation/retrieval")
                .header("Authorization", "Bearer eval-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timingMs.totalMs").value(0))
                .andExpect(jsonPath("$.diagnostics.qdrantCandidates").isArray());
        assertEquals(1, service.invocations);
        assertEquals(60, service.candidateLimit);
        assertEquals(5, service.topK);
    }

    @Test
    void rerankEndpointRequiresTheSameEvaluationToken() throws Exception {
        mockMvc.perform(post("/internal/v1/evaluation/retrieval/rerank")
                        .contentType(MediaType.APPLICATION_JSON).content(rerankRequest()))
                .andExpect(status().isUnauthorized());
        assertEquals(0, service.rerankInvocations);

        mockMvc.perform(post("/internal/v1/evaluation/retrieval/rerank")
                        .header("Authorization", "Bearer eval-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(rerankRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rerankApplied").value(true))
                .andExpect(jsonPath("$.inputVariant").value("STRUCTURAL_CONTEXT"));
        assertEquals(1, service.rerankInvocations);
    }

    @Test
    void candidateEndpointUsesServiceTokenWithoutInvokingRerank() throws Exception {
        mockMvc.perform(post("/internal/v1/evaluation/retrieval/candidates")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnauthorized());
        assertEquals(0, service.invocations);

        mockMvc.perform(post("/internal/v1/evaluation/retrieval/candidates")
                        .header("Authorization", "Bearer eval-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnostics.qdrantCandidates").isArray());
        assertEquals(1, service.invocations);
        assertEquals(0, service.rerankInvocations);
    }

    private String request() {
        return "{\"workspaceId\":1,\"query\":\"测试\",\"candidateLimit\":60,\"topK\":5}";
    }

    private String rerankRequest() {
        return "{\"workspaceId\":1,\"query\":\"测试\",\"topK\":5,\"candidates\":["
                + "{\"documentCode\":\"doc-a\",\"generationId\":\"gen-a\",\"chunkNo\":1,\"rank\":1,\"score\":0.1},"
                + "{\"documentCode\":\"doc-b\",\"generationId\":\"gen-b\",\"chunkNo\":2,\"rank\":2,\"score\":0.09}]}";
    }

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean
        @Primary
        FixtureEvaluationService fixtureEvaluationService() {
            return new FixtureEvaluationService();
        }
    }

    static class FixtureEvaluationService implements IRetrievalEvaluationService {
        private int invocations;

        private Integer candidateLimit;

        private Integer topK;

        private int rerankInvocations;

        @Override
        public RetrievalEvaluationResultVO retrieve(Long workspaceId, String query, Integer candidateLimit, Integer topK) {
            invocations++;
            this.candidateLimit = candidateLimit;
            this.topK = topK;
            return new RetrievalEvaluationResultVO(RetrievalResultVO.empty(), new RetrievalTimingVO(0, 0, 0, 0, 0));
        }

        @Override
        public RetrievalEvaluationResultVO retrieveCandidates(Long workspaceId, String query, Integer candidateLimit, Integer topK) {
            return retrieve(workspaceId, query, candidateLimit, topK);
        }

        @Override
        public List<com.hector.domain.knowledge.model.valobj.RetrievalEvaluationReadinessVO> readiness(
                Long workspaceId, List<String> documentCodes) {
            return List.of();
        }

        @Override
        public List<com.hector.domain.knowledge.model.valobj.RetrievalEvaluationChunkVO> activeChunks(
                Long workspaceId, List<String> documentCodes) {
            return List.of();
        }

        @Override
        public RetrievalEvaluationRerankResultVO rerankCandidates(
                Long workspaceId, String query, List<RetrievalEvaluationCandidateVO> candidates, Integer topK,
                com.hector.domain.knowledge.model.valobj.RetrievalEvaluationRerankInputVO inputVariant) {
            rerankInvocations++;
            return new RetrievalEvaluationRerankResultVO(candidates, true);
        }
    }
}
