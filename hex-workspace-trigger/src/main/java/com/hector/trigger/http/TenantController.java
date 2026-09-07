package com.hector.trigger.http;

import com.hector.domain.tenant.model.entity.TenantEntity;
import com.hector.domain.tenant.service.ITenantService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.trigger.api.dto.TenantCreateRequestDTO;
import com.hector.trigger.api.dto.TenantListResponseDTO;
import com.hector.trigger.api.dto.TenantResponseDTO;
import com.hector.trigger.api.request.Request;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 创建租户接口。
     * 当前登录用户直接作为新租户 owner。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/tenants" \
     *   -H "Content-Type: application/json" \
     *   -H "Authorization: Bearer 替换为实际登录令牌" \
     *   -d '{
     *     "data": {
     *       "tenantName": "演示租户",
     *       "description": "示例租户"
     *     }
     *   }'
     */
    @PostMapping
    public Response<TenantResponseDTO> createTenant(@RequestBody Request<TenantCreateRequestDTO> request) {
        try {
            TenantCreateRequestDTO data = requireData(request);
            log.info("创建租户开始 tenantName:{}", data.getTenantName());
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            TenantEntity tenantEntity = tenantService.createTenant(
                    data.getTenantName(),
                    currentUserId,
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
     * curl -X GET "http://127.0.0.1:8098/api/v1/tenants/1" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/{tenantId}")
    public Response<TenantResponseDTO> queryById(@PathVariable("tenantId") Long tenantId) {
        try {
            log.info("按ID查询租户开始 tenantId:{}", tenantId);
            TenantEntity tenantEntity = tenantService.getTenantById(tenantId);
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
     * curl -X GET "http://127.0.0.1:8098/api/v1/tenants/code/tenant_abc123" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/code/{tenantCode}")
    public Response<TenantResponseDTO> queryByCode(@PathVariable("tenantCode") String tenantCode) {
        try {
            log.info("按编码查询租户开始 tenantCode:{}", tenantCode);
            TenantEntity tenantEntity = tenantService.getTenantByCode(tenantCode);
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
     * 基于当前登录用户，查询其创建的租户列表。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/tenants/my" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/my")
    public Response<TenantListResponseDTO> listMyTenants() {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            String currentUsername = SecurityUserUtil.getCurrentUsername();
            log.info("查询当前用户租户列表开始 userId:{} username:{}", currentUserId, currentUsername);
            List<TenantEntity> tenantEntities = tenantService.listTenantsByOwnerUserId(currentUserId);
            log.info("查询当前用户租户列表完成 userId:{} tenantCount:{}", currentUserId, tenantEntities.size());
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
