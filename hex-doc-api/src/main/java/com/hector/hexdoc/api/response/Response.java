package com.hector.hexdoc.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应对象。
 * 用于在接口层统一返回响应码、响应说明以及具体业务数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Response<T> implements Serializable {

    /** 响应编码。 */
    private String code;

    /** 响应说明信息。 */
    private String info;

    /** 具体业务响应数据。 */
    private T data;
}
