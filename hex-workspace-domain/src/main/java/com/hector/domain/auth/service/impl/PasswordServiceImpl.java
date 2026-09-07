package com.hector.domain.auth.service.impl;

import com.hector.domain.auth.service.IPasswordService;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 密码服务实现。
 */
@Service
public class PasswordServiceImpl implements IPasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordServiceImpl.class);

    @Override
    public String encode(String rawPassword) {
        log.info("密码服务-密码加密开始");
        return DigestUtils.sha256Hex(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        log.info("密码服务-密码校验开始");
        return encode(rawPassword).equals(encodedPassword);
    }
}
