package com.hector.trigger.http.security;

import com.alibaba.fastjson.JSON;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.trigger.api.response.Response;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security 未认证处理器。
 * 负责在请求未登录或令牌缺失时，返回统一响应结构。
 */
@Component
public class SecurityAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuthenticationEntryPoint.class);

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {
        log.error("SpringSecurity未认证异常 requestUri:{} info:{}",
                request.getRequestURI(), authException.getMessage(), authException);
        Response<Object> result = Response.builder()
                .code(ResponseCode.AUTH_ERROR.getCode())
                .info(ResponseCode.AUTH_ERROR.getInfo())
                .build();
        writeResponse(response, HttpServletResponse.SC_UNAUTHORIZED, result);
    }

    private void writeResponse(HttpServletResponse response, int status, Response<Object> result) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.toJSONString(result));
    }
}
