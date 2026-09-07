package com.hector.infrastructure.embedding;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.knowledge.adapter.port.IEmbeddingModel;
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
public class DashScopeEmbeddingModel implements IEmbeddingModel {

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${ai.embedding.dashscope.api-key:}")
    private String apiKey;
    @Value("${ai.embedding.dashscope.model:text-embedding-v4}")
    private String model;
    @Value("${ai.embedding.dashscope.dimensions:1024}")
    private Integer dimensions;

    @Override
    public List<List<Float>> embed(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 ai.embedding.dashscope.api-key，无法建立或查询知识索引");
        }
        JSONObject body = new JSONObject();
        body.put("model", model);
        // compatible-mode 遵循 OpenAI embeddings 协议；用一次批量请求代替 DocAI 原先的逐条循环调用。
        body.put("input", texts);
        body.put("dimensions", dimensions);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"))
                    .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toJSONString())).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("DashScope embedding 调用失败: " + response.statusCode() + " " + response.body());
            JSONArray data = JSON.parseObject(response.body()).getJSONArray("data");
            List<List<Float>> vectors = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                JSONArray embedding = data.getJSONObject(i).getJSONArray("embedding");
                List<Float> vector = new ArrayList<>();
                for (Object value : embedding) vector.add(((Number) value).floatValue());
                vectors.add(vector);
            }
            return vectors;
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DashScope embedding 网络调用异常", exception);
        }
    }

    @Override public String modelName() { return model + "-" + dimensions; }
}
