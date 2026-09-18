package com.hector.infrastructure.rag.parser;

import com.hector.domain.knowledge.adapter.port.IDocumentParser;
import com.hector.domain.knowledge.model.valobj.DocumentFormatVO;
import com.hector.domain.knowledge.model.valobj.LoadedDocumentVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockTypeVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown 解析器，识别标题并把围栏代码块作为普通内容完整保留。 */
@Component
public class MarkdownDocumentParser implements IDocumentParser {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    @Override
    public boolean supports(DocumentFormatVO format) {
        return DocumentFormatVO.MARKDOWN == format;
    }

    @Override
    public ParsedDocumentVO parse(LoadedDocumentVO document) {
        try {
            List<ParsedContentBlockVO> blocks = new ArrayList<>();
            StringBuilder paragraph = new StringBuilder();
            boolean inFence = false;
            int index = 0;
            for (String line : new String(document.content(), StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n", -1)) {
                if (line.stripLeading().startsWith("```")) {
                    inFence = !inFence;
                    appendLine(paragraph, line);
                    continue;
                }
                Matcher matcher = !inFence ? HEADING_PATTERN.matcher(line) : null;
                if (matcher != null && matcher.matches()) {
                    index = flushParagraph(blocks, paragraph, index);
                    blocks.add(new ParsedContentBlockVO(index++, ParsedContentBlockTypeVO.HEADING,
                            matcher.group(2), matcher.group(1).length(), null));
                } else if (!inFence && line.isBlank()) {
                    index = flushParagraph(blocks, paragraph, index);
                } else {
                    appendLine(paragraph, line);
                }
            }
            flushParagraph(blocks, paragraph, index);
            return new ParsedDocumentVO("parsed-document-v1", "markdown-v1", List.copyOf(blocks));
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
    }

    private int flushParagraph(List<ParsedContentBlockVO> blocks, StringBuilder paragraph, int index) {
        String text = paragraph.toString().strip();
        paragraph.setLength(0);
        if (text.isEmpty()) {
            return index;
        }
        blocks.add(new ParsedContentBlockVO(index, ParsedContentBlockTypeVO.PARAGRAPH, text, null, null));
        return index + 1;
    }

    private void appendLine(StringBuilder paragraph, String line) {
        if (!paragraph.isEmpty()) {
            paragraph.append('\n');
        }
        paragraph.append(line);
    }
}
