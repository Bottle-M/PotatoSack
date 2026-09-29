import indi.somebottle.potatosack.utils.DirFileRecord;
import io.airlift.compress.zstd.ZstdInputStream;
import io.airlift.compress.zstd.ZstdOutputStream;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * DirFileRecord 记录文件读写往返测试，不依赖 Bukkit 服务端
 */
public class DirFileRecordTest {

    @Test
    public void testSaveAndLoadRoundTrip() throws Exception {
        File recordFile = File.createTempFile("_test_record", "");
        assertTrue(recordFile.delete());
        Random random = new Random(20240918L);
        DirFileRecord record = new DirFileRecord(recordFile);

        // 普通文件: 带符号位的 uint64 哈希 + 毫秒级最后修改时间
        long negativeHash = Long.MIN_VALUE + 12345; // 最高位为 1，检验 uint64 读写
        long lastModified = 1726600000123L;
        record.putEntry(new DirFileRecord.FileEntry("world/level.dat", lastModified, negativeHash, null));
        record.putEntry(new DirFileRecord.FileEntry("server.properties", 1726600000000L, 0L, null));
        // 一个超长路径，路径长度表的上界附近
        StringBuilder longPath = new StringBuilder("world/");
        for (int i = 0; i < 100; i++)
            longPath.append("sub").append(i).append('/');
        longPath.append("data.dat");
        record.putEntry(new DirFileRecord.FileEntry(longPath.toString(), 1726600000001L, -1L, null));
        // .mca 文件: 1024 个区块时间戳，含 0（稀疏）、小值和大值（检验 varint 边界）
        long[] chunkTimes = new long[DirFileRecord.MCA_CHUNK_COUNT];
        for (int i = 0; i < chunkTimes.length; i++) {
            if (i % 37 == 0)
                chunkTimes[i] = random.nextInt(Integer.MAX_VALUE); // 1..2^31-1，5 字节 varint
            else if (i % 11 == 0)
                chunkTimes[i] = 127; // 1 字节 varint 上界
            else if (i % 5 == 0)
                chunkTimes[i] = 128; // 需要 2 字节
            // 其余保持 0
        }
        // .mca 头部那个字段是 32 位无符号的秒级 epoch，2038 年之后会越过 2^31。
        // 放两个超出 int 范围的值，确认 64 位 varint 能原样往返（这是把时间戳改成 long 的原因）
        chunkTimes[1] = 3_000_000_000L;
        chunkTimes[2] = 4_294_967_295L; // 2^32 - 1，.mca 那个字段的取值上界
        record.putEntry(new DirFileRecord.FileEntry("world/region/r.0.0.mca", 1726600000999L, 987654321L, chunkTimes));
        // 路径中含非 ASCII，检验 UTF-8 编解码
        record.putEntry(new DirFileRecord.FileEntry("world/一些中文目录/说明.txt", 1726600000002L, 42L, null));

        record.save();

        // 文件应该存在且非空
        assertTrue(recordFile.exists());
        assertTrue(recordFile.length() > 0);
        long savedUpdateTime = record.getFileUpdateTime();
        assertTrue("save() 应自行刷新 fileUpdateTime", savedUpdateTime > 0);

        // 读回来
        DirFileRecord loaded = new DirFileRecord(recordFile);
        loaded.load();

        assertEquals(savedUpdateTime, loaded.getFileUpdateTime());
        assertEquals(record.getFileCount(), loaded.getFileCount());
        assertEquals(5, loaded.getFileCount());

        // 普通文件
        DirFileRecord.FileEntry levelDat = loaded.getEntry("world/level.dat");
        assertEquals(lastModified, levelDat.getLastModified());
        assertEquals(negativeHash, levelDat.getHash());
        assertNull("非 .mca 文件不应有区块时间戳", levelDat.getMcaChunkTimes());
        assertEquals(0L, loaded.getEntry("server.properties").getHash());
        assertEquals(longPath.toString(), loaded.getEntry(longPath.toString()).getRelativePath());
        assertEquals("world/一些中文目录/说明.txt", loaded.getEntry("world/一些中文目录/说明.txt").getRelativePath());
        assertEquals(42L, loaded.getEntry("world/一些中文目录/说明.txt").getHash());

        // .mca 文件
        DirFileRecord.FileEntry mca = loaded.getEntry("world/region/r.0.0.mca");
        assertArrayEquals(chunkTimes, mca.getMcaChunkTimes());
        assertEquals(987654321L, mca.getHash());

        // 内存中的 entries 应被覆写而不是叠加
        loaded.load();
        assertEquals(5, loaded.getFileCount());

        assertTrue(recordFile.delete());
    }

