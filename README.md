# PotatoSack

![Logo](./memos/images/potatosack-logo-transparent-smaller.png)  

_Take it easy, my old friend, this sack of potatoes is much easier to carry than Aunt Susan's marble statue!_

Lang: [中文简体](README.zh-CN.md) | English 

## What's this?

This is a simple backup plugin originally written for my Minecraft server to back up **data in specified directories**. It supports **incremental/full backup mechanism**, and incremental backup supports **chunk-level granularity**.

Backed-up archives are not stored locally, but are uploaded to cloud storage services like **OneDrive**, **Dropbox** and **S3 / S3-compatible object storage**.  

* Supported Minecraft Versions: **1.19+**  

* ✨ This plugin can compress and upload files **with little** local disk space usage, thus is suitable for scenarios where the service provider has imposed a limit on disk space. See the [Concepts](#concepts) for more details.

> Currently, the plugin supports OneDrive (**non-21Vianet version**), Dropbox, and AWS S3 / S3-compatible services (MinIO, Cloudflare R2, Wasabi, QCloud COS, etc.).

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
> If you are using Dropbox, trading time for space is not needed, because Dropbox does not require the total file size to be known in advance (however, if your server is deployed in Mainland China, you may need to set up a proxy to ensure access to the Dropbox API (；´д｀)ゞ).  
> S3 also does not require the final object size in advance (multipart upload only needs the part size), so with S3 the streaming mode needs no extra size-calculation process either.  

The traditional backup method temporarily compresses the files to be backed up into zip archives before uploading them to the cloud, which requires disk space enough to accommodate the files to be backed up and the resulting zip archives.  

However, many Minecraft server hosting providers limit the available space of disk. If the available space is only 10 GiB and the world data takes up 7 GiB, the remaining space on the disk won't be able to accommodate the temporary zip archive and the backup will fail.

</details>


## Installation

1. Download the plugin [here](https://github.com/Bottle-M/PotatoSack/releases/latest). (`PotatoSack*.jar`)    
2. Put the plugin in the `plugins` directory of your server directory.  
3. Launch the server, and the plugin will generate the initial configuration file `configs.yml`. You need to [configure](#configuration) it.  
4. Restart server after you have configured the plugin. If you find the log below, it means the plugin has been successfully initialized.   
    ```log
    [12:52:32 INFO]: [PotatoSack] PotatoSack successfully initialized! Savor using it!
    ```

## Configuration

The configuration file is located at `plugins/PotatoSack/configs.yml`.  

```yaml
# PotatoSack configuration version
version: '3.0.0'

client:
    # Choose the Cloud Storage Service Provider you want to use.
    # Supported values: onedrive / dropbox / s3
    use: onedrive
    # Base directory path for storing backups in cloud storage (default: empty, meaning root directory)
    # Example: if set to "my/backups", data will be stored at "my/backups/PotatoSack/..."
    # Note: Leading and trailing slashes will be automatically removed. E.g., "/my/backups/" will be treated as "my/backups".
    base-dir: ""
    # OneDrive API Configuration (Support OneDrive Business(ODB) / Personal(ODC))
    # Reference: https://learn.microsoft.com/en-us/graph/auth-v2-user?tabs=http#5-use-the-refresh-token-to-get-a-new-access-token
    # Tool: https://potatosack.imbottle.com/onedrive-token-helper.html
    onedrive:
        # use-app-folder specifies whether to use the "app folder" for storing backups.
        # Reference:
        #   [1] https://learn.microsoft.com/en-us/onedrive/developer/rest-api/concepts/special-folders-appfolder
        #   [2] https://learn.microsoft.com/en-us/graph/onedrive-sharepoint-appfolder
        # Although currently only OneDrive Personal(ODC) fully supports the "app folder" (with permission "Files.ReadWrite.AppFolder"),
        # it's still recommended to enable this option for safer data isolation and future compatibility.
        # For OneDrive Business(ODB), the data isolation may not be fully supported,
        # but the app folder can still be created and used normally (with permission Files.ReadWrite.All).
        use-app-folder: true
        client-id:
        client-secret:
        refresh-token:
    # Dropbox API Configuration
    # Create a Dropbox app and use a refresh token with "files.content.write",
    # "files.content.read", and "files.metadata.read" permissions.
    # Tool: https://potatosack.imbottle.com/dropbox-token-helper.html
    dropbox:
        app-key:
        app-secret:
        refresh-token:
    # S3 / S3-compatible object storage Configuration (AWS S3, QCloud COS, Aliyun OSS, ...)
    # Note: Only static credentials (access key / secret key, optionally with a session token) are supported.
    # Note: Required IAM permissions on the target bucket:
    #       s3:ListBucket, s3:GetObject, s3:PutObject, s3:AbortMultipartUpload, s3:DeleteObject
    # Note: S3 uploads use fixed 32 MiB parts (up to 10,000 parts), so one backup object is limited
    #       to about 312.5 GiB. Archives approaching hundreds of GiB are not recommended.
    s3:
        # Custom endpoint. Leave it empty to use the AWS S3 endpoint selected by the SDK according to "region".
        # For MinIO / other S3-compatible services, fill in the full endpoint including the scheme, e.g.
        #   endpoint: "http://127.0.0.1:9000"
        #   endpoint: "https://minio.example.com"
        endpoint: ""
        # Region used for signing. It must match the signature configuration of the server.
        # AWS S3 users should set it to the real region of the bucket; most S3-compatible services
        # accept the default value "us-east-1" (MinIO's default region is also us-east-1).
        region: "us-east-1"
        # The object storage bucket name.
        # The plugin stores data under "<base-dir>/PotatoSack/..." inside this bucket.
        bucket: ""
        # Access Key
        access-key: ""
        # Secret Key
        secret-key: ""
        # Session token, only needed for temporary credentials. Leave it empty to only use AK / SK.
        session-token: ""
        # Whether to use path-style access (http://endpoint/bucket/key) instead of
        # virtual-hosted-style access (http://bucket.endpoint/key).
        # MinIO and most custom endpoints require true; AWS S3 itself usually keeps false.
        path-style-access: false

# The number of full backups to keep, actually it refers to "groups of backups" to keep.
# Note: "A group of backups" consists of a full backup and a set of incremental backups following it (before the next full backup).
# Note: If a full backup is deleted, all incremental backups following it before the next full backup will be deleted as well.
max-full-backups-retained: 10

cron:
    # Cron expression for generating full backups
    # Note: This uses standard UNIX Cron syntax.
    # Example: "0 2 */1 * *" means the full backup runs every day at 2:00 AM.
    full-backup: "0 2 */1 * *"

    # Cron expression for generating incremental backups
    # Note: This uses standard UNIX Cron syntax.
    # Example: "*/30 * * * *" means the incremental backup runs every 30 minutes.
    incremental-backup: "*/30 * * * *"

# Whether to stop incremental backup when no player has joined
# - true: Skip incremental backups if no player has joined since the last incremental / full backup
# - false: Perform incremental backups according to the cron expression, regardless of player activity
stop-incremental-backup-when-no-player: true

# Whether to stop full backup when no player has joined
# - true: After a full backup is completed, skip subsequent scheduled full backups if no player joins, until a player joins again
# - false: Perform full backups according to the cron expression, regardless of player activity
stop-full-backup-when-no-player: false

# Whether to upload files while compressing them. (Time-space trade-off)
# Note: It will prevent zip file from being fully written to your local disk during backup creation and instead directly upload it to the cloud part by part, therefore the backup process is not constrained by disk size limitations when creating the zip file.
# Note: Actually this will temporarily write each chunk of zip file to a buffer in memory, however, this typically only requires **constant** additional space, it's not costly. (About 15.625 MiB per chunk for OneDrive/Dropbox and 32 MiB for S3.)
use-streaming-compression-upload: false

# The directory paths that you would like to make backups, can be absolute or relative (relative to server root) paths, example:
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
# Note 1: if you leave this blank, the plugin won't work.
# Note 2: all paths must be located under the server root directory.
# Note 3: you can place a .potatosackignore file in the server root directory to exclude certain files or directories from backups.
#         for more information, please refer to README.
# Note 4: the path must not be the server root directory itself.
# Note 5: backup paths must not overlap with each other. E.g., do not include both 'world' and 'world/playerdata'.
paths: [ ]
```

### Storage Service Configuration Guide

For detailed guidance on obtaining the required key, secret, and refresh-token for the configuration file, refer to the following documents:  

* [OneDrive](./memos/onedrive-guide.md)  
* [Dropbox](./memos/dropbox-guide.md)  

## Ignoring Specific Files From Backup

You can place a `.potatosackignore` file in the **server root directory** to ignore specific files or directories during backup, using a syntax similar to `.gitignore`.  

To keep implementation simple, there are two differences from `.gitignore`:  

1. It can only be placed in the **server root directory**.
2. Negation `!` syntax is not supported.

Example:  

```gitignore
# Comment: lines starting with # are comments; empty lines are also ignored

# A trailing / matches only directories; without a trailing slash, both files and directories are matched.
# Both rules below apply at any nesting level:
#   logs/  matches world/logs/, a/b/logs/, etc. (directories only, not files)
#   temp   matches temp, plugins/temp, x/y/temp (files or directories)
logs/
temp

# * matches any characters within a single path segment (excluding /). 
# In this case, it matches .log files within that path segment.
*.log

# ? matches a single character (excluding /).
# In this case, it matches cache1.dat, cacheX.dat, etc.
cache?.dat

# A leading / anchors the rule to the server root.
# In this case, it matches only temp.db in the root directory
/temp.db

# Rule with middle slash(es) also anchors to the root.
# In this case, it matches only .mca files under server-root/world/region/
world/region/*.mca

# ** matches zero or more directory levels.  
# This matches everything under logs/
logs/**

# A ** in the middle matches zero or more directories.
# In this case, it matches a/c, a/x/c, a/x/y/c, etc.
a/**/c
```

> The server root directory is **the directory where the server jar file is located** (e.g. `server.jar`).  

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

### Backups Merger  

As mentioned above, a "*group of backups*" consists of a full backup and some incremental backups. *BackupsMerger* can **merge these backups into one complete backup** when we are to restore server data.

See [BackupsMerger](backups-merger/README.md).  

## FAQ

1. Q: *Why does the console print out 404 at startup*? 

    A：This is often due to missing files or directories in the cloud when the plugin is first initialized, but don't worry, the program will automatically create the needed files and directories when it encounters a 404 problem.  

2. Q: *Where is the backup data stored in the cloud*?

    A: It depends on which provider you are using:

    * **OneDrive**: Depends on the `client.onedrive.use-app-folder` setting in `configs.yml` —
      * `true` (default): `OneDrive root/Apps/<application name>/<base-dir>/PotatoSack` (App Folder)
      * `false`: `OneDrive root/<base-dir>/PotatoSack`

    * **Dropbox**: `Dropbox root/<base-dir>/PotatoSack` or `Dropbox root/Apps/<your app name>/<base-dir>/PotatoSack`. If you selected App Folder access restriction when creating your Dropbox app, it will be the latter (recommended).

    * **S3**: `<bucket>/<base-dir>/PotatoSack/...`. S3 has no real directories — the path is expressed by object key prefixes, and no zero-byte marker objects are created by the plugin. Deleting an old backup group will delete every object under its prefix in batches.

    (`<base-dir>` refers to the `client.base-dir` setting in `configs.yml`)

3. Q: *Why the plugin is called* 'PotatoSack'？    
    
    A：Because the performance of my server is similar to that of a potato, backing up data is like carrying a sack of potatoes. (゜ー゜)

Feel free to raise an issue if you have any other questions.  

## Maven Build and Packaging

This plugin is compiled to Java 17 bytecode, and ProGuard is used to reduce the size of the final JAR file. Since ProGuard requires access to the `jmods` directory of a Java 17 JDK, you need to set the following environment variable before packaging:

```bash
# Specify the Java 17 installation directory
export JAVA17_HOME='/c/Program Files/Java/jdk-17.0.4.1'

mvn clean package
```

## License

MIT Licensed.

Thanks for using. (￣▽￣)"  
