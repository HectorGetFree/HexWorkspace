package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 初始化租户拥有者成员关系请求对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MembershipInitOwnerRequestDTO implements Serializable {

    /** 租户主键ID。 */
    private Long tenantId;
}
