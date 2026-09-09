package com.hector.domain.document.adapter.port;

import com.hector.domain.document.model.valobj.DocumentParseResultVO;

/**
 * 文档内容解析端口。
 * 领域层仅声明统一解析语义，不依赖 PDF、Word 等具体解析 SDK。
 */
public interface IDocumentContentParser {

    /**
     * 将指定格式的文档内容解析为可索引文本。
     *
     * @param originalName 文档导入时的原始名称，用于识别后缀
     * @param contentType 文档 MIME 类型
     * @param contentBytes 文档内容字节
     * @return 解析文本及解析器版本
     */
    DocumentParseResultVO parse(String originalName, String contentType, byte[] contentBytes);
}
