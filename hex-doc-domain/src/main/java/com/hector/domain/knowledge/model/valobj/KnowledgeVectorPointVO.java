package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/**
 * 一条待写入向量库的知识索引点。
 * 将分段标识、文本元数据与向量绑定，避免 Port 接口传递多组并行 List 而产生下标错位。
 */
public record KnowledgeVectorPointVO(String businessPointId, Integer chunkNo, String heading,
                                     String content, List<Float> vector) {
}
