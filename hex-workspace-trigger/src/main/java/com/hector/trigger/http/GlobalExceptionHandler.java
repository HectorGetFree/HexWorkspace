package com.hector.trigger.http;

import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.trigger.api.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

/**
 * 全局异常处理器。
 * 用于兜底处理在 Controller 参数绑定之前抛出的框架异常。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 multipart 请求异常。
     */
    @ExceptionHandler(MultipartException.class)
    public Response<Void> handleMultipartException(MultipartException exception) {
        log.error("全局异常处理-文件上传请求格式异常 info:{}", exception.getMessage(), exception);
        return Response.<Void>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("文件上传请求格式错误，请使用 multipart/form-data 并传入 file 字段")
                .build();
    }

    /**
     * 处理不支持的媒体类型异常。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Response<Void> handleMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException exception) {
        log.error("全局异常处理-请求媒体类型不支持 info:{}", exception.getMessage(), exception);
        return Response.<Void>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("请求 Content-Type 不支持，请使用 multipart/form-data")
                .build();
    }
}
