package com.hector.hexdoc.types.exception;

import com.hector.hexdoc.types.enums.ResponseCode;
import lombok.Getter;

/**
 * 业务异常。
 * 用于在各层之间传递稳定的业务错误码和错误说明。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String code;

    private final String info;

    public BusinessException(String code, String info) {
        super(info);
        this.code = code;
        this.info = info;
    }

    public BusinessException(ResponseCode responseCode) {
        super(responseCode.getInfo());
        this.code = responseCode.getCode();
        this.info = responseCode.getInfo();
    }
}
