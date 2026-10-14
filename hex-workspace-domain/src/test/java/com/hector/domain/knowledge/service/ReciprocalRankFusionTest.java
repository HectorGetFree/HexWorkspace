package com.hector.domain.knowledge.service;

import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReciprocalRankFusionTest {
    private RetrievalCandidateVO c(String point, String generation, int chunk) {
        return new RetrievalCandidateVO(point, 1L, 2L, 1L, 1L, generation, chunk, List.of(), "text", null, null, .9);
    }
    @Test void sharedCandidatesReceiveBothRanksAndSparseRescuesMiss() {
        var a = c("a", "g", 0); var b = c("b", "g", 1); var s = c("s", "g", 2);
        var result = ReciprocalRankFusion.fuse(List.of(a, b), List.of(b, s), 30);
        assertEquals(List.of("b", "a", "s"), result.stream().map(RetrievalCandidateVO::pointId).toList());
        assertEquals(1D / 62 + 1D / 61, result.get(0).score(), 1e-12);
    }
    @Test void duplicateWithinRouteCountsOnceAndGenerationsStaySeparate() {
        var a = c("a", "g", 0); var other = c("other", "new-g", 0);
        var result = ReciprocalRankFusion.fuse(List.of(a, a), List.of(other), 30);
        assertEquals(2, result.size());
        assertEquals(1D / 61, result.get(0).score(), 1e-12);
        assertEquals(1, ReciprocalRankFusion.fuse(List.of(a), List.of(other), 1).size());
    }
}
