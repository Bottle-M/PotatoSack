package indi.somebottle.potatosack.onedrive;

import com.google.gson.Gson;
import indi.somebottle.potatosack.entities.drivechildren.Item;
import indi.somebottle.potatosack.entities.drivechildren.Resp;
import indi.somebottle.potatosack.utils.Constants;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
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
     */
    public List<Item> listItems() {
        return listItems("");
    }

    /**
     * 列出指定目录中的所有项目
     *
     * @param path 指定目录（比如 "Documents" 指的是 "根目录/Documents"）
     * @return 指定目录中的所有项目Item
     */
    public List<Item> listItems(String path) {
        String url;
        if (path.equals("")) // 默认为根目录
            url = Constants.MS_GRAPH_ENDPOINT + "/me/drive/root/children";
        else // 指定子目录（相对根目录）
            url = Constants.MS_GRAPH_ENDPOINT + "/me/drive/root:/" + path + ":/children";
        return requestChilds(url);
    }

    public List<Item> requestChilds(String url) {
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
                if (childrenResp.getNextLink() != null) {
                    List<Item> page = childrenResp.getValue();
                    // 递归分页请求
                    page.addAll(requestChilds(childrenResp.getNextLink()));
                    return page;
                } else {
                    return childrenResp.getValue();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
