package com.hector.infrastructure.rag.chunk;

import com.hector.domain.knowledge.model.valobj.KnowledgeChunkVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockTypeVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证结构感知分段的正文、来源与边界语义。 */
class StructureAwareDocumentChunkerTest {

    @Test
    void shouldKeepHeadingPathSectionBoundarySourceBlockAndPageRange() {
        StructureAwareDocumentChunker chunker = chunker(100, 120, 10);
        ParsedDocumentVO document = new ParsedDocumentVO("v1", "v1", List.of(
                block(0, ParsedContentBlockTypeVO.HEADING, "第一章", 1, null),
                block(1, ParsedContentBlockTypeVO.PARAGRAPH, "第一章正文", null, 1),
                block(2, ParsedContentBlockTypeVO.HEADING, "第二章", 1, null),
                block(3, ParsedContentBlockTypeVO.PARAGRAPH, "第二章正文", null, 2),
                block(4, ParsedContentBlockTypeVO.TABLE, "字段 | 值", null, 3)));

        List<KnowledgeChunkVO> chunks = chunker.chunk("generation-1", 10L, 2L, document);

        assertEquals(2, chunks.size());
        assertEquals("第一章正文", chunks.get(0).text());
        assertEquals(List.of("第一章"), chunks.get(0).headingPath());
        assertEquals(1, chunks.get(0).sourceBlockStart());
        assertEquals(1, chunks.get(0).sourceBlockEnd());
        assertEquals(1, chunks.get(0).pageStart());
        assertEquals(1, chunks.get(0).pageEnd());
        assertEquals("第二章正文\n\n字段 | 值", chunks.get(1).text());
        assertEquals(List.of("第二章"), chunks.get(1).headingPath());
        assertEquals(3, chunks.get(1).sourceBlockStart());
        assertEquals(4, chunks.get(1).sourceBlockEnd());
        assertEquals(2, chunks.get(1).pageStart());
        assertEquals(3, chunks.get(1).pageEnd());
        assertFalse(chunks.get(0).text().contains("第二章正文"));
    }

    @Test
    void shouldSplitLongTextWithOverlapWithoutDroppingBodyOrDeduplicatingRepeatedText() {
        StructureAwareDocumentChunker chunker = chunker(10, 12, 3);
        String longText = "abcdefghi\njklmnopqr\nstuvwxyz";
        ParsedDocumentVO document = new ParsedDocumentVO("v1", "v1", List.of(
                block(0, ParsedContentBlockTypeVO.HEADING, "章节", 1, null),
                block(1, ParsedContentBlockTypeVO.PARAGRAPH, longText, null, null),
                block(2, ParsedContentBlockTypeVO.PARAGRAPH, "重复文本", null, null),
                block(3, ParsedContentBlockTypeVO.PARAGRAPH, "重复文本", null, null)));

        List<KnowledgeChunkVO> chunks = chunker.chunk("generation-2", 10L, 2L, document);

        assertTrue(chunks.size() >= 4);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.text().length() <= 12));
        assertTrue(hasOverlap(chunks.get(0).text(), chunks.get(1).text()));
        String merged = String.join("", chunks.stream().map(KnowledgeChunkVO::text).toList());
        assertTrue(merged.contains("abcdefghi"));
        assertTrue(merged.contains("jklmnopqr"));
        assertTrue(merged.contains("stuvwxyz"));
        assertEquals(2, chunks.stream().filter(chunk -> chunk.text().contains("重复文本")).count());
        assertEquals(1, chunks.get(0).sourceBlockStart());
        assertEquals(3, chunks.get(chunks.size() - 1).sourceBlockEnd());
    }

    private StructureAwareDocumentChunker chunker(int targetSize, int maxSize, int overlapSize) {
        StructureAwareDocumentChunker chunker = new StructureAwareDocumentChunker();
        ReflectionTestUtils.setField(chunker, "targetSize", targetSize);
        ReflectionTestUtils.setField(chunker, "maxSize", maxSize);
        ReflectionTestUtils.setField(chunker, "overlapSize", overlapSize);
        return chunker;
    }

    private ParsedContentBlockVO block(int index, ParsedContentBlockTypeVO type, String text, Integer level, Integer pageNo) {
        return new ParsedContentBlockVO(index, type, text, level, pageNo);
    }

    private boolean hasOverlap(String left, String right) {
        for (int size = Math.min(left.length(), right.length()); size > 0; size--) {
            if (left.endsWith(right.substring(0, size))) {
                return true;
            }
        }
        return false;
    }
}
