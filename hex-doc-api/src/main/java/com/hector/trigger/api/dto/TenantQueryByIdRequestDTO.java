package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 按租户ID查询请求对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantQueryByIdRequestDTO implements Serializable {

    /** 当前访问令牌。 */
    private String token;

    /** 租户主键ID。 */
    private Long tenantId;
}
