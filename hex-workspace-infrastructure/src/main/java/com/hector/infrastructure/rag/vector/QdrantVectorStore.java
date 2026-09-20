package com.hector.infrastructure.rag.vector;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.knowledge.adapter.port.IVectorStore;
import com.hector.domain.knowledge.adapter.port.IGenerationVectorStore;
import com.hector.domain.knowledge.model.valobj.GenerationVectorPointVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeSearchHitVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeVectorPointVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Qdrant REST 适配器。
 * Qdrant 仅保存可再生的向量索引；Workspace 与文档正文仍由 MySQL/MinIO 管理。
 */
@Component
public class QdrantVectorStore implements IVectorStore, IGenerationVectorStore {

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${ai.vector-store.qdrant.endpoint:http://127.0.0.1:6333}")
    private String endpoint;
    @Value("${ai.vector-store.qdrant.collection:hex_workspace_segments}")
    private String collection;

    @Override
    public void ensureCollection(Integer dimension, String distance, String collectionName) {
        try {
            HttpResponse<String> response = send("GET", "/collections/" + collectionName, null);
            if (response.statusCode() == 404) {
                JSONObject vectors = new JSONObject();
                vectors.put("size", dimension);
                vectors.put("distance", qdrantDistance(distance));

                JSONObject body = new JSONObject();
                body.put("vectors", vectors);
                request("PUT", "/collections/" + collectionName, body.toJSONString());
                return;
            }
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Qdrant 查询集合失败: " + response.statusCode());
            }
            JSONObject vectors = JSON.parseObject(response.body())
                    .getJSONObject("result")
                    .getJSONObject("config")
                    .getJSONObject("params")
                    .getJSONObject("vectors");
            if (!dimension.equals(vectors.getInteger("size")) || !distance.equalsIgnoreCase(vectors.getString("distance"))) {
                throw new IllegalStateException("Qdrant Collection 向量空间不兼容");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Qdrant 连接异常", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant 调用被中断", exception);
        }
    }

    @Override
    public void upsert(String collectionName, List<GenerationVectorPointVO> points) {
        if (points.isEmpty()) return;
        JSONArray qdrantPoints = new JSONArray();
        for (GenerationVectorPointVO source : points) {
            JSONObject point = new JSONObject();
            point.put("id", source.pointId());
            point.put("vector", source.vector());
            point.put("payload", source.payload());
            qdrantPoints.add(point);
        }
        JSONObject body = new JSONObject();
        body.put("points", qdrantPoints);
        request("PUT", "/collections/" + collectionName + "/points?wait=true", body.toJSONString());
    }

    @Override
    public long countByGeneration(String collectionName, String generationId) {
        JSONObject body = new JSONObject();
        body.put("exact", true);
        body.put("filter", filter("generation_id", generationId));
        JSONObject response = JSON.parseObject(request("POST", "/collections/" + collectionName + "/points/count", body.toJSONString()));
        return response.getJSONObject("result").getLongValue("count");
    }

    @Override
    public void verifyGenerationPoints(String collectionName, String generationId, List<String> expectedPointIds) {
        if (countByGeneration(collectionName, generationId) != expectedPointIds.size()) {
            throw new IllegalStateException("Qdrant Generation 向量数量与 Chunk 数量不一致");
        }
        Set<String> actualPointIds = new HashSet<>();
        for (int offset = 0; offset < expectedPointIds.size(); offset += 256) {
            List<String> batchIds = expectedPointIds.subList(offset, Math.min(expectedPointIds.size(), offset + 256));
            JSONObject requestBody = new JSONObject();
            requestBody.put("ids", batchIds);
            requestBody.put("with_payload", true);
            requestBody.put("with_vector", false);
            JSONObject response = JSON.parseObject(request(
                    "POST", "/collections/" + collectionName + "/points", requestBody.toJSONString()));
            JSONArray points = response.getJSONArray("result");
            for (Object item : points) {
                JSONObject point = (JSONObject) item;
                JSONObject payload = point.getJSONObject("payload");
                if (!generationId.equals(payload.getString("generation_id"))) {
                    throw new IllegalStateException("Qdrant Point Generation 归属不匹配");
                }
                actualPointIds.add(point.getString("id"));
            }
        }
        Set<String> expected = new HashSet<>(expectedPointIds);
        if (expected.size() != expectedPointIds.size() || !expected.equals(actualPointIds)) {
            throw new IllegalStateException("Qdrant Generation Point 集合不完整或包含意外 Point");
        }
    }

