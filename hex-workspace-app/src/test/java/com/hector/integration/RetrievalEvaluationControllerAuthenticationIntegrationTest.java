package com.hector.integration;

import com.hector.domain.knowledge.model.valobj.RetrievalResultVO;
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
                .andExpect(status().isOk());
        assertEquals(1, service.invocations);
    }

    private String request() {
        return "{\"workspaceId\":1,\"query\":\"测试\",\"topK\":6}";
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

        @Override
        public RetrievalResultVO retrieve(Long workspaceId, String query, Integer topK) {
            invocations++;
            return RetrievalResultVO.empty();
        }

        @Override
        public List<com.hector.domain.knowledge.model.valobj.RetrievalEvaluationReadinessVO> readiness(
                Long workspaceId, List<String> documentCodes) {
            return List.of();
        }
    }
}
