package com.hector.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.document.service.impl.DocumentUploadTaskServiceImpl;
import com.hector.domain.knowledge.adapter.port.IQueryEmbeddingClient;
import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.service.impl.KnowledgeRetrievalServiceImpl;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.knowledge.adapter.port.IEmbeddingClient;
import com.hector.domain.knowledge.adapter.port.IGenerationVectorStore;
import com.hector.domain.knowledge.model.valobj.EmbeddingVectorVO;
import com.hector.domain.knowledge.model.valobj.GenerationVectorPointVO;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexOutboxEventEntity;
import com.hector.domain.knowledge.repository.IKnowledgeIndexJobRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexOutboxEventRepository;
import com.hector.domain.knowledge.service.IKnowledgeIndexWorkerService;
import com.hector.domain.knowledge.service.impl.KnowledgeIndexJobServiceImpl;
import com.hector.infrastructure.dao.IDocumentDao;
import com.hector.infrastructure.dao.IDocumentVersionDao;
import com.hector.infrastructure.dao.IDocumentUploadTaskDao;
import com.hector.infrastructure.dao.IFolderDao;
import com.hector.infrastructure.dao.IWorkspaceDao;
import com.hector.infrastructure.dao.IEmbeddingConfigDao;
import com.hector.infrastructure.dao.IKnowledgeIndexGenerationDao;
import com.hector.infrastructure.dao.IKnowledgeIndexJobDao;
import com.hector.infrastructure.dao.IKnowledgeIndexOutboxEventDao;
import com.hector.infrastructure.dao.IKnowledgeIndexStateDao;
import com.hector.infrastructure.dao.IKnowledgeParseArtifactDao;
import com.hector.infrastructure.dao.IServingVectorSpaceDao;
import com.hector.infrastructure.rag.GenerationIndexBuildService;
import com.hector.infrastructure.rag.GenerationPublicationService;
import com.hector.infrastructure.rag.embedding.DashScopeEmbeddingModel;
import com.hector.infrastructure.rag.KnowledgeArtifactPreparationService;
import com.hector.infrastructure.rag.KnowledgeIndexWorkerService;
import com.hector.infrastructure.rag.KnowledgeGenerationCleanupService;
import com.hector.infrastructure.rag.chunk.StructureAwareDocumentChunker;
import com.hector.infrastructure.rag.loader.DocumentVersionLoader;
import com.hector.infrastructure.rag.parser.DocumentParserSelector;
import com.hector.infrastructure.rag.parser.MarkdownDocumentParser;
import com.hector.infrastructure.rag.parser.TextDocumentParser;
import com.hector.infrastructure.rag.vector.QdrantVectorStore;
import com.hector.infrastructure.repository.DocumentRepository;
import com.hector.infrastructure.repository.DocumentStorageRepository;
import com.hector.infrastructure.repository.DocumentVersionRepository;
import com.hector.infrastructure.repository.DocumentUploadTaskRepository;
import com.hector.infrastructure.repository.FolderRepository;
import com.hector.infrastructure.repository.WorkspaceRepository;
import com.hector.infrastructure.repository.EmbeddingConfigRepository;
import com.hector.infrastructure.repository.KnowledgeIndexGenerationRepository;
import com.hector.infrastructure.repository.KnowledgeIndexJobRepository;
import com.hector.infrastructure.repository.KnowledgeIndexOutboxEventRepository;
import com.hector.infrastructure.repository.KnowledgeIndexStateRepository;
import com.hector.infrastructure.repository.KnowledgeParseArtifactRepository;
import com.hector.infrastructure.repository.ServingVectorSpaceRepository;
import com.hector.trigger.mq.KnowledgeIndexTaskDispatcher;
import com.hector.trigger.mq.KnowledgeIndexTaskListener;
import com.hector.trigger.http.handler.capability.SearchWorkspaceCapabilityHandler;
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
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import org.awaitility.Awaitility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 3 真实 MySQL、MinIO、Qdrant、RabbitMQ 的知识索引完整自动链路验收。 */
class Phase3KnowledgeIndexEndToEndIntegrationTest {
    private static final String BUCKET = "phase5-e2e";
    private static final String COLLECTION = "hex_workspace_rag_v1";
    private static final String REAL_DASHSCOPE_COLLECTION = "hex_workspace_rag_v1_real";
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
    private static HttpServer embeddingServer;
    private static final AtomicBoolean failNextEmbeddingRequest = new AtomicBoolean();
    private static final AtomicBoolean pauseEmbeddingRequest = new AtomicBoolean();
    private static CountDownLatch embeddingRequestStarted;
    private static CountDownLatch allowEmbeddingRequest;

    private SqlSessionTemplate template;
    private RabbitTemplate rabbitTemplate;
    private CachingConnectionFactory rabbitConnectionFactory;

