import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.tasks.entities.ZipEntryInfo;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.DirFileRecord;
import indi.somebottle.potatosack.utils.IgnoreMatcher;
import indi.somebottle.potatosack.utils.ScanUtils;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ScanUtils#scanBackupPath} 的剪枝逻辑测试，不依赖 Bukkit 服务端
 *
 * <p>覆盖: mtime 快路径 / mtime 变了但哈希没变 / 内容变动 / 删除检测 /
 * `.mca` 区块时间戳的取样与基线挂载 / ignore 规则的配合。</p>
 *
 * <p>伪造服务端根目录的办法是直接设置 {@link PotatoSack#worldContainerDir}，
 * {@code pathRelativeToServer} / {@code pathAbsToServer} / {@link IgnoreMatcher#loadDefault()} 都依赖它。</p>
 */
public class ScanUtilsTest {

    /**
     * 测试里给文件设置的 mtime，取一个远离当前时间且互不相同的基准，
     * 免得"刚写完的文件 mtime 和上次扫描落在同一毫秒"导致快路径被误触发
     */
    private static final long BASE_MTIME = 1_700_000_000_000L;

    private File serverRoot;
    private File worldDir;
    private File originalWorldContainerDir;
    private IgnoreMatcher ignorer;

    @Before
    public void setUp() throws Exception {
        originalWorldContainerDir = PotatoSack.worldContainerDir;
        serverRoot = Files.createTempDirectory("_test_server_root").toFile();
        PotatoSack.worldContainerDir = serverRoot;
        worldDir = new File(serverRoot, "world");
        assertTrue(worldDir.mkdirs());
        ignorer = IgnoreMatcher.loadDefault();
    }

    @After
    public void tearDown() {
        PotatoSack.worldContainerDir = originalWorldContainerDir;
        deleteRecursively(serverRoot);
    }

    // ------------------------------------------------------------------ 基本扫描

    @Test
    public void testFirstScanTreatsEverythingAsNew() throws Exception {
        writeFile("world/a.txt", "aaa", BASE_MTIME);
        writeFile("world/sub/b.txt", "bbb", BASE_MTIME + 1);

        ScanUtils.ScanResult scan = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), ignorer);

        assertEquals(2, scan.entries.size());
        assertEquals(2, scan.changed.size());
        assertTrue(scan.deleted.isEmpty());

        Map<String, ZipEntryInfo> changedByEntryPath = new java.util.HashMap<>();
        for (ZipEntryInfo entry : scan.changed)
            changedByEntryPath.put(entry.entryPath, entry);
        // zip 包内路径就是相对路径，解包时靠它落盘
        assertTrue(changedByEntryPath.containsKey("world/a.txt"));
        assertTrue(changedByEntryPath.containsKey("world/sub/b.txt"));
        // 文件绝对路径应当指向真实文件
        assertEquals(new File(serverRoot, "world/sub/b.txt").getPath(),
                changedByEntryPath.get("world/sub/b.txt").filePath);
        // 全量备份没有任何基线，不许挂 mcaPrevChunkTimes
        assertNull(changedByEntryPath.get("world/a.txt").mcaPrevChunkTimes);

        // 记录里的 mtime 必须是文件真实的 mtime，哈希必须非 0
        DirFileRecord.FileEntry entry = scan.entries.get("world/a.txt");
        assertEquals(BASE_MTIME, entry.getLastModified());
        assertNotSame(0L, entry.getHash());
    }

    // ------------------------------------------------------------------ mtime 快路径

    @Test
    public void testSecondScanIsPrunedByMtime() throws Exception {
        writeFile("world/a.txt", "aaa", BASE_MTIME);
        writeFile("world/b.txt", "bbb", BASE_MTIME + 1);

        ScanUtils.ScanResult first = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), ignorer);
        ScanUtils.ScanResult second = ScanUtils.scanBackupPath(worldDir, first.entries, ignorer);

        assertTrue("mtime 没动就一个文件都不该打包", second.changed.isEmpty());
        assertTrue(second.deleted.isEmpty());
        assertEquals(2, second.entries.size());
        // 直接沿用旧条目对象 == 真的走了快路径（连文件都没读）
        assertSame(first.entries.get("world/a.txt"), second.entries.get("world/a.txt"));
        assertSame(first.entries.get("world/b.txt"), second.entries.get("world/b.txt"));
    }

    @Test
    public void testMtimeChangedButHashSameIsSkippedAndMtimeUpdated() throws Exception {
        File file = writeFile("world/a.txt", "aaa", BASE_MTIME);
        ScanUtils.ScanResult first = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), ignorer);
        long firstHash = first.entries.get("world/a.txt").getHash();

        // 只动 mtime，不动内容（模拟 touch）
        long newMtime = BASE_MTIME + 60_000;
        assertTrue(file.setLastModified(newMtime));

        ScanUtils.ScanResult second = ScanUtils.scanBackupPath(worldDir, first.entries, ignorer);

        assertTrue("内容没变就不该打包", second.changed.isEmpty());
        // 但新 mtime 必须记下来，否则这个文件以后每次备份都要白算一遍哈希
        assertEquals(newMtime, second.entries.get("world/a.txt").getLastModified());
        assertEquals(firstHash, second.entries.get("world/a.txt").getHash());
        assertNotSame(first.entries.get("world/a.txt"), second.entries.get("world/a.txt"));
    }

    @Test
    public void testContentChangeIsDetected() throws Exception {
        writeFile("world/a.txt", "aaa", BASE_MTIME);
        ScanUtils.ScanResult first = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), ignorer);

        writeFile("world/a.txt", "changed!", BASE_MTIME + 60_000);
        ScanUtils.ScanResult second = ScanUtils.scanBackupPath(worldDir, first.entries, ignorer);

        assertEquals(1, second.changed.size());
        assertEquals("world/a.txt", second.changed.get(0).entryPath);
        assertNotSame(first.entries.get("world/a.txt").getHash(),
                second.entries.get("world/a.txt").getHash());
    }

    @Test
    public void testDeletedFileIsReported() throws Exception {
        writeFile("world/a.txt", "aaa", BASE_MTIME);
        File b = writeFile("world/b.txt", "bbb", BASE_MTIME + 1);
        ScanUtils.ScanResult first = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), ignorer);

        assertTrue(b.delete());
        ScanUtils.ScanResult second = ScanUtils.scanBackupPath(worldDir, first.entries, ignorer);

        assertEquals(Arrays.asList("world/b.txt"), second.deleted);
        assertEquals(1, second.entries.size());
        assertTrue(second.changed.isEmpty());
    }

    // ------------------------------------------------------------------ .mca 特殊处理

    @Test
    public void testMcaChunkTimesAreSampledAndBaselineIsAttached() throws Exception {
        // 第一次扫描: 没有基线 -> 记录存下区块时间戳，但 zip 条目不挂 prev
        writeFile("world/region/r.0.0.mca", buildMca(new int[]{0, 5}, new int[]{111, 222}), BASE_MTIME);
        ScanUtils.ScanResult first = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), ignorer);

        DirFileRecord.FileEntry firstEntry = first.entries.get("world/region/r.0.0.mca");
        assertNotNull("变动过的 .mca 应当被记下区块时间戳", firstEntry.getMcaChunkTimes());
        assertEquals(111L, firstEntry.getMcaChunkTimes()[0]);
        assertEquals(222L, firstEntry.getMcaChunkTimes()[5]);
        assertEquals(0L, firstEntry.getMcaChunkTimes()[1]);
        assertEquals(1, first.changed.size());
        assertNull("没有基线就不该挂 prev，否则整文件打包的语义就错了",
                first.changed.get(0).mcaPrevChunkTimes);

        // mtime 没动 -> 不打包，时间戳原样沿用
        ScanUtils.ScanResult second = ScanUtils.scanBackupPath(worldDir, first.entries, ignorer);
        assertTrue(second.changed.isEmpty());
        assertSame(firstEntry.getMcaChunkTimes(), second.entries.get("world/region/r.0.0.mca").getMcaChunkTimes());

        // 改内容（时间戳变了）-> 打包，并且挂上第一次的时间戳作为 delta 基线
        writeFile("world/region/r.0.0.mca", buildMca(new int[]{0, 5}, new int[]{333, 222}), BASE_MTIME + 60_000);
        ScanUtils.ScanResult third = ScanUtils.scanBackupPath(worldDir, second.entries, ignorer);

        assertEquals(1, third.changed.size());
        assertArrayEquals("应当挂上旧记录的区块时间戳",
                firstEntry.getMcaChunkTimes(), third.changed.get(0).mcaPrevChunkTimes);
        DirFileRecord.FileEntry thirdEntry = third.entries.get("world/region/r.0.0.mca");
        assertEquals("新记录应当存下新读到的区块时间戳", 333L, thirdEntry.getMcaChunkTimes()[0]);
        assertEquals(222L, thirdEntry.getMcaChunkTimes()[5]);
    }

    @Test
    public void testNonMcaFileHasNoChunkTimes() throws Exception {
        writeFile("world/level.dat", "not a region file", BASE_MTIME);
        ScanUtils.ScanResult scan = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), ignorer);
        assertNull(scan.entries.get("world/level.dat").getMcaChunkTimes());
    }

    @Test
    public void testBrokenMcaIsStoredAsPlainFile() throws Exception {
        // 不足 8 KiB 的 .mca: 头部读不出来，应当当普通文件处理，记录里不存区块时间戳
        writeFile("world/region/broken.mca", "too short", BASE_MTIME);
        ScanUtils.ScanResult scan = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), ignorer);

        assertEquals(1, scan.changed.size());
        assertNull(scan.entries.get("world/region/broken.mca").getMcaChunkTimes());
        assertNull(scan.changed.get(0).mcaPrevChunkTimes);
    }

    // ------------------------------------------------------------------ ignore 规则

    @Test
    public void testIgnoredFileIsNotScannedNorReportedAsDeleted() throws Exception {
        writeFile("world/a.txt", "aaa", BASE_MTIME);
        writeFile("world/secret.txt", "secret", BASE_MTIME + 1);

        // 先扫一遍，让 secret.txt 进入"旧记录"
        ScanUtils.ScanResult first = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), ignorer);
        assertEquals(2, first.entries.size());

        // 之后它被 ignore 规则排除了
        Files.write(new File(serverRoot, ".potatosackignore").toPath(),
                "secret.txt\n".getBytes(StandardCharsets.UTF_8));
        IgnoreMatcher newIgnorer = IgnoreMatcher.loadDefault();
        assertFalse(newIgnorer.isEmpty());

        // 直接把未过滤的旧记录丢进去: 过滤是 scanBackupPath 内部的责任
        ScanUtils.ScanResult second = ScanUtils.scanBackupPath(worldDir, first.entries, newIgnorer);

        assertFalse("被忽略的文件不该出现在扫描结果里", second.entries.containsKey("world/secret.txt"));
        assertTrue("现已忽略的文件也不该被当成删除（透明移出备份宇宙）", second.deleted.isEmpty());
        assertTrue(second.changed.isEmpty());
        // a.txt 不受影响
        assertEquals(1, second.entries.size());
    }

    @Test
    public void testIgnoredDirectoryIsPruned() throws Exception {
        writeFile("world/a.txt", "aaa", BASE_MTIME);
        writeFile("world/cache/x.txt", "xxx", BASE_MTIME + 1);

        Files.write(new File(serverRoot, ".potatosackignore").toPath(),
                "cache/\n".getBytes(StandardCharsets.UTF_8));
        IgnoreMatcher newIgnorer = IgnoreMatcher.loadDefault();

        ScanUtils.ScanResult scan = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), newIgnorer);
        assertEquals(1, scan.entries.size());
        assertTrue(scan.entries.containsKey("world/a.txt"));
    }

    @Test
    public void testUnlistableBackupPathThrowsInsteadOfReturningEmptyScan() throws Exception {
        // 备份路径列不出内容时必须抛异常，不能"成功"返回一个空结果:
        // 那样增量会把该路径下所有文件判为已删除写进 deleted.files，同时把记录清空。
        // 这里用"路径其实是个文件"来让 listFiles() 返回 null；子目录列不出来走的是同一个判空，只是没法可移植地造出来
        File notADir = writeFile("world/not_a_dir.txt", "x", BASE_MTIME);
        // 先放一份"看起来很正常的旧记录"，确认异常确实来自目录列举失败
        ScanUtils.ScanResult first = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), ignorer);
        assertTrue(first.entries.containsKey("world/not_a_dir.txt"));
        try {
            ScanUtils.scanBackupPath(notADir, first.entries, ignorer);
            throw new AssertionError("列不出目录内容时应当抛出 IOException");
        } catch (IOException e) {
            assertTrue("实际消息: " + e.getMessage(),
                    e.getMessage().contains("Cannot list directory while scanning"));
        }
    }

    @Test
    public void testUnreadableFileKeepsOldEntryAndIsNotMarkedDeleted() throws Exception {
        // 场景: 文件还在、内容也变了，但这次扫描读不了它（Windows 下的 session.lock 就是典型）。
        // 它既不能算"已删除"（恢复工具会按 deleted.files 真的删掉它），也不能更新记录
        // （否则没备份到的内容会被标成已备份）。
        // 制造"读不了"的办法是 Windows 的字节范围锁: 它是强制锁，能挡住同一进程里另一个句柄的读；
        // POSIX 的 fcntl 锁只是建议锁，挡不住，所以这个用例只在 Windows 上跑
        Assume.assumeTrue("需要强制性的文件锁（Windows）",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));

        File file = writeFile("world/locked.txt", "v1", BASE_MTIME);
        ScanUtils.ScanResult first = ScanUtils.scanBackupPath(worldDir, new HashMap<>(), ignorer);
        DirFileRecord.FileEntry firstEntry = first.entries.get("world/locked.txt");
        assertNotNull(firstEntry);
        assertEquals(BASE_MTIME, firstEntry.getLastModified());

        // 内容变了，mtime 也推进了
        Files.write(file.toPath(), "v2".getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(BASE_MTIME + 1000));

        // 读不了的文件会走退避重试（1s+2s+4s），这里临时关掉，免得拖慢测试
        int savedRetry = Constants.FILE_READ_MAX_RETRY;
        long savedBackoff = Constants.FILE_READ_MAX_BACKOFF_MS;
        Constants.FILE_READ_MAX_RETRY = 0;
        Constants.FILE_READ_MAX_BACKOFF_MS = 1;
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
             FileLock ignored = raf.getChannel().lock()) {
            ScanUtils.ScanResult second = ScanUtils.scanBackupPath(worldDir, first.entries, ignorer);

            assertTrue("读不了的文件不该被当成已删除: " + second.deleted, second.deleted.isEmpty());
            assertTrue("读不了的文件不该被塞进待打包列表", second.changed.isEmpty());
            assertSame("旧条目应当原样沿用", firstEntry, second.entries.get("world/locked.txt"));
            // mtime 必须沿用旧的，否则下次扫描会走 mtime 快路径，把这个文件当成"一直没变过"而永远不再读它
            assertEquals(BASE_MTIME, second.entries.get("world/locked.txt").getLastModified());
        } finally {
            Constants.FILE_READ_MAX_RETRY = savedRetry;
            Constants.FILE_READ_MAX_BACKOFF_MS = savedBackoff;
        }
    }

    // ------------------------------------------------------------------ 测试辅助

    private File writeFile(String relativePath, String content, long mtime) throws Exception {
        return writeFile(relativePath, content.getBytes(StandardCharsets.UTF_8), mtime);
    }

    private File writeFile(String relativePath, byte[] content, long mtime) throws Exception {
        File file = new File(serverRoot, relativePath);
        File parent = file.getParentFile();
        if (!parent.exists())
            assertTrue(parent.mkdirs());
        Files.write(file.toPath(), content);
        // 必须显式设置 mtime: 否则"刚写完"的 mtime 和上一次扫描可能落在同一毫秒，快路径会被误触发
        assertTrue("设置 mtime 失败: " + file, file.setLastModified(mtime));
        return file;
    }

    /**
     * 造一个最小可用的 .mca: 8 KiB 头部 + 每个区块 1 个扇区，从扇区 2 开始紧挨着放
     *
     * @param indexes    区块下标
     * @param timestamps 与 indexes 一一对应的区块时间戳
     */
    private static byte[] buildMca(int[] indexes, int[] timestamps) {
        byte[] file = new byte[(2 + indexes.length) * 4096];
        for (int i = 0; i < indexes.length; i++) {
            int offset = 2 + i;
            int p = indexes[i] * 4;
            file[p] = (byte) (offset >> 16);
            file[p + 1] = (byte) (offset >> 8);
            file[p + 2] = (byte) offset;
            file[p + 3] = 1; // 占用 1 个扇区
            int t = 1024 * 4 + indexes[i] * 4;
            file[t] = (byte) (timestamps[i] >> 24);
            file[t + 1] = (byte) (timestamps[i] >> 16);
            file[t + 2] = (byte) (timestamps[i] >> 8);
            file[t + 3] = (byte) timestamps[i];
        }
        return file;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists())
            return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children)
                deleteRecursively(child);
        }
        file.delete();
    }
}
