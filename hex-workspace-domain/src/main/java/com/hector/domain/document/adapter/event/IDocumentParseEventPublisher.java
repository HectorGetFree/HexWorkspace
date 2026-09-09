package com.hector.domain.document.adapter.event;

import com.hector.domain.document.event.DocumentParseRequestedEvent;

/**
 * 文档解析事件发布端口。
 */
public interface IDocumentParseEventPublisher {

    /**
     * 发布文档版本解析请求。
     *
     * @param event 包含 Workspace、文档和版本标识的解析事件
     */
    void publish(DocumentParseRequestedEvent event);
}
