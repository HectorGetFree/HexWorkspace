package com.hector.domain.knowledge.service;

import java.time.LocalDateTime;

/** 延迟清理已失效索引 Generation 的领域能力。 */
public interface IKnowledgeGenerationCleanupService {

    /**
     * 清理超过保留期且不再被当前索引指针引用的 Generation。
     *
     * @param expiredBefore 清理保留期截止时间
     * @param limit 本次最多处理的 Generation 数量
     * @param leaseMinutes 清理 Worker 租约时长
     * @return 本次成功完成清理的 Generation 数量
     */
    int cleanExpired(LocalDateTime expiredBefore, int limit, int leaseMinutes);
}
