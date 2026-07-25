package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户表映射对象。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    private Long id;

    private String tenantCode;

    private String tenantName;

    private String status;

    private Long ownerUserId;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
