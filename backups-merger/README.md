# Backups Merger

A tool to merge **a group of backups** into a full backup.

> **Which version do I need?** Since PotatoSack 3.0.0, an incremental backup stores only the changed chunks of a
> `.mca` file (it still looks like a `.mca`, but the content is in PotatoSack's `PSMCA` delta format), so you need
> **BackupsMerger 1.1.0 or newer** to restore such a backup group. Older versions only copy `.mca` entries verbatim
> and would leave an unreadable file in your world. Backup groups made by older PotatoSack versions (increments
> holding complete `.mca` files) can still be merged normally.

## Usage

1. Download `BackupsMerger*.jar` at [here](https://github.com/Bottle-M/PotatoSack/releases/latest).  
2. Download the group of backups you want to restore from the cloud backup directory, unzip them and extract the directory structure as shown below.    

    ```text
    020240625000001/
    ├── _world_<hash>.bin
    ├── _world_nether_<hash>.bin
    ├── backup.json
    ├── full.zip
    ├── incre000001.zip
    ├── incre000002.zip
    ├── incre000003.zip
    ├── incre000004.zip
    └── incre000005.zip  
    ```

    > The `_*.bin` files are the directory record files of the plugin (`.json` files before PotatoSack 3.0.0). BackupsMerger does **not** read them; merging only needs `backup.json` and the zip archives.

3. Execute `java -jar BackupsMerger*.jar`。

4. After starting the program, you can press Enter to select the directory where the backup group is located, or you can type `exit` to exit the program.  
   
    ![Start Program](pics/StartProgram.png)  

    Select Backup Group:  

    ![Select Backup Group](pics/SelectBackup.png)  

5. The program works by sequentially merging incremental backups (`incre*.zip`) with the full backup (`full.zip`). However, we may not always want to merge all the incremental backups. Therefore, the program will ask up to which incremental backup do you want to merge (input the number before the option).

    ![Up to which incre](pics/UpToWhichIncre.png)  

6. Next, the program will prompt the user to choose where to save the final merged zip archive.   

    ![Where to save](pics/SaveMergedAs.png)  

7. The program will generate a merged zip archive (default filename is `merged.zip`). You can extract this package to your Minecraft server directory to restore the backed-up data.  

## How each kind of entry is merged

| Entry in the archive | Behaviour |
| --- | --- |
| Ordinary files (`.dat`, `.mcc`, …) | extracted and overwritten as-is |
| `.mca` holding a complete region file (always the case in `full.zip`; also the case for old versions or when the producer fell back to storing it as-is) | extracted and overwritten as-is |
| `.mca` starting with `PSMCA\0` (the 3.0.0 incremental delta format, holding only the changed chunks) | applied onto the region file already restored from the earlier backups, then rewritten as a complete Anvil region file |
| `deleted.files` | every path listed in it is deleted after the other entries of that increment have been merged; the list itself is removed afterwards |

* Only the chunks recorded in a delta are replaced; chunks that are absent from it keep their previous state.
* A chunk recorded with `0` sectors is removed from the region file.
* A delta carries no new chunk timestamps, so the 4 KiB timestamp table of the region file header is preserved
  from the baseline as-is.
* `.mcc` files are handled as plain files: the stub of such an out-of-band chunk in the `.mca` is copied together
  with the chunk data.
* Every `.mca` is written to a temporary file first and then moved over the target file, so a failure never leaves a
  half-written region file. The temporary files live in the tool's own work directory, which is deleted on exit.

## Troubleshooting

| Message | Meaning |
| --- | --- |
| `Full backup contains an incremental (PSMCA) region entry: …` | `full.zip` holds a delta `.mca`. A full backup must store region files as-is, so this backup group is inconsistent — download it again. |
| `Incremental region entry … is a PSMCA delta, but there is no baseline region file at …` | this increment needs a region file restored by the earlier backups as its baseline. Check that you selected the right backup group and that `full.zip` contains that region file. |
| `Invalid delta file …: payload of chunk #N … is truncated` / `… trailing byte(s) …` / `… appears more than once` | that increment is damaged (incomplete upload/download). Download the `incre*.zip` again. |
| `Invalid base region file …` | the `.mca` already present in the working directory is not a complete region file. |
| `Refusing zip entry: …` | the archive contains an entry (an absolute path, or one containing `..`) that would be written outside of the working directory; merging stops. |
| `Ignoring unsafe path in deleted.files: …` | a `deleted.files` line points outside of the restore directory; that line is skipped. |

If merging fails, the program reports the error and produces no output archive.
