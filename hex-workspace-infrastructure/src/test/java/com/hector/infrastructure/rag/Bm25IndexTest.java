package com.hector.infrastructure.rag;

import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Bm25IndexTest {
    private RetrievalCandidateVO candidate(String id, String content) {
        return new RetrievalCandidateVO(id, 1L, 2L, 1L, 1L, "g", 0, List.of(), content, null, null, 0D);
    }
    @Test void ranksMatchingContentAndKeepsPayload() {
        var index = new Bm25Index(List.of(candidate("a", "maple audit protocol"), candidate("b", "unrelated notes")));
        assertEquals("a", index.search("maple protocol", 30).get(0).pointId());
        assertEquals("maple audit protocol", index.search("maple", 1).get(0).content());
        assertEquals(1, index.search("maple", 30).size());
    }
    @Test void emptyChineseAndStopWordsReturnNoCandidates() {
        var index = new Bm25Index(List.of(candidate("a", "maple audit")));
        assertTrue(index.search("中文查询", 30).isEmpty());
        assertTrue(index.search("the and", 30).isEmpty());
        assertTrue(new Bm25Index(List.of()).search("maple", 1).isEmpty());
    }
    @Test void bm25FormulaMatchesFrozenAlgorithm() {
        var index = new Bm25Index(List.of(candidate("a", "maple maple"), candidate("b", "other other")));
        assertEquals(Math.log(1 + 1.5 / 1.5) * 2 * 2.2 / 3.2, index.search("maple", 1).get(0).score(), 1e-12);
        assertEquals(index.search("maple", 1).get(0).score() * 2,
                index.search("maple maple", 1).get(0).score(), 1e-12);
    }
}
