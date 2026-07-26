package com.hector.trigger.http;

import com.hector.domain.auth.service.IAuthService;
import com.hector.domain.membership.model.entity.MembershipEntity;
import com.hector.domain.membership.service.IMembershipService;
import com.hector.domain.tenant.model.entity.TenantEntity;
import com.hector.domain.tenant.service.ITenantService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.service.IUserService;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import com.hector.trigger.api.dto.MembershipAddRequestDTO;
import com.hector.trigger.api.dto.MembershipInitOwnerRequestDTO;
import com.hector.trigger.api.dto.MembershipListByTenantRequestDTO;
import com.hector.trigger.api.dto.MembershipListResponseDTO;
import com.hector.trigger.api.dto.MembershipMyListRequestDTO;
import com.hector.trigger.api.dto.MembershipRemoveRequestDTO;
import com.hector.trigger.api.dto.MembershipResponseDTO;
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
 * 成员关系接口控制器。
 * 负责对外暴露租户成员初始化、添加、查询、角色变更、移除和退出能力。
 */
@RestController
@RequestMapping("/api/v1/members")
public class MembershipController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(MembershipController.class);

    @Resource
    private IMembershipService membershipService;

    @Resource
    private IAuthService authService;

    @Resource
    private IUserService userService;

    @Resource
    private ITenantService tenantService;

    /**
     * 初始化租户拥有者成员关系接口。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/members/init-owner" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "token": "Bearer 替换为实际登录令牌",
     *       "tenantId": 1
     *     }
     *   }'
     */
    @PostMapping("/init-owner")
    public Response<MembershipResponseDTO> initOwnerMember(@RequestBody Request<MembershipInitOwnerRequestDTO> request) {
        try {
            MembershipInitOwnerRequestDTO data = requireData(request);
            UserEntity currentUser = authService.getCurrentUser(data.getToken());
            log.info("初始化租户拥有者成员关系开始 operatorUserId:{} tenantId:{}", currentUser.getId(), data.getTenantId());
            MembershipEntity membershipEntity = membershipService.initOwnerMember(currentUser.getId(), data.getTenantId());
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
     * curl -X POST "http://127.0.0.1:8098/api/v1/members/add" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "token": "Bearer 替换为实际登录令牌",
     *       "tenantId": 1,
     *       "userId": 2
     *     }
     *   }'
     */
    @PostMapping("/add")
    public Response<MembershipResponseDTO> addMember(@RequestBody Request<MembershipAddRequestDTO> request) {
        try {
            MembershipAddRequestDTO data = requireData(request);
            UserEntity currentUser = authService.getCurrentUser(data.getToken());
            log.info("添加成员开始 operatorUserId:{} tenantId:{} targetUserId:{}",
                    currentUser.getId(), data.getTenantId(), data.getUserId());
            MembershipEntity membershipEntity = membershipService.addMember(currentUser.getId(), data.getTenantId(), data.getUserId());
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
     * curl -X POST "http://127.0.0.1:8098/api/v1/members/list-by-tenant" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "token": "Bearer 替换为实际登录令牌",
     *       "tenantId": 1
     *     }
     *   }'
     */
    @PostMapping("/list-by-tenant")
    public Response<MembershipListResponseDTO> listByTenant(@RequestBody Request<MembershipListByTenantRequestDTO> request) {
        try {
            MembershipListByTenantRequestDTO data = requireData(request);
            UserEntity currentUser = authService.getCurrentUser(data.getToken());
            log.info("查询租户成员列表开始 operatorUserId:{} tenantId:{}", currentUser.getId(), data.getTenantId());
            List<MembershipEntity> membershipEntities = membershipService.listMembersByTenantId(currentUser.getId(), data.getTenantId());
            log.info("查询租户成员列表完成 tenantId:{} membershipCount:{}", data.getTenantId(), membershipEntities.size());
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
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/members/my-list" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "token": "Bearer 替换为实际登录令牌"
     *     }
     *   }'
     */
    @PostMapping("/my-list")
    public Response<MembershipListResponseDTO> listMyMemberships(@RequestBody Request<MembershipMyListRequestDTO> request) {
        try {
            MembershipMyListRequestDTO data = requireData(request);
            UserEntity currentUser = authService.getCurrentUser(data.getToken());
            log.info("查询当前用户成员关系列表开始 userId:{} username:{}", currentUser.getId(), currentUser.getUsername());
            List<MembershipEntity> membershipEntities = membershipService.listMembershipsByUserId(currentUser.getId());
            log.info("查询当前用户成员关系列表完成 userId:{} membershipCount:{}", currentUser.getId(), membershipEntities.size());
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
     * curl -X POST "http://127.0.0.1:8098/api/v1/members/remove" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "token": "Bearer 替换为实际登录令牌",
     *       "tenantId": 1,
     *       "targetUserId": 2
     *     }
     *   }'
     */
    @PostMapping("/remove")
    public Response<MembershipResponseDTO> removeMember(@RequestBody Request<MembershipRemoveRequestDTO> request) {
        try {
            MembershipRemoveRequestDTO data = requireData(request);
            UserEntity currentUser = authService.getCurrentUser(data.getToken());
            log.info("移除成员开始 operatorUserId:{} tenantId:{} targetUserId:{}",
                    currentUser.getId(), data.getTenantId(), data.getTargetUserId());
            MembershipEntity membershipEntity = membershipService.removeMember(
                    currentUser.getId(),
                    data.getTenantId(),
                    data.getTargetUserId());
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
