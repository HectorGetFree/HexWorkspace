package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 单轮知识库问答请求。MVP 不引入会话 ID 与历史消息。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeAskRequestDTO implements Serializable {

    private String question;
}
