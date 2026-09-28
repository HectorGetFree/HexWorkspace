package com.hector.infrastructure.rag.embedding;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.knowledge.adapter.port.IEmbeddingModel;
import com.hector.domain.knowledge.adapter.port.IEmbeddingClient;
import com.hector.domain.knowledge.adapter.port.IQueryEmbeddingClient;
import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;
import com.hector.domain.knowledge.model.valobj.EmbeddingVectorVO;
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

/** DashScope embedding 适配器，直接调用模型 HTTP 接口而不把供应商 SDK 泄漏进领域层。 */
@Component
public class DashScopeEmbeddingModel implements IEmbeddingModel, IEmbeddingClient, IQueryEmbeddingClient {

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${ai.embedding.dashscope.api-key:}")
    private String apiKey;
    @Value("${ai.embedding.dashscope.model:text-embedding-v4}")
    private String model;
    @Value("${ai.embedding.dashscope.dimensions:1024}")
    private Integer dimensions;
    @Value("${hexworkspace.knowledge.embedding.provider:DashScope}")
    private String provider;
    @Value("${hexworkspace.knowledge.embedding.collection:hex_workspace_rag_v1}")
    private String collectionName;
    @Value("${hexworkspace.knowledge.embedding.timeout-ms:10000}")
    private long timeoutMs;
    @Value("${hexworkspace.knowledge.embedding.retry-count:2}")
    private int retryCount;
    @Value("${hexworkspace.knowledge.embedding.dashscope-endpoint:https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings}")
    private String endpoint;

    @Override
    public List<List<Float>> embed(List<String> texts) {
        return embedWithIndex(texts).stream().map(EmbeddingVectorVO::vector).toList();
    }

    @Override
    public List<EmbeddingVectorVO> embedIndexed(List<String> texts) {
        return embedWithIndex(texts);
    }

    @Override
    public List<Float> embedQuery(EmbeddingConfigEntity servingConfig, String query) {
        if (servingConfig == null || !provider.equals(servingConfig.getProvider())
                || !model.equals(servingConfig.getModel()) || !dimensions.equals(servingConfig.getDimension())
                || !collectionName.equals(servingConfig.getCollectionName())) {
            throw new IllegalStateException("当前部署 Embedding 模型与权威向量空间配置不一致");
        }
        List<EmbeddingVectorVO> vectors = embedWithIndex(List.of(query));
        if (vectors.size() != 1 || vectors.get(0).vector() == null
                || vectors.get(0).vector().size() != servingConfig.getDimension()) {
            throw new IllegalStateException("查询 Embedding 返回向量维度不符合权威配置");
        }
        return vectors.get(0).vector();
    }

    private List<EmbeddingVectorVO> embedWithIndex(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 ai.embedding.dashscope.api-key，无法建立或查询知识索引");
        }
        JSONObject body = new JSONObject();
        body.put("model", model);
        // compatible-mode 遵循 OpenAI embeddings 协议；用一次批量请求代替 DocAI 原先的逐条循环调用。
        body.put("input", texts);
        body.put("dimensions", dimensions);
        IllegalStateException lastFailure = null;
        for (int attempt = 0; attempt <= Math.max(0, retryCount); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("DashScope embedding 调用失败: "
                            + response.statusCode() + " " + response.body());
                }
                JSONArray data = JSON.parseObject(response.body()).getJSONArray("data");
                List<EmbeddingVectorVO> vectors = new ArrayList<>();
                for (int i = 0; i < data.size(); i++) {
                    JSONObject item = data.getJSONObject(i);
                    JSONArray embedding = item.getJSONArray("embedding");
                    List<Float> vector = new ArrayList<>();
                    for (Object value : embedding) vector.add(((Number) value).floatValue());
                    vectors.add(new EmbeddingVectorVO(item.getIntValue("index"), vector));
                }
                return vectors;
            } catch (IOException exception) {
                lastFailure = new IllegalStateException("DashScope embedding 网络调用异常", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DashScope embedding 调用被中断", exception);
            }
        }
        throw lastFailure;
    }

    @Override public String modelName() { return model + "-" + dimensions; }
}
