package indi.somebottle.potatosack.onedrive;

import com.google.gson.Gson;
import indi.somebottle.potatosack.entities.onedrive.PutOrGetSessionResp;
import indi.somebottle.potatosack.utils.ConsoleSender;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.List;

public class RequestUtils {
    private static final Gson gson = new Gson();

    /**
     * 此类用于包装 OneDrive 相关的部分共用请求方法
     */
    private RequestUtils() {
    }

    /**
     * 获得服务端期待的下一个分片的 range
     *
     * @param client    OKHttpClient
     * @param uploadUrl 文件上传 URL
     * @return long[rangeStart, rangeEnd], rangeEnd 可能为 -1，代表未指定。
     * @throws IOException 请求异常
     */
    public static long[] getNextExpectedRange(OkHttpClient client, String uploadUrl) throws IOException {
        ResponseBody respBody = null;
        try {
            // 构造 GET 请求
            Request req = new Request.Builder()
                    .url(uploadUrl)
                    .build();
            // 发送 GET 请求
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                // 成功拿到
                ConsoleSender.toConsole("Get next expected range successfully.");
                // 解析响应
                PutOrGetSessionResp respObj = gson.fromJson(respBody.string(), PutOrGetSessionResp.class);
                List<String> nextRanges = respObj.getNextExpectedRanges();
                if (nextRanges != null && nextRanges.size() > 0) {
                    String[] range = nextRanges.get(0).split("-");
                    long rangeStart = Long.parseLong(range[0]);
                    long rangeEnd = -1L;
                    if (range.length > 1) {
                        rangeEnd = Long.parseLong(range[1]);
                    }
                    return new long[]{rangeStart, rangeEnd};
                } else {
                    // 找不到 nextRange
                    String errMsg = "Error: no next ranges, resp body: " + respBody.string();
                    throw new IOException(errMsg);
                }
            } else {
                // 请求未成功，抛出异常
                throw new IOException("Get next expected range failed. Code:" + resp.code() + ",Message:" + resp.message());
            }
        } finally {
            // 关闭 responseBody
            if (respBody != null) {
                respBody.close();
            }
        }
    }
}