    @Test
    public void testRecordFileIsZstdCompressedAndCompact() throws Exception {
        // 记录文件整体是 zstd 帧，所以要先确认写出来的字节确实能当 zstd 解出正确的载荷；
        // 同时确认它比未压缩载荷小得多（下方各张表就是为压缩率设计的）
        File recordFile = File.createTempFile("_test_record_zstd", "");
        assertTrue(recordFile.delete());
        DirFileRecord record = new DirFileRecord(recordFile);
        // 区块时间戳全 0: 未压缩时 mca 部分是 1024 字节（varint 的意义），压缩后应当大幅缩小
        record.putEntry(new DirFileRecord.FileEntry("world/region/r.0.0.mca", 1L, 1L,
                new long[DirFileRecord.MCA_CHUNK_COUNT]));
        record.save();

        // 1. 解压出来应当就是原来的载荷: uint32 版本号打头
        try (java.io.InputStream in = new ZstdInputStream(new java.io.FileInputStream(recordFile))) {
            byte[] versionBytes = new byte[4];
            assertEquals(4, in.read(versionBytes));
            assertEquals(DirFileRecord.STORAGE_FORMAT_VERSION,
                    ((long) (versionBytes[0] & 0xFF) << 24) | ((versionBytes[1] & 0xFF) << 16)
                            | ((versionBytes[2] & 0xFF) << 8) | (versionBytes[3] & 0xFF));
        }

        // 2. 未压缩载荷的大小: 头部 4 + 8 + 8，三张表 uint64 + uint64 + uint16，
        //    路径 UTF-8 长度 21 字节，mca 部分 1024 * 1 字节
        String path = "world/region/r.0.0.mca";
        long rawSize = 20 + 8 + 8 + 2 + path.getBytes(StandardCharsets.UTF_8).length + DirFileRecord.MCA_CHUNK_COUNT;
        assertTrue("压缩后(" + recordFile.length() + ") 应当远小于未压缩载荷(" + rawSize + ")",
                recordFile.length() < rawSize / 4);
        assertTrue(recordFile.delete());
    }

    @Test
    public void testUnsupportedVersionThrows() throws Exception {
        // 版本号在压缩载荷内部，所以要构造一个"是合法 zstd 帧、但版本号不对"的文件
        File recordFile = File.createTempFile("_test_record_badver", "");
        assertTrue(recordFile.delete());
        try (java.io.OutputStream out = new ZstdOutputStream(new java.io.FileOutputStream(recordFile))) {
            out.write(new byte[]{0, 0, 0, 99}); // uint32 版本号 = 99
            out.write(new byte[64]); // 后面补点内容，凑成一个完整的帧
        }
        try {
            new DirFileRecord(recordFile).load();
            throw new AssertionError("版本号不受支持时应当抛出 IOException");
        } catch (java.io.IOException e) {
            assertTrue("实际消息: " + e.getMessage(),
                    e.getMessage().contains("Unsupported storage format version"));
        }
        assertTrue(recordFile.delete());
    }

    @Test(expected = java.io.IOException.class)
    public void testTruncatedFileThrows() throws Exception {
        File recordFile = File.createTempFile("_test_record_truncated", "");
        assertTrue(recordFile.delete());
        DirFileRecord record = new DirFileRecord(recordFile);
        record.putEntry(new DirFileRecord.FileEntry("world/level.dat", 1L, 2L, null));
        record.save();
        // 截断到只剩 zstd 魔法数的一部分，此时连是不是记录文件都判断不了，必须抛异常
        byte[] all = Files.readAllBytes(recordFile.toPath());
        assertTrue(all.length > 4);
        Files.write(recordFile.toPath(), Arrays.copyOf(all, 3));
        new DirFileRecord(recordFile).load();
    }

    @Test
    public void testSaveGoesThroughTempFileAndLeavesNoResidue() throws Exception {
        // save() 是"写临时文件 + 原子替换"，这里确认临时文件不会残留，
        // 且上一次写失败残留的临时文件会被下一次 save() 覆盖而不是被当成正式记录
        File recordFile = File.createTempFile("_test_record_atomic", "");
        assertTrue(recordFile.delete());
        File tempFile = recordFile.toPath().resolveSibling(recordFile.getName() + ".tmp").toFile();

        // 伪造一个上次失败残留的临时文件
        Files.write(tempFile.toPath(), new byte[]{1, 2, 3, 4});

        DirFileRecord record = new DirFileRecord(recordFile);
        record.putEntry(new DirFileRecord.FileEntry("world/level.dat", 1L, 2L, null));
        record.save();

        assertFalse("save() 成功后不应残留临时文件", tempFile.exists());
        DirFileRecord loaded = new DirFileRecord(recordFile);
        loaded.load();
        assertEquals(1, loaded.getFileCount());
        assertEquals(1L, loaded.getEntry("world/level.dat").getLastModified());

        // 覆盖写一份新记录: 旧条目要被整体替换掉，且同样不留临时文件
        DirFileRecord second = new DirFileRecord(recordFile);
        second.putEntry(new DirFileRecord.FileEntry("world/level.dat", 2L, 3L, null));
        second.putEntry(new DirFileRecord.FileEntry("world/region/r.0.0.mca", 4L, 5L,
                new long[DirFileRecord.MCA_CHUNK_COUNT]));
        second.save();

        assertFalse("覆盖写成功后不应残留临时文件", tempFile.exists());
        loaded = new DirFileRecord(recordFile);
        loaded.load();
        assertEquals(2, loaded.getFileCount());
        assertEquals(2L, loaded.getEntry("world/level.dat").getLastModified());

        assertTrue(recordFile.delete());
    }
}
