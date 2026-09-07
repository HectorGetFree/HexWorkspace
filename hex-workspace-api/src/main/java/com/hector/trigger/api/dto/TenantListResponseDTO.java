package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 租户列表响应对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantListResponseDTO implements Serializable {

    /** 租户列表。 */
    private List<TenantResponseDTO> tenants;
}
