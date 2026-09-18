package com.hector.trigger.mq;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexOutboxEventEntity;
import com.hector.domain.knowledge.repository.IKnowledgeIndexOutboxEventRepository;
import com.hector.domain.knowledge.service.IKnowledgeIndexWorkerService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 可靠投递知识索引任务；发布权通过 Outbox 条件更新在多实例间抢占。 */
@Component
public class KnowledgeIndexTaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexTaskDispatcher.class);
    private static final int BATCH_SIZE = 100;

    @Value("${hexworkspace.mq.knowledge-index.exchange}")
    private String exchange;

    @Value("${hexworkspace.mq.knowledge-index.routing-key}")
    private String routingKey;

    @Value("${hexworkspace.mq.knowledge-index.outbox-publish-lease-seconds}")
    private int publishLeaseSeconds;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private IKnowledgeIndexOutboxEventRepository outboxRepository;

    @Resource
    private IKnowledgeIndexWorkerService workerService;

    @Scheduled(fixedDelayString = "${hexworkspace.knowledge.dispatcher.fixed-delay-ms:5000}")
    public void dispatch() {
        // 1. 回收中断发布和中断执行，确保消息或任务不会永久卡住。
        LocalDateTime now = LocalDateTime.now();
        for (KnowledgeIndexOutboxEventEntity event : outboxRepository.listExpiredPublishing(now, BATCH_SIZE)) {
            outboxRepository.recoverExpiredPublishing(event.getEventCode(), now, "知识索引 Outbox 发布租约超时");
        }
        workerService.recoverExpiredRunningJobs();
        for (var job : workerService.listPendingReadyJobs()) {
            outboxRepository.resetSentForRetry(job.getJobCode());
        }

        // 2. 查询不是抢占；每一条必须 CAS 获得发布令牌后才允许发 MQ。
        for (KnowledgeIndexOutboxEventEntity event : outboxRepository.listPending(BATCH_SIZE)) {
            publish(event);
        }
    }

    private void publish(KnowledgeIndexOutboxEventEntity event) {
        String token = UUID.randomUUID().toString();
        if (!outboxRepository.claimForPublishing(
                event.getEventCode(), token, LocalDateTime.now().plusSeconds(publishLeaseSeconds))) {
            return;
        }
        try {
            CorrelationData correlationData = new CorrelationData(event.getEventCode());
            rabbitTemplate.convertAndSend(exchange, routingKey, event.getJobCode(), correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ 未确认知识索引消息投递");
            }
            outboxRepository.markSentIfOwned(event.getEventCode(), token);
        } catch (Exception exception) {
            outboxRepository.releaseIfOwned(event.getEventCode(), token, exception.getMessage());
            log.error("知识索引 Outbox-投递失败 eventCode:{} jobCode:{}", event.getEventCode(), event.getJobCode(), exception);
        }
    }
}
