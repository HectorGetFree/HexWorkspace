package com.hector.trigger.http.security;

import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Spring Security 当前登录用户工具类。
 * 负责从 SecurityContext 中统一提取当前登录用户身份信息。
 */
public final class SecurityUserUtil {

    private SecurityUserUtil() {
    }

    /**
     * 获取当前登录用户主体。
     */
    public static LoginUserPrincipal getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof LoginUserPrincipal loginUserPrincipal)) {
            throw new BusinessException(ResponseCode.AUTH_ERROR);
        }
        return loginUserPrincipal;
    }

    /**
     * 获取当前登录用户ID。
     */
    public static Long getCurrentUserId() {
        return getLoginUser().getUserId();
    }

    /**
     * 获取当前登录用户名。
     */
    public static String getCurrentUsername() {
        return getLoginUser().getUsername();
    }
}
