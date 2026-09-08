package com.hector.infrastructure.rag.parser;

import com.hector.domain.document.adapter.port.IDocumentContentParser;
import com.hector.domain.document.model.valobj.DocumentParseResultVO;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文档内容解析器。
 * MVP 只解析 txt、md、pdf、docx，其他格式明确失败，避免引入不可靠的兜底行为。
 */
@Component
public class DocumentContentParser implements IDocumentContentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentContentParser.class);
    private static final String PARSER_VERSION = "mvp-parser-v1";

    @Override
    public DocumentParseResultVO parse(String originalFilename, String contentType, byte[] fileContent) {
        String suffix = extractSuffix(originalFilename);
        log.info("文档内容解析器-解析开始 originalFilename:{} contentType:{} suffix:{} fileSize:{}",
                originalFilename, contentType, suffix, fileContent == null ? 0 : fileContent.length);
        if (fileContent == null || fileContent.length == 0) {
            throw new BusinessException(ResponseCode.EMPTY_FILE);
        }
        try {
            String text = switch (suffix) {
                case "txt", "md" -> new String(fileContent, StandardCharsets.UTF_8);
                case "pdf" -> parsePdf(fileContent);
                case "docx" -> parseDocx(fileContent);
                default -> throw new BusinessException(ResponseCode.DOCUMENT_TYPE_UNSUPPORTED);
            };
            String normalizedText = normalizeText(text);
            if (normalizedText.isBlank()) {
                throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
            }
            log.info("文档内容解析器-解析完成 originalFilename:{} suffix:{} textLength:{}",
                    originalFilename, suffix, normalizedText.length());
            return new DocumentParseResultVO(normalizedText, PARSER_VERSION);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("文档内容解析器-解析异常 originalFilename:{} suffix:{} error:{}",
                    originalFilename, suffix, exception.getMessage(), exception);
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
    }

    private String parsePdf(byte[] fileContent) throws IOException {
        try (PDDocument document = Loader.loadPDF(fileContent)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String parseDocx(byte[] fileContent) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileContent))) {
            return document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText())
                    .filter(text -> text != null && !text.isBlank())
                    .reduce("", (left, right) -> left.isBlank() ? right : left + "\n" + right);
        }
    }

    private String extractSuffix(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int index = originalFilename.lastIndexOf('.');
        if (index < 0 || index == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
