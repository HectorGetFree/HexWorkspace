package com.hector.domain.agent.model.valobj;

/** Capability 写操作的持久化状态。 */
public enum CapabilityMutationStatusVO {
    PROCESSING(0),
    SUCCEEDED(1),
    FAILED(2),
    PENDING(3);

    private final int code;

    CapabilityMutationStatusVO(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static CapabilityMutationStatusVO fromCode(Integer code) {
        for (CapabilityMutationStatusVO value : values()) {
            if (value.code == code) return value;
        }
        throw new IllegalArgumentException("未知 Capability Mutation 状态: " + code);
    }
}
