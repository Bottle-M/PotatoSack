# 备忘

## 关于获取用户Object ID

* https://learn.microsoft.com/zh-cn/partner-center/marketplace/find-tenant-object-id

## 个人的安全性想法

在MS Graph应用程序授权下，如果某个应用被赋予了`Files.Read.All`，就可以访问同一租户目录的所有文件。

> 同一租户=同一组织。

因此最好只分配**委托权限(Delegated)**，而**不要**分配**应用程序权限(Application)**，以让权限分配局限在单个登录用户，从而限制访问范围。

## 关于OneDrive权限

* https://learn.microsoft.com/zh-cn/onedrive/developer/rest-api/concepts/permissions_reference?view=odsp-graph-online

## 关于Files.ReadWrite.AppFolder权限节点

在配置权限时可以仅为文件读写分配此权限节点`Files.ReadWrite.AppFolder`。

此权限节点使得程序只能请求访问**应用目录下**的文件和目录，而不能访问用户其他文件，能很大程度上提升安全性。

详见文档: https://learn.microsoft.com/zh-cn/onedrive/developer/rest-api/concepts/special-folders-appfolder?view=odsp-graph-online#getting-authorization-from-the-user

但是！就目前看来这个权限节点**仅仅支持**了`OneDrive Personal`（个人/家庭版）而没有支持`OneDrive Business`（企业版）。

对于后者，此权限节点的表现和`Files.ReadWrite.All`差不多。（微软官方好像很久前就说在考虑给ODB增加此权限节点，结果到现在都没加，老鸽子了）

## 403问题

* `AccessDenied Either scp or roles claim need to be present in the token`

    -
    解决贴: https://pnp.github.io/cli-microsoft365/user-guide/cli-certificate-caveats/#i-get-an-error-403-accessdenied-either-scp-or-roles-claim-need-to-be-present-in-the-token-when-executing-a-cli-for-microsoft-365-sharepoint-command-what-does-it-mean
    - 主要是账户权限缺少`Sites.Read.All`。

## 插件大致设计

1. 【一个全量备份+其后的增量备份(直至下一次全量备份前)】称为**一组备份**
2. 备份文件的相关记录文件也需要存在云端（比如上一次全量备份的时间，上一次增量备份的时间以及已备份文件标注）
3. 可以配置：
    - 保存备份的组数（其实就是保留全量备份的组数，删掉全量备份，其相关的增量备份也会被移除）
    - 增量备份的粒度（间隔时间）
    - 全量备份的粒度（间隔时间）
    - 注：程序启动时先拉取云端的备份记录，先检查是不是需要进行全量备份，再检查是不是需要进行增量备份。
4. 可配置仅在有玩家时增量备份
5. 备份任务进行时需要有锁，**防止意外出现多个备份任务**。
6. 注意增量备份时对**被删除文件**的处理。
7. 为了控制记录文件的规模，对每个目录（世界）分别生成对应的记录文件来进行记录。

## 增量备份检查的逻辑

对比文件哈希值以判断文件是否有变更。

* 可能有的问题：区域文件似乎有更新时间字段，每次在服务器内保存区块时文件内容会改变，导致哈希值不一致、
* 设想解决：对于区域文件，跳过更新时间字段，对文件剩下内容计算哈希值。
* 实际情况：难以解决，`.mca`文件有多个包含更新时间字段的部分，难以判断区块是否有变化。

## 流式压缩上传时的文件大小问题

这种方式下要先模拟压缩世界文件一遍以计算压缩文件大小，然后再压缩一遍，边压缩边上传到 OneDrive。

* 因为上传到 OneDrive 时必须要在开始上传时**提供确切的文件大小**。

尽管我在模拟压缩前用 `world.setAutoSave(false)` 关闭了自动保存，但是上传时仍时常会有和模拟压缩计算出的文件大小不一致的问题。  

推测是调用 `world.setAutoSave(false)` 时，服务端其实**已经在进行异步保存**工作，而此时关闭自动保存不会终止现有的保存工作，在模拟压缩时，服务端仍在将数据写入磁盘，这也就导致了压缩上传时的文件大小和模拟压缩时计算出的文件大小不一致。  


* 可能的解决方法：在调用 `world.setAutoSave(false)` 关闭自动保存后，用 `world.save()` 和 `Server.savePlayers()` 手动写入当前所有数据到磁盘中，然后等待几秒再开始进行备份任务，尽量保证开始备份时所有数据已经写入硬盘中。
* 存在的问题：保存相关方法必须放在主线程运行，会造成线程阻塞。
* 最终解决方法：在调用 `world.setAutoSave(false)` 关闭自动保存后，让线程先挂起 30s，等待之前自动保存任务的文件全部写入，再开始备份任务。（2024.6.11 测试通过，基本可行）
* 进阶：采用了如上方法，仍有可能会出现文件大小不一致的问题。在出现问题 i 时假设上传的字节比计算出来的多出了 `Ni` 字节，累计每次出问题时的平均溢出字节数，以计算溢出的平均字节数。在出现问题时立即重新启动一次备份任务（**尚未重新开启自动保存**），在新计算出的大小 `s` 上加上 `2*(N 的平均值)` 字节，再进行压缩上传。如果最终实际文件大小不足 `s+2*AVG(Ni)`，则填充空白字符直至满足 `s+2*AVG(Ni)` 字节（Zip 文件末尾的空白字节不会影响压缩条目的读取）。
* 回避：如果在重试一次后仍然文件大小不一致，那么就把下次重试推迟到 10 分钟后。

## 流式压缩上传时的分片重复上传问题

在上传分片的时候有概率因为网络问题造成这种情况：  

程序已经把分片传递给了服务端，但是没有正确拿到服务端的响应，于是程序认为这个分片没有正确发出，会重新发出。  

服务端重复接收到分片后会返回 416 错误，说明这个分片之前已经收到。

这个时候需要请求 `uploadUrl` ，如果服务端要求的下一个 range 的开头字节编号就是下个字节编号，那么可以从下个分片开始继续。

否则上传失败。

## 参考文档

1. [通过用户身份访问MS Graph](https://learn.microsoft.com/en-us/graph/auth-v2-user?tabs=http#5-use-the-refresh-token-to-get-a-new-access-token)
   （包括令牌刷新）
2. [大文件上传](https://learn.microsoft.com/en-us/onedrive/developer/rest-api/api/driveitem_createuploadsession?view=odsp-graph-online)
3. [Minecraft Anvil文件(.mca)格式](https://wiki.biligame.com/mc/%E5%8C%BA%E5%9D%97%E6%A0%BC%E5%BC%8F)
4. [Minecraft Region文件格式](https://wiki.biligame.com/mc/%E5%8C%BA%E5%9F%9F%E6%96%87%E4%BB%B6%E6%A0%BC%E5%BC%8F)
5. [获得未上传的分片列表](https://learn.microsoft.com/zh-cn/onedrive/developer/rest-api/api/driveitem_createuploadsession?view=odsp-graph-online#resuming-an-in-progress-upload)