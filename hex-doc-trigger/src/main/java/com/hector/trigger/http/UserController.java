package com.hector.trigger.http;

import com.hector.domain.auth.model.entity.AuthSession;
import com.hector.domain.auth.service.IAuthService;
import com.hector.domain.user.model.entity.User;
import com.hector.domain.user.service.IUserService;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import com.hector.trigger.api.request.Request;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.api.dto.CurrentUserRequestDTO;
import com.hector.trigger.api.dto.CurrentUserResponseDTO;
import com.hector.trigger.api.dto.UserLoginRequestDTO;
import com.hector.trigger.api.dto.UserLoginResponseDTO;
import com.hector.trigger.api.dto.UserLogoutRequestDTO;
import com.hector.trigger.api.dto.UserLogoutResponseDTO;
import com.hector.trigger.api.dto.UserRegisterRequestDTO;
import com.hector.trigger.api.dto.UserRegisterResponseDTO;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口控制器。
 * 负责对外暴露注册、登录、登出和当前用户查询能力。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private static final String TOKEN_TYPE = "Bearer";

    @Resource
    private IUserService userService;

    @Resource
    private IAuthService authService;

    /**
     * 用户注册接口。
     * 用于创建新的平台用户账号。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/users/register" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "username": "hector",
     *       "password": "123456"
     *     }
     *   }'
     */
    @PostMapping("/register")
    public Response<UserRegisterResponseDTO> register(@RequestBody Request<UserRegisterRequestDTO> request) {
        try {
            UserRegisterRequestDTO data = requireData(request);
            log.info("用户注册开始 username:{}", data.getUsername());
            User user = userService.registerUser(data.getUsername(), data.getPassword());
            log.info("用户注册完成 userId:{} username:{}", user.getId(), user.getUsername());
            return success(UserRegisterResponseDTO.builder()
                    .userId(user.getId())
                    .username(user.getUsername())
                    .status(user.getStatus().name())
                    .build());
        } catch (BusinessException exception) {
            log.error("用户注册业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("用户注册系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 用户登录接口。
     * 用于校验用户名密码并签发访问令牌。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/users/login" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "username": "hector",
     *       "password": "123456"
     *     }
     *   }'
     */
    @PostMapping("/login")
    public Response<UserLoginResponseDTO> login(@RequestBody Request<UserLoginRequestDTO> request) {
        try {
            UserLoginRequestDTO data = requireData(request);
            log.info("用户登录开始 username:{}", data.getUsername());
            AuthSession authSession = authService.login(data.getUsername(), data.getPassword());
            User user = authSession.getUser();
            log.info("用户登录完成 userId:{} username:{}", user.getId(), user.getUsername());
            return success(UserLoginResponseDTO.builder()
                    .token(authSession.getToken())
                    .tokenType(TOKEN_TYPE)
                    .userId(user.getId())
                    .username(user.getUsername())
                    .status(user.getStatus().name())
                    .build());
        } catch (BusinessException exception) {
            log.error("用户登录业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("用户登录系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 用户登出接口。
     * 用于基于当前令牌执行登出确认。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/users/logout" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "token": "Bearer 替换为实际登录令牌"
     *     }
     *   }'
     */
    @PostMapping("/logout")
    public Response<UserLogoutResponseDTO> logout(@RequestBody Request<UserLogoutRequestDTO> request) {
        try {
            UserLogoutRequestDTO data = requireData(request);
            log.info("用户登出开始 token:{}", data.getToken());
            authService.logout(data.getToken());
            log.info("用户登出完成 token:{}", data.getToken());
            return success(UserLogoutResponseDTO.builder()
                    .success(Boolean.TRUE)
                    .build());
        } catch (BusinessException exception) {
            log.error("用户登出业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("用户登出系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询当前用户接口。
     * 用于根据访问令牌解析当前登录用户身份。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/users/me" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "data": {
     *       "token": "Bearer 替换为实际登录令牌"
     *     }
     *   }'
     */
    @PostMapping("/me")
    public Response<CurrentUserResponseDTO> getCurrentUser(@RequestBody Request<CurrentUserRequestDTO> request) {
        try {
            CurrentUserRequestDTO data = requireData(request);
            log.info("查询当前用户开始 token:{}", data.getToken());
            User user = authService.getCurrentUser(data.getToken());
            log.info("查询当前用户完成 userId:{} username:{}", user.getId(), user.getUsername());
            return success(CurrentUserResponseDTO.builder()
                    .userId(user.getId())
                    .username(user.getUsername())
                    .status(user.getStatus().name())
                    .build());
        } catch (BusinessException exception) {
            log.error("查询当前用户业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询当前用户系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }
}
