package com.hector.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.knowledge.adapter.port.IEmbeddingClient;
import com.hector.domain.knowledge.model.valobj.EmbeddingVectorVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexJobRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexOutboxEventRepository;
import com.hector.domain.knowledge.service.IKnowledgeIndexWorkerService;
import com.hector.domain.knowledge.service.impl.KnowledgeIndexJobServiceImpl;
import com.hector.infrastructure.dao.IDocumentDao;
import com.hector.infrastructure.dao.IDocumentVersionDao;
import com.hector.infrastructure.dao.IEmbeddingConfigDao;
import com.hector.infrastructure.dao.IKnowledgeIndexGenerationDao;
import com.hector.infrastructure.dao.IKnowledgeIndexJobDao;
import com.hector.infrastructure.dao.IKnowledgeIndexOutboxEventDao;
import com.hector.infrastructure.dao.IKnowledgeIndexStateDao;
import com.hector.infrastructure.dao.IKnowledgeParseArtifactDao;
import com.hector.infrastructure.dao.IServingVectorSpaceDao;
import com.hector.infrastructure.rag.GenerationIndexBuildService;
import com.hector.infrastructure.rag.GenerationPublicationService;
import com.hector.infrastructure.rag.KnowledgeArtifactPreparationService;
import com.hector.infrastructure.rag.KnowledgeIndexWorkerService;
import com.hector.infrastructure.rag.chunk.StructureAwareDocumentChunker;
import com.hector.infrastructure.rag.loader.DocumentVersionLoader;
import com.hector.infrastructure.rag.parser.DocumentParserSelector;
import com.hector.infrastructure.rag.parser.MarkdownDocumentParser;
import com.hector.infrastructure.rag.parser.TextDocumentParser;
import com.hector.infrastructure.rag.vector.QdrantVectorStore;
import com.hector.infrastructure.repository.DocumentRepository;
import com.hector.infrastructure.repository.DocumentStorageRepository;
import com.hector.infrastructure.repository.DocumentVersionRepository;
import com.hector.infrastructure.repository.EmbeddingConfigRepository;
import com.hector.infrastructure.repository.KnowledgeIndexGenerationRepository;
import com.hector.infrastructure.repository.KnowledgeIndexJobRepository;
import com.hector.infrastructure.repository.KnowledgeIndexOutboxEventRepository;
import com.hector.infrastructure.repository.KnowledgeIndexStateRepository;
import com.hector.infrastructure.repository.KnowledgeParseArtifactRepository;
import com.hector.infrastructure.repository.ServingVectorSpaceRepository;
import com.hector.trigger.mq.KnowledgeIndexTaskDispatcher;
import com.hector.trigger.mq.KnowledgeIndexTaskListener;
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
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 3 真实 MySQL、MinIO、Qdrant、RabbitMQ 的知识索引完整自动链路验收。 */
class Phase3KnowledgeIndexEndToEndIntegrationTest {
    private static final String BUCKET = "phase3-e2e";
    private static final String EXCHANGE = "phase3.knowledge.exchange";
    private static final String QUEUE = "phase3.knowledge.queue";
    private static final String ROUTING_KEY = "knowledge.index.requested";

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hex_workspace")
            .withUsername("test")
            .withPassword("test");
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("quay.io/minio/minio:RELEASE.2024-06-13T22-53-53Z"))
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000)
            .withCommand("server", "/data");
    private static final GenericContainer<?> QDRANT = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.11.0"))
            .withExposedPorts(6333);
    private static final GenericContainer<?> RABBIT = new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-alpine"))
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    private SqlSessionTemplate template;
    private RabbitTemplate rabbitTemplate;
    private CachingConnectionFactory rabbitConnectionFactory;

    @BeforeAll
    static void startContainers() throws Exception {
        MYSQL.start();
        MINIO.start();
        QDRANT.start();
        RABBIT.start();
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
    static void stopContainers() {
        RABBIT.stop();
        QDRANT.stop();
        MINIO.stop();
        MYSQL.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String table : List.of(
                    "knowledge_index_outbox_event", "knowledge_index_state", "knowledge_serving_vector_space",
                    "knowledge_index_generation", "knowledge_parse_artifact", "knowledge_index_job",
                    "knowledge_embedding_config", "document_version", "document")) {
                statement.execute("DELETE FROM " + table);
            }
        }
        template = mybatisTemplate();
        rabbitConnectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getMappedPort(5672));
        rabbitConnectionFactory.setUsername("guest");
        rabbitConnectionFactory.setPassword("guest");
        rabbitConnectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        rabbitTemplate = new RabbitTemplate(rabbitConnectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());

        RabbitAdmin admin = new RabbitAdmin(rabbitConnectionFactory);
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        Queue queue = new Queue(QUEUE, true, false, false);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY));
        while (rabbitTemplate.receive(QUEUE) != null) {
            // 清空上一条用例可能残留的消息，保证本用例只验证本次 Outbox。
        }
    }

    @Test
    void shouldAutomaticallyBuildAndPublishGenerationThroughAllRealInfrastructure() throws Exception {
        DocumentEntity document = seedEffectiveDocument("# Phase 3\n\n第一段正文。\n\n## 小节\n\n第二段正文。");
        IKnowledgeIndexJobRepository jobs = jobRepository();
        IKnowledgeIndexOutboxEventRepository outbox = outboxRepository();
        registerIndexJobAndOutbox(document, jobs, outbox);

        CountDownLatch completed = new CountDownLatch(2);
        KnowledgeIndexWorkerService worker = workerService(completed);
        SimpleMessageListenerContainer listenerContainer = listenerContainer(worker);
        listenerContainer.start();
        String jobCode = null;
        try {
            dispatcher(outbox, worker).dispatch();
            jobCode = queryString("SELECT job_code FROM knowledge_index_job WHERE document_id = ?", document.getId());
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, jobCode);

            assertTrue(completed.await(20, TimeUnit.SECONDS), "知识索引 Worker 未完成首次执行与重复投递处理");
        } finally {
            listenerContainer.stop();
        }

        String generationId = queryString("SELECT generation_id FROM knowledge_index_generation WHERE document_id = ?", document.getId());
        String chunkKey = queryString("SELECT chunk_artifact_key FROM knowledge_index_generation WHERE generation_id = ?", generationId);
        Long chunkCount = queryLong("SELECT chunk_count FROM knowledge_index_generation WHERE generation_id = ?", generationId);
        DocumentStorageRepository storage = storage();

        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationId));
        assertEquals(generationId, queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", document.getId()));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_outbox_event WHERE job_code = ?", jobCode));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_generation WHERE document_id = ?", document.getId().toString()));
        assertNotNull(chunkKey);
        assertTrue(storage.download(chunkKey).length > 0);

        QdrantVectorStore vectors = vectorStore();
        assertEquals(chunkCount.longValue(), vectors.countByGeneration("phase3_e2e_vectors", generationId));
    }

    private void registerIndexJobAndOutbox(DocumentEntity document, IKnowledgeIndexJobRepository jobs,
                                           IKnowledgeIndexOutboxEventRepository outbox) {
        KnowledgeIndexJobServiceImpl service = new KnowledgeIndexJobServiceImpl();
        ReflectionTestUtils.setField(service, "jobRepository", jobs);
        ReflectionTestUtils.setField(service, "outboxEventRepository", outbox);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
        transaction.executeWithoutResult(status -> service.registerAutomatic(document));
    }

    private KnowledgeIndexTaskDispatcher dispatcher(IKnowledgeIndexOutboxEventRepository outbox,
                                                     IKnowledgeIndexWorkerService worker) {
        KnowledgeIndexTaskDispatcher dispatcher = new KnowledgeIndexTaskDispatcher();
        ReflectionTestUtils.setField(dispatcher, "exchange", EXCHANGE);
        ReflectionTestUtils.setField(dispatcher, "routingKey", ROUTING_KEY);
        ReflectionTestUtils.setField(dispatcher, "publishLeaseSeconds", 30);
        ReflectionTestUtils.setField(dispatcher, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(dispatcher, "outboxRepository", outbox);
        ReflectionTestUtils.setField(dispatcher, "workerService", worker);
        return dispatcher;
    }

    private SimpleMessageListenerContainer listenerContainer(IKnowledgeIndexWorkerService worker) {
        KnowledgeIndexTaskListener listener = new KnowledgeIndexTaskListener();
        ReflectionTestUtils.setField(listener, "workerService", worker);
        MessageListenerAdapter adapter = new MessageListenerAdapter(listener, "consume");
        adapter.setMessageConverter(new Jackson2JsonMessageConverter());
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(rabbitConnectionFactory);
        container.setQueueNames(QUEUE);
        container.setMessageListener(adapter);
        return container;
    }

    private KnowledgeIndexWorkerService workerService(CountDownLatch completed) {
        KnowledgeIndexWorkerService worker = new KnowledgeIndexWorkerService() {
            @Override
            public void execute(String jobCode) {
                super.execute(jobCode);
                completed.countDown();
            }
        };
        ReflectionTestUtils.setField(worker, "leaseSeconds", 30);
        ReflectionTestUtils.setField(worker, "maxRetryCount", 3);
        ReflectionTestUtils.setField(worker, "retryDelaySeconds", 1);
        ReflectionTestUtils.setField(worker, "jobRepository", jobRepository());
        ReflectionTestUtils.setField(worker, "artifactPreparationService", preparationService());
        ReflectionTestUtils.setField(worker, "generationIndexBuildService", generationBuildService());
        ReflectionTestUtils.setField(worker, "generationPublicationService", publicationService());
        return worker;
    }

    private KnowledgeArtifactPreparationService preparationService() {
        DocumentRepository documents = documentRepository();
        DocumentVersionRepository versions = documentVersionRepository();
        DocumentVersionLoader loader = new DocumentVersionLoader();
        ReflectionTestUtils.setField(loader, "documentRepository", documents);
        ReflectionTestUtils.setField(loader, "documentVersionRepository", versions);
        ReflectionTestUtils.setField(loader, "documentStorageRepository", storage());
        ReflectionTestUtils.setField(loader, "maxSourceBytes", 10_000_000L);

        StructureAwareDocumentChunker chunker = new StructureAwareDocumentChunker();
        ReflectionTestUtils.setField(chunker, "targetSize", 1000);
        ReflectionTestUtils.setField(chunker, "maxSize", 1400);
        ReflectionTestUtils.setField(chunker, "overlapSize", 160);

        KnowledgeArtifactPreparationService service = new KnowledgeArtifactPreparationService();
        ReflectionTestUtils.setField(service, "knowledgeIndexJobRepository", jobRepository());
        ReflectionTestUtils.setField(service, "knowledgeIndexGenerationRepository", generationRepository());
        ReflectionTestUtils.setField(service, "knowledgeParseArtifactRepository", parseArtifactRepository());
        ReflectionTestUtils.setField(service, "documentRepository", documents);
        ReflectionTestUtils.setField(service, "documentLoader", loader);
        ReflectionTestUtils.setField(service, "documentParserSelector", new DocumentParserSelector(List.of(
                new MarkdownDocumentParser(), new TextDocumentParser())));
        ReflectionTestUtils.setField(service, "parsedDocumentChunker", chunker);
        ReflectionTestUtils.setField(service, "documentStorageRepository", storage());
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        return service;
    }

    private GenerationIndexBuildService generationBuildService() {
        GenerationIndexBuildService service = new GenerationIndexBuildService();
        ReflectionTestUtils.setField(service, "generationRepository", generationRepository());
        ReflectionTestUtils.setField(service, "embeddingConfigRepository", embeddingConfigRepository());
        ReflectionTestUtils.setField(service, "documentRepository", documentRepository());
        ReflectionTestUtils.setField(service, "storageRepository", storage());
        ReflectionTestUtils.setField(service, "embeddingClient", deterministicEmbedding());
        ReflectionTestUtils.setField(service, "vectorStore", vectorStore());
        ReflectionTestUtils.setField(service, "servingVectorSpaceRepository", servingVectorSpaceRepository());
        ReflectionTestUtils.setField(service, "provider", "deterministic-test");
        ReflectionTestUtils.setField(service, "model", "phase3-test-model");
        ReflectionTestUtils.setField(service, "dimension", 3);
        ReflectionTestUtils.setField(service, "distance", "COSINE");
        ReflectionTestUtils.setField(service, "collection", "phase3_e2e_vectors");
        ReflectionTestUtils.setField(service, "batchSize", 2);
        return service;
    }

    private GenerationPublicationService publicationService() {
        GenerationPublicationService service = new GenerationPublicationService();
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(new DataSourceTransactionManager(dataSource())));
        ReflectionTestUtils.setField(service, "documentRepository", documentRepository());
        ReflectionTestUtils.setField(service, "generationRepository", generationRepository());
        ReflectionTestUtils.setField(service, "jobRepository", jobRepository());
        ReflectionTestUtils.setField(service, "indexStateRepository", indexStateRepository());
        ReflectionTestUtils.setField(service, "servingVectorSpaceRepository", servingVectorSpaceRepository());
        return service;
    }

    private IEmbeddingClient deterministicEmbedding() {
        return inputs -> {
            java.util.ArrayList<EmbeddingVectorVO> vectors = new java.util.ArrayList<>();
            for (int index = 0; index < inputs.size(); index++) {
                float basis = index + 1;
                vectors.add(new EmbeddingVectorVO(index, List.of(basis, basis + 1, basis + 2)));
            }
            return vectors;
        };
    }

    private DocumentEntity seedEffectiveDocument(String content) throws Exception {
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        long documentId;
        try (Connection connection = connection(); PreparedStatement document = connection.prepareStatement(
                "INSERT INTO document (document_code, workspace_id, parent_id, owner_user_id, title, file_name, document_type, status, current_version_no, index_input_revision) "
                        + "VALUES (?, 1, 1, 1, 'Phase 3 文档', 'phase3.md', 1, 2, 1, 1)", Statement.RETURN_GENERATED_KEYS)) {
            document.setString(1, "doc-" + UUID.randomUUID());
            document.executeUpdate();
            try (ResultSet keys = document.getGeneratedKeys()) {
                keys.next();
                documentId = keys.getLong(1);
            }
        }
        String objectKey = "documents/%d/1.md".formatted(documentId);
        storage().upload(objectKey, payload, "text/markdown");
        try (Connection connection = connection(); PreparedStatement version = connection.prepareStatement(
                "INSERT INTO document_version (document_id, version_no, storage_type, bucket_name, storage_path, content_type, content_size, content_hash, created_by) "
                        + "VALUES (?, 1, 0, ?, ?, 'text/markdown', ?, ?, 1)")) {
            version.setLong(1, documentId);
            version.setString(2, BUCKET);
            version.setString(3, objectKey);
            version.setLong(4, payload.length);
            version.setString(5, sha256(payload));
            version.executeUpdate();
        }
        return documentRepository().findById(documentId).orElseThrow();
    }

    private DocumentRepository documentRepository() {
        DocumentRepository repository = new DocumentRepository();
        ReflectionTestUtils.setField(repository, "documentDao", template.getMapper(IDocumentDao.class));
        return repository;
    }

    private DocumentVersionRepository documentVersionRepository() {
        DocumentVersionRepository repository = new DocumentVersionRepository();
        ReflectionTestUtils.setField(repository, "documentVersionDao", template.getMapper(IDocumentVersionDao.class));
        return repository;
    }

    private KnowledgeIndexJobRepository jobRepository() {
        KnowledgeIndexJobRepository repository = new KnowledgeIndexJobRepository();
        ReflectionTestUtils.setField(repository, "dao", template.getMapper(IKnowledgeIndexJobDao.class));
        return repository;
    }

    private KnowledgeIndexOutboxEventRepository outboxRepository() {
        KnowledgeIndexOutboxEventRepository repository = new KnowledgeIndexOutboxEventRepository();
        ReflectionTestUtils.setField(repository, "dao", template.getMapper(IKnowledgeIndexOutboxEventDao.class));
        return repository;
    }

    private KnowledgeIndexGenerationRepository generationRepository() {
        KnowledgeIndexGenerationRepository repository = new KnowledgeIndexGenerationRepository();
        ReflectionTestUtils.setField(repository, "knowledgeIndexGenerationDao", template.getMapper(IKnowledgeIndexGenerationDao.class));
        return repository;
    }

    private KnowledgeParseArtifactRepository parseArtifactRepository() {
        KnowledgeParseArtifactRepository repository = new KnowledgeParseArtifactRepository();
        ReflectionTestUtils.setField(repository, "knowledgeParseArtifactDao", template.getMapper(IKnowledgeParseArtifactDao.class));
        return repository;
    }

    private EmbeddingConfigRepository embeddingConfigRepository() {
        EmbeddingConfigRepository repository = new EmbeddingConfigRepository();
        ReflectionTestUtils.setField(repository, "embeddingConfigDao", template.getMapper(IEmbeddingConfigDao.class));
        return repository;
    }

    private KnowledgeIndexStateRepository indexStateRepository() {
        KnowledgeIndexStateRepository repository = new KnowledgeIndexStateRepository();
        ReflectionTestUtils.setField(repository, "knowledgeIndexStateDao", template.getMapper(IKnowledgeIndexStateDao.class));
        return repository;
    }

    private ServingVectorSpaceRepository servingVectorSpaceRepository() {
        ServingVectorSpaceRepository repository = new ServingVectorSpaceRepository();
        ReflectionTestUtils.setField(repository, "servingVectorSpaceDao", template.getMapper(IServingVectorSpaceDao.class));
        return repository;
    }

    private DocumentStorageRepository storage() {
        MinioClient client = MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials("minioadmin", "minioadmin")
                .build();
        DocumentStorageRepository storage = new DocumentStorageRepository();
        ReflectionTestUtils.setField(storage, "minioClient", client);
        ReflectionTestUtils.setField(storage, "bucketName", BUCKET);
        return storage;
    }

    private QdrantVectorStore vectorStore() {
        QdrantVectorStore store = new QdrantVectorStore();
        ReflectionTestUtils.setField(store, "endpoint", "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
        ReflectionTestUtils.setField(store, "collection", "phase3_e2e_vectors");
        return store;
    }

    private SqlSessionTemplate mybatisTemplate() throws Exception {
        DataSource source = dataSource();
        Configuration configuration = new Configuration(new Environment("phase3-e2e", new SpringManagedTransactionFactory(), source));
        for (String mapper : List.of(
                "document.xml", "document_version.xml", "knowledge_index_job.xml", "knowledge_index_outbox_event.xml",
                "knowledge_parse_artifact.xml", "knowledge_index_generation.xml", "embedding_config.xml",
                "knowledge_index_state.xml", "knowledge_serving_vector_space.xml")) {
            new XMLMapperBuilder(org.apache.ibatis.io.Resources.getResourceAsStream("mybatis/mapper/mysql/" + mapper),
                    configuration, mapper, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory);
    }

    private DataSource dataSource() {
        try {
            return new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                    new com.mysql.cj.jdbc.Driver(), MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建 Phase 3 集成测试数据源", exception);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private long queryLong(String sql, Object value) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private String queryString(String sql, Object value) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static String sha256(byte[] source) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(source);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
