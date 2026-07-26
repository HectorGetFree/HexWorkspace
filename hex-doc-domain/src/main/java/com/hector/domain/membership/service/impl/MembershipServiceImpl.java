package com.hector.domain.membership.service.impl;

import com.hector.domain.membership.model.entity.MembershipEntity;
import com.hector.domain.membership.model.valobj.MembershipRoleVO;
import com.hector.domain.membership.model.valobj.MembershipStatusVO;
import com.hector.domain.membership.repository.IMembershipRepository;
import com.hector.domain.membership.service.IMembershipService;
import com.hector.domain.tenant.model.entity.TenantEntity;
import com.hector.domain.tenant.repository.ITenantRepository;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 成员关系领域服务实现。
 */
@Service
public class MembershipServiceImpl implements IMembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipServiceImpl.class);

    @Resource
    private IMembershipRepository membershipRepository;

    @Resource
    private ITenantRepository tenantRepository;

    @Resource
    private IUserRepository userRepository;

    @Override
    @Transactional
    public MembershipEntity initOwnerMember(Long operatorUserId, Long tenantId) {
        log.info("成员关系领域服务-初始化租户拥有者成员开始 operatorUserId:{} tenantId:{}", operatorUserId, tenantId);
        Long normalizedOperatorUserId = normalizeUserId(operatorUserId);
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        assertOwnerOperator(tenantEntity, normalizedOperatorUserId);

        MembershipEntity membershipEntity = membershipRepository.findByUserIdAndTenantId(tenantEntity.getOwnerUserId(), tenantEntity.getId())
                .map(existingMembership -> {
                    MembershipEntity updatedMembership = rebuildMembership(
                            existingMembership,
                            MembershipRoleVO.ADMIN,
                            MembershipStatusVO.ACTIVE,
                            resolveJoinedAt(existingMembership.getJoinedAt()),
                            existingMembership.getInvitedAt(),
                            LocalDateTime.now());
                    membershipRepository.update(updatedMembership);
                    return updatedMembership;
                })
                .orElseGet(() -> membershipRepository.save(buildMembership(
                        tenantEntity.getOwnerUserId(),
                        tenantEntity.getId(),
                        MembershipRoleVO.ADMIN,
                        MembershipStatusVO.ACTIVE,
                        LocalDateTime.now(),
                        null)));

        log.info("成员关系领域服务-初始化租户拥有者成员完成 membershipId:{} tenantId:{} ownerUserId:{}",
                membershipEntity.getId(), membershipEntity.getTenantId(), membershipEntity.getUserId());
        return membershipEntity;
    }

    @Override
    @Transactional
    public MembershipEntity addMember(Long operatorUserId, Long tenantId, Long targetUserId) {
        log.info("成员关系领域服务-添加成员开始 operatorUserId:{} tenantId:{} targetUserId:{}",
                operatorUserId, tenantId, targetUserId);
        Long normalizedOperatorUserId = normalizeUserId(operatorUserId);
        Long normalizedTargetUserId = normalizeUserId(targetUserId);
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        assertCanManageMembers(tenantEntity, normalizedOperatorUserId);
        UserEntity targetUser = getActiveUser(normalizedTargetUserId);

        MembershipEntity membershipEntity = membershipRepository.findByUserIdAndTenantId(targetUser.getId(), tenantEntity.getId())
                .map(existingMembership -> reviveOrRejectExistingMembership(existingMembership))
                .orElseGet(() -> membershipRepository.save(buildMembership(
                        targetUser.getId(),
                        tenantEntity.getId(),
                        MembershipRoleVO.MEMBER,
                        MembershipStatusVO.ACTIVE,
                        LocalDateTime.now(),
                        null)));

        log.info("成员关系领域服务-添加成员完成 membershipId:{} tenantId:{} targetUserId:{}",
                membershipEntity.getId(), membershipEntity.getTenantId(), membershipEntity.getUserId());
        return membershipEntity;
    }

    @Override
    public List<MembershipEntity> listMembersByTenantId(Long operatorUserId, Long tenantId) {
        Long normalizedOperatorUserId = normalizeUserId(operatorUserId);
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        log.info("成员关系领域服务-查询租户成员列表开始 operatorUserId:{} tenantId:{}",
                normalizedOperatorUserId, tenantEntity.getId());
        assertCanViewMembers(tenantEntity, normalizedOperatorUserId);
        List<MembershipEntity> membershipEntities = membershipRepository.listByTenantId(tenantEntity.getId());
        log.info("成员关系领域服务-查询租户成员列表完成 tenantId:{} membershipCount:{}",
                tenantEntity.getId(), membershipEntities.size());
        return membershipEntities;
    }

    @Override
    public List<MembershipEntity> listMembershipsByUserId(Long userId) {
        Long normalizedUserId = normalizeUserId(userId);
        getActiveUser(normalizedUserId);
        log.info("成员关系领域服务-查询用户成员关系列表开始 userId:{}", normalizedUserId);
        List<MembershipEntity> membershipEntities = membershipRepository.listByUserId(normalizedUserId);
        log.info("成员关系领域服务-查询用户成员关系列表完成 userId:{} membershipCount:{}",
                normalizedUserId, membershipEntities.size());
        return membershipEntities;
    }

    @Override
    @Transactional
    public MembershipEntity removeMember(Long operatorUserId, Long tenantId, Long targetUserId) {
        log.info("成员关系领域服务-移除成员开始 operatorUserId:{} tenantId:{} targetUserId:{}",
                operatorUserId, tenantId, targetUserId);
        Long normalizedOperatorUserId = normalizeUserId(operatorUserId);
        Long normalizedTargetUserId = normalizeUserId(targetUserId);
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        assertOwnerOperator(tenantEntity, normalizedOperatorUserId);
        MembershipEntity membershipEntity = membershipRepository.findByUserIdAndTenantId(normalizedTargetUserId, tenantEntity.getId())
                .orElseThrow(() -> new BusinessException(ResponseCode.MEMBERSHIP_NOT_FOUND));

        if (membershipEntity.getUserId().equals(tenantEntity.getOwnerUserId())) {
            throw new BusinessException(ResponseCode.MEMBERSHIP_OWNER_CANNOT_REMOVE);
        }

        MembershipEntity updatedMembership = rebuildMembership(
                membershipEntity,
                membershipEntity.getRole(),
                MembershipStatusVO.REMOVED,
                membershipEntity.getJoinedAt(),
                membershipEntity.getInvitedAt(),
                LocalDateTime.now());
        membershipRepository.update(updatedMembership);
        log.info("成员关系领域服务-移除成员完成 membershipId:{} tenantId:{} targetUserId:{}",
                updatedMembership.getId(), updatedMembership.getTenantId(), updatedMembership.getUserId());
        return updatedMembership;
    }

    private MembershipEntity reviveOrRejectExistingMembership(MembershipEntity existingMembership) {
        if (MembershipStatusVO.LEFT != existingMembership.getStatus()
                && MembershipStatusVO.REMOVED != existingMembership.getStatus()) {
            throw new BusinessException(ResponseCode.MEMBERSHIP_ALREADY_EXISTS);
        }

        MembershipEntity updatedMembership = rebuildMembership(
                existingMembership,
                MembershipRoleVO.MEMBER,
                MembershipStatusVO.ACTIVE,
                LocalDateTime.now(),
                existingMembership.getInvitedAt(),
                LocalDateTime.now());
        membershipRepository.update(updatedMembership);
        return updatedMembership;
    }

    private MembershipEntity buildMembership(Long userId, Long tenantId, MembershipRoleVO role,
                                             MembershipStatusVO status, LocalDateTime joinedAt, LocalDateTime invitedAt) {
        LocalDateTime now = LocalDateTime.now();
        return MembershipEntity.builder()
                .userId(userId)
                .tenantId(tenantId)
                .role(role)
                .status(status)
                .joinedAt(joinedAt)
                .invitedAt(invitedAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private MembershipEntity rebuildMembership(MembershipEntity membershipEntity, MembershipRoleVO role,
                                               MembershipStatusVO status, LocalDateTime joinedAt,
                                               LocalDateTime invitedAt, LocalDateTime updatedAt) {
        return MembershipEntity.builder()
                .id(membershipEntity.getId())
                .userId(membershipEntity.getUserId())
                .tenantId(membershipEntity.getTenantId())
                .role(role)
                .status(status)
                .joinedAt(joinedAt)
                .invitedAt(invitedAt)
                .createdAt(membershipEntity.getCreatedAt())
                .updatedAt(updatedAt)
                .build();
    }

    private TenantEntity getActiveTenant(Long tenantId) {
        Long normalizedTenantId = normalizeTenantId(tenantId);
        TenantEntity tenantEntity = tenantRepository.findById(normalizedTenantId)
                .orElseThrow(() -> new BusinessException(ResponseCode.TENANT_NOT_FOUND));
        if (!tenantEntity.isActive()) {
            throw new BusinessException(ResponseCode.TENANT_STATUS_UNAVAILABLE);
        }
        return tenantEntity;
    }

    private UserEntity getActiveUser(Long userId) {
        UserEntity userEntity = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        if (!userEntity.isActive()) {
            throw new BusinessException(ResponseCode.USER_STATUS_UNAVAILABLE);
        }
        return userEntity;
    }

    private void assertOwnerOperator(TenantEntity tenantEntity, Long operatorUserId) {
        if (!tenantEntity.getOwnerUserId().equals(operatorUserId)) {
            throw new BusinessException(ResponseCode.MEMBERSHIP_OPERATOR_PERMISSION_DENIED);
        }
    }

    private void assertCanManageMembers(TenantEntity tenantEntity, Long operatorUserId) {
        if (tenantEntity.getOwnerUserId().equals(operatorUserId)) {
            return;
        }
        MembershipEntity operatorMembership = membershipRepository.findByUserIdAndTenantId(operatorUserId, tenantEntity.getId())
                .orElseThrow(() -> new BusinessException(ResponseCode.MEMBERSHIP_OPERATOR_NOT_MEMBER));
        if (!operatorMembership.isActive() || !operatorMembership.isAdmin()) {
            throw new BusinessException(ResponseCode.MEMBERSHIP_OPERATOR_PERMISSION_DENIED);
        }
    }

    private void assertCanViewMembers(TenantEntity tenantEntity, Long operatorUserId) {
        if (tenantEntity.getOwnerUserId().equals(operatorUserId)) {
            return;
        }
        MembershipEntity operatorMembership = membershipRepository.findByUserIdAndTenantId(operatorUserId, tenantEntity.getId())
                .orElseThrow(() -> new BusinessException(ResponseCode.MEMBERSHIP_OPERATOR_NOT_MEMBER));
        if (!operatorMembership.isActive()) {
            throw new BusinessException(ResponseCode.MEMBERSHIP_OPERATOR_NOT_MEMBER);
        }
    }

    private Long normalizeTenantId(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_TENANT_ID);
        }
        return tenantId;
    }

    private Long normalizeUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_USER_ID);
        }
        return userId;
    }

    private LocalDateTime resolveJoinedAt(LocalDateTime joinedAt) {
        return joinedAt == null ? LocalDateTime.now() : joinedAt;
    }
}
