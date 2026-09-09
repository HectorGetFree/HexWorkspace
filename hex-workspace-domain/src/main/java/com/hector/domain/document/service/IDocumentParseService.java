package com.hector.domain.document.service;

import com.hector.domain.document.event.DocumentParseRequestedEvent;

/**
 * 文档版本解析领域服务。
 */
public interface IDocumentParseService {

    /**
     * 解析事件指定的文档版本，并保存解析状态和派生内容。
     *
     * @param event 文档版本解析请求
     */
    void parse(DocumentParseRequestedEvent event);
}
