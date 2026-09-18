package com.hector.domain.knowledge.model.valobj;

/** 保留文档顺序与来源信息的不可变内容块。 */
public record ParsedContentBlockVO(
        int blockIndex,
        ParsedContentBlockTypeVO type,
        String text,
        Integer headingLevel,
        Integer pageNo) {
}
