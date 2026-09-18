package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.valobj.DocumentFormatVO;
import com.hector.domain.knowledge.model.valobj.LoadedDocumentVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;

/** 单一文档格式的结构化解析端口。 */
public interface IDocumentParser {

    /** 判断当前解析器是否支持指定格式。 */
    boolean supports(DocumentFormatVO format);

    /** 将原始文件解析为保留内容顺序的结构化产物。 */
    ParsedDocumentVO parse(LoadedDocumentVO document);
}
