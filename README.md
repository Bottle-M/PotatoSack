# PotatoSack

![Logo](./memos/pics/potatosack-logo-transparent-smaller.png)  

_Take it easy, carrying a sack of potatoes isn't that hard!_

Lang: [中文简体](README.zh-CN.md) | English 

## What's this?

This is a simple backup plugin originally written for my Minecraft server to back up **world data**. It supports **incremental/full backup mechanism**.  

Backed-up archives are not stored locally, but are uploaded to the OneDrive cloud storage.  

* Supported Minecraft Versions: **1.19+**  

* ✨ This plugin can compress and upload files **with little** local disk space usage, thus is suitable for scenarios where the service provider has imposed a limit on disk space. See the [Concepts](#concepts) for more details.

> Currently, the plugin only supports OneDrive (non-21Vianet version).

## Concepts

<details>

<summary>Expand / Collapse</summary>

### A Group of Backups

"A Group of Backups" refers to "one full backup + subsequent incremental backups (before the next full backup)".   

Every time a new full backup is created, a new "group of backups" is created. Subsequent incremental backups before the next full backup will be stored in this group.  

For more details, see [Backup Directory Structure](memos/backup-mechanism.md#云端备份存储结构).  

### Streaming Compression Upload

The "Streaming Compression Upload" of this plugin refers to the backup method of compressing files and uploading them to the cloud at the same time, which adopts the idea of exchanging time for space, and only takes up a small amount of memory space (used as a buffer), and hardly takes up any extra disk space.  

> Time for space is due to the fact that the OneDrive API requires the exact final file size to be known before a large file can be uploaded, so an extra process to simulate compression is needed to calculate the file size.  

The traditional backup method temporarily compresses the files to be backed up into zip archives before uploading them to the cloud, which requires disk space enough to accommodate the files to be backed up and the resulting zip archives.  

However, many service providers limit the available space of disk. If the available space is only 10 GiB and the archived data takes up 7 GiB, the remaining space on the disk won't be able to accommodate the temporary zip archive and the backup will fail.

</details>


## Installation

1. Download the plugin [here](https://github.com/Bottle-M/PotatoSack/releases/latest).  
2. Put the plugin in the `plugins` directory of your server directory.  
3. Launch the server, and the plugin will generate the initial configuration file `configs.yml`. You need to [configure](#configuration) it.  
4. Restart server after you have configured the plugin. If you find the log below, it means the plugin has been successfully initialized.   
    ```log
    [12:52:32 INFO]: [PotatoSack] PotatoSack successfully initialized! Savor using it!
    ```

## Configuration

The configuration file is located at `plugins/PotatoSack/configs.yml`。  

```yaml
# OneDrive API Configuration (Support OneDrive Business(ODB)/Personal(ODC))
onedrive:
  # Reference: https://learn.microsoft.com/en-us/graph/auth-v2-user?tabs=http#5-use-the-refresh-token-to-get-a-new-access-token
  # Tool: https://github.com/Bottle-M/PotatoSack/tree/main/onedrive-token-tool
  client-id:
  client-secret:
  refresh-token:

# The number of full backups to keep, actually it refers to "groups of backups" to keep.
# Note: "A group of backups" consists of a full backup and a set of incremental backups following it(before the next full backup).
# Note: If a full backup is deleted, all incremental backups following it before the next full backup will be deleted as well.
max-full-backups-retained: 3

# The interval of full backups (in minutes)
full-backup-interval: 1440

# The interval of incremental backups (in minutes)
incremental-backup-check-interval: 15

# Whether to stop incremental backup when no player is online
# Note: It may save you some data traffic expenses when nobody's there.
# Note: Full backup won't be stopped, only incremental backups will be affected.
stop-incremental-backup-when-no-player: true

# Whether to upload files while compressing them. (Time-space trade-off)
# Note: It will prevent zip file from being fully written to your local disk during backup creation and instead directly upload it to the cloud part by part, therefore the backup process is not constrained by disk size limitations when creating the zip file.
# Note: Actually this will temporarily write each chunk of zip file to a buffer in memory, however, it's not costly. (Each chunk of zip file is only about 15.625MiB)
use-streaming-compression-upload: false

# The worlds that you would like to back up, example:
# worlds:
#  - world
#  - world_nether
#  - world_the_end
# Note: If you leave this blank, the plugin won't work.
worlds: [ ]
```

## Command

There's only one command for the plugin:  

```text
/potatosack reload
```

Used for hot reloading plugin configuration file.  

### Permission Node

```text
potatosack.reload
```

> 💡 **Operators** will have this permission by default.

## Small tools

### Backup Merger  

As mentioned above, a "group of backups" consists of a full backup and some incremental backups. BackupMerger can merge these backups into one full backup when we are to restore server data.

See [BackupMerger](backups-merger/README.md).    

### OneDrive Token Tool  

With this tool, you can get the OneDrive Refresh Token required for writing configurations.

See [onedrive-token-tool](onedrive-token-tool/README.md)。

## FAQ

1. Q: Why does the console print out 404 at startup? 

    A：This is often due to missing files or directories in the cloud, but don't worry, the program will automatically create the needed files and directories when it encounters a 404 problem.  

2. Q: Where is the backup data stored in OneDrive?

    A: `OneDrive root directory/PotatoSack` or `OneDrive root directory/Apps/<your application name>/PotatoSack`. When using OneDrive Personal with the `Files.ReadWrite.AppFolder` permission scope, the latter will be the case.  

3. Q: Why the plugin is called 'PotatoSack'？    
    
    A：Because the performance of our server is similar to that of a potato, backing up data is like carrying a sack of potatoes. (゜ー゜)

Feel free to raise an issue if you have any other questions.

## License

MIT Licensed.

Thanks for using. (￣▽￣)"  