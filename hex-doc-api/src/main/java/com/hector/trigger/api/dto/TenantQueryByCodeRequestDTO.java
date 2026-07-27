package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 按租户编码查询请求对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantQueryByCodeRequestDTO implements Serializable {

    /** 租户唯一编码。 */
    private String tenantCode;
}
