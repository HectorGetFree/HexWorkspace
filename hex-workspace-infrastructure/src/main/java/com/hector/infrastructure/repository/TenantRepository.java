package com.hector.infrastructure.repository;

import com.hector.domain.tenant.model.entity.TenantEntity;
import com.hector.domain.tenant.model.valobj.TenantStatusVO;
import com.hector.domain.tenant.repository.ITenantRepository;
import com.hector.infrastructure.dao.ITenantDao;
import com.hector.infrastructure.dao.po.Tenant;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 租户仓储实现。
 */
@Repository
public class TenantRepository implements ITenantRepository {

    private static final Logger log = LoggerFactory.getLogger(TenantRepository.class);

    @Resource
    private ITenantDao tenantDao;

    @Override
    public TenantEntity save(TenantEntity tenantEntity) {
        log.info("租户仓储-保存租户开始 tenantCode:{} tenantName:{}", tenantEntity.getTenantCode(), tenantEntity.getTenantName());
        Tenant tenant = Tenant.builder()
                .tenantCode(tenantEntity.getTenantCode())
                .tenantName(tenantEntity.getTenantName())
                .status(tenantEntity.getStatus().name())
                .ownerUserId(tenantEntity.getOwnerUserId())
                .description(tenantEntity.getDescription())
                .createdAt(tenantEntity.getCreatedAt())
                .updatedAt(tenantEntity.getUpdatedAt())
                .build();
        tenantDao.insert(tenant);
        TenantEntity savedTenantEntity = toEntity(tenant);
        log.info("租户仓储-保存租户完成 tenantId:{} tenantCode:{}", savedTenantEntity.getId(), savedTenantEntity.getTenantCode());
        return savedTenantEntity;
    }

    @Override
    public Optional<TenantEntity> findById(Long tenantId) {
        log.info("租户仓储-按ID查询开始 tenantId:{}", tenantId);
        Tenant tenant = tenantDao.queryTenantById(tenantId);
        if (tenant == null) {
            return Optional.empty();
        }
        return Optional.of(toEntity(tenant));
    }

    @Override
    public Optional<TenantEntity> findByTenantCode(String tenantCode) {
        log.info("租户仓储-按编码查询开始 tenantCode:{}", tenantCode);
        Tenant tenant = tenantDao.queryTenantByTenantCode(tenantCode);
        if (tenant == null) {
            return Optional.empty();
        }
        return Optional.of(toEntity(tenant));
    }

    @Override
    public List<TenantEntity> listByOwnerUserId(Long ownerUserId) {
        log.info("租户仓储-按拥有者查询列表开始 ownerUserId:{}", ownerUserId);
        return tenantDao.queryTenantsByOwnerUserId(ownerUserId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    private TenantEntity toEntity(Tenant tenant) {
        return TenantEntity.builder()
                .id(tenant.getId())
                .tenantCode(tenant.getTenantCode())
                .tenantName(tenant.getTenantName())
                .status(TenantStatusVO.valueOf(tenant.getStatus()))
                .ownerUserId(tenant.getOwnerUserId())
                .description(tenant.getDescription())
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .build();
    }
}
