package com.hector.domain.document.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文档版本创建后发出的解析请求事件。
 * 事件只传递业务标识，具体内容读取与格式解析由后续消费者完成。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseRequestedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待解析文档所属 Workspace ID。 */
    private Long workspaceId;

    /** 待解析文档 ID。 */
    private Long documentId;

    /** 待解析的文档版本号。 */
    private Integer versionNumber;

    /** 触发解析流程的用户 ID，用于派生内容审计。 */
    private Long operatorUserId;
}
