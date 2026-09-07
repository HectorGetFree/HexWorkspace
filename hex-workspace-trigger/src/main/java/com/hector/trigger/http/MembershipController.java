package com.hector.trigger.http;

import com.hector.domain.membership.model.entity.MembershipEntity;
import com.hector.domain.membership.service.IMembershipService;
import com.hector.domain.tenant.model.entity.TenantEntity;
import com.hector.domain.tenant.service.ITenantService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.service.IUserService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.trigger.api.dto.MembershipAddRequestDTO;
import com.hector.trigger.api.dto.MembershipListResponseDTO;
import com.hector.trigger.api.dto.MembershipResponseDTO;
import com.hector.trigger.api.request.Request;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 成员关系接口控制器。
 * 负责对外暴露租户成员初始化、添加、查询和移除能力。
 */
@RestController
@RequestMapping("/api/v1")
public class MembershipController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(MembershipController.class);

    @Resource
    private IMembershipService membershipService;

    @Resource
    private IUserService userService;

    @Resource
    private ITenantService tenantService;

    /**
     * 初始化租户拥有者成员关系接口。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/tenants/1/members/owner" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @PostMapping("/tenants/{tenantId}/members/owner")
    public Response<MembershipResponseDTO> initOwnerMember(@PathVariable("tenantId") Long tenantId) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("初始化租户拥有者成员关系开始 operatorUserId:{} tenantId:{}", currentUserId, tenantId);
            MembershipEntity membershipEntity = membershipService.initOwnerMember(currentUserId, tenantId);
            log.info("初始化租户拥有者成员关系完成 membershipId:{} tenantId:{} userId:{}",
                    membershipEntity.getId(), membershipEntity.getTenantId(), membershipEntity.getUserId());
            return success(toResponse(membershipEntity));
        } catch (BusinessException exception) {
            log.error("初始化租户拥有者成员关系业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("初始化租户拥有者成员关系系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 添加成员接口。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/tenants/1/members" \
     *   -H "Content-Type: application/json" \
     *   -H "Authorization: Bearer 替换为实际登录令牌" \
     *   -d '{
     *     "data": {
     *       "userId": 2
     *     }
     *   }'
     */
    @PostMapping("/tenants/{tenantId}/members")
    public Response<MembershipResponseDTO> addMember(@PathVariable("tenantId") Long tenantId,
                                                     @RequestBody Request<MembershipAddRequestDTO> request) {
        try {
            MembershipAddRequestDTO data = requireData(request);
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("添加成员开始 operatorUserId:{} tenantId:{} targetUserId:{}",
                    currentUserId, tenantId, data.getUserId());
            MembershipEntity membershipEntity = membershipService.addMember(currentUserId, tenantId, data.getUserId());
            log.info("添加成员完成 membershipId:{} tenantId:{} targetUserId:{}",
                    membershipEntity.getId(), membershipEntity.getTenantId(), membershipEntity.getUserId());
            return success(toResponse(membershipEntity));
        } catch (BusinessException exception) {
            log.error("添加成员业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("添加成员系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询租户成员列表接口。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/tenants/1/members" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/tenants/{tenantId}/members")
    public Response<MembershipListResponseDTO> listByTenant(@PathVariable("tenantId") Long tenantId) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("查询租户成员列表开始 operatorUserId:{} tenantId:{}", currentUserId, tenantId);
            List<MembershipEntity> membershipEntities = membershipService.listMembersByTenantId(currentUserId, tenantId);
            log.info("查询租户成员列表完成 tenantId:{} membershipCount:{}", tenantId, membershipEntities.size());
            return success(MembershipListResponseDTO.builder()
                    .memberships(membershipEntities.stream().map(this::toResponse).collect(Collectors.toList()))
                    .build());
        } catch (BusinessException exception) {
            log.error("查询租户成员列表业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询租户成员列表系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询当前用户所在租户中成员关系列表接口。
     * 基于当前登录用户，查询其在不同租户下的成员身份信息。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/members/my" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/my")
    public Response<MembershipListResponseDTO> listMyMemberships() {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            String currentUsername = SecurityUserUtil.getCurrentUsername();
            log.info("查询当前用户成员关系列表开始 userId:{} username:{}", currentUserId, currentUsername);
            List<MembershipEntity> membershipEntities = membershipService.listMembershipsByUserId(currentUserId);
            log.info("查询当前用户成员关系列表完成 userId:{} membershipCount:{}", currentUserId, membershipEntities.size());
            return success(MembershipListResponseDTO.builder()
                    .memberships(membershipEntities.stream().map(this::toResponse).collect(Collectors.toList()))
                    .build());
        } catch (BusinessException exception) {
            log.error("查询当前用户成员关系列表业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询当前用户成员关系列表系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 移除成员接口。
     *
     * curl 示例：
     * curl -X DELETE "http://127.0.0.1:8098/api/v1/tenants/1/members/2" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @DeleteMapping("/tenants/{tenantId}/members/{targetUserId}")
    public Response<MembershipResponseDTO> removeMember(@PathVariable("tenantId") Long tenantId,
                                                        @PathVariable("targetUserId") Long targetUserId) {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("移除成员开始 operatorUserId:{} tenantId:{} targetUserId:{}",
                    currentUserId, tenantId, targetUserId);
            MembershipEntity membershipEntity = membershipService.removeMember(
                    currentUserId,
                    tenantId,
                    targetUserId);
            log.info("移除成员完成 membershipId:{} targetUserId:{}",
                    membershipEntity.getId(), membershipEntity.getUserId());
            return success(toResponse(membershipEntity));
        } catch (BusinessException exception) {
            log.error("移除成员业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("移除成员系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    private MembershipResponseDTO toResponse(MembershipEntity membershipEntity) {
        UserEntity userEntity = userService.getCurrentUser(membershipEntity.getUserId());
        TenantEntity tenantEntity = tenantService.getTenantById(membershipEntity.getTenantId());
        return MembershipResponseDTO.builder()
                .membershipId(membershipEntity.getId())
                .userId(membershipEntity.getUserId())
                .username(userEntity.getUsername())
                .tenantId(membershipEntity.getTenantId())
                .tenantCode(tenantEntity.getTenantCode())
                .tenantName(tenantEntity.getTenantName())
                .role(membershipEntity.getRole().name())
                .status(membershipEntity.getStatus().name())
                .joinedAt(membershipEntity.getJoinedAt())
                .build();
    }
}
