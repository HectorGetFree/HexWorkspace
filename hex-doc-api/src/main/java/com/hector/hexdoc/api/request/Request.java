package com.hector.hexdoc.api.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一请求对象。
 * 参考 big-market 的 API 契约风格，用于在接口层统一承载调用方身份信息和业务请求数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Request<T> implements Serializable {

    /** 调用方应用标识。 */
    private String appId;

    /** 调用方应用令牌。 */
    private String appToken;

    /** 具体业务请求对象。 */
    private T data;
}
