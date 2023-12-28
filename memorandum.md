# 备忘

## 关于获取用户Object ID

* https://learn.microsoft.com/zh-cn/partner-center/marketplace/find-tenant-object-id

## 个人的安全性想法

在MS Graph应用程序授权下，如果某个应用被赋予了`Files.Read.All`，就可以访问同一租户目录的所有文件。

> 同一租户=同一组织。

因此最好只分配**委托权限(Delegated)**，而**不要**分配**应用程序权限(Application)**，以让权限分配局限在单个登录用户，从而限制访问范围。


## 关于OneDrive权限

* https://learn.microsoft.com/zh-cn/onedrive/developer/rest-api/concepts/permissions_reference?view=odsp-graph-online

## 403问题

* `AccessDenied Either scp or roles claim need to be present in the token`  

    - 解决贴: https://pnp.github.io/cli-microsoft365/user-guide/cli-certificate-caveats/#i-get-an-error-403-accessdenied-either-scp-or-roles-claim-need-to-be-present-in-the-token-when-executing-a-cli-for-microsoft-365-sharepoint-command-what-does-it-mean 
    - 主要是账户权限缺少`Sites.Read.All`。

## 插件大致设计

1. 每天进行一次全量备份，全量备份后当天剩下时间全为增量备份。
  - 每天的全量备份+这天的增量备份称为**一组备份**
2. 可以配置保存备份的组数，以及增量备份的粒度
3. 可配置仅在有玩家时增量备份