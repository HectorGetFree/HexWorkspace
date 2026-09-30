package com.hector.integration;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.trigger.http.handler.capability.UpdateDocumentCapabilityHandler;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** update_document Capability 的真实 MySQL + MinIO 入口级持久化验收。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class UpdateDocumentCapabilityIntegrationTest {

    private static final String BUCKET = "update-document-test";
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hex_workspace").withUsername("test").withPassword("test");
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("quay.io/minio/minio:RELEASE.2024-06-13T22-53-53Z"))
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000)
            .withCommand("server", "/data");

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MinioClient minioClient;
    @Autowired
    private UpdateDocumentCapabilityHandler handler;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        MYSQL.start();
        MINIO.start();
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            String script = Files.readString(Path.of("..", "docs", "devops", "mysql", "sql", "001-init-hex-workspace.sql"))
                    .replaceAll("(?m)^--.*$", "")
                    .replaceFirst("(?s)CREATE DATABASE.*?;", "")
                    .replaceFirst("(?s)USE `hex_workspace`;", "");
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("minio.sdk.config.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("minio.sdk.config.access-key", () -> "minioadmin");
        registry.add("minio.sdk.config.secret-key", () -> "minioadmin");
        registry.add("minio.sdk.config.bucket", () -> BUCKET);
    }

    @AfterAll
    static void stopInfrastructure() {
        MINIO.stop();
        MYSQL.stop();
    }

    @BeforeEach
    void cleanDatabase() throws Exception {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : List.of("capability_mutation_record", "knowledge_index_outbox_event",
                "knowledge_index_job", "document_upload_outbox_event", "document_upload_task",
                "document_version", "document", "folder", "workspace", "user")) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=1");
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void happyPathCreatesNewFormalVersion() throws Exception {
        Seed seed = seedDocument("V3-CONTENT");
        String content = "UPDATE-DOCUMENT-V4-CONTENT";

        CapabilityResultVO result = handler.invoke(context("run-happy", "call-happy", seed),
                "{\"documentId\":" + seed.documentId + ",\"expectedVersionId\":" + seed.versionId
                        + ",\"content\":\"" + content + "\"}");

        assertEquals(CapabilityResultVO.Status.SUCCEEDED, result.status(),
                () -> result.errorCode() + ": " + result.errorMessage());
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT current_version_no FROM document WHERE id = ?",
                Long.class, seed.documentId));
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id = ?",
                Long.class, seed.documentId));
        Long newVersionRowId = jdbcTemplate.queryForObject(
                "SELECT id FROM document_version WHERE document_id = ? AND version_no = 2", Long.class, seed.documentId);
        assertTrue(result.dataJson().contains("\"versionId\":" + newVersionRowId));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_index_job WHERE document_id = ? AND version_no = 2",
                Long.class, seed.documentId));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_index_outbox_event", Long.class));
        assertEquals("V3-CONTENT", read(seed.versionPath));
        assertEquals(content, read(jdbcTemplate.queryForObject(
                "SELECT storage_path FROM document_version WHERE id = ?", String.class, newVersionRowId)));
    }

    @Test
    void reconcilesActiveVersionWithProcessingMutationRecord() throws Exception {
        Seed seed = seedDocument("V3-CONTENT");
        String content = "UPDATE-DOCUMENT-V4-RECOVERED";
        CapabilityResultVO first = handler.invoke(context("run-recover", "call-recover", seed),
                "{\"documentId\":" + seed.documentId + ",\"expectedVersionId\":" + seed.versionId
                        + ",\"content\":\"" + content + "\"}");
        assertEquals(CapabilityResultVO.Status.SUCCEEDED, first.status());
        Long versionRowId = jdbcTemplate.queryForObject(
                "SELECT id FROM document_version WHERE document_id = ? AND version_no = 2", Long.class, seed.documentId);
        jdbcTemplate.update("UPDATE capability_mutation_record SET status = 0, version_id = NULL, updated_at = ? WHERE run_id = ? AND call_id = ?",
                LocalDateTime.now(), "run-recover", "call-recover");

        CapabilityResultVO recovered = handler.invoke(context("run-recover", "call-recover", seed),
                "{\"documentId\":" + seed.documentId + ",\"expectedVersionId\":" + seed.versionId
                        + ",\"content\":\"" + content + "\"}");

        assertEquals(CapabilityResultVO.Status.SUCCEEDED, recovered.status());
        assertTrue(recovered.dataJson().contains("\"versionId\":" + versionRowId));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 2",
                Long.class, seed.documentId));
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 3",
                Long.class, seed.documentId));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT status FROM capability_mutation_record WHERE run_id = ? AND call_id = ?",
                Long.class, "run-recover", "call-recover"));
    }

    @Test
    void doesNotReexecutePartialMutation() throws Exception {
        Seed seed = seedDocument("V3-CONTENT");
        String arguments = arguments(seed, "UPDATE-DOCUMENT-V4-PARTIAL");
        insertProcessingRecord("run-partial", "call-partial", seed, arguments, LocalDateTime.now().plusMinutes(5));
        jdbcTemplate.update("UPDATE document SET status = 1 WHERE id = ?", seed.documentId);
        jdbcTemplate.update("INSERT INTO document_upload_task (task_code, document_id, version_no, created_by, staging_path, target_path, content_type, bucket_name, content_size, content_hash, status, retry_count) VALUES ('task-partial', ?, 2, ?, 'staging/partial', 'documents/update/v2.md', 'text/markdown', ?, 1, 'partial', 0, 0)",
                seed.documentId, seed.userId, BUCKET);

        CapabilityResultVO result = handler.invoke(context("run-partial", "call-partial", seed), arguments);

        assertEquals(CapabilityResultVO.Status.FAILED, result.status());
        assertEquals("DOCUMENT_UPDATE_PENDING", result.errorCode());
        assertEquals(1L, count("SELECT COUNT(*) FROM document_upload_task WHERE document_id = ? AND version_no = 2", seed.documentId));
        assertEquals(0L, count("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 2", seed.documentId));
        assertEquals(0L, count("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 3", seed.documentId));
    }

    @Test
    void sameCommandConcurrentRetryExecutesOnce() throws Exception {
        Seed seed = seedDocument("V3-CONTENT");
        String arguments = arguments(seed, "UPDATE-DOCUMENT-V4-CONCURRENT");
        insertProcessingRecord("run-concurrent", "call-concurrent", seed, arguments, LocalDateTime.now().minusMinutes(1));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CapabilityResultVO> first = executor.submit(() -> invokeAfter(start,
                    context("run-concurrent", "call-concurrent", seed), arguments));
            Future<CapabilityResultVO> second = executor.submit(() -> invokeAfter(start,
                    context("run-concurrent", "call-concurrent", seed), arguments));
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1L, count("SELECT COUNT(*) FROM document_upload_task WHERE document_id = ? AND version_no = 2", seed.documentId));
        assertEquals(1L, count("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 2", seed.documentId));
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT current_version_no FROM document WHERE id = ?", Long.class, seed.documentId));
        assertEquals(0L, count("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 3", seed.documentId));
    }

    @Test
    void differentCallsFromSameBaseVersionOnlyOneSucceeds() throws Exception {
        Seed seed = seedDocument("V3-CONTENT");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CapabilityResultVO> first = executor.submit(() -> invokeAfter(start,
                    context("run-occ", "call-occ-a", seed), arguments(seed, "CONTENT-A")));
            Future<CapabilityResultVO> second = executor.submit(() -> invokeAfter(start,
                    context("run-occ", "call-occ-b", seed), arguments(seed, "CONTENT-B")));
            start.countDown();
            CapabilityResultVO a = first.get(20, TimeUnit.SECONDS);
            CapabilityResultVO b = second.get(20, TimeUnit.SECONDS);
            assertEquals(1L, List.of(a, b).stream().filter(result -> result.status() == CapabilityResultVO.Status.SUCCEEDED).count());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1L, count("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 2", seed.documentId));
        assertEquals(0L, count("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 3", seed.documentId));
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT current_version_no FROM document WHERE id = ?", Long.class, seed.documentId));
    }

    @Test
    void rechecksAuthorityAtMutationTime() throws Exception {
        Seed seed = seedDocument("V3-CONTENT");
        jdbcTemplate.update("UPDATE document SET status = 3, deleted_at = ? WHERE id = ?", LocalDateTime.now(), seed.documentId);

        CapabilityResultVO result = handler.invoke(context("run-authority", "call-authority", seed),
                arguments(seed, "UPDATE-DOCUMENT-V4-DENIED"));

        assertTrue(result.status() == CapabilityResultVO.Status.FAILED || result.status() == CapabilityResultVO.Status.DENIED);
        assertEquals(0L, count("SELECT COUNT(*) FROM document_upload_task WHERE document_id = ?", seed.documentId));
        assertEquals(1L, count("SELECT COUNT(*) FROM document_version WHERE document_id = ?", seed.documentId));
        assertEquals(0L, count("SELECT COUNT(*) FROM capability_mutation_record WHERE run_id = ? AND call_id = ?", "run-authority", "call-authority"));
    }

    private Seed seedDocument(String v3Content) throws Exception {
        jdbcTemplate.update("INSERT INTO user (username, password_hash, status) VALUES ('update-test', 'hash', 0)");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM user WHERE username = 'update-test'", Long.class);
        jdbcTemplate.update("INSERT INTO workspace (workspace_code, workspace_name, is_default, status, owner_user_id) VALUES ('ws-update', 'Update Test', 1, 0, ?)", userId);
        Long workspaceId = jdbcTemplate.queryForObject("SELECT id FROM workspace WHERE workspace_code = 'ws-update'", Long.class);
        jdbcTemplate.update("INSERT INTO folder (folder_code, workspace_id, name, is_root, status) VALUES ('folder-update', ?, 'Root', 1, 0)", workspaceId);
        Long folderId = jdbcTemplate.queryForObject("SELECT id FROM folder WHERE folder_code = 'folder-update'", Long.class);
        jdbcTemplate.update("INSERT INTO document (document_code, workspace_id, parent_id, owner_user_id, title, file_name, document_type, status, current_version_no, index_input_revision) VALUES ('doc-offset', ?, ?, ?, 'Offset', 'offset.md', 1, 2, 1, 1)", workspaceId, folderId, userId);
        Long offsetDocumentId = jdbcTemplate.queryForObject("SELECT id FROM document WHERE document_code = 'doc-offset'", Long.class);
        jdbcTemplate.update("INSERT INTO document_version (document_id, version_no, bucket_name, storage_path, content_type, content_size, content_hash, created_by) VALUES (?, 1, ?, 'documents/update/offset.md', 'text/markdown', 6, SHA2('offset', 256), 1)", offsetDocumentId, BUCKET);
        jdbcTemplate.update("INSERT INTO document (document_code, workspace_id, parent_id, owner_user_id, title, file_name, document_type, status, current_version_no, index_input_revision) VALUES ('doc-update', ?, ?, ?, 'Update Test', 'update.md', 1, 2, 1, 1)", workspaceId, folderId, userId);
        Long documentId = jdbcTemplate.queryForObject("SELECT id FROM document WHERE document_code = 'doc-update'", Long.class);
        jdbcTemplate.update("INSERT INTO document_version (document_id, version_no, bucket_name, storage_path, content_type, content_size, content_hash, created_by) VALUES (?, 1, ?, 'documents/update/v1.md', 'text/markdown', ?, SHA2(?, 256), 1)", documentId, BUCKET, v3Content.length(), v3Content);
        jdbcTemplate.update("UPDATE document SET current_version_no = 1 WHERE id = ?", documentId);
        put("documents/update/v1.md", v3Content);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM document_version WHERE document_id = ? AND version_no = 1",
                Long.class, documentId);
        return new Seed(documentId, versionId, "documents/update/v1.md", workspaceId, userId);
    }

    private CapabilityContextVO context(String runId, String callId, Seed seed) {
        return new CapabilityContextVO(runId, callId, "conversation-update", seed.userId, seed.workspaceId,
                "[\"update_document\"]");
    }

    private CapabilityResultVO invokeAfter(CountDownLatch start, CapabilityContextVO context, String arguments) {
        try {
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return handler.invoke(context, arguments);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private String arguments(Seed seed, String content) {
        return "{\"documentId\":" + seed.documentId + ",\"expectedVersionId\":" + seed.versionId
                + ",\"content\":\"" + content + "\"}";
    }

    private void insertProcessingRecord(String runId, String callId, Seed seed, String arguments, LocalDateTime leaseUntil) {
        jdbcTemplate.update("INSERT INTO capability_mutation_record (run_id, call_id, capability, request_fingerprint, status, execution_token, lease_until, document_id, previous_version_id, target_version_no) VALUES (?, ?, 'update_document', ?, 0, 'stale-token', ?, ?, ?, 2)",
                runId, callId, fingerprint(arguments), leaseUntil, seed.documentId, seed.versionId);
    }

    private long count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private String fingerprint(String arguments) {
        try {
            String canonical = JSON.toJSONString(JSON.parseObject(arguments), SerializerFeature.SortField);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void put(String path, String content) throws Exception {
        minioClient.putObject(io.minio.PutObjectArgs.builder().bucket(BUCKET).object(path)
                .stream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), -1)
                .contentType("text/markdown").build());
    }

    private String read(String path) throws Exception {
        try (var stream = minioClient.getObject(GetObjectArgs.builder().bucket(BUCKET).object(path).build())) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Seed(Long documentId, Long versionId, String versionPath, Long workspaceId, Long userId) {
    }
}
