# Jira 工作流继承与进度管理插件框架

本目录由 Atlassian 官方 `atlas-create-jira-plugin` 命令生成。当前仅包含插件框架、Spring Scanner 示例组件、静态资源及示例测试，尚未实现工作流继承、配置追加或进度业务功能。

## 项目参数

| 参数 | 值 |
|---|---|
| Group ID | `igsl.com`（按用户指定，未改成域名倒写） |
| Artifact ID | `jira-workflow-inheritance` |
| 版本 | `1.0.0-SNAPSHOT` |
| Java 包 | `igsl.com.jira.workflow` |
| Plugin Key | `igsl.com.jira-workflow-inheritance` |
| Jira | `9.12.11` |
| Atlassian SDK / AMPS | `9.1.1` |
| 构建环境 | JDK 17，编译目标 Java 11 |
| 开发商 | 未配置名称和网址 |

## 创建命令

在父目录运行的官方脚手架命令如下。已有项目时不要重复执行：

```powershell
atlas-create-jira-plugin --group-id igsl.com --artifact-id jira-workflow-inheritance --version 1.0.0-SNAPSHOT --package igsl.com.jira.workflow --non-interactive '-Dmaven.repo.local=D:/code/pluginforjira/.maven/repository'
```

生成后将模板中的 Jira 9.12.2 改为 9.12.11，设置插件名称，移除示例开发商元信息。依赖缓存使用父目录 `.maven/repository`，避免本机 SDK 默认仓库 `C:/.m2/repository` 的写入问题；未修改全局 Maven 设置。

## 构建

在本目录执行（首次需要联网下载依赖）：

```powershell
$env:ATLAS_OPTS = "$env:ATLAS_OPTS -Dmaven.repo.local=D:/code/pluginforjira/.maven/repository"
atlas-package -B -ntp '-Dmaven.repo.local=D:/code/pluginforjira/.maven/repository'
```

`ATLAS_OPTS` 同时为 SDK 前置插件解析指定仓库；仅传命令行参数时，该前置步骤仍可能访问默认仓库。这里只设置当前 PowerShell 会话变量，不修改全局配置。依赖齐全后可添加 `-o` 离线打包；仍需检查日志中的最终 `BUILD SUCCESS`，SDK 脚本在前置失败时可能返回 0。

成功后主插件产物为 `target/jira-workflow-inheritance-1.0.0-SNAPSHOT.jar`。示例单元测试不代表业务功能或 DC 多节点兼容性验收。

如果 `atlas-package` 卡在前置插件解析，可使用同一 SDK 提供的 Maven 包装命令直接执行相同的 `package` 目标：

```powershell
atlas-mvn package -B -ntp '-Dmaven.repo.local=D:/code/pluginforjira/.maven/repository'
```

## 本次验证结果

2026-09-28：首次 `atlas-package` 构建成功；移除测试插件开发商占位符后，使用 `atlas-mvn package -o -B -ntp` 及上述仓库参数完成最终离线打包。官方示例单元测试 1 项通过，失败及错误均为 0。主插件与测试插件描述文件均无 `vendor` 节点。未启动 Jira，未运行容器内集成测试或 DC 多节点测试。

## 后续本地运行

需要启动开发 Jira 时再执行：

```powershell
$env:ATLAS_OPTS = "$env:ATLAS_OPTS -Dmaven.repo.local=D:/code/pluginforjira/.maven/repository"
atlas-run '-Dmaven.repo.local=D:/code/pluginforjira/.maven/repository'
```

该操作会下载并启动开发实例，需准备相应资源和 Jira 许可。打包成功不等于已验证 Jira 加载或多节点运行；本次框架搭建不部署到生产环境。

## 目录

- `pom.xml`：Jira 版本、依赖和 AMPS 构建配置。
- `src/main/java`：官方示例接口和组件，后续按模块逐步实现。
- `src/main/resources/atlassian-plugin.xml`：插件及 Web Resource 声明。
- `src/main/resources/META-INF/spring`：Spring Scanner 配置。
- `src/test/java/ut`：官方示例单元测试。
- `src/test/java/it`：需要 Jira 容器的官方示例集成测试。

## 官方参考

- [atlas-create-jira-plugin](https://developer.atlassian.com/server/framework/atlassian-sdk/atlas-create-jira-plugin/)
- [Jira 9.12 支持的平台](https://confluence.atlassian.com/adminjiraserver0912/supported-platforms-1346046805.html)

需求及功能拆分文档保留在父目录，不包含在插件包内。
