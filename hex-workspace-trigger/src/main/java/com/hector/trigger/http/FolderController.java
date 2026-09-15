package com.hector.trigger.http;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.service.IDocumentService;
import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.service.IFolderService;
import com.hector.domain.workspace.service.IWorkspaceService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.trigger.api.dto.DirectoryResponseDTO;
import com.hector.trigger.api.dto.DocumentResponseDTO;
import com.hector.trigger.api.dto.FolderCommandRequestDTO;
import com.hector.trigger.api.dto.FolderResponseDTO;
import com.hector.trigger.api.request.Request;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Workspace 文件夹接口控制器。 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/folders")
public class FolderController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(FolderController.class);

    @Resource
    private IWorkspaceService workspaceService;

    @Resource
    private IFolderService folderService;

    @Resource
    private IDocumentService documentService;

    /**
     * 获取 Workspace 根目录。
     * curl -X GET "http://127.0.0.1:8098/api/v1/workspaces/1/folders/root" -H "Authorization: Bearer 登录令牌"
     */
    @GetMapping("/root")
    public Response<FolderResponseDTO> root(@PathVariable("workspaceId") Long workspaceId) {
        return execute("获取Workspace根目录", workspaceId, () -> {
            assertOwner(workspaceId);
            FolderResponseDTO response = toResponse(folderService.ensureRootFolder(workspaceId));
            log.info("获取Workspace根目录完成 workspaceId:{} folderId:{}", workspaceId, response.getFolderId());
            return success(response);
        });
    }

    /**
     * 查询指定文件夹下的直属文件夹和文档。
     * curl -X GET "http://127.0.0.1:8098/api/v1/workspaces/1/folders/10/children" -H "Authorization: Bearer 登录令牌"
     */
    @GetMapping("/{folderId}/children")
    public Response<DirectoryResponseDTO> children(@PathVariable("workspaceId") Long workspaceId,
                                                    @PathVariable("folderId") Long folderId) {
        return execute("查询目录内容", workspaceId, () -> {
            assertOwner(workspaceId);
            DirectoryResponseDTO response = DirectoryResponseDTO.builder()
                    .currentFolder(toResponse(folderService.getActiveFolder(workspaceId, folderId)))
                    .folders(folderService.listChildren(workspaceId, folderId).stream()
                            .map(this::toResponse)
                            .collect(Collectors.toList()))
                    .documents(documentService.listVisibleDocumentsByParentId(workspaceId, folderId).stream()
                            .map(this::toDocumentResponse)
                            .collect(Collectors.toList()))
                    .build();
            log.info("查询目录内容完成 workspaceId:{} folderId:{} folderCount:{} documentCount:{}",
                    workspaceId, folderId, response.getFolders().size(), response.getDocuments().size());
            return success(response);
        });
    }

    /**
     * 在指定父目录中新建文件夹。
     * curl -X POST "http://127.0.0.1:8098/api/v1/workspaces/1/folders" -H "Content-Type: application/json" -d '{"data":{"parentId":10,"name":"项目资料"}}'
     */
    @PostMapping
    public Response<FolderResponseDTO> create(@PathVariable("workspaceId") Long workspaceId,
                                              @RequestBody Request<FolderCommandRequestDTO> request) {
        return execute("新建文件夹", workspaceId, () -> {
            FolderCommandRequestDTO data = requireData(request);
            assertOwner(workspaceId);
            FolderResponseDTO response = toResponse(
                    folderService.createFolder(workspaceId, data.getParentId(), data.getName()));
            log.info("新建文件夹完成 workspaceId:{} folderId:{}", workspaceId, response.getFolderId());
            return success(response);
        });
    }

    /**
     * 重命名文件夹。
     * curl -X PATCH "http://127.0.0.1:8098/api/v1/workspaces/1/folders/10/name" -H "Content-Type: application/json" -d '{"data":{"name":"新名称"}}'
     */
    @PatchMapping("/{folderId}/name")
    public Response<FolderResponseDTO> rename(@PathVariable("workspaceId") Long workspaceId,
                                              @PathVariable("folderId") Long folderId,
                                              @RequestBody Request<FolderCommandRequestDTO> request) {
        return execute("重命名文件夹", workspaceId, () -> {
            assertOwner(workspaceId);
            FolderResponseDTO response = toResponse(folderService.renameFolder(
                    workspaceId, folderId, requireData(request).getName()));
            log.info("重命名文件夹完成 workspaceId:{} folderId:{}", workspaceId, folderId);
            return success(response);
        });
    }

    /**
     * 移动文件夹到新的父目录。
     * curl -X PATCH "http://127.0.0.1:8098/api/v1/workspaces/1/folders/10/parent" -H "Content-Type: application/json" -d '{"data":{"targetParentId":20}}'
     */
    @PatchMapping("/{folderId}/parent")
    public Response<FolderResponseDTO> move(@PathVariable("workspaceId") Long workspaceId,
                                            @PathVariable("folderId") Long folderId,
                                            @RequestBody Request<FolderCommandRequestDTO> request) {
        return execute("移动文件夹", workspaceId, () -> {
            assertOwner(workspaceId);
            FolderResponseDTO response = toResponse(folderService.moveFolder(
                    workspaceId, folderId, requireData(request).getTargetParentId()));
            log.info("移动文件夹完成 workspaceId:{} folderId:{} parentId:{}",
                    workspaceId, folderId, response.getParentId());
            return success(response);
        });
    }

    /**
     * 逻辑删除文件夹及其目录树资源。
     * curl -X DELETE "http://127.0.0.1:8098/api/v1/workspaces/1/folders/10" -H "Authorization: Bearer 登录令牌"
     */
    @DeleteMapping("/{folderId}")
    public Response<Boolean> delete(@PathVariable("workspaceId") Long workspaceId,
                                    @PathVariable("folderId") Long folderId) {
        return execute("删除目录树", workspaceId, () -> {
            assertOwner(workspaceId);
            folderService.deleteFolderTree(workspaceId, folderId);
            log.info("删除目录树完成 workspaceId:{} folderId:{}", workspaceId, folderId);
            return success(true);
        });
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

    private void assertOwner(Long workspaceId) {
        workspaceService.assertOwner(workspaceId, SecurityUserUtil.getCurrentUserId());
    }

    private FolderResponseDTO toResponse(FolderEntity folder) {
        return FolderResponseDTO.builder()
                .folderId(folder.getId())
                .folderCode(folder.getFolderCode())
                .workspaceId(folder.getWorkspaceId())
                .parentId(folder.getParentId())
                .name(folder.getName())
                .root(folder.isRoot())
                .status(folder.getStatus().name())
                .build();
    }

    private DocumentResponseDTO toDocumentResponse(DocumentEntity document) {
        return DocumentResponseDTO.builder()
                .documentCode(document.getDocumentCode())
                .workspaceId(document.getWorkspaceId())
                .parentId(document.getParentId())
                .ownerUserId(document.getOwnerUserId())
                .title(document.getTitle())
                .fileName(document.getFileName())
                .documentType(document.getDocumentType().name())
                .status(document.getStatus().name())
                .currentVersionNo(document.getCurrentVersionNo())
                .deletedAt(document.getDeletedAt())
                .build();
    }
}
