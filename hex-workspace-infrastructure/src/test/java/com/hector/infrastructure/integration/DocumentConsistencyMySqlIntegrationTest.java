package com.hector.infrastructure.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 一致性硬门槛真实 MySQL 测试。
 *
 * <p>只替代对象存储与 MQ；Document 条件更新、唯一约束及数据库事务均使用 Testcontainers MySQL。</p>
 */
class DocumentConsistencyMySqlIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hex_workspace")
            .withUsername("test")
            .withPassword("test");

    @BeforeAll
    static void startDatabase() throws Exception {
        MYSQL.start();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
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

    @AfterAll
    static void stopDatabase() {
        MYSQL.stop();
    }

    @BeforeEach
    void cleanDatabase() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("USE hex_workspace");
            for (String table : List.of("knowledge_index_outbox_event", "knowledge_index_job", "document_version", "document_upload_task", "document")) {
                statement.execute("DELETE FROM " + table);
            }
        }
    }

    @Test
    void concurrentCas_shouldAllowOnlyOneCommit() throws Exception {
        long documentId = seedActiveDocument("doc_cas", 1L, 1L);
        CountDownLatch readSnapshot = new CountDownLatch(2);
        CountDownLatch startCas = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(casAttempt(documentId, readSnapshot, startCas, "a"));
            Future<Boolean> second = executor.submit(casAttempt(documentId, readSnapshot, startCas, "b"));
            assertTrue(readSnapshot.await(10, TimeUnit.SECONDS));
            startCas.countDown();
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(2L, queryLong("SELECT current_version_no FROM document WHERE id = ?", documentId));
        assertEquals(2L, queryLong("SELECT index_input_revision FROM document WHERE id = ?", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 2", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_job WHERE document_id = ? AND version_no = 2", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_outbox_event", documentId));
    }

    @Test
    void indexJobOrOutboxFailure_shouldRollbackEntireTransaction() throws Exception {
        long documentId = seedActiveDocument("doc_rollback", 1L, 1L);
        assertFalse(activateWithInjectedFailure(documentId, true, false));
        assertUnchangedAfterRollback(documentId);
        assertFalse(activateWithInjectedFailure(documentId, false, true));
        assertUnchangedAfterRollback(documentId);
        assertTrue(activateWithInjectedFailure(documentId, false, false));
        assertEquals(2L, queryLong("SELECT current_version_no FROM document WHERE id = ?", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 2", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_job WHERE document_id = ? AND version_no = 2", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_outbox_event", documentId));
    }

    @Test
    void duplicateCompletion_shouldApplyOnlyOnce() throws Exception {
        long documentId = seedActiveDocument("doc_duplicate", 1L, 1L);
        assertTrue(activateWithInjectedFailure(documentId, false, false));
        assertFalse(activateWithInjectedFailure(documentId, false, false));
        assertEquals(2L, queryLong("SELECT current_version_no FROM document WHERE id = ?", documentId));
        assertEquals(2L, queryLong("SELECT index_input_revision FROM document WHERE id = ?", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 2", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_job WHERE document_id = ? AND version_no = 2", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_outbox_event", documentId));
    }

    private Callable<Boolean> casAttempt(long documentId, CountDownLatch readSnapshot, CountDownLatch startCas, String suffix) {
        return () -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement read = connection.prepareStatement("SELECT current_version_no, index_input_revision FROM document WHERE id = ?")) {
                    read.setLong(1, documentId);
                    read.executeQuery();
                }
                readSnapshot.countDown();
                if (!startCas.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发开始屏障超时");
                try {
                    boolean success = activate(connection, documentId, suffix);
                    if (success) connection.commit(); else connection.rollback();
                    return success;
                } catch (SQLException exception) {
                    connection.rollback();
                    return false;
                }
            }
        };
    }

    private boolean activateWithInjectedFailure(long documentId, boolean failJob, boolean failOutbox) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (!insertVersionAndActivateDocument(connection, documentId, "single")) {
                    connection.rollback();
                    return false;
                }
                if (failJob) throw new SQLException("注入 IndexJob 登记失败");
                insertJob(connection, documentId);
                if (failOutbox) throw new SQLException("注入 Knowledge Outbox 登记失败");
                insertOutbox(connection);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                return false;
            }
        }
    }

    private boolean activate(Connection connection, long documentId, String suffix) throws SQLException {
        if (!insertVersionAndActivateDocument(connection, documentId, suffix)) return false;
        insertJob(connection, documentId);
        insertOutbox(connection);
        return true;
    }

    private boolean insertVersionAndActivateDocument(Connection connection, long documentId, String suffix) throws SQLException {
        try (PreparedStatement version = connection.prepareStatement("INSERT INTO document_version (document_id, version_no, storage_type, bucket_name, storage_path, content_type, content_size, content_hash, created_by) VALUES (?, 2, 0, 'test', ?, 'text/markdown', 1, ?, 1)")) {
            version.setLong(1, documentId); version.setString(2, "object/" + suffix); version.setString(3, "hash-" + suffix); version.executeUpdate();
        }
        try (PreparedStatement cas = connection.prepareStatement("UPDATE document SET status = 2, current_version_no = 2, index_input_revision = 2 WHERE id = ? AND current_version_no = 1 AND index_input_revision = 1 AND status = 2")) {
            cas.setLong(1, documentId);
            if (cas.executeUpdate() != 1) return false;
        }
        return true;
    }

    private void insertJob(Connection connection, long documentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO knowledge_index_job (job_code, request_key, workspace_id, document_id, version_no, index_input_revision, job_type, status, stage, retry_count) VALUES (?, ?, 1, ?, 2, 2, 0, 0, 0, 0)")) {
            statement.setString(1, "job-" + documentId); statement.setString(2, "auto:" + documentId + ":2:2"); statement.setLong(3, documentId); statement.executeUpdate();
        }
    }

    private void insertOutbox(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO knowledge_index_outbox_event (event_code, job_code, status) SELECT CONCAT('event-', job_code), job_code, 0 FROM knowledge_index_job ORDER BY id DESC LIMIT 1")) {
            statement.executeUpdate();
        }
    }

    private long seedActiveDocument(String code, long version, long revision) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO document (document_code, workspace_id, parent_id, owner_user_id, title, file_name, document_type, status, current_version_no, index_input_revision) VALUES (?, 1, 1, 1, '测试', '测试.md', 1, 2, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, code); statement.setLong(2, version); statement.setLong(3, revision); statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) { keys.next(); return keys.getLong(1); }
        }
    }

    private void assertUnchangedAfterRollback(long documentId) throws Exception {
        assertEquals(1L, queryLong("SELECT current_version_no FROM document WHERE id = ?", documentId));
        assertEquals(1L, queryLong("SELECT index_input_revision FROM document WHERE id = ?", documentId));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 2", documentId));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_index_job WHERE document_id = ?", documentId));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_index_outbox_event", documentId));
    }

    private long queryLong(String sql, long documentId) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (sql.contains("?")) {
                statement.setLong(1, documentId);
            }
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    private static Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Statement statement = connection.createStatement()) { statement.execute("USE hex_workspace"); }
        return connection;
    }
}
