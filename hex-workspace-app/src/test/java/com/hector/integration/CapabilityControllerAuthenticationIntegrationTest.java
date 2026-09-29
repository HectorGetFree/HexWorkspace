package com.hector.integration;

import com.hector.domain.agent.adapter.port.ICapabilityGateway;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Capability 内部接口的 service token 认证边界测试。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "hexworkspace.hexgent.capability-token=test-token")
class CapabilityControllerAuthenticationIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FixtureGateway gateway;

    @BeforeEach
    void resetFixture() {
        gateway.invocations = 0;
    }

    @Test
    void rejectsMissingServiceTokenBeforeGateway() throws Exception {
        mockMvc.perform(post("/internal/agent/capabilities/invoke")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertEquals(0, gateway.invocations);
    }

    @Test
    void rejectsWrongServiceTokenBeforeGateway() throws Exception {
        mockMvc.perform(post("/internal/agent/capabilities/invoke")
                        .header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertEquals(0, gateway.invocations);
    }

    @Test
    void acceptsValidServiceTokenWithoutFrontendJwt() throws Exception {
        mockMvc.perform(post("/internal/agent/capabilities/invoke")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(1, gateway.invocations);
    }

    private String request() {
        return "{\"protocolVersion\":\"v1\",\"runId\":\"run-1\","
                + "\"callId\":\"call-1\",\"capability\":\"phase5_echo\","
                + "\"arguments\":{}}";
    }

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean
        @Primary
        FixtureGateway fixtureGateway() {
            return new FixtureGateway();
        }
    }

    static class FixtureGateway implements ICapabilityGateway {
        private int invocations;

        @Override
        public CapabilityResultVO invoke(com.hector.domain.agent.model.valobj.CapabilityRequestVO request) {
            invocations++;
            return new CapabilityResultVO("v1", request.runId(), request.callId(),
                    CapabilityResultVO.Status.DENIED, null, "RUN_NOT_FOUND", "fixture");
        }
    }
}
