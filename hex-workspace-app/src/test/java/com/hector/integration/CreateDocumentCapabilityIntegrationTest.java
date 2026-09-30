package com.hector.integration;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.trigger.http.handler.capability.CreateDocumentCapabilityHandler;
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
import java.nio.file.Path;
import java.security.MessageDigest;
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

/** create_document 的真实 MySQL + MinIO 持久化、幂等与并发验收。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class CreateDocumentCapabilityIntegrationTest {
    private static final String BUCKET = "create-document-test";
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hex_workspace").withUsername("test").withPassword("test");
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("quay.io/minio/minio:RELEASE.2024-06-13T22-53-53Z"))
            .withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000).withCommand("server", "/data");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MinioClient minioClient;
    @Autowired private CreateDocumentCapabilityHandler handler;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        MYSQL.start(); MINIO.start();
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            String script = Files.readString(Path.of("..", "docs", "devops", "mysql", "sql", "001-init-hex-workspace.sql"))
                    .replaceAll("(?m)^--.*$", "").replaceFirst("(?s)CREATE DATABASE.*?;", "")
                    .replaceFirst("(?s)USE `hex_workspace`;", "");
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("minio.sdk.config.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("minio.sdk.config.access-key", () -> "minioadmin");
        registry.add("minio.sdk.config.secret-key", () -> "minioadmin");
        registry.add("minio.sdk.config.bucket", () -> BUCKET);
    }

    @AfterAll static void stopInfrastructure() { MINIO.stop(); MYSQL.stop(); }

    @BeforeEach
    void clean() throws Exception {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : List.of("capability_mutation_record", "knowledge_index_outbox_event", "knowledge_index_job",
                "document_upload_outbox_event", "document_upload_task", "document_version", "document", "folder", "workspace", "user")) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=1");
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }

    @Test void happyPathCreatesFormalV1() throws Exception {
        Seed seed = seed(); String args = args(seed.folderId, "happy.md", "HAPPY-CONTENT");
        CapabilityResultVO result = invoke("run-happy", "call-happy", seed, args);
        assertSuccess(result); Long documentId = id(result, "documentId"); Long versionId = id(result, "versionId");
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT status FROM document WHERE id=?", Long.class, documentId));
        assertEquals(1L, count("SELECT COUNT(*) FROM document_version WHERE id=? AND document_id=? AND version_no=1", versionId, documentId));
        assertEquals("HAPPY-CONTENT", read(jdbcTemplate.queryForObject("SELECT storage_path FROM document_version WHERE id=?", String.class, versionId)));
        assertEquals(1L, count("SELECT COUNT(*) FROM knowledge_index_job WHERE document_id=? AND version_no=1", documentId));
        assertEquals(1L, count("SELECT COUNT(*) FROM knowledge_index_outbox_event"));
    }

    @Test void lostResponseRetryReturnsSameDocument() {
        Seed seed = seed(); String args = args(seed.folderId, "retry.md", "RETRY");
        CapabilityResultVO first = invoke("run-retry", "call-retry", seed, args);
        CapabilityResultVO second = invoke("run-retry", "call-retry", seed, args);
        assertSuccess(first); assertSuccess(second); assertEquals(first.dataJson(), second.dataJson());
        assertEquals(1L, count("SELECT COUNT(*) FROM document")); assertEquals(1L, count("SELECT COUNT(*) FROM document_version"));
    }

    @Test void recoversActiveDocumentWithProcessingRecord() {
        Seed seed = seed(); String args = args(seed.folderId, "active.md", "ACTIVE");
        CapabilityResultVO first = invoke("run-active", "call-active", seed, args); assertSuccess(first);
        jdbcTemplate.update("UPDATE capability_mutation_record SET status=0, version_id=NULL WHERE run_id='run-active' AND call_id='call-active'");
        CapabilityResultVO recovered = invoke("run-active", "call-active", seed, args);
        assertSuccess(recovered); assertEquals(1L, count("SELECT COUNT(*) FROM document"));
        assertEquals(1L, count("SELECT status FROM capability_mutation_record WHERE run_id='run-active' AND call_id='call-active'"));
    }

    @Test void partialCreatingDoesNotCreateSecondDocument() {
        Seed seed = seed(); String args = args(seed.folderId, "partial.md", "PARTIAL");
        String key = key("run-partial", "call-partial");
        jdbcTemplate.update("INSERT INTO document(document_code,workspace_id,parent_id,owner_user_id,title,file_name,document_type,status,index_input_revision,idempotency_key) VALUES('doc-partial',?,?,?,?,?,1,0,0,?)", seed.workspaceId, seed.folderId, seed.userId, "partial", "partial.md", key);
        Long documentId = jdbcTemplate.queryForObject("SELECT id FROM document WHERE document_code='doc-partial'", Long.class);
        jdbcTemplate.update("INSERT INTO document_upload_task(task_code,document_id,version_no,created_by,staging_path,target_path,content_type,bucket_name,content_size,content_hash,status,retry_count) VALUES('task-partial',?,1,?,'staging/partial','workspace/partial/versions/1','text/markdown',?,7,SHA2('PARTIAL',256),0,0)", documentId, seed.userId, BUCKET);
        jdbcTemplate.update("INSERT INTO capability_mutation_record(run_id,call_id,capability,request_fingerprint,status,execution_token,lease_until,target_version_no) VALUES('run-partial','call-partial','create_document',?,0,'token',?,1)", fingerprint(args), LocalDateTime.now().plusMinutes(5));
        CapabilityResultVO result = invoke("run-partial", "call-partial", seed, args);
        assertEquals("DOCUMENT_CREATE_PENDING", result.errorCode()); assertEquals(1L, count("SELECT COUNT(*) FROM document WHERE id=?", documentId));
        assertEquals(1L, count("SELECT COUNT(*) FROM document_upload_task WHERE document_id=? AND version_no=1", documentId));
    }

    @Test void recoversRecordBeforeDocument() {
        Seed seed = seed(); String args = args(seed.folderId, "recover.md", "RECOVER");
        jdbcTemplate.update("INSERT INTO capability_mutation_record(run_id,call_id,capability,request_fingerprint,status,execution_token,lease_until,target_version_no) VALUES('run-pre','call-pre','create_document',?,0,'old',?,1)", fingerprint(args), LocalDateTime.now().minusMinutes(1));
        CapabilityResultVO result = invoke("run-pre", "call-pre", seed, args); assertSuccess(result);
        assertEquals(1L, count("SELECT COUNT(*) FROM document")); assertEquals(1L, count("SELECT COUNT(*) FROM document_version"));
    }

    @Test void concurrentRetryCreatesOnlyOneDocument() throws Exception {
        Seed seed = seed(); String args = args(seed.folderId, "concurrent.md", "CONCURRENT"); CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CapabilityResultVO> a = pool.submit(() -> after(gate, () -> invoke("run-con", "call-con", seed, args)));
            Future<CapabilityResultVO> b = pool.submit(() -> after(gate, () -> invoke("run-con", "call-con", seed, args)));
            gate.countDown(); a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(1L, count("SELECT COUNT(*) FROM document")); assertEquals(1L, count("SELECT COUNT(*) FROM document_version"));
    }

    @Test void rejectsFingerprintConflict() {
        Seed seed = seed(); CapabilityResultVO first = invoke("run-conflict", "call-conflict", seed, args(seed.folderId, "a.md", "A")); assertSuccess(first);
        CapabilityResultVO second = invoke("run-conflict", "call-conflict", seed, args(seed.folderId, "b.md", "B"));
        assertEquals("IDEMPOTENCY_CONFLICT", second.errorCode()); assertEquals(1L, count("SELECT COUNT(*) FROM document"));
    }

    @Test void rejectsDeletedFolderWithoutSideEffects() {
        Seed seed = seed(); jdbcTemplate.update("UPDATE folder SET status=1 WHERE id=?", seed.folderId);
        CapabilityResultVO result = invoke("run-denied", "call-denied", seed, args(seed.folderId, "denied.md", "DENIED"));
        assertEquals(CapabilityResultVO.Status.DENIED, result.status()); assertEquals(0L, count("SELECT COUNT(*) FROM capability_mutation_record"));
        assertEquals(0L, count("SELECT COUNT(*) FROM document")); assertEquals(0L, count("SELECT COUNT(*) FROM document_upload_task"));
    }

    private Seed seed() {
        jdbcTemplate.update("INSERT INTO user(username,password_hash,status) VALUES('create-user','hash',0)"); Long userId = jdbcTemplate.queryForObject("SELECT id FROM user WHERE username='create-user'", Long.class);
        jdbcTemplate.update("INSERT INTO workspace(workspace_code,workspace_name,is_default,status,owner_user_id) VALUES('create-ws','Create',1,0,?)", userId); Long workspaceId = jdbcTemplate.queryForObject("SELECT id FROM workspace WHERE workspace_code='create-ws'", Long.class);
        jdbcTemplate.update("INSERT INTO folder(folder_code,workspace_id,name,is_root,status) VALUES('create-folder',?,'Root',1,0)", workspaceId); Long folderId = jdbcTemplate.queryForObject("SELECT id FROM folder WHERE folder_code='create-folder'", Long.class);
        return new Seed(workspaceId, userId, folderId);
    }
    private CapabilityResultVO invoke(String run, String call, Seed seed, String args) { return handler.invoke(new CapabilityContextVO(run, call, "conversation", seed.userId, seed.workspaceId, "[\\\"create_document\\\"]"), args); }
    private String args(Long folder, String title, String content) { return "{\"parentFolderId\":" + folder + ",\"title\":\"" + title + "\",\"content\":\"" + content + "\"}"; }
    private void assertSuccess(CapabilityResultVO result) { assertEquals(CapabilityResultVO.Status.SUCCEEDED, result.status(), () -> result.errorCode() + ": " + result.errorMessage()); }
    private Long id(CapabilityResultVO result, String key) { return JSON.parseObject(result.dataJson()).getLong(key); }
    private long count(String sql, Object... args) { return jdbcTemplate.queryForObject(sql, Long.class, args); }
    private String fingerprint(String args) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(JSON.parseObject(args), SerializerFeature.SortField).getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String key(String run, String call) { try { return "agent:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((run + ":" + call).getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String read(String object) throws Exception { try (var stream = minioClient.getObject(GetObjectArgs.builder().bucket(BUCKET).object(object).build())) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); } }
    private CapabilityResultVO after(CountDownLatch gate, java.util.concurrent.Callable<CapabilityResultVO> action) { try { gate.await(10, TimeUnit.SECONDS); return action.call(); } catch (Exception e) { throw new IllegalStateException(e); } }
    private record Seed(Long workspaceId, Long userId, Long folderId) { }
}
