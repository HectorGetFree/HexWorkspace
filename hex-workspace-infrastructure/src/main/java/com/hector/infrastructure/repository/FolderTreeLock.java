package com.hector.infrastructure.repository;

import com.hector.domain.folder.service.IFolderTreeLock;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Repository;

/** Workspace 目录树变更锁的 Redis 实现。 */
@Repository
public class FolderTreeLock implements IFolderTreeLock {

    private static final String KEY_PREFIX = "workspace:folder:tree:";

    @Resource
    private RedissonClient redissonClient;

    @Override
    public void lock(Long workspaceId) {
        redissonClient.getLock(KEY_PREFIX + workspaceId).lock();
    }

    @Override
    public void unlock(Long workspaceId) {
        RLock lock = redissonClient.getLock(KEY_PREFIX + workspaceId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
