package indi.somebottle.potatosack.clients.base;

import indi.somebottle.potatosack.clients.base.entities.FileItem;
import indi.somebottle.potatosack.exceptions.ClientInitializationException;
import indi.somebottle.potatosack.tasks.entities.ZipFilePath;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.Utils;

import java.io.IOException;
import java.util.List;

/**
 * 云存储服务客户端接口
 */
public abstract class Client {
    protected Config config;
    protected String baseDir;

    /**
     * 初始化云存储服务客户端
     *
     * @param config 配置对象
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
     * 将 base-dir 配置与相对路径拼接，生成最终的云端路径
     *
     * @param relativePath 相对路径
     * @return 完整路径（带 baseDir 前缀）
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
     * 支持创建多层嵌套目录，如 "my/backups/PotatoSack"
     *
     * @param fullPath 完整路径
     * @return 是否创建成功
     * @throws IOException 网络异常
     */
    public boolean ensureFolderExists(String fullPath) throws IOException {
        if (getItem(fullPath) != null) {
            return true; // 已存在
        }
        // 递归创建父目录
        String[] parts = fullPath.split("/");
        String currentPath = "";
        for (String part : parts) {
            String nextPath = currentPath.isEmpty() ? part : currentPath + "/" + part;
            if (getItem(nextPath) == null) {
                if (!createFolder(currentPath, part)) {
                    return false;
                }
            }
            currentPath = nextPath;
        }
        return true;
    }

    /**
     * 关闭客户端，释放资源，执行一些清理工作
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
