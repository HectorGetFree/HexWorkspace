package com.hector.trigger.http;

import com.hector.domain.knowledge.adapter.port.IChatModel;
import com.hector.domain.knowledge.adapter.port.IEmbeddingModel;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.trigger.api.dto.AiPingResponseDTO;
import com.hector.trigger.api.dto.AiRuntimeInfoResponseDTO;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 模型连通性接口，不承担知识检索或通用对话能力。 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);
    private static final String PING_SYSTEM_PROMPT = "你是连通性检测服务。只能回复 pong。";
    private static final String PING_USER_PROMPT = "ping";

    @Resource
    private IChatModel chatModel;

    @Resource
    private IEmbeddingModel embeddingModel;

    /**
     * 返回当前运行时实际装配的模型名称，不返回密钥、Base URL 或其他敏感配置。
     */
    @GetMapping("/runtime-info")
    public Response<AiRuntimeInfoResponseDTO> runtimeInfo() {
        return success(AiRuntimeInfoResponseDTO.builder()
                .chatModel(chatModel.modelName())
                .embeddingModel(embeddingModel.modelName())
                .build());
    }

    /**
     * 使用固定 ping/pong 请求确认 Chat Model 的 endpoint、api-key 与模型配置有效。
     * 该接口继承全局 JWT 认证要求，不接受调用方自定义提示词。
     */
    @GetMapping("/ping")
    public Response<AiPingResponseDTO> ping() {
        long startedAt = System.nanoTime();
        try {
            Long userId = SecurityUserUtil.getCurrentUserId();
            String reply = chatModel.complete(PING_SYSTEM_PROMPT, PING_USER_PROMPT);
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("AI 连通性检测成功 userId:{} model:{} latencyMs:{}", userId, chatModel.modelName(), latencyMs);
            return success(AiPingResponseDTO.builder()
                    .model(chatModel.modelName())
                    .reply(reply)
                    .latencyMs(latencyMs)
                    .build());
        } catch (Exception exception) {
            log.error("AI 连通性检测失败 model:{} error:{}", chatModel.modelName(), exception.getMessage(), exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }
}
