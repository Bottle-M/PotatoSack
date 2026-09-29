package indi.somebottle.potatosack.exceptions;

import java.io.IOException;

/**
 * 云存储服务客户端初始化失败异常
 */
public class ClientInitializationException extends IOException {
    public ClientInitializationException(String message) {
        super(message);
    }

    /**
     * 构造带根因的初始化异常，便于在日志中追溯底层 SDK / 网络错误
     *
     * @param message 错误信息
     * @param cause   根因
     */
    public ClientInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
