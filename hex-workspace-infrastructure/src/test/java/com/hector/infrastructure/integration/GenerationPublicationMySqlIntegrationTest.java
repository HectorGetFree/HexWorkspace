package com.hector.infrastructure.integration;

import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexJobRepository;
import com.hector.infrastructure.dao.IDocumentDao;
import com.hector.infrastructure.dao.IKnowledgeIndexGenerationDao;
import com.hector.infrastructure.dao.IKnowledgeIndexJobDao;
import com.hector.infrastructure.dao.IKnowledgeIndexStateDao;
import com.hector.infrastructure.dao.IServingVectorSpaceDao;
import com.hector.infrastructure.rag.GenerationPublicationService;
import com.hector.infrastructure.repository.DocumentRepository;
import com.hector.infrastructure.repository.KnowledgeIndexGenerationRepository;
import com.hector.infrastructure.repository.KnowledgeIndexJobRepository;
import com.hector.infrastructure.repository.KnowledgeIndexStateRepository;
import com.hector.infrastructure.repository.ServingVectorSpaceRepository;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 真实 MySQL 验证 Generation 发布的原子提交与回滚。 */
class GenerationPublicationMySqlIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hex_workspace").withUsername("test").withPassword("test");

    private SqlSessionTemplate template;

    @BeforeAll
    static void startDatabase() throws Exception {
        MYSQL.start();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            String script = Files.readString(Path.of("..", "docs", "devops", "mysql", "sql", "001-init-hex-workspace.sql"))
                    .replaceAll("(?m)^--.*$", "").replaceFirst("(?s)CREATE DATABASE.*?;", "").replaceFirst("(?s)USE `hex_workspace`;", "");
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }

    @AfterAll
    static void stopDatabase() { MYSQL.stop(); }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String table : List.of("knowledge_index_state", "knowledge_serving_vector_space", "knowledge_index_generation", "knowledge_index_job", "knowledge_embedding_config", "document")) {
                statement.execute("DELETE FROM " + table);
            }
        }
        template = mybatisTemplate();
    }

    @Test
    void shouldAtomicallyActivateGenerationAndMarkJobSucceeded() throws Exception {
        Seed seed = seed("generation-new", "worker-a", 1L);
        publicationService(jobRepository()).publish(seed.generationId(), seed.jobCode(), "worker-a");
        assertEquals("generation-new", queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", seed.documentId()));
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", seed.jobCode()));
    }

    @Test
    void shouldRollbackActivePointerWhenJobCompletionFails() throws Exception {
        Seed seed = seed("generation-new", "worker-a", 1L);
        IKnowledgeIndexJobRepository failingJobRepository = new KnowledgeIndexJobRepository() {
            @Override public boolean markSucceededIfOwned(String jobCode, String workerToken, LocalDateTime now) { return false; }
        };
        ReflectionTestUtils.setField(failingJobRepository, "dao", template.getMapper(IKnowledgeIndexJobDao.class));
        assertThrows(BusinessException.class, () -> publicationService(failingJobRepository).publish(seed.generationId(), seed.jobCode(), "worker-a"));
        assertEquals("generation-old", queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", seed.documentId()));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", seed.jobCode()));
    }

    @Test
    void shouldRejectGenerationWithNonServingEmbeddingConfig() throws Exception {
        Seed seed = seed("generation-new", "worker-a", 2L);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO knowledge_embedding_config (id, config_hash, provider, model, dimension, distance, collection_name) VALUES (2, 'config-2', 'test', 'other-model', 3, 'COSINE', 'other_collection')");
        }
        assertThrows(BusinessException.class, () -> publicationService(jobRepository()).publish(seed.generationId(), seed.jobCode(), "worker-a"));
        assertEquals("generation-old", queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", seed.documentId()));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", seed.jobCode()));
    }

    @Test
    void shouldRejectPublicationWhenDocumentVersionChanges() throws Exception {
        Seed seed = seed("generation-version-stale", "worker-a", 1L);
        updateDocument("current_version_no", 2L, seed.documentId());
        assertThrows(BusinessException.class, () -> publicationService(jobRepository()).publish(seed.generationId(), seed.jobCode(), "worker-a"));
        assertEquals("generation-old", queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", seed.documentId()));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", seed.jobCode()));
    }

    @Test
    void shouldRejectPublicationWhenIndexRevisionChanges() throws Exception {
        Seed seed = seed("generation-revision-stale", "worker-a", 1L);
        updateDocument("index_input_revision", 2L, seed.documentId());
        assertThrows(BusinessException.class, () -> publicationService(jobRepository()).publish(seed.generationId(), seed.jobCode(), "worker-a"));
        assertEquals("generation-old", queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", seed.documentId()));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", seed.jobCode()));
    }

    @Test
    void shouldAllowOnlyOneConcurrentPublisherToComplete() throws Exception {
        Seed seed = seed("generation-concurrent", "worker-a", 1L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> publishAfter(start, seed));
            Future<Boolean> second = executor.submit(() -> publishAfter(start, seed));
            start.countDown();
            assertEquals(1, List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", seed.jobCode()));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_state WHERE document_id = ?", seed.documentId().toString()));
    }

    @Test
    void shouldRejectStaleWorkerGenerationStateWriteAfterLeaseTakeover() throws Exception {
        Seed stale = seed("generation-stale", "worker-a", 1L);
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE knowledge_index_generation generation "
                        + "INNER JOIN knowledge_index_job job ON job.id = generation.job_id "
                        + "SET generation.status = 0, job.worker_token = ?, job.running_lease_until = ? "
                        + "WHERE job.job_code = ?")) {
            statement.setString(1, "worker-b");
            statement.setObject(2, LocalDateTime.now().plusMinutes(5));
            statement.setString(3, stale.jobCode());
            statement.executeUpdate();
        }

        KnowledgeIndexGenerationRepository generations = generationRepository();
        assertEquals(false, generations.markFailedIfOwned(stale.generationId(), "worker-a", LocalDateTime.now()));
        assertEquals(0L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", stale.generationId()));

        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO knowledge_index_generation (generation_id, job_id, workspace_id, document_id, version_no, index_input_revision, chunk_count, status, worker_token) "
                        + "SELECT ?, id, workspace_id, document_id, version_no, index_input_revision, 0, 0, ? FROM knowledge_index_job WHERE job_code = ?")) {
            statement.setString(1, "generation-takeover");
            statement.setString(2, "worker-b");
            statement.setString(3, stale.jobCode());
            statement.executeUpdate();
        }
        assertEquals(true, generations.markFailedIfOwned("generation-takeover", "worker-b", LocalDateTime.now()));
    }

    @Test
    void shouldNotClaimGenerationAfterPublicationHasActivatedIt() throws Exception {
        Seed seed = seed("generation-publish-first", "worker-a", 1L);
        publicationService(jobRepository()).publish(seed.generationId(), seed.jobCode(), "worker-a");

        boolean claimed = generationRepository().claimForCleanup(seed.generationId(), "cleanup-a",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusMinutes(10), LocalDateTime.now());

        assertEquals(false, claimed);
        assertEquals(seed.generationId(), queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", seed.documentId()));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", seed.generationId()));
    }

    @Test
    void shouldRejectPublicationAfterCleanupHasClaimedGeneration() throws Exception {
        Seed seed = seed("generation-cleanup-first", "worker-a", 1L);
        boolean claimed = generationRepository().claimForCleanup(seed.generationId(), "cleanup-a",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusMinutes(10), LocalDateTime.now());
        assertEquals(true, claimed);

        assertThrows(BusinessException.class, () -> publicationService(jobRepository()).publish(seed.generationId(), seed.jobCode(), "worker-a"));
        assertEquals("generation-old", queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", seed.documentId()));
        assertEquals(3L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", seed.generationId()));
    }

    private GenerationPublicationService publicationService(IKnowledgeIndexJobRepository jobs) {
        DocumentRepository documents = new DocumentRepository();
        ReflectionTestUtils.setField(documents, "documentDao", template.getMapper(IDocumentDao.class));
        KnowledgeIndexGenerationRepository generations = new KnowledgeIndexGenerationRepository();
        ReflectionTestUtils.setField(generations, "knowledgeIndexGenerationDao", template.getMapper(IKnowledgeIndexGenerationDao.class));
        KnowledgeIndexStateRepository state = new KnowledgeIndexStateRepository();
        ReflectionTestUtils.setField(state, "knowledgeIndexStateDao", template.getMapper(IKnowledgeIndexStateDao.class));
        ServingVectorSpaceRepository serving = new ServingVectorSpaceRepository();
        ReflectionTestUtils.setField(serving, "servingVectorSpaceDao", template.getMapper(IServingVectorSpaceDao.class));
        GenerationPublicationService service = new GenerationPublicationService();
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(new DataSourceTransactionManager(template.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource())));
        ReflectionTestUtils.setField(service, "documentRepository", documents);
        ReflectionTestUtils.setField(service, "generationRepository", generations);
        ReflectionTestUtils.setField(service, "jobRepository", jobs);
        ReflectionTestUtils.setField(service, "indexStateRepository", state);
        ReflectionTestUtils.setField(service, "servingVectorSpaceRepository", serving);
        return service;
    }

    private KnowledgeIndexJobRepository jobRepository() {
        KnowledgeIndexJobRepository repository = new KnowledgeIndexJobRepository();
        ReflectionTestUtils.setField(repository, "dao", template.getMapper(IKnowledgeIndexJobDao.class));
        return repository;
    }

    private KnowledgeIndexGenerationRepository generationRepository() {
        KnowledgeIndexGenerationRepository repository = new KnowledgeIndexGenerationRepository();
        ReflectionTestUtils.setField(repository, "knowledgeIndexGenerationDao", template.getMapper(IKnowledgeIndexGenerationDao.class));
        return repository;
    }

    private boolean publishAfter(CountDownLatch start, Seed seed) throws InterruptedException {
        start.await();
        try {
            publicationService(jobRepository()).publish(seed.generationId(), seed.jobCode(), "worker-a");
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private void updateDocument(String column, long value, long documentId) throws Exception {
        if (!List.of("current_version_no", "index_input_revision").contains(column)) {
            throw new IllegalArgumentException("非法测试字段");
        }
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE document SET " + column + " = ? WHERE id = ?")) {
            statement.setLong(1, value);
            statement.setLong(2, documentId);
            statement.executeUpdate();
        }
    }

    private Seed seed(String generationId, String workerToken, long configId) throws Exception {
        try (Connection connection = connection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO knowledge_embedding_config (id, config_hash, provider, model, dimension, distance, collection_name) VALUES (1, 'config-1', 'test', 'test-model', 3, 'COSINE', 'test_collection')");
                statement.execute("INSERT INTO knowledge_serving_vector_space (id, embedding_config_id) VALUES (1, 1)");
                statement.execute("INSERT INTO document (id, document_code, workspace_id, parent_id, owner_user_id, title, file_name, document_type, status, current_version_no, index_input_revision) VALUES (10, 'doc-10', 1, 1, 1, '测试', '测试.md', 1, 2, 1, 1)");
                statement.execute("INSERT INTO knowledge_index_state (document_id, active_generation_id) VALUES (10, 'generation-old')");
            }
            try (PreparedStatement job = connection.prepareStatement("INSERT INTO knowledge_index_job (job_code, request_key, workspace_id, document_id, version_no, index_input_revision, job_type, status, stage, worker_token, running_lease_until, retry_count) VALUES (?, ?, 1, 10, 1, 1, 0, 1, 0, ?, ?, 0)", Statement.RETURN_GENERATED_KEYS)) {
                job.setString(1, "job-" + generationId); job.setString(2, "request-" + generationId); job.setString(3, workerToken); job.setObject(4, LocalDateTime.now().plusMinutes(5)); job.executeUpdate();
                try (ResultSet keys = job.getGeneratedKeys()) { keys.next(); long jobId = keys.getLong(1);
                    try (PreparedStatement generation = connection.prepareStatement("INSERT INTO knowledge_index_generation (generation_id, job_id, workspace_id, document_id, version_no, index_input_revision, embedding_config_id, chunk_count, status, worker_token) VALUES (?, ?, 1, 10, 1, 1, ?, 0, 1, ?)") ) {
                        generation.setString(1, generationId); generation.setLong(2, jobId); generation.setLong(3, configId); generation.setString(4, workerToken); generation.executeUpdate();
                    }
                }
            }
        }
        return new Seed(10L, "job-" + generationId, generationId);
    }

    private SqlSessionTemplate mybatisTemplate() throws Exception {
        DataSource dataSource = new org.springframework.jdbc.datasource.SimpleDriverDataSource(new com.mysql.cj.jdbc.Driver(), MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        for (String mapper : List.of("document.xml", "knowledge_index_job.xml", "knowledge_index_generation.xml", "knowledge_index_state.xml", "knowledge_serving_vector_space.xml")) {
            new XMLMapperBuilder(org.apache.ibatis.io.Resources.getResourceAsStream("mybatis/mapper/mysql/" + mapper), configuration, mapper, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory);
    }

    private long queryLong(String sql, String value) throws Exception { try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) { statement.setString(1, value); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); } } }
    private String queryString(String sql, long value) throws Exception { try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) { statement.setLong(1, value); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getString(1); } } }
    private static Connection connection() throws Exception { return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); }
    private record Seed(Long documentId, String jobCode, String generationId) { }
}
