package com.hector.domain.knowledge.model.valobj;

/** 索引任务来源；自动任务幂等，手动重建使用独立请求键。 */
public enum KnowledgeIndexJobTypeVO {
    AUTOMATIC(0), MANUAL_REBUILD(1);
    private final int code;
    KnowledgeIndexJobTypeVO(int code) { this.code = code; }
    public int getCode() { return code; }
    public static KnowledgeIndexJobTypeVO fromCode(Integer code) {
        for (KnowledgeIndexJobTypeVO value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知知识索引任务类型: " + code);
    }
}
