package com.hector.infrastructure.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hector.domain.document.model.valobj.DocumentStorageObjectVO;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.knowledge.repository.IKnowledgeParseArtifactRepository;
import com.hector.infrastructure.dao.IDocumentDao;
import com.hector.infrastructure.dao.IDocumentVersionDao;
import com.hector.infrastructure.dao.IKnowledgeIndexGenerationDao;
import com.hector.infrastructure.dao.IKnowledgeIndexJobDao;
import com.hector.infrastructure.dao.IKnowledgeParseArtifactDao;
import com.hector.infrastructure.rag.KnowledgeArtifactPreparationService;
import com.hector.infrastructure.rag.chunk.StructureAwareDocumentChunker;
import com.hector.infrastructure.rag.loader.DocumentVersionLoader;
import com.hector.infrastructure.rag.parser.DocumentParserSelector;
import com.hector.infrastructure.rag.parser.MarkdownDocumentParser;
import com.hector.infrastructure.rag.parser.TextDocumentParser;
import com.hector.infrastructure.rag.parser.DocxDocumentParser;
import com.hector.infrastructure.rag.parser.PdfDocumentParser;
import com.hector.infrastructure.repository.DocumentRepository;
import com.hector.infrastructure.repository.DocumentVersionRepository;
import com.hector.infrastructure.repository.KnowledgeIndexGenerationRepository;
import com.hector.infrastructure.repository.KnowledgeIndexJobRepository;
import com.hector.infrastructure.repository.KnowledgeParseArtifactRepository;
import com.hector.hexworkspace.types.exception.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 2 真实 MySQL + MinIO 产物链路验收测试。 */
class KnowledgeArtifactPreparationIntegrationTest {

