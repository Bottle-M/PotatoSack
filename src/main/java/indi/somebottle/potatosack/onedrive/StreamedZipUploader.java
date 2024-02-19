package indi.somebottle.potatosack.onedrive;

import com.google.gson.Gson;
import indi.somebottle.potatosack.entities.backup.ZipFilePath;
import indi.somebottle.potatosack.entities.onedrive.PutSessionResp;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.HttpRetryInterceptor;
import indi.somebottle.potatosack.utils.Utils;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipOutputStream;

/**
 * 本模块用于实现在压缩的同时进行上传，可以避免这样的情况：
 * 比如很多家的面板服会限制磁盘大小，而一般的备份建立方式会先建立一个 Zip 文件存放在本地临时目录，然后再进行上传，这就要求磁盘剩余的空间能容纳下这个压缩包。
 * 如果剩余空间不够，可能导致压缩文件写入失败。
 * 这个模块的思路是将压缩文件块先
 */
public class StreamedZipUploader {
    private final OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(new HttpRetryInterceptor()) // 添加拦截器，实现请求失败重试
            .build();
    private final Gson gson = new Gson();
    private final String uploadUrl;
    private long totalSize = 0; // 压缩文件总大小

    public StreamedZipUploader(String uploadUrl) {
        this.uploadUrl = uploadUrl;
    }

    /**
     * UploadOutputStream 将 Zip 文件流输出直接组块上传
     */
    private class UploadOutputStream extends OutputStream {
        // 缓冲区的大小就是一块文件的大小
        private final byte[] buffer = new byte[Constants.CHUNK_SIZE];
        private int writePos = 0; // 写入位置
        private long chunkOffset = 0L; // 当前块对应的 range 的起始字节编号
        private long expectRangeStart = 0L; // 服务端期待下次收到的 range 的起始字节编号（服务端返回，用来判断上传是否缺失了字节）
        private boolean uploadSessionClosed = false; // 是否已经关闭上传会话（通常是服务端认为上传完毕了）

