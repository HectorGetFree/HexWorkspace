package com.hector.infrastructure.rag.parser;

import com.hector.domain.knowledge.adapter.port.IDocumentParser;
import com.hector.domain.knowledge.model.valobj.DocumentFormatVO;
import com.hector.domain.knowledge.model.valobj.LoadedDocumentVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockTypeVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** PDF 解析器，逐页提取可检索文本并保留页码来源。 */
@Component
public class PdfDocumentParser implements IDocumentParser {

    @Override
    public boolean supports(DocumentFormatVO format) {
        return DocumentFormatVO.PDF == format;
    }

    @Override
    public ParsedDocumentVO parse(LoadedDocumentVO document) {
        try (PDDocument pdfDocument = Loader.loadPDF(document.content())) {
            List<ParsedContentBlockVO> blocks = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int pageNo = 1; pageNo <= pdfDocument.getNumberOfPages(); pageNo++) {
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String text = stripper.getText(pdfDocument).strip();
                if (!text.isEmpty()) {
                    blocks.add(new ParsedContentBlockVO(blocks.size(), ParsedContentBlockTypeVO.PARAGRAPH, text, null, pageNo));
                }
            }
            if (pdfDocument.getNumberOfPages() > 0 && blocks.isEmpty()) {
                throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
            }
            return new ParsedDocumentVO("parsed-document-v1", "pdf-v1", List.copyOf(blocks));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
    }
}
