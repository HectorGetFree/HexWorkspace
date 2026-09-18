package com.hector.infrastructure.rag.parser;

import com.hector.domain.knowledge.adapter.port.IDocumentParser;
import com.hector.domain.knowledge.model.valobj.LoadedDocumentVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;

/** 根据已加载文档格式选择唯一解析器。 */
@Component
public class DocumentParserSelector {

    private final List<IDocumentParser> documentParsers;

    public DocumentParserSelector(List<IDocumentParser> documentParsers) {
        this.documentParsers = documentParsers;
    }

    public ParsedDocumentVO parse(LoadedDocumentVO document) {
        return documentParsers.stream()
                .filter(parser -> parser.supports(document.format()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_TYPE_UNSUPPORTED))
                .parse(document);
    }
}
