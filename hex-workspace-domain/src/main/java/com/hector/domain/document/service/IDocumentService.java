package com.hector.domain.document.service;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;

import java.util.List;

/**
 * 文档领域服务。
 * 负责文档聚合及其初始版本的创建、查询和状态约束。
 */
public interface IDocumentService {

    DocumentEntity createUploadedDocument(Long workspaceId, Long ownerUserId, Long parentId, Long sourceFileId,
                                          String contentHash, String fileName);

    DocumentEntity createMarkdownDocument(Long workspaceId, Long ownerUserId, Long parentId,
                                          String fileName, String content);

    DocumentEntity editMarkdownDocument(Long workspaceId, String documentCode, Long operatorUserId, String content);

    byte[] readCurrentContent(Long workspaceId, String documentCode);

    DocumentEntity renameDocument(Long workspaceId, String documentCode, String fileName);

    DocumentEntity moveDocument(Long workspaceId, String documentCode, Long targetParentId);

    DocumentEntity deleteDocument(Long workspaceId, String documentCode);

    List<DocumentEntity> listActiveDocumentsByParentId(Long workspaceId, Long parentId);

    List<DocumentEntity> listActiveDocuments(Long workspaceId);

    DocumentEntity getActiveDocument(Long workspaceId, String documentCode);

    DocumentVersionEntity getCurrentVersion(DocumentEntity documentEntity);
}
