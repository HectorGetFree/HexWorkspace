package com.hector.domain.document.adapter.event;

import com.hector.domain.document.event.DocumentParseRequestedEvent;

/**
 * 文档解析事件发布端口。
 */
public interface IDocumentParseEventPublisher {

    void publish(DocumentParseRequestedEvent event);
}
