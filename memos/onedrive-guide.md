# OneDrive 应用注册与配置引导

* [English Version](#eng)  

本文档将会详细说明如何注册 OneDrive 应用并获取 `refresh_token`。  

## 1. 应用注册

1. 登录 Microsoft Entra 平台：https://entra.microsoft.com/#home, 在导航栏找到应用注册（App Registrations）入口：  

    ![find_application_registration](./images/onedrive-guide/217535e1f69c817f331d1afdea77d44df589dd487d31b2ebc91034729c5d072a-2026-07-05.png)  

2. 注册新应用：  

    ![start_registration](./images/onedrive-guide/66e65334b7a66e51f1125338665330f84aab0837ae3529f0be7899099becdffe-2026-07-05.png)  

3. 填写注册信息，注册应用：
   
    ![fill_in_registration_form](./images/onedrive-guide/40d36876611c57d9cec1bbaca3164ae5d6b76fcbd4810cad361dadfcea7416f3-2026-07-05.png)   

    * 名字自己随便起一个即可；
    * 其中账户类型请选择 `Any Entra ID Tenant + Personal Microsoft Accounts`（任意 Entra 租户 + 个人 Microsoft 账号），这样就算你是用的个人微软账号，也能正常访问这个 APP；  
    * Redirect URI 选择 Web 类型，填入 `https://potatosack.imbottle.com/onedrive-token-helper.html`，后面步骤成功登录并授权给应用后会带着必要信息跳转到这个 URI。  

## 2. 配置 API 权限

1. 在应用视图左侧导航栏找到 API 权限（API Permissions），进入权限管理面板，新增一个权限，选择 Microsoft Graph -> 委派权限（Delegated Permissions）：  

    ![request_new_permissions](./images/onedrive-guide/9b30481da86935e6b8afdfad3108801ed676aeec7f936607b197fe8edf42f684-2026-07-05.png)  

    ![choose_delegated_permissions](./images/onedrive-guide/31288ad4a6c6224f56690332b2c513306969d543581da1c4f43831332d9de7da-2026-07-05.png)  

    > 委派权限指的是代表用户身份去调用 API 的权限，采用委派权限，才知道访问 OneDrive 时访问的是谁的文件。  

2. 搜索 `Files.`，读方面，先选择 `Files.Read` 权限；写入方面，如果你采用 App Folder，请选择 `Files.ReadWrite.AppFolder`，否则请选择 `Files.ReadWrite.All`：  

    ![select_files_permissions](./images/onedrive-guide/2e14988e189637a930531fc239045ac1d6fff4efa6d80cab2676a8e522dd7737-2026-07-05.png)  

    > 注：使用 OneDrive 企业版（ODB）时，如果使用 `Files.ReadWrite.AppFolder` 有无法写入文件的问题，请再添加 `Files.ReadWrite.All` 权限。  


3. 搜索 `offline_access`，添加这个权限：  

    ![select_offline_access](./images/onedrive-guide/86644d918f0ac608c7ca4582e1bee73cd4ee4fe8c72e86a4b5bca4a834e83655-2026-07-05.png)  

    > 如果不选择这个权限，是无法获取到 `refresh_token` 的。  


4. 操作完后你应该有 `Files.Read`, `offline_access`, `Files.ReadWrite.AppFolder` / `Files.ReadWrite.All` 权限

## 3. 创建 Client Secret

在应用左侧导航面板找到证书 & 密钥（Certificates & secrets）进入，在 Client Secret 选项卡下创建新的 Client Secret：  

![create_client_secret](./images/onedrive-guide/02dda846f4420f3109f11d7b2080efb2e36d9ca5238eabaff708df6b7ac18fab-2026-07-05.png)  

* 描述可以随便写，过期时间的话，如果比较懒，可以选长一点的时间 (￣3￣)╭，不过到时候别忘了到期更换。  

创建后复制保存密钥：  

![copy_secret](./images/onedrive-guide/9f8f958954b5f570948d48208f54a0b40c657b6f465f635c07f396a6f8032072-2026-07-05.png)  

## 4. 获得 Client ID

在应用面板左侧导航栏点击概览（Overview）进入概览面板，一眼就能看到 Client ID，复制下来：  

![copy_client_id](./images/onedrive-guide/c0e003ef95faa10119bf15c3722bf831bc3df1f4d1bc56a4c15c63392c9ad6b4-2026-07-05.png)  

## 5. 获取 Refresh Token

1. 在地址栏访问 https://potatosack.imbottle.com/onedrive-token-helper.html, 在第一步模块中填入我们刚才收集到的信息，然后点击**复制授权链接**：  

    ![fill_in_first_step_form](./images/onedrive-guide/ad2f5ba92cb2c208c332881617c72efdf8e5d23c98ed815d22a46ab0ee85ac68-2026-07-05.png)  

2. 复制授权链接后在另一个窗口打开，然后建议你刚刚注册应用的账户进行登录，登录完成后授权应用：  

    ![authorize_app](./images/onedrive-guide/353edb3ca2fdcd107d77b55f403c6fefb71e00eba3b9792179171b77ee2bed85-2026-07-05.png)   

3. 授权完成后会跳转回刚才的页面，现在可以复制 curl 命令了：  

    ![continue_step_2](./images/onedrive-guide/699bb1e6b9dae9db39a511c2878a59ec09b656f00878487aaba41afc8b51f516-2026-07-05.png)  

4. 复制命令，在支持 curl 工具的终端执行，在输出的结果中你就可以找到 `refresh_token` 了：  

    ![execute_curl_and_get_token](./images/onedrive-guide/41ace840e7ce406c3042d60899bb5fa6bd7886673fcfb8dca0f460aa2374189c-2026-07-05.png)  

## 6. 编辑插件配置

现在你就可以编辑 `configs.yml`，填入相应信息了！

---

<a id="eng"></a>

# OneDrive App Registration & Configuration Guide

This document explains how to register a OneDrive application and obtain a `refresh_token`. 

## 1. App Registration

1. Sign in to the Microsoft Entra platform: https://entra.microsoft.com/#home, find **App Registrations** in the navigation panel:

    ![find_application_registration](./images/onedrive-guide/217535e1f69c817f331d1afdea77d44df589dd487d31b2ebc91034729c5d072a-2026-07-05.png)

2. Register a new application:

    ![start_registration](./images/onedrive-guide/66e65334b7a66e51f1125338665330f84aab0837ae3529f0be7899099becdffe-2026-07-05.png)

3. Fill in the registration form:

    ![fill_in_registration_form](./images/onedrive-guide/40d36876611c57d9cec1bbaca3164ae5d6b76fcbd4810cad361dadfcea7416f3-2026-07-05.png)

    * Choose any name you like;
    * For **Account types**, select `Any Entra ID Tenant + Personal Microsoft Accounts` — this lets both personal Microsoft accounts and work/school accounts access the app;
    * Set **Redirect URI** to Web type, enter `https://potatosack.imbottle.com/onedrive-token-helper.html`. After successful login and authorization, the browser will redirect here with necessary parameters.

## 2. Configure API Permissions

1. In the left navigation panel, find **API Permissions**, click **Add a permission**, select **Microsoft Graph** → **Delegated Permissions**:

    ![request_new_permissions](./images/onedrive-guide/9b30481da86935e6b8afdfad3108801ed676aeec7f936607b197fe8edf42f684-2026-07-05.png)

    ![choose_delegated_permissions](./images/onedrive-guide/31288ad4a6c6224f56690332b2c513306969d543581da1c4f43831332d9de7da-2026-07-05.png)

    > Delegated permissions let the application act on behalf of the signed-in user, so the API knows whose OneDrive files to access.

2. Search for `Files.`. For read access, check `Files.Read`. For write access, choose `Files.ReadWrite.AppFolder` if you are using App Folder, or `Files.ReadWrite.All` otherwise:

    ![select_files_permissions](./images/onedrive-guide/2e14988e189637a930531fc239045ac1d6fff4efa6d80cab2676a8e522dd7737-2026-07-05.png)

    > Note: When using OneDrive for Business (ODB), if `Files.ReadWrite.AppFolder` has trouble writing files, please also add `Files.ReadWrite.All`.

3. Search for `offline_access` and add it:

    ![select_offline_access](./images/onedrive-guide/86644d918f0ac608c7ca4582e1bee73cd4ee4fe8c72e86a4b5bca4a834e83655-2026-07-05.png)

    > Without this permission, you won't be able to obtain a `refresh_token`.

4. You should now have `Files.Read`, `offline_access`, and either `Files.ReadWrite.AppFolder` or `Files.ReadWrite.All`.

## 3. Create Client Secret

In the left navigation panel, go to **Certificates & secrets**, under **Client Secrets**, create a new secret:

![create_client_secret](./images/onedrive-guide/02dda846f4420f3109f11d7b2080efb2e36d9ca5238eabaff708df6b7ac18fab-2026-07-05.png)

* You can set any description. For expiry, choose a longer duration if you prefer less frequent maintenance :p — just remember to rotate it before it expires.

Once created, copy and save the secret:

![copy_secret](./images/onedrive-guide/9f8f958954b5f570948d48208f54a0b40c657b6f465f635c07f396a6f8032072-2026-07-05.png)

## 4. Get Client ID

In the left navigation panel, click **Overview**. The **Client ID** is displayed right on the page — copy it:

![copy_client_id](./images/onedrive-guide/c0e003ef95faa10119bf15c3722bf831bc3df1f4d1bc56a4c15c63392c9ad6b4-2026-07-05.png)

## 5. Get Refresh Token

1. Go to https://potatosack.imbottle.com/onedrive-token-helper.html. In the first step, fill in the information we just collected, then click **复制授权链接** to copy a URL for authorization:

    ![fill_in_first_step_form](./images/onedrive-guide/ad2f5ba92cb2c208c332881617c72efdf8e5d23c98ed815d22a46ab0ee85ac68-2026-07-05.png)

2. Open the copied link in a new window. Sign in with the account you registered the application under, then authorize the app:

    ![authorize_app](./images/onedrive-guide/353edb3ca2fdcd107d77b55f403c6fefb71e00eba3b9792179171b77ee2bed85-2026-07-05.png)

3. After authorization, you'll be redirected back to the helper page. Now you can copy the curl command:

    ![continue_step_2](./images/onedrive-guide/699bb1e6b9dae9db39a511c2878a59ec09b656f00878487aaba41afc8b51f516-2026-07-05.png)

4. Run the copied command in a terminal that supports curl. In the output, you'll find the `refresh_token`:

    ![execute_curl_and_get_token](./images/onedrive-guide/41ace840e7ce406c3042d60899bb5fa6bd7886673fcfb8dca0f460aa2374189c-2026-07-05.png)

## 6. Edit Plugin Configuration

Now you can edit `configs.yml` and fill in all the information!