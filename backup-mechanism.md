# PotatoSack 备份机制设计

## 云端备份存储结构

### 大概的目录结构

```bash
AppFolder
├── 020240104000001
│   ├── backup.json
│   ├── full.zip
│   ├── incre000001.zip
│   ├── incre000002.zip
│   ├── _world.json
│   ├── _world_nether.json
│   └── _world_the_end.json
└── 020240104000002 # 一组备份
    ├── backup.json # 备份记录文件
    ├── full.zip # 存放所有指定世界的全量备份
    ├── incre000001.zip # 存放所有指定世界的增量备份
    ├── incre000002.zip
    ├── incre000003.zip
    ├── _world.json # 存放world世界目录下所有文件的修改情况（最后的MD5哈希）
    ├── _world_nether.json
    └── _world_the_end.json
```

`full.zip`存放的就是所有指定世界的全量备份。

### backup.json

1. 记录上次**全量备份**的时间戳 `last_full_backup_time`；
2. 记录上次**增量备份**的时间戳 `last_incre_backup_time`；
3. 记录此文件更新的时间戳 `file_update_time`。
4. 最新一组（当前这组）备份的组号 `last_full_backup_id`，如`020240104000002`。
5. 上一次增量备份的序号 `last_incre_backup_id`，如`000001`。
6. 本组每一个增量备份创建时的时间戳 `incre_backups_history`。

文件 `backup.json` 示例如下:

```json
{
    "last_full_backup_time": 1716931489,
    "last_incre_backup_time": 1716934020,
    "file_update_time": 1716934020,
    "last_full_backup_id": "020240621000001",
    "last_incre_backup_id": "000002",
    "incre_backups_history": [
       {
        "id": "000001",
        "time": 1716932749
       }, 
       {
        "id": "000002",
        "time": 1716934020
       }
    ]
}
```

### _世界名.json

文件名前加一个下划线是为了防止有世界叫 "`backup`"，和 `backup.json` 冲突。

1. 记录某个世界数据目录中的所有文件的最后哈希值 `last_file_hashes`。
   * 键值对: `<相对于服务端根目录的路径, MD5 哈希值>`
2. 记录此文件的更新时间戳 `file_update_time`。

文件 `_world.json` 示例如下:  

```json
{
    "file_update_time": 1717853394,
    "last_file_hashes": {
        "world/playerdata/911cb843-480d-4c4c-80e2-1d4b1234ab68.dat_old": "8e61dc5106573480912f8f1f73c050ba",
        "world/entities/r.-7.3.mca": "ded5a6b1fa64387e4b05836a686c6e11",
        "...": "..."
    }
}
```

### incre*.zip

存放指定世界的增量备份，压缩包内目录结构如下：

```bash
incre*.zip # 压缩包内
├── deleted.files # 标记相比上次增量备份，被删除的文件
├── world # 世界数据
│   ├── level.dat
│   ├── level.dat_old
│   ├── paper-world.yml
│   ├── region
│   │  ├── r.0.-1.mca
│   │  ├── r.0.0.mca
│   │  ├── r.0.1.mca
│   │  └── ...
│   └── ...
├── world_nether # 世界数据
│   ├── level.dat
│   ├── session.lock
│   └── ...
└── world_the_end # 世界数据
    ├── level.dat
    └── ...
```

为了将被删除的文件考虑在内，这里还设置了一个`deleted.files`来进行标记。

`deleted.files` 文件中**每行记录一个**被删除的文件的相对路径（相对于服务端根目录）。

## 从云端拉取备份记录文件

