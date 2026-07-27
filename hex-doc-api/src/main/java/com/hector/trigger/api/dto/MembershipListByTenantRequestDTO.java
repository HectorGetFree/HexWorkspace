package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 按租户查询成员列表请求对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MembershipListByTenantRequestDTO implements Serializable {

    /** 租户主键ID。 */
    private Long tenantId;
}
