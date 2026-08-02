package com.hector.domain.document.service;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;

import java.util.List;

/**
 * 文档领域服务。
 * 负责文档聚合及其初始版本的创建、查询和状态约束。
 */
public interface IDocumentService {

    DocumentEntity createUploadedDocument(Long tenantId, Long ownerUserId, Long sourceFileId,
                                          String contentHash, String title);

    List<DocumentEntity> listActiveDocuments(Long tenantId);

    DocumentEntity getActiveDocument(Long tenantId, String documentCode);

    DocumentVersionEntity getCurrentVersion(DocumentEntity documentEntity);
}
