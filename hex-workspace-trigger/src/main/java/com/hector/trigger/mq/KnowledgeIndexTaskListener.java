package com.hector.trigger.mq;

import com.hector.domain.knowledge.service.IKnowledgeIndexWorkerService;
import jakarta.annotation.Resource;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 知识索引任务消费者；重复投递由 Job 抢占与 Generation 隔离处理。 */
@Component
public class KnowledgeIndexTaskListener {
    @Resource
    private IKnowledgeIndexWorkerService workerService;

    @RabbitListener(queues = "${hexworkspace.mq.knowledge-index.queue}")
    public void consume(String jobCode) {
        workerService.execute(jobCode);
    }
}
