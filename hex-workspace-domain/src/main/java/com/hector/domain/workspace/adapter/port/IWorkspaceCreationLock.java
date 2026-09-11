package com.hector.domain.workspace.adapter.port;

/**
 * 个人 Workspace 创建锁端口。
 * 以用户 ID 为粒度串行化默认资料库初始化，避免并发注册或重试重复创建根目录。
 */
public interface IWorkspaceCreationLock {

    /** 获取指定用户的个人 Workspace 创建锁。 */
    void lock(Long ownerUserId);

    /** 释放指定用户的个人 Workspace 创建锁。 */
    void unlock(Long ownerUserId);
}
