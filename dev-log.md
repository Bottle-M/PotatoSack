# PotatoSack 开发日志

2024 年 6 月我才想起来写这玩意...  

## 2024.6.21

1. 去除 TODO 注释。
2. 流式压缩上传时若数据上传完毕后因为数据大小误差需要填充空白字节，在命令行进行提示。

## 2024.6.20

1. 完善备份机制笔记。
2. 开始准备写备份合并工具。

## 2024.6.19

1. 新增 OKHttpClient 的超时设置。
2. 流式压缩上传 zipPipingUpload 因文件大小误差而重试时， `uploadUrl` 不可复用，现改为重试时重新生成一个 `uploadUrl` 的逻辑。  
3. ConsoleSender 模块的 `toConsole` 方法改为直接用 Java 的 `println`。
4. 解决流式压缩上传和普通压缩上传模块中的 OKHttp Response 泄露问题。只要执行（execute）了请求，拿到了响应，且没有用 `try-with-resource`，必须显式关闭 Response。
    - 关闭 ResponseBody 等同于关闭 Response。
    - 文档: https://square.github.io/okhttp/5.x/okhttp/okhttp3/-response/close.html?query=open%20override%20fun%20close()
5. OkHttp 请求重试拦截器中，每次重试前先等待 5 秒。
6. 流式压缩上传一块数据时，如果 OkHttp 请求（包括拦截器重试）失败，等待几秒再重试一次。
7. 流式压缩上传 Stream 中 `write` 方法增加对内存缓冲区访问越界的处理。
8. 重新审视应该将 `writePos` 置 `0` 的地方。
9. 处理流式压缩上传分片数据交叠（overlap）问题时，考虑服务端期待下次传输的首个字节位于当前分片数据之间的情况。
10. 流式压缩上传 Stream 中 `close` 方法重写，防止在 `try-with-resource` 关闭资源时，其被重复调用；且修改空白字符填充的逻辑（修复了上传完不足一个缓冲区大小的最后一块后没有填充剩余空白字符的问题）。
11. 新增 `logWarn` 方法, 让 MC 服务端日志记录器记录警告信息。部分 `logError` 更换为 `logWarn`。  