1. 对`AppFolder`进行列表。
2. 按照字典序进行降序排序，找到最新的**一组备份**。（比如上面的`020240104000002`）
3. 如果这一组备份是当天的（比如`020240104000002`对应2024年1月4日），则从目录中下载`backup.json`、`_世界名.json`到本地。
4. 如果**相应记录文件不存在**或**这一组备份并不是当天建立**的，则[新建一组备份](#新建一组备份)。

## 记录文件在本地存放的位置

存放在插件目录的 `data` 子目录下，比如 `backup.json` 应该存放在 `plugins/PotatoSack/data/backup.json`。

## 插件启动时

1. 检查本地（插件目录）是否有备份记录文件`backup.json`以及`_世界名.json`
   ，如果没有则会[从云端尝试拉取](#从云端拉取备份记录文件)对应的备份记录文件`backup.json`和`_世界名.json`。
    - 一个备份记录文件对应**一组备份**
    - 本地只存储当前一组备份的记录

2. 设立计时器，用于实现定时进行备份。计时器通过`backup.json`中的时间戳来判断是否需要进行备份。

## 新建一组备份

1. 进行一次全量备份，若成功则更新备份记录文件 `backup.json` 和 `_世界名.json`。
2. 将第2步产生的文件传输到云端。
3. 删除过时的备份组（插件配置中可以配置最多保留几组）。

## 进行增量备份

比如对`world`世界进行增量备份:

1. 读取`world.json`中存放的所有文件的最后md5哈希值`md5Set1`。
2. 扫描`world`目录中的所有文件，得到所有文件的最后md5哈希值`md5Set2`
3. 计算`md5Set1`和`md5Set2`的差集`diffSet`，差集中的是被删除的文件，将其写入`deleted.files`文件中。
4. 将有更新的文件打包成`incre*.zip`，更新`backup.json`和`world.json`。
5. 将第4步产生的文件传输到云端。

## 流式压缩上传 

* 2024.2.18 新增  

这天突然想到一个经常遇到的问题，对于一些小服主（比如我）来说，常常会租面板服来搭建朋友间一起玩的小服务器。  

有些面板服可能会在硬盘资源上比较吝啬（其实即使 VPS 也是如此），比如限制分配给服务器容器的硬盘空间上限为 10 GiB，这种时候，如果服务器存档**在压缩后**还有 5 GiB 以上，则插件肯定没法先把生成的 `zip` 文件全写入临时目录里，然后再上传。（而这是本插件的默认备份上传策略）  

解决方法呢，其实就是把 `ZipOutputStream` 生成的数据直接上传到云端。  

只需要在内存中维护一个 `CHUNK_SIZE` （大文件分块上传的每块大小）大小的缓冲区，让 zip 流输出到这个缓冲区里，一旦满了就把这一块立即上传到云端，不停复用这一块缓冲区。  

* **问题**：OneDrive 上传大文件前必须要知道**确切的**文件大小，而这里 zip 流的输出是和上传几乎同时进行的，怎么确定生成的 zip 文件总大小？  
* **解决**：时间换空间。先跑一遍压缩过程，对于生成的每个字节仅计数，随即抛弃，这样就可以算出来较为确切的 zip 文件总大小。然后再进行分块压缩上传的操作。（目前好像也只有这种解决方案了，代价是需要 CPU 跑两趟）  
* **注意**：需要严格保证两次 `ZipOutputStream` 输出的字节数一致，因此在备份过程中需要**临时停止 Minecraft 世界自动保存**。  
* 即使停止了自动保存也有可能两次 `ZipOutputStream` 输出的字节数不一致，因此本插件还配备了一些重试机制。

## 流式压缩上传容错能力 - 日志展示

### 因网络异常而多次重试

<details>

<summary>展开查看日志</summary>

```log
[19:49:03] [Server thread/INFO]: Done (27.549s)! For help, type "help"
[19:49:03] [Craft Scheduler Thread - 1 - GroupManager/INFO]: [GroupManager] 没有可用的更新
[19:54:03] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Making full backup...
[19:54:06] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] ------>[ Using Streaming Compression Upload ]<------
[19:54:06] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] [DEBUG] Auto-save stopped, affected Worlds: world, world_nether, world_the_end, explorationWorld
[19:54:06] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Temporarily stopped world auto-save...
[19:54:06] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Waiting for 30s before backup start...
[19:54:38] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Calculating file size... 
[19:55:25] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Calculation success. Files size in total: 1161437146
[19:55:25] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Total size: 1161437146
[19:55:25] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing and uploading... 
[19:55:26] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 0-16383999/1161437146 Byte(s)
[19:55:27] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:28] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 16384000-32767999/1161437146 Byte(s)
[19:55:29] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:30] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 32768000-49151999/1161437146 Byte(s)
[19:55:31] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:32] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 49152000-65535999/1161437146 Byte(s)
[19:55:33] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:34] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 65536000-81919999/1161437146 Byte(s)
[19:55:35] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:36] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 81920000-98303999/1161437146 Byte(s)
[19:55:37] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:38] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 98304000-114687999/1161437146 Byte(s)
[19:55:39] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:40] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 114688000-131071999/1161437146 Byte(s)
[19:55:41] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:42] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 131072000-147455999/1161437146 Byte(s)
[19:55:44] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:45] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 147456000-163839999/1161437146 Byte(s)
[19:55:47] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:48] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 163840000-180223999/1161437146 Byte(s)
[19:55:50] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:50] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 180224000-196607999/1161437146 Byte(s)
[19:55:52] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:52] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 196608000-212991999/1161437146 Byte(s)
[19:55:53] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:54] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 212992000-229375999/1161437146 Byte(s)
[19:55:55] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:56] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 229376000-245759999/1161437146 Byte(s)
[19:55:58] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:55:59] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 245760000-262143999/1161437146 Byte(s)
[19:56:00] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:56:01] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 262144000-278527999/1161437146 Byte(s)
[19:56:02] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:56:03] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 278528000-294911999/1161437146 Byte(s)
[19:56:04] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:56:05] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 294912000-311295999/1161437146 Byte(s)
[19:56:06] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:56:07] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 311296000-327679999/1161437146 Byte(s)
[19:56:08] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:56:09] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 327680000-344063999/1161437146 Byte(s)
[19:56:12] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:56:12] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 344064000-360447999/1161437146 Byte(s)
[19:56:14] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:56:14] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 360448000-376831999/1161437146 Byte(s)
[19:56:16] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[19:56:17] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 376832000-393215999/1161437146 Byte(s)
[20:02:30] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Retrying to request due to network issues...(1/3)
[20:02:35] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Retrying to request due to network issues...(2/3)
[20:02:40] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Retrying to request due to network issues...(3/3)
[20:02:45] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Failed to request, retrying to upload in 10 seconds...
[20:02:55] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 376832000-393215999/1161437146 Byte(s)
[20:02:56] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Fragment overlap, querying server to determine whether the transfer can proceed.
[20:02:57] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Get next expected range successfully.
[20:02:57] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Next expect range start: 385187840, current rangeEnd: 393215999
[20:02:57] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 385187840-393215999/1161437146 Byte(s)
[20:02:57] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:02:58] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 393216000-409599999/1161437146 Byte(s)
[20:02:59] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:00] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 409600000-425983999/1161437146 Byte(s)
[20:03:02] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:02] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 425984000-442367999/1161437146 Byte(s)
[20:03:04] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:04] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 442368000-458751999/1161437146 Byte(s)
[20:03:06] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:06] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 458752000-475135999/1161437146 Byte(s)
[20:03:08] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:08] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 475136000-491519999/1161437146 Byte(s)
[20:03:10] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:10] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 491520000-507903999/1161437146 Byte(s)
[20:03:12] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:13] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 507904000-524287999/1161437146 Byte(s)
[20:03:14] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:15] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 524288000-540671999/1161437146 Byte(s)
[20:03:16] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:17] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 540672000-557055999/1161437146 Byte(s)
[20:03:18] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:19] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 557056000-573439999/1161437146 Byte(s)
[20:03:20] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:21] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 573440000-589823999/1161437146 Byte(s)
[20:03:22] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:23] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 589824000-606207999/1161437146 Byte(s)
[20:03:24] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:25] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 606208000-622591999/1161437146 Byte(s)
[20:03:26] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:27] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 622592000-638975999/1161437146 Byte(s)
[20:03:28] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:29] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 638976000-655359999/1161437146 Byte(s)
[20:03:31] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:31] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 655360000-671743999/1161437146 Byte(s)
[20:03:33] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:33] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 671744000-688127999/1161437146 Byte(s)
[20:03:35] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:35] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 688128000-704511999/1161437146 Byte(s)
[20:03:37] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:37] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 704512000-720895999/1161437146 Byte(s)
[20:03:48] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Retrying to request due to network issues...(1/3)
[20:03:54] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Fragment overlap, querying server to determine whether the transfer can proceed.
[20:03:54] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Get next expected range successfully.
[20:03:54] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Next expect range start: 720896000, current rangeEnd: 720895999
[20:03:54] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Resuming upload...
[20:03:55] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 720896000-737279999/1161437146 Byte(s)
[20:03:56] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:57] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 737280000-753663999/1161437146 Byte(s)
[20:03:58] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:03:59] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 753664000-770047999/1161437146 Byte(s)
[20:04:00] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:01] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 770048000-786431999/1161437146 Byte(s)
[20:04:02] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:03] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 786432000-802815999/1161437146 Byte(s)
[20:04:04] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:05] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 802816000-819199999/1161437146 Byte(s)
[20:04:06] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:07] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 819200000-835583999/1161437146 Byte(s)
[20:04:08] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:09] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 835584000-851967999/1161437146 Byte(s)
[20:04:10] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:11] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 851968000-868351999/1161437146 Byte(s)
[20:04:13] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:13] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 868352000-884735999/1161437146 Byte(s)
[20:04:15] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:15] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 884736000-901119999/1161437146 Byte(s)
[20:04:17] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:17] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 901120000-917503999/1161437146 Byte(s)
[20:04:19] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:19] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 917504000-933887999/1161437146 Byte(s)
[20:04:21] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:21] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 933888000-950271999/1161437146 Byte(s)
[20:04:23] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:24] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 950272000-966655999/1161437146 Byte(s)
[20:04:25] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:26] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 966656000-983039999/1161437146 Byte(s)
[20:04:27] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:28] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 983040000-999423999/1161437146 Byte(s)
[20:04:29] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:30] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 999424000-1015807999/1161437146 Byte(s)
[20:04:31] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:32] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1015808000-1032191999/1161437146 Byte(s)
[20:04:33] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:34] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1032192000-1048575999/1161437146 Byte(s)
[20:04:35] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:36] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1048576000-1064959999/1161437146 Byte(s)
[20:04:37] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:38] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1064960000-1081343999/1161437146 Byte(s)
[20:04:39] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:40] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1081344000-1097727999/1161437146 Byte(s)
[20:04:41] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:42] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1097728000-1114111999/1161437146 Byte(s)
[20:04:43] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:44] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1114112000-1130495999/1161437146 Byte(s)
[20:04:45] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:46] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1130496000-1146879999/1161437146 Byte(s)
[20:04:47] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:48] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Closing upload stream.
[20:04:48] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1146880000-1161437142/1161437146 Byte(s)
[20:04:49] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:49] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Padding blanks to fit the calculated size...
[20:04:49] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1161437143-1161437145/1161437146 Byte(s)
[20:04:49] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[20:04:49] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Compression / upload success. Total size: 1161437146 Byte(s)
[20:04:49] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] [DEBUG] Auto-save started, affected Worlds: world, world_nether, world_the_end, explorationWorld
[20:04:49] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Restarted world auto-save...
[20:04:49] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Uploading Record Files...
[20:04:51] [Craft Scheduler Thread - 1 - PotatoSack/INFO]: [PotatoSack] Successfully made backup: 020240621000001
[20:08:27] [Craft Scheduler Thread - 7 - spark/INFO]: [⚡] TPS from last 5s, 10s, 1m, 5m, 15m:
[20:08:27] [Craft Scheduler Thread - 7 - spark/INFO]: [⚡]  20.0, 20.0, *20.0, *20.0, 20.0
[20:08:27] [Craft Scheduler Thread - 7 - spark/INFO]: [⚡] 
[20:08:27] [Craft Scheduler Thread - 7 - spark/INFO]: [⚡] Tick durations (min/med/95%ile/max ms) from last 10s, 1m:
[20:08:27] [Craft Scheduler Thread - 7 - spark/INFO]: [⚡]  0.9/1.1/1.5/8.7;  0.9/1.1/1.7/8.7
[20:08:27] [Craft Scheduler Thread - 7 - spark/INFO]: [⚡] 
[20:08:27] [Craft Scheduler Thread - 7 - spark/INFO]: [⚡] CPU usage from last 10s, 1m, 15m:
[20:08:27] [Craft Scheduler Thread - 7 - spark/INFO]: [⚡]  4%, 5%, 11%  (system)
[20:08:27] [Craft Scheduler Thread - 7 - spark/INFO]: [⚡]  4%, 5%, 11%  (process)
```

</details>

* 第一次因为网络原因传输中断时，分片只上传了一部分，程序在查询 OneDrive 接口后能恢复传输。
* 第二次因为网络原因传输中断时，整个分片实际上已经上传完，程序在询问 OneDrive 接口后能恢复，从下一个分片继续开始传输。
* 在最后数据传输完毕后，程序发现比预先模拟压缩计算出来的文件大小要小一点，通过填充空白字节进行了弥补，让文件成功完成传输。

### 服务端异步写入导致的文件大小偏差问题

<details>

<summary>展开查看日志</summary>

```log
[21:27:14] [Craft Scheduler Thread - 126 - PotatoSack/INFO]: [PotatoSack] Onedrive token refreshed
[21:29:12] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Making full backup...
[21:29:16] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] ------>[ Using Streaming Compression Upload ]<------
[21:29:16] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] [DEBUG] Auto-save stopped, affected Worlds: world, world_nether, world_the_end, explorationWorld
[21:29:16] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Temporarily stopped world auto-save...
[21:29:16] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Waiting for 30s before backup start...
[21:29:47] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Calculating file size... 
[21:30:35] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Calculation success. Files size in total: 1161452558
[21:30:35] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Total size: 1161452558
[21:30:35] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing and uploading... 
[21:30:36] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 0-16383999/1161452558 Byte(s)
[21:30:39] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:30:39] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 16384000-32767999/1161452558 Byte(s)
[21:30:42] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:30:42] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 32768000-49151999/1161452558 Byte(s)
[21:30:44] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:30:44] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 49152000-65535999/1161452558 Byte(s)
[21:30:46] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:30:46] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 65536000-81919999/1161452558 Byte(s)
[21:30:50] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:30:50] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 81920000-98303999/1161452558 Byte(s)
[21:30:53] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:30:54] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 98304000-114687999/1161452558 Byte(s)
[21:30:55] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:30:56] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 114688000-131071999/1161452558 Byte(s)
[21:30:57] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:30:58] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 131072000-147455999/1161452558 Byte(s)
[21:30:59] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:00] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 147456000-163839999/1161452558 Byte(s)
[21:31:02] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:02] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 163840000-180223999/1161452558 Byte(s)
[21:31:04] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:04] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 180224000-196607999/1161452558 Byte(s)
[21:31:06] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:07] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 196608000-212991999/1161452558 Byte(s)
[21:31:08] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:09] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 212992000-229375999/1161452558 Byte(s)
[21:31:10] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:11] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 229376000-245759999/1161452558 Byte(s)
[21:31:12] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:13] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 245760000-262143999/1161452558 Byte(s)
[21:31:14] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:15] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 262144000-278527999/1161452558 Byte(s)
[21:31:16] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:17] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 278528000-294911999/1161452558 Byte(s)
[21:31:18] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:19] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 294912000-311295999/1161452558 Byte(s)
[21:31:20] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:21] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 311296000-327679999/1161452558 Byte(s)
[21:31:22] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:23] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 327680000-344063999/1161452558 Byte(s)
[21:31:24] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:25] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 344064000-360447999/1161452558 Byte(s)
[21:31:27] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:28] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 360448000-376831999/1161452558 Byte(s)
[21:31:29] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:30] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 376832000-393215999/1161452558 Byte(s)
[21:31:31] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:32] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 393216000-409599999/1161452558 Byte(s)
[21:31:33] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:34] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 409600000-425983999/1161452558 Byte(s)
[21:31:35] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:36] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 425984000-442367999/1161452558 Byte(s)
[21:31:37] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:38] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 442368000-458751999/1161452558 Byte(s)
[21:31:39] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 458752000-475135999/1161452558 Byte(s)
[21:31:41] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:42] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 475136000-491519999/1161452558 Byte(s)
[21:31:43] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:44] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 491520000-507903999/1161452558 Byte(s)
[21:31:45] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:46] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 507904000-524287999/1161452558 Byte(s)
[21:31:47] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:48] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 524288000-540671999/1161452558 Byte(s)
[21:31:49] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:50] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 540672000-557055999/1161452558 Byte(s)
[21:31:51] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:52] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 557056000-573439999/1161452558 Byte(s)
[21:31:53] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:54] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 573440000-589823999/1161452558 Byte(s)
[21:31:56] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:56] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 589824000-606207999/1161452558 Byte(s)
[21:31:58] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:31:58] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 606208000-622591999/1161452558 Byte(s)
[21:32:00] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:00] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 622592000-638975999/1161452558 Byte(s)
[21:32:01] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:02] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 638976000-655359999/1161452558 Byte(s)
[21:32:03] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:04] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 655360000-671743999/1161452558 Byte(s)
[21:32:06] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:06] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 671744000-688127999/1161452558 Byte(s)
[21:32:08] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:08] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 688128000-704511999/1161452558 Byte(s)
[21:32:10] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:10] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 704512000-720895999/1161452558 Byte(s)
[21:32:12] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:12] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 720896000-737279999/1161452558 Byte(s)
[21:32:14] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:15] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 737280000-753663999/1161452558 Byte(s)
[21:32:16] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:17] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 753664000-770047999/1161452558 Byte(s)
[21:32:18] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:18] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 770048000-786431999/1161452558 Byte(s)
[21:32:20] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:21] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 786432000-802815999/1161452558 Byte(s)
[21:32:22] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:23] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 802816000-819199999/1161452558 Byte(s)
[21:32:24] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:25] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 819200000-835583999/1161452558 Byte(s)
[21:32:26] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:27] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 835584000-851967999/1161452558 Byte(s)
[21:32:28] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:29] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 851968000-868351999/1161452558 Byte(s)
[21:32:30] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:31] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 868352000-884735999/1161452558 Byte(s)
[21:32:32] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:33] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 884736000-901119999/1161452558 Byte(s)
[21:32:34] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:35] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 901120000-917503999/1161452558 Byte(s)
[21:32:36] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:37] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 917504000-933887999/1161452558 Byte(s)
[21:32:38] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:39] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 933888000-950271999/1161452558 Byte(s)
[21:32:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:41] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 950272000-966655999/1161452558 Byte(s)
[21:32:42] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:43] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 966656000-983039999/1161452558 Byte(s)
[21:32:44] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:45] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 983040000-999423999/1161452558 Byte(s)
[21:32:46] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:47] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 999424000-1015807999/1161452558 Byte(s)
[21:32:48] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:49] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1015808000-1032191999/1161452558 Byte(s)
[21:32:51] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:52] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1032192000-1048575999/1161452558 Byte(s)
[21:32:53] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:54] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1048576000-1064959999/1161452558 Byte(s)
[21:32:55] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:56] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1064960000-1081343999/1161452558 Byte(s)
[21:32:57] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:32:58] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1081344000-1097727999/1161452558 Byte(s)
[21:32:59] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:33:00] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1097728000-1114111999/1161452558 Byte(s)
[21:33:01] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:33:02] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1114112000-1130495999/1161452558 Byte(s)
[21:33:03] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:33:04] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1130496000-1146879999/1161452558 Byte(s)
[21:33:06] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:33:06] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Closing upload stream.
[21:33:06] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1146880000-1161452561/1161452558 Byte(s)
[21:33:07] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Data size overflowed the previous agreed size. Retrying... 
[21:33:07] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Calculating file size... 
[21:33:55] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Calculation success. Files size in total: 1161452562
[21:33:55] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Padding size: 20
[21:33:55] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Total size: 1161452582
[21:33:55] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing and uploading... 
[21:33:56] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 0-16383999/1161452582 Byte(s)
[21:33:57] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:33:58] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 16384000-32767999/1161452582 Byte(s)
[21:33:59] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:00] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 32768000-49151999/1161452582 Byte(s)
[21:34:01] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:02] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 49152000-65535999/1161452582 Byte(s)
[21:34:03] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:03] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 65536000-81919999/1161452582 Byte(s)
[21:34:05] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:06] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 81920000-98303999/1161452582 Byte(s)
[21:34:07] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:08] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 98304000-114687999/1161452582 Byte(s)
[21:34:09] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:10] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 114688000-131071999/1161452582 Byte(s)
[21:34:11] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:12] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 131072000-147455999/1161452582 Byte(s)
[21:34:14] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:15] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 147456000-163839999/1161452582 Byte(s)
[21:34:16] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:16] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 163840000-180223999/1161452582 Byte(s)
[21:34:18] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:18] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 180224000-196607999/1161452582 Byte(s)
[21:34:20] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:20] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 196608000-212991999/1161452582 Byte(s)
[21:34:22] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:22] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 212992000-229375999/1161452582 Byte(s)
[21:34:24] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:24] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 229376000-245759999/1161452582 Byte(s)
[21:34:26] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:26] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 245760000-262143999/1161452582 Byte(s)
[21:34:28] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:28] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 262144000-278527999/1161452582 Byte(s)
[21:34:30] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:30] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 278528000-294911999/1161452582 Byte(s)
[21:34:32] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:32] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 294912000-311295999/1161452582 Byte(s)
[21:34:34] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:34] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 311296000-327679999/1161452582 Byte(s)
[21:34:36] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:36] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 327680000-344063999/1161452582 Byte(s)
[21:34:38] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:38] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 344064000-360447999/1161452582 Byte(s)
[21:34:39] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 360448000-376831999/1161452582 Byte(s)
[21:34:41] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:42] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 376832000-393215999/1161452582 Byte(s)
[21:34:43] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:44] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 393216000-409599999/1161452582 Byte(s)
[21:34:45] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:46] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 409600000-425983999/1161452582 Byte(s)
[21:34:47] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:48] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 425984000-442367999/1161452582 Byte(s)
[21:34:49] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:50] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 442368000-458751999/1161452582 Byte(s)
[21:34:51] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:53] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 458752000-475135999/1161452582 Byte(s)
[21:34:54] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:55] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 475136000-491519999/1161452582 Byte(s)
[21:34:56] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:57] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 491520000-507903999/1161452582 Byte(s)
[21:34:58] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:34:59] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 507904000-524287999/1161452582 Byte(s)
[21:35:01] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:01] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 524288000-540671999/1161452582 Byte(s)
[21:35:03] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:03] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 540672000-557055999/1161452582 Byte(s)
[21:35:05] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:05] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 557056000-573439999/1161452582 Byte(s)
[21:35:07] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:07] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 573440000-589823999/1161452582 Byte(s)
[21:35:09] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:09] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 589824000-606207999/1161452582 Byte(s)
[21:35:11] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:11] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 606208000-622591999/1161452582 Byte(s)
[21:35:13] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:13] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 622592000-638975999/1161452582 Byte(s)
[21:35:15] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:15] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 638976000-655359999/1161452582 Byte(s)
[21:35:17] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:17] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 655360000-671743999/1161452582 Byte(s)
[21:35:19] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:19] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 671744000-688127999/1161452582 Byte(s)
[21:35:21] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:22] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 688128000-704511999/1161452582 Byte(s)
[21:35:23] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:24] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 704512000-720895999/1161452582 Byte(s)
[21:35:34] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Retrying to request due to network issues...(1/3)
[21:35:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Fragment overlap, querying server to determine whether the transfer can proceed.
[21:35:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Get next expected range successfully.
[21:35:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Next expect range start: 712867840, current rangeEnd: 720895999
[21:35:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 712867840-720895999/1161452582 Byte(s)
[21:35:41] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:42] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 720896000-737279999/1161452582 Byte(s)
[21:35:43] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:44] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 737280000-753663999/1161452582 Byte(s)
[21:35:46] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:46] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 753664000-770047999/1161452582 Byte(s)
[21:35:48] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:48] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 770048000-786431999/1161452582 Byte(s)
[21:35:50] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:51] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 786432000-802815999/1161452582 Byte(s)
[21:35:52] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:53] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 802816000-819199999/1161452582 Byte(s)
[21:35:54] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:55] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 819200000-835583999/1161452582 Byte(s)
[21:35:57] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:57] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 835584000-851967999/1161452582 Byte(s)
[21:35:59] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:35:59] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 851968000-868351999/1161452582 Byte(s)
[21:36:01] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:02] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 868352000-884735999/1161452582 Byte(s)
[21:36:03] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:04] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 884736000-901119999/1161452582 Byte(s)
[21:36:06] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:06] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 901120000-917503999/1161452582 Byte(s)
[21:36:08] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:08] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 917504000-933887999/1161452582 Byte(s)
[21:36:10] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:11] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 933888000-950271999/1161452582 Byte(s)
[21:36:12] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:13] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 950272000-966655999/1161452582 Byte(s)
[21:36:15] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:15] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 966656000-983039999/1161452582 Byte(s)
[21:36:17] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:17] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 983040000-999423999/1161452582 Byte(s)
[21:36:19] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:20] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 999424000-1015807999/1161452582 Byte(s)
[21:36:21] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:22] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1015808000-1032191999/1161452582 Byte(s)
[21:36:23] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:24] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1032192000-1048575999/1161452582 Byte(s)
[21:36:26] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:26] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1048576000-1064959999/1161452582 Byte(s)
[21:36:28] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:28] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1064960000-1081343999/1161452582 Byte(s)
[21:36:30] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:30] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1081344000-1097727999/1161452582 Byte(s)
[21:36:32] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:32] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1097728000-1114111999/1161452582 Byte(s)
[21:36:34] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:35] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1114112000-1130495999/1161452582 Byte(s)
[21:36:36] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:37] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1130496000-1146879999/1161452582 Byte(s)
[21:36:38] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:39] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Closing upload stream.
[21:36:39] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1146880000-1161452561/1161452582 Byte(s)
[21:36:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Padding blanks to fit the calculated size...
[21:36:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compressing + Uploading chunk: bytes 1161452562-1161452581/1161452582 Byte(s)
[21:36:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack]  --> Chunk successfully uploaded.
[21:36:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Compression / upload success. Total size: 1161452582 Byte(s)
[21:36:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] [DEBUG] Auto-save started, affected Worlds: world, world_nether, world_the_end, explorationWorld
[21:36:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Restarted world auto-save...
[21:36:40] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Uploading Record Files...
[21:36:42] [Craft Scheduler Thread - 125 - PotatoSack/INFO]: [PotatoSack] Successfully made backup: 020240622000001
[22:26:44] [Craft Scheduler Thread - 132 - PotatoSack/INFO]: [PotatoSack] Onedrive token refreshed
```

因为服务端还在异步写入硬盘文件，模拟压缩计算出的文件大小和实际上传的文件大小有偏差。  

* 第一次压缩上传时，模拟压缩计算出来的文件大小是 `1161452558` B，实际上传的文件大小是 `1161452562` B，多出了 `4` B。
* 重试压缩上传时，在末尾填充了 `平均溢出字节数 × 5` B，因为这里只出现了一次错误，平均溢出字节数就是 `4` ，因此填充了 `4 × 5 = 20` B。  

</details>