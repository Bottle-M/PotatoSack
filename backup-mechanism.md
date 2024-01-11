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
│   ├── world.json
│   ├── world_nether.json
│   └── world_the_end.json
└── 020240104000002 # 一组备份
    ├── backup.json # 备份记录文件
    ├── full.zip # 存放所有指定世界的全量备份
    ├── incre000001.zip # 存放所有指定世界的增量备份
    ├── incre000002.zip
    ├── incre000003.zip
    ├── world.json # 存放world世界目录下所有文件的修改情况（最后修改时间）
    ├── world_nether.json
    └── world_the_end.json
```

`full.zip`存放的就是所有指定世界的全量备份。

### backup.json

1. 记录上次**全量备份**的时间戳；
2. 记录上次**增量备份**的时间戳；
3. 记录本文件更新的时间戳。
4. 最后一组备份的组号，如`020240104000002`。
5. 上一次增量备份的序号，如`000001`。

### 世界名.json

存放各个世界数据目录中的所有文件的最后修改时间。

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

`deleted.files`文件中**每行记录一个**被删除的文件的相对路径。

## 从云端拉取备份记录文件

1. 对`AppFolder`进行列表。
2. 按照字典序进行降序排序，找到最新的**一组备份**。（比如上面的`020240104000002`）
3. 如果这一组备份是当天的（比如`020240104000002`对应2024年1月4日），则从目录中下载`backup.json`、`世界名.json`到本地。
4. 如果**相应记录文件不存在**或**这一组备份并不是当天建立**的，则[新建一组备份](#新建一组备份)。

## 记录文件在本地存放的位置

存放在插件目录的`data`子目录下，比如`backup.json`应该存放在`plugins/PotatoSack/data/backup.json`。

## 插件启动时

1. 检查本地（插件目录）是否有备份记录文件`backup.json`以及`世界名.json`
   ，如果没有则会[从云端尝试拉取](#从云端拉取备份记录文件)对应的备份记录文件`backup.json`和`世界名.json`。
    - 一个备份记录文件对应**一组备份**
    - 本地只存储当前一组备份的记录

2. 设立计时器，用于实现定时进行备份。计时器通过`backup.json`中的时间戳来判断是否需要进行备份。

## 新建一组备份

1. 移除本地备份记录文件`backup.json`和`世界名.json`。
2. 进行一次全量备份，重新生成备份记录文件`backup.json`和`世界名.json`。
3. 将第2步产生的文件传输到云端。
4. 删除过时的备份组。

## 进行增量备份

比如对`world`世界进行增量备份:

1. 读取`world.json`中存放的所有文件的最后修改时间集`timeSet1`。
2. 扫描`world`目录中的所有文件，得到所有文件的最后修改时间集`timeSet2`
3. 计算`timeSet1`和`timeSet2`的差集`diffSet`，差集中的是被删除的文件，将其写入`deleted.files`文件中。
4. 将有更新的文件打包成`incre*.zip`，更新`backup.json`和`world.json`。
5. 将第4步产生的文件传输到云端。