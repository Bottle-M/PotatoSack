package indi.somebottle;

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

/**
 * 测试用的 MCAnvil 区域文件与 PSMCA delta 构造/解析工具
 *
 * <p>类名不匹配 surefire 的测试类命名规则（{@code Test*} / {@code *Test}），不会被当作测试类执行。</p>
 */
final class RegionFixtures {

    static final int CHUNK_COUNT = McaDeltaMerger.CHUNK_COUNT;
    static final int SECTOR_SIZE = McaDeltaMerger.SECTOR_SIZE;
    static final int HEADER_SIZE = McaDeltaMerger.HEADER_SIZE;

    private RegionFixtures() {
    }

    /**
     * 造一段区块数据（用固定种子，保证可重复）
     *
     * @param seed    随机种子
     * @param sectors 占用扇区数
     * @return 长度为 {@code sectors * 4096} 的数据
     */
    static byte[] chunkData(int seed, int sectors) {
        byte[] data = new byte[sectors * SECTOR_SIZE];
        new Random(seed).nextBytes(data);
        return data;
    }

    /**
     * 造一个 .mca 文件，区块按加入顺序依次分配扇区（前两个扇区留给 8 KiB 头部）
     */
    static RegionBuilder region() {
        return new RegionBuilder();
    }

    // ------------------------------------------------------------------ delta 构造

    /**
     * delta 里的一条记录
     *
     * @param index   区块下标
     * @param sectors 扇区数，0 表示删除
     * @param data    区块数据（删除时为 null）
     */
    static final class DeltaRecord {
        final int index;
        final int sectors;
        final byte[] data;

        private DeltaRecord(int index, int sectors, byte[] data) {
            this.index = index;
            this.sectors = sectors;
            this.data = data;
        }
    }

    /**
     * 造一条"删除区块"的 delta 记录
     */
    static DeltaRecord del(int index) {
        return new DeltaRecord(index, 0, null);
    }

    /**
     * 造一条"写入区块"的 delta 记录
     */
    static DeltaRecord chunk(int index, int sectors, byte[] data) {
        if (data.length != sectors * SECTOR_SIZE)
            throw new IllegalArgumentException("区块数据长度必须为 扇区数 * " + SECTOR_SIZE);
        return new DeltaRecord(index, sectors, data);
    }

    /**
     * 造一条"写入区块"的 delta 记录，扇区数由数据长度推导
     */
    static DeltaRecord chunk(int index, byte[] data) {
        return chunk(index, data.length / SECTOR_SIZE, data);
    }

    /**
     * 按给定顺序拼出一份 PSMCA delta 字节
     */
    static byte[] delta(DeltaRecord... records) {
        return delta(records.length, Arrays.asList(records));
    }

    /**
     * 按给定顺序拼出一份 PSMCA delta 字节，区块数量的声明值可以故意写错（用于损坏输入测试）
     *
     * @param declaredCount 写进头部的区块数量
     * @param records       实际拼进去的记录
     */
    static byte[] delta(int declaredCount, List<DeltaRecord> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(McaDeltaMerger.MAGIC, 0, McaDeltaMerger.MAGIC.length);
        out.write((declaredCount >>> 8) & 0xFF);
        out.write(declaredCount & 0xFF);
        for (DeltaRecord record : records) {
            out.write((record.index >>> 8) & 0xFF);
            out.write(record.index & 0xFF);
            out.write(record.sectors & 0xFF);
            if (record.sectors > 0)
                out.write(record.data, 0, record.data.length);
        }
        return out.toByteArray();
    }

