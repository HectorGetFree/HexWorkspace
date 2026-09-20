package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** 经权限、生命周期和索引权威状态复核后可交付给内部 AI 的检索结果。 */
public record RetrievalResultVO(List<RetrievalHitVO> hits) {

    public static RetrievalResultVO empty() {
        return new RetrievalResultVO(List.of());
    }
}
