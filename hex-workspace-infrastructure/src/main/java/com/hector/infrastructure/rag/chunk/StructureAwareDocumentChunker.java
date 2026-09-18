package com.hector.infrastructure.rag.chunk;

import com.hector.domain.knowledge.adapter.port.IParsedDocumentChunker;
import com.hector.domain.knowledge.model.valobj.KnowledgeChunkVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockTypeVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** 按标题语义分段，并在过长段落内按字符窗口二次切分。 */
@Component
public class StructureAwareDocumentChunker implements IParsedDocumentChunker {

    @Value("${hexworkspace.knowledge.chunk.target-size:1000}")
    private int targetSize;

    @Value("${hexworkspace.knowledge.chunk.max-size:1400}")
    private int maxSize;

    @Value("${hexworkspace.knowledge.chunk.overlap-size:160}")
    private int overlapSize;

    @Override
    public List<KnowledgeChunkVO> chunk(String generationId, Long documentId, Long versionNo, ParsedDocumentVO parsedDocument) {
        List<KnowledgeChunkVO> chunks = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        List<ParsedContentBlockVO> section = new ArrayList<>();
        for (ParsedContentBlockVO block : parsedDocument.contentBlocks()) {
            if (ParsedContentBlockTypeVO.HEADING == block.type()) {
                flushSection(chunks, generationId, documentId, versionNo, headingPath, section);
                updateHeadingPath(headingPath, block.headingLevel(), block.text());
            } else {
                section.add(block);
            }
        }
        flushSection(chunks, generationId, documentId, versionNo, headingPath, section);
        return List.copyOf(chunks);
    }

    private void flushSection(List<KnowledgeChunkVO> chunks, String generationId, Long documentId, Long versionNo,
                              List<String> headingPath, List<ParsedContentBlockVO> section) {
        if (section.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        int startBlock = section.get(0).blockIndex();
        int endBlock = startBlock;
        Integer pageStart = null;
        Integer pageEnd = null;
        for (ParsedContentBlockVO block : section) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(block.text());
            endBlock = block.blockIndex();
            if (block.pageNo() != null) {
                pageStart = pageStart == null ? block.pageNo() : Math.min(pageStart, block.pageNo());
                pageEnd = pageEnd == null ? block.pageNo() : Math.max(pageEnd, block.pageNo());
            }
        }
        for (String part : split(text.toString())) {
            chunks.add(new KnowledgeChunkVO(generationId, documentId, versionNo, chunks.size(), part,
                    List.copyOf(headingPath), startBlock, endBlock, pageStart, pageEnd, checksum(part)));
        }
        section.clear();
    }

    private List<String> split(String source) {
        String text = source.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        int boundedMaxSize = Math.max(1, maxSize);
        int boundedTargetSize = Math.min(Math.max(1, targetSize), boundedMaxSize);
        int boundedOverlap = Math.min(Math.max(0, overlapSize), boundedTargetSize - 1);
        List<String> parts = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int limit = Math.min(text.length(), offset + boundedMaxSize);
            int preferred = Math.min(text.length(), offset + boundedTargetSize);
            int end = findNaturalBoundary(text, offset, preferred, limit);
            if (end <= offset) {
                end = limit;
            }
            parts.add(text.substring(offset, end).strip());
            if (end >= text.length()) {
                break;
            }
            offset = Math.max(offset + 1, end - boundedOverlap);
        }
        return parts;
    }

    private int findNaturalBoundary(String text, int start, int preferred, int limit) {
        for (int index = preferred; index > start; index--) {
            char character = text.charAt(index - 1);
            if (character == '\n' || character == '。' || character == '！' || character == '？' || character == '.' || character == '!' || character == '?') {
                return index;
            }
        }
        return limit;
    }

    private void updateHeadingPath(List<String> headingPath, Integer level, String text) {
        int headingLevel = level == null ? 1 : Math.max(1, level);
        while (headingPath.size() >= headingLevel) {
            headingPath.remove(headingPath.size() - 1);
        }
        while (headingPath.size() < headingLevel - 1) {
            headingPath.add("");
        }
        headingPath.add(text);
    }

    private String checksum(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Chunk 摘要计算失败", exception);
        }
    }
}
