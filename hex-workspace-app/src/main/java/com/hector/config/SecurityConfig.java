package com.hector.config;

import com.hector.trigger.http.security.JwtAuthenticationFilter;
import com.hector.trigger.http.security.SecurityAccessDeniedHandler;
import com.hector.trigger.http.security.SecurityAuthenticationEntryPoint;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置。
 * 负责注册 JWT 认证过滤链、白名单路径以及统一的认证异常处理。
 */
@Configuration
public class SecurityConfig {

    @Resource
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Resource
    private SecurityAuthenticationEntryPoint securityAuthenticationEntryPoint;

    @Resource
    private SecurityAccessDeniedHandler securityAccessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity // 安全规则构建器
                .csrf(AbstractHttpConfigurer::disable) // 关闭 csrf 防护 （csrf 针对 Cookie 场景设计的）
                .cors(Customizer.withDefaults())    // 允许跨域
                .formLogin(AbstractHttpConfigurer::disable) // 关闭 Spring Security 默认的登陆表单
                .httpBasic(AbstractHttpConfigurer::disable) // 关闭 HTTP Basic 认证，它是一种很传统的认证方式，现在用 JWT 不需要
                .sessionManagement(sessionManagement ->
                        sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))// 不创建session，也不依赖 Session 保存登陆状态
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(securityAuthenticationEntryPoint)
                        .accessDeniedHandler(securityAccessDeniedHandler)) // 配置统一安全异常处理
                .authorizeHttpRequests(authorizeHttpRequests -> authorizeHttpRequests
                        .requestMatchers(
                                "/api/v1/users/register",
                                "/api/v1/users/login",
                                "/api/v1/users/refresh",
                                "/api/v1/users/logout",
                                "/internal/v1/agent-runs/lifecycle",
                                "/internal/agent/capabilities/invoke",
                                "/error") // 这些接口允许匿名访问
                        .permitAll()
                        .anyRequest()
                        .authenticated()) // 除去白名单外，其他请求都必须是已认证的请求
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class); // 把自己写的 JWT 过滤器插入到 Spring Security 的过滤器链中

        return httpSecurity.build();
    }
}
