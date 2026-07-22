package com.hector.hexdoc.api.user;

import com.hector.hexdoc.api.request.Request;
import com.hector.hexdoc.api.response.Response;
import com.hector.hexdoc.api.user.dto.CurrentUserRequestDTO;
import com.hector.hexdoc.api.user.dto.CurrentUserResponseDTO;
import com.hector.hexdoc.api.user.dto.UserLoginRequestDTO;
import com.hector.hexdoc.api.user.dto.UserLoginResponseDTO;
import com.hector.hexdoc.api.user.dto.UserLogoutRequestDTO;
import com.hector.hexdoc.api.user.dto.UserLogoutResponseDTO;
import com.hector.hexdoc.api.user.dto.UserRegisterRequestDTO;
import com.hector.hexdoc.api.user.dto.UserRegisterResponseDTO;

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
    Response<UserLogoutResponseDTO> logout(Request<UserLogoutRequestDTO> request);

    /**
     * 查询当前登录用户接口。
     */
    Response<CurrentUserResponseDTO> getCurrentUser(Request<CurrentUserRequestDTO> request);
}
