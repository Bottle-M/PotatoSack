package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.tasks.entities.ZipEntryInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 备份路径扫描: 一次目录遍历同时产出「新记录的全部条目」「本次要打包的条目」「被删除的文件」
 *
 * <p>扫描要回答的核心问题是"相比上次备份，哪些文件变了"，剪枝规则:</p>
 *
 * <pre>
 * mtime 没变                 -&gt; 连读都不读，直接沿用旧条目
 * mtime 变了 -&gt; 算哈希 -&gt; 没变 -&gt; 跳过（但要把新 mtime 记进新记录）
 *                      -&gt; 变了 -&gt; 备份（.mca 另外读头部区块时间戳，供增量 delta 用）
 * </pre>
 * <p><b>扫描与打包的顺序（重构调用链前也请参阅 {@link #scanFile}）:</b>
 * 当前流程先在扫描阶段读取 `.mca` 的区块时间戳，随后才在打包阶段重新读取文件内容。
 * 因此即使文件在两者之间发生变化，也只会出现“记录的时间戳比 zip 内容旧”的安全偏差：
 * 下次增量可能把同一变更再备份一次，但不会因为这组时间戳而漏掉它。</p>
 *
 * <p>不可把顺序颠倒为“先打包、后为记录取样”。否则打包旧内容后若文件又发生变化，
 * 记录可能保存尚未进入 zip 的新时间戳；下次增量会把该变化误判为已经备份，从而静默漏掉数据。</p>
 *
 * <p>全量与增量调的是同一个 {@link #scanBackupPath}，区别只在于旧记录:
 * 全量传空 Map，于是所有文件都被当作"新文件"，全部打包、记录整个重建</p>
 */
public class ScanUtils {

    /**
     * 一次备份路径扫描的产物
     */
    public static class ScanResult {
        /**
         * 新记录里该备份路径下的全部条目，键为文件相对于服务端根目录的相对路径
         * <p>未变动的文件会直接沿用旧记录里的条目对象</p>
         */
        public final Map<String, DirFileRecord.FileEntry> entries = new HashMap<>();

        /**
         * 本次需要打包进 zip 的条目（内容相比旧记录发生变动的文件，以及新出现的文件）
         * <p>已变动的 `.mca` 上已经挂好了 {@link ZipEntryInfo#mcaPrevChunkTimes}</p>
         */
        public final List<ZipEntryInfo> changed = new ArrayList<>();

        /**
         * 相比旧记录被删除的文件的相对路径
         */
        public final List<String> deleted = new ArrayList<>();
    }

    /**
     * 扫描一个备份路径下的所有文件
     *
     * @param srcDir      待扫描的备份目录
     * @param prevEntries 上次备份时该备份路径的记录条目（键为相对路径）；<b>全量备份传空 Map</b>
     * @param ignorer     用于跳过被忽略的文件/目录（目录命中即剪枝，不递归）
     * @return 扫描结果
     * @throws IOException 备份路径已不存在、无法列出其内容，或遍历目录发生 IO 错误时抛出
     * @apiNote 传进来的 {@code prevEntries} 会先在内部过滤掉 ignorer 已忽略的条目，防止被 ignore 的文件会被误判成"删除"写进 deleted.files。
     */
    public static ScanResult scanBackupPath(File srcDir, Map<String, DirFileRecord.FileEntry> prevEntries,
                                            IgnoreMatcher ignorer) throws IOException {
        ScanResult result = new ScanResult();
        // 先剔除已忽略的旧条目，下面的比对和删除检测都必须基于过滤后的集合，
        // 否则那些之前备份过、现在被 ignore 的文件会被误判成"删除"
        Map<String, DirFileRecord.FileEntry> filteredPrevEntries = filterIgnoredEntries(prevEntries, ignorer);
        // 备份路径在 BackupMaker 构造时已经校验过存在；这里若路径已消失则直接抛异常
        String prefix = Utils.pathRelativeToServer(srcDir);
        // 注意: 这一步可能会因为 srcDir 列不出来而抛异常，见 scanDir 里的说明
        scanDir(srcDir, prefix, filteredPrevEntries, ignorer, result);
        // 旧记录里有、新记录里没有的，就是被删除的文件
        for (String prevPath : filteredPrevEntries.keySet()) {
            if (!result.entries.containsKey(prevPath))
                result.deleted.add(prevPath);
        }
        return result;
    }

    /**
     * 递归扫描目录，把文件逐个交给 {@link #scanFile}
     *
     * @param dir         当前目录
     * @param dirPath     当前目录相对于服务端根目录的路径（同时作为 zip 内路径前缀，空串表示服务端根）
     * @param filteredPrevEntries 上次备份的记录条目（已过滤掉现已忽略的）
     * @param ignorer     IgnoreMatcher
     * @param result      扫描结果累加器
     * @throws IOException 备份路径本身或其子目录列不出来时抛出
     */
    private static void scanDir(File dir, String dirPath, Map<String, DirFileRecord.FileEntry> filteredPrevEntries,
                                IgnoreMatcher ignorer, ScanResult result) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            // 列不出来（权限被拒等）必须直接失败，
            // 不能让本次扫描"成功"返回一个空结果: 那样这棵子树会整体被判为"已删除"写进 deleted.files，同时这部分记录被清空，恢复时这些文件就会被当成已删除处理。
            throw new IOException("Cannot list directory while scanning: " + dir.getAbsolutePath()
                    + ", it may have been deleted or become unreadable.");
        }
        for (File file : files) {
            // 拼出来的相对路径同时用作: 记录里的键、zip 包内路径（解包时的落盘位置），两者必须完全一致
            String relativePath = dirPath.isEmpty() ? file.getName() : dirPath + "/" + file.getName();
            if (file.isDirectory()) {
                // 仅在存在 ignore 规则时才判断是否剪枝，避免无规则时的额外开销
                if (!ignorer.isEmpty() && ignorer.isIgnored(relativePath, true))
                    continue; // 命中 ignore 的目录直接剪枝，不递归
                scanDir(file, relativePath, filteredPrevEntries, ignorer, result);
            } else if (file.isFile()) {
                // 命中 ignore 规则的文件跳过（不计入记录）
                if (ignorer.isIgnored(relativePath, false))
                    continue;
                scanFile(file, relativePath, filteredPrevEntries.get(relativePath), result);
            }
        }
    }

    /**
     * 扫描单个文件，决定它在新记录里的样子；内容变动的话把它加进待打包列表
     *
     * @param file         文件
     * @param relativePath 文件相对于服务端根目录的路径
     * @param prevEntry         上次备份时该文件的条目，没有则为 null（新文件）
     * @param result       扫描结果累加器
     * @apiNote 单个文件读不了时不会让整个扫描失败（Windows 下的 `session.lock` 就是典型）:
     * 该文件不打包、不更新记录，但它在旧记录里的条目会原样沿用，因此既不会被删除检测判成"已删除"，
     * 下次扫描也仍然会重新读它。
     * @implNote <p>扫描阶段本身并不保证世界已经停止自动保存，也不提供文件系统快照；普通文件写入通常会推进 mtime。mtime 与旧记录相同时，程序会假定内容未变并跳过读文件。</p>
     */
    private static void scanFile(File file, String relativePath, DirFileRecord.FileEntry prevEntry, ScanResult result) {
        long mtime = file.lastModified();

        // 1. mtime 没动 -> 假定内容没动，跳过该文件，沿用旧条目
        if (prevEntry != null && prevEntry.getLastModified() == mtime) {
            result.entries.put(relativePath, prevEntry);
            return;
        }

        // 2. mtime 动了 -> 读一遍算哈希
        long hash;
        try {
            hash = Utils.fileXXH3_64(file);
        } catch (IOException e) {
            // 读不了就跳过这个文件，既不打包也不更新它的记录。
            // 但旧条目必须原样留在新记录里（连 mtime 一起沿用）:
            // 1) 不留的话它就不在 entries 里，scanBackupPath 结尾的删除检测会把它判成"已删除"
            //    写进 deleted.files；
            // 2) mtime 沿用旧的，才能保证下次扫描仍然会重新读它
            ConsoleSender.logWarn("Cannot read file " + file.getAbsolutePath() + ", skipping: " + e.getMessage());
            if (prevEntry != null)
                result.entries.put(relativePath, prevEntry);
            return;
        }

        // 3. 哈希没变 -> 只是 mtime 动了，跳过打包
        if (prevEntry != null && prevEntry.getHash() == hash) {
            // 但新 mtime 必须记下来，否则这个文件的 mtime 永远追不上记录，以后每次备份都要白算一遍哈希。
            // 区块时间戳沿用旧的: 哈希没变说明文件内容逐字节相同，头部自然也没变
            result.entries.put(relativePath,
                    new DirFileRecord.FileEntry(relativePath, mtime, hash, prevEntry.getMcaChunkTimes()));
            return;
        }

        // 4. 内容变了（或者是新文件）
        long[] chunkTimes = null;
        long[] prevChunkTimes = null;
        if (DirFileRecord.isMcaPath(relativePath)) {
            // 如果是 .mca 文件
            try {
                chunkTimes = McaDeltaInputStream.readChunkTimes(file);
            } catch (IOException e) {
                // 头部读不出来（文件太短/损坏），那就当普通文件原样打包，记录里也不存区块时间戳
                ConsoleSender.logWarn("Cannot read region header of " + file.getAbsolutePath()
                        + ", it will be stored as a plain file: " + e.getMessage());
            }
            // 只有确实拿到了新时间戳、且旧记录里有基线，才谈得上走 delta
            if (chunkTimes != null && prevEntry != null)
                prevChunkTimes = prevEntry.getMcaChunkTimes();
        }
        result.entries.put(relativePath, new DirFileRecord.FileEntry(relativePath, mtime, hash, chunkTimes));

        ZipEntryInfo zipEntry = new ZipEntryInfo(Utils.pathAbsToServer(relativePath), relativePath);
        zipEntry.mcaPrevChunkTimes = prevChunkTimes;
        result.changed.add(zipEntry);
    }

    /**
     * 从记录条目中移除被 ignore 的条目
     *
     * @param entries 记录条目，可为 null
     * @param ignorer IgnoreMatcher
     * @return 过滤后的 Map；若无规则或入参为 null 则原样返回
     */
    private static <V> Map<String, V> filterIgnoredEntries(Map<String, V> entries, IgnoreMatcher ignorer) {
        if (entries == null || ignorer.isEmpty()) {
            return entries;
        }
        Map<String, V> filtered = new HashMap<>();
        for (Map.Entry<String, V> entry : entries.entrySet()) {
            // 用 isIgnored（内置祖先遍历）：既匹配文件自身，也匹配“位于被忽略目录之下”的文件（与扫描期目录剪枝一致），
            // 避免旧记录中此类文件被误当作删除
            if (!ignorer.isIgnored(entry.getKey(), false)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }
}
