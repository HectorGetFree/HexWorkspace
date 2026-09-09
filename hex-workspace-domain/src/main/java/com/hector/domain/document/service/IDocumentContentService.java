package com.hector.domain.document.service;

import com.hector.domain.document.model.entity.DocumentContentEntity;

/**
 * 文档内容快照服务接口。
 * 该能力只供文档创建、版本编辑和解析流程使用，不提供独立资源入口。
 */
public interface IDocumentContentService {

    /**
     * 保存用户导入的原始文档内容。
     */
    DocumentContentEntity storeImportedContent(Long workspaceId, Long operatorUserId, String originalName,
                                               String contentType, byte[] content);

    /**
     * 保存系统生成的派生文档内容。
     */
    DocumentContentEntity storeDerivedContent(Long workspaceId, Long operatorUserId, String contentName,
                                              String contentType, byte[] content);

    /**
     * 查询指定 Workspace 下可用的内容快照。
     */
    DocumentContentEntity getAvailableContent(Long workspaceId, Long contentId);

    /**
     * 读取 Workspace 内的文档内容快照。
     */
    byte[] readContent(Long workspaceId, Long contentId);

    /**
     * 删除尚未被文档版本引用的内容快照。
     */
    DocumentContentEntity deleteContent(Long workspaceId, Long contentId);
}