    @Override
    public void replaceDocument(Long workspaceId, Long documentId, List<KnowledgeVectorPointVO> points) {
        if (points.isEmpty()) return;
        ensureCollection(points.get(0).vector().size());

        // 1. 仅替换指定 Workspace 与文档的索引点；知识索引不保存编辑历史。
        JSONObject filter = new JSONObject();
        filter.put("must", List.of(
                buildMatchCondition("workspace_id", workspaceId),
                buildMatchCondition("document_id", documentId)
        ));
        JSONObject deleteBody = new JSONObject();
        deleteBody.put("filter", filter);
        request("POST", "/collections/" + collection + "/points/delete?wait=true", deleteBody.toJSONString());

        // 2. Qdrant 只接受无符号整数或 UUID 作为 point ID；业务复合键经 name-based UUID 转换后可稳定幂等写入。
        JSONArray qdrantPoints = new JSONArray();
        for (KnowledgeVectorPointVO pointValue : points) {
            JSONObject payload = new JSONObject();
            payload.put("workspace_id", workspaceId); payload.put("document_id", documentId);
            payload.put("document_code", pointValue.documentCode()); payload.put("chunk_no", pointValue.chunkNo());
            payload.put("heading", pointValue.heading()); payload.put("content", pointValue.content());
            JSONObject point = new JSONObject();
            point.put("id", toQdrantPointId(pointValue.businessPointId()));
            point.put("vector", pointValue.vector());
            point.put("payload", payload);
            qdrantPoints.add(point);
        }
        JSONObject body = new JSONObject(); body.put("points", qdrantPoints);
        request("PUT", "/collections/" + collection + "/points?wait=true", body.toJSONString());
    }

    @Override
    public List<KnowledgeSearchHitVO> search(Long workspaceId, List<Float> queryVector, int topK) {
        JSONObject match = new JSONObject(); match.put("value", workspaceId);
        JSONObject condition = new JSONObject(); condition.put("key", "workspace_id"); condition.put("match", match);
        JSONObject filter = new JSONObject(); filter.put("must", List.of(condition));
        JSONObject body = new JSONObject(); body.put("vector", queryVector); body.put("limit", topK); body.put("with_payload", true); body.put("filter", filter);
        JSONObject result = JSON.parseObject(request("POST", "/collections/" + collection + "/points/search", body.toJSONString()));
        JSONArray points = result.getJSONArray("result");
        List<KnowledgeSearchHitVO> hits = new ArrayList<>();
        for (Object item : points) {
            JSONObject point = (JSONObject) item;
            JSONObject payload = point.getJSONObject("payload");
            hits.add(new KnowledgeSearchHitVO(payload.getLong("document_id"), payload.getString("document_code"),
                    payload.getInteger("chunk_no"), payload.getString("heading"), payload.getString("content"), point.getDouble("score")));
        }
        return hits;
    }

    private void ensureCollection(int vectorSize) {
        ensureCollection(vectorSize, "Cosine", collection);
    }

    private JSONObject buildMatchCondition(String key, Object value) {
        JSONObject match = new JSONObject();
        match.put("value", value);
        JSONObject condition = new JSONObject();
        condition.put("key", key);
        condition.put("match", match);
        return condition;
    }

    private JSONObject filter(String key, Object value) {
        JSONObject filter = new JSONObject();
        filter.put("must", List.of(buildMatchCondition(key, value)));
        return filter;
    }

    private String toQdrantPointId(String businessPointId) {
        return UUID.nameUUIDFromBytes(businessPointId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 配置层使用统一的大写枚举，Qdrant REST 枚举要求首字母大写。 */
    private String qdrantDistance(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Qdrant 距离度量不能为空");
        }
        String normalized = value.trim().toLowerCase();
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String request(String method, String path, String body) {
        try {
            HttpResponse<String> response = send(method, path, body);
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Qdrant 调用失败: " + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (IOException exception) {
            throw new IllegalStateException("Qdrant 网络调用异常", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant 调用被中断", exception);
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(30));
        if (body != null) builder.header("Content-Type", "application/json");
        return httpClient.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
