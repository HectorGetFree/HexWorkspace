package com.hector.trigger.listener;

import com.hector.domain.document.event.DocumentParseRequestedEvent;
import com.hector.domain.document.service.IDocumentParseService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 文档解析消息监听器。
 * 仅作为 RabbitMQ 外部触发入口，具体解析规则由 document 领域服务处理。
 */
@Component
public class DocumentParseListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseListener.class);

    @Resource
    private IDocumentParseService documentParseService;

    @RabbitListener(queues = "hex.doc.document.parse.queue")
    public void onDocumentParseRequested(DocumentParseRequestedEvent event) {
        log.info("文档解析监听器-接收消息 workspaceId:{} documentId:{} versionNumber:{}",
                event.getWorkspaceId(), event.getDocumentId(), event.getVersionNumber());
        documentParseService.parse(event);
        log.info("文档解析监听器-处理完成 workspaceId:{} documentId:{} versionNumber:{}",
                event.getWorkspaceId(), event.getDocumentId(), event.getVersionNumber());
    }
}
