package com.hector.infrastructure.integration;

import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.knowledge.adapter.port.IGenerationVectorStore;
import com.hector.infrastructure.dao.IEmbeddingConfigDao;
import com.hector.infrastructure.dao.IKnowledgeIndexGenerationDao;
import com.hector.infrastructure.dao.IKnowledgeIndexStateDao;
import com.hector.infrastructure.rag.KnowledgeGenerationCleanupService;
import com.hector.infrastructure.rag.vector.QdrantVectorStore;
import com.hector.infrastructure.repository.DocumentStorageRepository;
import com.hector.infrastructure.repository.EmbeddingConfigRepository;
import com.hector.infrastructure.repository.KnowledgeIndexGenerationRepository;
import io.minio.MinioClient;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 MySQL、Qdrant 和 MinIO 验证 Generation 正常清理及重复执行幂等性。 */
class GenerationCleanupIntegrationTest {
    private static final String BUCKET = "phase4-cleanup-test";
    private static final String COLLECTION = "phase4-cleanup-vectors";
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hex_workspace").withUsername("test").withPassword("test");
    private static final GenericContainer<?> QDRANT = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.11.0"))
            .withExposedPorts(6333);
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:RELEASE.2024-06-13T22-53-53Z"))
            .withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000).withCommand("server", "/data");

    private SqlSessionTemplate template;
    private DocumentStorageRepository storage;
    private QdrantVectorStore vectors;

    @BeforeAll
    static void startContainers() throws Exception {
        MYSQL.start();
        QDRANT.start();
        MINIO.start();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            String script = Files.readString(Path.of("..", "docs", "devops", "mysql", "sql", "001-init-hex-workspace.sql"))
                    .replaceAll("(?m)^--.*$", "").replaceFirst("(?s)CREATE DATABASE.*?;", "")
                    .replaceFirst("(?s)USE `hex_workspace`;", "");
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }

    @AfterAll
    static void stopContainers() {
        MINIO.stop();
        QDRANT.stop();
        MYSQL.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String table : List.of("knowledge_index_state", "knowledge_index_generation", "knowledge_index_job",
                    "knowledge_serving_vector_space", "knowledge_embedding_config", "document")) {
                statement.execute("DELETE FROM " + table);
            }
        }
        template = mybatisTemplate();
        MinioClient minioClient = MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials("minioadmin", "minioadmin").build();
        storage = new DocumentStorageRepository();
        ReflectionTestUtils.setField(storage, "minioClient", minioClient);
        ReflectionTestUtils.setField(storage, "bucketName", BUCKET);
        waitForMinio(minioClient);
        storage.upload("chunks/generation-a.json", new ByteArrayInputStream("A".getBytes()), 1, "application/json");
        storage.upload("chunks/generation-b.json", new ByteArrayInputStream("B".getBytes()), 1, "application/json");
        vectors = new QdrantVectorStore();
        ReflectionTestUtils.setField(vectors, "endpoint", "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
        vectors.ensureCollection(3, "COSINE", COLLECTION);
        seedDatabase();
        vectors.upsert(COLLECTION, List.of(point("point-a", "generation-a"), point("point-b", "generation-b")));
    }

    @Test
    void shouldCleanExpiredInactiveGenerationAndRemainIdempotent() throws Exception {
        KnowledgeGenerationCleanupService service = cleanupService();

        assertEquals(1, service.cleanExpired(LocalDateTime.now().minusDays(7), 20, 10));
        assertEquals(4, status("generation-a"));
        assertEquals(1, status("generation-b"));
        assertEquals("generation-b", activeGeneration());
        assertEquals(0, vectors.countByGeneration(COLLECTION, "generation-a"));
        assertEquals(1, vectors.countByGeneration(COLLECTION, "generation-b"));
        assertFalse(objectExists("chunks/generation-a.json"));
        assertTrue(objectExists("chunks/generation-b.json"));

        assertEquals(0, service.cleanExpired(LocalDateTime.now().minusDays(7), 20, 10));
        assertEquals(4, status("generation-a"));
        assertEquals(1, status("generation-b"));
        assertEquals("generation-b", activeGeneration());
        assertEquals(1, vectors.countByGeneration(COLLECTION, "generation-b"));
        assertTrue(objectExists("chunks/generation-b.json"));
    }

    @Test
    void shouldRecoverCleanupAfterPartialFailureAndExpiredLease() throws Exception {
        KnowledgeIndexGenerationRepository generations = generationRepository();
        String workerAToken = "worker-a-cleanup-token";
        assertTrue(generations.claimForCleanup("generation-a", workerAToken,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusMinutes(10), LocalDateTime.now()));
        assertEquals(3, status("generation-a"));

        // 模拟 Worker A 在 Qdrant 删除成功后崩溃：不调用生产异常释放逻辑，也不删除 MinIO Artifact。
        vectors.deleteByGeneration(COLLECTION, "generation-a");
        assertEquals(0, vectors.countByGeneration(COLLECTION, "generation-a"));
        assertTrue(objectExists("chunks/generation-a.json"));

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE knowledge_index_generation SET cleanup_lease_until = NOW() - INTERVAL 1 MINUTE WHERE generation_id = 'generation-a'");
        }
        assertEquals(3, status("generation-a"));

        KnowledgeGenerationCleanupService workerB = cleanupService();
        assertEquals(1, workerB.cleanExpired(LocalDateTime.now().minusDays(7), 20, 10));
        assertEquals(4, status("generation-a"));
        assertFalse(objectExists("chunks/generation-a.json"));
        assertEquals(0, vectors.countByGeneration(COLLECTION, "generation-a"));
        assertFalse(generations.markCleanedIfOwned("generation-a", workerAToken, LocalDateTime.now()));

        assertEquals(1, status("generation-b"));
        assertEquals("generation-b", activeGeneration());
        assertEquals(1, vectors.countByGeneration(COLLECTION, "generation-b"));
        assertTrue(objectExists("chunks/generation-b.json"));
    }

    private KnowledgeGenerationCleanupService cleanupService() {
        KnowledgeGenerationCleanupService service = new KnowledgeGenerationCleanupService();
        KnowledgeIndexGenerationRepository generations = new KnowledgeIndexGenerationRepository();
        ReflectionTestUtils.setField(generations, "knowledgeIndexGenerationDao", template.getMapper(IKnowledgeIndexGenerationDao.class));
        EmbeddingConfigRepository configs = new EmbeddingConfigRepository();
        ReflectionTestUtils.setField(configs, "embeddingConfigDao", template.getMapper(IEmbeddingConfigDao.class));
        ReflectionTestUtils.setField(service, "generationRepository", generations);
        ReflectionTestUtils.setField(service, "embeddingConfigRepository", configs);
        ReflectionTestUtils.setField(service, "vectorStore", (IGenerationVectorStore) vectors);
        ReflectionTestUtils.setField(service, "storageRepository", (IDocumentStorageRepository) storage);
        return service;
    }

    private KnowledgeIndexGenerationRepository generationRepository() {
        KnowledgeIndexGenerationRepository repository = new KnowledgeIndexGenerationRepository();
        ReflectionTestUtils.setField(repository, "knowledgeIndexGenerationDao", template.getMapper(IKnowledgeIndexGenerationDao.class));
        return repository;
    }

    private void seedDatabase() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO knowledge_embedding_config (id, config_hash, provider, model, dimension, distance, collection_name) VALUES (1, 'cleanup-config', 'test', 'test', 3, 'COSINE', '" + COLLECTION + "')");
            statement.execute("INSERT INTO knowledge_index_job (id, job_code, request_key, workspace_id, document_id, version_no, index_input_revision, job_type, status, stage, worker_token) VALUES (1, 'cleanup-job-a', 'cleanup-request-a', 1, 100, 1, 1, 0, 2, 0, 'worker')");
            statement.execute("INSERT INTO knowledge_index_job (id, job_code, request_key, workspace_id, document_id, version_no, index_input_revision, job_type, status, stage, worker_token) VALUES (2, 'cleanup-job-b', 'cleanup-request-b', 1, 100, 1, 1, 0, 2, 0, 'worker')");
            statement.execute("INSERT INTO knowledge_index_generation (generation_id, job_id, workspace_id, document_id, version_no, index_input_revision, embedding_config_id, chunk_artifact_key, chunk_count, status, worker_token, created_at, updated_at) VALUES ('generation-a', 1, 1, 100, 1, 1, 1, 'chunks/generation-a.json', 1, 1, 'worker', NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 10 DAY)");
            statement.execute("INSERT INTO knowledge_index_generation (generation_id, job_id, workspace_id, document_id, version_no, index_input_revision, embedding_config_id, chunk_artifact_key, chunk_count, status, worker_token) VALUES ('generation-b', 2, 1, 100, 1, 1, 1, 'chunks/generation-b.json', 1, 1, 'worker')");
            statement.execute("INSERT INTO knowledge_index_state (document_id, active_generation_id) VALUES (100, 'generation-b')");
        }
    }

    private com.hector.domain.knowledge.model.valobj.GenerationVectorPointVO point(String id, String generationId) {
        return new com.hector.domain.knowledge.model.valobj.GenerationVectorPointVO(
                java.util.UUID.nameUUIDFromBytes(id.getBytes()).toString(), List.of(0.1F, 0.2F, 0.3F),
                Map.of("generation_id", generationId));
    }

    private int status(String generationId) throws Exception {
        try (Connection connection = connection(); var statement = connection.prepareStatement("SELECT status FROM knowledge_index_generation WHERE generation_id = ?")) {
            statement.setString(1, generationId);
            try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    private String activeGeneration() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement(); var result = statement.executeQuery("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = 100")) {
            result.next(); return result.getString(1);
        }
    }

    private boolean objectExists(String key) {
        try { storage.download(key); return true; } catch (Exception exception) { return false; }
    }

    private void waitForMinio(MinioClient client) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                client.listBuckets();
                return;
            } catch (Exception exception) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("MinIO 测试容器未在限定时间内就绪");
    }

    private SqlSessionTemplate mybatisTemplate() throws Exception {
        Configuration configuration = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), dataSource()));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String mapper : List.of("embedding_config.xml", "knowledge_index_generation.xml", "knowledge_index_state.xml")) {
            try (var input = Files.newInputStream(Path.of("src", "main", "resources", "mybatis", "mapper", "mysql", mapper))) {
                new XMLMapperBuilder(input, configuration, mapper, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory);
    }

    private static DataSource dataSource() {
        org.springframework.jdbc.datasource.DriverManagerDataSource source = new org.springframework.jdbc.datasource.DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(MYSQL.getJdbcUrl()); source.setUsername(MYSQL.getUsername()); source.setPassword(MYSQL.getPassword());
        return source;
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
