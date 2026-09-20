package com.hector.infrastructure.rag.vector;

import com.hector.domain.knowledge.model.valobj.GenerationVectorPointVO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 真实 Qdrant REST 适配器验收：集合参数、幂等写入与 Generation 隔离计数。 */
class QdrantVectorStoreIntegrationTest {
    private static final GenericContainer<?> QDRANT = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.11.0"))
            .withExposedPorts(6333);

    private static QdrantVectorStore store;

    @BeforeAll
    static void startQdrant() {
        QDRANT.start();
        store = new QdrantVectorStore();
        ReflectionTestUtils.setField(store, "endpoint", "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
    }

    @AfterAll
    static void stopQdrant() {
        QDRANT.stop();
    }

    @Test
    void shouldValidateCollectionAndCountGenerationPoints() {
        String collection = "phase3_vectors";
        store.ensureCollection(3, "COSINE", collection);
        store.upsert(collection, List.of(
                new GenerationVectorPointVO("0d83363e-4824-42f4-b685-3e677a0b0a21", List.of(0.1F, 0.2F, 0.3F), Map.of("generation_id", "g-1")),
                new GenerationVectorPointVO("3a6c331b-fc84-4e2e-b8ed-e4a5a20b76cb", List.of(0.3F, 0.2F, 0.1F), Map.of("generation_id", "g-1"))));
        assertEquals(2L, store.countByGeneration(collection, "g-1"));
        store.verifyGenerationPoints(collection, "g-1", List.of(
                "0d83363e-4824-42f4-b685-3e677a0b0a21",
                "3a6c331b-fc84-4e2e-b8ed-e4a5a20b76cb"));

        // 相同 UUID 的重复写入覆盖原 point，不会累计为重复向量。
        store.upsert(collection, List.of(new GenerationVectorPointVO("0d83363e-4824-42f4-b685-3e677a0b0a21",
                List.of(0.1F, 0.2F, 0.3F), Map.of("generation_id", "g-1"))));
        assertEquals(2L, store.countByGeneration(collection, "g-1"));
        assertThrows(IllegalStateException.class, () -> store.verifyGenerationPoints(collection, "g-1", List.of(
                "0d83363e-4824-42f4-b685-3e677a0b0a21",
                "9a261e5f-d25a-4c55-a0c3-2e93e03b457f")));
        assertThrows(IllegalStateException.class, () -> store.ensureCollection(4, "COSINE", collection));
    }

    @Test
    void shouldRejectAdditionalPointInExpectedGenerationSet() {
        String collection = "phase3_vectors_extra";
        store.ensureCollection(3, "COSINE", collection);
        String p1 = UUID.randomUUID().toString();
        String p2 = UUID.randomUUID().toString();
        String p3 = UUID.randomUUID().toString();
        String p4 = UUID.randomUUID().toString();
        store.upsert(collection, List.of(
                point(p1, "g-extra"), point(p2, "g-extra"), point(p3, "g-extra"), point(p4, "g-extra")));

        assertThrows(IllegalStateException.class, () -> store.verifyGenerationPoints(
                collection, "g-extra", List.of(p1, p2, p3)));
    }

    private GenerationVectorPointVO point(String pointId, String generationId) {
        return new GenerationVectorPointVO(pointId, List.of(0.1F, 0.2F, 0.3F), Map.of("generation_id", generationId));
    }
}
