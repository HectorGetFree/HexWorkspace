package com.hector.domain.knowledge.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexJobEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexOutboxEventEntity;
import com.hector.domain.knowledge.model.valobj.KnowledgeIndexJobStatusVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeIndexJobTypeVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeIndexOutboxStatusVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexJobRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexOutboxEventRepository;
import com.hector.domain.knowledge.service.IKnowledgeIndexJobService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.UUID;

/** Phase 1 索引任务登记实现；实际 Worker 在后续阶段启用。 */
@Service
public class KnowledgeIndexJobServiceImpl implements IKnowledgeIndexJobService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexJobServiceImpl.class);

    @Resource
    private IKnowledgeIndexJobRepository jobRepository;

    @Resource
    private IKnowledgeIndexOutboxEventRepository outboxEventRepository;

    @Override
    public void registerAutomatic(DocumentEntity document) {
        log.info("知识索引任务-自动登记开始 documentId:{} versionNo:{} indexInputRevision:{}",
                document.getId(), document.getCurrentVersionNo(), document.getIndexInputRevision());
        String requestKey = "auto:" + document.getId() + ":" + document.getCurrentVersionNo()
                + ":" + document.getIndexInputRevision();
        LocalDateTime now = LocalDateTime.now();
        String jobCode = "idx_" + UUID.randomUUID().toString().replace("-", "");
        KnowledgeIndexJobEntity job = KnowledgeIndexJobEntity.builder()
                .jobCode(jobCode)
                .requestKey(requestKey)
                .workspaceId(document.getWorkspaceId())
                .documentId(document.getId())
                .versionNo(document.getCurrentVersionNo())
                .indexInputRevision(document.getIndexInputRevision())
                .jobType(KnowledgeIndexJobTypeVO.AUTOMATIC)
                .status(KnowledgeIndexJobStatusVO.PENDING)
                .stage(0)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // 1. 以数据库唯一请求键登记任务，重复触发不重复投递。
        if (!jobRepository.saveIfAbsent(job)) {
            log.info("知识索引任务-自动登记复用已有任务 requestKey:{}", requestKey);
            return;
        }

        // 2. 与任务登记处于同一外层事务，记录后续调度所需的可靠消息意图。
        outboxEventRepository.save(KnowledgeIndexOutboxEventEntity.builder()
                .eventCode("knowledge_index_" + UUID.randomUUID().toString().replace("-", ""))
                .jobCode(jobCode)
                .status(KnowledgeIndexOutboxStatusVO.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build());
        log.info("知识索引任务-自动登记完成 jobCode:{} requestKey:{}", jobCode, requestKey);
    }
}
