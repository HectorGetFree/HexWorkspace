package com.hector.domain.knowledge.model.valobj;

/** 知识索引 Outbox 事件生命周期。 */
public enum KnowledgeIndexOutboxStatusVO {
    PENDING(0),
    SENT(1),
    PUBLISHING(2);

    private final int code;

    KnowledgeIndexOutboxStatusVO(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static KnowledgeIndexOutboxStatusVO fromCode(Integer code) {
        for (KnowledgeIndexOutboxStatusVO value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知知识索引 Outbox 状态: " + code);
    }
}
