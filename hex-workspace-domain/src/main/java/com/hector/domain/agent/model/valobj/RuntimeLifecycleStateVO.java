package com.hector.domain.agent.model.valobj;

/** Hexgent 基于 durable Transcript 计算出的运行状态。 */
public record RuntimeLifecycleStateVO(String runId, String conversationId, String state,
                                      boolean continuable, Long lastSequence) {
}
