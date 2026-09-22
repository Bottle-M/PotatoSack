package indi.somebottle;


import java.io.*;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 一些工具方法
 */
public class Utils {
    /**
     * 将整个文件按字符串读入
     *
     * @param file File 对象
     * @return 字符串
     * @throws IOException IO 异常
     */
    public static String readFile(File file) throws IOException {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] bytes = new byte[bis.available()];
            if (bis.read(bytes) == -1) {
                return "";
            }
            return new String(bytes);
        }
    }

    /**
     * 将 zip 解压到特定目录。zip 内的文件会覆盖现有的同名文件
     *
     * @param zipFile   Zip 文件 File 对象
     * @param targetDir 解压后存放的目录 File 对象
     * @return 是否解压成功
     * @apiNote 这里是给<b>全量备份</b>用的: `.mca` 条目必须是原样存储的完整区域文件；
     * 如果发现 PotatoSack 3.0.0 的 {@code PSMCA\0} 增量 delta 条目会直接失败，
     * 因为 delta 必须配合基线区域文件才能还原（增量包请走 {@link #mergeIncrementalZip(File, File)}）。
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean unzip(File zipFile, File targetDir) {
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zipIn.getNextEntry();
            while (zipEntry != null) {
                // 取得一个压缩条目
                String fileName = zipEntry.getName();
                File currFile;
                try {
                    currFile = resolveZipEntryTarget(targetDir, fileName);
                } catch (IOException e) {
                    System.out.println("Refusing zip entry: " + e.getMessage());
                    return false;
                }
                if (isDirectoryEntry(zipEntry)) {
                    // 如果是目录，且目录不存在，就尝试建立目录
                    if (!currFile.exists() && !currFile.mkdirs()) {
                        System.out.println("Failed to create directory " + currFile.getAbsolutePath());
                        return false;
                    }
                } else {
                    System.out.println("\tExtracting: " + fileName);
                    File tmpFile = null;
                    try {
                        tmpFile = spoolEntry(zipIn, currFile);
                        if (isMcaPath(fileName) && McaDeltaMerger.hasMagic(tmpFile)) {
                            // 全量备份里的 .mca 必须原样存储，不能是增量 delta
                            System.out.println("Full backup contains an incremental (PSMCA) region entry: "
                                    + fileName);
                            return false;
                        }
                        // 先完整写进临时文件，再原子替换目标文件，失败不会留下半截文件
                        moveAtomically(tmpFile, currFile);
                        tmpFile = null;
                    } catch (IOException e) {
                        System.out.println("Failed to extract " + fileName + ": " + e.getMessage());
                        return false;
                    } finally {
                        deleteQuietly(tmpFile);
                    }
                }
                // 关闭当前的条目
                zipIn.closeEntry();
                // 取出下一个条目
                zipEntry = zipIn.getNextEntry();
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * 把一份增量备份合并到已经恢复出来的目录中
     *
     * <p>条目分三类处理:</p>
     * <ul>
     *     <li>普通文件（含 `.mcc`、`deleted.files`）: 原样覆盖目标文件；</li>
     *     <li>原样存储的 `.mca`（旧版增量包，或生产端退化输出的情况）: 原样覆盖目标文件；</li>
     *     <li>{@code PSMCA\0} 开头的 `.mca` delta: 读取目标位置上已有的完整区域文件作为基线，
     *     应用 delta 后重建出完整的 Anvil 区域文件再覆盖（见 {@link McaDeltaMerger#apply(File, File, File)}）。</li>
     * </ul>
     *
     * @param zipFile   增量备份 Zip 文件
     * @param targetDir 已经解压好全量备份（以及更早的增量）的目录
     * @return 是否合并成功
     */
    public static boolean mergeIncrementalZip(File zipFile, File targetDir) {
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zipIn.getNextEntry();
            while (zipEntry != null) {
                String fileName = zipEntry.getName();
                File currFile;
                try {
                    currFile = resolveZipEntryTarget(targetDir, fileName);
                } catch (IOException e) {
                    System.out.println("Refusing zip entry: " + e.getMessage());
                    return false;
                }
                if (isDirectoryEntry(zipEntry)) {
                    if (!currFile.exists() && !currFile.mkdirs()) {
                        System.out.println("Failed to create directory " + currFile.getAbsolutePath());
                        return false;
                    }
                } else {
                    System.out.println("\tMerging: " + fileName);
                    File tmpFile = null;
                    File mergedFile = null;
                    try {
                        // 先落盘，才能判断条目到底是 delta 还是原样存储的 .mca
                        // ZipInputStream.read(byte[], int, int) 是当前 ZIP 条目数据读完时返回 -1，不是整个 ZIP 流的 EOF
                        tmpFile = spoolEntry(zipIn, currFile);
                        if (isMcaPath(fileName) && McaDeltaMerger.hasMagic(tmpFile)) {
                            // delta 必须应用到一个已经存在的完整区域文件上
                            if (!currFile.isFile()) {
                                System.out.println("Incremental region entry " + fileName
                                        + " is a PSMCA delta, but there is no baseline region file at "
                                        + currFile.getAbsolutePath());
                                return false;
                            }
                            if (McaDeltaMerger.hasMagic(currFile)) {
                                System.out.println("Baseline region file " + currFile.getAbsolutePath()
                                        + " is itself a PSMCA delta, cannot apply " + fileName);
                                return false;
                            }
                            mergedFile = createSiblingTempFile(currFile);
                            McaDeltaMerger.apply(currFile, tmpFile, mergedFile);
                            moveAtomically(mergedFile, currFile);
                            mergedFile = null;
                        } else {
                            moveAtomically(tmpFile, currFile);
                            tmpFile = null;
                        }
                    } catch (IOException e) {
                        System.out.println("Failed to merge " + fileName + " into "
                                + currFile.getAbsolutePath() + ": " + e.getMessage());
                        return false;
                    } finally {
                        deleteQuietly(tmpFile);
                        deleteQuietly(mergedFile);
                    }
                }
                zipIn.closeEntry();
                zipEntry = zipIn.getNextEntry();
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * 判断 zip 条目名的后缀是不是 `.mca`（不区分大小写，与备份端 {@code DirFileRecord.isMcaPath} 一致）
     *
     * @param entryName zip 条目名或相对路径
     * @return 是否为区域文件
     */
    static boolean isMcaPath(String entryName) {
        return entryName != null && entryName.toLowerCase(Locale.ROOT).endsWith(".mca");
    }

    /**
     * 判断一个 zip 条目是不是目录条目
     *
     * @param zipEntry zip 条目
     * @return 是否为目录
     */
    private static boolean isDirectoryEntry(ZipEntry zipEntry) {
        return zipEntry.isDirectory() || zipEntry.getName().endsWith("/");
    }

    /**
     * 把 zip 条目名解析成 targetDir 下的目标文件，并拒绝路径逃逸出 targetDir 的条目
     *
     * @param targetDir 解压目标目录
     * @param entryName zip 条目名
     * @return 目标文件
     * @throws IOException 条目名为空、非法，或经过规范化后不在 targetDir 之下
     */
    static File resolveZipEntryTarget(File targetDir, String entryName) throws IOException {
        if (entryName == null || entryName.isEmpty())
            throw new IOException("empty entry name");
        Path root = targetDir.getAbsoluteFile().toPath().normalize();
        Path resolved;
        try {
            resolved = root.resolve(entryName).normalize();
        } catch (InvalidPathException e) {
            throw new IOException("invalid entry path \"" + entryName + "\": " + e.getMessage());
        }
        // 绝对路径、盘符路径和 .. 穿越都会落到 root 之外
        if (resolved.equals(root) || !resolved.startsWith(root))
            throw new IOException("entry \"" + entryName + "\" points outside of " + root);
        return resolved.toFile();
    }

    /**
     * 把当前 zip 条目的内容写进目标文件旁边的临时文件
     *
     * @param zipIn    Zip 输入流（当前位置在当前条目内）
     * @param destFile 目标文件
     * @return 写完的临时文件
     * @throws IOException 读写失败
     */
    private static File spoolEntry(ZipInputStream zipIn, File destFile) throws IOException {
        File tmpFile = createSiblingTempFile(destFile);
        try (FileOutputStream out = new FileOutputStream(tmpFile)) {
            byte[] buffer = new byte[16384];
            int len;
            while ((len = zipIn.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            // 冲刷缓冲区，写入文件（如果不冲刷，关闭流后文件可能仍然被占用）
            out.flush();
        } catch (IOException e) {
            deleteQuietly(tmpFile);
            throw e;
        }
        return tmpFile;
    }

    /**
     * 在目标文件所在目录里创建一个临时文件
     *
     * @param destFile 目标文件
     * @return 临时文件
     * @throws IOException 目录创建失败或临时文件创建失败
     */
    private static File createSiblingTempFile(File destFile) throws IOException {
        File parent = destFile.getAbsoluteFile().getParentFile();
        if (parent == null)
            throw new IOException("Cannot determine the parent directory of " + destFile);
        if (!parent.exists() && !parent.mkdirs())
            throw new IOException("Failed to create directory " + parent.getAbsolutePath());
        return File.createTempFile(".psentry-", ".tmp", parent);
    }

    /**
     * 原子替换目标文件
     *
     * @param src  临时文件
     * @param dest 目标文件
     * @throws IOException 替换失败
     */
    private static void moveAtomically(File src, File dest) throws IOException {
        McaDeltaMerger.moveAtomically(src.toPath(), dest.toPath());
    }

    /**
     * 删除临时文件，忽略失败
     *
     * @param file 文件，可为 null
     */
    private static void deleteQuietly(File file) {
        if (file == null)
            return;
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            System.out.println("!!WARNING!! Failed to delete temp file " + file.getAbsolutePath());
        }
    }

    /**
     * 扫描一个目录下的所有文件，写入 Zip 流
     *
     * @param srcDir  待扫描目录
     * @param rootDir 一个根目录，zip 包内所有文件的路径都是相对于这个根路径生成的相对路径
     * @param zos     Zip 流
     * @return 是否成功
     */
    private static boolean zipDir(File srcDir, File rootDir, ZipOutputStream zos) {
        boolean success = true;
        try {
            File[] files = srcDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        success = success && zipDir(file, rootDir, zos);
                    } else {
                        Path rootPath = Path.of(rootDir.toURI());
                        Path filePath = Path.of(file.toURI());
                        Path relativePath = rootPath.relativize(filePath);
                        // zip 条目名必须用正斜杠: Windows 上 Path.toString() 用反斜杠，
                        // 那种包在 Linux 上解压会变成名为 "world\region\x.mca" 的单层文件
                        String entryName = relativePath.toString().replace(File.separatorChar, '/');
                        System.out.println("\tZipping: " + entryName);
                        zos.putNextEntry(new ZipEntry(entryName));
                        try (FileInputStream fis = new FileInputStream(file)) {
                            byte[] buffer = new byte[16384];
                            int len;
                            while ((len = fis.read(buffer)) > 0) {
                                zos.write(buffer, 0, len);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return success;
    }

    /**
     * 将 srcDirs 中每个目录加入压缩包
     *
     * @param srcFiles      目录 File 对象数组
     * @param outputZipFile 压缩包输出文件
     * @param rootDir       一个根目录，zip 包内所有文件的路径都是相对于这个根路径生成的相对路径
     * @return 是否成功
     */
    public static boolean zip(File[] srcFiles, File outputZipFile, File rootDir) {
        // 如果输出文件已经存在则移除
        if (outputZipFile.exists() && !outputZipFile.delete()) {
            System.out.println("Failed to remove existing file " + outputZipFile.getAbsolutePath());
            return false;
        }
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputZipFile))) {
            for (File srcFile : srcFiles) {
                if (srcFile.isDirectory()) {
                    if (!zipDir(srcFile, rootDir, zos))
                        return false;
                } else {
                    // 如果是文件则存入压缩包
                    // 因为是放在压缩包根目录下，文件名就是路径
                    System.out.println("\tZipping: " + srcFile.getName());
                    zos.putNextEntry(new ZipEntry(srcFile.getName()));
                    try (FileInputStream fis = new FileInputStream(srcFile)) {
                        byte[] buffer = new byte[16384];
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                }
            }
            // 冲刷缓冲区，写入文件（如果不冲刷，关闭流后文件可能仍然被占用）
            zos.flush();
            zos.closeEntry();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * 逐行读取文件并返回（忽略空行）
     *
     * @param file File 对象
     * @return String[]
     */
    public static String[] readLines(File file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty())
                    lines.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return lines.toArray(new String[0]);
    }

    /**
     * 彻底删除一个目录及其之中的文件
     *
     * @param dir 目录 File 对象
     * @return 是否成功
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean rmDir(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("Directory not exists or not a directory.");
            return false;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    if (!rmDir(f))
                        return false;
                } else {
                    if (!f.delete()) {
                        System.out.println("Failed to delete file " + f.getAbsolutePath());
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }

    /**
     * 截断文件
     *
     * @param file 文件 File 对象
     * @return 是否成功
     */
    public static boolean truncateFile(File file) {
        if (!file.isFile()) // 不是文件则失败
            return false;
        RandomAccessFile ras = null;
        try {
            ras = new RandomAccessFile(file, "rw");
            ras.setLength(0);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (ras != null) {
                try {
                    ras.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 将秒级时间戳转换为 日期字符串 yyyy-MM-dd HH:mm:ss
     *
     * @param ts 秒级时间戳
     * @return 日期字符串
     */
    public static String timestampToDate(long ts) {
        Instant instant = Instant.ofEpochSecond(ts);
        // 系统时区
        ZoneId sysZone = ZoneId.systemDefault();
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, sysZone);
        // 日期格式化
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return dtf.format(dateTime);
    }

    public static class ExitException extends RuntimeException {
        private final int exitCode;

        public ExitException(int code) {
            super();
            exitCode = code;
        }

        public int getExitCode() {
            return exitCode;
        }

    }
}
