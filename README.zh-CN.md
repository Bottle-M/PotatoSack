# PotatoSack

![Logo](./memos/pics/potatosack-logo-transparent-smaller.png)  

_放轻松些，我的老伙计，这袋儿土豆可比苏珊婶婶家的大理石雕像好搬多了！_

Lang: 中文简体 | [English](README.md)  

## 这是啥子哟

这是一个咱为自己 Minecraft 服务器写的一个简单实用的备份插件，可以对**指定目录的数据**进行备份。支持**增量备份/全量备份**机制。  

备份的存档**不会留存在本地**，而是上传至 **OneDrive**、**Dropbox** 等云端存储服务的目录中。

* 支持的 Minecraft 版本: **1.19+**

* ✨ 本插件在压缩上传文件时可以**几乎不占用**多余的本地硬盘空间，适用于服务提供商对硬盘空间进行了限制的场景。详见[概念介绍](#概念介绍)。

> 本插件目前已经支持 OneDrive (**非世纪互联版**)，Dropbox。  

## 概念介绍

<details>

<summary>展开查看</summary>

### 一组备份

“一组备份”指的是【一个全量备份 + 其后的增量备份 (直至下一次全量备份前的)】。  

每新产生一次全量备份，就会新创建“一组备份”。随后直至下次全量备份前的增量备份都会算在这一组里。  

详见[备份目录结构](memos/backup-mechanism.md#云端备份存储结构)。

### 流式压缩上传

本插件的“流式压缩上传”指的是边压缩文件边上传到云端的备份方式，采用了时间换空间的思路，仅占用较少的内存空间（用作缓冲区），几乎不会占用多余的硬盘空间。  

> 时间换空间是因为 **OneDrive API 要求大文件上传前必须知道确切的最终文件大小**，因此在选择 OneDrive 作为存储服务时，需要额外进行一趟模拟压缩来对文件大小进行计算。  
> 如果是 Dropbox，则甚至不需要时间换空间，因为 Dropbox 并不要求提前知道文件总大小（不过服务端如果部署在中国大陆，你可能需要为其加上代理以确保可以访问到 Dropbox API （；´д｀）ゞ）。   

传统的备份方式是将待备份文件先临时压缩为压缩包，再上传到云端，这种方式要求硬盘空间能容纳下待备份文件 + 产生的压缩包。  

然而，很多服务提供商会限制硬盘的可用空间。假如可用空间只有 10 GiB，而世界存档数据就占用了 7 GiB，那么硬盘剩余的空间是不太能容纳下产生的压缩包的，也就会导致备份失败。

</details>


## 怎么安装此插件？

1. 在[这里](https://github.com/Bottle-M/PotatoSack/releases/latest)下载插件。(`PotatoSack*.jar`)  
2. 把插件复制到你服务器目录下的 `plugins` 目录中。
3. 启动服务器，插件会在 `plugins/PotatoSack` 目录下生成初始配置文件 `configs.yml`，你需要在此文件中对插件进行必要的[配置](#配置文件)。
4. 修改配置后重启服务器即可。如果见到下面这样的日志内容，说明 PotatoSack 插件启动成功。  
    ```log
    [12:52:32 INFO]: [PotatoSack] PotatoSack successfully initialized! Savor using it!
    ```

## 配置文件

首次启动插件时，配置文件会被生成到 `plugins/PotatoSack/configs.yml`。  

```yaml
# PotatoSack 配置版本 (用于判断插件配置是否过于陈旧，需要更新)
version: '2.0.0'

client:
    # 选择你想要采用的存储服务提供方 (onedrive / dropbox)
    use: onedrive
    # 在存储服务中保存备份文件的基础目录路径（默认为空，表示云端根目录下）
    # 比如你把 base-dir 设为 "my/backups"，备份数据就会被存到 "云存储/my/backups/PotatoSack/..." 目录下
    # 注：就算你在最前面或者最后面输入了 "/"，程序也会自动将其移除，比如 "/my/backups/" -> "my/backups"
    base-dir: ""
    # OneDrive API 配置 (支持 OneDrive 企业版 (ODB) / 个人版 (ODC))
    # 开发文档参考: https://learn.microsoft.com/en-us/graph/auth-v2-user?tabs=http#5-use-the-refresh-token-to-get-a-new-access-token
    # 工具: https://github.com/yaonyan/ms-graph-cli  
    onedrive:
        # use-app-folder 指明是否使用 AppFolder 作为存储根目录
        # 关于 AppFolder: 
        #   [1] https://learn.microsoft.com/en-us/onedrive/developer/rest-api/concepts/special-folders-appfolder
        #   [2] https://learn.microsoft.com/en-us/graph/onedrive-sharepoint-appfolder 
        # 尽管 OneDrive 个人版 (ODC) 完全支持了 App Folder 特性，而 OneDrive 企业版 (ODB) 支持可能还不完善，
        # 但仍然还是推荐启用这个选项，以支持更安全的数据隔离
        # 如果启用这个选项，个人版需要 Files.ReadWrite.AppFolder 权限，而企业版可能需要 Files.ReadWrite.All 权限
        use-app-folder: true
        client-id:
        client-secret:
        refresh-token:
    # Dropbox API 配置
    # 需要有 files.content.write, files.content.read, files.metadata.read 权限
    dropbox:
        app-key:
        app-secret:
        refresh-token:

# 你想要保留的历史备份组数.
# 注："一组备份 "包括一个全量备份和其后的增量备份（在下一个全量备份之前的）。
max-full-backups-retained: 10

cron:
    # 用于安排全量备份时间的 UNIX Cron 表达式
    # 例: "0 2 */1 * *" 表示每天凌晨两点检查是否需要全量备份
    full-backup: "0 2 */1 * *"

    # 用于安排增量备份时间的 UNIX Cron 表达式
    # 例: "*/30 * * * *" 表示每 30 分钟检查一次是否需要增量备份.
    incremental-backup: "*/30 * * * *"

# 是否在没有玩家加入游戏的情况下暂停增量备份
# true: 如果自从上一次全量 / 增量备份起没有玩家加入过游戏，则跳过增量备份
# false: 老老实实按照 cron.incremental-backup 的安排进行增量备份
stop-incremental-backup-when-no-player: true

# 是否在没有玩家加入游戏的情况下暂停全量备份
# true: 如果自从上一次全量备份起没有玩家加入过游戏，则跳过增量备份
# false: 老老实实按照 cron.full-backup 的安排进行增量备份
stop-full-backup-when-no-player: false

# 是否采用流式压缩上传
# 注: 当服务器硬盘空间不够大时可以启用此选项。
# 注: 这种方式下程序只会将每块压缩文件数据暂时写入内存中的缓冲区，代价并不高。
use-streaming-compression-upload: false

# 你想要备份的目录路径（绝对路径或相对服务端根目录的相对路径），示例如下:
# paths:
#   - world
#   - ./world_nether
#   - world_the_end
#
# After Minecraft JE 26.1, it can be:
# paths:
#   - /workspace/server/world
#   - plugins/GroupManager
#
# 注 1: 如果 paths 留空，插件就不会工作辣！(∪.∪ )...zzz
# 注 2: 所有的路径都应该指向服务端根目录下级的目录
# 注 3: 你可以在服务端根目录下放一个 .potatosackignore 文件来让插件在备份时忽略掉某些文件或者目录（详情可以看 README）
# 注 4: 配置的路径不可以是服务端根目录
# 注 5: paths 中的路径不能有互相交叠的情况（比如你不能同时配置 'world' 和 'world/playerdata'）  
paths: [ ]
```

## 忽略特定文件的备份

你可以在**服务端根目录下**放一个 `.potatosackignore` 文件，以类似 `.gitignore` 的语法来忽略特定文件或者目录的备份。  

为了降低实现复杂度，和 `.gitignore` 不一样的两点如下：  

1. 只能放在服务端根目录下
2. 不支持取反 `!` 语法

示例如下：  

```gitignore
# 注释：以 # 开头的行为注释，空行也会被忽略

# 末尾 / 表示仅匹配目录；不含末尾斜杠时文件和目录都匹配。
# 以下两条都会在任意层级生效：
#   logs/  命中 world/logs/、a/b/logs/ 等目录，但不会命中文件
#   temp   命中 temp、plugins/temp、x/y/temp，文件或目录均可
logs/
temp

# * 匹配单段内任意字符（不含 /），此处匹配任意层级下的 .log 文件
*.log

# ? 匹配单个字符（不含 /），匹配 cache1.dat、cacheX.dat 等
cache?.dat

# 行首 / 锚定到服务端根目录，仅匹配根目录下的 temp.db
/temp.db

# 含中间斜杠也锚定到根，仅匹配 服务端根目录/world/region/ 下的 .mca
world/region/*.mca

# ** 跨段匹配零或多层目录，此处匹配 logs/ 下所有内容
logs/**

# 中间写 ** 同样匹配零或多个目录：命中 a/c、a/x/c、a/x/y/c 等
a/**/c
```

> 以防你不知道，服务端根目录就是**服务端 jar 包所在的目录**（如 `server.jar`）。  

## 命令

这个插件目前只有一个命令:  

```text
/potatosack reload
```

用于热重载插件配置。

* 命令权限节点: `potatosack.reload`

> 💡 服务器管理员（OP）默认有这个权限。  

## 小工具

### Backup Merger  

上文提到过，“一组备份”包括一个全量备份和一些增量备份。在恢复服务器数据的时候，BackupMerger 可以将这些备份合并成一个完整的备份。

详见 [BackupMerger](backups-merger/README.zh_CN.md)。  

### OneDrive Token Tool

通过这个工具你可以获取到编写配置时所需的 OneDrive 的 Refresh Token。  

详见 [onedrive-token-tool](onedrive-token-tool/README.zh_CN.md)。

## FAQ

1. 首次启动时控制台怎么打印出了 404 ？

    * 这往往是因为云端的文件缺失或相应目录未建立，不过不用担心，程序在遇到 404 响应后会自动建立相应文件和目录。  

2. 备份数据会被上传到云端哪个位置？  

    * 如果是 OneDrive：取决于 `configs.yml` 中 `client.onedrive.use-app-folder` 的设置——
      * `true`（默认）：`OneDrive 根目录/应用/<应用程序名>/<base-dir>/PotatoSack`（App Folder）
      * `false`：`OneDrive 根目录/<base-dir>/PotatoSack`  

    * 如果是 Dropbox: `Dropbox 根目录/<base-dir>/PotatoSack` 或者 `Dropbox 根目录/应用/<你创建的应用名>/<base-dir>/PotatoSack`。Dropbox 上创建应用时如果勾选采用 App Folder 访问限制，则就是后者（也建议这样）。  

    * (`<base-dir>` 即你在 `configs.yml` 中配置的 `client.base-dir`)  

3. 为什么叫 PotatoSack？  
    
    * 因为咱服务器的性能和土豆差不多，备份数据就像扛土豆麻袋一样 （゜ー゜）  

如果还有其他问题，欢迎提 Issue。  

## 开源协议

本插件采用 MIT 开源协议。

感谢你的使用 (￣▽￣)"  