package com.hector.trigger.http;

import com.hector.domain.document.adapter.event.IDocumentParseEventPublisher;
import com.hector.domain.document.event.DocumentParseRequestedEvent;
import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.service.IDocumentService;
import com.hector.domain.file.model.entity.FileEntity;
import com.hector.domain.file.service.IFileService;
import com.hector.domain.membership.service.IMembershipService;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import com.hector.trigger.api.dto.DocumentCreateResponseDTO;
import com.hector.trigger.api.dto.DocumentListResponseDTO;
import com.hector.trigger.api.dto.DocumentProcessingStatusResponseDTO;
import com.hector.trigger.api.dto.DocumentResponseDTO;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档接口控制器。
 * MVP 只提供创建、列表与详情能力；Controller 负责成员校验、文件保存、文档创建和解析事件发布的顺序编排。
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/documents")
public class DocumentController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    @Resource
    private IMembershipService membershipService;

    @Resource
    private IFileService fileService;

    @Resource
    private IDocumentService documentService;

    @Resource
    private IDocumentParseEventPublisher documentParseEventPublisher;

    /**
     * 上传文件并创建文档。
     * 创建成功即返回 PENDING；PDF、DOCX、TXT、MD 的内容解析由 RabbitMQ 消费者异步完成。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/tenants/1/documents" \
     *   -H "Authorization: Bearer 替换为实际登录令牌" \
     *   -F "file=@./demo.pdf" \
     *   -F "title=演示文档"
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<DocumentCreateResponseDTO> createDocument(@PathVariable("tenantId") Long tenantId,
                                                               @RequestPart("file") MultipartFile file,
                                                               @RequestParam(value = "title", required = false) String title) {
        FileEntity fileEntity = null;
        boolean documentCreated = false;
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            String currentUsername = SecurityUserUtil.getCurrentUsername();
            log.info("创建文档开始 tenantId:{} userId:{} username:{} originalFilename:{}",
                    tenantId, currentUserId, currentUsername, file == null ? null : file.getOriginalFilename());

            // 1. 先确认用户已在租户上下文内，再保存底层文件资源。
            membershipService.assertActiveMember(currentUserId, tenantId);
            validateSupportedFile(file);
            fileEntity = fileService.uploadFile(tenantId, currentUserId, file.getOriginalFilename(),
                    file.getContentType(), file.getBytes());

            // 2. 文档聚合与版本 1 落库完成后，发布异步解析事件。
            DocumentEntity documentEntity = documentService.createUploadedDocument(tenantId, currentUserId,
                    fileEntity.getId(), fileEntity.getFileHash(), resolveTitle(title, file.getOriginalFilename()));
            documentCreated = true;
            DocumentVersionEntity documentVersionEntity = documentService.getCurrentVersion(documentEntity);
            documentParseEventPublisher.publish(DocumentParseRequestedEvent.builder()
                    .tenantId(tenantId)
                    .documentId(documentEntity.getId())
                    .versionNumber(documentVersionEntity.getVersionNumber())
                    .operatorUserId(currentUserId)
                    .build());
            log.info("创建文档完成 tenantId:{} userId:{} documentId:{} documentCode:{} versionNumber:{}",
                    tenantId, currentUserId, documentEntity.getId(), documentEntity.getDocumentCode(),
                    documentVersionEntity.getVersionNumber());
            return success(DocumentCreateResponseDTO.builder()
                    .documentCode(documentEntity.getDocumentCode())
                    .versionNumber(documentVersionEntity.getVersionNumber())
                    .parseStatus(documentVersionEntity.getParseStatus().name())
                    .build());
        } catch (BusinessException exception) {
            cleanupOrphanFile(tenantId, fileEntity, documentCreated);
            log.error("创建文档业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (IOException exception) {
            cleanupOrphanFile(tenantId, fileEntity, documentCreated);
            log.error("创建文档读取上传文件异常 tenantId:{} error:{}", tenantId, exception.getMessage(), exception);
            return failure(ResponseCode.FILE_UPLOAD_FAILED);
        } catch (Exception exception) {
            cleanupOrphanFile(tenantId, fileEntity, documentCreated);
            log.error("创建文档系统异常 tenantId:{}", tenantId, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询当前租户下已创建且可用的文档列表。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/tenants/1/documents" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping
    public Response<DocumentListResponseDTO> listDocuments(@PathVariable("tenantId") Long tenantId) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("查询文档列表开始 tenantId:{} userId:{}", tenantId, currentUserId);
            membershipService.assertActiveMember(currentUserId, tenantId);
            List<DocumentResponseDTO> documents = documentService.listActiveDocuments(tenantId).stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
            log.info("查询文档列表完成 tenantId:{} userId:{} documentCount:{}", tenantId, currentUserId, documents.size());
            return success(DocumentListResponseDTO.builder().documents(documents).build());
        } catch (BusinessException exception) {
            log.error("查询文档列表业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询文档列表系统异常 tenantId:{}", tenantId, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询文档详情及当前版本解析状态。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/tenants/1/documents/doc_xxx" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/{documentCode}")
    public Response<DocumentResponseDTO> getDocument(@PathVariable("tenantId") Long tenantId,
                                                      @PathVariable("documentCode") String documentCode) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("查询文档详情开始 tenantId:{} userId:{} documentCode:{}", tenantId, currentUserId, documentCode);
            membershipService.assertActiveMember(currentUserId, tenantId);
            DocumentEntity documentEntity = documentService.getActiveDocument(tenantId, documentCode);
            DocumentResponseDTO response = toResponse(documentEntity);
            log.info("查询文档详情完成 tenantId:{} userId:{} documentCode:{}", tenantId, currentUserId, documentCode);
            return success(response);
        } catch (BusinessException exception) {
            log.error("查询文档详情业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询文档详情系统异常 tenantId:{} documentCode:{}", tenantId, documentCode, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询当前版本的异步解析状态，供上传客户端和评测执行器轮询。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/tenants/1/documents/doc_xxx/processing-status" \\
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/{documentCode}/processing-status")
    public Response<DocumentProcessingStatusResponseDTO> getProcessingStatus(
            @PathVariable("tenantId") Long tenantId,
            @PathVariable("documentCode") String documentCode) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("查询文档处理状态开始 tenantId:{} userId:{} documentCode:{}", tenantId, currentUserId, documentCode);
            membershipService.assertActiveMember(currentUserId, tenantId);
            DocumentEntity documentEntity = documentService.getActiveDocument(tenantId, documentCode);
            DocumentVersionEntity version = documentService.getCurrentVersion(documentEntity);
            DocumentProcessingStatusResponseDTO response = DocumentProcessingStatusResponseDTO.builder()
                    .documentCode(documentEntity.getDocumentCode())
                    .versionNumber(version.getVersionNumber())
                    .documentStatus(documentEntity.getStatus().name())
                    .parseStatus(version.getParseStatus().name())
                    .extractedFileId(version.getExtractedFileId())
                    .parserVersion(version.getParserVersion())
                    .parsedAt(version.getParsedAt())
                    .build();
            log.info("查询文档处理状态完成 tenantId:{} userId:{} documentCode:{} parseStatus:{}",
                    tenantId, currentUserId, documentCode, response.getParseStatus());
            return success(response);
        } catch (BusinessException exception) {
            log.error("查询文档处理状态业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询文档处理状态系统异常 tenantId:{} documentCode:{}", tenantId, documentCode, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    private DocumentResponseDTO toResponse(DocumentEntity documentEntity) {
        DocumentVersionEntity documentVersionEntity = documentService.getCurrentVersion(documentEntity);
        return DocumentResponseDTO.builder()
                .documentId(documentEntity.getId())
                .documentCode(documentEntity.getDocumentCode())
                .tenantId(documentEntity.getTenantId())
                .ownerUserId(documentEntity.getOwnerUserId())
                .title(documentEntity.getTitle())
                .documentType(documentEntity.getDocumentType().name())
                .status(documentEntity.getStatus().name())
                .currentVersion(documentEntity.getCurrentVersion())
                .sourceFileId(documentEntity.getSourceFileId())
                .currentVersionFileId(documentVersionEntity.getFileId())
                .parseStatus(documentVersionEntity.getParseStatus().name())
                .extractedFileId(documentVersionEntity.getExtractedFileId())
                .parserVersion(documentVersionEntity.getParserVersion())
                .parsedAt(documentVersionEntity.getParsedAt())
                .createdAt(documentEntity.getCreatedAt())
                .updatedAt(documentEntity.getUpdatedAt())
                .build();
    }

    private void validateSupportedFile(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new BusinessException(ResponseCode.EMPTY_FILE);
        }
        String normalizedFilename = file.getOriginalFilename().toLowerCase();
        if (!(normalizedFilename.endsWith(".txt") || normalizedFilename.endsWith(".md")
                || normalizedFilename.endsWith(".pdf") || normalizedFilename.endsWith(".docx"))) {
            throw new BusinessException(ResponseCode.DOCUMENT_TYPE_UNSUPPORTED);
        }
    }

    private String resolveTitle(String title, String originalFilename) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        int suffixIndex = originalFilename == null ? -1 : originalFilename.lastIndexOf('.');
        return suffixIndex > 0 ? originalFilename.substring(0, suffixIndex) : originalFilename;
    }

    private void cleanupOrphanFile(Long tenantId, FileEntity fileEntity, boolean documentCreated) {
        if (fileEntity == null || documentCreated) {
            return;
        }
        try {
            fileService.deleteFile(tenantId, fileEntity.getId());
        } catch (Exception exception) {
            log.warn("创建文档失败后清理孤儿文件异常 tenantId:{} fileId:{} error:{}",
                    tenantId, fileEntity.getId(), exception.getMessage(), exception);
        }
    }
}
