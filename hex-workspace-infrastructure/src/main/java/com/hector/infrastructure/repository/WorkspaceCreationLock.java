package com.hector.infrastructure.repository;

import com.hector.domain.workspace.service.IWorkspaceCreationLock;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Repository;

/**
 * 个人 Workspace 创建锁的 Redis 实现。
 *
 * <p>领域层仅定义创建锁的业务抽象；本实现负责封装 Redisson 的锁操作，避免技术依赖反向进入领域层。</p>
 */
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
        RLock lock = redissonClient.getLock(KEY_PREFIX + ownerUserId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
