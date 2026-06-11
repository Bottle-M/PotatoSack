package indi.somebottle.potatosack.clients.base;

import indi.somebottle.potatosack.clients.base.entities.FileItem;
import indi.somebottle.potatosack.exceptions.ClientInitializationException;
import indi.somebottle.potatosack.tasks.entities.ZipFilePath;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.Utils;

import java.io.IOException;
import java.util.List;

/**
 * 云存储服务客户端抽象基类
 * <p>
 * 提供了统一的云存储操作接口，包括文件上传、下载、删除、目录管理等功能。
 * 支持通过 {@code base-dir} 配置在云端指定根目录，所有操作的路径都会自动添加该前缀。
 * </p>
 * <p>
 * 具体的云存储实现类需要继承此类并实现所有抽象方法。目前支持的实现包括：
 * <ul>
 *   <li>OneDriveClient - Microsoft OneDrive 客户端</li>
 *   <li>DropboxClient - Dropbox 客户端</li>
 * </ul>
 * </p>
 *
 * @see indi.somebottle.potatosack.clients.onedrive.OneDriveClient
 * @see indi.somebottle.potatosack.clients.dropbox.DropboxClient
 */
public abstract class Client {
    /**
     * 配置对象，包含客户端所需的所有配置信息
     */
    protected Config config;

    /**
     * 云端数据存储的基础目录路径
     * <p>
     * 从配置文件的 {@code client.base-dir} 读取并规范化（去除首尾斜杠）。
     * 所有相对路径操作都会自动添加此前缀，形成完整的云端路径。
     * 若为空字符串，则直接使用相对路径。
     * </p>
     *
     * @see #buildFullPath(String)
     */
    protected String baseDir;

    /**
     * 初始化云存储服务客户端
     * <p>
     * 构造函数会自动从配置对象中读取 {@code client.base-dir} 配置项，
     * 并将其规范化后存储到 {@link #baseDir} 字段中。规范化过程会去除路径首尾的斜杠。
     * </p>
     *
     * @param config 配置对象，包含客户端初始化所需的所有配置信息
     * @throws ClientInitializationException 当客户端初始化失败时抛出，例如认证失败、网络错误等
     * @see Config.KEYS.CLIENT#BASE_DIR
     * @see Utils#normalizeBaseDir(String)
     */
    public Client(Config config) throws ClientInitializationException {
        this.config = config;
        // 读取并规范化 base-dir 配置
        String configBaseDir = (String) config.getConfig(Config.KEYS.CLIENT.BASE_DIR);
        this.baseDir = Utils.normalizeBaseDir(configBaseDir);
    }

    /**
     * 构建完整的远程路径
     * <p>
     * 将 {@link #baseDir} 配置与相对路径拼接，生成最终的云端路径。
     * 拼接规则如下：
     * <ul>
     *   <li>若 baseDir 为空，则直接返回相对路径</li>
     *   <li>否则返回 {@code baseDir + "/" + relativePath}</li>
     * </ul>
     * </p>
     * <p>
     * 例如，当 baseDir 为 {@code "my/backups"} 时：
     * <ul>
     *   <li>{@code buildFullPath("world.zip")} 返回 {@code "my/backups/world.zip"}</li>
     *   <li>{@code buildFullPath("data/config.yml")} 返回 {@code "my/backups/data/config.yml"}</li>
     * </ul>
     * </p>
     *
     * @param relativePath 相对路径，不应包含 baseDir 前缀
     * @return 完整路径（带 baseDir 前缀），若 baseDir 为空则返回原始相对路径
     * @see #baseDir
     */
    protected String buildFullPath(String relativePath) {
        if (baseDir.isEmpty()) {
            return relativePath;
        }
        return baseDir + "/" + relativePath;
    }

