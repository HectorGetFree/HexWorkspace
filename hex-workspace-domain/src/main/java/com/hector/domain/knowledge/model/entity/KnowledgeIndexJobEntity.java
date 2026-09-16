package com.hector.domain.knowledge.model.entity;

import com.hector.domain.knowledge.model.valobj.KnowledgeIndexJobStatusVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeIndexJobTypeVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 绑定不可变文档版本与索引输入修订的异步索引任务。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexJobEntity {
    /** 主键。 */
    private Long id;

    /** 任务全局编码。 */
    private String jobCode;

    /** 触发请求的稳定幂等键。 */
    private String requestKey;

    /** 所属资料库。 */
    private Long workspaceId;

    /** 所属文档。 */
    private Long documentId;

    /** 本次索引绑定的不可变内容版本号。 */
    private Long versionNo;

    /** 本次索引绑定的文档索引输入修订号。 */
    private Long indexInputRevision;

    /** 任务来源类型。 */
    private KnowledgeIndexJobTypeVO jobType;

    /** 任务当前状态。 */
    private KnowledgeIndexJobStatusVO status;

    /** 当前执行阶段，Phase 1 固定为排队阶段。 */
    private Integer stage;

    /** 当前执行 Worker 的唯一令牌。 */
    private String workerToken;

    /** Worker 执行租约截止时间。 */
    private LocalDateTime runningLeaseUntil;

    /** 已发生的真实执行失败重试次数。 */
    private Integer retryCount;

    /** 下一次允许重试的时间。 */
    private LocalDateTime nextRetryAt;

    /** 最近一次失败原因。 */
    private String failureReason;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
}
