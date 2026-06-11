package indi.somebottle.potatosack.clients;

import indi.somebottle.potatosack.clients.base.Client;
import indi.somebottle.potatosack.clients.dropbox.DropboxClient;
import indi.somebottle.potatosack.clients.onedrive.OneDriveClient;
import indi.somebottle.potatosack.utils.Config;

/**
 * 云存储客户端工厂类
 * <p>
 * 提供了统一的工厂方法来创建不同类型的云存储客户端实例。
 * 根据传入的类型标识符，返回对应的客户端实现。
 * </p>
 * <p>
 * 当前支持的云存储类型：
 * <ul>
 *   <li>{@code "onedrive"} - Microsoft OneDrive 客户端</li>
 *   <li>{@code "dropbox"} - Dropbox 客户端</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * Config config = new Config();
 * String clientType = (String) config.getConfig(Config.KEYS.CLIENT.USE);
 * Client client = ClientFactory.getClient(clientType, config);
 * }</pre>
 * </p>
 *
 * @see Client
 * @see indi.somebottle.potatosack.clients.onedrive.OneDriveClient
 * @see indi.somebottle.potatosack.clients.dropbox.DropboxClient
 */
public class ClientFactory {
    /**
     * 根据类型构造云存储客户端实例
     * <p>
     * 根据指定的客户端类型字符串（不区分大小写），创建并返回对应的云存储客户端对象。
     * 客户端会使用提供的配置对象进行初始化。
     * </p>
     *
     * @param type   云存储客户端类型（不区分大小写），支持 {@code "onedrive"} 和 {@code "dropbox"}
     * @param config 配置对象，包含客户端初始化所需的认证信息和其他配置
     * @return 初始化完成的云存储客户端实例
     * @throws IllegalArgumentException 当传入不支持的客户端类型时抛出
     * @throws Exception 当客户端初始化失败时抛出（如认证失败、网络错误等）
     * @see Client
     * @see Config
     */
    public static Client getClient(String type, Config config) throws Exception {
        //noinspection EnhancedSwitchMigration
        switch (type.toLowerCase()) {
            case "onedrive":
                return new OneDriveClient(config);
            case "dropbox":
                return new DropboxClient(config);
            default:
                throw new IllegalArgumentException("Unsupported client type: " + type);
        }
    }
}
