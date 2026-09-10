package com.hector.domain.document.service.impl;

import com.hector.domain.document.adapter.port.IDocumentContentParser;
import com.hector.domain.document.event.DocumentParseRequestedEvent;
import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentContentSnapshotEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentParseResultVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.document.service.IDocumentParseService;
import com.hector.domain.document.service.IDocumentContentSnapshotService;
import com.hector.domain.knowledge.service.IKnowledgeIndexService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 文档版本解析领域服务。
 * 解析结果保存为派生内容快照，数据库仅维护版本与内容快照的关联关系。
 */
@Service
public class DocumentParseServiceImpl implements IDocumentParseService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseServiceImpl.class);

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IDocumentVersionRepository documentVersionRepository;

    @Resource
    private IDocumentContentSnapshotService documentContentSnapshotService;

    @Resource
    private IDocumentContentParser documentContentParser;

    @Resource
    private IKnowledgeIndexService knowledgeIndexService;

    @Override
    public void parse(DocumentParseRequestedEvent event) {
        log.info("文档解析领域服务-解析文档版本开始 workspaceId:{} documentId:{} versionNumber:{}",
                event.getWorkspaceId(), event.getDocumentId(), event.getVersionNumber());
        DocumentVersionEntity documentVersionEntity = null;
        try {
            // 1. 仅处理事件指定的版本，避免异步任务误更新文档最新版本。
            DocumentEntity documentEntity = documentRepository.findById(event.getDocumentId())
                    .orElseThrow(() -> new IllegalStateException("待解析文档不存在"));
            if (!event.getWorkspaceId().equals(documentEntity.getWorkspaceId())) {
                throw new IllegalStateException("待解析文档Workspace不匹配");
            }
            documentVersionEntity = documentVersionRepository
                    .findByDocumentIdAndVersionNumber(documentEntity.getId(), event.getVersionNumber())
                    .orElseThrow(() -> new IllegalStateException("待解析文档版本不存在"));
            if (documentVersionEntity.getParseStatus().name().equals("SUCCESS")) {
                log.info("文档解析领域服务-文档版本已解析，跳过重复消息 documentId:{} versionNumber:{}",
                        documentEntity.getId(), documentVersionEntity.getVersionNumber());
                return;
            }

            // 2. 先落解析中状态，再读取对象内容并选择具体格式解析器。
            documentVersionEntity.markParsing();
            documentVersionRepository.updateParseResult(documentVersionEntity);
            DocumentContentSnapshotEntity sourceContent = documentContentSnapshotService.getAvailableSnapshot(
                    event.getWorkspaceId(), documentVersionEntity.getContentId());
            DocumentParseResultVO parseResult = documentContentParser.parse(
                    sourceContent.getOriginName(), sourceContent.getContentType(),
                    documentContentSnapshotService.readSnapshotContent(event.getWorkspaceId(), sourceContent.getId()));

            // 3. 解析文本作为派生内容快照保存，供后续 RAG、摘要和 Agent 能力复用。
            String parsedContentName = documentEntity.getDocumentCode() + "_v"
                    + documentVersionEntity.getVersionNumber() + "_parsed.txt";
            DocumentContentSnapshotEntity extractedContent = documentContentSnapshotService.prepareDerivedSnapshot(
                    event.getWorkspaceId(), event.getOperatorUserId(), parsedContentName,
                    "text/plain; charset=utf-8", parseResult.text().getBytes(StandardCharsets.UTF_8));
            extractedContent = documentContentSnapshotService.saveSnapshotMetadata(extractedContent);
            documentContentSnapshotService.writeSnapshotObject(
                    extractedContent, parseResult.text().getBytes(StandardCharsets.UTF_8));
            documentContentSnapshotService.markSnapshotSucceeded(event.getWorkspaceId(), extractedContent.getId());
            documentVersionEntity.markParseSucceeded(
                    extractedContent.getId(), parseResult.parserVersion(), LocalDateTime.now());
            documentVersionRepository.updateParseResult(documentVersionEntity);

            // 4. 解析与索引复用同一条消费链路，索引失败不回滚已成功保存的解析产物；后续可基于该产物安全重建。
            try {
                knowledgeIndexService.indexParsedDocument(event, extractedContent.getId());
            } catch (Exception exception) {
                log.error("文档解析领域服务-知识索引异常 workspaceId:{} documentId:{} versionNumber:{} extractedContentId:{} error:{}",
                        event.getWorkspaceId(), documentEntity.getId(), documentVersionEntity.getVersionNumber(),
                        extractedContent.getId(), exception.getMessage(), exception);
            }
            log.info("文档解析领域服务-解析文档版本完成 workspaceId:{} documentId:{} versionNumber:{} extractedContentId:{}",
                    event.getWorkspaceId(), documentEntity.getId(), documentVersionEntity.getVersionNumber(),
                    extractedContent.getId());
        } catch (Exception exception) {
            if (documentVersionEntity != null) {
                documentVersionEntity.markParseFailed("mvp-parser-v1");
                documentVersionRepository.updateParseResult(documentVersionEntity);
            }
            log.error("文档解析领域服务-解析文档版本异常 workspaceId:{} documentId:{} versionNumber:{} error:{}",
                    event.getWorkspaceId(), event.getDocumentId(), event.getVersionNumber(), exception.getMessage(), exception);
        }
    }
}
