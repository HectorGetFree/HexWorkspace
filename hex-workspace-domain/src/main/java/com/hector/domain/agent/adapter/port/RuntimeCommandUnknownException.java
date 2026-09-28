package com.hector.domain.agent.adapter.port;

/** Runtime 命令发送结果未知；不能把网络错误投影为 Runtime REJECTED。 */
public class RuntimeCommandUnknownException extends RuntimeException {

    public RuntimeCommandUnknownException(String message, Throwable cause) {
        super(message, cause);
    }

    public RuntimeCommandUnknownException(String message) {
        super(message);
    }
}
