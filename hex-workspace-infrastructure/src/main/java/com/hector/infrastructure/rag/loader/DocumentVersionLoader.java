package com.hector.infrastructure.rag.loader;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.knowledge.adapter.port.IDocumentLoader;
import com.hector.domain.knowledge.model.valobj.DocumentFormatVO;
import com.hector.domain.knowledge.model.valobj.LoadedDocumentVO;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Locale;

/** 基于不可变文档版本加载原始内容，并校验其归属与摘要。 */
@Component
public class DocumentVersionLoader implements IDocumentLoader {

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IDocumentVersionRepository documentVersionRepository;

    @Resource
    private IDocumentStorageRepository documentStorageRepository;

    @Value("${hexworkspace.knowledge.parser.max-source-bytes:52428800}")
    private long maxSourceBytes;

    @Override
    public LoadedDocumentVO load(Long workspaceId, Long documentId, Long versionNo) {
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        if (!workspaceId.equals(document.getWorkspaceId())) {
            throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        }

        DocumentVersionEntity version = documentVersionRepository.findByDocumentIdAndVersionNo(documentId, versionNo)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_CONTENT_NOT_FOUND));
        if (version.getContentSize() == null || version.getContentSize() < 0 || version.getContentSize() > maxSourceBytes) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }

        byte[] content = documentStorageRepository.download(version.getStoragePath());
        if (content.length != version.getContentSize() || !matchesChecksum(content, version.getContentHash())) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
        return new LoadedDocumentVO(workspaceId, documentId, versionNo, document.getFileName(),
                version.getContentType(), version.getContentHash(), resolveFormat(document.getFileName(), version.getContentType()), content);
    }

    private DocumentFormatVO resolveFormat(String fileName, String contentType) {
        String normalizedName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".md") || normalizedName.endsWith(".markdown") || normalizedType.contains("markdown")) {
            return DocumentFormatVO.MARKDOWN;
        }
        if (normalizedName.endsWith(".txt") || normalizedType.startsWith("text/plain")) {
            return DocumentFormatVO.TEXT;
        }
        if (normalizedName.endsWith(".docx") || normalizedType.contains("wordprocessingml")) {
            return DocumentFormatVO.DOCX;
        }
        if (normalizedName.endsWith(".pdf") || normalizedType.contains("pdf")) {
            return DocumentFormatVO.PDF;
        }
        throw new BusinessException(ResponseCode.DOCUMENT_TYPE_UNSUPPORTED);
    }

    private boolean matchesChecksum(byte[] content, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isBlank()) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder actualChecksum = new StringBuilder();
            for (byte value : digest) {
                actualChecksum.append(String.format("%02x", value));
            }
            return actualChecksum.toString().equalsIgnoreCase(expectedChecksum);
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
    }
}
