package com.hector.trigger.http;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.service.IDocumentService;
import com.hector.domain.workspace.service.IWorkspaceService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.trigger.api.dto.DocumentCreateResponseDTO;
import com.hector.trigger.api.dto.DocumentListResponseDTO;
import com.hector.trigger.api.dto.DocumentResponseDTO;
import com.hector.trigger.api.dto.DocumentCommandRequestDTO;
import com.hector.trigger.api.request.Request;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 文档接口控制器。
 * Controller 负责 Workspace Owner 校验与文档基础操作编排。
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents")
public class DocumentController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    @Resource
    private IWorkspaceService workspaceService;

    @Resource
    private IDocumentService documentService;

    /**
     * 上传文件并创建文档。
     * 创建成功即返回文档编码；对象转正前文档处于 CREATING 状态，不可读取。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/workspaces/1/documents" \
     *   -H "Authorization: Bearer 替换为实际登录令牌" \
     *   -F "file=@./demo.pdf" \
     *   -F "title=演示文档"
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<DocumentCreateResponseDTO> createDocument(@PathVariable("workspaceId") Long workspaceId,
                                                               @RequestPart("file") MultipartFile file,
                                                               @RequestParam("parentId") Long parentId) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            String currentUsername = SecurityUserUtil.getCurrentUsername();
            log.info("创建文档开始 workspaceId:{} userId:{} username:{} originalFilename:{}",
                    workspaceId, currentUserId, currentUsername, file == null ? null : file.getOriginalFilename());

            // 1. 先确认当前用户是 Workspace Owner；协作者写入必须由资源 EDIT 权限单独放行。
            workspaceService.assertOwner(workspaceId, currentUserId);
            validateUpload(file);
            // 2. 任意格式的本地内容直接导入为云文档，平台不产生第三种可独立管理的资源。
            DocumentEntity documentEntity = documentService.importDocument(
                    workspaceId, currentUserId, parentId, file.getOriginalFilename(),
                    file.getContentType(), file.getBytes());
            log.info("创建文档完成 workspaceId:{} userId:{} documentId:{} documentCode:{}",
                    workspaceId, currentUserId, documentEntity.getId(), documentEntity.getDocumentCode());
            return success(DocumentCreateResponseDTO.builder()
                    .documentCode(documentEntity.getDocumentCode())
                    .build());
        } catch (BusinessException exception) {
            log.error("创建文档业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (IOException exception) {
            log.error("创建文档读取上传文件异常 workspaceId:{} error:{}", workspaceId, exception.getMessage(), exception);
            return failure(ResponseCode.DOCUMENT_CONTENT_STORE_FAILED);
        } catch (Exception exception) {
            log.error("创建文档系统异常 workspaceId:{}", workspaceId, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 在平台内新建 Markdown 文档。
     * curl -X POST "http://127.0.0.1:8098/api/v1/workspaces/1/documents/markdown" -H "Content-Type: application/json" -d '{"data":{"parentId":10,"fileName":"说明.md","content":"# 标题"}}'
     */
    @PostMapping("/markdown")
    public Response<DocumentResponseDTO> createMarkdown(@PathVariable("workspaceId") Long workspaceId,
                                                         @RequestBody Request<DocumentCommandRequestDTO> request) {
        return execute("新建Markdown文档", workspaceId, () -> {
            Long userId = SecurityUserUtil.getCurrentUserId();
            workspaceService.assertOwner(workspaceId, userId);
            DocumentCommandRequestDTO data = requireData(request);
            DocumentEntity document = documentService.createMarkdownDocument(
                    workspaceId, userId, data.getParentId(), data.getFileName(), data.getContent());
            log.info("新建Markdown文档完成 workspaceId:{} documentCode:{}", workspaceId, document.getDocumentCode());
            return success(toResponse(document));
        });
    }

    /**
     * 编辑 Markdown 文档正文并替换当前内容。
     * curl -X PUT "http://127.0.0.1:8098/api/v1/workspaces/1/documents/doc_xxx/content" -H "Content-Type: application/json" -d '{"data":{"content":"更新后的内容"}}'
     */
    @PutMapping("/{documentCode}/content")
    public Response<DocumentResponseDTO> editMarkdown(@PathVariable("workspaceId") Long workspaceId,
                                                       @PathVariable("documentCode") String documentCode,
                                                       @RequestBody Request<DocumentCommandRequestDTO> request) {
        return execute("编辑Markdown文档", workspaceId, () -> {
            Long userId = SecurityUserUtil.getCurrentUserId();
            workspaceService.assertOwner(workspaceId, userId);
            DocumentEntity document = documentService.editMarkdownDocument(
                    workspaceId, documentCode, userId, requireData(request).getContent());
            log.info("编辑Markdown文档完成 workspaceId:{} documentCode:{}", workspaceId, documentCode);
            return success(toResponse(document, true));
        });
    }

    /**
     * 重命名文档。
     * curl -X PATCH "http://127.0.0.1:8098/api/v1/workspaces/1/documents/doc_xxx/name" -H "Content-Type: application/json" -d '{"data":{"fileName":"新名称.md"}}'
     */
    @PatchMapping("/{documentCode}/name")
    public Response<DocumentResponseDTO> rename(@PathVariable("workspaceId") Long workspaceId,
                                                @PathVariable("documentCode") String documentCode,
                                                @RequestBody Request<DocumentCommandRequestDTO> request) {
        return execute("重命名文档", workspaceId, () -> {
            workspaceService.assertOwner(workspaceId, SecurityUserUtil.getCurrentUserId());
            DocumentEntity document = documentService.renameDocument(
                    workspaceId, documentCode, requireData(request).getFileName());
            log.info("重命名文档完成 workspaceId:{} documentCode:{} fileName:{}",
                    workspaceId, documentCode, document.getFileName());
            return success(toResponse(document));
        });
    }

    /**
     * 移动文档到新的父目录。
     * curl -X PATCH "http://127.0.0.1:8098/api/v1/workspaces/1/documents/doc_xxx/parent" -H "Content-Type: application/json" -d '{"data":{"targetParentId":20}}'
     */
    @PatchMapping("/{documentCode}/parent")
    public Response<DocumentResponseDTO> move(@PathVariable("workspaceId") Long workspaceId,
                                              @PathVariable("documentCode") String documentCode,
                                              @RequestBody Request<DocumentCommandRequestDTO> request) {
        return execute("移动文档", workspaceId, () -> {
            workspaceService.assertOwner(workspaceId, SecurityUserUtil.getCurrentUserId());
            DocumentEntity document = documentService.moveDocument(
                    workspaceId, documentCode, requireData(request).getTargetParentId());
            log.info("移动文档完成 workspaceId:{} documentCode:{} parentId:{}",
                    workspaceId, documentCode, document.getParentId());
            return success(toResponse(document));
        });
    }

    /**
     * 逻辑删除文档。
     * curl -X DELETE "http://127.0.0.1:8098/api/v1/workspaces/1/documents/doc_xxx" -H "Authorization: Bearer 登录令牌"
     */
    @DeleteMapping("/{documentCode}")
    public Response<DocumentResponseDTO> delete(@PathVariable("workspaceId") Long workspaceId,
                                                @PathVariable("documentCode") String documentCode) {
        return execute("删除文档", workspaceId, () -> {
            workspaceService.assertOwner(workspaceId, SecurityUserUtil.getCurrentUserId());
            DocumentEntity document = documentService.deleteDocument(workspaceId, documentCode);
            log.info("删除文档完成 workspaceId:{} documentCode:{}", workspaceId, documentCode);
            return success(toResponse(document));
        });
    }

    /**
     * 查询当前Workspace下已创建且可用的文档列表。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/workspaces/1/documents" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping
    public Response<DocumentListResponseDTO> listDocuments(@PathVariable("workspaceId") Long workspaceId) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("查询文档列表开始 workspaceId:{} userId:{}", workspaceId, currentUserId);
            workspaceService.assertOwner(workspaceId, currentUserId);
            List<DocumentResponseDTO> documents = documentService.listActiveDocuments(workspaceId).stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
            log.info("查询文档列表完成 workspaceId:{} userId:{} documentCount:{}", workspaceId, currentUserId, documents.size());
            return success(DocumentListResponseDTO.builder().documents(documents).build());
        } catch (BusinessException exception) {
            log.error("查询文档列表业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询文档列表系统异常 workspaceId:{}", workspaceId, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询文档详情与 Markdown 当前正文。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/workspaces/1/documents/doc_xxx" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/{documentCode}")
    public Response<DocumentResponseDTO> getDocument(@PathVariable("workspaceId") Long workspaceId,
                                                      @PathVariable("documentCode") String documentCode) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("查询文档详情开始 workspaceId:{} userId:{} documentCode:{}", workspaceId, currentUserId, documentCode);
            workspaceService.assertOwner(workspaceId, currentUserId);
            DocumentEntity documentEntity = documentService.getActiveDocument(workspaceId, documentCode);
            DocumentResponseDTO response = toResponse(documentEntity, true);
            log.info("查询文档详情完成 workspaceId:{} userId:{} documentCode:{}", workspaceId, currentUserId, documentCode);
            return success(response);
        } catch (BusinessException exception) {
            log.error("查询文档详情业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询文档详情系统异常 workspaceId:{} documentCode:{}", workspaceId, documentCode, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    private DocumentResponseDTO toResponse(DocumentEntity documentEntity) {
        return toResponse(documentEntity, false);
    }

    private DocumentResponseDTO toResponse(DocumentEntity documentEntity, boolean includeContent) {
        DocumentResponseDTO response = DocumentResponseDTO.builder()
                .documentId(documentEntity.getId())
                .documentCode(documentEntity.getDocumentCode())
                .workspaceId(documentEntity.getWorkspaceId())
                .parentId(documentEntity.getParentId())
                .ownerUserId(documentEntity.getOwnerUserId())
                .title(documentEntity.getTitle())
                .fileName(documentEntity.getFileName())
                .source(documentEntity.getDocumentType().name())
                .documentType(documentEntity.getDocumentType().name())
                .status(documentEntity.getStatus().name())
                .createdAt(documentEntity.getCreatedAt())
                .updatedAt(documentEntity.getUpdatedAt())
                .build();
        if (includeContent && documentEntity.getFileName().toLowerCase().endsWith(".md")) {
            byte[] content = documentService.readCurrentContent(
                    documentEntity.getWorkspaceId(), documentEntity.getDocumentCode());
            response.setContent(new String(content, StandardCharsets.UTF_8));
        }
        return response;
    }

    private <T> Response<T> execute(String operation, Long workspaceId, Supplier<Response<T>> action) {
        try {
            log.info("{}开始 workspaceId:{}", operation, workspaceId);
            return action.get();
        } catch (BusinessException exception) {
            log.error("{}业务异常 workspaceId:{} code:{} info:{}",
                    operation, workspaceId, exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("{}系统异常 workspaceId:{}", operation, workspaceId, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new BusinessException(ResponseCode.EMPTY_DOCUMENT_CONTENT);
        }
    }

}
