package com.hector.domain.document.repository;

import com.hector.domain.document.model.entity.DocumentEntity;

import java.util.List;
import java.util.Optional;

/**
 * 文档聚合仓储。
 */
public interface IDocumentRepository {

    /** 保存文档聚合，并回填数据库主键。 */
    DocumentEntity save(DocumentEntity documentEntity);

    /** 更新文档当前状态、名称、目录或版本指针。 */
    void update(DocumentEntity documentEntity);

    /** 基于当前版本与索引修订号执行条件更新，只有成功后版本才真正生效。 */
    boolean activateVersionIfCurrent(DocumentEntity documentEntity, Long expectedVersionNo, Long expectedIndexInputRevision);

    /** 基于当前版本与索引修订号抢占写入权，避免并发编辑登记同一后继版本。 */
    boolean startWritingIfCurrent(DocumentEntity documentEntity, Long expectedVersionNo, Long expectedIndexInputRevision);

    /** 基于索引修订号条件更新标题，避免旧重命名覆盖较新的索引输入。 */
    boolean renameAndAdvanceIndexRevisionIfCurrent(DocumentEntity documentEntity, Long expectedIndexInputRevision);

    /** 按数据库主键查询文档。 */
    Optional<DocumentEntity> findById(Long documentId);

    /** 在发布事务中锁定文档行，防止旧版本索引与新内容提交交叉发布。 */
    Optional<DocumentEntity> lockById(Long documentId);

    /** 按对外业务编码查询文档。 */
    Optional<DocumentEntity> findByDocumentCode(String documentCode);

    /** 按本地上传幂等键查询首次创建的文档。 */
    Optional<DocumentEntity> findByUploadIdempotencyKey(Long workspaceId, Long ownerUserId, String idempotencyKey);

    /** 查询 Workspace 下的全部文档，由调用方决定是否过滤状态。 */
    List<DocumentEntity> listByWorkspaceId(Long workspaceId);

    /** 查询父目录下可展示的直属文档。 */
    List<DocumentEntity> listVisibleByParentId(Long workspaceId, Long parentId);

    /** 查询父目录下具有指定完整名称的可用文档，用于重名校验。 */
    Optional<DocumentEntity> findActiveByParentIdAndFileName(Long workspaceId, Long parentId, String fileName);
}
