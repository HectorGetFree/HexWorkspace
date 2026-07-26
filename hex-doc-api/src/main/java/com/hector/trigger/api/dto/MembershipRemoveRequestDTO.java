package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 移除成员请求对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MembershipRemoveRequestDTO implements Serializable {

    /** 当前访问令牌。 */
    private String token;

    /** 租户主键ID。 */
    private Long tenantId;

    /** 目标用户ID。 */
    private Long targetUserId;
}
