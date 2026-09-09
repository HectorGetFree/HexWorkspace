package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentParseStatusVO;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.document.service.IDocumentService;
import com.hector.domain.document.model.entity.DocumentContentEntity;
import com.hector.domain.document.service.IDocumentContentService;
import com.hector.domain.folder.service.IFolderService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文档领域服务实现。
 * 负责 Workspace 内文档及其版本的创建和状态维护。
 */
@Service
public class DocumentServiceImpl implements IDocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);
    private static final String DOCUMENT_CODE_PREFIX = "doc_";

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IDocumentVersionRepository documentVersionRepository;

    @Resource
    private IDocumentContentService documentContentService;

    @Resource
    private IFolderService folderService;

    @Override
    @Transactional
    public DocumentEntity importDocument(Long workspaceId, Long ownerUserId, Long parentId, String fileName,
                                         String contentType, byte[] content) {
        log.info("文档领域服务-导入文档开始 workspaceId:{} ownerUserId:{} parentId:{} fileName:{}",
                workspaceId, ownerUserId, parentId, fileName);
        validateCreateCommand(workspaceId, ownerUserId, parentId, fileName);
        folderService.getActiveFolder(workspaceId, parentId);
        String normalizedFileName = normalizeFileName(fileName, false);
        assertFileNameAvailable(workspaceId, parentId, normalizedFileName, null);

        // 1. 将本地内容保存为文档的初始内容快照，导入后不再形成独立文件资源。
        DocumentContentEntity initialContent = documentContentService.storeImportedContent(
                workspaceId, ownerUserId, normalizedFileName, contentType, content);

        // 2. 创建文档聚合根，当前版本固定从 1 开始。
        LocalDateTime now = LocalDateTime.now();
        DocumentEntity documentEntity = documentRepository.save(DocumentEntity.builder()
                .documentCode(generateDocumentCode())
                .workspaceId(workspaceId)
                .parentId(parentId)
                .ownerUserId(ownerUserId)
                .initialContentId(initialContent.getId())
                .title(resolveTitle(normalizedFileName))
                .fileName(normalizedFileName)
                .documentType(DocumentTypeVO.LOCAL_UPLOADED)
                .status(DocumentStatusVO.ACTIVE)
                .currentVersion(1)
                .createdAt(now)
                .updatedAt(now)
                .build());

        // 3. 初始版本引用内容快照；解析能力不影响文档导入结果。
        documentVersionRepository.save(DocumentVersionEntity.builder()
                .documentId(documentEntity.getId())
                .versionNumber(1)
                .contentId(initialContent.getId())
                .contentHash(initialContent.getContentHash())
                .parseStatus(DocumentParseStatusVO.NOT_REQUESTED)
                .createdByUserId(ownerUserId)
                .createdAt(now)
                .build());
        log.info("文档领域服务-导入文档完成 documentId:{} documentCode:{} workspaceId:{}",
                documentEntity.getId(), documentEntity.getDocumentCode(), documentEntity.getWorkspaceId());
        return documentEntity;
    }

    @Override
    @Transactional
    public DocumentEntity createMarkdownDocument(Long workspaceId, Long ownerUserId, Long parentId,
                                                 String fileName, String content) {
        log.info("文档领域服务-新建Markdown文档开始 workspaceId:{} ownerUserId:{} parentId:{}",
                workspaceId, ownerUserId, parentId);

        // 1. 校验目标目录和完整文件名，平台新建文档统一补齐 .md 后缀。
        folderService.getActiveFolder(workspaceId, parentId);
        String normalizedFileName = normalizeFileName(fileName, true);
        assertFileNameAvailable(workspaceId, parentId, normalizedFileName, null);
        String normalizedContent = content == null ? "" : content;

        // 2. 先将 Markdown 快照保存为内部对象，再创建云文档与版本 1。
        DocumentContentEntity initialContent = documentContentService.storeDerivedContent(
                workspaceId, ownerUserId, normalizedFileName,
                "text/markdown; charset=utf-8", normalizedContent.getBytes(StandardCharsets.UTF_8));
        LocalDateTime now = LocalDateTime.now();
        DocumentEntity document = documentRepository.save(DocumentEntity.builder()
                .documentCode(generateDocumentCode())
                .workspaceId(workspaceId)
                .parentId(parentId)
                .ownerUserId(ownerUserId)
                .initialContentId(initialContent.getId())
                .title(resolveTitle(normalizedFileName))
                .fileName(normalizedFileName)
                .documentType(DocumentTypeVO.PLATFORM_CREATED)
                .status(DocumentStatusVO.ACTIVE)
                .currentVersion(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
        documentVersionRepository.save(DocumentVersionEntity.builder()
                .documentId(document.getId())
                .versionNumber(1)
                .contentId(initialContent.getId())
                .contentHash(initialContent.getContentHash())
                .parseStatus(DocumentParseStatusVO.NOT_REQUESTED)
                .createdByUserId(ownerUserId)
                .createdAt(now)
                .build());
        log.info("文档领域服务-新建Markdown文档完成 workspaceId:{} documentId:{} documentCode:{}",
                workspaceId, document.getId(), document.getDocumentCode());
        return document;
    }

    @Override
    @Transactional
    public DocumentEntity editMarkdownDocument(Long workspaceId, String documentCode, Long operatorUserId, String content) {
        log.info("文档领域服务-编辑Markdown文档开始 workspaceId:{} documentCode:{} operatorUserId:{}",
                workspaceId, documentCode, operatorUserId);

        // 1. 只允许平台 Markdown 文档进入编辑流程，上传的其他格式保持原始内容不变。
        DocumentEntity document = getActiveDocument(workspaceId, documentCode);
        if (!document.getFileName().toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new BusinessException(ResponseCode.DOCUMENT_EDIT_UNSUPPORTED);
        }
        // 2. 保存新的不可变内容快照，然后原子推进当前版本指针。
        String normalizedContent = content == null ? "" : content;
        DocumentContentEntity contentSnapshot = documentContentService.storeDerivedContent(
                workspaceId, operatorUserId, document.getFileName(),
                "text/markdown; charset=utf-8", normalizedContent.getBytes(StandardCharsets.UTF_8));
        int nextVersion = document.getCurrentVersion() + 1;
        documentVersionRepository.save(DocumentVersionEntity.builder()
                .documentId(document.getId())
                .versionNumber(nextVersion)
                .contentId(contentSnapshot.getId())
                .contentHash(contentSnapshot.getContentHash())
                .parseStatus(DocumentParseStatusVO.NOT_REQUESTED)
                .createdByUserId(operatorUserId)
                .createdAt(LocalDateTime.now())
                .build());
        document.switchCurrentVersion(nextVersion);
        documentRepository.update(document);
        log.info("文档领域服务-编辑Markdown文档完成 workspaceId:{} documentCode:{} versionNumber:{}",
                workspaceId, documentCode, nextVersion);
        return document;
    }

    @Override
    public byte[] readCurrentContent(Long workspaceId, String documentCode) {
        DocumentEntity document = getActiveDocument(workspaceId, documentCode);
        return documentContentService.readContent(workspaceId, getCurrentVersion(document).getContentId());
    }

    @Override
    @Transactional
    public DocumentEntity renameDocument(Long workspaceId, String documentCode, String fileName) {
        log.info("文档领域服务-重命名文档开始 workspaceId:{} documentCode:{}", workspaceId, documentCode);
        DocumentEntity document = getActiveDocument(workspaceId, documentCode);
        String normalized = normalizeFileName(fileName, document.getDocumentType() == DocumentTypeVO.PLATFORM_CREATED);
        assertFileNameAvailable(workspaceId, document.getParentId(), normalized, document.getId());
        document.rename(normalized);
        documentRepository.update(document);
        log.info("文档领域服务-重命名文档完成 workspaceId:{} documentCode:{} fileName:{}",
                workspaceId, documentCode, normalized);
        return document;
    }

    @Override
    @Transactional
    public DocumentEntity moveDocument(Long workspaceId, String documentCode, Long targetParentId) {
        log.info("文档领域服务-移动文档开始 workspaceId:{} documentCode:{} targetParentId:{}",
                workspaceId, documentCode, targetParentId);
        DocumentEntity document = getActiveDocument(workspaceId, documentCode);
        folderService.getActiveFolder(workspaceId, targetParentId);
        assertFileNameAvailable(workspaceId, targetParentId, document.getFileName(), document.getId());
        document.moveTo(targetParentId);
        documentRepository.update(document);
        log.info("文档领域服务-移动文档完成 workspaceId:{} documentCode:{} targetParentId:{}",
                workspaceId, documentCode, targetParentId);
        return document;
    }

    @Override
    @Transactional
    public DocumentEntity deleteDocument(Long workspaceId, String documentCode) {
        log.info("文档领域服务-删除文档开始 workspaceId:{} documentCode:{}", workspaceId, documentCode);
        DocumentEntity document = getActiveDocument(workspaceId, documentCode);
        document.delete();
        documentRepository.update(document);
        log.info("文档领域服务-删除文档完成 workspaceId:{} documentCode:{}", workspaceId, documentCode);
        return document;
    }

    @Override
    public List<DocumentEntity> listActiveDocumentsByParentId(Long workspaceId, Long parentId) {
        folderService.getActiveFolder(workspaceId, parentId);
        return documentRepository.listActiveByParentId(workspaceId, parentId);
    }

    @Override
    public List<DocumentEntity> listActiveDocuments(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_WORKSPACE_ID);
        }
        log.info("文档领域服务-查询活跃文档列表开始 workspaceId:{}", workspaceId);
        List<DocumentEntity> documentEntities = documentRepository.listByWorkspaceId(workspaceId).stream()
                .filter(DocumentEntity::isActive)
                .collect(Collectors.toList());
        log.info("文档领域服务-查询活跃文档列表完成 workspaceId:{} documentCount:{}", workspaceId, documentEntities.size());
        return documentEntities;
    }

    @Override
    public DocumentEntity getActiveDocument(Long workspaceId, String documentCode) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_WORKSPACE_ID);
        }
        if (documentCode == null || documentCode.isBlank()) {
            throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        }
        log.info("文档领域服务-查询活跃文档开始 workspaceId:{} documentCode:{}", workspaceId, documentCode);
        DocumentEntity documentEntity = documentRepository.findByDocumentCode(documentCode)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        if (!workspaceId.equals(documentEntity.getWorkspaceId())) {
            throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        }
        if (!documentEntity.isActive()) {
            throw new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE);
        }
        log.info("文档领域服务-查询活跃文档完成 documentId:{} documentCode:{}", documentEntity.getId(), documentEntity.getDocumentCode());
        return documentEntity;
    }

    @Override
    public DocumentVersionEntity getCurrentVersion(DocumentEntity documentEntity) {
        log.info("文档领域服务-查询当前版本开始 documentId:{} currentVersion:{}",
                documentEntity.getId(), documentEntity.getCurrentVersion());
        DocumentVersionEntity documentVersionEntity = documentVersionRepository
                .findByDocumentIdAndVersionNumber(documentEntity.getId(), documentEntity.getCurrentVersion())
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        log.info("文档领域服务-查询当前版本完成 documentId:{} versionId:{} versionNumber:{}",
                documentEntity.getId(), documentVersionEntity.getId(), documentVersionEntity.getVersionNumber());
        return documentVersionEntity;
    }

    private void validateCreateCommand(Long workspaceId, Long ownerUserId, Long parentId, String fileName) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_WORKSPACE_ID);
        }
        if (ownerUserId == null || ownerUserId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_USER_ID);
        }
        if (parentId == null || parentId <= 0) {
            throw new BusinessException(ResponseCode.FOLDER_NOT_FOUND);
        }
        normalizeFileName(fileName, false);
    }

    private String normalizeFileName(String fileName, boolean markdown) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ResponseCode.EMPTY_DOCUMENT_TITLE);
        }
        String normalized = fileName.trim().replace('\\', '_').replace('/', '_');
        if (markdown && !normalized.toLowerCase(Locale.ROOT).endsWith(".md")) {
            normalized += ".md";
        }
        return normalized;
    }

    private String resolveTitle(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private void assertFileNameAvailable(Long workspaceId, Long parentId, String fileName, Long selfId) {
        documentRepository.findActiveByParentIdAndFileName(workspaceId, parentId, fileName)
                .filter(found -> !found.getId().equals(selfId))
                .ifPresent(found -> {
                    throw new BusinessException(ResponseCode.DOCUMENT_NAME_DUPLICATE);
                });
    }

    private String generateDocumentCode() {
        for (int i = 0; i < 10; i++) {
            String documentCode = DOCUMENT_CODE_PREFIX + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 12)
                    .toLowerCase(Locale.ROOT);
            if (documentRepository.findByDocumentCode(documentCode).isEmpty()) {
                return documentCode;
            }
        }
        throw new BusinessException(ResponseCode.SYSTEM_ERROR);
    }
}
