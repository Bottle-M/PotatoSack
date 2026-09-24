package indi.somebottle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static indi.somebottle.RegionFixtures.CHUNK_COUNT;
import static indi.somebottle.RegionFixtures.ChunkState;
import static indi.somebottle.RegionFixtures.DeltaRecord;
import static indi.somebottle.RegionFixtures.SECTOR_SIZE;
import static indi.somebottle.RegionFixtures.chunk;
import static indi.somebottle.RegionFixtures.chunkData;
import static indi.somebottle.RegionFixtures.concat;
import static indi.somebottle.RegionFixtures.del;
import static indi.somebottle.RegionFixtures.delta;
import static indi.somebottle.RegionFixtures.isDelta;
import static indi.somebottle.RegionFixtures.parseRegion;
import static indi.somebottle.RegionFixtures.region;
import static indi.somebottle.RegionFixtures.stateOf;
import static indi.somebottle.RegionFixtures.timestampTable;
import static indi.somebottle.RegionFixtures.writeTemp;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link McaDeltaMerger} 的单元测试: 「基线区域文件 + PSMCA delta = 完整区域文件」
 *
 * <p>重建过程会重新分配扇区（和 Minecraft 自己写区块一样），因此断言的是<b>区块层面</b>的等价
 * （存在性、扇区数、逐字节 payload）以及时间戳表的保留策略，而不是整个文件的逐字节相等。</p>
 */
