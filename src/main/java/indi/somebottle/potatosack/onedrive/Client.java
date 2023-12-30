package indi.somebottle.potatosack.onedrive;

import com.google.gson.Gson;
import indi.somebottle.potatosack.entities.drivechildren.Item;
import indi.somebottle.potatosack.entities.drivechildren.Resp;
import indi.somebottle.potatosack.utils.Constants;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Client {
    private final TokenFetcher fetcher;
    private final Gson gson = new Gson();
    private final OkHttpClient client = new OkHttpClient();

    public Client(TokenFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * 列出根目录下所有项目
     *
     * @return 根目录下所有项目
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public List<Item> listItems() {
        return listItems("");
    }

    /**
     * 列出指定目录中的所有项目
     *
     * @param path 指定目录（比如 "Documents" 指的是 "根目录/Documents"）
     * @return 指定目录中的所有项目Item
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public List<Item> listItems(String path) {
        String url;
        if (path.equals("")) // 默认为根目录
            url = Constants.MS_GRAPH_ENDPOINT + "/me/drive/root/children";
        else // 指定子目录（相对根目录）
            url = Constants.MS_GRAPH_ENDPOINT + "/me/drive/root:/" + path + ":/children";
        return requestChilds(url);
    }

    /**
     * 发出子目录请求
     *
     * @param url 子目录请求URL
     * @return 子目录中的所有项目
     */
    private List<Item> requestChilds(String url) {
        List<Item> resList = new ArrayList<>(); // 子目录中的所有项目
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + fetcher.getAccessToken())
                .build();
        // 发送请求
        try {
            Response resp = client.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) {
                Resp childrenResp = gson.fromJson(resp.body().string(), Resp.class);
                resList = childrenResp.getValue();
                if (childrenResp.getNextLink() != null) {
                    // 递归分页请求
                    resList.addAll(requestChilds(childrenResp.getNextLink()));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return resList;
    }

    /**
     * 将本地文件上传到Onedrive中
     *
     * @param localPath  本地文件路径
     * @param remotePath 远程文件路径
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public void uploadFile(String localPath, String remotePath) {
        File file = new File(localPath);

    }
}
