package com.hector.domain.agent.model.valobj;

/** WS 发往 Hexgent 的内部 Runtime Command；不暴露 Hexgent DTO。 */
public record RuntimeCommandRequestVO(
        String commandId,
        AgentRunCommandTypeVO commandType,
        String runId,
        String conversationId,
        String issuedAt,
        String payloadJson) {
}
