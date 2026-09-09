package com.hector.domain.document.model.valobj;

/**
 * 文档内容解析结果。
 *
 * @param text 从文档中提取的纯文本
 * @param parserVersion 产生本次结果的解析器版本
 */
public record DocumentParseResultVO(String text, String parserVersion) {
}
