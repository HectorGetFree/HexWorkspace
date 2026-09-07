package com.hector.domain.tenant.service.impl;

import com.hector.domain.tenant.model.entity.TenantEntity;
import com.hector.domain.tenant.model.valobj.TenantStatusVO;
import com.hector.domain.tenant.repository.ITenantRepository;
import com.hector.domain.tenant.service.ITenantService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 租户领域服务实现。
 */
@Service
public class TenantServiceImpl implements ITenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantServiceImpl.class);
    private static final String TENANT_CODE_PREFIX = "tenant_";
    private static final int TENANT_CODE_RETRY_TIMES = 5;

    @Resource
    private ITenantRepository tenantRepository;

    @Resource
    private IUserRepository userRepository;

    @Override
    @Transactional
    public TenantEntity createTenant(String tenantName, Long ownerUserId, String description) {
        log.info("租户领域服务-创建租户开始 tenantName:{} ownerUserId:{}", tenantName, ownerUserId);
        String normalizedTenantName = normalizeTenantName(tenantName);
        Long normalizedOwnerUserId = normalizeOwnerUserId(ownerUserId);
        String normalizedDescription = normalizeDescription(description);

        UserEntity ownerUser = userRepository.findById(normalizedOwnerUserId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        if (!ownerUser.isActive()) {
            throw new BusinessException(ResponseCode.USER_STATUS_UNAVAILABLE);
        }

        String generatedTenantCode = generateTenantCode();

        LocalDateTime now = LocalDateTime.now();
        TenantEntity tenantEntity = TenantEntity.builder()
                .tenantCode(generatedTenantCode)
                .tenantName(normalizedTenantName)
                .status(TenantStatusVO.ACTIVE)
                .ownerUserId(normalizedOwnerUserId)
                .description(normalizedDescription)
                .createdAt(now)
                .updatedAt(now)
                .build();

        TenantEntity savedTenantEntity = tenantRepository.save(tenantEntity);
        log.info("租户领域服务-创建租户完成 tenantId:{} tenantCode:{} ownerUserId:{}",
                savedTenantEntity.getId(), savedTenantEntity.getTenantCode(), savedTenantEntity.getOwnerUserId());
        return savedTenantEntity;
    }

    @Override
    public TenantEntity getTenantById(Long tenantId) {
        log.info("租户领域服务-按ID查询租户开始 tenantId:{}", tenantId);
        TenantEntity tenantEntity = tenantRepository.findById(normalizeTenantId(tenantId))
                .orElseThrow(() -> new BusinessException(ResponseCode.TENANT_NOT_FOUND));
        log.info("租户领域服务-按ID查询租户完成 tenantId:{} tenantCode:{}", tenantEntity.getId(), tenantEntity.getTenantCode());
        return tenantEntity;
    }

    @Override
    public TenantEntity getTenantByCode(String tenantCode) {
        log.info("租户领域服务-按编码查询租户开始 tenantCode:{}", tenantCode);
        TenantEntity tenantEntity = tenantRepository.findByTenantCode(normalizeTenantCode(tenantCode))
                .orElseThrow(() -> new BusinessException(ResponseCode.TENANT_NOT_FOUND));
        log.info("租户领域服务-按编码查询租户完成 tenantId:{} tenantCode:{}", tenantEntity.getId(), tenantEntity.getTenantCode());
        return tenantEntity;
    }

    @Override
    public List<TenantEntity> listTenantsByOwnerUserId(Long ownerUserId) {
        Long normalizedOwnerUserId = normalizeOwnerUserId(ownerUserId);
        log.info("租户领域服务-按拥有者查询租户列表开始 ownerUserId:{}", normalizedOwnerUserId);

        UserEntity ownerUser = userRepository.findById(normalizedOwnerUserId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        if (!ownerUser.isActive()) {
            throw new BusinessException(ResponseCode.USER_STATUS_UNAVAILABLE);
        }

        List<TenantEntity> tenantEntities = tenantRepository.listByOwnerUserId(normalizedOwnerUserId);
        log.info("租户领域服务-按拥有者查询租户列表完成 ownerUserId:{} tenantCount:{}",
                normalizedOwnerUserId, tenantEntities.size());
        return tenantEntities;
    }

    private String normalizeTenantCode(String tenantCode) {
        if (tenantCode == null || tenantCode.trim().isEmpty()) {
            throw new BusinessException(ResponseCode.EMPTY_TENANT_CODE);
        }
        return tenantCode.trim();
    }

    private String normalizeTenantName(String tenantName) {
        if (tenantName == null || tenantName.trim().isEmpty()) {
            throw new BusinessException(ResponseCode.EMPTY_TENANT_NAME);
        }
        return tenantName.trim();
    }

    private Long normalizeOwnerUserId(Long ownerUserId) {
        if (ownerUserId == null || ownerUserId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_OWNER_USER_ID);
        }
        return ownerUserId;
    }

    private Long normalizeTenantId(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ResponseCode.ILLEGAL_PARAMETER);
        }
        return tenantId;
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String normalizedDescription = description.trim();
        return normalizedDescription.isEmpty() ? null : normalizedDescription;
    }

    private String generateTenantCode() {
        for (int i = 0; i < TENANT_CODE_RETRY_TIMES; i++) { // 循环重试是为了防极小概率的 tenantCode 撞码
            String tenantCode = TENANT_CODE_PREFIX + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 12)
                    .toLowerCase(Locale.ROOT); // 避免大小写转换受系统语言环境影响
            if (tenantRepository.findByTenantCode(tenantCode).isEmpty()) {
                log.info("租户领域服务-生成租户编码成功 tenantCode:{} retryIndex:{}", tenantCode, i);
                return tenantCode;
            }
            log.warn("租户领域服务-生成租户编码重复 tenantCode:{} retryIndex:{}", tenantCode, i);
        }
        log.error("租户领域服务-生成租户编码失败，超过最大重试次数");
        throw new BusinessException(ResponseCode.SYSTEM_ERROR);
    }
}
