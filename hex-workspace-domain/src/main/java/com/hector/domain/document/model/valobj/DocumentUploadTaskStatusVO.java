package com.hector.domain.document.model.valobj;

/** 文档上传任务生命周期状态。 */
public enum DocumentUploadTaskStatusVO {
    /** 已登记，等待 Outbox 投递 MQ。 */
    PENDING(0),
    /** 已被消费者抢占，正在写入正式对象。 */
    RUNNING(1),
    /** 正式对象写入和文档激活均已完成。 */
    SUCCEEDED(2),
    /** 本次执行失败，等待下一次重试投递。 */
    RETRY_WAIT(3);

    private final int code;
    DocumentUploadTaskStatusVO(int code) { this.code = code; }
    public int getCode() { return code; }
    public static DocumentUploadTaskStatusVO fromCode(Integer code) {
        for (DocumentUploadTaskStatusVO value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知文档上传任务状态码: " + code);
    }
}
