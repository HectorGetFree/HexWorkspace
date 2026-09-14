package com.hector.trigger.mq;

import com.hector.domain.document.model.entity.DocumentUploadOutboxEventEntity;
import com.hector.domain.document.repository.IDocumentUploadOutboxEventRepository;
import com.hector.domain.document.service.IDocumentUploadTaskService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 定时发布上传 Outbox 事件，并重新登记失败上传任务。 */
@Component
public class DocumentUploadTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadTaskDispatcher.class);
    private static final int BATCH_SIZE = 100;

    @Value("${hexworkspace.mq.document-upload.exchange}")
    private String documentUploadExchange;

    @Value("${hexworkspace.mq.document-upload.routing-key}")
    private String documentUploadRoutingKey;

    @Value("${hexworkspace.mq.document-upload.outbox-publish-lease-seconds}")
    private int outboxPublishLeaseSeconds;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private IDocumentUploadOutboxEventRepository outboxRepository;

    @Resource
    private IDocumentUploadTaskService uploadTaskService;

    @Scheduled(fixedDelay = 5000)
    public void dispatch() {
        // 1. 先回收发布器异常退出遗留的发布权，避免事件永久停留在 PUBLISHING。
        recoverExpiredPublishingEvents();

        // 2. 回收任务执行租约，并为到期失败任务重新登记 Outbox 事件。
        uploadTaskService.recoverExpiredRunningTasks();
        uploadTaskService.enqueueRetryTasks();

        // 3. 查询待投递事件；查询不代表获得发布权，必须由条件更新完成抢占。
        for (DocumentUploadOutboxEventEntity event : outboxRepository.listPending(BATCH_SIZE)) {
            publish(event);
        }
    }

    private void recoverExpiredPublishingEvents() {
        for (DocumentUploadOutboxEventEntity event : outboxRepository.listExpiredPublishing(
                LocalDateTime.now(), BATCH_SIZE)) {
            LocalDateTime now = LocalDateTime.now();
            outboxRepository.recoverExpiredPublishing(
                    event.getEventCode(), now, "文档上传 Outbox 发布租约超时");
        }
    }

    private void publish(DocumentUploadOutboxEventEntity event) {
        // 1. 多实例并发扫描时，只有条件更新成功的实例可获得本次发布权。
        String publishingToken = UUID.randomUUID().toString();
        LocalDateTime publishingLeaseUntil = LocalDateTime.now().plusSeconds(outboxPublishLeaseSeconds);
        if (!outboxRepository.claimForPublishing(
                event.getEventCode(), publishingToken, publishingLeaseUntil)) {
            return;
        }

        try {
            // 2. 等待 RabbitMQ broker 确认；仅确认成功才把事件标记为已投递。
            CorrelationData correlationData = new CorrelationData(event.getEventCode());
            rabbitTemplate.convertAndSend(
                    documentUploadExchange,
                    documentUploadRoutingKey,
                    event.getTaskCode(),
                    correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ 未确认消息投递");
            }
            if (!outboxRepository.markSentIfOwned(event.getEventCode(), publishingToken)) {
                log.warn("文档上传Outbox-确认成功但发布权已失效 eventCode:{} taskCode:{}",
                        event.getEventCode(), event.getTaskCode());
            }
        } catch (Exception exception) {
            // 3. 仅当前发布者可释放发布权；过期发布者不能覆盖新发布者的状态。
            if (!outboxRepository.releaseIfOwned(
                    event.getEventCode(), publishingToken, exception.getMessage())) {
                log.warn("文档上传Outbox-投递失败但发布权已失效 eventCode:{} taskCode:{}",
                        event.getEventCode(), event.getTaskCode());
                return;
            }
            log.error("文档上传Outbox-投递失败 eventCode:{} taskCode:{}",
                    event.getEventCode(), event.getTaskCode(), exception);
        }
    }
}