    @BeforeAll
    static void startContainers() throws Exception {
        MYSQL.start();
        MINIO.start();
        QDRANT.start();
        RABBIT.start();
        embeddingServer = HttpServer.create(new InetSocketAddress(0), 0);
        embeddingServer.createContext("/embeddings", exchange -> {
            if (failNextEmbeddingRequest.compareAndSet(true, false)) {
                exchange.sendResponseHeaders(503, 0);
                exchange.close();
                return;
            }
            if (pauseEmbeddingRequest.get()) {
                embeddingRequestStarted.countDown();
                try {
                    allowEmbeddingRequest.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                    return;
                }
            }
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int inputStart = requestBody.indexOf("\"input\":[") + 9;
            int inputEnd = requestBody.indexOf(']', inputStart);
            int inputCount = inputStart > 8 && inputEnd > inputStart
                    ? requestBody.substring(inputStart, inputEnd).contains(",") ? 2 : 1 : 1;
            StringBuilder body = new StringBuilder("{\"data\":[");
            for (int index = 0; index < inputCount; index++) {
                if (index > 0) {
                    body.append(',');
                }
                body.append("{\"index\":").append(index)
                        .append(",\"embedding\":[").append(index + 1).append(',')
                        .append(index + 2).append(',').append(index + 3).append("]}");
            }
            byte[] response = body.append("]}").toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        embeddingServer.start();
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
        if (embeddingServer != null) {
            embeddingServer.stop(0);
        }
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
                    "knowledge_embedding_config", "document_upload_task", "document_version", "document")) {
                statement.execute("DELETE FROM " + table);
            }
            statement.execute("DELETE FROM folder");
            statement.execute("DELETE FROM workspace");
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
    void shouldIndexDocumentVersionAndPublishActiveGenerationEndToEnd() throws Exception {
        DocumentEntity document = seedEffectiveDocument("# Phase 5\n\n第一段正文。\n\n## 小节\n\n第二段正文。");
        String taskCode = seedPendingUploadTask(document,
                "# Phase 6\n\n授权检索内容 ORANGE-7419。");
        documentUploadTaskService().execute(taskCode);
        IKnowledgeIndexJobRepository jobs = jobRepository();
        IKnowledgeIndexOutboxEventRepository outbox = outboxRepository();

        CountDownLatch completed = new CountDownLatch(1);
        KnowledgeIndexWorkerService worker = workerService(completed);
        SimpleMessageListenerContainer listenerContainer = listenerContainer(worker);
        listenerContainer.start();
        String jobCode = null;
        try {
            dispatcher(outbox, worker).dispatch();
            jobCode = queryString("SELECT job_code FROM knowledge_index_job WHERE document_id = ?", document.getId());
            assertTrue(completed.await(30, TimeUnit.SECONDS), "知识索引 Worker 未完成真实消息链路");
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
        assertEquals(chunkCount.longValue(), vectors.countByGeneration(COLLECTION, generationId));
        assertEquals(2L, queryLong("SELECT status FROM document_upload_task WHERE task_code = ?", taskCode));
        assertEquals(2L, queryLong("SELECT current_version_no FROM document WHERE id = ?", document.getId()));
    }

    @Test
    void shouldIndexAndRetrieveUsingRealDashScopeEndToEnd() throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey);
        assertFalse(apiKey.isBlank());

        DocumentEntity document = seedEffectiveDocument(
                "# Hex Workspace RAG V1\n\nHex Workspace 使用 DocumentVersion 管理不可变文档内容。\n");
        registerIndexJobAndOutbox(document, jobRepository(), outboxRepository());
        String jobCode = queryString("SELECT job_code FROM knowledge_index_job WHERE document_id = ?", document.getId());
        CountDownLatch completed = new CountDownLatch(1);
        KnowledgeIndexWorkerService worker = workerService(completed, realGenerationBuildService());
        SimpleMessageListenerContainer listenerContainer = listenerContainer(worker);
        listenerContainer.start();
        try {
            dispatcher(outboxRepository(), worker).dispatch();
            assertTrue(completed.await(120, TimeUnit.SECONDS), "真实 DashScope 索引链路未完成");
        } finally {
            listenerContainer.stop();
        }

        String generationId = queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?",
                document.getId());
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_generation WHERE document_id = ? AND status = 1",
                document.getId()));
        long chunkCount = queryLong("SELECT chunk_count FROM knowledge_index_generation WHERE generation_id = ?", generationId);
        assertTrue(chunkCount > 0);
        assertEquals(chunkCount, vectorStore().countByGeneration(REAL_DASHSCOPE_COLLECTION, generationId));

        var result = realRetrievalService().retrieve(new RetrievalContextVO(1L, 1L),
                "Hex Workspace 如何管理文档版本？", null);
        assertFalse(result.hits().isEmpty());
        assertTrue(result.hits().stream().anyMatch(hit -> document.getId().equals(hit.documentId())
                && generationId.equals(hit.generationId()) && hit.content().contains("DocumentVersion")));
    }

    @Test
    void shouldRetrieveChunksFromPublishedGenerationEndToEnd() throws Exception {
        DocumentEntity document = seedEffectiveDocument("# Phase 5\n\n第一段正文。\n\n## 小节\n\n第二段正文。");
        String taskCode = seedPendingUploadTask(document);
        documentUploadTaskService().execute(taskCode);

        IKnowledgeIndexOutboxEventRepository outbox = outboxRepository();
        CountDownLatch completed = new CountDownLatch(1);
        KnowledgeIndexWorkerService worker = workerService(completed);
        SimpleMessageListenerContainer listenerContainer = listenerContainer(worker);
        listenerContainer.start();
        try {
            dispatcher(outbox, worker).dispatch();
            String jobCode = queryString("SELECT job_code FROM knowledge_index_job WHERE document_id = ?", document.getId());
            assertTrue(completed.await(30, TimeUnit.SECONDS), "索引任务未通过真实 RabbitMQ 链路完成");
            assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
        } finally {
            listenerContainer.stop();
        }

        String generationId = queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", document.getId());
        assertEquals("phase5-e2e-provider|phase5-e2e-model|3|COSINE|" + COLLECTION,
                queryString("SELECT CONCAT(provider, '|', model, '|', dimension, '|', distance, '|', collection_name) "
                        + "FROM knowledge_embedding_config WHERE id = (SELECT embedding_config_id FROM knowledge_serving_vector_space LIMIT 1)"));
        KnowledgeRetrievalServiceImpl retrieval = retrievalService();
        var result = retrieval.retrieve(new RetrievalContextVO(1L, 1L), "正式版本内容", null);

        assertTrue(!result.hits().isEmpty(), "已发布 Generation 应可被真实 Retrieval 检索");
        var hit = result.hits().stream()
                .filter(value -> document.getId().equals(value.documentId()))
                .findFirst()
                .orElseThrow();
        assertEquals(document.getId(), hit.documentId());
        assertEquals(2L, hit.versionNo());
        assertEquals(queryLong("SELECT id FROM document_version WHERE document_id = ? AND version_no = 2",
                document.getId()), hit.versionId());
        assertEquals(generationId, hit.generationId());
        assertTrue(hit.content().contains("正式版本内容"));
        assertEquals("Phase 3 文档", hit.title());
        assertEquals(1L, queryLong("SELECT workspace_id FROM document WHERE id = ?", hit.documentId()));
        assertEquals(generationId, queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", hit.documentId()));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationId));
    }

    @Test
    void shouldSearchWorkspaceCapabilityUseAuthoritativeRetrievalEndToEnd() throws Exception {
        DocumentEntity document = seedEffectiveDocument("# Phase 6\n\n授权检索内容 ORANGE-7419。");
        String taskCode = seedPendingUploadTask(document,
                "# Phase 6\n\n授权检索内容 ORANGE-7419。");
        documentUploadTaskService().execute(taskCode);
        buildAndAwaitIndex(document);

        SearchWorkspaceCapabilityHandler handler = new SearchWorkspaceCapabilityHandler(retrievalService());
        CapabilityResultVO authorized = handler.invoke(
                new CapabilityContextVO("run-phase6", "conversation-phase6", 1L, 1L,
                        "[\"search_workspace\"]"),
                "{\"query\":\"ORANGE-7419\",\"topK\":5}");
        assertEquals(CapabilityResultVO.Status.SUCCEEDED, authorized.status(),
                authorized.errorCode() + ":" + authorized.errorMessage());
        assertTrue(authorized.dataJson().contains("ORANGE-7419"), authorized.dataJson());
        assertTrue(authorized.dataJson().contains("documentId"));
        assertTrue(authorized.dataJson().contains("\"versionId\":" + queryLong(
                "SELECT id FROM document_version WHERE document_id = ? AND version_no = 2", document.getId())));
        assertFalse(authorized.dataJson().contains("generationId"));
        assertFalse(authorized.dataJson().contains("chunkNo"));

        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE document SET status = 3 WHERE id = ?")) {
            statement.setLong(1, document.getId());
            assertEquals(1, statement.executeUpdate());
        }
        CapabilityResultVO invalidated = handler.invoke(
                new CapabilityContextVO("run-phase6", "conversation-phase6", 1L, 1L,
                        "[\"search_workspace\"]"),
                "{\"query\":\"ORANGE-7419\",\"topK\":5}");
        assertEquals(CapabilityResultVO.Status.SUCCEEDED, invalidated.status());
        assertEquals("{\"items\":[]}", invalidated.dataJson());
    }

    @Test
    void shouldSwitchRetrievalToNewVersionAndCleanupOldGenerationEndToEnd() throws Exception {
        DocumentEntity document = seedEffectiveDocument("# V1\n\n第一版本内容。");
        registerIndexJobAndOutbox(document, jobRepository(), outboxRepository());
        buildAndAwaitIndex(document);
        String generationA = queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", document.getId());

        var firstResult = retrievalService().retrieve(new RetrievalContextVO(1L, 1L), "第一版本内容", null);
        assertTrue(firstResult.hits().stream().anyMatch(hit -> hit.content().contains("正式版本内容") || hit.content().contains("第一版本内容")));
        assertEquals(1L, firstResult.hits().get(0).versionNo());
        assertEquals(generationA, firstResult.hits().get(0).generationId());

        String taskCode = seedPendingUploadTask(document);
        documentUploadTaskService().execute(taskCode);
        var transitionResult = retrievalService().retrieve(new RetrievalContextVO(1L, 1L), "正式版本内容", null);
        assertTrue(transitionResult.hits().isEmpty(), "V2 生效但新 Generation 未发布时不得返回旧版本 Chunk");

        buildAndAwaitIndex(document);
        String generationB = queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", document.getId());
        assertTrue(!generationA.equals(generationB));
        var secondResult = retrievalService().retrieve(new RetrievalContextVO(1L, 1L), "正式版本内容", null);
        assertTrue(!secondResult.hits().isEmpty());
        assertTrue(secondResult.hits().stream().allMatch(hit -> hit.versionNo() == 2L
                && generationB.equals(hit.generationId())));
        assertTrue(secondResult.hits().stream().anyMatch(hit -> hit.content().contains("正式版本内容")));
        assertEquals(2L, queryLong("SELECT current_version_no FROM document WHERE id = ?", document.getId()));

        String oldChunkKey = queryString("SELECT chunk_artifact_key FROM knowledge_index_generation WHERE generation_id = ?", generationA);
        String newChunkKey = queryString("SELECT chunk_artifact_key FROM knowledge_index_generation WHERE generation_id = ?", generationB);
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE knowledge_index_generation SET updated_at = NOW() - INTERVAL 10 DAY WHERE generation_id = ?")) {
            statement.setString(1, generationA);
            statement.executeUpdate();
        }
        KnowledgeGenerationCleanupService cleanup = cleanupService();
        assertEquals(1, cleanup.cleanExpired(java.time.LocalDateTime.now().minusDays(7), 20, 10));
        assertEquals(4L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationA));
        assertEquals(0L, vectorStore().countByGeneration(COLLECTION, generationA));
        assertTrue(objectExists(oldChunkKey) == false);
        assertTrue(vectorStore().countByGeneration(COLLECTION, generationB) > 0);
        assertTrue(objectExists(newChunkKey));
        var finalResult = retrievalService().retrieve(new RetrievalContextVO(1L, 1L), "正式版本内容", null);
        assertTrue(finalResult.hits().stream().allMatch(hit -> generationB.equals(hit.generationId()) && hit.versionNo() == 2L));
    }

    @Test
    void shouldRetryFailedIndexJobAndPublishGenerationSuccessfully() throws Exception {
        DocumentEntity document = seedEffectiveDocument("# Retry\n\n可恢复失败前的内容。");
        registerIndexJobAndOutbox(document, jobRepository(), outboxRepository());
        String jobCode = queryString("SELECT job_code FROM knowledge_index_job WHERE document_id = ?", document.getId());
        failNextEmbeddingRequest.set(true);

        AtomicInteger executionCount = new AtomicInteger();
        CountDownLatch executionsCompleted = new CountDownLatch(2);
        KnowledgeIndexWorkerService worker = workerService(executionsCompleted, executionCount);
        ReflectionTestUtils.setField(worker, "retryDelaySeconds", 0);
        SimpleMessageListenerContainer listenerContainer = listenerContainer(worker);
        listenerContainer.start();
        try {
            KnowledgeIndexTaskDispatcher dispatcher = dispatcher(outboxRepository(), worker);
            dispatcher.dispatch();
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                    queryLong("SELECT retry_count FROM knowledge_index_job WHERE job_code = ?", jobCode) >= 1L);
            assertEquals(0L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
            assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_index_generation WHERE document_id = ? AND status = 1",
                    document.getId()));

            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                    queryLong("SELECT COUNT(*) FROM knowledge_index_job WHERE job_code = ? "
                            + "AND status = 0 AND next_retry_at <= CURRENT_TIMESTAMP", jobCode) == 1L);
            dispatcher.dispatch();
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                    queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode) == 2L);
            assertTrue(executionsCompleted.await(10, TimeUnit.SECONDS), "未观察到第二次 Worker 执行");
        } finally {
            listenerContainer.stop();
        }

        assertEquals(2, executionCount.get());
        String generationId = queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?", document.getId());
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_generation WHERE document_id = ? AND status = 1",
                document.getId()));
        assertTrue(vectorStore().countByGeneration(COLLECTION, generationId) > 0);
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
    }

    @Test
    void shouldTakeOverExpiredWorkerLeaseAndRejectStaleWorker() throws Exception {
        DocumentEntity document = seedEffectiveDocument("# Lease\n\nLease 接管测试内容。");
        registerIndexJobAndOutbox(document, jobRepository(), outboxRepository());
        String jobCode = queryString("SELECT job_code FROM knowledge_index_job WHERE document_id = ?", document.getId());
        String workerTokenA = "worker-a-" + UUID.randomUUID();
        IKnowledgeIndexJobRepository jobs = jobRepository();

        assertTrue(jobs.claimForRunning(jobCode, workerTokenA,
                LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30)));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
        assertEquals(workerTokenA, queryString("SELECT worker_token FROM knowledge_index_job WHERE job_code = ?", jobCode));

        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE knowledge_index_job SET running_lease_until = UTC_TIMESTAMP() - INTERVAL 1 SECOND "
                        + "WHERE job_code = ? AND status = 1 AND worker_token = ?")) {
            statement.setString(1, jobCode);
            statement.setString(2, workerTokenA);
            assertEquals(1, statement.executeUpdate());
        }

        KnowledgeIndexWorkerService recoveryWorker = workerService(new CountDownLatch(1));
        recoveryWorker.recoverExpiredRunningJobs();
        assertEquals(0L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_index_job WHERE job_code = ? AND worker_token IS NOT NULL",
                jobCode));

        assertFalse(jobs.markSucceededIfOwned(jobCode, workerTokenA, LocalDateTime.now(ZoneOffset.UTC)));
        assertFalse(jobs.finishFailureIfOwned(jobCode, workerTokenA, 3, 0, "stale worker"));
        assertEquals(0L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));

        embeddingRequestStarted = new CountDownLatch(1);
        allowEmbeddingRequest = new CountDownLatch(1);
        pauseEmbeddingRequest.set(true);
        CountDownLatch completed = new CountDownLatch(1);
        KnowledgeIndexWorkerService workerB = workerService(completed);
        SimpleMessageListenerContainer listenerContainer = listenerContainer(workerB);
        listenerContainer.start();
        try {
            dispatcher(outboxRepository(), workerB).dispatch();
            assertTrue(embeddingRequestStarted.await(30, TimeUnit.SECONDS), "Worker B 未进入可控的 Embedding 阶段");
            String workerTokenB = queryString("SELECT worker_token FROM knowledge_index_job WHERE job_code = ?", jobCode);
            String leaseB = queryString("SELECT running_lease_until FROM knowledge_index_job WHERE job_code = ?", jobCode);
            assertNotNull(workerTokenB);
            assertNotNull(leaseB);
            assertFalse(workerTokenA.equals(workerTokenB));
            assertEquals(1L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
            assertFalse(jobs.markSucceededIfOwned(jobCode, workerTokenA, LocalDateTime.now(ZoneOffset.UTC)));
            assertFalse(jobs.finishFailureIfOwned(jobCode, workerTokenA, 3, 0, "stale worker after takeover"));
            assertEquals(1L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
            assertEquals(workerTokenB, queryString("SELECT worker_token FROM knowledge_index_job WHERE job_code = ?", jobCode));
            assertEquals(leaseB, queryString("SELECT running_lease_until FROM knowledge_index_job WHERE job_code = ?", jobCode));
            assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_index_state WHERE document_id = ? AND active_generation_id IS NOT NULL",
                    document.getId()));
            allowEmbeddingRequest.countDown();
            assertTrue(completed.await(30, TimeUnit.SECONDS), "Worker B 未完成接管后的索引构建");
        } finally {
            allowEmbeddingRequest.countDown();
            pauseEmbeddingRequest.set(false);
            listenerContainer.stop();
        }

        String generationId = queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?",
                document.getId());
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_generation WHERE document_id = ? AND status = 1",
                document.getId()));
        assertTrue(vectorStore().countByGeneration(COLLECTION, generationId) > 0);
    }

    @Test
    void shouldRecoverFailedOutboxPublishAndCompleteIndexing() throws Exception {
        DocumentEntity document = seedEffectiveDocument("# Outbox\n\nOutbox 投递恢复测试内容。");
        registerIndexJobAndOutbox(document, jobRepository(), outboxRepository());
        String jobCode = queryString("SELECT job_code FROM knowledge_index_job WHERE document_id = ?", document.getId());
        String eventCode = queryString("SELECT event_code FROM knowledge_index_outbox_event WHERE job_code = ?", jobCode);

        assertEquals(0L, queryLong("SELECT status FROM knowledge_index_outbox_event WHERE event_code = ?", eventCode));
        assertEquals(0L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));

        CachingConnectionFactory failingConnectionFactory = new CachingConnectionFactory("127.0.0.1", 1);
        RabbitTemplate failingRabbitTemplate = new RabbitTemplate(failingConnectionFactory);
        KnowledgeIndexWorkerService worker = workerService(new CountDownLatch(1));
        KnowledgeIndexTaskDispatcher failingDispatcher = dispatcher(outboxRepository(), worker, failingRabbitTemplate);
        failingDispatcher.dispatch();

        assertEquals(0L, queryLong("SELECT status FROM knowledge_index_outbox_event WHERE event_code = ?", eventCode));
        assertNotNull(queryString("SELECT failure_reason FROM knowledge_index_outbox_event WHERE event_code = ?", eventCode));
        assertEquals(0L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_index_state WHERE document_id = ? "
                + "AND active_generation_id IS NOT NULL", document.getId()));
        failingConnectionFactory.destroy();

        CountDownLatch completed = new CountDownLatch(1);
        KnowledgeIndexWorkerService recoveryWorker = workerService(completed);
        SimpleMessageListenerContainer listenerContainer = listenerContainer(recoveryWorker);
        listenerContainer.start();
        try {
            dispatcher(outboxRepository(), recoveryWorker).dispatch();
            assertTrue(completed.await(30, TimeUnit.SECONDS), "恢复后的 Outbox 消息未完成索引任务");
        } finally {
            listenerContainer.stop();
        }

        String generationId = queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?",
                document.getId());
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_outbox_event WHERE event_code = ?", eventCode));
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_generation WHERE document_id = ? AND status = 1",
                document.getId()));
        assertTrue(vectorStore().countByGeneration(COLLECTION, generationId) > 0);
    }

    @Test
    void shouldRecoverAfterBrokerConfirmButOutboxMarkSentFails() throws Exception {
        DocumentEntity document = seedEffectiveDocument("# Outbox Confirm\n\nBroker Confirm 后标记失败恢复测试。\n");
        String taskCode = seedPendingUploadTask(document);
        documentUploadTaskService().execute(taskCode);

        IKnowledgeIndexOutboxEventRepository realOutbox = outboxRepository();
        String jobCode = queryString("SELECT job_code FROM knowledge_index_job WHERE document_id = ? ORDER BY id DESC LIMIT 1",
                document.getId());
        String eventCode = queryString("SELECT event_code FROM knowledge_index_outbox_event WHERE job_code = ?", jobCode);
        AtomicBoolean failMarkSentOnce = new AtomicBoolean(true);
        AtomicInteger executionCount = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        IKnowledgeIndexOutboxEventRepository failingOutbox = markSentFailureOnce(realOutbox, failMarkSentOnce);
        KnowledgeIndexWorkerService worker = workerService(completed, executionCount);
        SimpleMessageListenerContainer listenerContainer = listenerContainer(worker);
        listenerContainer.start();
        try {
            dispatcher(failingOutbox, worker).dispatch();

            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> executionCount.get() >= 1);
            assertFalse(failMarkSentOnce.get());
            assertEquals(0L, queryLong("SELECT status FROM knowledge_index_outbox_event WHERE event_code = ?", eventCode));

            dispatcher(realOutbox, worker).dispatch();
            assertTrue(completed.await(30, TimeUnit.SECONDS), "重复投递后的索引任务未完成");
            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> executionCount.get() >= 2);
        } finally {
            listenerContainer.stop();
        }

        String generationId = queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?",
                document.getId());
        assertNotNull(generationId);
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_outbox_event WHERE event_code = ?", eventCode));
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationId));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_state WHERE document_id = ? "
                + "AND active_generation_id IS NOT NULL", document.getId()));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_generation WHERE document_id = ? AND status = 1",
                document.getId()));
        assertTrue(vectorStore().countByGeneration(COLLECTION, generationId) > 0);
    }

    @Test
    void shouldRetryWithNewGenerationAfterPartialQdrantBatchFailure() throws Exception {
        StringBuilder content = new StringBuilder("# Partial\n\n");
        for (int index = 1; index <= 8; index++) {
            content.append("## Section ").append(index).append("\n\n");
            content.append("这是第 ").append(index).append(" 个独立章节，用于验证 Qdrant 批量写入失败后的 Generation 隔离与重试。\n\n");
        }
        DocumentEntity document = seedEffectiveDocument(content.toString());
        registerIndexJobAndOutbox(document, jobRepository(), outboxRepository());
        String jobCode = queryString("SELECT job_code FROM knowledge_index_job WHERE document_id = ?", document.getId());

        AtomicInteger upsertCalls = new AtomicInteger();
        AtomicInteger firstBatchPointCount = new AtomicInteger();
        IGenerationVectorStore failingVectorStore = failingSecondBatchVectorStore(upsertCalls, firstBatchPointCount);
        CountDownLatch firstAttemptCompleted = new CountDownLatch(1);
        KnowledgeIndexWorkerService firstWorker = workerService(firstAttemptCompleted,
                generationBuildService(failingVectorStore));
        SimpleMessageListenerContainer firstListener = listenerContainer(firstWorker);
        firstListener.start();
        String generationA;
        try {
            dispatcher(outboxRepository(), firstWorker).dispatch();
            assertTrue(firstAttemptCompleted.await(30, TimeUnit.SECONDS), "第一次 Worker 未结束");
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                    queryLong("SELECT retry_count FROM knowledge_index_job WHERE job_code = ?", jobCode) >= 1L);
            generationA = queryString("SELECT generation_id FROM knowledge_index_generation WHERE document_id = ? ORDER BY id LIMIT 1",
                    document.getId());
        } finally {
            firstListener.stop();
        }

        long expectedPointCount = queryLong("SELECT chunk_count FROM knowledge_index_generation WHERE generation_id = ?", generationA);
        assertTrue(firstBatchPointCount.get() > 0);
        assertTrue(firstBatchPointCount.get() < expectedPointCount);
        assertTrue(upsertCalls.get() >= 2);
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationA));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_index_state WHERE document_id = ? "
                + "AND active_generation_id = ?", document.getId(), generationA));
        assertEquals(firstBatchPointCount.get(), vectorStore().countByGeneration(COLLECTION, generationA));

        CountDownLatch retryCompleted = new CountDownLatch(1);
        KnowledgeIndexWorkerService retryWorker = workerService(retryCompleted);
        SimpleMessageListenerContainer retryListener = listenerContainer(retryWorker);
        retryListener.start();
        try {
            dispatcher(outboxRepository(), retryWorker).dispatch();
            assertTrue(retryCompleted.await(30, TimeUnit.SECONDS), "重试 Worker 未完成");
        } finally {
            retryListener.stop();
        }

        String generationB = queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?",
                document.getId());
        long actualPointCountB = vectorStore().countByGeneration(COLLECTION, generationB);
        assertFalse(generationA.equals(generationB));
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationB));
        assertEquals(expectedPointCount, actualPointCountB);
        vectorStore().verifyGenerationPoints(COLLECTION, generationB, generationPointIds(generationB, expectedPointCount));
        Set<String> pointIdsA = new HashSet<>(generationPointIds(generationA, expectedPointCount));
        Set<String> pointIdsB = new HashSet<>(generationPointIds(generationB, expectedPointCount));
        pointIdsA.retainAll(pointIdsB);
        assertTrue(pointIdsA.isEmpty());
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_generation WHERE document_id = ? AND status = 1",
                document.getId()));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_index_generation WHERE generation_id = ? AND status = 1",
                generationA));
        assertTrue(vectorStore().countByGeneration(COLLECTION, generationA) > 0);
    }

    @Test
    void shouldCleanupFailedGenerationResidualsWithoutAffectingActiveGeneration() throws Exception {
        StringBuilder content = new StringBuilder("# Failed Cleanup\n\n");
        for (int index = 1; index <= 8; index++) {
            content.append("## Section ").append(index).append("\n\n");
            content.append("这是第 ").append(index).append(" 个章节，用于验证 FAILED Generation 残留清理。\n\n");
        }
        DocumentEntity document = seedEffectiveDocument(content.toString());
        registerIndexJobAndOutbox(document, jobRepository(), outboxRepository());
        String jobCode = queryString("SELECT job_code FROM knowledge_index_job WHERE document_id = ?", document.getId());

        AtomicInteger upsertCalls = new AtomicInteger();
        AtomicInteger firstBatchPointCount = new AtomicInteger();
        CountDownLatch firstAttemptCompleted = new CountDownLatch(1);
        KnowledgeIndexWorkerService firstWorker = workerService(firstAttemptCompleted,
                generationBuildService(failingSecondBatchVectorStore(upsertCalls, firstBatchPointCount)));
        SimpleMessageListenerContainer firstListener = listenerContainer(firstWorker);
        firstListener.start();
        String generationA;
        try {
            dispatcher(outboxRepository(), firstWorker).dispatch();
            assertTrue(firstAttemptCompleted.await(30, TimeUnit.SECONDS), "FAILED Generation 构建未结束");
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                    queryLong("SELECT retry_count FROM knowledge_index_job WHERE job_code = ?", jobCode) >= 1L);
            generationA = queryString("SELECT generation_id FROM knowledge_index_generation WHERE document_id = ? ORDER BY id LIMIT 1",
                    document.getId());
        } finally {
            firstListener.stop();
        }

        long expectedPointCount = queryLong("SELECT chunk_count FROM knowledge_index_generation WHERE generation_id = ?", generationA);
        String failedArtifactKey = queryString("SELECT chunk_artifact_key FROM knowledge_index_generation WHERE generation_id = ?",
                generationA);
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationA));
        assertTrue(firstBatchPointCount.get() > 0);
        assertTrue(firstBatchPointCount.get() < expectedPointCount);
        assertEquals(firstBatchPointCount.get(), vectorStore().countByGeneration(COLLECTION, generationA));
        assertTrue(objectExists(failedArtifactKey));

        CountDownLatch retryCompleted = new CountDownLatch(1);
        KnowledgeIndexWorkerService retryWorker = workerService(retryCompleted);
        SimpleMessageListenerContainer retryListener = listenerContainer(retryWorker);
        retryListener.start();
        try {
            dispatcher(outboxRepository(), retryWorker).dispatch();
            assertTrue(retryCompleted.await(30, TimeUnit.SECONDS), "重试 Worker 未完成");
        } finally {
            retryListener.stop();
        }

        String generationB = queryString("SELECT active_generation_id FROM knowledge_index_state WHERE document_id = ?",
                document.getId());
        String activeArtifactKey = queryString("SELECT chunk_artifact_key FROM knowledge_index_generation WHERE generation_id = ?",
                generationB);
        assertFalse(generationA.equals(generationB));
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE job_code = ?", jobCode));
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationA));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationB));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_state WHERE document_id = ? "
                + "AND active_generation_id = ?", document.getId(), generationB));
        assertTrue(!failedArtifactKey.equals(activeArtifactKey));
        assertTrue(objectExists(failedArtifactKey));
        assertTrue(objectExists(activeArtifactKey));
        assertEquals(expectedPointCount, vectorStore().countByGeneration(COLLECTION, generationB));
        assertEquals(firstBatchPointCount.get(), vectorStore().countByGeneration(COLLECTION, generationA));
        vectorStore().verifyGenerationPoints(COLLECTION, generationB, generationPointIds(generationB, expectedPointCount));

        Set<String> failedPointIds = new HashSet<>(generationPointIds(generationA, expectedPointCount));
        Set<String> activePointIds = new HashSet<>(generationPointIds(generationB, expectedPointCount));
        failedPointIds.retainAll(activePointIds);
        assertTrue(failedPointIds.isEmpty());

        KnowledgeGenerationCleanupService cleanup = cleanupService();
        LocalDateTime retentionCutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
        assertEquals(0, cleanup.cleanExpired(retentionCutoff, 20, 10));
        assertEquals(2L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationA));
        assertEquals(firstBatchPointCount.get(), vectorStore().countByGeneration(COLLECTION, generationA));
        assertTrue(objectExists(failedArtifactKey));

        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE knowledge_index_generation SET updated_at = UTC_TIMESTAMP() - INTERVAL 10 DAY "
                        + "WHERE generation_id = ? AND status = 2")) {
            statement.setString(1, generationA);
            assertEquals(1, statement.executeUpdate());
        }

        assertEquals(1, cleanup.cleanExpired(retentionCutoff, 20, 10));
        assertEquals(4L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationA));
        assertEquals(0L, vectorStore().countByGeneration(COLLECTION, generationA));
        assertFalse(objectExists(failedArtifactKey));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_generation WHERE generation_id = ?", generationB));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_index_state WHERE document_id = ? "
                + "AND active_generation_id = ?", document.getId(), generationB));
        assertEquals(expectedPointCount, vectorStore().countByGeneration(COLLECTION, generationB));
        assertTrue(objectExists(activeArtifactKey));

        var retrievalResult = retrievalService().retrieve(new RetrievalContextVO(1L, 1L), "章节 残留清理", null);
        assertTrue(retrievalResult.hits().stream().allMatch(hit -> generationB.equals(hit.generationId())));
    }

    private List<String> generationPointIds(String generationId, long chunkCount) {
        List<String> pointIds = new java.util.ArrayList<>();
        for (int chunkNo = 0; chunkNo < chunkCount; chunkNo++) {
            pointIds.add(UUID.nameUUIDFromBytes((generationId + ":" + chunkNo).getBytes(StandardCharsets.UTF_8)).toString());
        }
        return pointIds;
    }

    private IGenerationVectorStore failingSecondBatchVectorStore(AtomicInteger upsertCalls,
                                                                  AtomicInteger firstBatchPointCount) {
        QdrantVectorStore delegate = vectorStore();
        return new IGenerationVectorStore() {
            @Override
            public void ensureCollection(Integer dimension, String distance, String collectionName) {
                delegate.ensureCollection(dimension, distance, collectionName);
            }

            @Override
            public void upsert(String collectionName, List<GenerationVectorPointVO> points) {
                if (upsertCalls.incrementAndGet() == 1) {
                    firstBatchPointCount.set(points.size());
                    delegate.upsert(collectionName, points);
                    return;
                }
                throw new IllegalStateException("测试注入：Qdrant 第二批写入失败");
            }

            @Override
            public long countByGeneration(String collectionName, String generationId) {
                return delegate.countByGeneration(collectionName, generationId);
            }

            @Override
            public void verifyGenerationPoints(String collectionName, String generationId, List<String> expectedPointIds) {
                delegate.verifyGenerationPoints(collectionName, generationId, expectedPointIds);
            }

            @Override
            public void deleteByGeneration(String collectionName, String generationId) {
                delegate.deleteByGeneration(collectionName, generationId);
            }
        };
    }

    private void buildAndAwaitIndex(DocumentEntity document) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        KnowledgeIndexWorkerService worker = workerService(completed);
        SimpleMessageListenerContainer listenerContainer = listenerContainer(worker);
        listenerContainer.start();
        try {
            dispatcher(outboxRepository(), worker).dispatch();
            assertTrue(completed.await(30, TimeUnit.SECONDS), "索引任务未完成");
            assertEquals(2L, queryLong("SELECT status FROM knowledge_index_job WHERE document_id = ? ORDER BY id DESC LIMIT 1",
                    document.getId()));
        } finally {
            listenerContainer.stop();
        }
    }

    private void registerIndexJobAndOutbox(DocumentEntity document, IKnowledgeIndexJobRepository jobs,
                                           IKnowledgeIndexOutboxEventRepository outbox) {
        KnowledgeIndexJobServiceImpl service = new KnowledgeIndexJobServiceImpl();
        ReflectionTestUtils.setField(service, "jobRepository", jobs);
        ReflectionTestUtils.setField(service, "outboxEventRepository", outbox);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
        transaction.executeWithoutResult(status -> service.registerAutomatic(document));
    }

    private IKnowledgeIndexOutboxEventRepository markSentFailureOnce(
            IKnowledgeIndexOutboxEventRepository delegate, AtomicBoolean failOnce) {
        return new IKnowledgeIndexOutboxEventRepository() {
            @Override
            public KnowledgeIndexOutboxEventEntity save(KnowledgeIndexOutboxEventEntity event) {
                return delegate.save(event);
            }

            @Override
            public List<KnowledgeIndexOutboxEventEntity> listPending(int limit) {
                return delegate.listPending(limit);
            }

            @Override
            public boolean claimForPublishing(String eventCode, String publishingToken, LocalDateTime leaseUntil) {
                return delegate.claimForPublishing(eventCode, publishingToken, leaseUntil);
            }

            @Override
            public boolean markSentIfOwned(String eventCode, String publishingToken) {
                if (failOnce.compareAndSet(true, false)) {
                    throw new IllegalStateException("测试注入：Broker Confirm 后 markSentIfOwned 失败");
                }
                return delegate.markSentIfOwned(eventCode, publishingToken);
            }

            @Override
            public boolean releaseIfOwned(String eventCode, String publishingToken, String failureReason) {
                return delegate.releaseIfOwned(eventCode, publishingToken, failureReason);
            }

            @Override
            public List<KnowledgeIndexOutboxEventEntity> listExpiredPublishing(LocalDateTime now, int limit) {
                return delegate.listExpiredPublishing(now, limit);
            }

            @Override
            public boolean recoverExpiredPublishing(String eventCode, LocalDateTime now, String failureReason) {
                return delegate.recoverExpiredPublishing(eventCode, now, failureReason);
            }

            @Override
            public boolean resetSentForRetry(String jobCode) {
                return delegate.resetSentForRetry(jobCode);
            }
        };
    }

    private KnowledgeIndexTaskDispatcher dispatcher(IKnowledgeIndexOutboxEventRepository outbox,
                                                     IKnowledgeIndexWorkerService worker) {
        return dispatcher(outbox, worker, rabbitTemplate);
    }

    private KnowledgeIndexTaskDispatcher dispatcher(IKnowledgeIndexOutboxEventRepository outbox,
                                                     IKnowledgeIndexWorkerService worker,
                                                     RabbitTemplate publisher) {
        KnowledgeIndexTaskDispatcher dispatcher = new KnowledgeIndexTaskDispatcher();
        ReflectionTestUtils.setField(dispatcher, "exchange", EXCHANGE);
        ReflectionTestUtils.setField(dispatcher, "routingKey", ROUTING_KEY);
        ReflectionTestUtils.setField(dispatcher, "publishLeaseSeconds", 30);
        ReflectionTestUtils.setField(dispatcher, "rabbitTemplate", publisher);
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
        return workerService(completed, null, generationBuildService());
    }

    private KnowledgeIndexWorkerService workerService(CountDownLatch completed, AtomicInteger executionCount) {
        return workerService(completed, executionCount, generationBuildService());
    }

    private KnowledgeIndexWorkerService workerService(CountDownLatch completed,
                                                      GenerationIndexBuildService generationBuildService) {
        return workerService(completed, null, generationBuildService);
    }

    private KnowledgeIndexWorkerService workerService(CountDownLatch completed,
                                                      AtomicInteger executionCount,
                                                      GenerationIndexBuildService generationBuildService) {
        KnowledgeIndexWorkerService worker = new KnowledgeIndexWorkerService() {
            @Override
            public void execute(String jobCode) {
                if (executionCount != null) {
                    executionCount.incrementAndGet();
                }
                super.execute(jobCode);
                completed.countDown();
            }
        };
        ReflectionTestUtils.setField(worker, "leaseSeconds", 30);
        ReflectionTestUtils.setField(worker, "maxRetryCount", 3);
        ReflectionTestUtils.setField(worker, "retryDelaySeconds", 1);
        ReflectionTestUtils.setField(worker, "jobRepository", jobRepository());
        ReflectionTestUtils.setField(worker, "artifactPreparationService", preparationService());
        ReflectionTestUtils.setField(worker, "generationIndexBuildService", generationBuildService);
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
        return generationBuildService(vectorStore());
    }

    private GenerationIndexBuildService generationBuildService(IGenerationVectorStore vectorStore) {
        GenerationIndexBuildService service = new GenerationIndexBuildService();
        ReflectionTestUtils.setField(service, "generationRepository", generationRepository());
        ReflectionTestUtils.setField(service, "embeddingConfigRepository", embeddingConfigRepository());
        ReflectionTestUtils.setField(service, "documentRepository", documentRepository());
        ReflectionTestUtils.setField(service, "storageRepository", storage());
        DashScopeEmbeddingModel embeddingModel = new DashScopeEmbeddingModel();
        ReflectionTestUtils.setField(embeddingModel, "apiKey", "phase5-e2e-key");
        ReflectionTestUtils.setField(embeddingModel, "model", "phase5-e2e-model");
        ReflectionTestUtils.setField(embeddingModel, "dimensions", 3);
        ReflectionTestUtils.setField(embeddingModel, "provider", "phase5-e2e-provider");
        ReflectionTestUtils.setField(embeddingModel, "collectionName", COLLECTION);
        ReflectionTestUtils.setField(embeddingModel, "timeoutMs", 3000L);
        ReflectionTestUtils.setField(embeddingModel, "retryCount", 0);
        ReflectionTestUtils.setField(embeddingModel, "endpoint",
                "http://127.0.0.1:" + embeddingServer.getAddress().getPort() + "/embeddings");
        ReflectionTestUtils.setField(service, "embeddingClient", embeddingModel);
        ReflectionTestUtils.setField(service, "vectorStore", vectorStore);
        ReflectionTestUtils.setField(service, "servingVectorSpaceRepository", servingVectorSpaceRepository());
        ReflectionTestUtils.setField(service, "provider", "phase5-e2e-provider");
        ReflectionTestUtils.setField(service, "model", "phase5-e2e-model");
        ReflectionTestUtils.setField(service, "dimension", 3);
        ReflectionTestUtils.setField(service, "distance", "COSINE");
        ReflectionTestUtils.setField(service, "collection", COLLECTION);
        ReflectionTestUtils.setField(service, "batchSize", 2);
        return service;
    }

    private GenerationIndexBuildService realGenerationBuildService() {
        GenerationIndexBuildService service = new GenerationIndexBuildService();
        ReflectionTestUtils.setField(service, "generationRepository", generationRepository());
        ReflectionTestUtils.setField(service, "embeddingConfigRepository", embeddingConfigRepository());
        ReflectionTestUtils.setField(service, "documentRepository", documentRepository());
        ReflectionTestUtils.setField(service, "storageRepository", storage());
        DashScopeEmbeddingModel embeddingModel = realDashScopeModel();
        ReflectionTestUtils.setField(service, "embeddingClient", embeddingModel);
        ReflectionTestUtils.setField(service, "vectorStore", vectorStore());
        ReflectionTestUtils.setField(service, "servingVectorSpaceRepository", servingVectorSpaceRepository());
        ReflectionTestUtils.setField(service, "provider", "DashScope");
        ReflectionTestUtils.setField(service, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(service, "dimension", 1024);
        ReflectionTestUtils.setField(service, "distance", "COSINE");
        ReflectionTestUtils.setField(service, "collection", REAL_DASHSCOPE_COLLECTION);
        ReflectionTestUtils.setField(service, "batchSize", 16);
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

    private KnowledgeRetrievalServiceImpl retrievalService() {
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl();
        ReflectionTestUtils.setField(service, "workspaceRepository", workspaceRepository());
        ReflectionTestUtils.setField(service, "documentRepository", documentRepository());
        ReflectionTestUtils.setField(service, "documentVersionRepository", documentVersionRepository());
        ReflectionTestUtils.setField(service, "folderRepository", folderRepository());
        ReflectionTestUtils.setField(service, "knowledgeIndexStateRepository", indexStateRepository());
        ReflectionTestUtils.setField(service, "knowledgeIndexGenerationRepository", generationRepository());
        ReflectionTestUtils.setField(service, "servingVectorSpaceRepository", servingVectorSpaceRepository());
        var servingConfig = servingVectorSpaceRepository().findServingConfig().orElseThrow();
        DashScopeEmbeddingModel embeddingModel = new DashScopeEmbeddingModel();
        ReflectionTestUtils.setField(embeddingModel, "apiKey", "phase5-e2e-key");
        ReflectionTestUtils.setField(embeddingModel, "model", "phase5-e2e-model");
        ReflectionTestUtils.setField(embeddingModel, "dimensions", 3);
        ReflectionTestUtils.setField(embeddingModel, "provider", "phase5-e2e-provider");
        ReflectionTestUtils.setField(embeddingModel, "collectionName", COLLECTION);
        ReflectionTestUtils.setField(embeddingModel, "timeoutMs", 3000L);
        ReflectionTestUtils.setField(embeddingModel, "retryCount", 0);
        ReflectionTestUtils.setField(embeddingModel, "endpoint",
                "http://127.0.0.1:" + embeddingServer.getAddress().getPort() + "/embeddings");
        var queryConfig = com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity.builder()
                .provider("phase5-e2e-provider")
                .model("phase5-e2e-model")
                .dimension(3)
                .collectionName(COLLECTION)
                .build();
        ReflectionTestUtils.setField(service, "queryEmbeddingClient",
                (IQueryEmbeddingClient) (config, query) -> embeddingModel.embedQuery(queryConfig, query));
        ReflectionTestUtils.setField(service, "retrievalVectorStore", vectorStore());
        return service;
    }

    private KnowledgeRetrievalServiceImpl realRetrievalService() {
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl();
        ReflectionTestUtils.setField(service, "workspaceRepository", workspaceRepository());
        ReflectionTestUtils.setField(service, "documentRepository", documentRepository());
        ReflectionTestUtils.setField(service, "documentVersionRepository", documentVersionRepository());
        ReflectionTestUtils.setField(service, "folderRepository", folderRepository());
        ReflectionTestUtils.setField(service, "knowledgeIndexStateRepository", indexStateRepository());
        ReflectionTestUtils.setField(service, "knowledgeIndexGenerationRepository", generationRepository());
        ReflectionTestUtils.setField(service, "servingVectorSpaceRepository", servingVectorSpaceRepository());
        ReflectionTestUtils.setField(service, "queryEmbeddingClient",
                (IQueryEmbeddingClient) (config, query) -> realDashScopeModel().embedQuery(config, query));
        QdrantVectorStore realVectorStore = vectorStore();
        ReflectionTestUtils.setField(realVectorStore, "collection", REAL_DASHSCOPE_COLLECTION);
        ReflectionTestUtils.setField(service, "retrievalVectorStore", realVectorStore);
        return service;
    }

    private DashScopeEmbeddingModel realDashScopeModel() {
        DashScopeEmbeddingModel model = new DashScopeEmbeddingModel();
        ReflectionTestUtils.setField(model, "apiKey", System.getenv("DASHSCOPE_API_KEY"));
        ReflectionTestUtils.setField(model, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(model, "dimensions", 1024);
        ReflectionTestUtils.setField(model, "provider", "DashScope");
        ReflectionTestUtils.setField(model, "collectionName", REAL_DASHSCOPE_COLLECTION);
        ReflectionTestUtils.setField(model, "timeoutMs", 30_000L);
        ReflectionTestUtils.setField(model, "retryCount", 0);
        ReflectionTestUtils.setField(model, "endpoint",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings");
        return model;
    }

    private KnowledgeGenerationCleanupService cleanupService() {
        KnowledgeGenerationCleanupService service = new KnowledgeGenerationCleanupService();
        ReflectionTestUtils.setField(service, "generationRepository", generationRepository());
        ReflectionTestUtils.setField(service, "embeddingConfigRepository", embeddingConfigRepository());
        ReflectionTestUtils.setField(service, "vectorStore", vectorStore());
        ReflectionTestUtils.setField(service, "storageRepository", storage());
        ReflectionTestUtils.setField(service, "defaultCollection", COLLECTION);
        return service;
    }

    private boolean objectExists(String objectKey) {
        try {
            storage().download(objectKey);
            return true;
        } catch (Exception exception) {
            return false;
        }
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

    private String seedPendingUploadTask(DocumentEntity document) throws Exception {
        return seedPendingUploadTask(document, "# Phase 5\n\n正式版本内容。\n\n## 小节\n\n索引内容。");
    }

    private String seedPendingUploadTask(DocumentEntity document, String content) throws Exception {
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        String stagingPath = "staging/" + document.getId() + "/2.md";
        String targetPath = "documents/" + document.getId() + "/2.md";
        storage().upload(stagingPath, payload, "text/markdown");
        String taskCode = "phase5-upload-" + UUID.randomUUID();
        try (Connection connection = connection();
             PreparedStatement writing = connection.prepareStatement(
                     "UPDATE document SET status = 1 WHERE id = ?");
             PreparedStatement task = connection.prepareStatement(
                     "INSERT INTO document_upload_task (task_code, document_id, version_no, created_by, staging_path, "
                             + "target_path, content_type, bucket_name, content_size, content_hash, status, retry_count) "
                             + "VALUES (?, ?, 2, 1, ?, ?, 'text/markdown', ?, ?, ?, 0, 0)")) {
            writing.setLong(1, document.getId());
            writing.executeUpdate();
            task.setString(1, taskCode);
            task.setLong(2, document.getId());
            task.setString(3, stagingPath);
            task.setString(4, targetPath);
            task.setString(5, BUCKET);
            task.setLong(6, payload.length);
            task.setString(7, sha256(payload));
            task.executeUpdate();
        }
        return taskCode;
    }

    private DocumentUploadTaskServiceImpl documentUploadTaskService() throws Exception {
        DocumentUploadTaskRepository tasks = new DocumentUploadTaskRepository();
        ReflectionTestUtils.setField(tasks, "documentUploadTaskDao", template.getMapper(IDocumentUploadTaskDao.class));
        KnowledgeIndexJobServiceImpl indexService = new KnowledgeIndexJobServiceImpl();
        ReflectionTestUtils.setField(indexService, "jobRepository", jobRepository());
        ReflectionTestUtils.setField(indexService, "outboxEventRepository", outboxRepository());

        DocumentUploadTaskServiceImpl service = new DocumentUploadTaskServiceImpl();
        ReflectionTestUtils.setField(service, "taskRepository", tasks);
        ReflectionTestUtils.setField(service, "documentRepository", documentRepository());
        ReflectionTestUtils.setField(service, "documentVersionRepository", documentVersionRepository());
        ReflectionTestUtils.setField(service, "storageRepository", storage());
        ReflectionTestUtils.setField(service, "knowledgeIndexJobService", indexService);
        ReflectionTestUtils.setField(service, "transactionTemplate",
                new TransactionTemplate(new DataSourceTransactionManager(dataSource())));
        return service;
    }

    private DocumentEntity seedEffectiveDocument(String content) throws Exception {
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        long documentId;
        try (Connection connection = connection(); Statement workspace = connection.createStatement()) {
            workspace.execute("INSERT INTO workspace (id, workspace_code, workspace_name, is_default, status, owner_user_id) "
                    + "VALUES (1, 'workspace-phase5', 'Phase 5 Workspace', 1, 0, 1)");
            workspace.execute("INSERT INTO folder (id, folder_code, workspace_id, parent_id, name, is_root, status) "
                    + "VALUES (1, 'folder-phase5-root', 1, NULL, '根目录', 1, 0)");
        }
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

    private WorkspaceRepository workspaceRepository() {
        WorkspaceRepository repository = new WorkspaceRepository();
        ReflectionTestUtils.setField(repository, "workspaceDao", template.getMapper(IWorkspaceDao.class));
        return repository;
    }

    private FolderRepository folderRepository() {
        FolderRepository repository = new FolderRepository();
        ReflectionTestUtils.setField(repository, "folderDao", template.getMapper(IFolderDao.class));
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
        ReflectionTestUtils.setField(store, "collection", COLLECTION);
        return store;
    }

    private SqlSessionTemplate mybatisTemplate() throws Exception {
        DataSource source = dataSource();
        Configuration configuration = new Configuration(new Environment("phase3-e2e", new SpringManagedTransactionFactory(), source));
        for (String mapper : List.of(
                "document.xml", "document_version.xml", "knowledge_index_job.xml", "knowledge_index_outbox_event.xml",
                "document_upload_task.xml", "workspace.xml", "folder.xml",
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

    private long queryLong(String sql, Object... values) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
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

    private String queryString(String sql) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
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
