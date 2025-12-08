package indi.somebottle.potatosack.clients;

import indi.somebottle.potatosack.clients.base.Client;
import indi.somebottle.potatosack.clients.onedrive.OneDriveClient;
import indi.somebottle.potatosack.utils.Config;

/**
 * 云存储 Client 工厂类
 */
public class ClientFactory {
    /**
     * 构造云存储客户端
     *
     * @param type   类型
     * @param config 配置 Config 对象
     * @return 云存储客户端 Client 对象
     * @throws Exception 初始化失败时抛出异常
     */
    public static Client getClient(String type, Config config) throws Exception {
        switch (type.toLowerCase()) {
            case "onedrive":
                return new OneDriveClient(config);
            default:
                throw new IllegalArgumentException("Unsupported client type: " + type);
        }
    }
}
