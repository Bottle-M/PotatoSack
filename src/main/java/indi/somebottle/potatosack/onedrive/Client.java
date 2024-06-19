package indi.somebottle.potatosack.onedrive;

import com.google.gson.Gson;
import indi.somebottle.potatosack.entities.backup.ZipFilePath;
import indi.somebottle.potatosack.entities.onedrive.*;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.HttpRetryInterceptor;
import okhttp3.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class Client {
    private final TokenFetcher fetcher;
    private final Gson gson = new Gson();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(new HttpRetryInterceptor()) // 添加拦截器，实现请求失败重试
            .build();

    public Client(TokenFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * 获得用户在OneDrive中的AppFolder的Web URL
     *
     * @return 用户在OneDrive中的AppFolder WebURL
     */
    public String getAppFolderUrl() throws IOException {
        Item appFolder = getItem("");
        if (appFolder != null)
            return appFolder.getWebUrl();
        else
            return "";
    }

    /**
     * 列出根目录下所有项目
     *
     * @return 根目录下所有项目
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public List<Item> listItems() throws IOException {
        return listItems("");
    }

    /**
     * 列出指定目录中的所有项目
     *
     * @param path 指定目录（比如 "Documents" 指的是 "根目录/Documents"）
     * @return 指定目录中的所有项目Item
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public List<Item> listItems(String path) throws IOException {
        String url;
        if (path.equals("")) // 默认为根目录
            url = Constants.MS_GRAPH_ENDPOINT + Constants.OD_API_ROOT_PATH + "/children";
        else // 指定子目录（相对根目录）
            url = Constants.MS_GRAPH_ENDPOINT + Constants.OD_API_ROOT_PATH + ":/" + path + ":/children";
        return requestChildren(url);
    }

    /**
     * 发出子目录请求
     *
     * @param url 子目录请求URL
     * @return 子目录中的所有项目
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     */
    private List<Item> requestChildren(String url) throws IOException {
        List<Item> resList = new ArrayList<>(); // 子目录中的所有项目
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + fetcher.getAccessToken())
                .build();
        ResponseBody respBody = null;
        // 发送请求
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                ChildrenResp childrenResp = gson.fromJson(respBody.string(), ChildrenResp.class);
                resList = childrenResp.getValue();
                if (childrenResp.getNextLink() != null) {
                    // 递归分页请求
                    resList.addAll(requestChildren(childrenResp.getNextLink()));
                }
            } else {
                String errMsg = "Children req failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            }
        } catch (Exception e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return resList;
    }

    /**
     * 把文件下载到本地
     *
     * @param path      文件在OneDrive的路径，比如"test.txt"指的就是 "根目录/test.txt"
     * @param localFile 本地文件File对象，指定本地文件路径
     * @return 是否成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     */
    public boolean downloadFile(String path, File localFile) throws IOException {
        File parentFile = localFile.getParentFile();
        if (!parentFile.exists())
            if (!parentFile.mkdirs()) // 创建缺失的目录
                return false; // 建立失败
        Item item = getItem(path);
        // 若项目不存在或不是文件则无法下载
        if (item == null || item.isFolder())
            return false;
        String url = item.getDownloadUrl();
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .build();
        ResponseBody respBody = null;
        // 发送请求
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (!resp.isSuccessful() || respBody == null) {
                String errMsg = "Download failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            } else {
                try (InputStream inputStream = respBody.byteStream();
                     FileOutputStream fos = new FileOutputStream(localFile)) {
                    // 缓冲
                    byte[] buf = new byte[8192];
                    int bytesRead;
                    // 将数据流写入文件
                    while ((bytesRead = inputStream.read(buf)) != -1) {
                        fos.write(buf, 0, bytesRead);
                    }
                    fos.flush();
                }
                return true;
            }
        } catch (Exception e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return false;
    }

    /**
     * 获得某个项目的详细信息（文件/目录）
     *
     * @param path 项目路径，比如"Documents"指的就是 "根目录/Documents"
     * @return 项目详细信息
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 同时包含项目文件的下载URL
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public Item getItem(String path) throws IOException {
        String url;
        if (path.equals("")) // 默认为根目录
            url = Constants.MS_GRAPH_ENDPOINT + Constants.OD_API_ROOT_PATH;
        else // 指定子目录（相对根目录）
            url = Constants.MS_GRAPH_ENDPOINT + Constants.OD_API_ROOT_PATH + ":/" + path;
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + fetcher.getAccessToken())
                .build();
        ResponseBody respBody = null;
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                return gson.fromJson(respBody.string(), Item.class);
            } else {
                String errMsg = "Item req failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            }
        } catch (IOException e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return null;
    }

    /**
     * 小文件上传(<=4MB)，当文件大于4MB时本方法会自动调用uploadLargeFile方法，转变为大文件分块上传
     *
     * @param localPath  本地文件路径
     * @param remotePath 远程目录路径，比如"Documents/test.txt"指的就是 "根目录/Documents/test.txt"
     * @return 是否上传成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 请在线程内调用此方法，可能阻塞
     * @apiNote 如果remotePath所指的文件已经存在，会更新这个文件
     */
    public boolean uploadFile(String localPath, String remotePath) throws IOException {
        if (!new File(localPath).exists() || remotePath.equals(""))
            return false;
        File localFile = new File(localPath);
        if (localFile.length() > Constants.MAX_SMALL_FILE_SIZE) // 超过了小文件最大允许大小，转为大文件上传
            return uploadLargeFile(localPath, remotePath);
        // 以下为小文件上传
        String url = Constants.MS_GRAPH_ENDPOINT + Constants.OD_API_ROOT_PATH + ":/" + remotePath + ":/content?@microsoft.graph.conflictBehavior=replace";
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + fetcher.getAccessToken())
                .put(RequestBody.create(localFile, MediaType.parse("text/plain")))
                .build();
        ResponseBody respBody = null;
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                return true;
            } else {
                String errMsg = "File upload failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            }
        } catch (IOException e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return false;
    }

    /**
     * （用于大文件上传）建立文件上传会话
     *
     * @param remotePath 远程目录路径，比如"Documents/test.txt"指的就是 "根目录/Documents/test.txt"
     * @return uploadUrl 上传URL
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 请保证remotePath不为空
     */
    private String createUploadSession(String remotePath) throws IOException {
        String remoteName = new File(remotePath).getName(); // 获取在远程目录的文件名
        String url = Constants.MS_GRAPH_ENDPOINT + Constants.OD_API_ROOT_PATH + ":/" + remotePath + ":/createUploadSession";
        UploadRequest upReq = new UploadRequest(remoteName); // 构建请求表单
        String upReqJson = gson.toJson(upReq);
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + fetcher.getAccessToken())
                .post(RequestBody.create(upReqJson, MediaType.parse("application/json")))
                .build();
        ResponseBody respBody = null;
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                UpSessionCreateResp crResp = gson.fromJson(respBody.string(), UpSessionCreateResp.class);
                return crResp.getUploadUrl();
            } else {
                // 20240611 修改此处的错误信息
                String errMsg = "Upload session creation failed, code: " + resp.code() + ", message: " + resp.message();
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
     * <p>压缩的同时进行文件上传（仅用于大文件）</p><bold>此处传入的是ZipFilePath[]，是将指定的文件进行打包后上传。</bold>
     *
     * @param zipFilePaths 要打包的文件路径对ZipFilePath[]
     * @param remotePath   远程目录路径，比如"Documents/test.txt"指的就是 "根目录/Documents/test.txt"
     * @param quiet        是否静默打包（不显示 Adding... 信息)
     * @return 是否上传成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public boolean zipPipingUpload(ZipFilePath[] zipFilePaths, String remotePath, boolean quiet) throws IOException {
        if (zipFilePaths.length == 0 || remotePath.equals(""))
            return false;
        String uploadUrl = createUploadSession(remotePath); // 建立上传会话
        StreamedZipUploader uploader = new StreamedZipUploader(uploadUrl);
        return uploader.zipSpecifiedAndUpload(zipFilePaths, quiet);
    }

    /*
     * <p>压缩的同时进行文件上传（仅用于大文件）</p><bold>此处传入的是String[]。</bold>
     * <p>srcDirPath指定要打包的目录路径（注意：这些目录必须在同一级）</p>
     *
     * @param srcDirPath 指定要打包的目录路径（注意：这些目录必须在同一级）
     * @param remotePath 远程目录路径，比如"Documents/test.txt"指的就是 "根目录/Documents/test.txt"
     * @param quiet      是否静默打包（不显示 Adding... 信息)
     * @return 是否上传成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     */
    /*public boolean zipPipingUpload(String[] srcDirPath, String remotePath, boolean quiet) throws IOException {
        if (srcDirPath.length == 0 || remotePath.equals(""))
            return false;
        String uploadUrl = createUploadSession(remotePath); // 建立上传会话
        StreamedZipUploader uploader = new StreamedZipUploader(uploadUrl);
        return uploader.zipAndUpload(srcDirPath, quiet);
    }*/

    /**
     * 将本地大文件上传到Onedrive中
     *
     * @param localPath  本地文件路径
     * @param remotePath 远程目录路径，比如"Documents/test.txt"指的就是 "根目录/Documents/test.txt"
     * @return 是否上传成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public boolean uploadLargeFile(String localPath, String remotePath) throws IOException {
        if (!new File(localPath).exists() || remotePath.equals(""))
            return false;
        File localFile = new File(localPath);
        String uploadUrl = createUploadSession(remotePath); // 建立上传会话
        FileUploader uploader = new FileUploader(localFile, uploadUrl);
        return uploader.upload();
    }

    /**
     * 删除指定路径下的项目（文件/目录）
     *
     * @param path 指定路径，比如"Documents"指的就是 "根目录/Documents"
     * @return 是否删除成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 请在线程内调用此方法，可能阻塞
     */
    public boolean deleteItem(String path) throws IOException {
        String url = Constants.MS_GRAPH_ENDPOINT + Constants.OD_API_ROOT_PATH + ":/" + path;
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + fetcher.getAccessToken())
                .delete() // DELETE请求
                .build();
        ResponseBody respBody = null;
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                return true;
            } else {
                String errMsg = "Delete req failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            }
        } catch (IOException e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return false;
    }

    /**
     * 在根目录下建立子目录
     *
     * @param name 目录名
     * @return 是否建立成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 请在线程内调用此方法，会阻塞
     */
    public boolean createFolder(String name) throws IOException {
        return createFolder("", name);
    }

    /**
     * 建立目录
     *
     * @param path 路径（比如"Documents" 指的是 "根目录/Documents"）
     * @param name 目录名
     * @return 是否建立成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 请在线程内调用此方法，会阻塞
     */
    public boolean createFolder(String path, String name) throws IOException {
        FolderRequest folderReq = new FolderRequest(name);
        String jsonReqBody = gson.toJson(folderReq);
        String url; // 子目录请求URL
        if (path.equals("")) // 默认为根目录
            url = Constants.MS_GRAPH_ENDPOINT + Constants.OD_API_ROOT_PATH + "/children";
        else // 指定子目录（相对根目录）
            url = Constants.MS_GRAPH_ENDPOINT + Constants.OD_API_ROOT_PATH + ":/" + path + ":/children";
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + fetcher.getAccessToken())
                .post(RequestBody.create(jsonReqBody, MediaType.parse("application/json")))
                .build();
        ResponseBody respBody = null;
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                return true;
            } else {
                String errMsg = "Folder req failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            }
        } catch (Exception e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return false;
    }
}
