package indi.somebottle.potatosack.exceptions;

import java.io.IOException;

/**
 * 文件存储服务客户端初始化失败异常
 */
public class ClientInitializationException extends IOException {
    public ClientInitializationException(String message) {
        super(message);
    }
}
