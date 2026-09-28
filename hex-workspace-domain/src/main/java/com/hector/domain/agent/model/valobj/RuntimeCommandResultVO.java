package com.hector.domain.agent.model.valobj;

/** Hexgent 对 Runtime Command 的协议结果。 */
public record RuntimeCommandResultVO(
        String commandId,
        String runId,
        Status status,
        String reasonCode,
        String message) {

    public enum Status {
        ACCEPTED,
        REJECTED
    }

    public boolean accepted() {
        return status == Status.ACCEPTED;
    }
}