        /**
         * 将缓冲区中的块上传，并清空缓冲区
         *
         * @throws IOException 上传失败时抛出 IO异常
         */
        private void uploadBuf() throws IOException {
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
            System.out.println("Compressing + Uploading chunk: " + range + " Byte(s)");
            try {
                // 建立文件内容请求体
                // 用Arrays.copyOfRange复制缓冲区切片，防止多余数据被上传
                RequestBody fileReqBody = RequestBody.create(Arrays.copyOfRange(buffer, 0, writePos), MediaType.parse("application/octet-stream"));
                // 构造请求
                Request req = new Request.Builder()
                        .url(uploadUrl)
                        .header("Content-Type", "application/octet-stream")
                        .header("Content-Range", range)
                        .put(fileReqBody)
                        .build();
                // 发送请求
                Response resp = client.newCall(req).execute();
                if (resp.isSuccessful()) {
                    System.out.println(" --> Chunk successfully uploaded.");
                    int respCode = resp.code();
                    respBody = resp.body(); // 放在这里才能保证ResponseBody不会泄露
                    if (respCode == 202) {
                        // 返回202说明还需要上传其他字节
                        if (respBody == null) throw new IOException("No response body.");
                        // 读取响应
                        PutSessionResp respObj = gson.fromJson(respBody.string(), PutSessionResp.class);
                        // 读取服务端期待收到的range
                        List<String> nextRanges = respObj.getNextExpectedRanges();
                        if (nextRanges != null && nextRanges.size() > 0) {
                            // 解析下一次要发送的字段
                            String[] nextRangeSp = nextRanges.get(0).split("-");
                            // 更新服务端期待下次收到的字节编号
                            expectRangeStart = Long.parseLong(nextRangeSp[0]);
                        } else {
                            String errMsg = "Error: no next ranges, resp body: " + respBody;
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
                    String errMsg = "Upload req failed, code: " + resp.code() + ", message: " + resp.message();
                    respBody = resp.body();
                    if (respBody != null)
                        errMsg += "\n Resp body: " + respBody.string();
                    throw new IOException(errMsg);
                }
            } finally {
                // 关闭responseBody
                if (respBody != null)
                    respBody.close();
            }
        }

        /**
         * 写入字节到缓冲区，直至缓冲区满，则阻塞，进行块上传
         *
         * @param b the {@code byte}.
         * @throws IOException IO异常
         */
        @Override
        public void write(int b) throws IOException {
            // 服务端提前关闭了上传会话，则失败
            if (uploadSessionClosed)
                throw new IOException("Unexpected: Upload session closed.");
            // 先往缓冲区写
            buffer[writePos++] = (byte) b;
            // 如果写满一块
            if (writePos == buffer.length) {
                uploadBuf(); // 上传缓冲区并冲刷掉
            }
        }

        /**
         * 关闭时冲刷掉缓冲区剩余的内容，并上传至云端
         *
         * @throws IOException IO异常
         */
        @Override
        public void close() throws IOException {
            if (uploadSessionClosed) {
                if (writePos > 0) // 虽然关闭了上传会话，但内存还有数据没有上传
                    throw new IOException("Unexpected: Upload session closed.");
                // 如果上传会话已经正常关闭，无需再次执行 close
            } else if (writePos > 0) {
                // 如果上传会话未关闭，且缓冲区有剩余内容
                uploadBuf(); // 上传缓冲区并冲刷掉
            } else {
                // 上传会话未关闭且缓冲区没有剩余内容
                // 这是异常情况！
                throw new IOException("Unexpected: Upload session not closed although there's no more data to upload.");
            }
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
    public boolean zipSpecifiedAndUpload(ZipFilePath[] zipFilePaths, boolean quiet) {
        AtomicLong fileSizeCounter = new AtomicLong(0L); // 文件总大小计数
        System.out.println("Calculating file size... ");
        // 先统计文件大小
        try (
                CounterOutputStream cos = new CounterOutputStream(fileSizeCounter);
                ZipOutputStream zout = new ZipOutputStream(cos)
        ) {
            // 进行文件压缩
            Utils.zipSpecificFilesUtil(zout, zipFilePaths, quiet);
        } catch (Exception e) {
            Utils.logError("Calculation failed: " + e.getMessage());
            return false;
        }
        // 注意，要在流关闭后再取出结果
        totalSize = fileSizeCounter.get();
        System.out.println("Calculation success. Total size: " + totalSize);
        // 再进行文件压缩和上传
        System.out.println("Compressing and uploading... ");
        try (
                UploadOutputStream uos = new UploadOutputStream();
                ZipOutputStream zout = new ZipOutputStream(uos)
        ) {
            Utils.zipSpecificFilesUtil(zout, zipFilePaths, quiet);
        } catch (Exception e) {
            Utils.logError("Compression / upload failed: " + e.getMessage());
            return false;
        }
        System.out.println("Compression / upload success. Total size: " + totalSize + " Byte(s)");
        return true;
    }


    /**
     * 指定多个目录打包成zip，然后进行上传
     *
     * @param srcDirPath String[] ，指定要打包的目录路径（注意：路径需要是同一层目录下的子目录）
     * @param quiet      是否静默打包（不显示 Adding... 信息)
     * @return 是否打包上传成功
     */
    public boolean zipAndUpload(String[] srcDirPath, boolean quiet) {
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
                Utils.addItemsToZip(srcDir, srcDir.getName(), zout, quiet);
            }
            zout.closeEntry();
            zout.flush();
        } catch (Exception e) {
            Utils.logError("Calculation failed: " + e.getMessage());
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
                Utils.addItemsToZip(srcDir, srcDir.getName(), zout, quiet);
            }
            zout.closeEntry();
            zout.flush();
        } catch (Exception e) {
            Utils.logError("Compression / upload failed: " + e.getMessage());
            return false;
        }
        System.out.println("Compression / upload success. Total size: " + totalSize + " Byte(s)");
        return true;
    }
}
