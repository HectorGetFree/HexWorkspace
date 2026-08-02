package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 成员关系表映射对象。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Membership {

    private Long id;

    private Long userId;

    private Long tenantId;

    private String role;

    private String status;

    private LocalDateTime joinedAt;

    private LocalDateTime invitedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
