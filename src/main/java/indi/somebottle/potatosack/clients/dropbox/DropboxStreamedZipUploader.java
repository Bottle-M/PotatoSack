package indi.somebottle.potatosack.clients.dropbox;

import indi.somebottle.potatosack.tasks.entities.ZipFilePath;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.Utils;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipOutputStream;

/**
 * Dropbox 流式压缩上传器
 * <p>
 * 实现边压缩边上传的功能，无需先生成本地临时压缩文件。
 * 通过自定义 {@link java.io.OutputStream}，将 ZIP 压缩流的输出直接重定向到 Dropbox 上传会话。
 * </p>
 * <p>
 * 工作原理：
 * <ol>
 *   <li>创建 Dropbox 上传会话</li>
 *   <li>创建 {@link UploadOutputStream}，内部维护缓冲区</li>
 *   <li>将 {@link java.util.zip.ZipOutputStream} 包装在 UploadOutputStream 上</li>
 *   <li>压缩数据写入 ZipOutputStream 时，自动写入缓冲区</li>
 *   <li>缓冲区满时，自动上传到 Dropbox</li>
 *   <li>压缩完成后，上传剩余数据并完成会话</li>
 * </ol>
 * </p>
 * <p>
 * 支持自动重试机制，处理文件读写冲突和网络错误。
 * </p>
 *
 * @see indi.somebottle.potatosack.clients.dropbox.DropboxClient
 */
public class DropboxStreamedZipUploader {
    /**
     * Dropbox 客户端实例
     */
    private final DropboxClient dropboxClient;

    /**
     * 目标上传路径
     */
    private final String targetUploadPath;

    /**
     * 构造流式压缩上传器
     *
     * @param dropboxClient Dropbox 客户端实例
     * @param remotePath 目标远程路径
     */
    public DropboxStreamedZipUploader(DropboxClient dropboxClient, String remotePath) {
        this.dropboxClient = dropboxClient;
        this.targetUploadPath = remotePath;
    }

    /**
     * 自定义输出流，用于将压缩数据重定向到 Dropbox 上传会话
     * <p>
     * 内部维护一个缓冲区，当缓冲区满时自动上传到 Dropbox。
     * 流关闭时会上传剩余数据并完成上传会话。
     * </p>
     */
    private class UploadOutputStream extends OutputStream {
        private byte[] buffer = new byte[DropboxClient.CHUNK_SIZE];
        private int writePos = 0;
        private long uploadedOffset = 0;
        private final String sessionId;
        private boolean streamClosed = false;
        private boolean terminated = false;

        public UploadOutputStream(String sessionId) {
            this.sessionId = sessionId;
        }

        private void uploadBuf() throws IOException {
            if (writePos <= 0) {
                return;
            }
            int uploadLength = writePos;
            ConsoleSender.toConsole("Compressing + uploading Dropbox chunk: bytes " + uploadedOffset + "-" + (uploadedOffset + uploadLength - 1));
            try {
                uploadedOffset = dropboxClient.appendUploadSession(sessionId, uploadedOffset, buffer, 0, uploadLength);
                writePos = 0;
                ConsoleSender.toConsole(" --> Dropbox chunk successfully uploaded.");
            } catch (IOException e) {
                writePos = 0;
                terminated = true;
                streamClosed = true;
                buffer = null;
                throw e;
            }
        }

        public void terminate() {
            terminated = true;
            streamClosed = true;
            buffer = null;
        }

        public long getUploadedOffset() {
            return uploadedOffset;
        }

        @Override
        public void write(int b) throws IOException {
            if (streamClosed || terminated) {
                return;
            }
            if (writePos >= buffer.length) {
                uploadBuf();
            }
            buffer[writePos++] = (byte) b;
            if (writePos == buffer.length) {
                uploadBuf();
            }
        }

        @Override
        public void close() throws IOException {
            if (streamClosed) {
                return;
            }
            streamClosed = true;
            try {
                uploadBuf();
                if (!terminated) {
                    if (!dropboxClient.finishUploadSession(sessionId, uploadedOffset, targetUploadPath)) {
                        throw new IOException("Dropbox upload session finish failed.");
                    }
                }
            } finally {
                buffer = null;
            }
        }
    }

    /**
     * 压缩并上传指定文件
     * <p>
     * 创建上传会话和输出流后，使用 {@link indi.somebottle.potatosack.utils.Utils#zipSpecificFilesUtil(java.util.zip.ZipOutputStream, indi.somebottle.potatosack.tasks.entities.ZipFilePath[], boolean)} 进行压缩。
     * 压缩数据会自动通过 {@link UploadOutputStream} 上传到 Dropbox。
     * </p>
     * <p>
     * 支持自动重试机制：
     * <ul>
     *   <li>文件读写冲突时重试</li>
     *   <li>网络错误时重试</li>
     *   <li>最多重试 {@link indi.somebottle.potatosack.utils.Constants#ZIP_MAX_RETRY_COUNT} 次</li>
     * </ul>
     * </p>
     *
     * @param zipFilePaths 要压缩的文件路径数组
     * @param quiet 是否静默模式（不显示 "Adding..." 信息）
     * @return {@code true} 表示压缩上传成功，{@code false} 表示失败
     */
    public boolean zipSpecifiedAndUpload(ZipFilePath[] zipFilePaths, boolean quiet) {
        ConsoleSender.toConsole("Compressing and uploading to Dropbox... ");
        for (int zipRetryCnt = 0; zipRetryCnt <= Constants.ZIP_MAX_RETRY_COUNT; zipRetryCnt++) {
            UploadOutputStream uos = null;
            try {
                String sessionId = dropboxClient.createUploadSession();
                uos = new UploadOutputStream(sessionId);
                try (UploadOutputStream uploadStream = uos;
                     ZipOutputStream zout = new ZipOutputStream(uploadStream)) {
                    try {
                        Utils.zipSpecificFilesUtil(zout, zipFilePaths, quiet);
                    } catch (Utils.ZipRWConflictException e) {
                        uploadStream.terminate();
                        ConsoleSender.logWarn(e.getMessage());
                        if (zipRetryCnt < Constants.ZIP_MAX_RETRY_COUNT) {
                            ConsoleSender.toConsole("Retrying to compress and upload the files anew...(" + (zipRetryCnt + 1) + "/" + Constants.ZIP_MAX_RETRY_COUNT + ")");
                            continue;
                        }
                        return false;
                    }
                }
                ConsoleSender.toConsole("Dropbox compression / upload success. Total size: " + uos.getUploadedOffset() + " Byte(s)");
                return true;
            } catch (IOException e) {
                if (uos != null) {
                    uos.terminate();
                }
                ConsoleSender.logError("Dropbox compression / upload failed: " + e.getMessage());
                if (zipRetryCnt < Constants.ZIP_MAX_RETRY_COUNT) {
                    ConsoleSender.toConsole("Retrying Dropbox compression / upload anew...(" + (zipRetryCnt + 1) + "/" + Constants.ZIP_MAX_RETRY_COUNT + ")");
                    continue;
                }
                e.printStackTrace();
                return false;
            }
        }
        return false;
    }
}
