package com.hector.domain.document.service;

import java.time.LocalDateTime;

/** 文档临时对象清理服务。 */
public interface IDocumentStagingCleanupService {

    /** 清理保留期外且未被上传任务引用的 staging 对象。 */
    int cleanExpiredOrphans(LocalDateTime expiredBefore);

    /** 清理保留期外的终态任务 staging 对象，并移除任务中的路径引用。 */
    int cleanExpiredTerminalTaskObjects(LocalDateTime expiredBefore);
}
