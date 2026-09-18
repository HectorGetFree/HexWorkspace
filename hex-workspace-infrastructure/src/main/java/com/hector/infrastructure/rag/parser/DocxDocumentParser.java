package com.hector.infrastructure.rag.parser;

import com.hector.domain.knowledge.adapter.port.IDocumentParser;
import com.hector.domain.knowledge.model.valobj.DocumentFormatVO;
import com.hector.domain.knowledge.model.valobj.LoadedDocumentVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockTypeVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DOCX 解析器，按正文元素原始顺序保留段落和表格。 */
@Component
public class DocxDocumentParser implements IDocumentParser {

    private static final Pattern HEADING_LEVEL_PATTERN = Pattern.compile("(?:heading|标题)\\s*([1-9])", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(DocumentFormatVO format) {
        return DocumentFormatVO.DOCX == format;
    }

    @Override
    public ParsedDocumentVO parse(LoadedDocumentVO document) {
        try (XWPFDocument xwpfDocument = new XWPFDocument(new ByteArrayInputStream(document.content()))) {
            List<ParsedContentBlockVO> blocks = new ArrayList<>();
            int index = 0;
            for (IBodyElement element : xwpfDocument.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText().strip();
                    if (!text.isEmpty()) {
                        Integer headingLevel = resolveHeadingLevel(paragraph.getStyle());
                        blocks.add(new ParsedContentBlockVO(index++, headingLevel == null
                                ? ParsedContentBlockTypeVO.PARAGRAPH : ParsedContentBlockTypeVO.HEADING,
                                text, headingLevel, null));
                    }
                } else if (element instanceof XWPFTable table) {
                    String text = formatTable(table);
                    if (!text.isBlank()) {
                        blocks.add(new ParsedContentBlockVO(index++, ParsedContentBlockTypeVO.TABLE, text, null, null));
                    }
                }
            }
            return new ParsedDocumentVO("parsed-document-v1", "docx-v1", List.copyOf(blocks));
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
    }

    private Integer resolveHeadingLevel(String style) {
        if (style == null) {
            return null;
        }
        Matcher matcher = HEADING_LEVEL_PATTERN.matcher(style.toLowerCase(Locale.ROOT));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private String formatTable(XWPFTable table) {
        List<String> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText().replace('\n', ' ').strip());
            }
            rows.add(String.join(" | ", cells));
        }
        return String.join("\n", rows).strip();
    }
}