    private static final String BUCKET = "phase2-test";
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hex_workspace").withUsername("test").withPassword("test");
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:RELEASE.2024-06-13T22-53-53Z"))
            .withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000).withCommand("server", "/data");

    private SqlSessionTemplate template;
    private RealMinioStorage storage;

    @BeforeAll
    static void startContainers() throws Exception {
        MYSQL.start();
        MINIO.start();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            String script = Files.readString(Path.of("..", "docs", "devops", "mysql", "sql", "001-init-hex-workspace.sql"))
                    .replaceAll("(?m)^--.*$", "").replaceFirst("(?s)CREATE DATABASE.*?;", "").replaceFirst("(?s)USE `hex_workspace`;", "");
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }

    @AfterAll
    static void stopContainers() {
        MINIO.stop();
        MYSQL.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String table : List.of("knowledge_index_generation", "knowledge_parse_artifact", "knowledge_index_job", "knowledge_index_state", "document_version", "document")) {
                statement.execute("DELETE FROM " + table);
            }
        }
        MinioClient client = MinioClient.builder().endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials("minioadmin", "minioadmin").build();
        storage = new RealMinioStorage(client);
        storage.ensureBucket();
        template = mybatisTemplate();
    }

    @Test
    void shouldPrepareCompleteArtifactChainAndReuseParsedArtifact() throws Exception {
        Seed seed = seed("# 标题\n\n第一段内容。\n\n## 子标题\n\n第二段内容。", "worker-a");
        KnowledgeArtifactPreparationService service = service(storage, parseRepository());

        service.prepare(seed.jobCode(), "worker-a");

        String parseKey = queryString("SELECT artifact_key FROM knowledge_parse_artifact WHERE document_id = ?", seed.documentId());
        String parseChecksum = queryString("SELECT artifact_checksum FROM knowledge_parse_artifact WHERE document_id = ?", seed.documentId());
        String chunkKey = queryString("SELECT chunk_artifact_key FROM knowledge_index_generation WHERE job_id = ?", seed.jobId());
        String chunkChecksum = queryString("SELECT chunk_artifact_checksum FROM knowledge_index_generation WHERE job_id = ?", seed.jobId());
        byte[] parsed = storage.download(parseKey);
        byte[] chunks = storage.download(chunkKey);
        assertEquals(sha256(parsed), parseChecksum);
        assertEquals(sha256(chunks), chunkChecksum);
        assertEquals(2L, new ObjectMapper().readTree(chunks).size());
        assertEquals(2L, queryLong("SELECT chunk_count FROM knowledge_index_generation WHERE job_id = ?", seed.jobId()));
        assertEquals(0L, queryLong("SELECT status FROM knowledge_index_generation WHERE job_id = ?", seed.jobId()));
        assertEquals(1L, queryLong("SELECT status FROM knowledge_index_job WHERE id = ?", seed.jobId()));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_index_state", seed.jobId()));

        Seed second = seedExistingDocument(seed.documentId(), "worker-b");
        service.prepare(second.jobCode(), "worker-b");
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_parse_artifact WHERE document_id = ?", seed.documentId()));
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM knowledge_index_generation WHERE document_id = ?", seed.documentId()));
    }

    @Test
    void shouldRejectReuseWhenParsedObjectMissingOrChecksumInvalid() throws Exception {
        Seed seed = seed("# 标题\n\n正文", "worker-a");
        KnowledgeArtifactPreparationService service = service(storage, parseRepository());
        service.prepare(seed.jobCode(), "worker-a");
        String key = queryString("SELECT artifact_key FROM knowledge_parse_artifact WHERE document_id = ?", seed.documentId());
        storage.delete(key);
        Seed missing = seedExistingDocument(seed.documentId(), "worker-b");
        assertThrows(BusinessException.class, () -> service.prepare(missing.jobCode(), "worker-b"));

        storage.upload(key, "被篡改".getBytes(StandardCharsets.UTF_8), "application/json");
        Seed corrupted = seedExistingDocument(seed.documentId(), "worker-c");
        assertThrows(BusinessException.class, () -> service.prepare(corrupted.jobCode(), "worker-c"));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_parse_artifact WHERE document_id = ?", seed.documentId()));
    }

    @Test
    void shouldNotCreateValidMetadataWhenMinioOrMysqlWriteFails() throws Exception {
        Seed minioFailure = seed("# 标题\n\n正文", "worker-a");
        IDocumentStorageRepository failingStorage = new FailingStorage(storage);
        assertThrows(BusinessException.class, () -> service(failingStorage, parseRepository()).prepare(minioFailure.jobCode(), "worker-a"));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_parse_artifact WHERE document_id = ?", minioFailure.documentId()));

        Seed mysqlFailure = seed("# 标题\n\n正文", "worker-b");
        IKnowledgeParseArtifactRepository failingRepository = new FailingParseRepository(parseRepository());
        assertThrows(BusinessException.class, () -> service(storage, failingRepository).prepare(mysqlFailure.jobCode(), "worker-b"));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM knowledge_parse_artifact WHERE document_id = ?", mysqlFailure.documentId()));
    }

    @Test
    void shouldAllowConcurrentPreparationButKeepOneParsedArtifact() throws Exception {
        Seed first = seed("# 标题\n\n并发正文", "worker-a");
        Seed second = seedExistingDocument(first.documentId(), "worker-b");
        KnowledgeArtifactPreparationService service = service(storage, parseRepository());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = executor.submit(() -> { await(start); service.prepare(first.jobCode(), "worker-a"); });
            Future<?> b = executor.submit(() -> { await(start); service.prepare(second.jobCode(), "worker-b"); });
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM knowledge_parse_artifact WHERE document_id = ?", first.documentId()));
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM knowledge_index_generation WHERE document_id = ?", first.documentId()));
    }

    @Test
    void shouldRejectExpiredWorkerAndAllowNewWorkerGenerationToAttachChunk() throws Exception {
        Seed first = seed("# 标题\n\n正文", "worker-a");
        long oldGenerationId = insertGeneration(first.jobId(), first.documentId(), "generation-a", "worker-a");
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE knowledge_index_job SET worker_token = 'worker-b', running_lease_until = ? WHERE id = ?")) {
            statement.setObject(1, LocalDateTime.now().plusMinutes(5));
            statement.setLong(2, first.jobId());
            statement.executeUpdate();
        }
        KnowledgeIndexGenerationRepository generations = generationRepository();
        assertFalse(generations.attachChunkArtifactIfOwned("generation-a", "worker-a", "knowledge/chunks/a.json", "a", 1, LocalDateTime.now()));
        long newGenerationId = insertGeneration(first.jobId(), first.documentId(), "generation-b", "worker-b");
        assertTrue(generations.attachChunkArtifactIfOwned("generation-b", "worker-b", "knowledge/chunks/b.json", "b", 1, LocalDateTime.now()));
        assertEquals(0L, queryLong("SELECT chunk_count FROM knowledge_index_generation WHERE id = ?", oldGenerationId));
        assertEquals(1L, queryLong("SELECT chunk_count FROM knowledge_index_generation WHERE id = ?", newGenerationId));
    }

    private KnowledgeArtifactPreparationService service(IDocumentStorageRepository objectStorage, IKnowledgeParseArtifactRepository parseArtifacts) throws Exception {
        DocumentRepository documents = new DocumentRepository();
        ReflectionTestUtils.setField(documents, "documentDao", template.getMapper(IDocumentDao.class));
        DocumentVersionRepository versions = new DocumentVersionRepository();
        ReflectionTestUtils.setField(versions, "documentVersionDao", template.getMapper(IDocumentVersionDao.class));
        DocumentVersionLoader loader = new DocumentVersionLoader();
        ReflectionTestUtils.setField(loader, "documentRepository", documents);
        ReflectionTestUtils.setField(loader, "documentVersionRepository", versions);
        ReflectionTestUtils.setField(loader, "documentStorageRepository", objectStorage);
        ReflectionTestUtils.setField(loader, "maxSourceBytes", 10_000_000L);
        StructureAwareDocumentChunker chunker = new StructureAwareDocumentChunker();
        ReflectionTestUtils.setField(chunker, "targetSize", 1000);
        ReflectionTestUtils.setField(chunker, "maxSize", 1400);
        ReflectionTestUtils.setField(chunker, "overlapSize", 160);
        KnowledgeArtifactPreparationService service = new KnowledgeArtifactPreparationService();
        ReflectionTestUtils.setField(service, "knowledgeIndexJobRepository", jobRepository());
        ReflectionTestUtils.setField(service, "knowledgeIndexGenerationRepository", generationRepository());
        ReflectionTestUtils.setField(service, "knowledgeParseArtifactRepository", parseArtifacts);
        ReflectionTestUtils.setField(service, "documentRepository", documents);
        ReflectionTestUtils.setField(service, "documentLoader", loader);
        ReflectionTestUtils.setField(service, "documentParserSelector", new DocumentParserSelector(List.of(new MarkdownDocumentParser(), new TextDocumentParser(), new DocxDocumentParser(), new PdfDocumentParser())));
        ReflectionTestUtils.setField(service, "parsedDocumentChunker", chunker);
        ReflectionTestUtils.setField(service, "documentStorageRepository", objectStorage);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        return service;
    }

    private Seed seed(String content, String workerToken) throws Exception {
        long documentId;
        try (Connection connection = connection(); PreparedStatement document = connection.prepareStatement(
                "INSERT INTO document (document_code, workspace_id, parent_id, owner_user_id, title, file_name, document_type, status, current_version_no, index_input_revision) VALUES (?, 1, 1, 1, '测试', '测试.md', 1, 2, 1, 1)", Statement.RETURN_GENERATED_KEYS)) {
            document.setString(1, "doc-" + UUID.randomUUID()); document.executeUpdate();
            try (ResultSet keys = document.getGeneratedKeys()) { keys.next(); documentId = keys.getLong(1); }
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String path = "documents/" + documentId + "/1.md";
        storage.upload(path, bytes, "text/markdown");
        try (Connection connection = connection(); PreparedStatement version = connection.prepareStatement(
                "INSERT INTO document_version (document_id, version_no, storage_type, bucket_name, storage_path, content_type, content_size, content_hash, created_by) VALUES (?, 1, 0, ?, ?, 'text/markdown', ?, ?, 1)")) {
            version.setLong(1, documentId); version.setString(2, BUCKET); version.setString(3, path); version.setLong(4, bytes.length); version.setString(5, sha256(bytes)); version.executeUpdate();
        }
        return insertRunningJob(documentId, workerToken);
    }

    private Seed seedExistingDocument(long documentId, String workerToken) throws Exception { return insertRunningJob(documentId, workerToken); }

    private Seed insertRunningJob(long documentId, String workerToken) throws Exception {
        try (Connection connection = connection(); PreparedStatement job = connection.prepareStatement(
                "INSERT INTO knowledge_index_job (job_code, request_key, workspace_id, document_id, version_no, index_input_revision, job_type, status, stage, worker_token, running_lease_until, retry_count) VALUES (?, ?, 1, ?, 1, 1, 0, 1, 0, ?, ?, 0)", Statement.RETURN_GENERATED_KEYS)) {
            String code = "job-" + UUID.randomUUID(); job.setString(1, code); job.setString(2, "request-" + UUID.randomUUID()); job.setLong(3, documentId); job.setString(4, workerToken); job.setObject(5, LocalDateTime.now().plusMinutes(5)); job.executeUpdate();
            try (ResultSet keys = job.getGeneratedKeys()) { keys.next(); return new Seed(documentId, keys.getLong(1), code); }
        }
    }

    private long insertGeneration(long jobId, long documentId, String generationId, String workerToken) throws Exception {
        try (Connection connection = connection(); PreparedStatement generation = connection.prepareStatement(
                "INSERT INTO knowledge_index_generation (generation_id, job_id, workspace_id, document_id, version_no, index_input_revision, chunking_config_hash, status, worker_token) VALUES (?, ?, 1, ?, 1, 1, 'test', 0, ?)", Statement.RETURN_GENERATED_KEYS)) {
            generation.setString(1, generationId); generation.setLong(2, jobId); generation.setLong(3, documentId); generation.setString(4, workerToken); generation.executeUpdate();
            try (ResultSet keys = generation.getGeneratedKeys()) { keys.next(); return keys.getLong(1); }
        }
    }

    private IKnowledgeParseArtifactRepository parseRepository() {
        KnowledgeParseArtifactRepository repository = new KnowledgeParseArtifactRepository();
        ReflectionTestUtils.setField(repository, "knowledgeParseArtifactDao", template.getMapper(IKnowledgeParseArtifactDao.class));
        return repository;
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

    private SqlSessionTemplate mybatisTemplate() throws Exception {
        DataSource dataSource = new org.springframework.jdbc.datasource.SimpleDriverDataSource(new com.mysql.cj.jdbc.Driver(), MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        for (String mapper : List.of("document.xml", "document_version.xml", "knowledge_index_job.xml", "knowledge_parse_artifact.xml", "knowledge_index_generation.xml")) {
            new XMLMapperBuilder(org.apache.ibatis.io.Resources.getResourceAsStream("mybatis/mapper/mysql/" + mapper), configuration, mapper, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory);
    }

    private long queryLong(String sql, long id) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (sql.contains("?")) statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    private String queryString(String sql, long id) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) { statement.setLong(1, id); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getString(1); } }
    }

    private static Connection connection() throws Exception { return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); }
    private static void await(CountDownLatch latch) { try { latch.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); } }
    private static String sha256(byte[] source) throws Exception { byte[] digest = MessageDigest.getInstance("SHA-256").digest(source); StringBuilder result = new StringBuilder(); for (byte value : digest) result.append(String.format("%02x", value)); return result.toString(); }
    private record Seed(long documentId, long jobId, String jobCode) { }

    private static class RealMinioStorage implements IDocumentStorageRepository {
        private final MinioClient client;
        private RealMinioStorage(MinioClient client) { this.client = client; }
        private void ensureBucket() throws Exception { if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build()); }
        public String getBucketName() { return BUCKET; }
        public void upload(String key, byte[] content, String type) { upload(key, new ByteArrayInputStream(content), content.length, type); }
        public void upload(String key, java.io.InputStream content, long size, String type) { try { ensureBucket(); client.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key).stream(content, size, -1).contentType(type).build()); } catch (Exception exception) { throw new IllegalStateException(exception); } }
        public void copy(String source, String target, String type) { throw new UnsupportedOperationException(); }
        public byte[] download(String key) { try (java.io.InputStream input = client.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).build())) { return input.readAllBytes(); } catch (Exception exception) { throw new IllegalStateException(exception); } }
        public void delete(String key) { try { client.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(key).build()); } catch (Exception exception) { throw new IllegalStateException(exception); } }
        public List<DocumentStorageObjectVO> listByPrefix(String prefix) { return List.of(); }
    }

    private static class FailingStorage implements IDocumentStorageRepository {
        private final IDocumentStorageRepository delegate;
        private FailingStorage(IDocumentStorageRepository delegate) { this.delegate = delegate; }
        public String getBucketName() { return delegate.getBucketName(); }
        public void upload(String key, byte[] content, String type) { throw new IllegalStateException("注入 MinIO 上传失败"); }
        public void upload(String key, java.io.InputStream content, long size, String type) { throw new IllegalStateException("注入 MinIO 上传失败"); }
        public void copy(String source, String target, String type) { delegate.copy(source, target, type); }
        public byte[] download(String key) { return delegate.download(key); }
        public void delete(String key) { delegate.delete(key); }
        public List<DocumentStorageObjectVO> listByPrefix(String prefix) { return delegate.listByPrefix(prefix); }
    }

    private static class FailingParseRepository implements IKnowledgeParseArtifactRepository {
        private final IKnowledgeParseArtifactRepository delegate;
        private FailingParseRepository(IKnowledgeParseArtifactRepository delegate) { this.delegate = delegate; }
        public java.util.Optional<com.hector.domain.knowledge.model.entity.KnowledgeParseArtifactEntity> findByIdentity(Long a, Long b, String c, String d, String e) { return delegate.findByIdentity(a, b, c, d, e); }
        public com.hector.domain.knowledge.model.entity.KnowledgeParseArtifactEntity saveIfAbsent(com.hector.domain.knowledge.model.entity.KnowledgeParseArtifactEntity artifact) { throw new IllegalStateException("注入 MySQL 写入失败"); }
    }
}
