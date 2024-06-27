# PotatoSack

![Logo](./memos/pics/potatosack-logo-transparent-smaller.png)  

_放轻松些，我的老伙计，这袋儿土豆可比苏珊婶婶家的大理石雕像好搬多了！_

Lang: 中文简体 | [English](README.md)  

## 这是啥子哟

这是一个咱为自己 Minecraft 服务器写的一个简单的备份插件，可以对**世界数据**进行备份。支持**增量备份/全量备份**机制。  

备份的存档**不会留存在本地**，而是上传至 **OneDrive** 云端目录中。

* ✨ 本插件在压缩上传文件时可以**几乎不占用**多余的本地硬盘空间，适用于服务提供商对硬盘空间进行了限制的场景。详见[概念介绍](#概念介绍)。

> 本插件目前仅支持 OneDrive (**非世纪互联版**)。

## 概念介绍

<details>

<summary>展开查看</summary>

### 一组备份

“一组备份”指的是【一个全量备份 + 其后的增量备份 (直至下一次全量备份前的)】。  

每新建立一次全量备份，就会新创建“一组备份”。随后直至下次全量备份前的增量备份都会算在这一组里。  

详见[备份目录结构](memos/backup-mechanism.md#云端备份存储结构)。

### 流式压缩上传

本插件的“流式压缩上传”指的是边压缩文件边上传到云端的备份方式，采用了时间换空间的思路，仅占用较少的内存空间（用作缓冲区），几乎不会占用多余的硬盘空间。  

> 时间换空间是因为 OneDrive API 要求大文件上传前必须知道确切的最终文件大小，因此需要额外进行一趟模拟压缩来对文件大小进行计算。

传统的备份方式是将待备份文件先临时压缩为压缩包，再上传到云端，这种方式要求硬盘空间能容纳下待备份文件 + 产生的压缩包。  

然而，很多服务提供商会限制硬盘的可用空间。假如可用空间只有 10 GiB，而存档数据就占用了 7 GiB，那么硬盘剩余的空间是不太能容纳下产生的压缩包的，也就会导致备份失败。

</details>


## 怎么安装此插件？

1. 在[这里](https://github.com/Bottle-M/PotatoSack/releases/latest)下载插件。
2. 把插件复制到你服务器目录下的 `plugins` 目录中。
3. 启动服务器，插件会在 `plugins/PotatoSack` 目录下生成初始配置文件 `configs.yml`，你需要在此文件中对插件进行必要的[配置](#配置)。
4. 修改配置后重启服务器即可。如果见到下面这样的日志内容，说明 PotatoSack 插件启动成功。  
    ```log
    [12:52:32 INFO]: [PotatoSack] PotatoSack successfully initialized! Savor using it!
    ```

## 配置文件

配置文件位于 `plugins/PotatoSack/configs.yml`。

```yaml
# OneDrive API 配置 (支持 OneDrive Business(ODB)/Personal(ODC))
onedrive:
  # 开发文档参考: https://learn.microsoft.com/en-us/graph/auth-v2-user?tabs=http#5-use-the-refresh-token-to-get-a-new-access-token
  # 工具: https://github.com/Bottle-M/PotatoSack/tree/main/onedrive-token-tool
  client-id:
  client-secret:
  refresh-token:

# 你想要保留的备份组数.
# 注："一组备份 "包括一个全量备份和其后的增量备份（在下一个全量备份之前的）。
max-full-backups-retained: 3

# 相邻两次全量备份之间的时间间隔 (以分钟为单位)
full-backup-interval: 1440

# 相邻两次增量备份之间的时间间隔 (以分钟为单位)
incremental-backup-check-interval: 15

# 当没有玩家在线时是否暂停进行增量备份
# 注：全量备份将照常进行。
stop-incremental-backup-when-no-player: true

# 是否采用流式压缩上传
# 注: 当服务器硬盘空间不够大时可以启用此选项。
# 注: 这种方式下程序会将每块压缩文件数据暂时写入内存中的缓冲区，代价并不高。
use-streaming-compression-upload: false

# 需要进行备份的世界名，示例如下: 
# worlds:
#  - world
#  - world_nether
#  - world_the_end
# 注: 如果这个选项留空了，则本插件不会工作。
worlds: [ ]
```

## 命令

这个插件目前只有一个命令:  

```text
/potatosack reload
```

用于热重载插件配置文件。

### 命令权限节点

```text
potatosack.reload
```

> 💡 服务器管理员（OP）默认有这个权限。

## 小工具

### Backup Merger  

上文提到过，“一组备份”包括一个全量备份和一些增量备份。在恢复服务器数据的时候，BackupMerger 可以将这些备份合并成一个完整的备份。

详见 [BackupMerger](backups-merger/README.md)。  

### OneDrive Token Tool

通过这个工具你可以获取到编写配置时所需的 OneDrive 的 Refresh Token。  

详见 [onedrive-token-tool](onedrive-token-tool/README.zh_CN.md)。

## FAQ

1. Q：启动时控制台怎么打印出了 404 ？

    A：这往往是因为云端的文件缺失或相应目录未建立，不过不用担心，程序在遇到 404 响应后会自动建立相应文件和目录。

2. Q：备份数据存放在 OneDrive 的哪个位置？  

    A：`OneDrive 根目录/PotatoSack`，或者 `OneDrive 根目录/应用/<你创建的应用程序名>/PotatoSack`。当使用 OneDrive 家庭版 / 个人版，且采用 `Files.ReadWrite.AppFolder` 权限结点时，将会是后者的情况。  

3. Q：为什么叫 PotatoSack？  
    
    A：因为咱服务器的性能和土豆差不多，备份数据就像扛土豆麻袋一样 （゜ー゜）。

如果还有其他问题，欢迎提出 issue。  

## 开源协议

本插件采用 MIT 开源协议。

感谢你的使用 (￣▽￣)"  