package com.hector.domain.knowledge.model.valobj;

import java.util.List;
import java.util.Map;

/** 归属于单一 Generation 的 Qdrant 写入点。 */
public record GenerationVectorPointVO(String pointId, List<Float> vector, Map<String, Object> payload) {
}
