package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** 文档原始文件的结构化、不可变解析结果。 */
public record ParsedDocumentVO(
        String schemaVersion,
        String parserVersion,
        List<ParsedContentBlockVO> contentBlocks) {
}
