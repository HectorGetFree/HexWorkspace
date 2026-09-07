package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 租户响应对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantResponseDTO implements Serializable {

    /** 租户主键ID。 */
    private Long tenantId;

    /** 租户唯一编码。 */
    private String tenantCode;

    /** 租户名称。 */
    private String tenantName;

    /** 租户状态。 */
    private String status;

    /** 租户拥有者用户ID。 */
    private Long ownerUserId;

    /** 租户描述。 */
    private String description;
}
