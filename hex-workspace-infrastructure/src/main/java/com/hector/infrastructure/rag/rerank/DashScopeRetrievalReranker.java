package com.hector.infrastructure.rag.rerank;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.knowledge.adapter.port.IRetrievalReranker;
import com.hector.domain.knowledge.model.valobj.RetrievalRerankCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalRerankScoreVO;
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

/** DashScope 文本重排适配器；关闭或未配置密钥时不参与生产检索。 */
@Component
public class DashScopeRetrievalReranker implements IRetrievalReranker {

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${hexworkspace.knowledge.rerank.enabled:false}")
    private boolean enabled;
    @Value("${ai.rerank.dashscope.api-key:}")
    private String apiKey;
    @Value("${ai.rerank.dashscope.model:qwen3-rerank}")
    private String model;
    @Value("${ai.rerank.dashscope.endpoint:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
    private String endpoint;
    @Value("${hexworkspace.knowledge.rerank.timeout-ms:800}")
    private long timeoutMs;
    @Value("${hexworkspace.knowledge.rerank.instruct:Given a web search query, retrieve relevant passages that answer the query.}")
    private String instruct;

    @Override
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<RetrievalRerankScoreVO> rerank(String query, List<RetrievalRerankCandidateVO> candidates) {
        if (!isEnabled()) {
            return List.of();
        }
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("Rerank 请求不能为空");
        }
        JSONObject input = new JSONObject();
        input.put("query", query);
        JSONArray documents = new JSONArray();
        for (RetrievalRerankCandidateVO candidate : candidates) {
            if (candidate == null || candidate.index() == null || candidate.text() == null || candidate.text().isBlank()) {
                throw new IllegalArgumentException("Rerank 候选不完整");
            }
            documents.add(candidate.text());
        }
        input.put("documents", documents);
        JSONObject parameters = new JSONObject();
        parameters.put("top_n", candidates.size());
        parameters.put("return_documents", false);
        parameters.put("instruct", instruct);
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("DashScope Rerank 调用失败: " + response.statusCode() + " " + response.body());
            }
            return parseScores(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("DashScope Rerank 网络调用异常", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DashScope Rerank 调用被中断", exception);
        }
    }

    private List<RetrievalRerankScoreVO> parseScores(String responseBody) {
        JSONObject response = JSON.parseObject(responseBody);
        JSONArray results = response.getJSONArray("results");
        if (results == null && response.getJSONObject("output") != null) {
            results = response.getJSONObject("output").getJSONArray("results");
        }
        if (results == null) {
            throw new IllegalStateException("DashScope Rerank 响应缺少 results");
        }
        List<RetrievalRerankScoreVO> scores = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            JSONObject result = results.getJSONObject(index);
            scores.add(new RetrievalRerankScoreVO(result.getInteger("index"), result.getDouble("relevance_score")));
        }
        return scores;
    }
}
