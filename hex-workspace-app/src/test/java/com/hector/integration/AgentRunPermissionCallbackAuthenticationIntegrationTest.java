package com.hector.integration;

import com.hector.domain.agent.adapter.port.IBrowserEventPublisher;
import com.hector.domain.agent.model.valobj.LifecycleEventVO;
import com.hector.domain.agent.model.valobj.PermissionDecisionVO;
import com.hector.domain.agent.model.valobj.PermissionRequestVO;
import com.hector.domain.agent.service.IAgentRunPermissionService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Permission callback 必须使用 service token，不能进入用户 JWT 认证链。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "hexworkspace.hexgent.lifecycle-callback-token=permission-test-token")
class AgentRunPermissionCallbackAuthenticationIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FixturePermissionService permissionService;

    @Autowired
    private FixtureBrowserPublisher browserPublisher;

    @BeforeEach
    void resetFixture() {
        permissionService.invocations = 0;
        browserPublisher.permissionPublications = 0;
    }

    @Test
    void rejectsMissingServiceTokenBeforeReceiptService() throws Exception {
        mockMvc.perform(post("/internal/v1/agent-runs/permissions")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnauthorized());
        assertEquals(0, permissionService.invocations);
    }

    @Test
    void rejectsWrongServiceTokenBeforeReceiptService() throws Exception {
        mockMvc.perform(post("/internal/v1/agent-runs/permissions")
                        .header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnauthorized());
        assertEquals(0, permissionService.invocations);
    }

    @Test
    void acceptsValidServiceTokenWithoutFrontendJwt() throws Exception {
        mockMvc.perform(post("/internal/v1/agent-runs/permissions")
                        .header("Authorization", "Bearer permission-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isOk());
        assertEquals(1, permissionService.invocations);
        assertEquals(1, browserPublisher.permissionPublications);
    }

    private String request() {
        return "{\"integrationRunId\":\"run-1\",\"requestId\":\"permission-1\","
                + "\"toolName\":\"read_file\",\"description\":\"读取文件\","
                + "\"createdAt\":\"2026-10-01T00:00:00+08:00\"}";
    }

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean
        @Primary
        FixturePermissionService fixturePermissionService() {
            return new FixturePermissionService();
        }

        @Bean
        @Primary
        FixtureBrowserPublisher fixtureBrowserPublisher() {
            return new FixtureBrowserPublisher();
        }
    }

    static class FixturePermissionService implements IAgentRunPermissionService {
        private int invocations;

        @Override
        public boolean receive(PermissionRequestVO request) {
            invocations++;
            return true;
        }

        @Override
        public PermissionDecisionVO decide(String workspaceRunId, String requestId, String decision, String responseId) {
            return new PermissionDecisionVO(workspaceRunId, requestId, decision, responseId);
        }
    }

    static class FixtureBrowserPublisher implements IBrowserEventPublisher {
        private int permissionPublications;

        @Override public Subscription subscribe(String runId) { throw new UnsupportedOperationException(); }
        @Override public void publishLifecycle(LifecycleEventVO event) { }
        @Override public void publishPermission(PermissionRequestVO event) { permissionPublications++; }
        @Override public void publishRuntime(String runId, String event) { }
        @Override public void publishEnd(String runId) { }
    }
}
