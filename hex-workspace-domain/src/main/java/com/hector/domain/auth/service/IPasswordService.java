package com.hector.domain.auth.service;

/**
 * 密码服务接口。
 */
public interface IPasswordService {

    /**
     * 对明文密码进行哈希。
     */
    String encode(String rawPassword);

    /**
     * 校验明文密码与哈希值是否匹配。
     */
    boolean matches(String rawPassword, String encodedPassword);
}
