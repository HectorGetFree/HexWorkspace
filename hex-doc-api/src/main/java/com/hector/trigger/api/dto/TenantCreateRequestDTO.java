package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建租户请求对象。
 * 通过当前登录用户发起租户创建，owner 由服务端根据 token 解析后自动绑定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantCreateRequestDTO implements Serializable {

    /** 租户名称。 */
    private String tenantName;

    /** 租户描述。 */
    private String description;
}
