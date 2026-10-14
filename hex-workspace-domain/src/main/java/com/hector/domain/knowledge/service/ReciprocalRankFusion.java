package com.hector.domain.knowledge.service;

import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;
import java.util.*;

/** 双路排名融合，不直接混合不同量纲的 Dense / BM25 分数。 */
public final class ReciprocalRankFusion {
    private ReciprocalRankFusion() { }
    private static String key(RetrievalCandidateVO c) {
        return c.workspaceId() + ":" + c.documentId() + ":" + c.generationId() + ":" + c.chunkNo();
    }
    public static List<RetrievalCandidateVO> fuse(List<RetrievalCandidateVO> dense,
            List<RetrievalCandidateVO> sparse, int limit) {
        Map<String, RetrievalCandidateVO> payload = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Integer> denseRank = new HashMap<>(), sparseRank = new HashMap<>();
        for (int route = 0; route < 2; route++) {
            List<RetrievalCandidateVO> values = route == 0 ? dense : sparse;
            Map<String, Integer> ranks = route == 0 ? denseRank : sparseRank;
            for (int i = 0; i < values.size(); i++) {
                var c = values.get(i);
                String key = key(c);
                if (ranks.putIfAbsent(key, i + 1) != null) continue;
                payload.putIfAbsent(key, c);
                scores.merge(key, 1D / (60 + i + 1), Double::sum);
            }
        }
        return payload.keySet().stream().sorted(Comparator
                .<String>comparingDouble(scores::get).reversed()
                .thenComparingInt(k -> denseRank.getOrDefault(k, Integer.MAX_VALUE))
                .thenComparingInt(k -> sparseRank.getOrDefault(k, Integer.MAX_VALUE))
                .thenComparing(Comparator.naturalOrder())).limit(limit).map(k -> {
                    var c = payload.get(k);
                    return new RetrievalCandidateVO(c.pointId(), c.workspaceId(), c.documentId(), c.versionNo(),
                            c.indexInputRevision(), c.generationId(), c.chunkNo(), c.headingPath(), c.content(),
                            c.pageStart(), c.pageEnd(), scores.get(k));
                }).toList();
    }
}
