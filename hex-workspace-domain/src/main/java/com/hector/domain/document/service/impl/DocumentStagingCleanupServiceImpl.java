package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.valobj.DocumentStorageObjectVO;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.document.repository.IDocumentUploadTaskRepository;
import com.hector.domain.document.service.IDocumentStagingCleanupService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 文档 staging 孤儿对象清理领域服务。 */
@Service
public class DocumentStagingCleanupServiceImpl implements IDocumentStagingCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStagingCleanupServiceImpl.class);
    private static final String STAGING_PREFIX = "staging/";

    @Resource
    private IDocumentStorageRepository storageRepository;

    @Resource
    private IDocumentUploadTaskRepository taskRepository;

    @Override
    public int cleanExpiredOrphans(LocalDateTime expiredBefore) {
        log.info("文档staging对象清理开始 expiredBefore:{}", expiredBefore);
        int deletedCount = 0;
        for (DocumentStorageObjectVO object : storageRepository.listByPrefix(STAGING_PREFIX)) {
            // 1. 保留期内的对象可能仍处于“写 staging 与 MySQL 提交”窗口，禁止清理。
            if (object.getLastModifiedAt() == null || object.getLastModifiedAt().isAfter(expiredBefore)) {
                continue;
            }

            // 2. UploadTask 是 staging 对象唯一的业务引用来源；有引用即交给任务链路处理。
            if (taskRepository.existsByStagingPath(object.getObjectKey())) {
                continue;
            }

            // 3. 无任务引用的过期对象属于孤儿对象，删除操作可重复执行。
            storageRepository.delete(object.getObjectKey());
            deletedCount++;
            log.info("文档staging对象清理完成 objectKey:{}", object.getObjectKey());
        }
        log.info("文档staging对象清理结束 expiredBefore:{} deletedCount:{}", expiredBefore, deletedCount);
        return deletedCount;
    }
}
