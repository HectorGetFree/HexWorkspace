package com.hector.trigger.http.security;

import com.hector.domain.auth.service.ITokenService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.service.IUserService;
import com.hector.hexdoc.types.exception.BusinessException;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器。
 * 负责从 Authorization 请求头中解析 Bearer Token，并将当前登录用户放入 Spring Security 上下文。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String TOKEN_PREFIX = "Bearer ";

    @Resource
    private ITokenService tokenService;

    @Resource
    private IUserService userService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestUri = request.getRequestURI();
        String authorization = request.getHeader(AUTHORIZATION_HEADER);

        if (authorization == null || authorization.isBlank()) {
            log.debug("JWT认证过滤器-请求头中未携带Authorization requestUri:{}", requestUri);
            filterChain.doFilter(request, response);
            return;
        }

        if (!authorization.startsWith(TOKEN_PREFIX)) {
            log.debug("JWT认证过滤器-Authorization格式不正确 requestUri:{} authorization:{}", requestUri, authorization);
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                log.info("JWT认证过滤器-开始解析登录用户 requestUri:{}", requestUri);
                Long userId = tokenService.parseUserId(authorization.substring(TOKEN_PREFIX.length()));
                UserEntity userEntity = userService.getCurrentUser(userId);
                LoginUserPrincipal loginUserPrincipal = new LoginUserPrincipal(
                        userEntity.getId(),
                        userEntity.getUsername(),
                        userEntity.getStatus().name());

                UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                        loginUserPrincipal,
                        null,
                        Collections.emptyList());
                authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                log.info("JWT认证过滤器-登录用户写入上下文完成 requestUri:{} userId:{} username:{}",
                        requestUri, userEntity.getId(), userEntity.getUsername());
            } catch (BusinessException exception) {
                log.error("JWT认证过滤器-解析登录用户失败 requestUri:{} code:{} info:{}",
                        requestUri, exception.getCode(), exception.getInfo(), exception);
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
