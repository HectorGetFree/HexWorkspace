package com.hector.infrastructure.rag.parser;

import com.hector.domain.knowledge.model.valobj.DocumentFormatVO;
import com.hector.domain.knowledge.model.valobj.LoadedDocumentVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockTypeVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 使用运行时生成的真实 DOCX/PDF Fixture 验证各格式解析结果。 */
class DocumentParserFixtureTest {

    @Test
    void shouldParseMarkdownHeadingCodeBlockAndParagraphInOrder() {
        String markdown = "# 一级标题\n\n普通段落。\n\n```java\n# 不是标题\n```\n\n## 二级标题\n\n结尾段落。";

        ParsedDocumentVO parsed = new MarkdownDocumentParser().parse(document(DocumentFormatVO.MARKDOWN, "fixture.md", markdown.getBytes(StandardCharsets.UTF_8)));

        assertEquals(5, parsed.contentBlocks().size());
        assertEquals(ParsedContentBlockTypeVO.HEADING, parsed.contentBlocks().get(0).type());
        assertEquals("一级标题", parsed.contentBlocks().get(0).text());
        assertEquals(1, parsed.contentBlocks().get(0).headingLevel());
        assertEquals("普通段落。", parsed.contentBlocks().get(1).text());
        assertEquals("```java\n# 不是标题\n```", parsed.contentBlocks().get(2).text());
        assertEquals("二级标题", parsed.contentBlocks().get(3).text());
        assertEquals("结尾段落。", parsed.contentBlocks().get(4).text());
    }

    @Test
    void shouldParseTextParagraphBoundaryAndAllowEmptyDocument() {
        TextDocumentParser parser = new TextDocumentParser();
        ParsedDocumentVO parsed = parser.parse(document(DocumentFormatVO.TEXT, "fixture.txt", "第一段\n延续\n\n \n第二段".getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, parsed.contentBlocks().size());
        assertEquals("第一段\n延续", parsed.contentBlocks().get(0).text());
        assertEquals("第二段", parsed.contentBlocks().get(1).text());
        assertEquals(0, parser.parse(document(DocumentFormatVO.TEXT, "empty.txt", new byte[0])).contentBlocks().size());
    }

    @Test
    void shouldKeepDocxHeadingParagraphAndTableOriginalOrder() throws Exception {
        ParsedDocumentVO parsed = new DocxDocumentParser().parse(document(DocumentFormatVO.DOCX, "fixture.docx", createDocxFixture()));

        assertEquals(3, parsed.contentBlocks().size());
        assertEquals(ParsedContentBlockTypeVO.HEADING, parsed.contentBlocks().get(0).type());
        assertEquals("文档标题", parsed.contentBlocks().get(0).text());
        assertEquals("正文段落", parsed.contentBlocks().get(1).text());
        assertEquals(ParsedContentBlockTypeVO.TABLE, parsed.contentBlocks().get(2).type());
        assertEquals("字段 | 值\n状态 | 正常", parsed.contentBlocks().get(2).text());
    }

    @Test
    void shouldKeepPdfPageNumberAndRejectScannedPdf() throws Exception {
        PdfDocumentParser parser = new PdfDocumentParser();
        ParsedDocumentVO parsed = parser.parse(document(DocumentFormatVO.PDF, "fixture.pdf", createTextPdfFixture()));

        assertEquals(2, parsed.contentBlocks().size());
        assertEquals("page-one", parsed.contentBlocks().get(0).text());
        assertEquals(1, parsed.contentBlocks().get(0).pageNo());
        assertEquals("page-two", parsed.contentBlocks().get(1).text());
        assertEquals(2, parsed.contentBlocks().get(1).pageNo());
        assertThrows(BusinessException.class, () -> parser.parse(document(DocumentFormatVO.PDF, "scan.pdf", createEmptyPagePdfFixture())));
    }

    private LoadedDocumentVO document(DocumentFormatVO format, String fileName, byte[] content) {
        return new LoadedDocumentVO(1L, 2L, 3L, fileName, "application/octet-stream", "checksum", format, content);
    }

    private byte[] createDocxFixture() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph title = document.createParagraph();
            title.setStyle("Heading1");
            title.createRun().setText("文档标题");
            document.createParagraph().createRun().setText("正文段落");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("字段");
            table.getRow(0).getCell(1).setText("值");
            table.getRow(1).getCell(0).setText("状态");
            table.getRow(1).getCell(1).setText("正常");
            document.write(output);
            return output.toByteArray();
        }
    }

    private byte[] createTextPdfFixture() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writePdfPage(document, "page-one");
            writePdfPage(document, "page-two");
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] createEmptyPagePdfFixture() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private void writePdfPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            stream.newLineAtOffset(72, 720);
            stream.showText(text);
            stream.endText();
        }
    }
}
