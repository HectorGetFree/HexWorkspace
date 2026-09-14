package com.hector.domain.document.model.valobj;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/** 文档对象存储中的对象元数据。 */
@Getter
@Builder
public class DocumentStorageObjectVO {

    /** 对象在 Bucket 中的完整路径。 */
    private final String objectKey;
    /** 对象最后修改时间。 */
    private final LocalDateTime lastModifiedAt;
}
