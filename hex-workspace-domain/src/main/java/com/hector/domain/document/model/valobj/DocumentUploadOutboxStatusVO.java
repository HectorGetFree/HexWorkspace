package com.hector.domain.document.model.valobj;

/** 上传任务 Outbox 事件投递状态。 */
public enum DocumentUploadOutboxStatusVO {
    /** 尚未成功投递到 MQ。 */
    PENDING(0),
    /** MQ 已确认接收。 */
    SENT(1);

    private final int code;
    DocumentUploadOutboxStatusVO(int code) { this.code = code; }
    public int getCode() { return code; }
    public static DocumentUploadOutboxStatusVO fromCode(Integer code) {
        for (DocumentUploadOutboxStatusVO value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知文档上传Outbox状态码: " + code);
    }
}
