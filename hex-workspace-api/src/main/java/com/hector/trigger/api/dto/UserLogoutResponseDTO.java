package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户登出响应对象。
 * 第一版用于返回登出动作是否处理成功。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLogoutResponseDTO implements Serializable {

    /** 登出是否成功。 */
    private Boolean success;
}
