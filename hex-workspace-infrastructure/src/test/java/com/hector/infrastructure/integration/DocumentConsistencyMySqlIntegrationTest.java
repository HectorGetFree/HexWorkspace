package com.hector.infrastructure.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import com.hector.domain.document.model.valobj.DocumentStorageObjectVO;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.document.service.impl.DocumentUploadTaskServiceImpl;
import com.hector.domain.knowledge.service.impl.KnowledgeIndexJobServiceImpl;
import com.hector.domain.knowledge.repository.IKnowledgeIndexJobRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexOutboxEventRepository;
import com.hector.infrastructure.dao.IDocumentDao;
import com.hector.infrastructure.dao.IDocumentUploadTaskDao;
import com.hector.infrastructure.dao.IDocumentVersionDao;
import com.hector.infrastructure.dao.IKnowledgeIndexJobDao;
import com.hector.infrastructure.dao.IKnowledgeIndexOutboxEventDao;
import com.hector.infrastructure.repository.DocumentRepository;
import com.hector.infrastructure.repository.DocumentUploadTaskRepository;
import com.hector.infrastructure.repository.DocumentVersionRepository;
import com.hector.infrastructure.repository.KnowledgeIndexJobRepository;
import com.hector.infrastructure.repository.KnowledgeIndexOutboxEventRepository;

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
import java.lang.reflect.Proxy;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 一致性硬门槛真实 MySQL 测试。
 *
 * <p>只替代对象存储与 MQ；Document 条件更新、唯一约束及数据库事务均使用 Testcontainers MySQL。</p>
 */
class DocumentConsistencyMySqlIntegrationTest {

    private DocumentUploadTaskServiceImpl uploadTaskService;
    private KnowledgeIndexJobRepository jobRepository;
    private KnowledgeIndexOutboxEventRepository knowledgeOutboxRepository;

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
        initialiseProductionService();
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