    /**
     * 递归创建目录（如果不存在）
     * <p>
     * 支持创建多层嵌套目录，如 {@code "my/backups/PotatoSack"}，会依次创建：
     * <ol>
     *   <li>{@code "my"}</li>
     *   <li>{@code "my/backups"}</li>
     *   <li>{@code "my/backups/PotatoSack"}</li>
     * </ol>
     * 若某一层级的目录已存在，则跳过该层级的创建。
     * </p>
     * <p>
     * 此方法会自动处理路径规范化（去除首尾斜杠、空路径段）。
     * 对于空路径或根目录，方法会直接返回 {@code true}。
     * </p>
     *
     * @param fullPath 完整路径（应为绝对路径或已包含 baseDir 的完整路径）
     * @return {@code true} 表示目录已存在或创建成功，{@code false} 表示创建失败
     * @throws IOException 当网络请求失败时抛出（如超时、连接错误等）
     * @see #createFolderInternal(String, String)
     * @see #getItemInternal(String)
     */
    public boolean ensureFolderExists(String fullPath) throws IOException {
        fullPath = Utils.normalizeBaseDir(fullPath);
        if (fullPath.isEmpty()) {
            return true;
        }
        if (getItemInternal(fullPath) != null) {
            return true; // 已存在
        }
        // 递归创建父目录
        String[] parts = fullPath.split("/");
        String currentPath = "";
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            String nextPath = currentPath.isEmpty() ? part : currentPath + "/" + part;
            if (getItemInternal(nextPath) == null) {
                if (!createFolderInternal(currentPath, part)) {
                    return false;
                }
            }
            currentPath = nextPath;
        }
        return true;
    }

    /**
     * 关闭客户端，释放资源，执行清理工作
     * <p>
     * 此方法应在客户端不再使用时调用，用于释放网络连接、关闭文件句柄、
     * 清理缓存等资源。具体的清理行为由子类实现决定。
     * </p>
     * <p>
     * 建议在插件卸载或服务器关闭时调用此方法，确保资源被正确释放。
     * </p>
     */
    public abstract void shutdown();

    /**
     * 列出根目录下所有项目
     *
     * @return 根目录下的所有项目 FileItem
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public List<FileItem> listItems() throws IOException {
        return listItems("");
    }

    /**
     * 列出指定目录中的所有项目
     *
     * @param path 指定目录，可以为空（比如 "Documents" 指的是 "根目录/Documents"）
     * @return 指定目录中的所有项目 FileItem
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     * @apiNote 请在异步线程内调用此方法，可能阻塞
     */
    public List<FileItem> listItems(String path) throws IOException {
        return listItemsInternal(buildFullPath(path));
    }

    /**
     * 列出指定目录中的所有项目（内部实现）
     *
     * @param fullPath 完整路径
     * @return 指定目录中的所有项目 FileItem
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     */
    protected abstract List<FileItem> listItemsInternal(String fullPath) throws IOException;

    /**
     * 获得某个项目的详细信息 (文件/目录)
     *
     * @param path 项目路径，比如 "Documents" 指的就是 "根目录/Documents"
     * @return 项目详细信息 FileItem
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 同时包含项目文件的下载URL
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public FileItem getItem(String path) throws IOException {
        return getItemInternal(buildFullPath(path));
    }

    /**
     * 获得某个项目的详细信息（内部实现）
     *
     * @param fullPath 完整路径
     * @return 项目详细信息 FileItem
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     */
    protected abstract FileItem getItemInternal(String fullPath) throws IOException;

    /**
     * 把文件下载到本地
     *
     * @param remotePath 远程文件路径，比如 "test.txt" 指的就是 "根目录/test.txt"
     * @param localPath  本地文件路径
     * @return 下载是否成功
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     */
    public boolean downloadFile(String remotePath, String localPath) throws IOException {
        return downloadFileInternal(buildFullPath(remotePath), localPath);
    }

    /**
     * 把文件下载到本地（内部实现）
     *
     * @param fullRemotePath 完整远程路径
     * @param localPath      本地文件路径
     * @return 下载是否成功
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     */
    protected abstract boolean downloadFileInternal(String fullRemotePath, String localPath) throws IOException;

    /**
     * <p>压缩的同时进行文件上传（仅用于大文件）</p><bold>此处传入的是 ZipFilePath[]，用于将指定的文件进行打包后上传。</bold>
     *
     * @param zipFilePaths 要打包的文件路径对 ZipFilePath[]
     * @param remotePath   远程目录路径，比如 "Documents/test.txt" 指的就是 "根目录/Documents/test.txt"
     * @param quiet        是否静默打包（不显示 Adding... 信息)
     * @return 上传是否成功
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     * @apiNote 请在异步线程内调用此方法，可能阻塞
     */
    public boolean streamCompressAndUpload(ZipFilePath[] zipFilePaths, String remotePath, boolean quiet) throws IOException {
        return streamCompressAndUploadInternal(zipFilePaths, buildFullPath(remotePath), quiet);
    }

    /**
     * 压缩的同时进行文件上传（内部实现）
     *
     * @param zipFilePaths   要打包的文件路径对 ZipFilePath[]
     * @param fullRemotePath 完整远程路径
     * @param quiet          是否静默打包（不显示 Adding... 信息)
     * @return 上传是否成功
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     */
    protected abstract boolean streamCompressAndUploadInternal(ZipFilePath[] zipFilePaths, String fullRemotePath, boolean quiet) throws IOException;

    /**
     * 将本地大文件上传到云存储中
     *
     * @param localPath  本地文件路径
     * @param remotePath 远程目录路径，比如 "Documents/test.txt" 指的就是 "根目录/Documents/test.txt"
     * @return 是否上传成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 请在异步线程内调用此方法，可能阻塞
     */
    public boolean uploadLargeFile(String localPath, String remotePath) throws IOException {
        return uploadLargeFileInternal(localPath, buildFullPath(remotePath));
    }

    /**
     * 将本地大文件上传到云存储中（内部实现）
     *
     * @param localPath      本地文件路径
     * @param fullRemotePath 完整远程路径
     * @return 是否上传成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     */
    protected abstract boolean uploadLargeFileInternal(String localPath, String fullRemotePath) throws IOException;

    /**
     * 小文件上传，当文件较大时本方法会自动调用 uploadLargeFile 方法，转变为大文件分块上传
     *
     * @param localPath  本地文件路径
     * @param remotePath 远程目录路径，比如 "Documents/test.txt" 指的就是 "根目录/Documents/test.txt"
     * @return 上传是否成功
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     * @apiNote 请在异步线程内调用此方法，可能阻塞
     * @apiNote 如果 remotePath 所指的文件已经存在，会更新这个文件
     */
    public boolean uploadFile(String localPath, String remotePath) throws IOException {
        return uploadFileInternal(localPath, buildFullPath(remotePath));
    }

    /**
     * 小文件上传（内部实现）
     *
     * @param localPath      本地文件路径
     * @param fullRemotePath 完整远程路径
     * @return 上传是否成功
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     */
    protected abstract boolean uploadFileInternal(String localPath, String fullRemotePath) throws IOException;

    /**
     * 删除指定路径下的项目（文件/目录）
     *
     * @param path 指定路径，比如 "Documents" 指的就是 "根目录/Documents"
     * @return 是否删除成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 请在异步线程内调用此方法，可能阻塞
     */
    public boolean deleteItem(String path) throws IOException {
        return deleteItemInternal(buildFullPath(path));
    }

    /**
     * 删除指定路径下的项目（内部实现）
     *
     * @param fullPath 完整路径
     * @return 是否删除成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     */
    protected abstract boolean deleteItemInternal(String fullPath) throws IOException;

    /**
     * 在根目录下建立子目录
     *
     * @param name 目录名
     * @return 是否创建成功
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     * @apiNote 请在线程内调用此方法，会阻塞
     */
    public boolean createFolder(String name) throws IOException {
        return createFolder("", name);
    }

    /**
     * 建立目录
     *
     * @param path 路径（比如 "Documents" 指的是 "根目录/Documents"）
     * @param name 目录名
     * @return 是否建立成功
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     * @apiNote 请在线程内调用此方法，会阻塞
     */
    public boolean createFolder(String path, String name) throws IOException {
        return createFolderInternal(buildFullPath(path), name);
    }

    /**
     * 建立目录（内部实现）
     *
     * @param fullPath 完整路径
     * @param name     目录名
     * @return 是否建立成功
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     */
    protected abstract boolean createFolderInternal(String fullPath, String name) throws IOException;
}
