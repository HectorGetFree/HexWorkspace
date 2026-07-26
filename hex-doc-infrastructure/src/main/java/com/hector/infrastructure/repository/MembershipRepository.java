package com.hector.infrastructure.repository;

import com.hector.domain.membership.model.entity.MembershipEntity;
import com.hector.domain.membership.model.valobj.MembershipRoleVO;
import com.hector.domain.membership.model.valobj.MembershipStatusVO;
import com.hector.domain.membership.repository.IMembershipRepository;
import com.hector.infrastructure.dao.IMembershipDao;
import com.hector.infrastructure.dao.po.Membership;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 成员关系仓储实现。
 */
@Repository
public class MembershipRepository implements IMembershipRepository {

    private static final Logger log = LoggerFactory.getLogger(MembershipRepository.class);

    @Resource
    private IMembershipDao membershipDao;

    @Override
    public MembershipEntity save(MembershipEntity membershipEntity) {
        log.info("成员关系仓储-保存成员关系开始 userId:{} tenantId:{} role:{} status:{}",
                membershipEntity.getUserId(), membershipEntity.getTenantId(), membershipEntity.getRole(), membershipEntity.getStatus());
        Membership membership = toPo(membershipEntity);
        membershipDao.insert(membership);
        MembershipEntity savedMembershipEntity = toEntity(membership);
        log.info("成员关系仓储-保存成员关系完成 membershipId:{} userId:{} tenantId:{}",
                savedMembershipEntity.getId(), savedMembershipEntity.getUserId(), savedMembershipEntity.getTenantId());
        return savedMembershipEntity;
    }

    @Override
    public void update(MembershipEntity membershipEntity) {
        log.info("成员关系仓储-更新成员关系开始 membershipId:{} userId:{} tenantId:{} role:{} status:{}",
                membershipEntity.getId(), membershipEntity.getUserId(), membershipEntity.getTenantId(),
                membershipEntity.getRole(), membershipEntity.getStatus());
        membershipDao.update(toPo(membershipEntity));
        log.info("成员关系仓储-更新成员关系完成 membershipId:{}", membershipEntity.getId());
    }

    @Override
    public Optional<MembershipEntity> findById(Long membershipId) {
        log.info("成员关系仓储-按ID查询开始 membershipId:{}", membershipId);
        Membership membership = membershipDao.queryMembershipById(membershipId);
        if (membership == null) {
            return Optional.empty();
        }
        MembershipEntity membershipEntity = toEntity(membership);
        log.info("成员关系仓储-按ID查询完成 membershipId:{} userId:{} tenantId:{}",
                membershipEntity.getId(), membershipEntity.getUserId(), membershipEntity.getTenantId());
        return Optional.of(membershipEntity);
    }

    @Override
    public Optional<MembershipEntity> findByUserIdAndTenantId(Long userId, Long tenantId) {
        log.info("成员关系仓储-按用户和租户查询开始 userId:{} tenantId:{}", userId, tenantId);
        Membership membership = membershipDao.queryMembershipByUserIdAndTenantId(userId, tenantId);
        if (membership == null) {
            return Optional.empty();
        }
        MembershipEntity membershipEntity = toEntity(membership);
        log.info("成员关系仓储-按用户和租户查询完成 membershipId:{} userId:{} tenantId:{}",
                membershipEntity.getId(), membershipEntity.getUserId(), membershipEntity.getTenantId());
        return Optional.of(membershipEntity);
    }

    @Override
    public List<MembershipEntity> listByTenantId(Long tenantId) {
        log.info("成员关系仓储-按租户查询列表开始 tenantId:{}", tenantId);
        List<MembershipEntity> membershipEntities = membershipDao.queryMembershipsByTenantId(tenantId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
        log.info("成员关系仓储-按租户查询列表完成 tenantId:{} membershipCount:{}", tenantId, membershipEntities.size());
        return membershipEntities;
    }

    @Override
    public List<MembershipEntity> listByUserId(Long userId) {
        log.info("成员关系仓储-按用户查询列表开始 userId:{}", userId);
        List<MembershipEntity> membershipEntities = membershipDao.queryMembershipsByUserId(userId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
        log.info("成员关系仓储-按用户查询列表完成 userId:{} membershipCount:{}", userId, membershipEntities.size());
        return membershipEntities;
    }

    private Membership toPo(MembershipEntity membershipEntity) {
        return Membership.builder()
                .id(membershipEntity.getId())
                .userId(membershipEntity.getUserId())
                .tenantId(membershipEntity.getTenantId())
                .role(membershipEntity.getRole().name())
                .status(membershipEntity.getStatus().name())
                .joinedAt(membershipEntity.getJoinedAt())
                .invitedAt(membershipEntity.getInvitedAt())
                .createdAt(membershipEntity.getCreatedAt())
                .updatedAt(membershipEntity.getUpdatedAt())
                .build();
    }

    private MembershipEntity toEntity(Membership membership) {
        return MembershipEntity.builder()
                .id(membership.getId())
                .userId(membership.getUserId())
                .tenantId(membership.getTenantId())
                .role(MembershipRoleVO.valueOf(membership.getRole()))
                .status(MembershipStatusVO.valueOf(membership.getStatus()))
                .joinedAt(membership.getJoinedAt())
                .invitedAt(membership.getInvitedAt())
                .createdAt(membership.getCreatedAt())
                .updatedAt(membership.getUpdatedAt())
                .build();
    }
}
