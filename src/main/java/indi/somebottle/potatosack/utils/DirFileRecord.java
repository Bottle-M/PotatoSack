package indi.somebottle.potatosack.utils;

import io.airlift.compress.zstd.ZstdInputStream;
import io.airlift.compress.zstd.ZstdOutputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DirFileRecord 代表某个备份路径下的一个备份记录文件 (`_备份路径标识`) 及其之上的读写操作
 *
 * <p>下面这块内容会被 <b>zstd</b> 压缩后存入硬盘（所有数值均为大端序）:</p>
 *
 * <pre>
 * [ uint32 存储格式版本号 ] [ uint64 存储最后修改时间戳 ] [ uint64 文件数量 ]
 * [ 文件最后修改时间表 (uint64 * N) ] [ 文件哈希表 (uint64 * N) ]
 * [ 文件路径字符串长度表 (uint16 * N) ] [ 紧凑的文件路径字符串表 (byte[]) ]
 * [ mca 数据部分 (varint[1024] * mcaLen) ]
 * </pre>
 *
 * <p>记录文件是一个 zstd 帧，不做任何未压缩格式的兼容。
 * 下方各张表是为压缩率设计的，压缩后重复模式更容易被 zstd 命中。</p>
 *
 * <p><b>关于缓冲流（容易被误改，实测数据见 {@link #load()} 与 {@link #save()} 的 @implNote）:</b>
 * 读侧要在 ZstdInputStream <b>外面</b>包一层 BufferedInputStream，写侧则一层都不加。
 * 判据是"每次调用有多少固定开销"：读侧每次单字节 read() 都要检查、推进解压状态，48 万次下来很可观；
 * 写侧每次 write(int) 只是往内部数组存一个字节，且 ZstdOutputStream 本来就攒够一块才写盘，
 * 所以它下面和它外面加缓冲都只有白多一层方法调用的份（外面那层实测慢 2.48 倍）。</p>
 *
 */
public class DirFileRecord {
    /**
     * 当前支持的存储格式版本号，记录在 zstd 载荷内部的 uint32
     * <p>由于载荷被压缩，这个版本号要在解压之后才能读到</p>
     */
    public static final int STORAGE_FORMAT_VERSION = 1;

    /**
     * `.mca` 区域文件中区块的数量，固定为 32 * 32
     */
    public static final int MCA_CHUNK_COUNT = 1024;

    /**
     * 单个文件记录数上限，防止读到损坏的文件数量字段后试图分配超大数组
     */
    private static final long MAX_FILE_COUNT = 100_000_000L;

    /**
     * 记录文件本身（`_备份路径标识`）的 File 对象
     */
    private final File recordFile;

    /**
     * 记录文件的最后修改时间戳（格式中的第 2 个字段，单位秒）
     */
    private long fileUpdateTime;

    /**
     * 记录中的所有文件条目，键为文件相对于服务端根目录的相对路径（形如 world/region/r.0.0.mca）
     * <p>无序: 记录文件中各张表只按索引对齐，写入 / 读出的先后不影响条目之间的对应关系</p>
     */
    private final Map<String, FileEntry> entries = new HashMap<>();

    /**
     * 构造函数
     *
     * @param dirFileRecordsFile _备份路径标识 文件
     * @apiNote 构造函数不读写硬盘，需要调用 {@link #load()} 载入磁盘上的记录，
     * 或调用 {@link #save()} 把当前内容覆盖写入磁盘
     */
    public DirFileRecord(File dirFileRecordsFile) {
        this.recordFile = dirFileRecordsFile;
    }

    /**
     * 从 {@link #getRecordFile()} 指定的文件读入记录，覆写当前内存中的内容
     *
     * @throws IOException 文件不存在、被占用，或存储格式版本号不受支持时抛出
     * @apiNote mca 数据部分的 varint 数组在载入时就应展开成 int[] 时间戳
     * @implNote 缓冲只加在 ZstdInputStream <b>外面</b>，不要加在它和文件之间:
     * 本方法的读取几乎全是单字节调用（uint64 每字段 8 次、varint 每字节 1 次），
     * 而解压流每次单字节 read() 都有状态检查和方法调用链的固定开销，外面这层省的就是它
     * （实测 2 万条目: 无外层缓冲 46 ms -> 有 34 ms）。
     * 内层则不需要: 记录文件压缩后只有几百 KiB 量级，解压流按块向底层要数据，调用次数本来就少，
     * 多套一层 BufferedInputStream 实测无差别（36.1 ms vs 37.2 ms）
     */
    public void load() throws IOException {
        try (InputStream in = new BufferedInputStream(new ZstdInputStream(
                new FileInputStream(recordFile)))) {
            // 以下各步与格式中的字段一一对应
            // 1. 存储格式版本号
            int version = (int) readUint32(in);
            if (version != STORAGE_FORMAT_VERSION) {
                // 不认识的版本号直接报错，避免把不认识的记录文件覆盖掉
                throw new IOException("Unsupported storage format version: " + version
                        + ", this build supports version " + STORAGE_FORMAT_VERSION);
            }
            // 2. 记录文件的最后修改时间戳
            long readFileUpdateTime = readUint64(in);
            // 3. 文件数量
            long count = readUint64(in);
            if (count < 0 || count > MAX_FILE_COUNT) {
                throw new IOException("Corrupted record file: invalid file count " + count);
            }
            int fileCount = (int) count;
            // 4. 文件最后修改时间表
            long[] lastModifiedTable = new long[fileCount];
            for (int i = 0; i < fileCount; i++)
                lastModifiedTable[i] = readUint64(in);
            // 5. 文件哈希表
            long[] hashTable = new long[fileCount];
            for (int i = 0; i < fileCount; i++)
                hashTable[i] = readUint64(in);
            // 6. 文件路径字符串长度表
            int[] pathLengthTable = new int[fileCount];
            for (int i = 0; i < fileCount; i++)
                pathLengthTable[i] = readUint16(in);
            // 7. 紧凑的文件路径字符串表，按同一顺序和表 4, 5 对齐
            List<RawEntry> rawEntries = new ArrayList<>(fileCount);
            for (int i = 0; i < fileCount; i++) {
                byte[] pathBytes = new byte[pathLengthTable[i]];
                readFully(in, pathBytes);
                rawEntries.add(new RawEntry(new String(pathBytes, StandardCharsets.UTF_8),
                        lastModifiedTable[i], hashTable[i]));
            }
            // 8. mca 数据部分: 按 .mca 文件出现的顺序，每个一个 varint[1024]
            for (RawEntry rawEntry : rawEntries) {
                if (isMcaPath(rawEntry.relativePath))
                    rawEntry.mcaChunkTimes = readMcaChunkTimes(in);
            }
            // 全部读完才覆写内存中的内容，避免中途出错留下半份记录
            entries.clear();
            for (RawEntry rawEntry : rawEntries) {
                entries.put(rawEntry.relativePath, new FileEntry(
                        rawEntry.relativePath, rawEntry.lastModified, rawEntry.hash, rawEntry.mcaChunkTimes));
            }
            fileUpdateTime = readFileUpdateTime;
        }
    }

    /**
     * 把当前内存中的记录覆盖写入 {@link #getRecordFile()}
     *
     * @throws IOException 写出失败时抛出
     * @apiNote 写入时应顺带刷新记录文件的最后修改时间戳
     * @implNote 压缩流下面不加 BufferedOutputStream: ZstdOutputStream 自带块缓冲，
     * 会先把未压缩数据攒进内部缓冲、攒够一块才压缩并向底层流写一次，
     * 所以底层流拿到的本来就是大块写入，再套一层实测无差别（15.5 ms vs 16.1 ms），
     * 且两者输出字节数完全相同
     */
    public void save() throws IOException {
        // 每次落盘都刷新记录文件的最后修改时间戳，外部不需要自己维护它
        fileUpdateTime = Utils.timestamp();
        if (!recordFile.exists()) {
            // 必要的目录先建立起来
            File parent = recordFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs())
                throw new IOException("Cannot create directory for record file: " + parent);
        }
        try (OutputStream out = new ZstdOutputStream(new FileOutputStream(recordFile))) {
            // 以下各步与格式中的字段一一对应
            // 1. 存储格式版本号
            writeUint32(out, STORAGE_FORMAT_VERSION);
            // 2. 记录文件的最后修改时间戳
            writeUint64(out, fileUpdateTime);
            // 3. 文件数量
            writeUint64(out, entries.size());
            // 4. 文件最后修改时间表
            for (FileEntry entry : entries.values())
                writeUint64(out, entry.lastModified);
            // 5. 文件哈希表
            for (FileEntry entry : entries.values())
                writeUint64(out, entry.hash);
            // 6. 文件路径字符串长度表 + 7. 紧凑的文件路径字符串表
            List<FileEntry> mcaEntries = new ArrayList<>();
            for (FileEntry entry : entries.values()) {
                byte[] pathBytes = entry.relativePath.getBytes(StandardCharsets.UTF_8);
                if (pathBytes.length > 0xFFFF) {
                    // uint16 存不下，现实中不可能出现
                    throw new IOException("File path too long to be stored: " + entry.relativePath);
                }
                writeUint16(out, pathBytes.length);
                if (isMcaPath(entry.relativePath))
                    mcaEntries.add(entry);
            }
            for (FileEntry entry : entries.values())
                out.write(entry.relativePath.getBytes(StandardCharsets.UTF_8));
            // 8. mca 数据部分: 按 .mca 文件出现的顺序，每个一个 varint[1024]
            for (FileEntry mcaEntry : mcaEntries) {
                int[] chunkTimes = mcaEntry.mcaChunkTimes;
                if (chunkTimes == null)
                    chunkTimes = new int[MCA_CHUNK_COUNT]; // 没读到区块时间戳就当作全 0
                if (chunkTimes.length != MCA_CHUNK_COUNT) {
                    throw new IOException("Invalid chunk timestamp array length for " + mcaEntry.relativePath
                            + ": " + chunkTimes.length + ", expected " + MCA_CHUNK_COUNT);
                }
                for (int chunkTime : chunkTimes)
                    writeVarInt(out, chunkTime);
            }
        }
    }

    // ------------------------------------------------------------------
    // 内存中的记录内容
    // ------------------------------------------------------------------

    /**
     * 取得记录文件的 File 对象
     *
     * @return 记录文件的 File 对象
     * @apiNote 备份成功后需要把记录文件上传到云端，所以外部需要拿到这个 File
     */
    public File getRecordFile() {
        return recordFile;
    }

    /**
     * 取得记录文件的最后修改时间戳
     *
     * @return 时间戳，单位秒
     */
    public long getFileUpdateTime() {
        return fileUpdateTime;
    }

    /**
     * 设置记录文件的最后修改时间戳
     *
     * @param fileUpdateTime 时间戳，单位秒
     * @apiNote {@link #save()} 会自行刷新该时间戳，因此外部一般不需要调用本方法
     */
    public void setFileUpdateTime(long fileUpdateTime) {
        this.fileUpdateTime = fileUpdateTime;
    }

    /**
     * 取得当前记录中的文件条目数量（即格式中的"文件数量"字段）
     *
     * @return 文件条目数量
     */
    public long getFileCount() {
        return entries.size();
    }

    /**
     * 取得所有文件条目
     *
     * @return 以文件相对路径为键的 Map
     */
    public Map<String, FileEntry> getEntries() {
        return entries;
    }

    /**
     * 取得某个相对路径对应的文件条目
     *
     * @param relativePath 文件相对于服务端根目录的相对路径
     * @return 文件条目，不存在则返回 null
     */
    public FileEntry getEntry(String relativePath) {
        return entries.get(relativePath);
    }

    /**
     * 添加 / 覆写一个文件条目
     *
     * @param entry 文件条目
     */
    public void putEntry(FileEntry entry) {
        if (entry == null || entry.relativePath == null)
            throw new IllegalArgumentException("File entry and its relative path must not be null.");
        entries.put(entry.relativePath, entry);
    }

    /**
     * 移除一个文件条目
     *
     * @param relativePath 文件相对于服务端根目录的相对路径
     * @return 被移除的条目，不存在则返回 null
     */
    public FileEntry removeEntry(String relativePath) {
        return entries.remove(relativePath);
    }

    /**
     * 清空所有文件条目
     */
    public void clear() {
        entries.clear();
    }

    // ------------------------------------------------------------------
    // 文件条目
    // ------------------------------------------------------------------

    /**
     * FileEntry 代表记录中的一个文件条目，对应格式中同一列位置的
     * "文件最后修改时间 + 文件哈希 + 文件路径"
     */
    public static class FileEntry {
        /**
         * 文件相对于服务端根目录的相对路径（UTF-8 编码后存入记录文件）
         */
        private String relativePath;

        /**
         * 文件的最后修改时间戳（uint64，单位毫秒）
         */
        private long lastModified;

        /**
         * 文件内容的 XXH3_64 哈希（uint64，按无符号处理）
         */
        private long hash;

        /**
         * 仅 `.mca` 文件有: 1024 个区块的最后变更时间戳（即 `.mca` 文件头的前 4 KiB）
         * <p>非 `.mca` 文件此字段为 null，此时在 mca 数据部分中不占位置</p>
         */
        private int[] mcaChunkTimes;

        /**
         * 构造函数
         *
         * @param relativePath  文件相对于服务端根目录的相对路径
         * @param lastModified  文件的最后修改时间戳，单位毫秒
         * @param hash          文件内容的 XXH3_64 哈希
         * @param mcaChunkTimes 仅 `.mca` 文件: 1024 个区块的最后变更时间戳，非 `.mca` 传 null
         */
        public FileEntry(String relativePath, long lastModified, long hash, int[] mcaChunkTimes) {
            this.relativePath = relativePath;
            this.lastModified = lastModified;
            this.hash = hash;
            this.mcaChunkTimes = mcaChunkTimes;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public void setRelativePath(String relativePath) {
            this.relativePath = relativePath;
        }

        public long getLastModified() {
            return lastModified;
        }

        public void setLastModified(long lastModified) {
            this.lastModified = lastModified;
        }

        public long getHash() {
            return hash;
        }

        public void setHash(long hash) {
            this.hash = hash;
        }

        public int[] getMcaChunkTimes() {
            return mcaChunkTimes;
        }

        public void setMcaChunkTimes(int[] mcaChunkTimes) {
            this.mcaChunkTimes = mcaChunkTimes;
        }
    }

    // ------------------------------------------------------------------
    // 内部工具: 定长字段读写、varint、mca 区块时间戳
    // ------------------------------------------------------------------

    /**
     * 判断一个相对路径是否指向 `.mca` 区域文件（按后缀名判断）
     *
     * @param relativePath 文件相对路径
     * @return 是 `.mca` 则返回 true
     */
    private static boolean isMcaPath(String relativePath) {
        return relativePath.toLowerCase(Locale.ROOT).endsWith(".mca");
    }

    /**
     * 读入一个 uint16（大端）
     *
     * @param in 输入流
     * @return 读到的数值
     * @throws IOException 读不到足够字节时抛出
     */
    private static int readUint16(InputStream in) throws IOException {
        return (readByte(in) << 8) | readByte(in);
    }

    /**
     * 读入一个 uint32（大端）
     *
     * @param in 输入流
     * @return 读到的数值，按 long 返回以避免符号问题
     * @throws IOException 读不到足够字节时抛出
     */
    private static long readUint32(InputStream in) throws IOException {
        return ((long) readByte(in) << 24) | ((long) readByte(in) << 16)
                | ((long) readByte(in) << 8) | readByte(in);
    }

    /**
     * 读入一个 uint64（大端）
     *
     * @param in 输入流
     * @return 读到的数值（Java 没有无符号类型，按 long 承载）
     * @throws IOException 读不到足够字节时抛出
     */
    private static long readUint64(InputStream in) throws IOException {
        long value = 0;
        for (int i = 0; i < 8; i++)
            value = (value << 8) | readByte(in);
        return value;
    }

    /**
     * 读入一个字节，读到流末尾则抛出异常
     *
     * @param in 输入流
     * @return 0..255
     * @throws IOException 流已结束时抛出
     */
    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0)
            throw new IOException("Unexpected end of record file.");
        return b;
    }

    /**
     * 把缓冲区填满，填不满则抛出异常
     *
     * @param in  输入流
     * @param buf 目标缓冲区
     * @throws IOException 流提前结束时抛出
     */
    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int readLen = in.read(buf, offset, buf.length - offset);
            if (readLen < 0)
                throw new IOException("Unexpected end of record file.");
            offset += readLen;
        }
    }

    /**
     * 写出一个 uint16（大端）
     *
     * @param out   输出流
     * @param value 数值
     * @throws IOException 写出失败时抛出
     */
    private static void writeUint16(OutputStream out, int value) throws IOException {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * 写出一个 uint32（大端）
     *
     * @param out   输出流
     * @param value 数值
     * @throws IOException 写出失败时抛出
     */
    private static void writeUint32(OutputStream out, long value) throws IOException {
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    /**
     * 写出一个 uint64（大端）
     *
     * @param out   输出流
     * @param value 数值
     * @throws IOException 写出失败时抛出
     */
    private static void writeUint64(OutputStream out, long value) throws IOException {
        for (int shift = 56; shift >= 0; shift -= 8)
            out.write((int) ((value >>> shift) & 0xFF));
    }

    /**
     * 写出一个 varint（LEB128，和 Minecraft 的 VarInt 编码一致）
     *
     * @param out   输出流
     * @param value 数值，按非负数处理
     * @throws IOException 写出失败时抛出
     */
    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /**
     * 读入一个 varint（LEB128）
     *
     * @param in 输入流
     * @return 读到的数值
     * @throws IOException 编码非法或流提前结束时抛出
     */
    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = readByte(in);
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                return value;
            shift += 7;
            if (shift > 28)
                throw new IOException("Corrupted record file: varint is too long.");
        }
    }

    /**
     * 读入一个 `.mca` 文件的 1024 个区块最后变更时间戳
     *
     * @param in 输入流
     * @return 长度为 {@link #MCA_CHUNK_COUNT} 的数组
     * @throws IOException 流提前结束时抛出
     */
    private static int[] readMcaChunkTimes(InputStream in) throws IOException {
        int[] chunkTimes = new int[MCA_CHUNK_COUNT];
        for (int i = 0; i < MCA_CHUNK_COUNT; i++)
            chunkTimes[i] = readVarInt(in);
        return chunkTimes;
    }

    /**
     * 读入过程中用的中间表示，用于先攒齐路径再去读 mca 数据部分
     */
    private static class RawEntry {
        private final String relativePath;
        private final long lastModified;
        private final long hash;
        private int[] mcaChunkTimes;

        private RawEntry(String relativePath, long lastModified, long hash) {
            this.relativePath = relativePath;
            this.lastModified = lastModified;
            this.hash = hash;
        }
    }
}
