package indi.somebottle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static indi.somebottle.RegionFixtures.ChunkState;
import static indi.somebottle.RegionFixtures.anvilChunkData;
import static indi.somebottle.RegionFixtures.chunk;
import static indi.somebottle.RegionFixtures.chunkData;
import static indi.somebottle.RegionFixtures.del;
import static indi.somebottle.RegionFixtures.delta;
import static indi.somebottle.RegionFixtures.isDelta;
import static indi.somebottle.RegionFixtures.parseRegion;
import static indi.somebottle.RegionFixtures.region;
import static indi.somebottle.RegionFixtures.stateOf;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ZIP 层的集成测试: 全量包 + 多个增量包合并出一个完整的目录树
 *
 * <p>覆盖 {@link Utils#unzip(File, File)}、{@link Utils#mergeIncrementalZip(File, File)} 与
 * {@link Main#applyDeletedFilesSafely(File, File)} 的组合行为，不启动 GUI。</p>
 */
public class IncrementalZipMergeTest {

    private static final String REGION = "world/region/r.0.0.mca";

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testUnlistedUploadedIncrementalsAreSelectableInOrder() throws Exception {
        File group = tmp.newFolder();
        BackupRecord.IncreBackupHistoryItem first = new BackupRecord.IncreBackupHistoryItem();
        first.setId("000001");
        first.setTime(100);
        Files.write(new File(group, "incre000001.zip").toPath(), new byte[0]);
        Files.write(new File(group, "incre000002.zip").toPath(), new byte[0]);
        Files.write(new File(group, "incre000004.zip").toPath(), new byte[0]);
        List<BackupRecord.IncreBackupHistoryItem> discovered =
                Main.discoverAvailableIncrementals(group, List.of(first));
        assertEquals(2, discovered.size());
        assertEquals("000002", discovered.get(1).getId());
        assertEquals(0, discovered.get(1).getTime());
        assertEquals(2, Main.discoverAvailableIncrementals(group, null).size());
    }

    // ------------------------------------------------------------------ 主流程

    @Test
    public void testFullThenDeltaThenRawIncremental() throws Exception {
        File root = tmp.newFolder();
        File groupDir = new File(root, "backups");
        File restoreDir = new File(root, "restore");
        assertTrue(groupDir.mkdirs() && restoreDir.mkdirs());

        // 全量包: 一个完整区域文件 + 普通文件 + .mcc
        byte[] fullRegion = region()
                .chunk(0, 1, 1000, chunkData(0, 1))
                .chunk(1, 2, 2000, chunkData(1, 2))
                .chunk(5, 1, 5000, chunkData(5, 1))
                .build();
        byte[] otherRegion = region().chunk(3, 1, 3000, chunkData(3, 1)).build();
        File fullZip = zip(groupDir, "full.zip", entries(
                REGION, fullRegion,
                "world/region/r.1.0.mca", otherRegion,
                "world/region/r.0.0.mcc", text("MCC-OLD"),
                "world/level.dat", text("LEVEL")));

        // 增量 1: delta .mca（改一个区块、删一个区块）+ 新的 .mcc + 普通文件
        byte[] deltaBytes = delta(chunk(5, 3, 5001, chunkData(55, 3)), del(1, 2001));
        File incre1Zip = zip(groupDir, "incre000001.zip", entries(
                REGION, deltaBytes,
                "world/region/r.0.0.mcc", text("MCC-NEW"),
                "world/data/extra.txt", text("EXTRA")));

        // 增量 2: 生产端会退化成 raw 的 .mca + deleted.files
        byte[] rawRegion = region().chunk(2, 1, 6000, chunkData(2, 1)).build();
        File incre2Zip = zip(groupDir, "incre000002.zip", entries(
                REGION, rawRegion,
                "deleted.files", text("world/data/extra.txt\nworld/region/r.0.0.mcc\nworld/region/r.1.0.mca\n")));

        // 1. 全量解压
        assertTrue("全量包应当解压成功", Utils.unzip(fullZip, restoreDir));
        assertArrayEquals(fullRegion, read(file(restoreDir, REGION)));
        assertEquals("LEVEL", new String(read(file(restoreDir, "world/level.dat")), StandardCharsets.UTF_8));

        // 2. 应用第一份增量（delta）
        assertTrue("增量 delta 应当合并成功", Utils.mergeIncrementalZip(incre1Zip, restoreDir));
        byte[] afterFirst = read(file(restoreDir, REGION));
        assertFalse("合并后不能还是 PSMCA delta", isDelta(afterFirst));
        Map<Integer, ChunkState> state = parseRegion(afterFirst);
        assertEquals("delta 里删除的区块应当消失", ChunkState.DELETED, stateOf(state, 1));
        assertEquals("delta 里改动的区块应当生效", new ChunkState(3, chunkData(55, 3)), stateOf(state, 5));
        assertEquals("未出现在 delta 里的区块沿用基线", new ChunkState(1, chunkData(0, 1)), stateOf(state, 0));
        long[] timestamps = RegionFixtures.timesOf(afterFirst);
        assertEquals(1000L, timestamps[0]);
        assertEquals(2001L, timestamps[1]);
        assertEquals(5001L, timestamps[5]);
        assertEquals("MCC-NEW", new String(read(file(restoreDir, "world/region/r.0.0.mcc")), StandardCharsets.UTF_8));
        assertEquals("EXTRA", new String(read(file(restoreDir, "world/data/extra.txt")), StandardCharsets.UTF_8));

        // 3. 应用第二份增量（raw .mca 应当原样覆盖 delta 合并出来的版本）
        assertTrue("raw .mca 增量应当合并成功", Utils.mergeIncrementalZip(incre2Zip, restoreDir));
        assertArrayEquals("raw .mca 应当逐字节覆盖", rawRegion, read(file(restoreDir, REGION)));

        // 4. deleted.files 语义（Main 中的处理逻辑）
        Main.applyDeletedFilesSafely(restoreDir, file(restoreDir, "deleted.files"));
        assertFalse("清单里的普通文件应当被删除", file(restoreDir, "world/data/extra.txt").exists());
        assertFalse("清单里的 .mcc 应当被删除", file(restoreDir, "world/region/r.0.0.mcc").exists());
        assertFalse("清单里的 .mca 应当被删除", file(restoreDir, "world/region/r.1.0.mca").exists());
        assertFalse("清单本身处理完应当消失", file(restoreDir, "deleted.files").exists());
        assertTrue("没有被删除的文件应当保留", file(restoreDir, "world/level.dat").exists());
        assertNoTempFilesLeft(restoreDir);
    }

    @Test
    public void testRawOnlyBackupGroupStillMerges() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        assertTrue(restoreDir.mkdirs());

        byte[] fullRegion = region().chunk(0, 1, 100, chunkData(0, 1)).build();
        byte[] increRegion = region().chunk(9, 2, 900, chunkData(9, 2)).build();
        File fullZip = zip(root, "full.zip", entries(REGION, fullRegion, "server.properties", text("motd=A")));
        File increZip = zip(root, "incre000001.zip", entries(REGION, increRegion, "server.properties", text("motd=B")));

        assertTrue(Utils.unzip(fullZip, restoreDir));
        assertTrue(Utils.mergeIncrementalZip(increZip, restoreDir));
        assertArrayEquals("完整 .mca 条目应当原样覆盖", increRegion, read(file(restoreDir, REGION)));
        assertEquals("motd=B", new String(read(file(restoreDir, "server.properties")), StandardCharsets.UTF_8));
        assertNoTempFilesLeft(restoreDir);
    }

    @Test
    public void testDeltaAcceptsBaseMcaMissingOnlyTailPadding() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        assertTrue(restoreDir.mkdirs());

        int chunkLength = 353;
        byte[] fullChunk = anvilChunkData(7, 1, chunkLength, 2);
        byte[] fullRegion = region().chunk(0, 1, 100, fullChunk).build();
        byte[] shortRegion = Arrays.copyOf(fullRegion, McaDeltaMerger.HEADER_SIZE + 4 + chunkLength);
        File fullZip = zip(root, "full.zip", entries(REGION, shortRegion));
        File increZip = zip(root, "incre000001.zip", entries(REGION, delta()));

        assertTrue(Utils.unzip(fullZip, restoreDir));
        assertArrayEquals("全量包里的 raw MCA 应当保持原始物理长度", shortRegion, read(file(restoreDir, REGION)));
        assertTrue("只有尾部 padding 缺失的基线应当可以应用 PSMCA", Utils.mergeIncrementalZip(increZip, restoreDir));

        byte[] merged = read(file(restoreDir, REGION));
        assertEquals(McaDeltaMerger.HEADER_SIZE + McaDeltaMerger.SECTOR_SIZE, merged.length);
        assertEquals(new ChunkState(1, fullChunk), stateOf(parseRegion(merged), 0));
        assertNoTempFilesLeft(restoreDir);
    }

    @Test
    public void testMergedOutputCanBeZippedAndUnzippedAgain() throws Exception {
        // 模拟 Main 的最后一步: 合并完的目录打包成 merged.zip，再解压应当得到同样的文件树
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        File againDir = new File(root, "again");
        assertTrue(restoreDir.mkdirs() && againDir.mkdirs());

        byte[] fullRegion = region().chunk(0, 1, 100, chunkData(0, 1)).build();
        byte[] deltaBytes = delta(chunk(0, 2, chunkData(10, 2)));
        File fullZip = zip(root, "full.zip", entries(REGION, fullRegion, "世界/存档/说明.txt", text("你好")));
        File increZip = zip(root, "incre000001.zip", entries(REGION, deltaBytes));

        assertTrue(Utils.unzip(fullZip, restoreDir));
        assertTrue(Utils.mergeIncrementalZip(increZip, restoreDir));
        File mergedZip = new File(root, "merged.zip");
        assertTrue(Utils.zip(restoreDir.listFiles(), mergedZip, restoreDir));
        assertTrue(Utils.unzip(mergedZip, againDir));

        File mergedRegion = file(againDir, REGION);
        assertArrayEquals(read(file(restoreDir, REGION)), read(mergedRegion));
        assertEquals(new ChunkState(2, chunkData(10, 2)), stateOf(parseRegion(mergedRegion), 0));
        assertEquals("你好", new String(read(file(againDir, "世界/存档/说明.txt")), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 非法/损坏输入

    @Test
    public void testFullBackupContainingDeltaIsRejected() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        assertTrue(restoreDir.mkdirs());
        byte[] deltaBytes = delta(chunk(0, 1, chunkData(1, 1)));
        File fullZip = zip(root, "full.zip", entries(REGION, deltaBytes, "world/level.dat", text("LEVEL")));

        assertFalse("全量包里出现 PSMCA delta 应当直接失败", Utils.unzip(fullZip, restoreDir));
        assertFalse("不能把 delta 当成 .mca 落盘", file(restoreDir, REGION).exists());
        assertNoTempFilesLeft(restoreDir);
    }

    @Test
    public void testDeltaWithoutBaselineIsRejected() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        assertTrue(restoreDir.mkdirs());
        File increZip = zip(root, "incre000001.zip", entries(REGION, delta(chunk(0, 1, chunkData(1, 1)))));

        assertFalse("没有基线时 delta 无法应用", Utils.mergeIncrementalZip(increZip, restoreDir));
        assertFalse("失败时不能生成目标区域文件", file(restoreDir, REGION).exists());
        assertNoTempFilesLeft(restoreDir);
    }

    @Test
    public void testDeltaBaselineIsItselfADeltaIsRejected() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        File regionFile = file(restoreDir, REGION);
        assertTrue(regionFile.getParentFile().mkdirs());
        byte[] existingDelta = delta(chunk(0, 2, chunkData(1, 2)));
        Files.write(regionFile.toPath(), existingDelta);
        File increZip = zip(root, "incre000001.zip", entries(REGION, delta(chunk(0, 1, chunkData(2, 1)))));

        assertFalse("基线本身是 delta 时应当失败", Utils.mergeIncrementalZip(increZip, restoreDir));
        assertArrayEquals("失败时基线不能被改动", existingDelta, read(regionFile));
        assertNoTempFilesLeft(restoreDir);
    }

    @Test
    public void testCorruptDeltaKeepsBaselineIntact() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        File regionFile = file(restoreDir, REGION);
        assertTrue(regionFile.getParentFile().mkdirs());
        byte[] baseRegion = region().chunk(0, 1, 100, chunkData(0, 1)).build();
        Files.write(regionFile.toPath(), baseRegion);

        byte[] full = delta(chunk(0, 2, chunkData(9, 2)));
        File increZip = zip(root, "incre000001.zip",
                entries(REGION, java.util.Arrays.copyOf(full, full.length - 50)));

        assertFalse("损坏的 delta 必须让本次合并失败", Utils.mergeIncrementalZip(increZip, restoreDir));
        assertArrayEquals("失败后基线文件必须保持完整", baseRegion, read(regionFile));
        assertNoTempFilesLeft(restoreDir);
    }

    @Test
    public void testZipSlipEntriesAreRejected() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        assertTrue(restoreDir.mkdirs());
        File outside = new File(root, "outside.txt");
        File absoluteTarget = new File(root.getAbsoluteFile(), "abs.txt");

        File evilMerge = zip(root, "evil-merge.zip", entries(
                "../outside.txt", text("evil"),
                "/abs.txt", text("evil"),
                "world/../../outside.txt", text("evil")));
        assertFalse("含路径穿越条目的增量包应当被拒绝", Utils.mergeIncrementalZip(evilMerge, restoreDir));
        assertFalse("../outside.txt 不能被创建", outside.exists());
        assertFalse("绝对路径条目不能被创建", absoluteTarget.exists());

        File evilUnzip = zip(root, "evil-unzip.zip", entries("../outside.txt", text("evil")));
        assertFalse("含路径穿越条目的全量包应当被拒绝", Utils.unzip(evilUnzip, restoreDir));
        assertFalse(outside.exists());
    }

    @Test
    public void testDeletedFilesManifestRejectsUnsafePaths() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        assertTrue(restoreDir.mkdirs());
        File outside = new File(root, "outside.txt");
        Files.write(outside.toPath(), text("keep me"));
        File inside = file(restoreDir, "keep.txt");
        Files.write(inside.toPath(), text("delete me"));

        File manifest = file(restoreDir, "deleted.files");
        Files.write(manifest.toPath(), text("../outside.txt\nkeep.txt\n/abs.txt\n.\n"));

        Main.applyDeletedFilesSafely(restoreDir, manifest);
        assertTrue("恢复目录之外的文件不能被清单删掉", outside.exists());
        assertFalse("清单里的正常路径应当被删除", inside.exists());
        assertFalse("清单本身应当被删除", manifest.exists());
        assertTrue("恢复目录本身不能被删除", restoreDir.isDirectory());
    }

    @Test
    public void testDeletedFilesManifestReportsDeletionFailure() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        assertTrue(restoreDir.mkdirs());

        File nonEmptyDirectory = new File(restoreDir, "directory");
        assertTrue(nonEmptyDirectory.mkdirs());
        Files.write(new File(nonEmptyDirectory, "child.txt").toPath(), text("keep me"));
        File manifest = file(restoreDir, "deleted.files");
        Files.write(manifest.toPath(), text("directory\n"));

        assertFalse("清单中的文件删除失败时应报告失败",
                Main.applyDeletedFilesSafely(restoreDir, manifest));
        assertTrue("删除失败的目录应当保留", nonEmptyDirectory.exists());
        assertTrue("删除失败时清单应当保留以便定位问题", manifest.exists());
    }

    // ------------------------------------------------------------------ 路径与后缀

    @Test
    public void testUpperCaseMcaSuffixAndNonAsciiPaths() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        assertTrue(restoreDir.mkdirs());
        String upperRegion = "世界/存档/r.0.0.MCA";

        byte[] baseRegion = region().chunk(0, 1, 100, chunkData(0, 1)).build();
        File fullZip = zip(root, "full.zip", entries(
                upperRegion, baseRegion,
                "世界/存档/说明.txt", text("你好")));
        // 后缀大小写不同也要按 .mca 处理（与备份端 isMcaPath 的语义一致）
        File increZip = zip(root, "incre000001.zip", entries(
                upperRegion, delta(chunk(0, 2, chunkData(10, 2)))));

        assertTrue(Utils.unzip(fullZip, restoreDir));
        assertEquals("你好", new String(read(file(restoreDir, "世界/存档/说明.txt")), StandardCharsets.UTF_8));
        assertTrue(Utils.mergeIncrementalZip(increZip, restoreDir));
        byte[] merged = read(file(restoreDir, upperRegion));
        assertFalse(isDelta(merged));
        assertEquals(new ChunkState(2, chunkData(10, 2)), stateOf(parseRegion(merged), 0));
    }

    @Test
    public void testDirectoryEntriesAndEmptyZip() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        assertTrue(restoreDir.mkdirs());
        File fullZip = zip(root, "full.zip", entries(
                "world/", new byte[0],
                "world/region/", new byte[0],
                "world/region/r.0.0.mca", region().chunk(0, 1, 100, chunkData(0, 1)).build(),
                "world/empty.txt", new byte[0]));

        assertTrue(Utils.unzip(fullZip, restoreDir));
        assertTrue("显式目录条目应当被创建", file(restoreDir, "world/region").isDirectory());
        assertTrue("空文件应当被创建", file(restoreDir, "world/empty.txt").isFile());
        assertEquals(0, file(restoreDir, "world/empty.txt").length());

        File emptyZip = zip(root, "incre000001.zip", entries());
        assertTrue("空增量包应当是无操作而不是失败", Utils.mergeIncrementalZip(emptyZip, restoreDir));
        assertNoTempFilesLeft(restoreDir);
    }

    @Test
    public void testMissingZipFileFails() throws Exception {
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        assertTrue(restoreDir.mkdirs());
        assertFalse(Utils.unzip(new File(root, "missing.zip"), restoreDir));
        assertFalse(Utils.mergeIncrementalZip(new File(root, "missing.zip"), restoreDir));
    }

    // ------------------------------------------------------------------ 输出压缩包

    @Test
    public void testZippedEntryNamesUseForwardSlashes() throws Exception {
        // Windows 上 Path.toString() 用反斜杠作分隔符，如果直接拿它当 zip 条目名，
        // 在 Linux 上解压会得到一个名为 "world\region\r.0.0.mca" 的单层文件，服务端读不到。
        File root = tmp.newFolder();
        File restoreDir = new File(root, "restore");
        File regionDir = file(restoreDir, "world/region");
        assertTrue(regionDir.mkdirs());
        Files.write(file(regionDir, "r.0.0.mca").toPath(),
                region().chunk(0, 1, 100, chunkData(0, 1)).build());
        Files.write(file(restoreDir, "level.dat").toPath(), text("LEVEL"));

        File mergedZip = new File(root, "merged.zip");
        assertTrue(Utils.zip(restoreDir.listFiles(), mergedZip, restoreDir));

        List<String> names = new ArrayList<>();
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(mergedZip))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null)
                names.add(entry.getName());
        }
        assertTrue("应当包含正斜杠形式的条目名: " + names, names.contains("world/region/r.0.0.mca"));
        assertTrue("根目录下的文件也应当存在: " + names, names.contains("level.dat"));
        for (String name : names)
            assertFalse("zip 条目名必须使用正斜杠: " + name, name.contains("\\"));
    }

    // ------------------------------------------------------------------ 测试辅助

    /**
     * 按顺序拼一个 zip 包（用 LinkedHashMap 保证条目顺序）
     */
    private static File zip(File dir, String zipName, Map<String, byte[]> entries) throws IOException {
        File zipFile = new File(dir, zipName);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return zipFile;
    }

    /**
     * 造一个有序的条目表
     *
     * @param pairs 交替的「条目名, 内容字节」
     */
    private static Map<String, byte[]> entries(Object... pairs) {
        if (pairs.length % 2 != 0)
            throw new IllegalArgumentException("参数必须成对出现");
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
            map.put((String) pairs[i], (byte[]) pairs[i + 1]);
        return map;
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static File file(File dir, String relativePath) {
        return new File(dir, relativePath);
    }

    private static byte[] read(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    /**
     * 断言目录树里没有残留临时文件
     */
    private static void assertNoTempFilesLeft(File dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            List<Path> leftovers = walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(".psentry-") || name.startsWith(".psdelta-")
                                || name.startsWith(".psmerged-");
                    })
                    .collect(Collectors.toList());
            if (!leftovers.isEmpty())
                fail("目录里残留了临时文件: " + leftovers);
        }
    }
}
