package com.hector.domain.document.repository;

import com.hector.domain.document.model.entity.DocumentContentSnapshotEntity;

import java.util.List;
import java.util.Optional;

/**
 * 文档内容快照仓储接口。
 */
public interface IDocumentContentSnapshotRepository {

    /**
     * 保存内容快照。
     */
    DocumentContentSnapshotEntity save(DocumentContentSnapshotEntity snapshot);

    /**
     * 更新内容快照。
     */
    void update(DocumentContentSnapshotEntity snapshot);

    /**
     * 按主键查询内容快照。
     */
    Optional<DocumentContentSnapshotEntity> findById(Long snapshotId);

    /**
     * 按业务编码查询内容快照。
     */
    Optional<DocumentContentSnapshotEntity> findByContentCode(String contentCode);

    /**
     * 按 Workspace ID 查询内容快照列表。
     */
    List<DocumentContentSnapshotEntity> listByWorkspaceId(Long workspaceId);
}
