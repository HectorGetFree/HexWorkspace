package com.hector.hexdoc.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 当前用户查询响应对象。
 * 用于返回当前登录用户的基础身份信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserResponseDTO implements Serializable {

    /** 用户主键ID。 */
    private Long userId;

    /** 用户名。 */
    private String username;

    /** 账号状态。 */
    private String status;
}
