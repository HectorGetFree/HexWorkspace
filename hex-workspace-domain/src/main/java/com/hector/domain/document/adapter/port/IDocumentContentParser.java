package com.hector.domain.document.adapter.port;

import com.hector.domain.document.model.valobj.DocumentParseResultVO;

/**
 * 文档内容解析端口。
 * 领域层仅声明统一解析语义，不依赖 PDF、Word 等具体解析 SDK。
 */
public interface IDocumentContentParser {

    DocumentParseResultVO parse(String originalFilename, String contentType, byte[] fileContent);
}