    /**
     * 拼接字节数组（用于制造"尾部多余数据"等损坏输入）
     */
    static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 解析一个 .mca 文件里所有区块的状态；顺带检查每个区块的扇区范围都落在文件内
     *
     * @throws AssertionError 区域文件的扇区范围越界（说明被测代码产出了非法区域文件）
     */
    static Map<Integer, ChunkState> parseRegion(byte[] file) {
        if (file.length < HEADER_SIZE)
            throw new AssertionError("不是合法的区域文件: 只有 " + file.length + " 字节");
        Map<Integer, ChunkState> result = new HashMap<>();
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int p = i * 4;
            int offset = ((file[p] & 0xFF) << 16) | ((file[p + 1] & 0xFF) << 8) | (file[p + 2] & 0xFF);
            int sectors = file[p + 3] & 0xFF;
            if (offset == 0 || sectors == 0)
                continue;
            if (offset < HEADER_SIZE / SECTOR_SIZE || ((long) offset + sectors) * SECTOR_SIZE > file.length) {
                throw new AssertionError("区域文件中区块 " + i + " 的扇区范围越界: offset=" + offset
                        + ", sectors=" + sectors + ", 文件长度=" + file.length);
            }
            result.put(i, new ChunkState(sectors,
                    Arrays.copyOfRange(file, offset * SECTOR_SIZE, (offset + sectors) * SECTOR_SIZE)));
        }
        return result;
    }

    /**
     * 读取一个 .mca 文件所有区块状态
     */
    static Map<Integer, ChunkState> parseRegion(File file) throws IOException {
        return parseRegion(Files.readAllBytes(file.toPath()));
    }

    /**
     * 取出区块状态，不存在的区块视为"已删除"
     */
    static ChunkState stateOf(Map<Integer, ChunkState> map, int index) {
        return map.getOrDefault(index, ChunkState.DELETED);
    }

    /**
     * 读出一个 .mca 头部的 1024 个区块时间戳
     */
    static long[] timesOf(byte[] file) {
        long[] times = new long[CHUNK_COUNT];
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int p = CHUNK_COUNT * 4 + i * 4;
            times[i] = ((long) (file[p] & 0xFF) << 24) | ((file[p + 1] & 0xFF) << 16)
                    | ((file[p + 2] & 0xFF) << 8) | (file[p + 3] & 0xFF);
        }
        return times;
    }

    /**
     * 取出一份 .mca 的 4 KiB 时间戳表
     */
    static byte[] timestampTable(byte[] file) {
        return Arrays.copyOfRange(file, CHUNK_COUNT * 4, HEADER_SIZE);
    }

    /**
     * 判断字节是否以 PSMCA 魔数开头
     */
    static boolean isDelta(byte[] bytes) {
        if (bytes.length < McaDeltaMerger.MAGIC.length)
            return false;
        for (int i = 0; i < McaDeltaMerger.MAGIC.length; i++) {
            if (bytes[i] != McaDeltaMerger.MAGIC[i])
                return false;
        }
        return true;
    }

    /**
     * 写出一个临时文件
     */
    static File writeTemp(File dir, String name, byte[] bytes) throws IOException {
        File file = new File(dir, name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    /**
     * 读一个流到末尾
     */
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0)
            bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /**
     * 造 delta 用的 ByteBuffer（大端序）
     */
    static ByteBuffer bigEndian(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    }

    /**
     * 造一个 .mca 文件，区块按加入顺序依次分配扇区（前两个扇区留给 8 KiB 头部）
     */
    static final class RegionBuilder {
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
                if (data[i] == null)
                    continue;
                int offset = (int) offsets[i];
                int p = i * 4;
                file[p] = (byte) (offset >> 16);
                file[p + 1] = (byte) (offset >> 8);
                file[p + 2] = (byte) offset;
                file[p + 3] = (byte) sectors[i];
                int t = CHUNK_COUNT * 4 + i * 4;
                file[t] = (byte) (times[i] >> 24);
                file[t + 1] = (byte) (times[i] >> 16);
                file[t + 2] = (byte) (times[i] >> 8);
                file[t + 3] = (byte) times[i];
                System.arraycopy(data[i], 0, file, offset * SECTOR_SIZE, data[i].length);
            }
            return file;
        }
    }

    /**
     * 一个区块的状态: 扇区数 + 数据（扇区数为 0 表示区块不存在）
     * <p>record 自带的 equals 对数组是引用比较，这里必须自己覆盖</p>
     */
    static final class ChunkState {
        static final ChunkState DELETED = new ChunkState(0, null);

        private final int sectors;
        private final byte[] data;

        ChunkState(int sectors, byte[] data) {
            this.sectors = sectors;
            this.data = data;
        }

        int sectors() {
            return sectors;
        }

        byte[] data() {
            return data;
        }

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
