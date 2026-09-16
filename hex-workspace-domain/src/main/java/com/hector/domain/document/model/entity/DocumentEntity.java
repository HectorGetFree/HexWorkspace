package com.hector.domain.document.model.entity;

import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档聚合根。
 * 保存文档的身份、归属和当前版本指针；具体内容由 DocumentVersionEntity 不可变保存。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {

    /** 文档主键 ID。 */
    private Long id;

    /** 文档唯一业务编码，用于接口路径和跨模块引用。 */
    private String documentCode;

    /** 文档所属的个人 Workspace ID。 */
    private Long workspaceId;

    /** 文档所在的父文件夹 ID。 */
    private Long parentId;

    /** 文档所有者用户 ID；当前阶段等同于 Workspace Owner。 */
    private Long ownerUserId;

    /** 不含文件后缀的文档展示标题。 */
    private String title;

    /** 包含文件后缀的完整文件名，用于同级目录重名判断。 */
    private String fileName;

    /** 文档来源类型，区分平台创建和本地上传。 */
    private DocumentTypeVO documentType;

    /** 文档当前生命周期状态；只有 ACTIVE 状态可对外读取。 */
    private DocumentStatusVO status;

    /** 当前已正式生效的内容版本号；ACTIVE 文档必须大于 0。 */
    private Long currentVersionNo;

    /** 影响索引输入的单调修订号；内容版本生效或标题变更时递增。 */
    private Long indexInputRevision;

    /** 本地上传请求的客户端幂等键；平台新建文档为空。 */
    private String idempotencyKey;

    /** 逻辑删除时间；未删除时为空，用于未来回收站与物理清理保留期计算。 */
    private LocalDateTime deletedAt;

    /** 文档创建时间。 */
    private LocalDateTime createdAt;

    /** 文档最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 将文档标记为逻辑删除状态，并仅记录首次删除时间。 */
    public void delete() {
        if (DocumentStatusVO.DELETED == this.status) {
            return;
        }
        this.status = DocumentStatusVO.DELETED;
        this.deletedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** 恢复逻辑删除文档，保留原当前版本指针和全部历史内容版本。 */
    public void restore() {
        if (DocumentStatusVO.DELETED != this.status) {
            return;
        }
        this.status = DocumentStatusVO.ACTIVE;
        this.deletedAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 使用完整文件名重命名文档，并同步维护展示标题与更新时间。
     *
     * @param newFileName 新的完整文件名
     */
    public void rename(String newFileName) {
        this.fileName = newFileName;
        this.title = newFileName.toLowerCase().endsWith(".md")
                ? newFileName.substring(0, newFileName.length() - 3) : newFileName;
        this.updatedAt = LocalDateTime.now();
    }

    /** 标题变更会改变检索时的索引输入，因此必须产生新的索引修订。 */
    public void renameAndAdvanceIndexRevision(String newFileName) {
        rename(newFileName);
        advanceIndexInputRevision();
    }

    /**
     * 将文档移动到新的父目录。
     *
     * @param newParentId 目标父目录 ID
     */
    public void moveTo(Long newParentId) {
        this.parentId = newParentId;
        this.updatedAt = LocalDateTime.now();
    }

    /** 将指定内容版本设为当前正式版本，并将文档切换为可用状态。 */
    public void activateVersion(Long versionNo) {
        if (versionNo == null || versionNo <= 0) {
            throw new IllegalArgumentException("文档当前版本号必须大于0");
        }
        this.currentVersionNo = versionNo;
        this.status = DocumentStatusVO.ACTIVE;
        advanceIndexInputRevision();
        this.updatedAt = LocalDateTime.now();
    }

    private void advanceIndexInputRevision() {
        this.indexInputRevision = (this.indexInputRevision == null ? 0L : this.indexInputRevision) + 1;
    }

    /** 登记一次内容保存中的中间状态，保留原当前版本供失败重试与后续恢复使用。 */
    public void startWriting() {
        this.status = DocumentStatusVO.WRITING;
        this.updatedAt = LocalDateTime.now();
    }

    /** 将首次内容写入失败且无法继续重试的文档标记为失败。 */
    public void failUpload() {
        this.status = DocumentStatusVO.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    /** 判断文档是否处于可用状态。 */
    public boolean isActive() {
        return DocumentStatusVO.ACTIVE == this.status;
    }

    /** 判断文档是否已进入逻辑删除状态。 */
    public boolean isDeleted() {
        return DocumentStatusVO.DELETED == this.status;
    }

    /** 判断文档是否应展示在资料库列表中。 */
    public boolean isVisibleInWorkspace() {
        return DocumentStatusVO.ACTIVE == this.status || DocumentStatusVO.FAILED == this.status;
    }
}
