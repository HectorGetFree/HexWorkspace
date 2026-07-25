package com.hector.trigger.http;

import com.hector.domain.auth.service.IAuthService;
import com.hector.domain.tenant.model.entity.TenantEntity;
import com.hector.domain.tenant.service.ITenantService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import com.hector.trigger.api.dto.TenantCreateRequestDTO;
import com.hector.trigger.api.dto.TenantListRequestDTO;
import com.hector.trigger.api.dto.TenantListResponseDTO;
import com.hector.trigger.api.dto.TenantQueryByCodeRequestDTO;
import com.hector.trigger.api.dto.TenantQueryByIdRequestDTO;
import com.hector.trigger.api.dto.TenantResponseDTO;
import com.hector.trigger.api.request.Request;
import com.hector.trigger.api.response.Response;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 租户接口控制器。
 * 负责对外暴露租户创建、查询和列表能力。
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(TenantController.class);

    @Resource
    private ITenantService tenantService;

    @Resource
    private IAuthService authService;

    /**
     * 创建租户接口。
     * 当前登录用户直接作为新租户 owner。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/tenants/create" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "token": "Bearer 替换为实际登录令牌",
     *       "tenantName": "演示租户",
     *       "description": "示例租户"
     *     }
     *   }'
     */
    @PostMapping("/create")
    public Response<TenantResponseDTO> createTenant(@RequestBody Request<TenantCreateRequestDTO> request) {
        try {
            TenantCreateRequestDTO data = requireData(request);
            log.info("创建租户开始 tenantName:{}", data.getTenantName());
            UserEntity currentUser = authService.getCurrentUser(data.getToken());
            TenantEntity tenantEntity = tenantService.createTenant(
                    data.getTenantName(),
                    currentUser.getId(),
                    data.getDescription());
            log.info("创建租户完成 tenantId:{} tenantCode:{} ownerUserId:{}",
                    tenantEntity.getId(), tenantEntity.getTenantCode(), tenantEntity.getOwnerUserId());
            return success(toResponse(tenantEntity));
        } catch (BusinessException exception) {
            log.error("创建租户业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("创建租户系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 按租户ID查询接口。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/tenants/query-by-id" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "token": "Bearer 替换为实际登录令牌",
     *       "tenantId": 1
     *     }
     *   }'
     */
    @PostMapping("/query-by-id")
    public Response<TenantResponseDTO> queryById(@RequestBody Request<TenantQueryByIdRequestDTO> request) {
        try {
            TenantQueryByIdRequestDTO data = requireData(request);
            log.info("按ID查询租户开始 tenantId:{}", data.getTenantId());
            authService.getCurrentUser(data.getToken());
            TenantEntity tenantEntity = tenantService.getTenantById(data.getTenantId());
            log.info("按ID查询租户完成 tenantId:{} tenantCode:{}", tenantEntity.getId(), tenantEntity.getTenantCode());
            return success(toResponse(tenantEntity));
        } catch (BusinessException exception) {
            log.error("按ID查询租户业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("按ID查询租户系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 按租户编码查询接口。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/tenants/query-by-code" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "token": "Bearer 替换为实际登录令牌",
     *       "tenantCode": "tenant-001"
     *     }
     *   }'
     */
    @PostMapping("/query-by-code")
    public Response<TenantResponseDTO> queryByCode(@RequestBody Request<TenantQueryByCodeRequestDTO> request) {
        try {
            TenantQueryByCodeRequestDTO data = requireData(request);
            log.info("按编码查询租户开始 tenantCode:{}", data.getTenantCode());
            authService.getCurrentUser(data.getToken());
            TenantEntity tenantEntity = tenantService.getTenantByCode(data.getTenantCode());
            log.info("按编码查询租户完成 tenantId:{} tenantCode:{}", tenantEntity.getId(), tenantEntity.getTenantCode());
            return success(toResponse(tenantEntity));
        } catch (BusinessException exception) {
            log.error("按编码查询租户业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("按编码查询租户系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询当前用户名下租户列表接口。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/tenants/my-list" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "token": "Bearer 替换为实际登录令牌"
     *     }
     *   }'
     */
    @PostMapping("/my-list")
    public Response<TenantListResponseDTO> listMyTenants(@RequestBody Request<TenantListRequestDTO> request) {
        try {
            TenantListRequestDTO data = requireData(request);
            UserEntity currentUser = authService.getCurrentUser(data.getToken());
            log.info("查询当前用户租户列表开始 userId:{} username:{}", currentUser.getId(), currentUser.getUsername());
            List<TenantEntity> tenantEntities = tenantService.listTenantsByOwnerUserId(currentUser.getId());
            log.info("查询当前用户租户列表完成 userId:{} tenantCount:{}", currentUser.getId(), tenantEntities.size());
            return success(TenantListResponseDTO.builder()
                    .tenants(tenantEntities.stream().map(this::toResponse).collect(Collectors.toList()))
                    .build());
        } catch (BusinessException exception) {
            log.error("查询当前用户租户列表业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询当前用户租户列表系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    private TenantResponseDTO toResponse(TenantEntity tenantEntity) {
        return TenantResponseDTO.builder()
                .tenantId(tenantEntity.getId())
                .tenantCode(tenantEntity.getTenantCode())
                .tenantName(tenantEntity.getTenantName())
                .status(tenantEntity.getStatus().name())
                .ownerUserId(tenantEntity.getOwnerUserId())
                .description(tenantEntity.getDescription())
                .build();
    }
}
