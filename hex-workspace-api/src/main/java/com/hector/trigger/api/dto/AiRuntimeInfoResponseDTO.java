package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 当前 AI 运行时实际装配的模型标识，不包含密钥或服务地址。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRuntimeInfoResponseDTO implements Serializable {

    private String chatModel;
    private String embeddingModel;
}
