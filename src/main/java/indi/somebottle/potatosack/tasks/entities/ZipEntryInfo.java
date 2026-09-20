package indi.somebottle.potatosack.tasks.entities;

import indi.somebottle.potatosack.utils.Utils;

import java.io.File;
import java.io.IOException;

/**
 * 打包进 Zip 包的一个条目: 它的字节从哪里来（{@link #filePath}）、在包内叫什么（{@link #entryPath}），
 * 以及打包时要不要做转换（{@link #mcaPrevChunkTimes}）
 *
 * <p>主要用于 {@link Utils#zipSpecificFilesUtil}，是增量 / 全量备份与各 Client 上传实现之间传递的物品清单。</p>
 */
public class ZipEntryInfo {

    /**
     * 文件绝对路径，即这个条目的字节来源
     */
    public String filePath;

    /**
     * 文件相对于服务端根目录的相对路径（同时也是在 zip 包内的路径）
     */
    public String entryPath;

    /**
     * 仅增量备份中有上次基线的 `.mca` 会用到: 上一次备份记录中该文件的 1024 个区块时间戳
     *
     * <p>非 null 时，打包会把这个 `.mca` 交给 {@link indi.somebottle.potatosack.utils.McaDeltaInputStream}
     * 转成增量 delta 格式（只装发生变动的区块）；为 null 时原样打包。</p>
     *
     * <p>下列情况都应当保持为 null: 全量备份（设计上 `.mca` 一律原样存储）、普通文件、
     * 没有上次记录的 `.mca`（新出现的文件没有基线，delta 无从谈起）。</p>
     */
    public long[] mcaPrevChunkTimes;

    public ZipEntryInfo(String filePath) throws IOException {
        this.filePath = filePath;
        // entryPath缺省时默认会采用Utils.pathRelativeToServer方法来获得相对路径
        this.entryPath = Utils.pathRelativeToServer(new File(filePath));
    }

    public ZipEntryInfo(String filePath, String entryPath) {
        this.filePath = filePath;
        this.entryPath = entryPath;
    }

    @Override
    public String toString() {
        return "[" + filePath + " -> " + entryPath + "]";
    }
}
