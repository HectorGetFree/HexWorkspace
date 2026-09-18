package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** 单条 Embedding 返回结果，index 用于恢复供应商可能重排的批量响应顺序。 */
public record EmbeddingVectorVO(int index, List<Float> vector) {
}
