package com.hector.hexdoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HexDoc 启动类。
 * 当前阶段只负责装配和启动 Spring Boot 容器，不承载任何具体业务规则。
 */
@SpringBootApplication
public class HexDocApplication {

    /**
     * 应用启动入口。
     */
    public static void main(String[] args) {
        SpringApplication.run(HexDocApplication.class, args);
    }
}
