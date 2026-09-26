import indi.somebottle.potatosack.utils.McaDeltaInputStream;
import indi.somebottle.potatosack.utils.Utils;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * McaDeltaInputStream 的往返测试: 「上一次的 .mca + delta = 这一次的 .mca」，不依赖 Bukkit 服务端
 *
 * <p>测试里自带一个 delta 解码器（{@link #parseDelta(byte[])}）和一个 .mca 解析器（{@link #parseRegion(byte[])}），
 * 验证的是<b>区块层面</b>的等价而不是逐字节相等 —— 因为 delta 里不存扇区偏移，
 * 复原时必然要重新分配扇区（和 Minecraft 自己写区块一样），逐字节比较没有意义。</p>
 */
public class McaDeltaInputStreamTest {

    private static final int CHUNK_COUNT = McaDeltaInputStream.CHUNK_COUNT;
    private static final int SECTOR_SIZE = McaDeltaInputStream.SECTOR_SIZE;
    private static final int HEADER_SIZE = McaDeltaInputStream.HEADER_SIZE;

    // ------------------------------------------------------------------ 主流程

    @Test
    public void testDeltaRoundTrip() throws Exception {
        byte[] prev = region()
                .chunk(0, 1, 100, chunkData(0, 1))
                .chunk(1, 2, 200, chunkData(1, 2))
                .chunk(2, 1, 300, chunkData(2, 1))
                .chunk(3, 3, 400, chunkData(3, 3))
                .chunk(4, 1, 500, chunkData(4, 1))
                .chunk(5, 2, 600, chunkData(5, 2))
                .build();
        // 0/1/3 未变动；2、4 数据变了；5 被删除（时间戳变了但区块不存在）；10 是新出现的
        byte[] next = region()
                .chunk(0, 1, 100, chunkData(0, 1))
                .chunk(1, 2, 200, chunkData(1, 2))
                .chunk(2, 2, 301, chunkData(102, 2))
                .chunk(3, 3, 400, chunkData(3, 3))
                .chunk(4, 1, 501, chunkData(104, 1))
                .chunk(10, 1, 700, chunkData(10, 1))
                .build();

        File file = writeTemp(next);
        McaDeltaInputStream in = new McaDeltaInputStream(file, timesOf(prev));
        byte[] out = readAll(in, 4096);
        long crc = in.sourceCRC32();
        assertEquals(-1, in.read());
        in.close();

        assertTrue("有区块变动就应该走 delta", isDelta(out));
        assertEquals("sourceCRC32 必须和 Utils.fileCRC32 一致", Utils.fileCRC32(file), crc);

        Map<Integer, ChunkState> delta = parseDelta(out);
        assertEquals("只有变动的区块才该进 delta",
                new java.util.TreeSet<>(Arrays.asList(2, 4, 5, 10)),
                new java.util.TreeSet<>(delta.keySet()));

        assertRoundTrip(prev, next, delta);
    }

    @Test
    public void testOnlyChangedChunksArePacked() throws Exception {
        RegionBuilder prevBuilder = region();
        RegionBuilder nextBuilder = region();
        for (int i = 0; i < 64; i++) {
            byte[] data = chunkData(i, 2);
            prevBuilder.chunk(i, 2, 1000 + i, data);
            // 只让下标 7 的区块变动
            nextBuilder.chunk(i, 2, i == 7 ? 9999 : 1000 + i, i == 7 ? chunkData(777, 2) : data);
        }
        byte[] prev = prevBuilder.build();
        byte[] next = nextBuilder.build();

        byte[] out = deltaOf(next, timesOf(prev));
        Map<Integer, ChunkState> delta = parseDelta(out);
        assertEquals(1, delta.size());
        assertTrue(delta.containsKey(7));
        // 64 个区块 * 2 扇区 = 512 KiB，只打包一个区块的 delta 应该小得多
        assertTrue("delta 应当远小于原文件: " + out.length + " vs " + next.length,
                out.length < next.length / 20);
        assertRoundTrip(prev, next, delta);
    }

    @Test
    public void testDeletedChunkIsRecordedWithZeroSectors() throws Exception {
        byte[] prev = region()
                .chunk(5, 2, 600, chunkData(5, 2))
                .chunk(6, 1, 700, chunkData(6, 1))
                .build();
        // 5 被删除: 扇区偏移和扇区数都清零（原版约定 = 该区块未创建），时间戳也变了
        byte[] next = region()
                .deleted(5, 601)
                .chunk(6, 1, 700, chunkData(6, 1))
                .build();

        Map<Integer, ChunkState> delta = parseDelta(deltaOf(next, timesOf(prev)));
        assertEquals("被删除的区块也要出现在 delta 里，否则解包方不知道要删它",
                Collections.singleton(5), delta.keySet());
        assertEquals(0, delta.get(5).sectors());
        assertNull(delta.get(5).data());
        assertRoundTrip(prev, next, delta);
    }

    @Test
    public void testVersionAndUnsignedTimestampsIncludingDeletion() throws Exception {
        byte[] prev = region().chunk(5, 1, 100, chunkData(5, 1)).build();
        byte[] next = region().deleted(5, 0x80000000L)
                .chunk(6, 1, 0xF1234567L, chunkData(6, 1)).build();
        byte[] encoded = deltaOf(next, timesOf(prev));
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        bytes.position(McaDeltaInputStream.MAGIC.length);
        assertEquals(McaDeltaInputStream.DELTA_FORMAT_VERSION, bytes.getShort() & 0xFFFF);
        assertEquals(2, bytes.getShort() & 0xFFFF);
        assertEquals(5, bytes.getShort() & 0xFFFF);
        assertEquals(0, bytes.get() & 0xFF);
        assertEquals(0x80000000L, readTimestamp(bytes));
        assertEquals(6, bytes.getShort() & 0xFFFF);
        assertEquals(1, bytes.get() & 0xFF);
        assertEquals(0xF1234567L, readTimestamp(bytes));
        assertEquals(SECTOR_SIZE, bytes.remaining());
    }

    // ------------------------------------------------------------------ 输出与读取缓冲无关

    @Test
    public void testOutputIsIndependentOfReadBufferSize() throws Exception {
        byte[] prev = region()
                .chunk(2, 1, 300, chunkData(2, 1))
                .chunk(3, 3, 400, chunkData(3, 3))
                .chunk(9, 2, 900, chunkData(9, 2))
                .build();
        byte[] next = region()
                .chunk(2, 3, 301, chunkData(202, 3))
                .chunk(8, 1, 800, chunkData(8, 1))
                .chunk(9, 1, 901, chunkData(209, 1))
                .build();
        File file = writeTemp(next);
        long[] prevTimes = timesOf(prev);

        // 1 字节读会把区块头挤到下一次 read()，专门压 produce() 里那个"区块头待发"分支
        byte[] baseline = null;
        for (int bufSize : new int[]{1, 2, 3, 7, 4096, 1 << 20}) {
            McaDeltaInputStream in = new McaDeltaInputStream(file, prevTimes);
            byte[] out = readAll(in, bufSize);
            long crc = in.sourceCRC32();
            in.close();
            assertEquals("sourceCRC32 与读取缓冲无关", Utils.fileCRC32(file), crc);
            if (baseline == null)
                baseline = out;
            else
                assertArrayEquals("缓冲区大小 " + bufSize + " 下输出应当完全一致", baseline, out);
        }
        assertTrue(isDelta(baseline));
        assertRoundTrip(prev, next, parseDelta(baseline));
    }

    @Test
    public void testSingleByteReadMatchesBulkRead() throws Exception {
        byte[] prev = region().chunk(1, 1, 100, chunkData(1, 1)).build();
        byte[] next = region().chunk(1, 2, 101, chunkData(11, 2)).build();
        File file = writeTemp(next);

        McaDeltaInputStream in = new McaDeltaInputStream(file, timesOf(prev));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1)
            bos.write(b);
        long crc = in.sourceCRC32();
        in.close();

        assertArrayEquals(readAll(new McaDeltaInputStream(file, timesOf(prev)), 4096), bos.toByteArray());
        assertEquals(Utils.fileCRC32(file), crc);
    }

    // ------------------------------------------------------------------ 退化分支

    @Test
    public void testZeroChangedChunksFallsBackToRaw() throws Exception {
        // 时间戳一个都没变，但数据变了 —— 模拟"判据失效"，此时必须整个文件原样打包
        byte[] prev = region()
                .chunk(0, 1, 100, chunkData(0, 1))
                .chunk(1, 2, 200, chunkData(1, 2))
                .build();
        byte[] next = region()
                .chunk(0, 1, 100, chunkData(99, 1))
                .chunk(1, 2, 200, chunkData(98, 2))
                .build();
        assertFalse(Arrays.equals(prev, next));

        byte[] out = deltaOf(next, timesOf(prev));
        assertFalse("没有区块变动时必须退化", isDelta(out));
        assertArrayEquals(next, out);
    }

    @Test
    public void testMissingTailPaddingStillProducesDelta() throws Exception {
        byte[] prev = region()
                .chunk(1, 2, 200, chunkDataWithLength(1, 2, 500, 0x02))
                .build();
        byte[] next = region()
                .chunk(1, 2, 201, chunkDataWithLength(11, 2, 353, 0x02))
                .build();

        // 有效数据在 353 字节处完整结束，但最后一个已分配扇区没有把剩余 padding 真正写入文件。
        // 这是合法的 EOF 形态，delta 应继续生成，并把缺失的 sector padding 补 0。
        byte[] shortNext = Arrays.copyOf(next, HEADER_SIZE + 353);
        File file = writeTemp(shortNext);
        McaDeltaInputStream in = new McaDeltaInputStream(file, timesOf(prev));
        byte[] out = readAll(in, 8192);
        long sourceCrc = in.sourceCRC32();
        in.close();

        assertEquals("补出的零 padding 不能计入源文件 CRC", Utils.fileCRC32(file), sourceCrc);
        assertTrue("只缺扇区尾部 padding 时不应退化成 raw MCA", isDelta(out));
        Map<Integer, ChunkState> delta = parseDelta(out);
        assertEquals(Collections.singleton(1), delta.keySet());
        assertEquals(2, delta.get(1).sectors());
        assertEquals(2 * SECTOR_SIZE, delta.get(1).data().length);
        assertRoundTrip(prev, shortNext, delta);
    }

    @Test
    public void testExternalChunkStubMissingTailPaddingStillProducesDelta() throws Exception {
        byte[] prev = region()
                .chunk(1, 1, 200, chunkDataWithLength(1, 1, 5, 0x82))
                .build();
        byte[] next = region()
                .chunk(1, 1, 201, chunkDataWithLength(2, 1, 5, 0x82))
                .build();

        // 外置 .mcc 的 MCA 存根只有 [uint32 Length=1][compression/version] 这 5 字节；
        // .mca 自身无需读取 .mcc，就能确认存根完整。
        byte[] shortNext = Arrays.copyOf(next, HEADER_SIZE + 5);
        byte[] out = deltaOf(shortNext, timesOf(prev));

        assertTrue("完整的 external-chunk stub 不应因缺少 sector padding 而退化", isDelta(out));
        assertRoundTrip(prev, shortNext, parseDelta(out));
    }

    @Test
    public void testChunkPayloadPastEofFallsBackToRaw() throws Exception {
        byte[] prev = region()
                .chunk(1, 2, 200, chunkDataWithLength(1, 2, 1200, 0x02))
                .build();
        byte[] next = region()
                .chunk(1, 2, 201, chunkDataWithLength(11, 2, 1200, 0x02))
                .build();

        // Length 声明有效数据应到 1200 字节，但物理文件只保留 900 字节：这才是真正的数据截断。
        File file = writeTemp(Arrays.copyOf(next, HEADER_SIZE + 900));

        byte[] out = deltaOf(file, timesOf(prev));
        assertFalse(isDelta(out));
        assertArrayEquals(Files.readAllBytes(file.toPath()), out);
    }

    @Test
    public void testOverlappingChunksFallBackToRaw() throws Exception {
        byte[] prev = region()
                .chunk(0, 1, 100, chunkData(0, 1))
                .chunk(1, 1, 200, chunkData(1, 1))
                .build();
        byte[] next = region()
                .chunk(0, 1, 101, chunkData(10, 1))
                .chunk(1, 1, 201, chunkData(11, 1))
                .build();
        // 把下标 1 的扇区偏移改成和下标 0 一样，制造区间重叠
        int offset0 = ((next[0] & 0xFF) << 16) | ((next[1] & 0xFF) << 8) | (next[2] & 0xFF);
        int p = 4;
        next[p] = (byte) (offset0 >> 16);
        next[p + 1] = (byte) (offset0 >> 8);
        next[p + 2] = (byte) offset0;

        byte[] out = deltaOf(next, timesOf(prev));
        assertFalse("扇区区间重叠时必须退化", isDelta(out));
        assertArrayEquals(next, out);
    }

    @Test
    public void testFileShorterThanHeaderFallsBackToRaw() throws Exception {
        byte[] tiny = new byte[100];
        new Random(7).nextBytes(tiny);
        byte[] out = deltaOf(tiny, new long[CHUNK_COUNT]);
        assertArrayEquals(tiny, out);
    }

    // ------------------------------------------------------------------ 头部时间戳读取

    @Test
    public void testReadChunkTimes() throws Exception {
        long[] expected = new long[CHUNK_COUNT];
        RegionBuilder builder = region();
        for (int i = 0; i < CHUNK_COUNT; i++) {
            // 只放一部分区块，剩下的时间戳保持 0
            if (i % 5 != 0)
                continue;
            expected[i] = 1_700_000_000L + i;
            builder.chunk(i, 1, expected[i], chunkData(i, 1));
        }
        File file = writeTemp(builder.build());
        assertArrayEquals(expected, McaDeltaInputStream.readChunkTimes(file));

        // 头部都不完整的文件应当报错，而不是返回一堆 0
        File shortFile = writeTemp(new byte[100]);
        try {
            McaDeltaInputStream.readChunkTimes(shortFile);
            fail("不足 8 KiB 的文件应当抛 IOException");
        } catch (IOException expectedException) {
            // 符合预期
        }
    }

    @Test
    public void testTimestampIsReadAsUnsigned32Bit() throws Exception {
        // .mca 头部那个字段是 32 位无符号的，2038 年之后会越过 2^31。
        // 若按有符号 int 读，3_000_000_000 会变成 -1294967296，这里确认它是正数
        long post2038 = 3_000_000_000L;
        byte[] file = region()
                .chunk(1, 1, post2038, chunkData(1, 1))
                .chunk(2, 1, 4_000_000_000L, chunkData(2, 1))
                .build();
        long[] times = McaDeltaInputStream.readChunkTimes(writeTemp(file));
        assertEquals(post2038, times[1]);
        assertEquals(4_000_000_000L, times[2]);
        assertTrue("必须是无符号解释，不能是负数", times[1] > 0 && times[2] > 0);

        // 时间戳本身没变的话不该被判为变动：应当直接退化成原样输出
        byte[] out = deltaOf(file, times);
        assertFalse("时间戳没变就不该走 delta", isDelta(out));
        assertArrayEquals(file, out);
    }

    // ------------------------------------------------------------------ 测试辅助

    /**
     * 断言「上一次的文件 + delta = 这一次的文件」（区块层面）
     */
    private static void assertRoundTrip(byte[] prev, byte[] next, Map<Integer, ChunkState> delta) {
        Map<Integer, ChunkState> prevState = parseRegion(prev);
        Map<Integer, ChunkState> nextState = parseRegion(next);
        for (int i = 0; i < CHUNK_COUNT; i++) {
            // delta 里有的以 delta 为准，没有的沿用上一次的状态
            ChunkState expected = delta.containsKey(i) ? delta.get(i) : stateOf(prevState, i);
            assertEquals("区块 " + i + " 未能正确复原", expected, stateOf(nextState, i));
        }
    }

    private static ChunkState stateOf(Map<Integer, ChunkState> map, int index) {
        return map.getOrDefault(index, ChunkState.DELETED);
    }

    /**
     * 用给定的「上一次时间戳」把 .mca 转成 delta 字节
     */
    private static byte[] deltaOf(byte[] mcaBytes, long[] prevTimes) throws IOException {
        return deltaOf(writeTemp(mcaBytes), prevTimes);
    }

    private static byte[] deltaOf(File mcaFile, long[] prevTimes) throws IOException {
        McaDeltaInputStream in = new McaDeltaInputStream(mcaFile, prevTimes);
        try {
            return readAll(in, 8192);
        } finally {
            in.close();
        }
    }

    /**
     * 解析 delta
     */
    private static Map<Integer, ChunkState> parseDelta(byte[] delta) {
        assertTrue("不是 delta 格式", isDelta(delta));
        ByteBuffer buf = ByteBuffer.wrap(delta).order(ByteOrder.BIG_ENDIAN);
        buf.position(McaDeltaInputStream.MAGIC.length);
        assertEquals(McaDeltaInputStream.DELTA_FORMAT_VERSION, buf.getShort() & 0xFFFF);
        int count = buf.getShort() & 0xFFFF;
        Map<Integer, ChunkState> result = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int index = buf.getShort() & 0xFFFF;
            int sectors = buf.get() & 0xFF;
            readTimestamp(buf);
            if (sectors == 0) {
                result.put(index, ChunkState.DELETED);
            } else {
                byte[] data = new byte[sectors * SECTOR_SIZE];
                buf.get(data);
                result.put(index, new ChunkState(sectors, data));
            }
        }
        assertEquals("delta 尾部有多余数据", delta.length, buf.position());
        return result;
    }

    private static long readTimestamp(ByteBuffer bytes) {
        long timestamp = 0;
        int shift = 0;
        while (true) {
            int b = bytes.get() & 0xFF;
            timestamp |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                return timestamp;
            shift += 7;
        }
    }

    private static boolean isDelta(byte[] bytes) {
        if (bytes.length < McaDeltaInputStream.MAGIC.length)
            return false;
        for (int i = 0; i < McaDeltaInputStream.MAGIC.length; i++) {
            if (bytes[i] != McaDeltaInputStream.MAGIC[i])
                return false;
        }
        return true;
    }

    /**
     * 读出一个 .mca 里所有区块的状态
     */
    private static Map<Integer, ChunkState> parseRegion(byte[] file) {
        Map<Integer, ChunkState> result = new HashMap<>();
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int p = i * 4;
            int offset = ((file[p] & 0xFF) << 16) | ((file[p + 1] & 0xFF) << 8) | (file[p + 2] & 0xFF);
            int sectors = file[p + 3] & 0xFF;
            if (offset == 0 || sectors == 0)
                continue;
            byte[] data = Arrays.copyOfRange(file, offset * SECTOR_SIZE, (offset + sectors) * SECTOR_SIZE);
            result.put(i, new ChunkState(sectors, data));
        }
        return result;
    }

    private static long[] timesOf(byte[] file) {
        long[] times = new long[CHUNK_COUNT];
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int p = CHUNK_COUNT * 4 + i * 4;
            times[i] = ((long) (file[p] & 0xFF) << 24) | ((file[p + 1] & 0xFF) << 16)
                    | ((file[p + 2] & 0xFF) << 8) | (file[p + 3] & 0xFF);
        }
        return times;
    }

    private static byte[] readAll(InputStream in, int bufSize) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[bufSize];
        int n;
        while ((n = in.read(buf)) > 0)
            bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static byte[] chunkData(int seed, int sectors) {
        byte[] data = new byte[sectors * SECTOR_SIZE];
        new Random(seed).nextBytes(data);
        return data;
    }

    /**
     * 构造一个带合法 Region chunk Length 的扇区数据。
     *
     * @param usedBytes       从 chunk 起点算起的实际有效字节数，包含 4-byte Length 本身
     * @param compressionByte compression/version byte；例如 0x02 或 external stub 的 0x82
     */
    private static byte[] chunkDataWithLength(int seed, int sectors, int usedBytes, int compressionByte) {
        int allocatedBytes = sectors * SECTOR_SIZE;
        if (usedBytes < 5 || usedBytes > allocatedBytes)
            throw new IllegalArgumentException("usedBytes 必须位于 [5, sectors * 4096]");

        byte[] data = new byte[allocatedBytes];
        if (usedBytes > 5) {
            byte[] payload = new byte[usedBytes - 5];
            new Random(seed).nextBytes(payload);
            System.arraycopy(payload, 0, data, 5, payload.length);
        }
        int chunkLength = usedBytes - 4; // Length 包含 compression/version byte，不包含自身 4 字节
        data[0] = (byte) (chunkLength >>> 24);
        data[1] = (byte) (chunkLength >>> 16);
        data[2] = (byte) (chunkLength >>> 8);
        data[3] = (byte) chunkLength;
        data[4] = (byte) compressionByte;
        return data;
    }

    private static File writeTemp(byte[] bytes) throws IOException {
        File file = File.createTempFile("_test_region", ".mca");
        file.deleteOnExit();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static RegionBuilder region() {
        return new RegionBuilder();
    }

    /**
     * 造一个 .mca 文件，区块按加入顺序依次分配扇区（前两个扇区留给 8 KiB 头部）
     */
    private static final class RegionBuilder {
        private final int[] sectors = new int[CHUNK_COUNT];
        private final int[] times = new int[CHUNK_COUNT];
        private final byte[][] data = new byte[CHUNK_COUNT][];
        private final List<Integer> allocationOrder = new ArrayList<>();
        private int gapSectors = 0;

        /**
         * 放一个存在的区块
         *
         * @param timestamp 区块时间戳；`.mca` 头部只存低 32 位，超出范围的值会被截断
         */
        RegionBuilder chunk(int index, int sectorCount, long timestamp, byte[] chunkData) {
            if (chunkData.length != sectorCount * SECTOR_SIZE)
                throw new IllegalArgumentException("区块数据长度必须为 扇区数 * " + SECTOR_SIZE);
            sectors[index] = sectorCount;
            times[index] = (int) timestamp;
            data[index] = chunkData;
            allocationOrder.add(index);
            return this;
        }

        /**
         * 留一个"不存在但时间戳还在"的区块（用于模拟被删除的区块）
         */
        RegionBuilder deleted(int index, long timestamp) {
            times[index] = (int) timestamp;
            return this;
        }

        /**
         * 每个区块之间留出若干空闲扇区
         */
        RegionBuilder gap(int gapSectors) {
            this.gapSectors = gapSectors;
            return this;
        }

        /**
         * 按相反的顺序分配扇区，用于制造"文件内的扇区顺序 != 下标顺序"
         */
        RegionBuilder reverseAllocation() {
            Collections.reverse(allocationOrder);
            return this;
        }

        byte[] build() {
            long[] offsets = new long[CHUNK_COUNT];
            int nextSector = HEADER_SIZE / SECTOR_SIZE;
            for (int index : allocationOrder) {
                offsets[index] = nextSector;
                nextSector += sectors[index] + gapSectors;
            }
            byte[] file = new byte[nextSector * SECTOR_SIZE];
            for (int i = 0; i < CHUNK_COUNT; i++) {
                int t = CHUNK_COUNT * 4 + i * 4;
                file[t] = (byte) (times[i] >> 24);
                file[t + 1] = (byte) (times[i] >> 16);
                file[t + 2] = (byte) (times[i] >> 8);
                file[t + 3] = (byte) times[i];
                if (data[i] == null)
                    continue;
                int offset = (int) offsets[i];
                int p = i * 4;
                file[p] = (byte) (offset >> 16);
                file[p + 1] = (byte) (offset >> 8);
                file[p + 2] = (byte) offset;
                file[p + 3] = (byte) sectors[i];
                System.arraycopy(data[i], 0, file, offset * SECTOR_SIZE, data[i].length);
            }
            return file;
        }
    }

    /**
     * 一个区块的状态: 扇区数 + 数据（扇区数为 0 表示区块不存在）
     * <p>record 自带的 equals 对数组是引用比较，这里必须自己覆盖</p>
     */
    private record ChunkState(int sectors, byte[] data) {
        static final ChunkState DELETED = new ChunkState(0, null);

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof ChunkState other))
                return false;
            return sectors == other.sectors && Arrays.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return 31 * sectors + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return data == null ? "DELETED" : "sectors=" + sectors + ", bytes=" + data.length;
        }
    }
}