public class McaDeltaMergerTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // ------------------------------------------------------------------ 正常合并

    @Test
    public void testSingleChunkChange() throws Exception {
        byte[] base = region()
                .chunk(0, 1, 100, chunkData(0, 1))
                .chunk(5, 2, 500, chunkData(5, 2))
                .chunk(7, 1, 700, chunkData(7, 1))
                .build();
        byte[] deltaBytes = delta(chunk(5, 3, 501, chunkData(55, 3)));
        File[] files = prepare(base, deltaBytes);

        File outFile = new File(files[0], "out.mca");
        McaDeltaMerger.apply(files[1], files[2], outFile);

        assertArrayEquals("apply 不能修改基线文件", base, Files.readAllBytes(files[1].toPath()));
        byte[] out = Files.readAllBytes(outFile.toPath());
        assertFalse("输出不能还是 delta", isDelta(out));
        Map<Integer, ChunkState> state = parseRegion(out);
        assertEquals("变动区块应当等于 delta 里的数据", new ChunkState(3, chunkData(55, 3)), stateOf(state, 5));
        assertEquals("未变动区块应当保留", new ChunkState(1, chunkData(0, 1)), stateOf(state, 0));
        assertEquals("未变动区块应当保留", new ChunkState(1, chunkData(7, 1)), stateOf(state, 7));
        assertEquals(501L, RegionFixtures.timesOf(out)[5]);
        assertEquals(100L, RegionFixtures.timesOf(out)[0]);
        assertEquals(700L, RegionFixtures.timesOf(out)[7]);
        assertNoTempFilesLeft(files[0]);
        // 紧凑重排: 2 个头部扇区 + 1 + 3 + 1
        assertEquals((2 + 1 + 3 + 1) * SECTOR_SIZE, out.length);
    }

    @Test
    public void testVersionedDeltaRestoresChangedAndDeletedTimestamps() throws Exception {
        byte[] base = region()
                .chunk(1, 1, 100, chunkData(1, 1))
                .chunk(2, 1, 200, chunkData(2, 1))
                .chunk(3, 1, 300, chunkData(3, 1))
                .build();
        long[] updatedTimes = RegionFixtures.timesOf(base);
        updatedTimes[1] = 0xF1234567L; // uint32 最高位为 1，编码恰好 5 字节
        updatedTimes[2] = 201; // 删除后的非零时间戳也必须恢复
        byte[] newDelta = delta(chunk(1, 2, updatedTimes[1], chunkData(11, 2)), del(2, updatedTimes[2]));
        File[] files = prepare(base, newDelta);
        File outFile = new File(files[0], "versioned.mca");
        McaDeltaMerger.apply(files[1], files[2], outFile);
        byte[] restored = Files.readAllBytes(outFile.toPath());
        assertEquals(new ChunkState(2, chunkData(11, 2)), stateOf(parseRegion(restored), 1));
        assertEquals(ChunkState.DELETED, stateOf(parseRegion(restored), 2));
        assertEquals(updatedTimes[1], RegionFixtures.timesOf(restored)[1]);
        assertEquals(updatedTimes[2], RegionFixtures.timesOf(restored)[2]);
        assertEquals(300L, RegionFixtures.timesOf(restored)[3]);
    }

    @Test
    public void testDeltaRecordsInArbitraryOrder() throws Exception {
        byte[] base = region()
                .chunk(1, 1, 100, chunkData(1, 1))
                .chunk(3, 2, 300, chunkData(3, 2))
                .chunk(9, 1, 900, chunkData(9, 1))
                .reverseAllocation()
                .gap(2)
                .build();
        // 记录顺序故意是: 删除、大下标、小下标，且与扇区顺序无关
        byte[] deltaBytes = delta(del(3, 300), chunk(9, 4, 900, chunkData(99, 4)),
                chunk(1, 2, 100, chunkData(11, 2)));
        File[] files = prepare(base, deltaBytes);
        File outFile = new File(files[0], "out.mca");
        McaDeltaMerger.apply(files[1], files[2], outFile);

        Map<Integer, ChunkState> state = parseRegion(outFile);
        assertEquals(ChunkState.DELETED, stateOf(state, 3));
        assertEquals(new ChunkState(4, chunkData(99, 4)), stateOf(state, 9));
        assertEquals(new ChunkState(2, chunkData(11, 2)), stateOf(state, 1));
        assertArrayEquals(timestampTable(base), timestampTable(Files.readAllBytes(outFile.toPath())));
    }

    @Test
    public void testChunkDeletionZeroesLocationEntry() throws Exception {
        byte[] base = region()
                .chunk(2, 1, 200, chunkData(2, 1))
                .chunk(4, 2, 400, chunkData(4, 2))
                .build();
        File[] files = prepare(base, delta(del(2, 201)));
        File outFile = new File(files[0], "out.mca");
        McaDeltaMerger.apply(files[1], files[2], outFile);
        byte[] out = Files.readAllBytes(outFile.toPath());

        Map<Integer, ChunkState> state = parseRegion(out);
        assertEquals("被删除的区块不应再存在", ChunkState.DELETED, stateOf(state, 2));
        assertEquals("其他区块不受影响", new ChunkState(2, chunkData(4, 2)), stateOf(state, 4));
        // 位置表对应 4 字节应全部为 0
        for (int p = 2 * 4; p < 2 * 4 + 4; p++)
            assertEquals("区块 2 的位置表项应当清零", 0, out[p]);
        assertEquals("被删除区块的时间戳应由 delta 恢复", 201L, RegionFixtures.timesOf(out)[2]);
    }

    @Test
    public void testSectorCountsOneTwoAnd255() throws Exception {
        byte[] base = region()
                .chunk(0, 1, 100, chunkData(0, 1))
                .chunk(1, 2, 200, chunkData(1, 2))
                .chunk(2, 255, 300, chunkData(2, 255))
                .chunk(3, 2, 400, chunkData(3, 2))
                .build();
        // 1 -> 255、2 -> 1、255 -> 2，按 sectorCount * 4096 精确复制（填充字节不能丢）
        byte[] deltaBytes = delta(
                chunk(0, 255, chunkData(10, 255)),
                chunk(1, 1, chunkData(11, 1)),
                chunk(2, 2, chunkData(12, 2)));
        File[] files = prepare(base, deltaBytes);
        File outFile = new File(files[0], "out.mca");
        McaDeltaMerger.apply(files[1], files[2], outFile);

        Map<Integer, ChunkState> state = parseRegion(outFile);
        assertEquals(new ChunkState(255, chunkData(10, 255)), stateOf(state, 0));
        assertEquals(new ChunkState(1, chunkData(11, 1)), stateOf(state, 1));
        assertEquals(new ChunkState(2, chunkData(12, 2)), stateOf(state, 2));
        assertEquals("未变动区块保留原扇区数", new ChunkState(2, chunkData(3, 2)), stateOf(state, 3));
    }

    @Test
    public void testEmptyDeltaIsAccepted() throws Exception {
        byte[] base = region()
                .chunk(1, 1, 100, chunkData(1, 1))
                .chunk(8, 2, 800, chunkData(8, 2))
                .build();
        File[] files = prepare(base, delta());
        File outFile = new File(files[0], "out.mca");
        McaDeltaMerger.apply(files[1], files[2], outFile);

        assertEquals("count=0 的空 delta 应当等价于基线", parseRegion(base), parseRegion(outFile));
        assertArrayEquals(timestampTable(base), timestampTable(Files.readAllBytes(outFile.toPath())));
    }

    @Test
    public void testNewChunkAppears() throws Exception {
        byte[] base = region().chunk(1, 1, 100, chunkData(1, 1)).build();
        File[] files = prepare(base, delta(chunk(100, 2, 101, chunkData(100, 2))));
        File outFile = new File(files[0], "out.mca");
        McaDeltaMerger.apply(files[1], files[2], outFile);

        Map<Integer, ChunkState> state = parseRegion(outFile);
        assertEquals(new ChunkState(2, chunkData(100, 2)), stateOf(state, 100));
        assertEquals(new ChunkState(1, chunkData(1, 1)), stateOf(state, 1));
        assertEquals("新出现的区块必须采用 delta 时间戳", 101L, RegionFixtures.timesOf(Files.readAllBytes(outFile.toPath()))[100]);
    }

    @Test
    public void testDeletingAbsentChunkIsNoOp() throws Exception {
        byte[] base = region().chunk(1, 1, 100, chunkData(1, 1)).build();
        File[] files = prepare(base, delta(del(500)));
        File outFile = new File(files[0], "out.mca");
        McaDeltaMerger.apply(files[1], files[2], outFile);
        assertEquals(parseRegion(base), parseRegion(outFile));
    }

    @Test
    public void testOutputIsCompactlyRepackedFromSector2() throws Exception {
        // 基线里区块之间有空闲扇区、且扇区顺序与下标顺序不同
        byte[] base = region()
                .chunk(4, 1, 400, chunkData(4, 1))
                .chunk(0, 2, 100, chunkData(0, 2))
                .chunk(9, 1, 900, chunkData(9, 1))
                .reverseAllocation()
                .gap(3)
                .build();
        File[] files = prepare(base, delta(chunk(0, 1, chunkData(10, 1))));
        File outFile = new File(files[0], "out.mca");
        McaDeltaMerger.apply(files[1], files[2], outFile);
        byte[] out = Files.readAllBytes(outFile.toPath());

        int[] expectedOffsets = new int[CHUNK_COUNT];
        int[] expectedSectors = new int[CHUNK_COUNT];
        expectedOffsets[0] = 2;
        expectedSectors[0] = 1;
        expectedOffsets[4] = 3;
        expectedSectors[4] = 1;
        expectedOffsets[9] = 4;
        expectedSectors[9] = 1;
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int p = i * 4;
            int offset = ((out[p] & 0xFF) << 16) | ((out[p + 1] & 0xFF) << 8) | (out[p + 2] & 0xFF);
            int sectors = out[p + 3] & 0xFF;
            assertEquals("区块 " + i + " 的扇区偏移应当紧凑重排", expectedOffsets[i], offset);
            assertEquals("区块 " + i + " 的扇区数", expectedSectors[i], sectors);
        }
        assertEquals("文件应当只包含头部和有效扇区", 5 * SECTOR_SIZE, out.length);
    }

    @Test
    public void testTwoConsecutiveDeltas() throws Exception {
        byte[] base = region()
                .chunk(1, 1, 100, chunkData(1, 1))
                .chunk(2, 2, 200, chunkData(2, 2))
                .chunk(3, 1, 300, chunkData(3, 1))
                .gap(2)
                .build();
        File dir = tmp.newFolder();
        File baseFile = writeTemp(dir, "base.mca", base);
        File delta1 = writeTemp(dir, "delta1.mca", delta(chunk(2, 3, 201, chunkData(22, 3)), del(3, 301)));
        File out1 = new File(dir, "out1.mca");
        McaDeltaMerger.apply(baseFile, delta1, out1);

        // 第二份 delta 以第一份的输出为基线，不能依赖原文件里的扇区偏移
        File delta2 = writeTemp(dir, "delta2.mca", delta(chunk(1, 4, 101, chunkData(111, 4)),
                chunk(3, 1, 302, chunkData(33, 1))));
        File out2 = new File(dir, "out2.mca");
        McaDeltaMerger.apply(out1, delta2, out2);

        Map<Integer, ChunkState> state = parseRegion(out2);
        assertEquals(new ChunkState(4, chunkData(111, 4)), stateOf(state, 1));
        assertEquals("第一份 delta 的改动应当保留", new ChunkState(3, chunkData(22, 3)), stateOf(state, 2));
        assertEquals("第一份 delta 删除的区块应当能被第二份恢复", new ChunkState(1, chunkData(33, 1)), stateOf(state, 3));
        long[] restoredTimes = RegionFixtures.timesOf(Files.readAllBytes(out2.toPath()));
        assertEquals(101L, restoredTimes[1]);
        assertEquals(201L, restoredTimes[2]);
        assertEquals(302L, restoredTimes[3]);
    }

    // ------------------------------------------------------------------ MAGIC 检测

    @Test
    public void testHasMagic() throws Exception {
        File dir = tmp.newFolder();
        byte[] deltaBytes = delta(chunk(0, 1, chunkData(1, 1)));
        File deltaFile = writeTemp(dir, "delta.mca", deltaBytes);
        File rawFile = writeTemp(dir, "raw.mca", region().chunk(0, 1, 1, chunkData(1, 1)).build());
        File tinyFile = writeTemp(dir, "tiny.mca", new byte[]{'P', 'S', 'M'});
        File missing = new File(dir, "missing.mca");

        assertTrue(McaDeltaMerger.hasMagic(deltaFile));
        assertFalse(McaDeltaMerger.hasMagic(rawFile));
        assertFalse("不足 6 字节的文件不是 delta", McaDeltaMerger.hasMagic(tinyFile));
        assertFalse("文件不存在不算 delta", McaDeltaMerger.hasMagic(missing));
    }

    // ------------------------------------------------------------------ 基线异常

    @Test
    public void testMissingBaseFails() throws Exception {
        File dir = tmp.newFolder();
        File missingBase = new File(dir, "missing.mca");
        File deltaFile = writeTemp(dir, "delta.mca", delta(chunk(0, 1, chunkData(1, 1))));
        File out = new File(dir, "out.mca");
        try {
            McaDeltaMerger.apply(missingBase, deltaFile, out);
            fail("基线不存在时应当失败");
        } catch (IOException e) {
            assertTrue("错误信息应当指明基线: " + e.getMessage(), e.getMessage().contains("base"));
        }
        assertFalse("失败时不能生成目标文件", out.exists());
        assertNoTempFilesLeft(dir);
    }

    @Test
    public void testBaseBeingADeltaFails() throws Exception {
        // 一个"基线"其实是 delta: 头部那些字节不可能构成合法的区域位置表
        File dir = tmp.newFolder();
        File baseFile = writeTemp(dir, "base.mca", delta(chunk(0, 2, chunkData(1, 2))));
        File deltaFile = writeTemp(dir, "delta.mca", delta(chunk(0, 1, chunkData(2, 1))));
        File out = new File(dir, "out.mca");
        try {
            McaDeltaMerger.apply(baseFile, deltaFile, out);
            fail("基线本身是 delta 时应当失败");
        } catch (IOException e) {
            assertTrue("错误信息应当指明基线非法: " + e.getMessage(), e.getMessage().contains("Invalid base region file"));
        }
        assertFalse(out.exists());
        assertNoTempFilesLeft(dir);
    }

    @Test
    public void testBaseShorterThanHeaderFails() throws Exception {
        File dir = tmp.newFolder();
        File baseFile = writeTemp(dir, "base.mca", new byte[100]);
        File deltaFile = writeTemp(dir, "delta.mca", delta());
        File out = new File(dir, "out.mca");
        try {
            McaDeltaMerger.apply(baseFile, deltaFile, out);
            fail("基线短于 8 KiB 时应当失败");
        } catch (IOException e) {
            assertTrue("错误信息应当说明头部不完整: " + e.getMessage(), e.getMessage().contains("shorter than"));
        }
        assertFalse(out.exists());
    }

    @Test
    public void testBaseChunkPastEofFails() throws Exception {
        byte[] base = region().chunk(0, 1, 100, chunkData(0, 1)).build();
        // 把区块 0 的扇区数改成 5，让它的数据范围越过文件末尾
        base[3] = 5;
        File[] files = prepare(base, delta());
        assertApplyFails(files[0], files[1], files[2], "past the end");
    }

    @Test
    public void testBaseOverlappingChunksFails() throws Exception {
        byte[] base = region()
                .chunk(0, 1, 100, chunkData(0, 1))
                .chunk(1, 1, 200, chunkData(1, 1))
                .build();
        // 让区块 1 的扇区偏移等于区块 0 的（2），制造区间重叠
        base[4] = 0;
        base[5] = 0;
        base[6] = 2;
        File[] files = prepare(base, delta());
        assertApplyFails(files[0], files[1], files[2], "overlaps");
    }

    @Test
    public void testBaseChunkInsideHeaderFails() throws Exception {
        byte[] base = region().chunk(0, 1, 100, chunkData(0, 1)).build();
        // 把区块 0 的扇区偏移改成 1（落在 8 KiB 头部里）
        base[0] = 0;
        base[1] = 0;
        base[2] = 1;
        File[] files = prepare(base, delta());
        assertApplyFails(files[0], files[1], files[2], "inside the");
    }

    // ------------------------------------------------------------------ delta 异常

    @Test
    public void testTruncatedPayloadFails() throws Exception {
        byte[] base = region().chunk(0, 1, 100, chunkData(0, 1)).build();
        byte[] full = delta(chunk(0, 2, chunkData(9, 2)));
        byte[] truncated = Arrays.copyOf(full, full.length - 100);
        File[] files = prepare(base, truncated);
        assertApplyFails(files[0], files[1], files[2], "truncated");
    }

    @Test
    public void testTrailingGarbageFails() throws Exception {
        byte[] base = region().chunk(0, 1, 100, chunkData(0, 1)).build();
        byte[] withGarbage = concat(delta(chunk(0, 1, chunkData(9, 1))), new byte[]{1, 2, 3, 4});
        File[] files = prepare(base, withGarbage);
        assertApplyFails(files[0], files[1], files[2], "trailing");
    }

    @Test
    public void testDuplicateChunkIndexFails() throws Exception {
        byte[] base = region().chunk(5, 1, 100, chunkData(5, 1)).build();
        File[] files = prepare(base, delta(chunk(5, 1, chunkData(9, 1)), del(5)));
        assertApplyFails(files[0], files[1], files[2], "more than once");
    }

    @Test
    public void testChunkIndexOutOfRangeFails() throws Exception {
        byte[] base = region().chunk(5, 1, 100, chunkData(5, 1)).build();
        File[] files = prepare(base, delta(del(1024)));
        assertApplyFails(files[0], files[1], files[2], "out of range");
    }

    @Test
    public void testChunkCountTooLargeFails() throws Exception {
        byte[] base = region().chunk(5, 1, 100, chunkData(5, 1)).build();
        List<DeltaRecord> records = new ArrayList<>();
        records.add(del(5));
        File[] files = prepare(base, delta(1025, records));
        assertApplyFails(files[0], files[1], files[2], "exceeds the maximum");
    }

    @Test
    public void testTruncatedDeltaHeaderFails() throws Exception {
        byte[] base = region().chunk(5, 1, 100, chunkData(5, 1)).build();
        // 声明有 1 条记录，但一条都没写
        File[] files = prepare(base, delta(1, new ArrayList<>()));
        assertApplyFails(files[0], files[1], files[2], "truncated header");
    }

    @Test
    public void testTruncatedMagicFails() throws Exception {
        byte[] base = region().chunk(5, 1, 100, chunkData(5, 1)).build();
        File[] files = prepare(base, new byte[]{'P', 'S', 'M', 'C', 'A'});
        assertApplyFails(files[0], files[1], files[2], "too short");
    }

    @Test
    public void testWrongMagicFails() throws Exception {
        byte[] base = region().chunk(5, 1, 100, chunkData(5, 1)).build();
        byte[] deltaBytes = delta(chunk(5, 1, chunkData(9, 1)));
        deltaBytes[5] = 'X';
        File[] files = prepare(base, deltaBytes);
        assertApplyFails(files[0], files[1], files[2], "PSMCA magic");
    }

    @Test
    public void testUnsupportedDeltaVersionFails() throws Exception {
        byte[] base = region().chunk(5, 1, 100, chunkData(5, 1)).build();
        byte[] deltaBytes = delta(chunk(5, 1, chunkData(9, 1)));
        deltaBytes[7] = 2;
        File[] files = prepare(base, deltaBytes);
        assertApplyFails(files[0], files[1], files[2], "Unsupported PSMCA delta format version");
    }

    @Test
    public void testDeltaWithoutVersionFails() throws Exception {
        byte[] base = region().chunk(5, 1, 100, chunkData(5, 1)).build();
        byte[] unversionedEmptyDelta = {'P', 'S', 'M', 'C', 'A', 0, 0, 0};
        File[] files = prepare(base, unversionedEmptyDelta);
        assertApplyFails(files[0], files[1], files[2], "too short");
    }

    // ------------------------------------------------------------------ 扇区分配上限

    @Test
    public void testAllocateSectorOffsetsPacksFromSector2() throws Exception {
        int[] sectors = new int[CHUNK_COUNT];
        sectors[0] = 1;
        sectors[1] = 3;
        sectors[7] = 2;
        int[] offsets = McaDeltaMerger.allocateSectorOffsets(sectors, McaDeltaMerger.MAX_SECTOR_OFFSET);
        assertEquals(2, offsets[0]);
        assertEquals(3, offsets[1]);
        assertEquals(6, offsets[7]);
        assertEquals(0, offsets[2]);
    }

    @Test
    public void testAllocateSectorOffsetsRejectsOverflow() {
        int[] sectors = new int[CHUNK_COUNT];
        sectors[0] = 1;
        sectors[1] = 1;
        try {
            McaDeltaMerger.allocateSectorOffsets(sectors, 2);
            fail("超过 3 字节扇区偏移上限时应当失败");
        } catch (IOException e) {
            assertTrue("错误信息应当指明超出上限: " + e.getMessage(),
                    e.getMessage().contains("beyond the maximum representable sector offset"));
        }
    }

    // ------------------------------------------------------------------ 测试辅助

    /**
     * 在临时目录里准备一组「基线 + delta」
     *
     * @return {@code [目录, 基线文件, delta 文件]}
     */
    private File[] prepare(byte[] baseBytes, byte[] deltaBytes) throws IOException {
        File dir = tmp.newFolder();
        File baseFile = writeTemp(dir, "base.mca", baseBytes);
        File deltaFile = writeTemp(dir, "delta.mca", deltaBytes);
        return new File[]{dir, baseFile, deltaFile};
    }

    /**
     * 断言合并失败: 错误信息包含指定片段，已有目标文件不被改动，临时文件被清理
     */
    private static void assertApplyFails(File dir, File baseFile, File deltaFile, String expectedMessagePart)
            throws IOException {
        File out = new File(dir, "out.mca");
        byte[] sentinel = "SENTINEL-TARGET".getBytes(StandardCharsets.UTF_8);
        Files.write(out.toPath(), sentinel);
        try {
            McaDeltaMerger.apply(baseFile, deltaFile, out);
            fail("损坏输入应当抛 IOException（期望错误信息含 \"" + expectedMessagePart + "\"）");
        } catch (IOException e) {
            String message = e.getMessage();
            assertTrue("错误信息应当包含 \"" + expectedMessagePart + "\"，实际为: " + message,
                    message != null && message.contains(expectedMessagePart));
        }
        assertArrayEquals("失败时不能改动已有的目标文件", sentinel, Files.readAllBytes(out.toPath()));
        assertNoTempFilesLeft(dir);
    }

    /**
     * 断言目录里没有残留临时文件
     */
    private static void assertNoTempFilesLeft(File dir) {
        File[] files = dir.listFiles();
        assertTrue(files != null);
        for (File file : files) {
            String name = file.getName();
            assertFalse("临时文件未清理: " + name,
                    name.startsWith(".psentry-") || name.startsWith(".psdelta-") || name.startsWith(".psmerged-"));
        }
    }
}
