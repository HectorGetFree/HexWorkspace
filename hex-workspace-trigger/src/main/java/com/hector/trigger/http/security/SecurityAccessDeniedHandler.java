package com.hector.trigger.http.security;

import com.alibaba.fastjson.JSON;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.trigger.api.response.Response;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security 无权限处理器。
 * 负责在请求已登录但无访问权限时，返回统一响应结构。
 */
@Component
public class SecurityAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityAccessDeniedHandler.class);

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        log.error("SpringSecurity无权限异常 requestUri:{} info:{}",
                request.getRequestURI(), accessDeniedException.getMessage(), accessDeniedException);
        Response<Object> result = Response.builder()
                .code(ResponseCode.FORBIDDEN.getCode())
                .info(ResponseCode.FORBIDDEN.getInfo())
                .build();
        writeResponse(response, HttpServletResponse.SC_FORBIDDEN, result);
    }

    private void writeResponse(HttpServletResponse response, int status, Response<Object> result) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.toJSONString(result));
    }
}
