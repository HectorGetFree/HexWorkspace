package com.hector.infrastructure.rag.parser;

import com.hector.domain.knowledge.adapter.port.IDocumentParser;
import com.hector.domain.knowledge.model.valobj.DocumentFormatVO;
import com.hector.domain.knowledge.model.valobj.LoadedDocumentVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockTypeVO;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 纯文本解析器，以连续空行划分自然段。 */
@Component
public class TextDocumentParser implements IDocumentParser {

    @Override
    public boolean supports(DocumentFormatVO format) {
        return DocumentFormatVO.TEXT == format;
    }

    @Override
    public ParsedDocumentVO parse(LoadedDocumentVO document) {
        List<ParsedContentBlockVO> blocks = new ArrayList<>();
        int index = 0;
        for (String paragraph : new String(document.content(), StandardCharsets.UTF_8).replace("\r\n", "\n").split("\\n\\s*\\n+")) {
            String text = paragraph.strip();
            if (!text.isEmpty()) {
                blocks.add(new ParsedContentBlockVO(index++, ParsedContentBlockTypeVO.PARAGRAPH, text, null, null));
            }
        }
        return new ParsedDocumentVO("parsed-document-v1", "text-v1", List.copyOf(blocks));
    }
}
