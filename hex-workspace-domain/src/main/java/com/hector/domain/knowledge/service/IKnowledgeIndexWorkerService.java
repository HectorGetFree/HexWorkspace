package com.hector.domain.knowledge.service;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexJobEntity;

import java.util.List;

/** 知识索引异步执行入口，由 Trigger 消费 MQ 消息后调用。 */
public interface IKnowledgeIndexWorkerService {
    /**
     * 执行指定索引任务；重复消息由任务抢占机制幂等处理。
     *
     * @param jobCode 待执行的知识索引任务编码
     */
    void execute(String jobCode);

    /** 回收执行租约已经过期的任务。 */
    void recoverExpiredRunningJobs();

    /**
     * 查询已到下一次重试时间的任务，供 Outbox Dispatcher 重新投递。
     *
     * @return 可重试的待执行任务
     */
    List<KnowledgeIndexJobEntity> listPendingReadyJobs();
}
