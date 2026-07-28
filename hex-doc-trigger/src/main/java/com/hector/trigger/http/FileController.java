package com.hector.trigger.http;

import com.hector.domain.file.model.entity.FileEntity;
import com.hector.domain.file.service.IFileService;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import com.hector.trigger.api.dto.FileListResponseDTO;
import com.hector.trigger.api.dto.FileResponseDTO;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件接口控制器。
 * 负责租户共享文件的上传、查询和删除。
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/files")
public class FileController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    @Resource
    private IFileService fileService;

    /**
     * 上传租户共享文件接口。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/tenants/1/files" \
     *   -H "Authorization: Bearer 替换为实际登录令牌" \
     *   -F "file=@/path/to/demo.pdf"
     */
    @PostMapping
    public Response<FileResponseDTO> upload(@PathVariable("tenantId") Long tenantId,
                                             @RequestParam("file") MultipartFile file) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("上传租户文件开始 operatorUserId:{} tenantId:{} originalFilename:{}",
                    currentUserId, tenantId, file.getOriginalFilename());
            FileEntity fileEntity = fileService.uploadTenantFile(
                    currentUserId,
                    tenantId,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes());
            log.info("上传租户文件完成 fileId:{} fileCode:{} tenantId:{}",
                    fileEntity.getId(), fileEntity.getFileCode(), fileEntity.getTenantId());
            return success(toResponse(fileEntity));
        } catch (BusinessException exception) {
            log.error("上传租户文件业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("上传租户文件系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询租户文件列表接口。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/tenants/1/files" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping
    public Response<FileListResponseDTO> list(@PathVariable("tenantId") Long tenantId) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("查询租户文件列表开始 operatorUserId:{} tenantId:{}", currentUserId, tenantId);
            List<FileEntity> fileEntities = fileService.listTenantFiles(currentUserId, tenantId);
            log.info("查询租户文件列表完成 tenantId:{} fileCount:{}", tenantId, fileEntities.size());
            return success(FileListResponseDTO.builder()
                    .files(fileEntities.stream().map(this::toResponse).collect(Collectors.toList()))
                    .build());
        } catch (BusinessException exception) {
            log.error("查询租户文件列表业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询租户文件列表系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询租户文件详情接口。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/tenants/1/files/file_xxx" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/{fileCode}")
    public Response<FileResponseDTO> detail(@PathVariable("tenantId") Long tenantId,
                                            @PathVariable("fileCode") String fileCode) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("查询租户文件详情开始 operatorUserId:{} tenantId:{} fileCode:{}",
                    currentUserId, tenantId, fileCode);
            FileEntity fileEntity = fileService.getTenantFileDetail(currentUserId, tenantId, fileCode);
            log.info("查询租户文件详情完成 fileId:{} fileCode:{} tenantId:{}",
                    fileEntity.getId(), fileEntity.getFileCode(), fileEntity.getTenantId());
            return success(toResponse(fileEntity));
        } catch (BusinessException exception) {
            log.error("查询租户文件详情业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询租户文件详情系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 删除租户文件接口。
     *
     * curl 示例：
     * curl -X DELETE "http://127.0.0.1:8098/api/v1/tenants/1/files/file_xxx" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @DeleteMapping("/{fileCode}")
    public Response<FileResponseDTO> delete(@PathVariable("tenantId") Long tenantId,
                                            @PathVariable("fileCode") String fileCode) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("删除租户文件开始 operatorUserId:{} tenantId:{} fileCode:{}",
                    currentUserId, tenantId, fileCode);
            FileEntity fileEntity = fileService.deleteTenantFile(currentUserId, tenantId, fileCode);
            log.info("删除租户文件完成 fileId:{} fileCode:{} tenantId:{}",
                    fileEntity.getId(), fileEntity.getFileCode(), fileEntity.getTenantId());
            return success(toResponse(fileEntity));
        } catch (BusinessException exception) {
            log.error("删除租户文件业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("删除租户文件系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    private FileResponseDTO toResponse(FileEntity fileEntity) {
        return FileResponseDTO.builder()
                .fileId(fileEntity.getId())
                .fileCode(fileEntity.getFileCode())
                .tenantId(fileEntity.getTenantId())
                .scope(fileEntity.getScope().name())
                .creatorUserId(fileEntity.getCreatorUserId())
                .fileName(fileEntity.getFileName())
                .originName(fileEntity.getOriginName())
                .fileSuffix(fileEntity.getFileSuffix())
                .contentType(fileEntity.getContentType())
                .fileSize(fileEntity.getFileSize())
                .storageType(fileEntity.getStorageType())
                .bucketName(fileEntity.getBucketName())
                .storagePath(fileEntity.getStoragePath())
                .fileHash(fileEntity.getFileHash())
                .status(fileEntity.getStatus().name())
                .createdAt(fileEntity.getCreatedAt())
                .updatedAt(fileEntity.getUpdatedAt())
                .build();
    }
}
