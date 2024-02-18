package indi.somebottle.potatosack.onedrive;

import com.google.gson.Gson;
import indi.somebottle.potatosack.entities.onedrive.PutSessionResp;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.HttpRetryInterceptor;
import indi.somebottle.potatosack.utils.Utils;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 注：在向uploadUrl发PUT请求上传文件时不需要传传递AccessToken验证头，否则会返回401
 * <a href="https://learn.microsoft.com/zh-cn/onedrive/developer/rest-api/api/driveitem_createuploadsession?view=odsp-graph-online#remarks">文档备注</a>
 */
public class FileUploader {
    private final OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(new HttpRetryInterceptor()) // 添加拦截器，实现请求失败重试
            .build();
    private final Gson gson = new Gson();
    private final File localFile;
    private final long fileSize;
    private final String uploadUrl;
    private final long[] nextRange = {0, -1}; // 接下来要上传的字节范围[start, end]，end=-1代表end=start+CHUNK_SIZE

    public FileUploader(File localFile, String uploadUrl) {
        System.out.println("File upload task: " + localFile.getName() + " to " + uploadUrl);
        this.localFile = localFile;
        this.uploadUrl = uploadUrl;
        this.fileSize = localFile.length();
    }

    public boolean upload() {
        boolean keepUploading = true;
        // 切片上传
        while (keepUploading) {
            // 本块的最后一个字节的编号（从0开始）
            long startPos = nextRange[0];
            long endPos;
            // 如果超过切片大小，就进行切片
            if (nextRange[1] == -1) // 服务端没指定下一段的endPos
                endPos = Math.min(fileSize, startPos + Constants.CHUNK_SIZE) - 1;
            else // 服务端指定了下一段的endPos，注意服务端给出的就是字节位置，不需要-1
                endPos = Math.min(nextRange[1], startPos + Constants.CHUNK_SIZE - 1);
            int status = uploadChunk(startPos, endPos);
            switch (status) {
                case 200: // 上传完毕
                case 201:
                    System.out.println("Upload success! File size: " + fileSize + " bytes");
                    return true;
                case 202:
                    System.out.println("Upload in progress...(Range: " + startPos + "-" + endPos + ") Chunk size: " + (endPos - startPos + 1) + " bytes");
                    break;
                case -1: // 上传失败
                    keepUploading = false;
                    break;
            }
        }
        return false;
    }

    /**
     * 上传指定文件的指定字节范围
     *
     * @param start 起始字节位置
     * @param end   结束字节位置
     * @return 上传后返回的状态码（只有2XX状态码会返回，其余全返回-1）
     */
    private int uploadChunk(long start, long end) {
        ResponseBody respBody = null;
        try {
            // 生成Range头
            String range = "bytes " + start + "-" + end + "/" + fileSize;
            // 读取当前这块的字节数据
            byte[] chunkData = Utils.readBytesFromFile(localFile, start, (int) (end - start + 1));
            // 建立文件内容请求体
            RequestBody fileReqBody = RequestBody.create(chunkData, MediaType.parse("application/octet-stream"));
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
                    if (respBody == null) return -1;
                    // 读取响应
                    PutSessionResp respObj = gson.fromJson(respBody.string(), PutSessionResp.class);
                    // 读取服务端期待收到的range
                    List<String> nextRanges = respObj.getNextExpectedRanges();
                    if (nextRanges != null && nextRanges.size() > 0) {
                        // 解析下一次要发送的字段
                        String[] nextRangeSp = nextRanges.get(0).split("-");
                        nextRange[0] = Long.parseLong(nextRangeSp[0]);
                        if (nextRangeSp.length > 1) // 缺失段时服务端返回"start-end"
                            nextRange[1] = Long.parseLong(nextRangeSp[1]);
                        else // 正常时服务端返回"start-"
                            nextRange[1] = -1; // -1代表end=start+CHUNK_SIZE
                    } else {
                        String errMsg = "Error: no next ranges, resp body: " + respBody;
                        Utils.logError(errMsg);
                        return -1;
                    }
                }
                return respCode;
            } else {
                String errMsg = "Upload req failed, code: " + resp.code() + ", message: " + resp.message();
                respBody = resp.body();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                Utils.logError(errMsg);
            }
        } catch (IOException e) {
            System.out.println("File upload failed due to IO Error");
            Utils.logError(e.getMessage());
            e.printStackTrace();
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return -1;
    }
}
