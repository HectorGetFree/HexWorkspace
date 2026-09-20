package com.hector.infrastructure.integration;

import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.folder.repository.IFolderRepository;
import com.hector.domain.knowledge.adapter.port.IQueryEmbeddingClient;
import com.hector.domain.knowledge.adapter.port.IRetrievalVectorStore;
import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexStateEntity;
import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.GenerationVectorPointVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexStateRepository;
import com.hector.domain.knowledge.repository.IServingVectorSpaceRepository;
import com.hector.domain.knowledge.service.impl.KnowledgeRetrievalServiceImpl;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import com.hector.infrastructure.dao.IDocumentDao;
import com.hector.infrastructure.dao.IFolderDao;
import com.hector.infrastructure.dao.IKnowledgeIndexGenerationDao;
import com.hector.infrastructure.dao.IKnowledgeIndexStateDao;
import com.hector.infrastructure.dao.IServingVectorSpaceDao;
import com.hector.infrastructure.dao.IWorkspaceDao;
import com.hector.infrastructure.repository.DocumentRepository;
import com.hector.infrastructure.repository.FolderRepository;
import com.hector.infrastructure.repository.KnowledgeIndexGenerationRepository;
import com.hector.infrastructure.repository.KnowledgeIndexStateRepository;
import com.hector.infrastructure.repository.ServingVectorSpaceRepository;
import com.hector.infrastructure.repository.WorkspaceRepository;
import com.hector.infrastructure.rag.vector.QdrantVectorStore;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 MySQL 验证 Retrieval 初次校验与最终复核之间的删除可见性。 */
class KnowledgeRetrievalMySqlIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hex_workspace").withUsername("test").withPassword("test");
    private static final GenericContainer<?> QDRANT = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.11.0"))
            .withExposedPorts(6333);
    private static QdrantVectorStore qdrant;
    private SqlSessionTemplate template;

    @BeforeAll
    static void startDatabase() throws Exception {
        MYSQL.start();
        QDRANT.start();
        qdrant = new QdrantVectorStore();
        ReflectionTestUtils.setField(qdrant, "endpoint", "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            String script = Files.readString(Path.of("..", "docs", "devops", "mysql", "sql", "001-init-hex-workspace.sql"))
                    .replaceAll("(?m)^--.*$", "")
                    .replaceFirst("(?s)CREATE DATABASE.*?;", "")
                    .replaceFirst("(?s)USE `hex_workspace`;", "");
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }

    @AfterAll
    static void stopDatabase() {
        QDRANT.stop();
        MYSQL.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String table : List.of("knowledge_index_state", "knowledge_index_generation", "knowledge_index_job",
                    "knowledge_serving_vector_space", "knowledge_embedding_config", "document_version",
                    "document", "folder", "workspace")) {
                statement.execute("DELETE FROM " + table);
            }
        }
        template = mybatisTemplate();
        seedData();
    }

    @Test
    void shouldRejectDocumentDeletedBetweenInitialValidationAndFinalRecheck() throws Exception {
        IWorkspaceRepository workspaces = workspaceRepository();
        IDocumentRepository documents = documentRepository();
        IFolderRepository folders = folderRepository();
        IKnowledgeIndexGenerationRepository generations = generationRepository();
        IServingVectorSpaceRepository serving = servingRepository();
        IKnowledgeIndexStateRepository states = pausingStateRepository();

        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl();
        ReflectionTestUtils.setField(service, "workspaceRepository", workspaces);
        ReflectionTestUtils.setField(service, "documentRepository", documents);
        ReflectionTestUtils.setField(service, "folderRepository", folders);
        ReflectionTestUtils.setField(service, "knowledgeIndexStateRepository", states);
        ReflectionTestUtils.setField(service, "knowledgeIndexGenerationRepository", generations);
        ReflectionTestUtils.setField(service, "servingVectorSpaceRepository", serving);
        ReflectionTestUtils.setField(service, "queryEmbeddingClient",
                (IQueryEmbeddingClient) (config, query) -> List.of(0.1F, 0.2F, 0.3F));
        ReflectionTestUtils.setField(service, "retrievalVectorStore",
                (IRetrievalVectorStore) (collection, workspaceId, vector, limit) -> List.of(
                        new RetrievalCandidateVO("point-1", 1L, 100L, 1L, 1L, "generation-1", 0,
                                List.of("根目录"), "正文", 1, 1, 0.99D)));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> retrieval = executor.submit(() -> service.retrieve(
                    new RetrievalContextVO(7L, 1L), "查询", null));

            assertTrue(initialValidationPassed.await(10, TimeUnit.SECONDS),
                    "Retrieval 未完成第一次 MySQL 权威校验");
            deleteDocumentAndCommit();
            documentDeletedAndCommitted.set(true);
            releaseFinalRecheck.countDown();

            @SuppressWarnings("unchecked")
            var result = (com.hector.domain.knowledge.model.valobj.RetrievalResultVO) retrieval.get(10, TimeUnit.SECONDS);
            assertTrue(documentDeletedAndCommitted.get(), "删除事务未在最终复核前提交");
            assertTrue(result.hits().isEmpty(), "Document 删除后最终复核不应返回该 Document");
        } finally {
            releaseFinalRecheck.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldFilterInvalidCandidatesUsingAuthoritativeMySqlState() throws Exception {
        seedMixedCandidates();
        String collection = "retrieval-mixed-candidates";
        qdrant.ensureCollection(3, "COSINE", collection);
        qdrant.upsert(collection, List.of(
                point("valid", 100L, 1L, 1L, 1L, "generation-1"),
                point("old-version", 101L, 1L, 1L, 1L, "generation-101"),
                point("old-revision", 102L, 1L, 2L, 1L, "generation-102"),
                point("inactive-generation", 103L, 1L, 1L, 1L, "generation-103"),
                point("deleted-ancestor", 104L, 1L, 1L, 1L, "generation-104")));

        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl();
        ReflectionTestUtils.setField(service, "workspaceRepository", workspaceRepository());
        ReflectionTestUtils.setField(service, "documentRepository", documentRepository());
        ReflectionTestUtils.setField(service, "folderRepository", folderRepository());
        ReflectionTestUtils.setField(service, "knowledgeIndexStateRepository", stateRepository());
        ReflectionTestUtils.setField(service, "knowledgeIndexGenerationRepository", generationRepository());
        ReflectionTestUtils.setField(service, "servingVectorSpaceRepository", servingRepository());
        ReflectionTestUtils.setField(service, "queryEmbeddingClient",
                (IQueryEmbeddingClient) (config, query) -> List.of(0.1F, 0.2F, 0.3F));
        ReflectionTestUtils.setField(service, "retrievalVectorStore",
                (IRetrievalVectorStore) qdrant::searchCandidates);

        var result = service.retrieve(new RetrievalContextVO(7L, 1L), "查询", null);

        assertEquals(1, result.hits().size());
        assertEquals(100L, result.hits().get(0).documentId());
        assertEquals("当前 MySQL 标题", result.hits().get(0).title());
    }

    private GenerationVectorPointVO point(String id, long documentId, long versionNo,
                                          long revision, long chunkNo, String generationId) {
        return new GenerationVectorPointVO(UUID.nameUUIDFromBytes(id.getBytes()).toString(), List.of(0.1F, 0.2F, 0.3F), Map.of(
                "workspace_id", 1L, "document_id", documentId, "version_no", versionNo,
                "index_input_revision", revision, "generation_id", generationId,
                "chunk_no", chunkNo, "heading_path", List.of("候选标题"),
                "body", "Qdrant 标题不可信", "page_start", 1, "page_end", 1));
    }

    private void seedMixedCandidates() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO folder (id, folder_code, workspace_id, parent_id, name, is_root, status) VALUES (2, 'folder-deleted', 1, 1, '已删除目录', 0, 1)");
            statement.execute("UPDATE document SET title = '当前 MySQL 标题' WHERE id = 100");
            statement.execute("INSERT INTO document (id, document_code, workspace_id, parent_id, owner_user_id, title, file_name, document_type, status, current_version_no, index_input_revision) VALUES "
                    + "(101, 'doc-101', 1, 1, 7, '旧版本', 'old-version.md', 1, 2, 2, 1),"
                    + "(102, 'doc-102', 1, 1, 7, '旧修订', 'old-revision.md', 1, 2, 1, 2),"
                    + "(103, 'doc-103', 1, 1, 7, '非活动索引', 'inactive.md', 1, 2, 1, 1),"
                    + "(104, 'doc-104', 1, 2, 7, '祖先已删除', 'deleted-parent.md', 1, 2, 1, 1)");
            for (int i = 2; i <= 5; i++) {
                int documentId = 99 + i;
                int jobId = 10 + i;
                String generationId = "generation-" + documentId;
                statement.execute("INSERT INTO knowledge_index_job (id, job_code, request_key, workspace_id, document_id, version_no, index_input_revision, job_type, status, stage, worker_token) VALUES (" + jobId + ", 'job-" + documentId + "', 'request-" + documentId + "', 1, " + documentId + ", 1, 1, 0, 2, 0, 'worker-1')");
                statement.execute("INSERT INTO knowledge_index_generation (generation_id, job_id, workspace_id, document_id, version_no, index_input_revision, embedding_config_id, chunk_count, status, worker_token) VALUES ('" + generationId + "', " + jobId + ", 1, " + documentId + ", 1, 1, 1, 1, 1, 'worker-1')");
                String activeGeneration = documentId == 103 ? "another-generation" : generationId;
                statement.execute("INSERT INTO knowledge_index_state (document_id, active_generation_id) VALUES (" + documentId + ", '" + activeGeneration + "')");
            }
        }
    }

    private CountDownLatch initialValidationPassed = new CountDownLatch(1);
    private CountDownLatch releaseFinalRecheck = new CountDownLatch(1);
    private AtomicBoolean documentDeletedAndCommitted = new AtomicBoolean();

    private IKnowledgeIndexStateRepository pausingStateRepository() {
        KnowledgeIndexStateRepository delegate = (KnowledgeIndexStateRepository) stateRepository();
        return new IKnowledgeIndexStateRepository() {
            private final AtomicBoolean paused = new AtomicBoolean();

            @Override public Optional<KnowledgeIndexStateEntity> findByDocumentId(Long documentId) {
                return delegate.findByDocumentId(documentId);
            }

            @Override public List<KnowledgeIndexStateEntity> listByDocumentIds(List<Long> documentIds) {
                List<KnowledgeIndexStateEntity> result = delegate.listByDocumentIds(documentIds);
                if (paused.compareAndSet(false, true)) {
                    initialValidationPassed.countDown();
                    await(releaseFinalRecheck);
                }
                return result;
            }

            @Override public void activate(Long documentId, String generationId) {
                delegate.activate(documentId, generationId);
            }
        };
    }

    private void deleteDocumentAndCommit() throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("UPDATE document SET status = 3, deleted_at = NOW() WHERE id = 100")) {
            connection.setAutoCommit(false);
            statement.executeUpdate();
            connection.commit();
        }
    }

    private void seedData() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO workspace (id, workspace_code, workspace_name, is_default, status, owner_user_id) VALUES (1, 'ws-1', '测试空间', 1, 0, 7)");
            statement.execute("INSERT INTO folder (id, folder_code, workspace_id, parent_id, name, is_root, status) VALUES (1, 'folder-root', 1, NULL, '根目录', 1, 0)");
            statement.execute("INSERT INTO document (id, document_code, workspace_id, parent_id, owner_user_id, title, file_name, document_type, status, current_version_no, index_input_revision) VALUES (100, 'doc-100', 1, 1, 7, '原始标题', '原始标题.md', 1, 2, 1, 1)");
            statement.execute("INSERT INTO document_version (document_id, version_no, storage_type, bucket_name, storage_path, content_type, content_size, content_hash, created_by) VALUES (100, 1, 0, 'test', 'documents/100/1', 'text/markdown', 5, 'hash', 7)");
            statement.execute("INSERT INTO knowledge_embedding_config (id, config_hash, provider, model, dimension, distance, collection_name) VALUES (1, 'config-1', 'test', 'test-model', 3, 'COSINE', 'retrieval-mixed-candidates')");
            statement.execute("INSERT INTO knowledge_serving_vector_space (id, embedding_config_id) VALUES (1, 1)");
            statement.execute("INSERT INTO knowledge_index_job (job_code, request_key, workspace_id, document_id, version_no, index_input_revision, job_type, status, stage, worker_token) VALUES ('job-1', 'request-1', 1, 100, 1, 1, 0, 2, 0, 'worker-1')");
            statement.execute("INSERT INTO knowledge_index_generation (generation_id, job_id, workspace_id, document_id, version_no, index_input_revision, embedding_config_id, chunk_count, status, worker_token) VALUES ('generation-1', 1, 1, 100, 1, 1, 1, 1, 1, 'worker-1')");
            statement.execute("INSERT INTO knowledge_index_state (document_id, active_generation_id) VALUES (100, 'generation-1')");
        }
    }

    private IWorkspaceRepository workspaceRepository() {
        WorkspaceRepository repository = new WorkspaceRepository();
        ReflectionTestUtils.setField(repository, "workspaceDao", template.getMapper(IWorkspaceDao.class));
        return repository;
    }

    private IDocumentRepository documentRepository() {
        DocumentRepository repository = new DocumentRepository();
        ReflectionTestUtils.setField(repository, "documentDao", template.getMapper(IDocumentDao.class));
        return repository;
    }

    private IFolderRepository folderRepository() {
        FolderRepository repository = new FolderRepository();
        ReflectionTestUtils.setField(repository, "folderDao", template.getMapper(IFolderDao.class));
        return repository;
    }

    private IKnowledgeIndexGenerationRepository generationRepository() {
        KnowledgeIndexGenerationRepository repository = new KnowledgeIndexGenerationRepository();
        ReflectionTestUtils.setField(repository, "knowledgeIndexGenerationDao", template.getMapper(IKnowledgeIndexGenerationDao.class));
        return repository;
    }

    private KnowledgeIndexStateRepository stateRepository() {
        KnowledgeIndexStateRepository repository = new KnowledgeIndexStateRepository();
        ReflectionTestUtils.setField(repository, "knowledgeIndexStateDao", template.getMapper(IKnowledgeIndexStateDao.class));
        return repository;
    }

    private IServingVectorSpaceRepository servingRepository() {
        ServingVectorSpaceRepository repository = new ServingVectorSpaceRepository();
        ReflectionTestUtils.setField(repository, "servingVectorSpaceDao", template.getMapper(IServingVectorSpaceDao.class));
        return repository;
    }

    private SqlSessionTemplate mybatisTemplate() throws Exception {
        Configuration configuration = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), dataSource()));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String mapper : List.of("workspace.xml", "folder.xml", "document.xml", "document_version.xml",
                "knowledge_index_job.xml", "knowledge_index_generation.xml", "knowledge_index_state.xml",
                "embedding_config.xml",
                "knowledge_serving_vector_space.xml")) {
            try (var input = Files.newInputStream(Path.of("src", "main", "resources",
                    "mybatis", "mapper", "mysql", mapper))) {
                new XMLMapperBuilder(input, configuration, mapper, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory);
    }

    private static DataSource dataSource() {
        org.springframework.jdbc.datasource.DriverManagerDataSource source = new org.springframework.jdbc.datasource.DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(MYSQL.getJdbcUrl());
        source.setUsername(MYSQL.getUsername());
        source.setPassword(MYSQL.getPassword());
        return source;
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("测试同步点等待超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("测试线程被中断", exception);
        }
    }
}
