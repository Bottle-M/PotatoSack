import indi.somebottle.potatosack.utils.DirFileRecord;
import io.airlift.compress.zstd.ZstdInputStream;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 记录文件里 mca 数据部分那段 varint 的<b>规范字节</b>锁定测试
 *
 * <p>区块时间戳用的是无符号 LEB128。这个编码是整个记录格式的一部分，
 * 一旦有人改动它（哪怕是改成 zigzag 或者别的变长方案），已经写出去的记录文件就全读不出来了，
 * 所以这里用一组探针值把规范字节钉死。</p>
 *
 * <p>探针覆盖了各级 varint 边界（1/2/3/4/5 字节的分界）以及 32 位无符号范围的两端 ——
 * `.mca` 头部那个时间戳字段就是 32 位无符号的秒级 epoch。</p>
 */
public class VarintEncodingTest {

    private static final long[] PROBE_VALUES = {
            0L, 1L, 127L, 128L, 16383L, 16384L,
            (1L << 21) - 1, 1L << 21, (1L << 28) - 1, 1L << 28,
            Integer.MAX_VALUE, 1L << 31, (1L << 32) - 1,
            1722659687L // 一个真实的区块时间戳（2024-08-03）
    };

    /**
     * 上面各探针值的无符号 LEB128 编码首尾相接
     */
    private static final String EXPECTED_HEX =
            "00017f8001ff7f808001ffff7f80808001ffffff7f8080808001" // 0..2^28
                    + "ffffffff07"       // 2^31 - 1
                    + "8080808008"       // 2^31，越过 int 范围
                    + "ffffffff0f"       // 2^32 - 1，字段上界
                    + "e7e6b6b506";      // 1722659687

    @Test
    public void testMcaSectionVarintEncoding() throws Exception {
        long[] chunkTimes = new long[DirFileRecord.MCA_CHUNK_COUNT];
        System.arraycopy(PROBE_VALUES, 0, chunkTimes, 0, PROBE_VALUES.length);

        String path = "world/region/r.0.0.mca";
        File recordFile = File.createTempFile("_test_varint_format", "");
        recordFile.deleteOnExit();
        DirFileRecord record = new DirFileRecord(recordFile);
        record.putEntry(new DirFileRecord.FileEntry(path, 1L, 1L, chunkTimes));
        record.save();

        byte[] payload = decompress(recordFile);
        // 载荷布局: uint32 版本号 + uint64 记录时间戳 + uint64 文件数量
        //          + uint64 文件最后修改时间表 + uint64 文件哈希表 + uint16 路径长度表 + 路径
        int mcaOffset = 4 + 8 + 8 + 8 + 8 + 2 + path.getBytes(StandardCharsets.UTF_8).length;
        byte[] mcaSection = new byte[payload.length - mcaOffset];
        System.arraycopy(payload, mcaOffset, mcaSection, 0, mcaSection.length);

        byte[] expected = HexFormat.of().parseHex(EXPECTED_HEX);
        byte[] actualHead = new byte[expected.length];
        System.arraycopy(mcaSection, 0, actualHead, 0, expected.length);
        assertArrayEquals("varint 编码变了，已写出的记录文件将读不出来", expected, actualHead);

        // 剩下的区块时间戳都是 0，各自只占 1 字节
        assertEquals("mca 数据部分长度不对", expected.length + (DirFileRecord.MCA_CHUNK_COUNT - PROBE_VALUES.length),
                mcaSection.length);
        for (int i = expected.length; i < mcaSection.length; i++)
            assertEquals("0 必须编码成单个 0x00 字节", 0, mcaSection[i]);

        // 反向: 自己写的要能自己读回来（含超过 int 范围的两个值）
        DirFileRecord loaded = new DirFileRecord(recordFile);
        loaded.load();
        assertArrayEquals(chunkTimes, loaded.getEntry(path).getMcaChunkTimes());
        assertTrue(recordFile.delete());
    }

    private static byte[] decompress(File recordFile) throws Exception {
        try (InputStream in = new ZstdInputStream(new FileInputStream(recordFile))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
