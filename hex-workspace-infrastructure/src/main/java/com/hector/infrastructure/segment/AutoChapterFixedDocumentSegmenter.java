package com.hector.infrastructure.segment;

import com.hector.domain.knowledge.adapter.port.IDocumentSegmenter;
import com.hector.domain.knowledge.model.valobj.KnowledgeSegmentVO;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DocAI 分段策略的 MVP 复刻实现。
 * 章节标题优先保留语义边界，无法识别标题时退化为 1000 字符、100 重叠的固定窗口；不引入逐句语义切分，避免索引阶段的 N 次模型调用。
 */
@Component
public class AutoChapterFixedDocumentSegmenter implements IDocumentSegmenter {

    private static final int CHUNK_SIZE = 1000;
    private static final int OVERLAP = 100;
    private static final int MIN_SEGMENT_SIZE = 100;
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "(?im)^(?:#{1,6}\\s+.+|第[一二三四五六七八九十百千万零0-9]+[章节篇部].*|(?:\\d+\\.)+\\d*\\s+.+|(?:chapter|section)\\s+\\d+.*|\\[.+])$");

    @Override
    public List<KnowledgeSegmentVO> segment(String content) {
        String normalized = normalize(content);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<RawSection> sections = splitChapters(normalized);
        List<RawSegment> rawSegments = new ArrayList<>();
        for (RawSection section : sections) {
            rawSegments.addAll(splitFixed(section.heading(), section.content()));
        }
        return toDeduplicatedSegments(mergeShortTail(rawSegments));
    }

    @Override
    public String strategyVersion() {
        return "auto-chapter-fixed-v1(1000/100)";
    }

    private List<RawSection> splitChapters(String content) {
        Matcher matcher = HEADING_PATTERN.matcher(content);
        List<Heading> headings = new ArrayList<>();
        while (matcher.find()) {
            headings.add(new Heading(matcher.start(), matcher.group().trim()));
        }
        if (headings.isEmpty()) {
            return List.of(new RawSection(null, content));
        }
        List<RawSection> sections = new ArrayList<>();
        if (headings.get(0).position() > 0) {
            sections.add(new RawSection(null, content.substring(0, headings.get(0).position()).trim()));
        }
        for (int i = 0; i < headings.size(); i++) {
            int bodyStart = headings.get(i).position() + headings.get(i).text().length();
            int bodyEnd = i + 1 < headings.size() ? headings.get(i + 1).position() : content.length();
            sections.add(new RawSection(headings.get(i).text(), content.substring(bodyStart, bodyEnd).trim()));
        }
        return sections;
    }

    private List<RawSegment> splitFixed(String heading, String content) {
        List<RawSegment> result = new ArrayList<>();
        if (content.isBlank()) {
            return result;
        }
        for (int start = 0; start < content.length(); ) {
            int end = Math.min(start + CHUNK_SIZE, content.length());
            if (end < content.length()) {
                end = findNaturalBoundary(content, start, end);
            }
            result.add(new RawSegment(heading, content.substring(start, end).trim()));
            if (end >= content.length()) {
                break;
            }
            start = Math.max(end - OVERLAP, start + 1);
        }
        return result;
    }

    private int findNaturalBoundary(String content, int start, int expectedEnd) {
        for (int index = expectedEnd - 1; index > start + CHUNK_SIZE / 2; index--) {
            char c = content.charAt(index);
            if ("。！？；：、，.!?;:\\n".indexOf(c) >= 0 || Character.isWhitespace(c)) {
                return index + 1;
            }
        }
        return expectedEnd;
    }

    private List<RawSegment> mergeShortTail(List<RawSegment> segments) {
        List<RawSegment> result = new ArrayList<>();
        for (RawSegment segment : segments) {
            if (segment.content().isBlank()) {
                continue;
            }
            if (segment.content().length() < MIN_SEGMENT_SIZE && !result.isEmpty()) {
                RawSegment previous = result.remove(result.size() - 1);
                String heading = previous.heading() == null ? segment.heading() : previous.heading();
                result.add(new RawSegment(heading, previous.content() + "\n" + segment.content()));
            } else {
                result.add(segment);
            }
        }
        return result;
    }

    private List<KnowledgeSegmentVO> toDeduplicatedSegments(List<RawSegment> segments) {
        Map<String, RawSegment> distinct = new LinkedHashMap<>();
        for (RawSegment segment : segments) {
            distinct.putIfAbsent(hash(segment.content()), segment);
        }
        List<KnowledgeSegmentVO> result = new ArrayList<>();
        int chunkNo = 1;
        for (Map.Entry<String, RawSegment> entry : distinct.entrySet()) {
            result.add(new KnowledgeSegmentVO(chunkNo++, entry.getValue().heading(), entry.getValue().content(), entry.getKey()));
        }
        return result;
    }

    private String normalize(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String hash(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) builder.append(String.format("%02x", value));
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record Heading(int position, String text) { }
    private record RawSection(String heading, String content) { }
    private record RawSegment(String heading, String content) { }
}
