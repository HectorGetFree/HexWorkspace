package com.hector.domain.auth.model.valobj;

/** JWT 用途，避免 refreshToken 被错误用于访问业务接口。 */
public enum AuthTokenTypeVO {
    ACCESS,
    REFRESH
}
