package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 添加租户成员请求对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MembershipAddRequestDTO implements Serializable {

    /** 当前访问令牌。 */
    private String token;

    /** 租户主键ID。 */
    private Long tenantId;

    /** 待加入的用户ID。 */
    private Long userId;
}
