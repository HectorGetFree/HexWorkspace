package com.hector.domain.document.service;

import com.hector.domain.document.event.DocumentParseRequestedEvent;

/**
 * 文档版本解析领域服务。
 */
public interface IDocumentParseService {

    void parse(DocumentParseRequestedEvent event);
}
