# 备忘

## 关于获取用户Object ID

* https://learn.microsoft.com/zh-cn/partner-center/marketplace/find-tenant-object-id

## 个人的安全性想法

在MS Graph应用程序授权下，如果某个应用被赋予了`Files.Read.All`，就可以访问同一租户目录的所有文件。

> 同一租户=同一组织。

因此最好只分配**委托权限(Delegated)**，而**不要**分配**应用程序权限(Application)**，以让权限分配局限在单个登录用户，从而限制访问范围。


## 关于OneDrive权限

* https://learn.microsoft.com/zh-cn/onedrive/developer/rest-api/concepts/permissions_reference?view=odsp-graph-online

## 关于Files.ReadWrite.AppFolder权限节点

在配置权限时可以仅为文件读写分配此权限节点`Files.ReadWrite.AppFolder`。  

此权限节点使得程序只能请求访问**应用目录下**的文件和目录，而不能访问用户其他文件，能很大程度上提升安全性。

详见文档: https://learn.microsoft.com/zh-cn/onedrive/developer/rest-api/concepts/special-folders-appfolder?view=odsp-graph-online#getting-authorization-from-the-user  

但是！就目前看来这个权限节点**仅仅支持**了`OneDrive Personal`（个人/家庭版）而没有支持`OneDrive Business`（企业版）。  

对于后者，此权限节点的表现和`Files.ReadWrite.All`差不多。（微软官方好像很久前就说在考虑给ODB增加此权限节点，结果到现在都没加，老鸽子了）

## 403问题

* `AccessDenied Either scp or roles claim need to be present in the token`  

    - 解决贴: https://pnp.github.io/cli-microsoft365/user-guide/cli-certificate-caveats/#i-get-an-error-403-accessdenied-either-scp-or-roles-claim-need-to-be-present-in-the-token-when-executing-a-cli-for-microsoft-365-sharepoint-command-what-does-it-mean 
    - 主要是账户权限缺少`Sites.Read.All`。

## 插件大致设计

1. 每天进行一次全量备份，全量备份后当天剩下时间全为增量备份。
  - 每天的全量备份+这天的增量备份称为**一组备份**
2. 可以配置保存备份的组数，以及增量备份的粒度
3. 可配置仅在有玩家时增量备份
4. 备份文件的记录文件也需要存在云端

## 参考文档

1. [通过用户身份访问MS Graph](https://learn.microsoft.com/en-us/graph/auth-v2-user?tabs=http#5-use-the-refresh-token-to-get-a-new-access-token) （包括令牌刷新）  
2. [大文件上传](https://learn.microsoft.com/en-us/onedrive/developer/rest-api/api/driveitem_createuploadsession?view=odsp-graph-online)  
3. 
