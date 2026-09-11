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

import java.util.concurrent.TimeUnit;

/** 定时发布上传 Outbox 事件，并重新登记失败上传任务。 */
@Component
public class DocumentUploadTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadTaskDispatcher.class);

    @Value("${hexworkspace.mq.document-upload.exchange}")
    private String documentUploadExchange;

    @Value("${hexworkspace.mq.document-upload.routing-key}")
    private String documentUploadRoutingKey;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private IDocumentUploadOutboxEventRepository outboxRepository;

    @Resource
    private IDocumentUploadTaskService uploadTaskService;

    @Scheduled(fixedDelay = 5000)
    public void dispatch() {
        // 1. 先把到期失败任务转为新的 Outbox 事件，再统一发布所有待投递事件。
        uploadTaskService.enqueueRetryTasks();
        for (DocumentUploadOutboxEventEntity event : outboxRepository.listPending(100)) {
            try {
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
                event.markSent();
                outboxRepository.update(event);
            } catch (Exception exception) {
                log.error("文档上传Outbox-投递失败 eventCode:{} taskCode:{}", event.getEventCode(), event.getTaskCode(), exception);
                event.markPending(exception.getMessage());
                outboxRepository.update(event);
            }
        }
    }
}
