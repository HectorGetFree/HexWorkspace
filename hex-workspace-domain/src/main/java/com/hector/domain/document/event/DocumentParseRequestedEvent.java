package com.hector.domain.document.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文档版本创建后发出的解析请求事件。
 * 事件只传递业务标识，具体文件读取与格式解析由后续消费者完成。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseRequestedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long workspaceId;
    private Long documentId;
    private Integer versionNumber;
    private Long operatorUserId;
}
