package com.hector.domain.folder.service;

/**
 * Workspace 目录树变更锁端口。
 *
 * <p>创建、重命名、移动、删除和恢复均会改写同一棵树；以 Workspace 为粒度串行化，
 * 避免多实例下同名目录并发写入或交叉移动形成环。</p>
 */
public interface IFolderTreeLock {

    /** 获取指定 Workspace 的目录树变更锁。 */
    void lock(Long workspaceId);

    /** 释放指定 Workspace 的目录树变更锁。 */
    void unlock(Long workspaceId);
}
