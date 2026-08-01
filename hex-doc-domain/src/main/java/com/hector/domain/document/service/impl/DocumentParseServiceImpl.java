package com.hector.domain.document.service.impl;

import com.hector.domain.document.adapter.port.IDocumentContentParser;
import com.hector.domain.document.event.DocumentParseRequestedEvent;
import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentParseResultVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.document.service.IDocumentParseService;
import com.hector.domain.file.model.entity.FileEntity;
import com.hector.domain.file.service.IFileService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 文档版本解析领域服务。
 * 解析结果保存为派生文件，数据库仅维护版本与派生文件的关联关系。
 */
@Service
public class DocumentParseServiceImpl implements IDocumentParseService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseServiceImpl.class);

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IDocumentVersionRepository documentVersionRepository;

    @Resource
    private IFileService fileService;

    @Resource
    private IDocumentContentParser documentContentParser;

    @Override
    public void parse(DocumentParseRequestedEvent event) {
        log.info("文档解析领域服务-解析文档版本开始 tenantId:{} documentId:{} versionNumber:{}",
                event.getTenantId(), event.getDocumentId(), event.getVersionNumber());
        DocumentVersionEntity documentVersionEntity = null;
        try {
            // 1. 仅处理事件指定的版本，避免异步任务误更新文档最新版本。
            DocumentEntity documentEntity = documentRepository.findById(event.getDocumentId())
                    .orElseThrow(() -> new IllegalStateException("待解析文档不存在"));
            if (!event.getTenantId().equals(documentEntity.getTenantId())) {
                throw new IllegalStateException("待解析文档租户不匹配");
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
            FileEntity sourceFileEntity = fileService.getAvailableFile(event.getTenantId(), documentVersionEntity.getFileId());
            DocumentParseResultVO parseResult = documentContentParser.parse(
                    sourceFileEntity.getOriginName(), sourceFileEntity.getContentType(),
                    fileService.readFileContent(event.getTenantId(), sourceFileEntity.getId()));

            // 3. 解析文本作为派生文件保存，供后续 RAG、摘要和 Agent 能力复用。
            String parsedFilename = documentEntity.getDocumentCode() + "_v"
                    + documentVersionEntity.getVersionNumber() + "_parsed.txt";
            FileEntity extractedFileEntity = fileService.storeDerivedFile(event.getTenantId(), event.getOperatorUserId(),
                    parsedFilename, "text/plain; charset=utf-8", parseResult.text().getBytes(StandardCharsets.UTF_8));
            documentVersionEntity.markParseSucceeded(extractedFileEntity.getId(), parseResult.parserVersion(), LocalDateTime.now());
            documentVersionRepository.updateParseResult(documentVersionEntity);
            log.info("文档解析领域服务-解析文档版本完成 tenantId:{} documentId:{} versionNumber:{} extractedFileId:{}",
                    event.getTenantId(), documentEntity.getId(), documentVersionEntity.getVersionNumber(), extractedFileEntity.getId());
        } catch (Exception exception) {
            if (documentVersionEntity != null) {
                documentVersionEntity.markParseFailed("mvp-parser-v1");
                documentVersionRepository.updateParseResult(documentVersionEntity);
            }
            log.error("文档解析领域服务-解析文档版本异常 tenantId:{} documentId:{} versionNumber:{} error:{}",
                    event.getTenantId(), event.getDocumentId(), event.getVersionNumber(), exception.getMessage(), exception);
        }
    }
}
