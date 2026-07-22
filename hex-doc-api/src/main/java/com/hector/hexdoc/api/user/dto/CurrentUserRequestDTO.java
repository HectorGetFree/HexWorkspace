package com.hector.hexdoc.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 当前用户查询请求对象。
 * 第一版通过令牌定位当前用户身份，后续也可以由 trigger 层从鉴权上下文中补齐。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserRequestDTO implements Serializable {

    /** 当前访问令牌。 */
    private String token;
}
