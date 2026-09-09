package com.hector.trigger.http;

import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.trigger.api.request.Request;
import com.hector.trigger.api.response.Response;

/**
 * 控制器基类。
 * 负责复用通用响应包装和基础请求校验逻辑。
 */
public abstract class BaseController {

    protected <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    protected <T> Response<T> failure(String code, String info) {
        return Response.<T>builder()
                .code(code)
                .info(info)
                .build();
    }

    protected <T> Response<T> failure(ResponseCode responseCode) {
        return Response.<T>builder()
                .code(responseCode.getCode())
                .info(responseCode.getInfo())
                .build();
    }

    protected <T> T requireData(Request<T> request) {
        if (request == null || request.getData() == null) {
            throw new BusinessException(ResponseCode.EMPTY_REQUEST);
        }
        return request.getData();
    }
}
