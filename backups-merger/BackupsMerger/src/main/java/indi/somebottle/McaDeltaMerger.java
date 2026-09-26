package indi.somebottle;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 PotatoSack 3.0.0 增量备份中的 {@code PSMCA\0} delta `.mca` 应用回完整的 MCAnvil 区域文件。
 *
 * <p>delta 格式（所有数值均为大端序，定义见 PotatoSack 3.0.0 设计 1.2 节）:</p>
 *
 * <pre>
 * 6 bytes  MAGIC = 50 53 4d 43 41 00   （ASCII: PSMCA\0）
 * uint16   version
 * uint16   count                            （0..1024）
 * 重复 count 次：
 *   uint16 chunkIndex                       （0..1023）
 *   uint8  sectorCount                      （0 表示删除；否则 1..255）
 *   unsigned LEB128 timestamp               （varint；当前限制为原 .mca 头部中的 uint32）
 *   byte[] payload                          （sectorCount * 4096，包含填充）
 * </pre>
 *
 * <p>本类只负责“一个 delta + 一个基线区域文件 = 一个新的区域文件”，不判断输入到底是 delta 还是
 * 原样存储的 `.mca`：调用方先用 {@link #hasMagic(File)} 分流，只有确认是 delta 才调用 {@link #apply(File, File, File)}。</p>
 *
 * <p><b>重建策略</b>: 读取基线 `.mca` 的 8 KiB 头部，把 delta 里的区块覆盖到基线状态上，
 * 然后按区块下标 {@code 0..1023} 从扇区 2 开始紧凑重排扇区，最后整文件原子替换。
 * 之所以不原地覆盖，是因为同一个区块在新旧版本中占用的扇区数可能不同（可大可小）。</p>
 *
 * <p><b>时间戳</b>: delta .mca 文件中每个区块的时间戳以 unsigned LEB128（varint）存储，
 * 不是固定长度字段。由于原始 `.mca` 头部的时间戳目前只有 4 字节，当前实现暂时限制为
 * {@code uint32}，因此最多读取 5 字节；恢复时再写回 `.mca` 的 4 字节时间戳表。</p>
 *
 * @apiNote 本类不去读取 PotatoSack 的目录记录文件（`_*.bin`）；恢复某个增量截止点只需要基线文件本身。
 */
public final class McaDeltaMerger {

    /**
     * delta 格式的魔数 {@code PSMCA\0}
     */
    public static final byte[] MAGIC = {'P', 'S', 'M', 'C', 'A', 0};

    /**
     * 目前的 MCA delta 格式版本
     */
    public static final int DELTA_FORMAT_VERSION = 1;

    /**
     * 一个区域文件里的区块数量: 32 * 32
     */
    public static final int CHUNK_COUNT = 1024;

    /**
     * 一个扇区的大小: 4 KiB
     */
    public static final int SECTOR_SIZE = 4096;

    /**
     * 区域文件头部大小: 1024 个 [3 字节扇区偏移 + 1 字节扇区数] 位置表，加上 1024 个 4 字节时间戳表
     */
    public static final int HEADER_SIZE = CHUNK_COUNT * 4 * 2;

    /**
     * 位置表中扇区偏移字段是 3 字节大端无符号整数，能表示的最大值
     */
    public static final int MAX_SECTOR_OFFSET = 0xFFFFFF;

    /**
     * 位置表（前 4 KiB）里每个区块占用的字节数
     */
    private static final int LOCATION_ENTRY_SIZE = 4;

    /**
     * 复制扇区数据时用的缓冲区大小
     */
    private static final int COPY_BUF_SIZE = 65536;

    private McaDeltaMerger() {
    }

    /**
     * 判断一个文件是不是 {@code PSMCA\0} 开头的 delta 文件
     *
     * @param file 待检查的文件
     * @return 文件存在且以 {@link #MAGIC} 开头则为 true；文件不存在或长度不足 6 字节为 false
     * @throws IOException 读取文件失败
     */
    public static boolean hasMagic(File file) throws IOException {
        if (file == null || !file.isFile())
            return false;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] head = new byte[MAGIC.length];
            int total = 0;
            while (total < head.length) {
                int n = in.read(head, total, head.length - total);
                if (n < 0)
                    break;
                total += n;
            }
            return total == MAGIC.length && Arrays.equals(head, MAGIC);
        }
    }

    /**
     * 把 delta 应用到基线区域文件，结果写入 outputFile
     *
     * @param baseFile   基线 `.mca` 文件（必须是完整区域文件，本方法不会修改它）
     * @param deltaFile  {@code PSMCA\0} delta 文件
     * @param outputFile 输出 `.mca` 路径（先写同目录临时文件，再原子替换到该路径）
     * @throws IOException 基线损坏、delta 损坏、扇区偏移溢出或读写失败
     * @apiNote 任何失败都不会改动 {@code baseFile}，也不会留下写了一半的 {@code outputFile}。
     */
    public static void apply(File baseFile, File deltaFile, File outputFile) throws IOException {
        Path outputPath = outputFile.getAbsoluteFile().toPath();
        Path parent = outputPath.getParent();
        if (parent == null)
            throw new IOException("Cannot determine the parent directory of output file " + outputPath);
        Files.createDirectories(parent);

        // delta payload 落盘用的临时文件: 一个 delta 的 payload 理论上可以到 1 GiB 左右，不整份读进堆
        Path payloadTmp = Files.createTempFile(parent, ".psdelta-", ".tmp");
        // 输出先写临时文件，全部写完再原子替换
        Path mergedTmp = Files.createTempFile(parent, ".psmerged-", ".tmp");
        try {
            BaseRegion base = readBaseRegion(baseFile);
            Map<Integer, DeltaChunk> delta = readDelta(deltaFile, payloadTmp);
            writeMergedRegion(baseFile, base, delta, payloadTmp, mergedTmp);
            moveAtomically(mergedTmp, outputPath);
        } finally {
            deleteQuietly(payloadTmp);
            deleteQuietly(mergedTmp);
        }
    }

    /**
     * 解析基线区域文件的头部，并检查每个存在区块的扇区范围
     *
     * @param baseFile 基线文件
     * @return 基线头部和各区块的扇区范围
     * @throws IOException 基线不存在、短于 8 KiB，或扇区范围越界/重叠
     */
    static BaseRegion readBaseRegion(File baseFile) throws IOException {
        if (baseFile == null || !baseFile.isFile())
            throw new IOException("Invalid base region file: " + baseFile + " does not exist or is not a regular file");
        long fileLength = baseFile.length();
        if (fileLength < HEADER_SIZE) {
            throw new IOException("Invalid base region file " + baseFile + ": only " + fileLength
                    + " bytes, shorter than the " + HEADER_SIZE + "-byte region header");
        }
        byte[] header = new byte[HEADER_SIZE];
        try (InputStream in = new BufferedInputStream(new FileInputStream(baseFile))) {
            int n = readFully(in, header);
            if (n < HEADER_SIZE)
                throw new IOException("Invalid base region file " + baseFile + ": failed to read the full "
                        + HEADER_SIZE + "-byte region header (got " + n + " bytes)");
        }

        int[] offsets = new int[CHUNK_COUNT];
        int[] sectors = new int[CHUNK_COUNT];
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int p = i * LOCATION_ENTRY_SIZE;
            offsets[i] = readUnsigned24(header, p);
            sectors[i] = header[p + 3] & 0xFF;
        }
        long[] storedLengths = validateBaseRanges(baseFile, offsets, sectors, fileLength);
        return new BaseRegion(header, offsets, sectors, storedLengths);
    }

    /**
     * 校验基线里各区块的扇区范围
     *
     * @param baseFile   基线文件（只用于错误信息）
     * @param offsets    各区块扇区偏移
     * @param sectors    各区块扇区数
     * @param fileLength 基线文件长度
     * @return 每个存在区块在基线文件中实际物理存在的字节数。正常情况下等于 {@code sectorCount * 4096}；
     * 文件末尾区块数据的扇区填充可能没有实际写入硬盘，这种情况下要返回从区块起点到 EOF 的实际长度。
     * @throws IOException 偏移落在头部内、有效区块数据被 EOF 截断、区块长度非法，或不同区块的数据范围重叠
     * @implNote 「存在」的判据与生产端 {@code McaDeltaInputStream} 一致:
     * 扇区偏移或扇区数任一为 0 都算该区块不存在（原版约定）。
     * <p>Region 文件头里的 sectorCount 描述的是分配空间，不代表物理文件末尾一定已经写满 padding。
     * 当最后一个区块的已分配 sector 空间越过 EOF 时（也就是缺失填充字节），本方法会读取区块开头的 4-byte 长度字段；只要
     * {@code 4 + Length} 仍完整落在文件内，并且没有超过已分配 sector，即认为区块数据尚且没问题。</p>
     */
    private static long[] validateBaseRanges(File baseFile, int[] offsets, int[] sectors, long fileLength)
            throws IOException {
        long[] storedLengths = new long[CHUNK_COUNT];
        // 按偏移排序后检查区间是否重叠
        int[] sorted = new int[CHUNK_COUNT];
        int present = 0;
        try (FileChannel channel = FileChannel.open(baseFile.toPath(), StandardOpenOption.READ)) {
            for (int i = 0; i < CHUNK_COUNT; i++) {
                // 跳过不存在的区块
                if (offsets[i] == 0 || sectors[i] == 0)
                    continue;
                // 校验区域文件头部大小
                if (offsets[i] < HEADER_SIZE / SECTOR_SIZE) {
                    throw new IOException("Invalid base region file " + baseFile + ": chunk #" + i + " starts at sector "
                            + offsets[i] + ", which is inside the " + (HEADER_SIZE / SECTOR_SIZE) + "-sector header");
                }

                long start = (long) offsets[i] * SECTOR_SIZE;
                long allocatedLength = (long) sectors[i] * SECTOR_SIZE;
                long end = start + allocatedLength;
                if (end <= fileLength) {
                    storedLengths[i] = allocatedLength;
                } else {
                    // 最后一个区块数据末尾的填充可能没有写入硬盘，
                    // 这种情况用区块自身的 4-byte 长度判断区块有效数据是否完整
                    if (start + 4 > fileLength) {
                        throw new IOException("Invalid base region file " + baseFile + ": chunk #" + i
                                + " is past the end of the " + fileLength
                                + "-byte file before its 4-byte length field is complete");
                    }
                    long chunkLength = readUnsigned32(channel, start, baseFile, i);
                    // 区块头部的“长度”字段是从第 5 个字节，即 compression type 这个字节开始算的
                    // 因此至少有 1 个字节
                    if (chunkLength < 1) {
                        throw new IOException("Invalid base region file " + baseFile + ": chunk #" + i
                                + " has invalid length " + chunkLength);
                    }
                    // 计算区块实际占用的字节数
                    long usedLength = 4L + chunkLength;
                    if (usedLength > allocatedLength) {
                        throw new IOException("Invalid base region file " + baseFile + ": chunk #" + i
                                + " declares " + usedLength + " bytes including its length field, exceeding its "
                                + allocatedLength + "-byte sector allocation");
                    }
                    // 区块数据不完整
                    if (start + usedLength > fileLength) {
                        throw new IOException("Invalid base region file " + baseFile + ": chunk #" + i
                                + " payload is past the end of the " + fileLength + "-byte file (needs through byte "
                                + (start + usedLength) + ")");
                    }
                    // 有效数据完整，只是 EOF 截掉了后续 padding
                    // 这里按分配的扇区字节数记，合并输出时会补回 0
                    storedLengths[i] = fileLength - start;
                }
                sorted[present++] = i;
            }
        }
        // 按 offset 升序插入排序（1024 个元素，插入排序够用了）
        for (int i = 1; i < present; i++) {
            int key = sorted[i];
            int j = i - 1;
            while (j >= 0 && offsets[sorted[j]] > offsets[key]) {
                sorted[j + 1] = sorted[j];
                j--;
            }
            sorted[j + 1] = key;
        }
        // 排序后检查区块数据是否有交叠
        for (int i = 1; i < present; i++) {
            int prev = sorted[i - 1];
            int curr = sorted[i];
            if (offsets[curr] < offsets[prev] + sectors[prev]) {
                throw new IOException("Invalid base region file " + baseFile + ": chunk #" + prev + " (sectors ["
                        + offsets[prev] + ", " + (offsets[prev] + sectors[prev]) + ")) overlaps chunk #" + curr
                        + " (sectors [" + offsets[curr] + ", " + (offsets[curr] + sectors[curr]) + "))");
            }
        }
        return storedLengths;
    }

    /**
     * 解析 delta 文件，payload 顺序写入临时文件
     *
     * @param deltaFile  delta 文件
     * @param payloadTmp payload 落盘用的临时文件
     * @return 区块下标到 delta 区块的映射
     * @throws IOException 魔数不对、区块数越界、区块下标越界、区块重复、payload 截断或文件尾部有多余数据
     */
    static Map<Integer, DeltaChunk> readDelta(File deltaFile, Path payloadTmp) throws IOException {
        if (deltaFile == null || !deltaFile.isFile())
            throw new IOException("Invalid delta file: " + deltaFile + " does not exist or is not a regular file");
        long fileLength = deltaFile.length();
        if (fileLength < MAGIC.length + 4) {
            throw new IOException("Invalid delta file " + deltaFile + ": only " + fileLength
                    + " bytes, too short to contain the PSMCA header");
        }
        Map<Integer, DeltaChunk> chunks = new HashMap<>();
        try (CountingInput in = new CountingInput(new BufferedInputStream(new FileInputStream(deltaFile)));
             OutputStream payloadOut = Files.newOutputStream(payloadTmp, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] magic = new byte[MAGIC.length];
            requireFully(in.readFully(magic), magic.length, deltaFile,
                    "truncated PSMCA magic at offset 0");
            if (!Arrays.equals(magic, MAGIC))
                throw new IOException("Invalid delta file " + deltaFile + ": it does not start with the PSMCA magic");

            byte[] countBuf = new byte[2];
            requireFully(in.readFully(countBuf), countBuf.length, deltaFile, "truncated version");
            int version = readUnsigned16(countBuf, 0);
            if (version != DELTA_FORMAT_VERSION) {
                throw new IOException("Unsupported PSMCA delta format version: 0x"
                        + Integer.toHexString(version));
            }
            requireFully(in.readFully(countBuf), countBuf.length, deltaFile, "truncated chunk count");
            int count = readUnsigned16(countBuf, 0);
            if (count > CHUNK_COUNT) {
                throw new IOException("Invalid delta file " + deltaFile + ": chunk count " + count
                        + " exceeds the maximum of " + CHUNK_COUNT);
            }

            byte[] recHeader = new byte[3];
            byte[] copyBuf = new byte[COPY_BUF_SIZE];
            long payloadOffset = 0;
            for (int rec = 0; rec < count; rec++) {
                if (in.readFully(recHeader) < recHeader.length) {
                    throw new IOException("Invalid delta file " + deltaFile + ": truncated header of delta record "
                            + rec + " (expected " + count + " records)");
                }
                int index = readUnsigned16(recHeader, 0);
                int sectorCount = recHeader[2] & 0xFF;
                if (index >= CHUNK_COUNT) {
                    throw new IOException("Invalid delta file " + deltaFile + ": chunk index " + index
                            + " in delta record " + rec + " is out of range (0.." + (CHUNK_COUNT - 1) + ")");
                }
                if (chunks.containsKey(index)) {
                    throw new IOException("Invalid delta file " + deltaFile + ": chunk index " + index
                            + " appears more than once (delta record " + rec + ")");
                }
                long timestamp = readTimestamp(in, deltaFile);
                if (sectorCount == 0) {
                    // 扇区数为 0 = 该区块已被删除，后面没有 payload
                    chunks.put(index, DeltaChunk.deleted(timestamp));
                    continue;
                }
                long payloadLength = (long) sectorCount * SECTOR_SIZE;
                long copied = 0;
                while (copied < payloadLength) {
                    int want = (int) Math.min(copyBuf.length, payloadLength - copied);
                    int n = in.readFully(copyBuf, want);
                    if (n <= 0) {
                        throw new IOException("Invalid delta file " + deltaFile + ": payload of chunk #" + index
                                + " (delta record " + rec + ") is truncated, expected " + payloadLength
                                + " bytes but got " + copied);
                    }
                    payloadOut.write(copyBuf, 0, n);
                    copied += n;
                }
                chunks.put(index, DeltaChunk.present(sectorCount, payloadOffset, timestamp));
                payloadOffset += payloadLength;
            }
            if (in.getCount() != fileLength) {
                throw new IOException("Invalid delta file " + deltaFile + ": " + (fileLength - in.getCount())
                        + " trailing byte(s) after all " + count + " delta records");
            }
        }
        return chunks;
    }

    /**
     * 读取 delta 记录中的 unsigned LEB128（varint）时间戳。
     *
     * <p>当前时间戳最终要写回 `.mca` 头部的 4 字节字段，因此暂时只接受 {@code uint32}，
     * 即最多 5 字节的 LEB128 编码。</p>
     *
     * @param in        delta 输入流
     * @param deltaFile 用于错误信息的 delta 文件
     * @return 解码后的无符号时间戳
     * @throws IOException 流提前结束、varint 超过 5 字节或数值超出 {@code uint32} 范围
     */
    private static long readTimestamp(CountingInput in, File deltaFile) throws IOException {
        long value = 0;
        byte[] one = new byte[1];
        for (int i = 0; i < 5; i++) {
            requireFully(in.readFully(one), 1, deltaFile, "truncated chunk timestamp");
            int b = one[0] & 0xFF;
            if (i == 4 && (b & 0xF0) != 0)
                throw new IOException("Invalid uint32 chunk timestamp in " + deltaFile);
            value |= (long) (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0)
                return value;
        }
        throw new IOException("Chunk timestamp varint is too long in " + deltaFile);
    }

    /**
     * 按合并后的状态重排扇区并写出新的区域文件
     *
     * @param baseFile   基线文件
     * @param base       基线头部和区块范围
     * @param delta      delta 区块映射
     * @param payloadTmp delta payload 临时文件
     * @param mergedTmp  输出临时文件
     * @throws IOException 输出扇区偏移超过 24 位上限，或读写失败
     * @implNote 位置表按区块下标升序从扇区 2 开始紧凑重排；delta 覆写变动区块的时间戳。
     */
    private static void writeMergedRegion(File baseFile, BaseRegion base, Map<Integer, DeltaChunk> delta,
                                          Path payloadTmp, Path mergedTmp) throws IOException {
        // 1. 定出每个区块的最终扇区数（0 = 该区块不存在）
        int[] finalSectors = new int[CHUNK_COUNT];
        for (int i = 0; i < CHUNK_COUNT; i++) {
            DeltaChunk chunk = delta.get(i);
            if (chunk == null) {
                // delta 里没有记录 -> 沿用基线状态
                finalSectors[i] = (base.offsets[i] == 0 || base.sectors[i] == 0) ? 0 : base.sectors[i];
            } else {
                finalSectors[i] = chunk.sectorCount;
            }
        }
        // 2. 重新分配扇区偏移
        int[] newOffsets = allocateSectorOffsets(finalSectors, MAX_SECTOR_OFFSET);
        // 3. 组装新头部: 位置表全部重写，先复制基线时间戳，再覆写新 delta 指定的表项
        byte[] newHeader = buildMergedHeader(base.header, newOffsets, finalSectors);
        for (Map.Entry<Integer, DeltaChunk> record : delta.entrySet()) {
            long timestamp = record.getValue().timestamp;
            int p = CHUNK_COUNT * LOCATION_ENTRY_SIZE + record.getKey() * LOCATION_ENTRY_SIZE;
            newHeader[p] = (byte) (timestamp >>> 24);
            newHeader[p + 1] = (byte) (timestamp >>> 16);
            newHeader[p + 2] = (byte) (timestamp >>> 8);
            newHeader[p + 3] = (byte) timestamp;
        }

        // 4. 先写头部，再按区块下标升序复制数据
        try (FileChannel out = FileChannel.open(mergedTmp, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             FileChannel baseChannel = FileChannel.open(baseFile.toPath(), StandardOpenOption.READ);
             FileChannel payloadChannel = FileChannel.open(payloadTmp, StandardOpenOption.READ)) {
            writeFully(out, ByteBuffer.wrap(newHeader));
            for (int i = 0; i < CHUNK_COUNT; i++) {
                if (finalSectors[i] == 0)
                    continue;
                long length = (long) finalSectors[i] * SECTOR_SIZE;
                DeltaChunk chunk = delta.get(i);
                if (chunk == null) {
                    // 这个区块没有发生变化，沿用 baseline 的
                    // 原 .mca 文件末尾可能有有效 chunk 数据而没有完整的扇区填充
                    // 这种时候要先复制实际存在的部分，再补 0，保证新 .mca 文件大小可被 4096 整除
                    long storedLength = base.storedLengths[i];
                    copyRange(baseChannel, (long) base.offsets[i] * SECTOR_SIZE, storedLength, out, baseFile, i);
                    writeZeroes(out, length - storedLength);
                } else {
                    copyRange(payloadChannel, chunk.payloadOffset, length, out, null, i);
                }
            }
        }
    }

    /**
     * 按区块下标升序，从扇区 2 开始为存在区块连续分配扇区偏移
     *
     * @param sectors         每个区块的扇区数，0 表示该区块不存在
     * @param maxSectorOffset 允许的最大扇区偏移（位置表里只有 3 字节，正常为 {@link #MAX_SECTOR_OFFSET}）
     * @return 每个区块的扇区偏移，不存在的区块为 0
     * @throws IOException 需要的扇区超过 {@code maxSectorOffset} 能表示的范围
     */
    static int[] allocateSectorOffsets(int[] sectors, long maxSectorOffset) throws IOException {
        int[] offsets = new int[sectors.length];
        long next = HEADER_SIZE / SECTOR_SIZE; // 头部占扇区 0、1
        for (int i = 0; i < sectors.length; i++) {
            if (sectors[i] <= 0)
                continue;
            long last = next + sectors[i] - 1;
            if (last > maxSectorOffset) {
                throw new IOException("Merged region is too large: chunk #" + i + " would end at sector " + last
                        + ", beyond the maximum representable sector offset " + maxSectorOffset);
            }
            offsets[i] = (int) next;
            next = last + 1;
        }
        return offsets;
    }

    /**
     * 组装合并后的 8 KiB 头部
     *
     * @param baseHeader 基线头部
     * @param offsets    新的扇区偏移
     * @param sectors    新的扇区数
     * @return 新的头部字节
     * @implNote 这里先复制基线的时间戳表；调用者随后用 delta 中的时间戳覆写变动的区块
     */
    static byte[] buildMergedHeader(byte[] baseHeader, int[] offsets, int[] sectors) {
        byte[] header = new byte[HEADER_SIZE];
        System.arraycopy(baseHeader, CHUNK_COUNT * LOCATION_ENTRY_SIZE, header,
                CHUNK_COUNT * LOCATION_ENTRY_SIZE, CHUNK_COUNT * LOCATION_ENTRY_SIZE);
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int p = i * LOCATION_ENTRY_SIZE;
            int sectorCount = sectors[i];
            if (sectorCount <= 0)
                continue; // 位置表的这 4 字节保持全 0 = 该区块不存在
            int offset = offsets[i];
            header[p] = (byte) (offset >>> 16);
            header[p + 1] = (byte) (offset >>> 8);
            header[p + 2] = (byte) offset;
            header[p + 3] = (byte) sectorCount;
        }
        return header;
    }

    /**
     * 从输入通道复制固定长度的数据到输出通道
     *
     * @param src     源通道
     * @param srcPos  源起始偏移
     * @param length  要复制的字节数
     * @param out     输出通道（当前位置即写入位置）
     * @param srcFile 源文件（仅用于错误信息，可为 null）
     * @param index   区块下标（仅用于错误信息）
     * @throws IOException 源数据不足
     */
    private static void copyRange(FileChannel src, long srcPos, long length, FileChannel out, File srcFile, int index)
            throws IOException {
        long pos = srcPos;
        long remaining = length;
        while (remaining > 0) {
            // transferTo 的 position 是"源文件里的偏移"，数据写入 out 的当前位置
            long n = src.transferTo(pos, remaining, out);
            if (n <= 0) {
                throw new IOException("Unexpected end of data while copying chunk #" + index
                        + (srcFile == null ? " from the delta payload" : " from base region file " + srcFile)
                        + ": " + remaining + " byte(s) left of " + length);
            }
            pos += n;
            remaining -= n;
        }
    }

    /**
     * 向输出写入指定数量的 0 字节，用于补齐基线文件末尾未物理落盘的 sector padding
     *
     * @param out    输出 FileChannel
     * @param length 要填充的 0 字节数
     * @throws IOException 写入出现问题
     */
    private static void writeZeroes(FileChannel out, long length) throws IOException {
        if (length <= 0)
            return;
        ByteBuffer zeroes = ByteBuffer.allocate((int) Math.min(COPY_BUF_SIZE, length));
        long remaining = length;
        while (remaining > 0) {
            zeroes.clear();
            int writeLength = (int) Math.min(zeroes.capacity(), remaining);
            zeroes.limit(writeLength);
            writeFully(out, zeroes);
            remaining -= writeLength;
        }
    }

    /**
     * 把数组完整写入通道
     */
    private static void writeFully(FileChannel out, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining())
            out.write(buf);
    }

    /**
     * 原子替换（同文件系统优先 ATOMIC_MOVE，不支持时退化为普通替换）
     *
     * @param src 源路径
     * @param dst 目标路径
     * @throws IOException 移动失败
     */
    static void moveAtomically(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 读满整个数组
     *
     * @return 实际读到的字节数，到达末尾时小于数组长度
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
     * 校验某个定长字段确实读满了
     */
    private static void requireFully(int read, int expected, File file, String what) throws IOException {
        if (read < expected)
            throw new IOException("Invalid delta file " + file + ": " + what);
    }

    /**
     * 从字节数组读取 16 位大端无符号整数
     */
    private static int readUnsigned16(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
    }

    /**
     * 从字节数组读取 24 位大端无符号整数
     */
    private static int readUnsigned24(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 16) | ((buf[off + 1] & 0xFF) << 8) | (buf[off + 2] & 0xFF);
    }

    /**
     * 从文件指定位置读取 4 字节大端无符号整数
     */
    private static long readUnsigned32(FileChannel channel, long pos, File file, int index) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4);
        long readPos = pos;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, readPos);
            if (n < 0)
                break;
            if (n == 0)
                throw new IOException("Failed to read chunk #" + index + " length from base region file " + file);
            readPos += n;
        }
        if (buf.hasRemaining()) {
            throw new IOException("Invalid base region file " + file + ": chunk #" + index
                    + " has a truncated 4-byte length field");
        }
        buf.flip();
        return Integer.toUnsignedLong(buf.getInt());
    }

    /**
     * 删除文件，忽略失败（临时文件清理用）
     */
    private static void deleteQuietly(Path path) {
        if (path == null)
            return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 临时目录最终会被整体清理，这里不打断主流程
        }
    }

    /**
     * baseline 区域文件的头部和区块范围
     *
     * @param header        8 KiB 头部
     * @param offsets       各区块的扇区偏移
     * @param sectors       各区块的扇区数
     * @param storedLengths 各区块在 baseline 文件中实际存在的字节数；通常只有物理上最后一个区块数据没有填充到扇区大小时会有小于分配扇区字节数的情况
     */
    static final class BaseRegion {
        final byte[] header;
        final int[] offsets;
        final int[] sectors;
        final long[] storedLengths;

        BaseRegion(byte[] header, int[] offsets, int[] sectors, long[] storedLengths) {
            this.header = header;
            this.offsets = offsets;
            this.sectors = sectors;
            this.storedLengths = storedLengths;
        }
    }

    /**
     * delta 里的一个区块
     *
     * @param sectorCount   扇区数，0 表示删除；复制 payload 时的长度由它推出（{@code sectorCount * 4096}）
     * @param payloadOffset payload 在临时文件中的偏移
     * @param timestamp     新时间戳
     */
    static final class DeltaChunk {
        final int sectorCount;
        final long payloadOffset;
        final long timestamp;

        private DeltaChunk(int sectorCount, long payloadOffset, long timestamp) {
            this.sectorCount = sectorCount;
            this.payloadOffset = payloadOffset;
            this.timestamp = timestamp;
        }

        static DeltaChunk deleted(long timestamp) {
            return new DeltaChunk(0, 0, timestamp);
        }

        static DeltaChunk present(int sectorCount, long payloadOffset, long timestamp) {
            return new DeltaChunk(sectorCount, payloadOffset, timestamp);
        }
    }

    /**
     * 会累计已读字节数的输入流，用于检查 delta 尾部有没有多余数据
     */
    private static final class CountingInput implements Closeable {
        private final InputStream in;
        private long count;

        CountingInput(InputStream in) {
            this.in = in;
        }

        /**
         * 尽量读满指定长度
         *
         * @return 实际读到的字节数
         */
        int readFully(byte[] buf, int length) throws IOException {
            int total = 0;
            while (total < length) {
                int n = in.read(buf, total, length - total);
                if (n < 0)
                    break;
                total += n;
            }
            count += total;
            return total;
        }

        /**
         * 读满整个数组
         *
         * @return 实际读到的字节数
         */
        int readFully(byte[] buf) throws IOException {
            return readFully(buf, buf.length);
        }

        long getCount() {
            return count;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
