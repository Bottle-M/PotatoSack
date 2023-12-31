package indi.somebottle.potatosack.onedrive;

import com.google.gson.Gson;
import indi.somebottle.potatosack.entities.ErrorResp;
import indi.somebottle.potatosack.entities.drivechildren.FolderRequest;
import indi.somebottle.potatosack.entities.drivechildren.Item;
import indi.somebottle.potatosack.entities.drivechildren.Resp;
import indi.somebottle.potatosack.utils.Constants;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Client {
    private final String ROOT_PATH = "/drive/special/approot";
    // private final String ROOT_PATH = "/me/drive/root";
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
            url = Constants.MS_GRAPH_ENDPOINT + ROOT_PATH + "/children";
        else // 指定子目录（相对根目录）
            url = Constants.MS_GRAPH_ENDPOINT + ROOT_PATH + ":/" + path + ":/children";
        return requestChildren(url);
    }

    /**
     * 发出子目录请求
     *
     * @param url 子目录请求URL
     * @return 子目录中的所有项目
     */
    private List<Item> requestChildren(String url) {
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
                    resList.addAll(requestChildren(childrenResp.getNextLink()));
                }
            } else {
                System.out.println("Children req failed");
                System.out.println(resp.code());
                System.out.println(resp.message());
                ResponseBody errorBody = resp.body();
                if (errorBody != null)
                    System.out.println(errorBody.string());
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
     * @return 是否上传成功
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public boolean uploadFile(String localPath, String remotePath) {
        File file = new File(localPath);
        return true;
    }

    /**
     * 在根目录下建立子目录
     *
     * @param name 目录名
     * @return 是否建立成功
     * @apiNote 请在线程内调用此方法，会阻塞
     */
    public boolean createFolder(String name) {
        return createFolder("", name);
    }

    /**
     * 建立目录
     *
     * @param path 路径（比如"Documents" 指的是 "根目录/Documents"）
     * @param name 目录名
     * @return 是否建立成功
     * @apiNote 请在线程内调用此方法，会阻塞
     */
    public boolean createFolder(String path, String name) {
        FolderRequest folderReq = new FolderRequest(name);
        String jsonReqBody = gson.toJson(folderReq);
        String url; // 子目录请求URL
        if (path.equals("")) // 默认为根目录
            url = Constants.MS_GRAPH_ENDPOINT + ROOT_PATH + "/children";
        else // 指定子目录（相对根目录）
            url = Constants.MS_GRAPH_ENDPOINT + ROOT_PATH + ":/" + path + ":/children";
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + fetcher.getAccessToken())
                .post(RequestBody.create(jsonReqBody, MediaType.parse("application/json")))
                .build();
        try {
            Response resp = client.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) {
                System.out.println(resp.body().string());
                return true;
            } else {
                System.out.println("Folder req failed");
                System.out.println(resp.code());
                System.out.println(resp.message());
                ResponseBody errorBody = resp.body();
                if (errorBody != null)
                    System.out.println(errorBody.string());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
