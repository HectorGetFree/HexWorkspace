package com.hector.trigger.http;

import com.hector.domain.auth.model.entity.AuthSessionEntity;
import com.hector.domain.auth.service.IAuthService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.service.IUserService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.trigger.api.dto.CurrentUserResponseDTO;
import com.hector.trigger.api.dto.UserLoginRequestDTO;
import com.hector.trigger.api.dto.UserLoginResponseDTO;
import com.hector.trigger.api.dto.UserLogoutResponseDTO;
import com.hector.trigger.api.dto.UserRegisterRequestDTO;
import com.hector.trigger.api.dto.UserRegisterResponseDTO;
import com.hector.trigger.api.request.Request;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
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

    private static final String REFRESH_TOKEN_COOKIE = "hex_workspace_refresh_token";

    @Value("${hexworkspace.jwt.refresh-expire-days:30}")
    private long refreshExpireDays;

    @Value("${hexworkspace.jwt.refresh-cookie-secure:false}")
    private boolean refreshCookieSecure;

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
            UserEntity userEntity = userService.registerUser(data.getUsername(), data.getPassword());
            log.info("用户注册完成 userId:{} username:{}", userEntity.getId(), userEntity.getUsername());
            return success(UserRegisterResponseDTO.builder()
                    .userId(userEntity.getId())
                    .username(userEntity.getUsername())
                    .status(userEntity.getStatus().name())
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
    public Response<UserLoginResponseDTO> login(@RequestBody Request<UserLoginRequestDTO> request,
                                                HttpServletResponse response) {
        try {
            UserLoginRequestDTO data = requireData(request);
            log.info("用户登录开始 username:{}", data.getUsername());
            AuthSessionEntity authSessionEntity = authService.login(data.getUsername(), data.getPassword());
            UserEntity userEntity = authSessionEntity.getUser();
            writeRefreshTokenCookie(response, authSessionEntity.getRefreshToken());
            log.info("用户登录完成 userId:{} username:{}", userEntity.getId(), userEntity.getUsername());
            return success(UserLoginResponseDTO.builder()
                    .token(authSessionEntity.getToken())
                    .tokenType(TOKEN_TYPE)
                    .userId(userEntity.getId())
                    .username(userEntity.getUsername())
                    .status(userEntity.getStatus().name())
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
     * 使用 HttpOnly refreshToken 换取新的短期 accessToken。
     * 前端在 accessToken 失效时以携带 Cookie 的方式调用该接口，再重试原请求。
     */
    @PostMapping("/refresh")
    public Response<UserLoginResponseDTO> refresh(HttpServletRequest request) {
        try {
            AuthSessionEntity authSessionEntity = authService.refresh(extractRefreshToken(request));
            UserEntity userEntity = authSessionEntity.getUser();
            return success(UserLoginResponseDTO.builder().token(authSessionEntity.getToken()).tokenType(TOKEN_TYPE)
                    .userId(userEntity.getId()).username(userEntity.getUsername()).status(userEntity.getStatus().name()).build());
        } catch (BusinessException exception) {
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("刷新访问令牌系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 用户登出接口。
     * 用于基于当前令牌执行登出确认。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/users/logout" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @PostMapping("/logout")
    public Response<UserLogoutResponseDTO> logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            log.info("用户登出开始");
            authService.logoutByRefreshToken(extractRefreshToken(request));
            clearRefreshTokenCookie(response);
            // 仅清理当前 HTTP 请求上下文；后续请求是否可访问由 Redis 撤销记录决定。
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            log.info("用户登出完成");
            return success(UserLogoutResponseDTO.builder()
                    .success(Boolean.TRUE)
                    .build());
        } catch (BusinessException exception) {
            clearRefreshTokenCookie(response);
            log.error("用户登出业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("用户登出系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /** 从 HttpOnly Cookie 中读取 refreshToken；该令牌不进入 JSON 响应或前端脚本存储。 */
    private String extractRefreshToken(HttpServletRequest request) {
        if (request.getCookies() == null) throw new BusinessException(ResponseCode.AUTH_ERROR);
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (REFRESH_TOKEN_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) return cookie.getValue();
        }
        throw new BusinessException(ResponseCode.AUTH_ERROR);
    }

    /** 登录成功后写入仅 HTTPS 可读策略可配置的 HttpOnly refreshToken Cookie。 */
    private void writeRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, refreshToken).httpOnly(true)
                .secure(refreshCookieSecure).sameSite("Strict").path("/api/v1/users")
                .maxAge(java.time.Duration.ofDays(refreshExpireDays)).build();
        response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** 登出时要求客户端立即删除 refreshToken Cookie。 */
    private void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, "").httpOnly(true)
                .secure(refreshCookieSecure).sameSite("Strict").path("/api/v1/users").maxAge(0).build();
        response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 查询当前用户接口。
     * 用于基于当前认证上下文返回登录用户身份信息。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/users/me" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/me")
    public Response<CurrentUserResponseDTO> getCurrentUser() {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("查询当前用户开始 userId:{}", currentUserId);
            UserEntity userEntity = userService.getCurrentUser(currentUserId);
            log.info("查询当前用户完成 userId:{} username:{}", userEntity.getId(), userEntity.getUsername());
            return success(CurrentUserResponseDTO.builder()
                    .userId(userEntity.getId())
                    .username(userEntity.getUsername())
                    .status(userEntity.getStatus().name())
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
