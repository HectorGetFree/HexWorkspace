package com.hector.infrastructure.rag.embedding;

import com.hector.domain.knowledge.model.valobj.EmbeddingVectorVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 显式执行的真实 DashScope Smoke Test；不属于普通测试回归。 */
class RealDashScopeEmbeddingSmokeTest {

    @Test
    void shouldEmbedTextUsingRealDashScope() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未注入 DASHSCOPE_API_KEY，不能执行真实在线联调");
        }

        DashScopeEmbeddingModel model = new DashScopeEmbeddingModel();
        ReflectionTestUtils.setField(model, "apiKey", apiKey);
        ReflectionTestUtils.setField(model, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(model, "dimensions", 1024);
        ReflectionTestUtils.setField(model, "provider", "DashScope");
        ReflectionTestUtils.setField(model, "collectionName", "hex_workspace_rag_v1");
        ReflectionTestUtils.setField(model, "timeoutMs", 30_000L);
        ReflectionTestUtils.setField(model, "retryCount", 0);
        ReflectionTestUtils.setField(model, "endpoint",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings");

        long startedAt = System.nanoTime();
        List<EmbeddingVectorVO> result = model.embedIndexed(List.of("Hex Workspace RAG V1 真实 Embedding 联调。"));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertFalse(result.isEmpty());
        assertNotNull(result.get(0).vector());
        assertEquals(1024, result.get(0).vector().size());
        result.get(0).vector().forEach(value -> {
            assertNotNull(value);
            assertFalse(value.isNaN());
            assertFalse(value.isInfinite());
        });
        System.out.println("REAL_DASHSCOPE_MODEL=text-embedding-v4");
        System.out.println("REAL_DASHSCOPE_DIMENSION=" + result.get(0).vector().size());
        System.out.println("REAL_DASHSCOPE_REQUEST_MILLIS=" + elapsedMillis);
    }
}
