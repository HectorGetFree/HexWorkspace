package com.hector.domain.knowledge.adapter.port;

/**
 * 聊天模型端口。领域层只表达受控上下文下的文本生成需求，不依赖 OpenAI 或其他厂商 SDK。
 */
public interface IChatModel {

    String complete(String systemPrompt, String userPrompt);

    String modelName();
}
