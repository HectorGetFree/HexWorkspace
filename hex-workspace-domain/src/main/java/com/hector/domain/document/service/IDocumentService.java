package com.hector.domain.document.service;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;

import java.util.List;

/**
 * 文档领域服务。
 * 负责文档聚合及其初始版本的创建、查询和状态约束。
 */
public interface IDocumentService {

    /** 将本地内容导入为云文档，并创建初始版本。 */
    DocumentEntity importDocument(Long workspaceId, Long ownerUserId, Long parentId, String fileName,
                                  String contentType, byte[] content);

    /** 在指定目录中新建 Markdown 云文档。 */
    DocumentEntity createMarkdownDocument(Long workspaceId, Long ownerUserId, Long parentId,
                                          String fileName, String content);

    /** 编辑 Markdown 正文并创建新的不可变版本。 */
    DocumentEntity editMarkdownDocument(Long workspaceId, String documentCode, Long operatorUserId, String content);

    /** 读取文档当前版本的原始内容。 */
    byte[] readCurrentContent(Long workspaceId, String documentCode);

    /** 修改文档完整名称，并执行同级目录重名校验。 */
    DocumentEntity renameDocument(Long workspaceId, String documentCode, String fileName);

    /** 将文档移动到 Workspace 内的目标目录。 */
    DocumentEntity moveDocument(Long workspaceId, String documentCode, Long targetParentId);

    /** 将文档标记为逻辑删除状态。 */
    DocumentEntity deleteDocument(Long workspaceId, String documentCode);

    /** 查询父目录下处于可用状态的直属文档。 */
    List<DocumentEntity> listActiveDocumentsByParentId(Long workspaceId, Long parentId);

    /** 查询 Workspace 下全部处于可用状态的文档。 */
    List<DocumentEntity> listActiveDocuments(Long workspaceId);

    /** 查询并校验属于指定 Workspace 的可用文档。 */
    DocumentEntity getActiveDocument(Long workspaceId, String documentCode);

    /** 根据文档当前版本指针查询对应的版本实体。 */
    DocumentVersionEntity getCurrentVersion(DocumentEntity documentEntity);
}
