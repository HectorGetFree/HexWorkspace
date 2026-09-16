package com.hector.domain.knowledge.model.valobj;

/** 知识索引任务生命周期。 */
public enum KnowledgeIndexJobStatusVO {
    PENDING(0), RUNNING(1), SUCCEEDED(2), FAILED(3), SKIPPED(4);
    private final int code;
    KnowledgeIndexJobStatusVO(int code) { this.code = code; }
    public int getCode() { return code; }
    public static KnowledgeIndexJobStatusVO fromCode(Integer code) {
        for (KnowledgeIndexJobStatusVO value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知知识索引任务状态: " + code);
    }
}
