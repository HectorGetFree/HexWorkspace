package com.hector.infrastructure.rag.vector;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.knowledge.adapter.port.IVectorStore;
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
import java.util.List;
import java.util.UUID;

/**
 * Qdrant REST 适配器。
 * Qdrant 仅保存可再生的向量索引；Workspace、文档版本与解析产物仍由 MySQL/MinIO 管理。
 */
@Component
public class QdrantVectorStore implements IVectorStore {

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${ai.vector-store.qdrant.endpoint:http://127.0.0.1:6333}")
    private String endpoint;
    @Value("${ai.vector-store.qdrant.collection:hex_workspace_segments}")
    private String collection;

    @Override
    public void replaceDocumentVersion(Long workspaceId, Long documentId, Integer versionNumber,
                                       List<KnowledgeVectorPointVO> points) {
        if (points.isEmpty()) return;
        ensureCollection(points.get(0).vector().size());

        // 1. 仅替换指定Workspace、文档、版本的索引点，保留该文档其他版本以支持版本追溯。
        JSONObject filter = new JSONObject();
        filter.put("must", List.of(
                buildMatchCondition("workspace_id", workspaceId),
                buildMatchCondition("document_id", documentId),
                buildMatchCondition("version_number", versionNumber)
        ));
        JSONObject deleteBody = new JSONObject();
        deleteBody.put("filter", filter);
        request("POST", "/collections/" + collection + "/points/delete?wait=true", deleteBody.toJSONString());

        // 2. Qdrant 只接受无符号整数或 UUID 作为 point ID；业务复合键经 name-based UUID 转换后可稳定幂等写入。
        JSONArray qdrantPoints = new JSONArray();
        for (KnowledgeVectorPointVO pointValue : points) {
            JSONObject payload = new JSONObject();
            payload.put("workspace_id", workspaceId); payload.put("document_id", documentId); payload.put("version_number", versionNumber);
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
            hits.add(new KnowledgeSearchHitVO(payload.getLong("document_id"), payload.getString("document_code"), payload.getInteger("version_number"),
                    payload.getInteger("chunk_no"), payload.getString("heading"), payload.getString("content"), point.getDouble("score")));
        }
        return hits;
    }

    private void ensureCollection(int vectorSize) {
        try {
            HttpResponse<String> response = send("GET", "/collections/" + collection, null);
            if (response.statusCode() == 200) return;
            if (response.statusCode() != 404) throw new IllegalStateException("Qdrant 查询集合失败: " + response.statusCode() + " " + response.body());
            JSONObject vectors = new JSONObject(); vectors.put("size", vectorSize); vectors.put("distance", "Cosine");
            JSONObject body = new JSONObject(); body.put("vectors", vectors);
            request("PUT", "/collections/" + collection, body.toJSONString());
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant 连接异常", exception);
        }
    }

    private JSONObject buildMatchCondition(String key, Object value) {
        JSONObject match = new JSONObject();
        match.put("value", value);
        JSONObject condition = new JSONObject();
        condition.put("key", key);
        condition.put("match", match);
        return condition;
    }

    private String toQdrantPointId(String businessPointId) {
        return UUID.nameUUIDFromBytes(businessPointId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String request(String method, String path, String body) {
        try {
            HttpResponse<String> response = send(method, path, body);
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Qdrant 调用失败: " + response.statusCode() + " " + response.body());
            return response.body();
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant 网络调用异常", exception);
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(30));
        if (body != null) builder.header("Content-Type", "application/json");
        return httpClient.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
