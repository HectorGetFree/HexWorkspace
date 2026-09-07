package com.hector.infrastructure.event;

import com.hector.domain.document.adapter.event.IDocumentParseEventPublisher;
import com.hector.domain.document.event.DocumentParseRequestedEvent;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 文档解析事件发布实现。
 */
@Component
public class DocumentParseEventPublisher implements IDocumentParseEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseEventPublisher.class);

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Override
    public void publish(DocumentParseRequestedEvent event) {
        log.info("文档解析事件发布开始 tenantId:{} documentId:{} versionNumber:{}",
                event.getTenantId(), event.getDocumentId(), event.getVersionNumber());
        rabbitTemplate.convertAndSend("hex.doc.document.exchange", "document.parse.requested", event);
        log.info("文档解析事件发布完成 tenantId:{} documentId:{} versionNumber:{}",
                event.getTenantId(), event.getDocumentId(), event.getVersionNumber());
    }
}
