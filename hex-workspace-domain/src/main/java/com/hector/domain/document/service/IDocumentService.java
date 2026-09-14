package com.hector.domain.document.service;

import com.hector.domain.document.model.entity.DocumentEntity;

import java.io.InputStream;
import java.util.List;

/**
 * 文档领域服务。
 * 负责文档聚合、内容存储编排和状态约束。
 */
public interface IDocumentService {

    /** 将本地内容导入为云文档。 */
    DocumentEntity importDocument(Long workspaceId, Long ownerUserId, Long parentId, String fileName,
                                  String contentType, InputStream content, long contentSize, String idempotencyKey);

    /** 在指定目录中新建 Markdown 云文档。 */
    DocumentEntity createMarkdownDocument(Long workspaceId, Long ownerUserId, Long parentId,
                                          String fileName, String content);

    /** 编辑 Markdown 正文并替换当前内容。 */
    DocumentEntity editMarkdownDocument(Long workspaceId, String documentCode, Long operatorUserId, String content);

    /** 读取文档当前原始内容。 */
    byte[] readCurrentContent(Long workspaceId, String documentCode);

    /** 修改文档完整名称，并执行同级目录重名校验。 */
    DocumentEntity renameDocument(Long workspaceId, String documentCode, String fileName);

    /** 将文档移动到 Workspace 内的目标目录。 */
    DocumentEntity moveDocument(Long workspaceId, String documentCode, Long targetParentId);

    /** 将文档标记为逻辑删除状态。 */
    DocumentEntity deleteDocument(Long workspaceId, String documentCode);

    /** 查询父目录下可展示的直属文档。 */
    List<DocumentEntity> listVisibleDocumentsByParentId(Long workspaceId, Long parentId);

    /** 查询 Workspace 下全部可展示的文档。 */
    List<DocumentEntity> listVisibleDocuments(Long workspaceId);

    /** 查询并校验属于指定 Workspace 的可用文档。 */
    DocumentEntity getActiveDocument(Long workspaceId, String documentCode);

}
