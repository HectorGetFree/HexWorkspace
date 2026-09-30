package com.hector.infrastructure.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hector.domain.document.adapter.port.IDocumentVersionTextReader;
import com.hector.domain.knowledge.model.entity.KnowledgeParseArtifactEntity;
import com.hector.domain.knowledge.model.valobj.ParsedContentBlockVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;
import com.hector.domain.knowledge.repository.IKnowledgeParseArtifactRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** 从精确版本对应的 Parsed Artifact 生成稳定的模型可读文本。 */
@Component
public class DocumentVersionTextReader implements IDocumentVersionTextReader {
    private static final int READY = 1;

    @Resource
    private IKnowledgeParseArtifactRepository artifactRepository;

    @Resource
    private IDocumentStorageRepository storageRepository;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public String read(Long documentId, Long versionNo, String sourceChecksum) {
        KnowledgeParseArtifactEntity artifact = artifactRepository.findReadyByVersion(
                        documentId, versionNo, sourceChecksum)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED));
        if (artifact.getStatus() != READY || artifact.getArtifactKey() == null
                || artifact.getArtifactChecksum() == null) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
        try {
            byte[] payload = storageRepository.download(artifact.getArtifactKey());
            if (!checksum(payload).equalsIgnoreCase(artifact.getArtifactChecksum())) {
                throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
            }
            ParsedDocumentVO parsed = objectMapper.readValue(payload, ParsedDocumentVO.class);
            return flatten(parsed.contentBlocks());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
    }

    private String flatten(List<ParsedContentBlockVO> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        return blocks.stream()
                .map(ParsedContentBlockVO::text)
                .filter(text -> text != null)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String checksum(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("解析产物摘要计算失败", exception);
        }
    }
}
