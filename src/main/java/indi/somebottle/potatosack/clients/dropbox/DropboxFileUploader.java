package indi.somebottle.potatosack.clients.dropbox;

import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Utils;

import java.io.File;
import java.io.IOException;

/**
 * Dropbox 文件上传器
 * <p>
 * 负责将本地文件分块上传到 Dropbox。使用上传会话（upload session）机制，
 * 将大文件分割成多个块依次上传，适用于超过小文件上传限制的文件。
 * </p>
 * <p>
 * 上传流程：
 * <ol>
 *   <li>创建上传会话，获取会话 ID</li>
 *   <li>循环读取本地文件块，通过 {@link indi.somebottle.potatosack.clients.dropbox.DropboxClient#appendUploadSession(String, long, byte[], int, int)} 上传</li>
 *   <li>完成上传会话，将文件保存到指定路径</li>
 * </ol>
 * </p>
 *
 * @see indi.somebottle.potatosack.clients.dropbox.DropboxClient
 */
public class DropboxFileUploader {
    /**
     * 本地文件对象
     */
    private final File localFile;

    /**
     * Dropbox 客户端实例
     */
    private final DropboxClient dropboxClient;

    /**
     * 目标远程路径
     */
    private final String remotePath;

    /**
     * 文件大小（字节）
     */
    private final long fileSize;

    /**
     * 构造文件上传器
     *
     * @param localFile 本地文件
     * @param dropboxClient Dropbox 客户端实例
     * @param remotePath 目标远程路径
     */
    public DropboxFileUploader(File localFile, DropboxClient dropboxClient, String remotePath) {
        this.localFile = localFile;
        this.dropboxClient = dropboxClient;
        this.remotePath = remotePath;
        this.fileSize = localFile.length();
        ConsoleSender.toConsole("Dropbox file upload task: " + localFile.getName());
    }

    /**
     * 执行文件上传
     * <p>
     * 创建上传会话后，循环读取文件块并上传，最后完成会话。
     * 每上传一个块都会在控制台显示进度。
     * </p>
     *
     * @return {@code true} 表示上传成功，{@code false} 表示上传失败
     */
    public boolean upload() {
        try {
            String sessionId = dropboxClient.createUploadSession();
            long offset = 0;
            while (offset < fileSize) {
                int chunkLength = (int) Math.min(DropboxClient.CHUNK_SIZE, fileSize - offset);
                byte[] chunkData = Utils.readBytesFromFile(localFile, offset, chunkLength);
                long nextOffset = dropboxClient.appendUploadSession(sessionId, offset, chunkData, 0, chunkLength);
                ConsoleSender.toConsole("Dropbox upload in progress...(Range: " + offset + "-" + (nextOffset - 1) + ") Total size: " + fileSize + " bytes");
                offset = nextOffset;
            }
            if (dropboxClient.finishUploadSession(sessionId, offset, remotePath)) {
                ConsoleSender.toConsole("Dropbox upload success! File size: " + fileSize + " bytes");
                return true;
            }
        } catch (IOException e) {
            ConsoleSender.logError("Dropbox file upload failed: " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }
}
