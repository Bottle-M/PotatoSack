# PotatoSack 开发日志

2024 年 6 月我才想起来写这玩意...

## 2024.6.23

1. 重写 `BackupChecker` 采用的互斥锁 `BackupMutex`，改为了一个简单的可重入互斥锁。
2. 传统备份上传时，把输出 `Chunk size` 改为输出 `Total size`。
3. 去除注释的代码。
4. `zipSpecificFilesUtil` 方法添加对文件大小（`.length()`）的检查。目前，每将一个文件读取出来压缩入压缩包内，都会对**文件的修改时间
   **、**大小**以及 **CRC32 校验和**进行检查，保证文件的完整性。

## 2024.6.22

1. 传统全量备份方式（先在本地临时产生压缩包再上传）改用压缩方法 `Utils.zipSpecificFiles`，抛弃 `Utils.zip`。
2. 利用文件最后修改时间以及 **CRC32**
   校验和来在压缩过程中对文件完整性进行检查。（在服务端异步写入文件的同时若本插件在读取该文件进行压缩，会导致读出的数据混乱，通过完整性检查起码可以保证压入压缩包的每个文件都是完整可解析的。）

## 2024.6.21

1. 去除 TODO 注释。
2. 流式压缩上传时若数据上传完毕后因为数据大小误差需要填充空白字节，在命令行进行提示。
3. 存储时，`世界名.json` 加上一个下划线前缀，改为 `_世界名.json`，防止有世界名为 `backup`，和 `backup.json` 冲突。
4. `_世界名.json` 字段命名规范和 `backup.json` 的统一，下划线分隔单词。
5. `backup.json` 中新增对每个增量备份的时间戳的记录。
6. 流式压缩上传文件大小不匹配时末尾填充的空白字符数改为溢出字节数平均值的 **5 倍**。

## 2024.6.20

1. 完善备份机制笔记。
2. 开始准备写备份合并工具。

## 2024.6.19

1. 新增 OKHttpClient 的超时设置。
2. 流式压缩上传 zipPipingUpload 因文件大小误差而重试时， `uploadUrl` 不可复用，现改为重试时重新生成一个 `uploadUrl` 的逻辑。
3. ConsoleSender 模块的 `toConsole` 方法改为直接用 Java 的 `println`。
4. 解决流式压缩上传和普通压缩上传模块中的 OKHttp Response
   泄露问题。只要执行（execute）了请求，拿到了响应，且没有用 `try-with-resource`，必须显式关闭 Response。
    - 关闭 ResponseBody 等同于关闭 Response。
    -
   文档: https://square.github.io/okhttp/5.x/okhttp/okhttp3/-response/close.html?query=open%20override%20fun%20close()
5. OkHttp 请求重试拦截器中，每次重试前先等待 5 秒。
6. 流式压缩上传一块数据时，如果 OkHttp 请求（包括拦截器重试）失败，等待几秒再重试一次。
7. 流式压缩上传 Stream 中 `write` 方法增加对内存缓冲区访问越界的处理。
8. 重新审视应该将 `writePos` 置 `0` 的地方。
9. 处理流式压缩上传分片数据交叠（overlap）问题时，考虑服务端期待下次传输的首个字节位于当前分片数据之间的情况。
10. 流式压缩上传 Stream 中 `close` 方法重写，防止在 `try-with-resource`
    关闭资源时，其被重复调用；且修改空白字符填充的逻辑（修复了上传完不足一个缓冲区大小的最后一块后没有填充剩余空白字符的问题）。
11. 新增 `logWarn` 方法, 让 MC 服务端日志记录器记录警告信息。部分 `logError` 更换为 `logWarn`。  