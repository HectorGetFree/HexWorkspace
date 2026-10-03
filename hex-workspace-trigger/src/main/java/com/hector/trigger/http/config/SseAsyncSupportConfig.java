package com.hector.trigger.http.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 浏览器 Agent SSE 需要跨越模型调用、工具执行和人工确认，不能使用 Servlet 默认短超时。
 */
@Configuration
public class SseAsyncSupportConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 1. 当前 MVC 异步入口仅用于长连接 SSE；0 交由 Servlet 视为不设置超时。
        // 2. 连接终止仍由浏览器断开、Runtime Stream 结束或服务关闭负责。
        configurer.setDefaultTimeout(0L);
    }
}
