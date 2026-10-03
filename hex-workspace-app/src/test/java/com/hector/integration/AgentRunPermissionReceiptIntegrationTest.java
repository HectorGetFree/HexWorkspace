package com.hector.integration;

import com.hector.domain.agent.model.valobj.PermissionRequestVO;
import com.hector.domain.agent.adapter.port.IBrowserEventPublisher;
import com.hector.domain.agent.service.IAgentRunPermissionService;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.trigger.api.dto.AgentRunPermissionRequestDTO;
import com.hector.trigger.http.AgentRunPermissionCallbackController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Permission callback 收据的真实 MySQL 幂等与决定一致性验收。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class AgentRunPermissionReceiptIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hex_workspace").withUsername("test").withPassword("test");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IAgentRunPermissionService permissionService;

    @Autowired
    private AgentRunPermissionCallbackController callbackController;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        MYSQL.start();
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            String script = Files.readString(Path.of("..", "docs", "devops", "mysql", "sql", "001-init-hex-workspace.sql"))
                    .replaceAll("(?m)^--.*$", "").replaceFirst("(?s)CREATE DATABASE.*?;", "")
                    .replaceFirst("(?s)USE `hex_workspace`;", "");
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }

    @AfterAll
    static void stopInfrastructure() {
        MYSQL.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("minio.sdk.config.endpoint", () -> "http://127.0.0.1:9000");
        registry.add("minio.sdk.config.access-key", () -> "minioadmin");
        registry.add("minio.sdk.config.secret-key", () -> "minioadmin");
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM agent_run_permission_request");
        jdbcTemplate.execute("DELETE FROM agent_run");
        seedRun("run-1");
    }

    @Test
    void callbackPublishesOnlyAfterFirstDurableReceipt() throws Exception {
        CapturePublisher publisher = new CapturePublisher();
        ReflectionTestUtils.setField(callbackController, "browserEventPublisher", publisher);
        ReflectionTestUtils.setField(callbackController, "callbackToken", "callback-token");

        assertEquals(401, callbackController.publish(null, callback("run-1", "permission-1")).getStatusCode().value());
        assertEquals(200, callbackController.publish("Bearer callback-token", callback("run-1", "permission-1")).getStatusCode().value());
        assertEquals(200, callbackController.publish("Bearer callback-token", callback("run-1", "permission-1")).getStatusCode().value());

        assertEquals(1, publisher.permissionPublications);
        assertEquals("permission-1", publisher.lastRequest.requestId());
        assertEquals(1L, count("SELECT COUNT(*) FROM agent_run_permission_request WHERE request_id='permission-1'"));
    }

    @Test
    void persistsOneReceiptForDuplicateAndConcurrentCallbacks() throws Exception {
        assertTrue(permissionService.receive(request("run-1", "permission-1")));
        assertFalse(permissionService.receive(request("run-1", "permission-1")));

        CountDownLatch gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> after(gate, () -> permissionService.receive(request("run-1", "permission-2"))));
            var second = pool.submit(() -> after(gate, () -> permissionService.receive(request("run-1", "permission-2"))));
            gate.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS) || second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2L, count("SELECT COUNT(*) FROM agent_run_permission_request"));
        assertEquals(0L, count("SELECT COUNT(*) FROM agent_run_permission_request WHERE status <> 0"));
    }

    @Test
    void recordsDecisionAndAllowsSameDecisionDeliveryRetry() {
        permissionService.receive(request("run-1", "permission-1"));

        var first = permissionService.decide("run-1", "permission-1", "allow", "response-first");
        var retry = permissionService.decide("run-1", "permission-1", "allow", "response-retry");

        assertEquals("response-first", first.responseId());
        assertEquals("response-first", retry.responseId());
        assertEquals(1L, count("SELECT COUNT(*) FROM agent_run_permission_request WHERE status = 1"));
        assertThrows(BusinessException.class,
                () -> permissionService.decide("run-1", "permission-1", "deny", "response-conflict"));
    }

    @Test
    void rejectsMismatchedRunWithoutChangingAgentRunLifecycle() {
        permissionService.receive(request("run-1", "permission-1"));
        long before = jdbcTemplate.queryForObject("SELECT lifecycle_sequence FROM agent_run WHERE run_id='run-1'", Long.class);

        assertThrows(BusinessException.class,
                () -> permissionService.decide("run-other", "permission-1", "deny", "response"));
        assertThrows(BusinessException.class,
                () -> permissionService.receive(request("run-2", "permission-1")));

        assertEquals(before, jdbcTemplate.queryForObject("SELECT lifecycle_sequence FROM agent_run WHERE run_id='run-1'", Long.class));
        assertEquals(0L, count("SELECT COUNT(*) FROM agent_run_permission_request WHERE status <> 0"));
    }

    private void seedRun(String runId) {
        jdbcTemplate.update("INSERT INTO agent_run(run_id, conversation_id, workspace_id, initiator_user_id, status, instruction, version, lifecycle_sequence) VALUES (?, 'conversation-1', 1, 1, 1, 'test', 0, 7)", runId);
    }

    private PermissionRequestVO request(String runId, String requestId) {
        return new PermissionRequestVO(runId, requestId, "read_file", "读取文件", OffsetDateTime.now());
    }

    private AgentRunPermissionRequestDTO callback(String runId, String requestId) {
        AgentRunPermissionRequestDTO request = new AgentRunPermissionRequestDTO();
        request.setIntegrationRunId(runId);
        request.setRequestId(requestId);
        request.setToolName("read_file");
        request.setDescription("读取文件");
        request.setCreatedAt(OffsetDateTime.now().toString());
        return request;
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private <T> T after(CountDownLatch gate, java.util.concurrent.Callable<T> callable) throws Exception {
        gate.await(10, TimeUnit.SECONDS);
        return callable.call();
    }

    private static final class CapturePublisher implements IBrowserEventPublisher {
        private int permissionPublications;
        private PermissionRequestVO lastRequest;

        @Override public Subscription subscribe(String runId) { throw new UnsupportedOperationException(); }
        @Override public void publishLifecycle(com.hector.domain.agent.model.valobj.LifecycleEventVO event) { }
        @Override public void publishPermission(PermissionRequestVO event) { permissionPublications++; lastRequest = event; }
        @Override public void publishRuntime(String runId, String event) { }
        @Override public void publishEnd(String runId) { }
    }
}
