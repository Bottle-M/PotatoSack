package indi.somebottle.potatosack.onedrive;

import com.google.gson.Gson;
import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.entities.backup.ZipFilePath;
import indi.somebottle.potatosack.entities.onedrive.PutOrGetSessionResp;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.HttpRetryInterceptor;
import indi.somebottle.potatosack.utils.Utils;
import okhttp3.*;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipOutputStream;

/**
 * 本模块用于实现在压缩的同时进行上传，可以避免这样的情况：
 * 比如很多家的面板服会限制磁盘大小，而一般的备份建立方式会先建立一个 Zip 文件存放在本地临时目录，然后再进行上传，这就要求磁盘剩余的空间能容纳下这个压缩包。
 * 如果剩余空间不够，可能导致压缩文件写入失败。
 * 这个模块的思路是时间换空间，先模拟压缩一遍，计算出压缩文件的总大小，然后再压缩一遍，边压缩边上传，即可避免磁盘剩余空间不够的情况
 */
public class StreamedZipUploader {
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Constants.OKHTTP_CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(Constants.OKHTTP_WRITE_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(Constants.OKHTTP_READ_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(Constants.OKHTTP_CALL_TIMEOUT, TimeUnit.SECONDS)
            .addInterceptor(new HttpRetryInterceptor()) // 添加拦截器，实现请求失败重试
            .build();
    private final Gson gson = new Gson();
    private String uploadUrl; // 文件上传时请求的 URL
    private final Client odClient; // OneDrive 模块
    private final String targetUploadPath; // 文件在 OneDrive 中的路径
    private long paddingSize = 0; // 在计算出 totalSize 后，在末尾填充的空白字节数
    private long totalSize = 0; // 压缩文件总大小

    /**
     * 构建 StreamedZipUploader
     *
     * @param odClient   OneDrive Client 对象
     * @param remotePath 文件在 OneDrive 中的远程目录路径，比如"Documents/test.txt"指的就是 "根目录/Documents/test.txt"
     */
    public StreamedZipUploader(Client odClient, String remotePath) {
        this.targetUploadPath = remotePath;
        this.odClient = odClient;
    }

    /**
     * UploadOutputStream 将 Zip 文件流输出直接组块上传
     */
    private class UploadOutputStream extends OutputStream {
        // 缓冲区的大小就是一块文件的大小
        private byte[] buffer = new byte[Constants.CHUNK_SIZE];
        private int writePos = 0; // 写入位置
        private long chunkOffset = 0L; // 当前块对应的 range 的起始字节编号
        private long expectRangeStart = 0L; // 服务端期待下次收到的 range 的起始字节编号（服务端返回，用来判断上传是否缺失了字节）
        private boolean uploadSessionClosed = false; // 是否已经关闭上传会话（通常是服务端认为上传完毕了）
        private boolean streamClosed = false; // 本流是否已经被关闭

        /**
         * 将缓冲区中的块上传，并清空缓冲区
         *
         * @throws IOException 上传失败时抛出 IO异常
         */
        private void uploadBuf() throws IOException {
            uploadBuf(0, 0, writePos);
        }

        /**
         * 将缓冲区中的块上传，并清空缓冲区
         *
         * @param retry       该块的上传重试次数
         * @param bufStartPos 当前块在 buffer 中的起始字节下标（默认是 0 ）
         * @param bufEndPos   当前块在 buffer 中的终止字节下标的后一个下标（默认是 writePos）
         * @throws IOException 上传失败时抛出 IO异常
         * @apiNote 本方法会上传 buffer[bufStartPos, bufEndPos) 这一段数据。
         */
        private void uploadBuf(int retry, int bufStartPos, int bufEndPos) throws IOException {
            // 将缓冲区中的块上传，并清空缓冲区
            // 检查上传数据是否有缺失
            if (expectRangeStart != chunkOffset) {
                // 服务端期待下次接受的 range 和本地要传输的不匹配
                throw new IOException("Expect range start mismatch. Expect: " + expectRangeStart + ", Actual: " + chunkOffset);
            }
            long currRangeEnd = chunkOffset + writePos - 1; // 本次 range 的最后一个字节的编号
            ResponseBody respBody = null;
            // 生成Range头
            String range = "bytes " + chunkOffset + "-" + currRangeEnd + "/" + totalSize;
            ConsoleSender.toConsole("Compressing + Uploading chunk: " + range + " Byte(s)");
            try {
                // 建立文件内容请求体
                // 用Arrays.copyOfRange复制缓冲区切片，防止多余数据被上传
                RequestBody fileReqBody = RequestBody.create(Arrays.copyOfRange(buffer, bufStartPos, bufEndPos), MediaType.parse("application/octet-stream"));
                // 构造请求
                Request req = new Request.Builder()
                        .url(uploadUrl)
                        .header("Content-Type", "application/octet-stream")
                        .header("Content-Range", range)
                        .put(fileReqBody)
                        .build();
                // 发送请求
                Response resp;
                try {
                    resp = client.newCall(req).execute();
                } catch (IOException e) {
                    // 虽然 OKHttp 请求我已经写了拦截器进行重试
                    // 但有时候拦截器重试后还是没能成功（拿不到响应）
                    // 对于流式压缩上传来说最好是不要中断，因此这里还需进行一次完整的重试，递归调用 uploadBuf
                    if (retry >= Constants.MAX_STREAMED_CHUNK_UPLOAD_RETRY) {
                        // 如果已经重试多次了，抛出错误
                        throw e;
                    } else {
                        try {
                            System.out.println("Failed to request, retrying to upload in 10 seconds...");
                            Thread.sleep(10000);
                        } catch (InterruptedException e1) {
                            System.out.println(e1.getMessage());
                        }
                        // 否则重试 uploadBuf
                        uploadBuf(retry + 1, bufStartPos, bufEndPos);
                        return;
                    }
                }
                if (resp.isSuccessful()) {
                    ConsoleSender.toConsole(" --> Chunk successfully uploaded.");
                    int respCode = resp.code();
                    respBody = resp.body(); // 放在这里才能保证ResponseBody不会泄露
                    if (respCode == 202) {
                        // 返回202说明还需要上传其他字节
                        if (respBody == null) throw new IOException("No response body.");
                        // 读取响应
                        PutOrGetSessionResp respObj = gson.fromJson(respBody.string(), PutOrGetSessionResp.class);
                        // 读取服务端期待收到的range
                        List<String> nextRanges = respObj.getNextExpectedRanges();
                        if (nextRanges != null && nextRanges.size() > 0) {
                            // 解析下一次要发送的字段
                            String[] nextRangeSp = nextRanges.get(0).split("-");
                            // 更新服务端期待下次收到的字节编号
                            expectRangeStart = Long.parseLong(nextRangeSp[0]);
                        } else {
                            String errMsg = "Error: no next ranges, resp body: " + respBody.string();
                            throw new IOException(errMsg);
                        }
                    } else {
                        // 200, 201 状态码，服务端认为文件上传完毕
                        uploadSessionClosed = true;
                    }
                    // 上传成功，处理缓冲区
                    chunkOffset += writePos; // 更新下一块相对于文件起点的偏移量
                    writePos = 0; // 从缓冲区头部重新开始写
                } else {
                    /*
                     * OKHttp 的一个坑，只要拿到了 Response，如果没有用 try-with-resource，则必须显式关闭
                     * 如果 respBody = resp.body() 放在下方，可能因为 throw 或者 return 执行不到这一句，导致 Response 没有正常关闭
                     * 关闭 respBody 等同于关闭 Response，详见: https://square.github.io/okhttp/5.x/okhttp/okhttp3/-response/close.html?query=open%20override%20fun%20close()
                     */
                    respBody = resp.body();
                    // 请求未成功，可能有 400 和 416 状态码
                    switch (resp.code()) {
                        case 400:
                            // 400 可能是因为 Range 的问题
                            if (currRangeEnd > totalSize - 1) {
                                // 如果确实是实际上传大小比计算出的大小更大
                                // 抛出异常时携带信息：溢出的字节数
                                throw new DataSizeOverflowException("Data size overflow.", currRangeEnd - totalSize + 1);
                            }
                            break;
                        case 416:
                            // 遇到 416 问题时，检查服务端要求接收的下一个分片的起始字节编号是什么
                            ConsoleSender.toConsole("Fragment overlap, querying server to determine whether the transfer can proceed.");
                            long[] nextExpectedRange = RequestUtils.getNextExpectedRange(client, uploadUrl);
                            ConsoleSender.toConsole("Next expect range start: " + nextExpectedRange[0] + ", current rangeEnd: " + currRangeEnd);
                            if (nextExpectedRange[0] == currRangeEnd + 1) {
                                // 当前分片 range 的末尾字节编号 +1 就是服务端期待接收到的下一个字节，说明当前分片服务端已经成功收到
                                // 接着上传下一个分片即可，这个异常可忽略
                                expectRangeStart = nextExpectedRange[0];
                                chunkOffset = currRangeEnd + 1;
                                // writePos 归零，从下一块的 0 字节处重新开始
                                writePos = 0;
                                ConsoleSender.toConsole("Resuming upload...");
                                return;
                            } else if (nextExpectedRange[0] >= chunkOffset && nextExpectedRange[0] <= currRangeEnd) {
                                // 还有一种就是当前分片服务端只收到了一部分
                                // 期待的下一个字节位于当前分片的字节范围内
                                // 那么就从期待的字节开始继续传输完这个分片
                                // 计算出距离当前分片起始字节的偏移
                                int offset = (int) (nextExpectedRange[0] - chunkOffset);
                                // 调整 chunkOffset 和 writePos（其实就是在调整上传的 range）
                                expectRangeStart = nextExpectedRange[0];
                                chunkOffset = nextExpectedRange[0];
                                // 这里 writePos 并不需要归零，而是要调整
                                writePos -= offset;
                                // 上传 buffer[offset, bufEndPos) 这一部分的数据
                                uploadBuf(0, offset, bufEndPos);
                                return;
                            }
                            break;
                    }
                    String errMsg = "Upload req failed, code: " + resp.code() + ", message: " + resp.message();
                    if (respBody != null)
                        errMsg += "\n Resp body: " + respBody.string();
                    throw new IOException(errMsg);
                }
            } catch (IOException e) {
                // 如果有 IO 异常，先把 writePos 置 0
                // 避免 try-with-resource 关闭（close）时再次触发 uploadBuf
                writePos = 0;
                // 再把异常抛出去
                throw e;
            } finally {
                // 关闭responseBody
                if (respBody != null)
                    respBody.close();
            }
        }

        /**
         * 中止流，直接将流标记为关闭，让 write 和 close 方法无效。
         */
        public void terminate() {
            streamClosed = true;
            buffer = null;
        }

        /**
         * 写入字节到缓冲区，直至缓冲区满，则阻塞，进行块上传
         *
         * @param b the {@code byte}.
         * @throws IOException IO异常
         */
        @Override
        public void write(int b) throws IOException {
            // 如果流已经关闭，写入无效
            if (streamClosed)
                return;
            // 上传会话已经关闭则无法继续传输
            if (uploadSessionClosed)
                throw new IOException("Unexpected: Upload session closed.");
            // 20240619 防越界处理: 如果已经有 writePos >= buffer.length，则 writePos 归零
            if (writePos >= buffer.length) {
                writePos = 0;
            }
            // 先往缓冲区写
            buffer[writePos++] = (byte) b;
            // 如果正好写满一块
            if (writePos == buffer.length) {
                uploadBuf(); // 上传缓冲区并冲刷掉
            }
        }

        /**
         * 填充空白字符以完成上传
         *
         * @throws IOException IO异常
         */
        private void padBlanks() throws IOException {
            // 出现了文件大小上的误差
            // 一般来说误差字节数不会大于一块的大小
            // 更新 writePos 为剩余需要填充的字节数
            ConsoleSender.toConsole("Padding blanks to fit the calculated size...");
            writePos = (int) (totalSize - chunkOffset);
            // 健壮性考虑: 如果误差字节数大于一块的大小，则重设缓冲区
            if (writePos > buffer.length) {
                if (writePos > 10L * Constants.CHUNK_SIZE) {
                    // 但是误差字节数不允许过大，至多为 10 块的大小
                    ConsoleSender.logWarn("Failed to pad. File size error is too large!!!");
                    throw new IOException("Unexpected: File size error is too large!!!");
                }
                buffer = new byte[writePos];
            }
            // 将 buffer [0, writePos-1] 填充为 0
            Arrays.fill(buffer, 0, writePos, (byte) 0);
            // 把剩余的空白字节进行上传
            uploadBuf();
        }

        /**
         * 关闭时冲刷掉缓冲区剩余的内容，并上传至云端
         *
         * @throws IOException IO异常
         */
        @Override
        public void close() throws IOException {
            // 这个 close 方法可能被调用两次，利用 streamClosed 变量防止该方法被重复调用
            if (streamClosed)
                return;
            streamClosed = true;
            System.out.println("Closing upload stream.");
            if (uploadSessionClosed) {
                if (writePos > 0) // 虽然关闭了上传会话，但内存还有数据没有上传
                    throw new IOException("Unexpected: Upload session closed but there's still more data to be uploaded.");
                // 如果上传会话已经正常关闭，无需再次执行 close
            } else if (writePos > 0) {
                // 如果上传会话未关闭，且缓冲区有剩余内容
                // 因为最后一块往往写不满缓冲区
                uploadBuf(); // 上传缓冲区并冲刷掉
                // 上传完最后一块后，可能因为末尾有 paddingSize 填充的空白字符还需要填充，上传会话尚未关闭
                if (!uploadSessionClosed) {
                    padBlanks();
                }
            } else {
                // 上传会话未关闭且缓冲区没有剩余内容
                padBlanks();
            }
            // 关闭流后尝试释放 buffer 资源
            buffer = null;
        }
    }

    /**
     * CounterOutputStream 不执行任何写入操作，仅仅计算 Zip 文件流输出的字节数
     */
    private static class CounterOutputStream extends OutputStream {
        // 计数器
        private final AtomicLong counter;

        public CounterOutputStream(AtomicLong counter) {
            this.counter = counter;
        }

        @Override
        public void write(int b) {
            counter.incrementAndGet();
        }

    }

    /**
     * 将指定的文件打包成Zip并上传
     *
     * @param zipFilePaths 要打包的文件路径对ZipFilePath[]
     * @param quiet        是否静默打包（不显示 Adding... 信息)
     * @return 是否打包上传成功
     */
    public boolean zipSpecifiedAndUpload(ZipFilePath[] zipFilePaths, boolean quiet) throws IOException {
        return zipSpecifiedAndUpload(zipFilePaths, quiet, false);
    }

    /**
     * 将指定的文件打包成Zip并上传
     *
     * @param zipFilePaths 要打包的文件路径对ZipFilePath[]
     * @param quiet        是否静默打包（不显示 Adding... 信息)
     * @param retry        是否是重试
     * @return 是否打包上传成功
     */
    private boolean zipSpecifiedAndUpload(ZipFilePath[] zipFilePaths, boolean quiet, boolean retry) throws IOException {
        AtomicLong fileSizeCounter = new AtomicLong(0L); // 文件总大小计数
        ConsoleSender.toConsole("Calculating file size... ");
        for (int zipRetryCnt = 0; zipRetryCnt <= Constants.ZIP_MAX_RETRY_COUNT; zipRetryCnt++) {
            // 重置计数器数值
            fileSizeCounter.set(0L);
            // 先统计文件大小
            try (
                    CounterOutputStream cos = new CounterOutputStream(fileSizeCounter);
                    ZipOutputStream zout = new ZipOutputStream(cos)
            ) {
                // 进行模拟文件压缩，计算文件大小
                Utils.zipSpecificFilesUtil(zout, zipFilePaths, quiet);
                // 成功了就跳出重试循环继续后续流程
                break;
            } catch (Utils.ZipRWConflictException e) {
                // 发生了数据混乱问题，重试
                ConsoleSender.logWarn(e.getMessage());
                if (zipRetryCnt < Constants.ZIP_MAX_RETRY_COUNT) {
                    ConsoleSender.toConsole("Retrying to calculate...(" + (zipRetryCnt + 1) + "/" + Constants.ZIP_MAX_RETRY_COUNT + ")");
                }
            } catch (Exception e) {
                ConsoleSender.logError("Calculation failed: " + e.getMessage());
                return false;
            }
        }
        // 注意，要在流关闭后再取出结果
        long filesSize = fileSizeCounter.get();
        // 末尾还要加上填充的空白字符
        totalSize = filesSize + paddingSize;
        ConsoleSender.toConsole("Calculation success. Files size in total: " + filesSize);
        if (paddingSize > 0) {
            ConsoleSender.toConsole("Padding size: " + paddingSize);
        }
        ConsoleSender.toConsole("Total size: " + totalSize);
        // 再进行文件压缩和上传
        ConsoleSender.toConsole("Compressing and uploading... ");
        for (int zipRetryCnt = 0; zipRetryCnt <= Constants.ZIP_MAX_RETRY_COUNT; zipRetryCnt++) {
            // 生成新的 uploadUrl
            uploadUrl = odClient.createUploadSession(targetUploadPath);
            try (
                    UploadOutputStream uos = new UploadOutputStream();
                    ZipOutputStream zout = new ZipOutputStream(uos)
            ) {
                try {
                    Utils.zipSpecificFilesUtil(zout, zipFilePaths, quiet);
                    // 成功压缩上传后跳出重试循环
                    break;
                } catch (Utils.ZipRWConflictException e) {
                    // 出现数据混乱问题时先直接中止流，阻止 close 时的上传，然后重试
                    uos.terminate();
                    ConsoleSender.logWarn(e.getMessage());
                    if (zipRetryCnt < Constants.ZIP_MAX_RETRY_COUNT) {
                        ConsoleSender.toConsole("Retrying to compress and upload the files anew...(" + (zipRetryCnt + 1) + "/" + Constants.ZIP_MAX_RETRY_COUNT + ")");
                    }
                }
            } catch (DataSizeOverflowException e) {
                // 异常的多态
                // 出现 400 问题的时候比对 rangeEnd 和 totalSize，如果 rangeEnd >= totalSize，说明是因为超出约定大小，需要立即重试
                // 获得溢出的字节数，更新平均溢出的字节数
                PotatoSack.streamedOverflowBytesTracker.update(e.getOverflowSize());
                // 如果 400 错误的原因是 range 错误，立即重试，自动填充空白字符
                if (retry) {
                    // 重试过了，但还是溢出了，回避
                    ConsoleSender.logError("Compression / upload failed after retrying: " + e.getMessage());
                    return false;
                }
                ConsoleSender.toConsole("Data size overflowed the previous agreed size. Retrying... ");
                // 没有重试过则进行重试
                // 末尾填充的空白字节数 = 5 × 平均溢出的字节数
                paddingSize = 5 * PotatoSack.streamedOverflowBytesTracker.getAvg();
                // 立即重试一次
                return zipSpecifiedAndUpload(zipFilePaths, quiet, true);
            } catch (Exception e) {
                // 20240611 如果这里 uos 抛出了异常，会被捕捉
                // 但是捕捉后，会关闭 zout 资源和 uos 资源
                // 关闭 zout 时会尝试 flush 剩余数据到 uos 中，会调用 uos 的 write 方法
                // 如果此时已经有 writePos==buffer.length，那么就会导致越界异常，但是 writePos 仍会 +1
                // 因为是关闭资源时再次发生的异常，这个异常会被抑制，不会被抛出，在日志中不会体现出来
                // 在这之后会关闭 uos 资源，但是关闭 uos 时又会调用 uos 的 close 方法，在 writePos 不为 0 的情况下触发一次 uploadBuf，因此可能导致再次上传失败，再次抛出异常，这个异常也会被抑制
                ConsoleSender.logError("Compression / upload failed: " + e.getMessage());
                e.printStackTrace();
                // try-with-resource 可能有异常被抑制，获取并打印所有被抑制的异常
                Throwable[] suppressed = e.getSuppressed();
                if (suppressed.length > 0) {
                    System.out.println("Suppressed exceptions:");
                    for (Throwable t : suppressed) {
                        t.printStackTrace();
                    }
                }
                return false;
            }
        }
        ConsoleSender.toConsole("Compression / upload success. Total size: " + totalSize + " Byte(s)");
        return true;
    }

    /**
     * 当上传的文件大小超出了先前模拟压缩计算出的大小时抛出此异常
     */
    public static class DataSizeOverflowException extends IOException {
        private final long overflowSize;

        public DataSizeOverflowException(String msg, long overflowSize) {
            super(msg);
            // 实际上传大小相较模拟压缩计算的大小溢出了多少字节
            this.overflowSize = overflowSize;
        }

        public long getOverflowSize() {
            return overflowSize;
        }
    }


    /*
     * 指定多个目录打包成zip，然后进行上传
     *
     * @param srcDirPath String[] ，指定要打包的目录路径（注意：路径需要是同一层目录下的子目录）
     * @param quiet      是否静默打包（不显示 Adding... 信息)
     * @return 是否打包上传成功
     */
    /*public boolean zipAndUpload(String[] srcDirPath, boolean quiet) {
        // 先统计一次文件大小
        AtomicLong fileSizeCounter = new AtomicLong(0L); // 初始化计数器
        try (
                CounterOutputStream cos = new CounterOutputStream(fileSizeCounter);
                ZipOutputStream zout = new ZipOutputStream(cos)
        ) {
            System.out.println("Calculating file size... ");
            for (String path : srcDirPath) {
                File srcDir = new File(path);
                // 将指定目录内容加入包中
                Utils.addDirFilesToZip(srcDir, srcDir.getName(), zout, quiet);
            }
            zout.closeEntry();
            zout.flush();
        } catch (Exception e) {
            ConsoleSender.logError("Calculation failed: " + e.getMessage());
            return false;
        }
        // 注意，要在流关闭后再取出结果
        totalSize = fileSizeCounter.get();
        System.out.println("Calculation success. Total size: " + totalSize);
        // 再对文件进行压缩并上传
        try (
                UploadOutputStream uos = new UploadOutputStream();
                ZipOutputStream zout = new ZipOutputStream(uos)
        ) {
            System.out.println("Compressing and uploading... ");
            for (String path : srcDirPath) {
                File srcDir = new File(path);
                // 将指定目录内容加入包中
                Utils.addDirFilesToZip(srcDir, srcDir.getName(), zout, quiet);
            }
            zout.closeEntry();
            zout.flush();
        } catch (Exception e) {
            ConsoleSender.logError("Compression / upload failed: " + e.getMessage());
            return false;
        }
        System.out.println("Compression / upload success. Total size: " + totalSize + " Byte(s)");
        return true;
    }*/
}
