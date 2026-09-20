package indi.somebottle.potatosack.utils;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * McaDeltaInputStream 把一个 `.mca` 区域文件转换成增量备份专用的 delta 格式，后缀名仍为 .mca。
 *
 * <p>本类是给 {@link Utils#zipSpecificFilesUtil} 在打包时用的：增量备份里 `.mca` 只打包发生变动的区块，
 * 其他文件照常原样打包，不经过本类。</p>
 *
 * <p>输入是「当前 `.mca`」与「上一次备份记录中该文件的 1024 个区块最后变更时间戳」，输出为（所有数值均为大端序）:</p>
 *
 * <pre>
 * [ MAGIC (50 53 4D 43 41 00) ] [ uint16 区块数量 ] [
 *     [ uint16 区块下标 (0..1023) ] [ uint8 区块占用扇区数 ] [ 区块数据 (扇区数 * 4 KiB) ]
 *     ...
 * ]
 * </pre>
 *
 * <p>区块占用扇区数为 0 表示该区块<b>已被删除</b>，此时不存储区块数据。</p>
 *
 * <p><b>关于区块的排列顺序（解包方必须注意）:</b>
 * delta 中的区块是按<b>文件内的扇区偏移顺序</b>排列的。
 * 因为本类是顺着文件顺序一遍读完的，先遇到哪个区块就先写哪个，顺序读效率高
 * （如一个 .mca 里区块 0 在扇区 201、区块 1 在扇区 74、区块 2 在扇区 2）。
 * 每个区块自带 uint16 下标，所以顺序不影响正确性。
 * 被删除的区块没有扇区偏移，统一排在最前面（紧跟在 MAGIC 和区块数量之后）。
 *
 * <p><b>整文件一遍顺序读，不做随机访问:</b>
 * 本类还会给出整个原始文件的 {@link #sourceCRC32()}，供调用方沿用
 * {@link Utils#zipSpecificFilesUtil} 里会校验压缩前后文件 CRC32 是否发生变化，如果使用本类读取文件，读取的是处理后的数据，计算 CRC32 肯定不一致
 * （见 {@link Utils#fileCRC32(File)}），所以本类会提供原始文件的 CRC32。
 *
 * <p><b>退化（原样输出整个文件）:</b> 下列情况会放弃 delta、把文件原样吐出去，
 * 此时输出与输入逐字节相同，而 MAGIC 不会出现在输出开头:</p>
 * <ul>
 *     <li>没有任何区块的时间戳发生变动 —— 文件哈希变了却一个区块都没变，说明时间戳判据失效了，肯定还有别的地方发生变动，原样输出</li>
 *     <li>某个待打包区块的扇区区间越过文件末尾 —— 说明文件本身被截断，按偏移取数据会取到不完整内容</li>
 *     <li>某个待打包区块的扇区起始位置落在 8 KiB 头部之内 —— MCAnvil 文件头异常</li>
 *     <li>待打包区块的扇区区间互相重叠 —— 也算文件头异常</li>
 *     <li>文件不足 8 KiB，连头部都不完整</li>
 * </ul>
 *
 */
public class McaDeltaInputStream extends InputStream {

    /**
     * delta 格式的魔数 `PSMCA\0`，用于让解包方区分"增量 delta"和"原样存储的 .mca"
     */
    public static final byte[] MAGIC = {'P', 'S', 'M', 'C', 'A', 0};

    /**
     * `.mca` 区域文件中区块的数量，固定为 32 * 32
     */
    public static final int CHUNK_COUNT = DirFileRecord.MCA_CHUNK_COUNT;

    /**
     * `.mca` 中一个扇区的大小，目前 4 KiB
     */
    public static final int SECTOR_SIZE = 4096;

    /**
     * `.mca` 头部大小: 1024 个 [3 字节扇区偏移 + 1 字节扇区数] 表，加上 1024 个 4 字节时间戳表
     */
    public static final int HEADER_SIZE = CHUNK_COUNT * 4 * 2;

    /**
     * 时间戳表在头部中的起始偏移
     */
    private static final int TIMESTAMP_TABLE_OFFSET = CHUNK_COUNT * 4;

    /**
     * 顺序读取源文件时用的缓冲区大小
     */
    private static final int READ_BUF_SIZE = 65536;

    /**
     * 待打包的 `.mca` 文件
     */
    private final File mcaFile;

    /**
     * 上一次备份记录中该文件的 1024 个区块最后变更时间戳
     * <p>取值落在 `[0, 2^32)`: `.mca` 头部那个字段是 32 位无符号的秒级 epoch，
     * 用 long 存是为了让它在 2038 年之后（`>= 2^31`）仍然是有意义的正数</p>
     */
    private final long[] prevChunkTimes;

    /**
     * 源文件的顺序读取流，首次 {@link #read(byte[], int, int)} 时才打开
     */
    private InputStream fileIn;

    /**
     * 覆盖<b>整个源文件</b>的 CRC32
     */
    private final CRC32 crc = new CRC32();

    /**
     * 主体数据之前要输出的内容:
     * delta 模式是 `MAGIC + uint16 区块数量 + 各被删除区块的头部`，原 .mca 模式是整个 8 KiB 头部
     */
    private byte[] prologue = new byte[0];

    /**
     * {@link #prologue} 中已经吐出去的位置
     */
    private int prologuePos = 0;

    /**
     * 是否已经读过头部并做好决策
     */
    private boolean parsed = false;

    /**
     * 是否退化成了原样输出
     */
    private boolean rawMode = false;

    /**
     * 是否已经到达输入末尾
     */
    private boolean finished = false;

    /**
     * 顺序读缓冲区
     */
    private final byte[] readBuf = new byte[READ_BUF_SIZE];

    /**
     * {@link #readBuf} 中的有效字节数
     */
    private int readLen = 0;

    /**
     * {@link #readBuf} 中已经消费到的位置
     */
    private int readPos = 0;

    /**
     * {@link #readBuf}[0] 对应的源文件偏移
     */
    private long sourceFileOffset = 0;

    /**
     * 需要输出数据的区块区间，按源文件中的扇区偏移升序排列
     */
    private List<ChunkRange> chunks = List.of();

    /**
     * {@link #chunks} 中下一个尚未开始输出的区块
     */
    private int chunkPtr = 0;

    /**
     * 当前正在输出数据的区块，为 null 表示当前位置不在任何区块的数据区间内
     */
    private ChunkRange curChunk = null;

    /**
     * 待发出的区块头 (uint16 下标 + uint8 占用扇区数)，delta 文件中每个区块数据前都有个区块头
     */
    private final byte[] chunkHeader = new byte[3];

    /**
     * {@link #chunkHeader} 中已经输出的位置，等于 3 表示没有待发的区块头
     */
    private int chunkHeaderPos = 3;

    /**
     * {@link #read()} 单字节读取用的中转数组
     */
    private final byte[] singleByte = new byte[1];

    /**
     * 构造函数
     *
     * @param mcaFile        待打包的 `.mca` 文件
     * @param prevChunkTimes 上一次备份记录中该文件的区块时间戳，长度必须为 {@link #CHUNK_COUNT}
     * @throws NullPointerException     文件为 null
     * @throws IllegalArgumentException 时间戳数组为 null 或长度不对
     * @apiNote 构造函数不打开文件，也不读头部；这些都推迟到第一次 {@link #read(byte[], int, int)}。
     * <p>调用方只应在<b>确实有上次记录</b>时才构造本流，没有基线（新出现的 `.mca`）时应当直接原样打包。</p>
     */
    public McaDeltaInputStream(File mcaFile, long[] prevChunkTimes) {
        this.mcaFile = Objects.requireNonNull(mcaFile, "mcaFile must not be null");
        if (prevChunkTimes == null || prevChunkTimes.length != CHUNK_COUNT)
            throw new IllegalArgumentException("prevChunkTimes must be a long[" + CHUNK_COUNT + "]");
        this.prevChunkTimes = prevChunkTimes;
    }

    /**
     * 读取一个 `.mca` 文件头部里的 1024 个区块最后变更时间戳
     *
     * @param mcaFile `.mca` 文件
     * @return 长度为 {@link #CHUNK_COUNT} 的时间戳数组（32 位无符号秒级 epoch，用 long 承载）
     * @throws IOException 文件读不了，或文件不足 {@link #HEADER_SIZE} 字节
     * @apiNote 扫描阶段（生成备份记录文件用）调用本方法取得要写进记录的时间戳；
     * 打包阶段则由 {@link #McaDeltaInputStream(File, long[])} 自己再读一次头部。
     * <p><b>顺序不变式:</b> 记录里的时间戳取样时刻必须<b>不晚于</b>打包读取时刻。否则记录比打进 zip 的内容"新"，
     * 下次增量会把这些区块判为未变动而漏掉，属于静默丢数据；反过来的话最多是多备一次，是安全方向。</p>
     */
    public static long[] readChunkTimes(File mcaFile) throws IOException {
        try (InputStream in = new FileInputStream(mcaFile)) {
            byte[] header = new byte[HEADER_SIZE];
            if (readFully(in, header) < HEADER_SIZE)
                throw new IOException("Not a valid region file (shorter than " + HEADER_SIZE + " bytes): " + mcaFile);
            return parseChunkTimes(header);
        }
    }

    /**
     * 取出读取过程中算出的 CRC32
     *
     * @return 原始 `.mca` 文件的 CRC32
     * @apiNote 只有在流被读到末尾（{@link #read(byte[], int, int)} 返回 -1）之后，这个值才是完整的；
     * 它和 {@link Utils#fileCRC32(File)} 对同一个文件的结果一致。
     */
    public long sourceCRC32() {
        return crc.getValue();
    }

    @Override
    public int read(byte @NotNull [] b, int off, int len) throws IOException {
        if (len == 0)
            return 0;
        if (finished)
            return -1;
        if (fileIn == null)
            fileIn = new FileInputStream(mcaFile);
        if (!parsed)
            parseHeader();
        // 1. 先把 prologue 吐完
        if (prologuePos < prologue.length) {
            int n = Math.min(len, prologue.length - prologuePos);
            System.arraycopy(prologue, prologuePos, b, off, n);
            prologuePos += n;
            return n;
        }
        // 2. 再吐主体；这里可能要跨过若干"不输出"的区间，所以循环到真的产出字节为止
        int n = produce(b, off, len);
        if (n > 0)
            return n;
        finished = true;
        return -1;
    }

    @Override
    public int read() throws IOException {
        int n = read(singleByte, 0, 1);
        return n <= 0 ? -1 : (singleByte[0] & 0xFF);
    }

    @Override
    public void close() throws IOException {
        finished = true;
        if (fileIn != null)
            fileIn.close();
    }

    /**
     * 读头部、算出要打包哪些区块、并组装 {@link #prologue}；顺带把头部计入 CRC
     */
    private void parseHeader() throws IOException {
        parsed = true;
        byte[] header = new byte[HEADER_SIZE];
        int n = readFully(fileIn, header);
        crc.update(header, 0, n);
        sourceFileOffset = n;
        if (n < HEADER_SIZE) {
            // 连头部都不完整，原样把读到的东西吐出去
            rawMode = true;
            prologue = Arrays.copyOf(header, n);
            return;
        }

        int[] offsets = new int[CHUNK_COUNT];
        int[] sectors = new int[CHUNK_COUNT];
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int p = i * 4; // 每一个区块的 [区块存储位置偏移和占用扇区数] 占用 4 字节
            // 这个区块的扇区偏移 = 3 字节大端无符号整数
            offsets[i] = ((header[p] & 0xFF) << 16) | ((header[p + 1] & 0xFF) << 8) | (header[p + 2] & 0xFF);
            // 扇区数 = 1 字节无符号整数
            sectors[i] = header[p + 3] & 0xFF;
        }
        long[] times = parseChunkTimes(header);
        long fileLength = mcaFile.length();

        // 1. 按时间戳判据挑出发生变动的区块
        List<ChunkRange> changed = new ArrayList<>();
        List<Integer> deleted = new ArrayList<>();
        for (int i = 0; i < CHUNK_COUNT; i++) {
            if (prevChunkTimes[i] == times[i])
                continue; // 时间戳没变，判为未变动
            if (offsets[i] == 0 || sectors[i] == 0) {
                // 区块现在不存在（原版约定: 扇区偏移和扇区数都为 0 即该区块未创建），标记为"已删除"
                deleted.add(i);
                continue;
            }
            long start = (long) offsets[i] * SECTOR_SIZE;
            long end = start + (long) sectors[i] * SECTOR_SIZE;
            if (start < HEADER_SIZE || end > fileLength) {
                // 扇区区间落在头部里，或者越过文件末尾（文件被截断），按偏移取数据会取到损坏的数据
                fallbackToRaw(header);
                return;
            }
            changed.add(new ChunkRange(i, sectors[i], start, end));
        }
        int total = changed.size() + deleted.size();
        if (total == 0) {
            // 文件哈希变了，却一个区块的时间戳都没变 —— 暂时不知道哪里变了，老老实实整个文件原样打包
            fallbackToRaw(header);
            return;
        }

        // 2. 输出顺序是文件内的扇区顺序（顺序读一遍时先遇到谁就先写谁），所以按偏移排序
        changed.sort(Comparator.comparingLong(ChunkRange::start));
        for (int i = 1; i < changed.size(); i++) {
            if (changed.get(i).start() < changed.get(i - 1).end()) {
                // 扇区区间发生重叠，说明 mca 文件头有问题，退化到全文件备份
                fallbackToRaw(header);
                return;
            }
        }
        chunks = changed;

        // 3. 组装 delta mca 格式的 prologue: MAGIC + uint16 区块数量 + 各被删除区块的头部（它们没有扇区偏移可排序，统一放最前面）
        prologue = new byte[8 + deleted.size() * 3];
        System.arraycopy(MAGIC, 0, prologue, 0, MAGIC.length);
        prologue[6] = (byte) (total >>> 8); // uint16 (6 和 7 这 2 字节) 区块数量
        prologue[7] = (byte) total;
        int p = 8;
        for (int index : deleted) {
            prologue[p++] = (byte) (index >>> 8); // uint16 区块下标
            prologue[p++] = (byte) index;
            prologue[p++] = 0; // 扇区数为 0 = 该区块已删除
        }
    }

    /**
     * 放弃 delta，改成原样输出整个文件
     *
     * @param header 已经读进来的 8 KiB 头部，直接当作 prologue 吐出去
     */
    private void fallbackToRaw(byte[] header) {
        rawMode = true;
        chunks = List.of();
        curChunk = null;
        prologue = header;
    }

    /**
     * 把源文件顺序读窗口推进一格，读到的字节全部计入 CRC
     *
     * @return 是否读到了数据（false 表示到达文件末尾）
     */
    private boolean refill() throws IOException {
        int n = fileIn.read(readBuf);
        if (n <= 0)
            return false;
        crc.update(readBuf, 0, n);
        sourceFileOffset += readLen;
        readLen = n;
        readPos = 0;
        return true;
    }

    /**
     * 尽量往调用方的数组里产出字节
     *
     * @return 产出的字节数，0 表示输入已到末尾
     */
    private int produce(byte[] b, int off, int len) throws IOException {
        while (true) {
            // 1. 有待发的区块头就先发它（最多 3 字节，必然能塞下）
            if (chunkHeaderPos < 3) {
                int n = Math.min(len, 3 - chunkHeaderPos);
                System.arraycopy(chunkHeader, chunkHeaderPos, b, off, n);
                chunkHeaderPos += n;
                return n;
            }
            // 2. 窗口空了就补
            if (readPos >= readLen && !refill())
                return 0;
            // 3. 原样模式: 窗口里有什么就吐什么
            if (rawMode) {
                int n = Math.min(len, readLen - readPos);
                System.arraycopy(readBuf, readPos, b, off, n);
                readPos += n;
                return n;
            }
            // 4. delta 模式: 在本窗口内产出；可能整窗都是"不输出"的区间，那就回到第 2 步继续读
            int produced = produceDelta(b, off, len);
            if (produced > 0)
                return produced;
        }
    }

    /**
     * 在当前读窗口内产出 delta 主体数据
     *
     * @return 产出的字节数；返回 0 表示本窗口已被整段跳过（{@link #readPos} 已推进到窗口末尾）
     */
    private int produceDelta(byte[] b, int off, int len) {
        int p = readPos;
        int out = 0;
        while (p < readLen && out < len) {
            long absOff = sourceFileOffset + p;
            // 当前位置正好是某个待打包区块的起点 → 先把它的头部准备好
            if (chunkPtr < chunks.size() && chunks.get(chunkPtr).start() == absOff) {
                ChunkRange c = chunks.get(chunkPtr++);
                curChunk = c;
                chunkHeader[0] = (byte) (c.index() >>> 8);
                chunkHeader[1] = (byte) c.index();
                chunkHeader[2] = (byte) c.sectors();
                chunkHeaderPos = 0;
                readPos = p;
                // 已经攒了数据就先把数据交出去，区块头留给下一轮 produce()
                return out;
            }
            if (curChunk != null && absOff < curChunk.end()) {
                // 正在某个区块的数据区间内则拷贝区块数据
                int n = (int) Math.min(Math.min(len - out, readLen - p), curChunk.end() - absOff);
                System.arraycopy(readBuf, p, b, off + out, n);
                p += n;
                out += n;
                if (absOff + n >= curChunk.end())
                    curChunk = null;
            } else {
                // 不在任何待输出区间内（空闲扇区、未变动区块）则直接跳过，只计入 CRC
                curChunk = null;
                long nextStart = chunkPtr < chunks.size() ? chunks.get(chunkPtr).start() : Long.MAX_VALUE;
                long skipTo = Math.min(nextStart, sourceFileOffset + readLen);
                p += (int) (skipTo - absOff);
            }
        }
        readPos = p;
        return out;
    }

    /**
     * 从 8 KiB 头部里取出 1024 个区块最后变更时间戳
     *
     * @param header 至少 {@link #HEADER_SIZE} 字节的头部
     * @return 长度为 {@link #CHUNK_COUNT} 的时间戳数组
     * @implNote `.mca` 里这个字段是 <b>32 位无符号</b>的秒级 epoch，所以要先把字节提升成 long 再移位，
     * 否则 `>= 2^31` 的值（2038 年之后，或者被工具写坏的文件）会被当成负数
     */
    private static long[] parseChunkTimes(byte[] header) {
        long[] times = new long[CHUNK_COUNT];
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int p = TIMESTAMP_TABLE_OFFSET + i * 4;
            // 时间戳 = 4 字节大端无符号整数，提升成 long 再移位
            times[i] = ((long) (header[p] & 0xFF) << 24) | ((header[p + 1] & 0xFF) << 16)
                    | ((header[p + 2] & 0xFF) << 8) | (header[p + 3] & 0xFF);
        }
        return times;
    }

    /**
     * 把输入流读满指定数组，或读到末尾为止
     *
     * @return 实际读到的字节数
     */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0)
                break;
            total += n;
        }
        return total;
    }

    /**
     * 一个待打包区块在源文件中的扇区区间
     *
     * @param index   区块下标 (0..1023)
     * @param sectors 占用扇区数 (1..255)
     * @param start   数据起始偏移（含）
     * @param end     数据结束偏移（不含）
     */
    private record ChunkRange(int index, int sectors, long start, long end) {
    }
}
