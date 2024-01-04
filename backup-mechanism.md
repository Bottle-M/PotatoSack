# PotatoSack 备份机制设计

## 云端备份存储结构

### 大概的目录结构

```bash
AppFolder
├── 020240104000001
│   ├── backups.json
│   ├── full.zip
│   ├── incre000001.zip
│   ├── incre000002.zip
│   ├── world.json
│   ├── world_nether.json
│   └── world_the_end.json
└── 020240104000002 # 一组备份
    ├── backups.json # 备份记录文件
    ├── full.zip # 存放所有指定世界的全量备份
    ├── incre000001.zip # 存放所有指定世界的增量备份
    ├── incre000002.zip
    ├── incre000003.zip
    ├── world.json # 存放world世界目录下所有文件的修改情况（最后修改时间）
    ├── world_nether.json
    └── world_the_end.json
```

`full.zip`存放的就是所有指定世界的全量备份。

### backups.json

1. 记录上次**全量备份**的时间戳；
2. 记录上次**增量备份**的时间戳；
3. 记录本文件更新的时间戳。

### 世界名.json

存放各个世界数据目录中的所有文件的最后修改时间。

### incre*.zip

存放指定世界的增量备份，压缩包内目录结构如下：

```bash
incre*.zip # 压缩包内
├── deleted.json # 标记相比上次增量备份，被删除的文件
├── world # 世界数据
│  ├── level.dat
│  ├── level.dat_old
│  ├── paper-world.yml
│  ├── region
│  │  ├── r.0.-1.mca
│  │  ├── r.0.0.mca
│  │  ├── r.0.1.mca
│  │  └── ...
│  └── ...
├── world_nether # 世界数据
│  ├── level.dat
│  ├── session.lock
│  └── ...
└── world_the_end # 世界数据
    ├── level.dat
    └── ...
```

为了将被删除的文件考虑在内，这里还设置了一个`deleted.json`来进行标记。

## 插件启动时

1. 检查本地（插件目录）是否有备份记录文件`current-backups.json`，如果没有则会从云端尝试拉取对应的备份记录文件`backups.json`。
   - 一个备份记录文件对应**一组备份**
   - 本地只存储当前一组备份的记录