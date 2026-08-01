package com.hector.infrastructure.vector;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.knowledge.adapter.port.IVectorStore;
import com.hector.domain.knowledge.model.valobj.KnowledgeSearchHitVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Qdrant REST 适配器。
 * Qdrant 仅保存可再生的向量索引；租户、文档版本与解析产物仍由 MySQL/MinIO 管理。
 */
@Component
public class QdrantVectorStore implements IVectorStore {

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${ai.vector-store.qdrant.endpoint:http://127.0.0.1:6333}")
    private String endpoint;
    @Value("${ai.vector-store.qdrant.collection:hex_doc_segments}")
    private String collection;

    @Override
    public void replaceDocumentVersion(Long tenantId, Long documentId, Integer versionNumber, List<String> pointIds,
                                       List<List<Float>> vectors, List<String> headings, List<String> contents) {
        if (vectors.isEmpty()) return;
        ensureCollection(vectors.get(0).size());
        JSONObject must = new JSONObject();
        must.put("key", "document_id"); must.put("match", JSON.parseObject("{\"value\":" + documentId + "}"));
        JSONObject filter = new JSONObject(); filter.put("must", List.of(must));
        request("POST", "/collections/" + collection + "/points/delete?wait=true", JSON.toJSONString(new JSONObject() {{ put("filter", filter); }}));

        JSONArray points = new JSONArray();
        for (int index = 0; index < pointIds.size(); index++) {
            JSONObject payload = new JSONObject();
            payload.put("tenant_id", tenantId); payload.put("document_id", documentId); payload.put("version_number", versionNumber);
            payload.put("chunk_no", index + 1); payload.put("heading", headings.get(index)); payload.put("content", contents.get(index));
            JSONObject point = new JSONObject(); point.put("id", pointIds.get(index)); point.put("vector", vectors.get(index)); point.put("payload", payload);
            points.add(point);
        }
        JSONObject body = new JSONObject(); body.put("points", points);
        request("PUT", "/collections/" + collection + "/points?wait=true", body.toJSONString());
    }

    @Override
    public List<KnowledgeSearchHitVO> search(Long tenantId, List<Float> queryVector, int topK) {
        JSONObject match = new JSONObject(); match.put("value", tenantId);
        JSONObject condition = new JSONObject(); condition.put("key", "tenant_id"); condition.put("match", match);
        JSONObject filter = new JSONObject(); filter.put("must", List.of(condition));
        JSONObject body = new JSONObject(); body.put("vector", queryVector); body.put("limit", topK); body.put("with_payload", true); body.put("filter", filter);
        JSONObject result = JSON.parseObject(request("POST", "/collections/" + collection + "/points/search", body.toJSONString()));
        JSONArray points = result.getJSONArray("result");
        List<KnowledgeSearchHitVO> hits = new ArrayList<>();
        for (Object item : points) {
            JSONObject point = (JSONObject) item;
            JSONObject payload = point.getJSONObject("payload");
            hits.add(new KnowledgeSearchHitVO(payload.getLong("document_id"), payload.getInteger("version_number"),
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
