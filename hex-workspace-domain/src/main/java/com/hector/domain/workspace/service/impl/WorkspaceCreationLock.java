package com.hector.domain.workspace.service.impl;

import com.hector.domain.workspace.service.IWorkspaceCreationLock;
import jakarta.annotation.Resource;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Repository;

/** Redisson 个人 Workspace 创建锁实现。 */
@Repository
public class WorkspaceCreationLock implements IWorkspaceCreationLock {

    private static final String KEY_PREFIX = "workspace:personal:create:";

    @Resource
    private RedissonClient redissonClient;

    @Override
    public void lock(Long ownerUserId) {
        redissonClient.getLock(KEY_PREFIX + ownerUserId).lock();
    }

    @Override
    public void unlock(Long ownerUserId) {
        org.redisson.api.RLock lock = redissonClient.getLock(KEY_PREFIX + ownerUserId);
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
