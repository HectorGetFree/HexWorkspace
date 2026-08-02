package com.hector.infrastructure.chat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.knowledge.adapter.port.IChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** OpenAI Chat Completions HTTP 适配器，模型密钥仅从运行环境读取。 */
@Component
public class OpenAiChatModel implements IChatModel {

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${ai.chat.openai.api-key:}")
    private String apiKey;

    @Value("${ai.chat.openai.base-url:}")
    private String baseUrl;

    @Value("${ai.chat.openai.model:gpt-5.4}")
    private String model;

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 ai.chat.openai.api-key，无法生成知识库回答");
        }
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("temperature", 0.2);
            body.put("messages", buildMessages(systemPrompt, userPrompt));
            HttpRequest request = HttpRequest.newBuilder(URI.create(chatCompletionsEndpoint())).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString())).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("OpenAI 聊天模型调用失败: " + response.statusCode() + " " + response.body());
            }
            return extractContent(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI 聊天模型调用被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("OpenAI 聊天模型网络调用异常", exception);
        }
    }

    @Override
    public String modelName() {
        return model;
    }

    private String chatCompletionsEndpoint() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("未配置 ai.chat.openai.base-url，无法生成知识库回答");
        }
        return baseUrl.replaceAll("/+$", "") + "/chat/completions";
    }

    private JSONArray buildMessages(String systemPrompt, String userPrompt) {
        JSONArray messages = new JSONArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        return messages;
    }

    private JSONObject message(String role, String content) {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String extractContent(String responseBody) {
        JSONArray choices = JSON.parseObject(responseBody).getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("OpenAI 聊天模型未返回候选答案");
        }
        String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("OpenAI 聊天模型返回答案为空");
        }
        return content.trim();
    }
}
