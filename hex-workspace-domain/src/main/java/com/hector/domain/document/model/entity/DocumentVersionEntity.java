package com.hector.domain.document.model.entity;

import com.hector.domain.document.model.valobj.DocumentStorageTypeVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档某次内容保存形成的不可变快照。
 *
 * <p>版本一经写入不再修改；DocumentEntity 仅通过 currentVersionNo 指向其中一个正式版本。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersionEntity {

    /** 版本主键 ID。 */
    private Long id;
    /** 所属文档主键 ID。 */
    private Long documentId;
    /** 文档内单调递增的版本号，从 1 开始。 */
    private Long versionNo;
    /** 内容对象的存储类型。 */
    private DocumentStorageTypeVO storageType;
    /** 内容对象所在 Bucket 名称。 */
    private String bucketName;
    /** 内容对象在 Bucket 中的不可变路径。 */
    private String storagePath;
    /** 内容 MIME 类型。 */
    private String contentType;
    /** 内容字节大小。 */
    private Long contentSize;
    /** 内容 SHA-256 摘要。 */
    private String contentHash;
    /** 创建该版本的用户 ID。 */
    private Long createdBy;
    /** 版本创建时间。 */
    private LocalDateTime createdAt;
}