    @Test
    void concurrentUploadTaskExecution_shouldApplyOnlyOnceThroughProductionService() throws Exception {
        long documentId = seedActiveDocument("doc_service_duplicate", 1L, 1L);
        try (Connection connection = connection(); PreparedStatement writing = connection.prepareStatement("UPDATE document SET status = 1 WHERE id = ?")) {
            writing.setLong(1, documentId);
            writing.executeUpdate();
        }
        try (Connection connection = connection(); PreparedStatement task = connection.prepareStatement("INSERT INTO document_upload_task (task_code, document_id, version_no, created_by, staging_path, target_path, content_type, bucket_name, content_size, content_hash, status, retry_count) VALUES ('task-service', ?, 2, 1, 'staging/test', 'document/test', 'text/markdown', 'test', 1, 'hash-v2', 0, 0)")) {
            task.setLong(1, documentId);
            task.executeUpdate();
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> { await(start); uploadTaskService.execute("task-service"); });
            Future<?> second = executor.submit(() -> { await(start); uploadTaskService.execute("task-service"); });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertEquals(2L, queryLong("SELECT current_version_no FROM document WHERE id = ?", documentId));
        assertEquals(2L, queryLong("SELECT index_input_revision FROM document WHERE id = ?", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 2", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_job WHERE document_id = ? AND version_no = 2", documentId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_outbox_event", documentId));
    }

    @Test
    void indexJobOrKnowledgeOutboxFailure_shouldRollbackProductionUploadCompletion() throws Exception {
        long documentId = seedWritingDocumentAndPendingTask("doc_service_job_fault", "task-job-fault");
        configureKnowledgeServiceFailure(true, false);
        uploadTaskService.execute("task-job-fault");
        assertProductionRollback(documentId, "task-job-fault");

        documentId = seedWritingDocumentAndPendingTask("doc_service_outbox_fault", "task-outbox-fault");
        configureKnowledgeServiceFailure(false, true);
        uploadTaskService.execute("task-outbox-fault");
        assertProductionRollback(documentId, "task-outbox-fault");
    }

    private long seedWritingDocumentAndPendingTask(String code, String taskCode) throws Exception {
        long documentId = seedActiveDocument(code, 1L, 1L);
        try (Connection connection = connection(); PreparedStatement writing = connection.prepareStatement("UPDATE document SET status = 1 WHERE id = ?"); PreparedStatement task = connection.prepareStatement("INSERT INTO document_upload_task (task_code, document_id, version_no, created_by, staging_path, target_path, content_type, bucket_name, content_size, content_hash, status, retry_count) VALUES (?, ?, 2, 1, 'staging/test', 'document/test', 'text/markdown', 'test', 1, 'hash-v2', 0, 0)")) {
            writing.setLong(1, documentId); writing.executeUpdate(); task.setString(1, taskCode); task.setLong(2, documentId); task.executeUpdate();
        }
        return documentId;
    }

    private void configureKnowledgeServiceFailure(boolean failJob, boolean failOutbox) {
        IKnowledgeIndexJobRepository jobs = faultProxy(IKnowledgeIndexJobRepository.class, jobRepository, "saveIfAbsent", failJob);
        IKnowledgeIndexOutboxEventRepository outbox = faultProxy(IKnowledgeIndexOutboxEventRepository.class, knowledgeOutboxRepository, "save", failOutbox);
        KnowledgeIndexJobServiceImpl service = new KnowledgeIndexJobServiceImpl();
        ReflectionTestUtils.setField(service, "jobRepository", jobs); ReflectionTestUtils.setField(service, "outboxEventRepository", outbox);
        ReflectionTestUtils.setField(uploadTaskService, "knowledgeIndexJobService", service);
    }

    @SuppressWarnings("unchecked")
    private <T> T faultProxy(Class<T> type, T delegate, String methodName, boolean fail) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (fail && methodName.equals(method.getName())) throw new IllegalStateException("测试注入失败: " + methodName);
            try { return method.invoke(delegate, args); } catch (java.lang.reflect.InvocationTargetException exception) { throw exception.getCause(); }
        });
    }

    private void assertProductionRollback(long documentId, String taskCode) throws Exception {
        assertEquals(1L, queryLong("SELECT current_version_no FROM document WHERE id = ?", documentId));
        assertEquals(1L, queryLong("SELECT index_input_revision FROM document WHERE id = ?", documentId));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM document_version WHERE document_id = ? AND version_no = 2", documentId));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_index_job WHERE document_id = ?", documentId));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_index_outbox_event", documentId));
        assertEquals(3L, queryLong("SELECT status FROM document_upload_task WHERE task_code = ?", taskCode));
    }

    private static void await(CountDownLatch latch) { try { latch.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); } }

    private void initialiseProductionService() throws Exception {
        DataSource dataSource = new org.springframework.jdbc.datasource.SimpleDriverDataSource(new com.mysql.cj.jdbc.Driver(), MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        for (String mapper : List.of("document.xml", "document_version.xml", "document_upload_task.xml", "knowledge_index_job.xml", "knowledge_index_outbox_event.xml")) {
            new XMLMapperBuilder(org.apache.ibatis.io.Resources.getResourceAsStream("mybatis/mapper/mysql/" + mapper), configuration, mapper, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        DocumentRepository documents = new DocumentRepository(); ReflectionTestUtils.setField(documents, "documentDao", template.getMapper(IDocumentDao.class));
        DocumentVersionRepository versions = new DocumentVersionRepository(); ReflectionTestUtils.setField(versions, "documentVersionDao", template.getMapper(IDocumentVersionDao.class));
        DocumentUploadTaskRepository tasks = new DocumentUploadTaskRepository(); ReflectionTestUtils.setField(tasks, "documentUploadTaskDao", template.getMapper(IDocumentUploadTaskDao.class));
        jobRepository = new KnowledgeIndexJobRepository(); ReflectionTestUtils.setField(jobRepository, "dao", template.getMapper(IKnowledgeIndexJobDao.class));
        knowledgeOutboxRepository = new KnowledgeIndexOutboxEventRepository(); ReflectionTestUtils.setField(knowledgeOutboxRepository, "dao", template.getMapper(IKnowledgeIndexOutboxEventDao.class));
        KnowledgeIndexJobServiceImpl indexService = new KnowledgeIndexJobServiceImpl(); ReflectionTestUtils.setField(indexService, "jobRepository", jobRepository); ReflectionTestUtils.setField(indexService, "outboxEventRepository", knowledgeOutboxRepository);
        uploadTaskService = new DocumentUploadTaskServiceImpl();
        ReflectionTestUtils.setField(uploadTaskService, "taskRepository", tasks); ReflectionTestUtils.setField(uploadTaskService, "documentRepository", documents); ReflectionTestUtils.setField(uploadTaskService, "documentVersionRepository", versions); ReflectionTestUtils.setField(uploadTaskService, "knowledgeIndexJobService", indexService); ReflectionTestUtils.setField(uploadTaskService, "storageRepository", new NoopStorage()); ReflectionTestUtils.setField(uploadTaskService, "transactionTemplate", new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private static class NoopStorage implements IDocumentStorageRepository {
        public String getBucketName() { return "test"; } public void upload(String key, byte[] value, String type) { } public void upload(String key, java.io.InputStream value, long size, String type) { } public void copy(String source, String target, String type) { } public byte[] download(String key) { return new byte[0]; } public void delete(String key) { } public List<DocumentStorageObjectVO> listByPrefix(String prefix) { return List.of(); }
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

    private long queryLong(String sql, String value) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    private static Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Statement statement = connection.createStatement()) { statement.execute("USE hex_workspace"); }
        return connection;
    }
}
