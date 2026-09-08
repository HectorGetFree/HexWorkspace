package com.hector.domain.auth.service.impl;

import com.hector.domain.auth.service.IPasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 密码服务实现。
 */
@Service
public class PasswordServiceImpl implements IPasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordServiceImpl.class);
    private static final int BCRYPT_STRENGTH = 12;
    private static final Pattern BCRYPT_HASH_PATTERN = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    @Override
    public String encode(String rawPassword) {
        log.debug("密码服务-生成 BCrypt 哈希");
        return passwordEncoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        log.debug("密码服务-校验 BCrypt 哈希");
        if (rawPassword == null || encodedPassword == null
                || !BCRYPT_HASH_PATTERN.matcher(encodedPassword).matches()) {
            return false;
        }
        try {
            return passwordEncoder.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException exception) {
            // 数据库中的非法或旧格式哈希统一按认证失败处理，不能让格式异常升级为系统错误。
            log.warn("密码服务-拒绝非法密码哈希格式");
            return false;
        }
    }
}
