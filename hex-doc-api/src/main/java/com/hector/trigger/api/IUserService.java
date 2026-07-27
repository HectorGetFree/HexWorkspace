package com.hector.trigger.api;

import com.hector.trigger.api.request.Request;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.api.dto.CurrentUserResponseDTO;
import com.hector.trigger.api.dto.UserLoginRequestDTO;
import com.hector.trigger.api.dto.UserLoginResponseDTO;
import com.hector.trigger.api.dto.UserLogoutResponseDTO;
import com.hector.trigger.api.dto.UserRegisterRequestDTO;
import com.hector.trigger.api.dto.UserRegisterResponseDTO;

/**
 * 用户服务接口。
 * 作为 user-service 第一版对外暴露的统一 API 契约，负责定义注册、登录、登出和当前用户查询能力。
 */
public interface IUserService {

    /**
     * 用户注册接口。
     */
    Response<UserRegisterResponseDTO> registerUser(Request<UserRegisterRequestDTO> request);

    /**
     * 用户登录接口。
     */
    Response<UserLoginResponseDTO> login(Request<UserLoginRequestDTO> request);

    /**
     * 用户登出接口。
     */
    Response<UserLogoutResponseDTO> logout();

    /**
     * 查询当前登录用户接口。
     */
    Response<CurrentUserResponseDTO> getCurrentUser();
}
