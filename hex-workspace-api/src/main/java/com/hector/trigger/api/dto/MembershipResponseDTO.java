package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 成员关系响应对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MembershipResponseDTO implements Serializable {

    /** 成员关系主键ID。 */
    private Long membershipId;

    /** 用户ID。 */
    private Long userId;

    /** 用户名。 */
    private String username;

    /** 租户ID。 */
    private Long tenantId;

    /** 租户编码。 */
    private String tenantCode;

    /** 租户名称。 */
    private String tenantName;

    /** 成员角色。 */
    private String role;

    /** 成员关系状态。 */
    private String status;

    /** 正式加入时间。 */
    private LocalDateTime joinedAt;
}
