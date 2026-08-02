package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 已配置聊天模型的最小连通性检测响应。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPingResponseDTO implements Serializable {

    private String model;
    private String reply;
    private Long latencyMs;
}
