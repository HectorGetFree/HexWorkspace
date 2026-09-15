package com.hector.domain.document.service;

/** 文档上传异步任务服务。 */
public interface IDocumentUploadTaskService {

    /** 消费 MQ 消息并执行一次幂等的正式对象写入。 */
    void execute(String taskCode);

    /** 使用已在保存事务中取得的执行权，同步完成一次内容版本转正。 */
    void executeOwned(String taskCode, String executionToken);

    /** 将到达重试时间的失败任务重新登记为待投递事件。 */
    void enqueueRetryTasks();

    /** 回收执行器崩溃后超过租约的 RUNNING 任务。 */
    void recoverExpiredRunningTasks();
}
