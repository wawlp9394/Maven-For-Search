# Maven Search IntelliJ Plugin 设计文档

## 概述

实现一个 IntelliJ IDEA 插件,提供 Maven 依赖搜索功能,效果类似 Maven Search 插件。支持 IntelliJ Platform 2024.2+ (用户使用 2026 版本)。

## 环境约束

- 操作系统: Windows
- 工作目录: `h:\study\java\maven-plugin\maven-search-plugin`
- **禁止向 C 盘写入任何缓存或下载内容**
- Gradle 安装目录: `G:\software\gradle\gradle-8.7`
- Gradle 缓存 (`GRADLE_USER_HOME`): `G:\software\gradle` (caches 落到 `G:\software\gradle\caches`)
- Gradle Wrapper 分发: `G:\software\gradle\wrapper\dists`
- 构建 JDK: `G:\software\JDK\jdk-21`
- 镜像加速: 阿里云 `https://maven.aliyun.com/repository/public`

## 工程结构

```
maven-search-plugin/
├── build.gradle.kts          # Gradle Kotlin DSL 构建文件
├── settings.gradle.kts       # Gradle 设置
├── gradle.properties         # 版本配置
├── gradle/                   # Gradle wrapper
├── gradlew.bat
├── src/main/
│   ├── kotlin/com/mavensearch/
│   │   ├── plugin/           # 插件入口、生命周期
│   │   ├── api/              # 搜索 API 客户端
│   │   ├── ui/               # Swing UI 组件
│   │   ├── action/           # 菜单/快捷键动作
│   │   ├── config/           # 配置项管理
│   │   └── model/            # 数据模型
│   └── resources/
│       ├── META-INF/plugin.xml
│       └── icons/
└── src/test/kotlin/          # 测试
```

## 构建配置

- Gradle Kotlin DSL
- Gradle IntelliJ Plugin 1.17.4
- `intellij.version = 2024.2` (最低兼容版本)
- `intellij.type = IC` (Community)
- `intellij.updateSinceUntilBuild = false` (兼容到 2026+)
- Kotlin JVM Target: 21
- 仓库镜像: 阿里云

## 分层架构

```
┌─────────────────────────────────────────────┐
│  UI 层 (ui/)                                 │
│  ├─ SearchToolWindowFactory  (Tool Window)   │
│  ├─ SearchDialog             (弹出对话框)     │
│  ├─ ResultListCellRenderer   (结果渲染)       │
│  └─ VersionViewerDialog      (版本查看)       │
├─────────────────────────────────────────────┤
│  Action 层 (action/)                         │
│  ├─ OpenSearchDialogAction   (快捷键打开)     │
│  ├─ CopyDependencyAction     (复制 XML)       │
│  ├─ AddToPomAction           (加到 pom.xml)   │
│  └─ DownloadJarAction        (下载 jar)       │
├─────────────────────────────────────────────┤
│  Service 层 (api/)                           │
│  ├─ MavenSearchService       (搜索服务)       │
│  │   ├─ SearchMavenOrgApi    (默认 API)       │
│  │   └─ SonatypeCentralApi   (备用 API)       │
│  └─ ArtifactDownloadService  (下载服务)       │
├─────────────────────────────────────────────┤
│  Config 层 (config/)                         │
│  ├─ MavenSearchConfigurable  (设置面板)       │
│  └─ MavenSearchSettings      (持久化配置)     │
├─────────────────────────────────────────────┤
│  Model 层 (model/)                           │
│  ├─ Artifact                 (坐标模型)       │
│  ├─ SearchResult             (搜索结果)       │
│  └─ VersionInfo              (版本信息)       │
└─────────────────────────────────────────────┘
```

## 搜索数据源

### 默认 API (search.maven.org)

```
GET https://search.maven.org/solrsearch/select
  ?q=g:"org.springframework"+AND+a:"spring-core"
  &rows=20&wt=json
```

- 关键词搜索: `q=<keyword>` (匹配 g/a)
- 分页: `start`, `rows`
- 返回 JSON: `response.docs[]` 含 id/g/a/latestVersion/timestamp

### 备用 API (central.sonatype.com)

```
GET https://central.sonatype.com/api/internal/search
  ?q=<keyword>&page=0&size=20
```

- 返回 JSON: `components[]` 含 coordinates/versions

### 版本查询

```
GET https://search.maven.org/solrsearch/select
  ?g=<groupId>&a=<artifactId>&core=gav&rows=200&wt=json
```

## 搜索逻辑

- **大小写不敏感**: API 本身不区分大小写
- **模糊查询**: 用户输入 `spring core` → 拆分为 `spring` AND `core` 进行搜索
- **防抖**: 使用 `com.intellij.util.Alarm`,300ms 延迟,新输入取消旧请求

## 错误处理

- 网络超时: 10s,显示错误提示
- API 失败: 自动切换备用 API 重试一次
- 无结果: 显示 "No results found"

## UI 设计

### Tool Window (停靠面板)

```
┌──────────────────────────────────────────────┐
│ [搜索框_____________________] [搜索源▼] [⚙]  │
├──────────────────────────────────────────────┤
│ org.springframework:spring-core              │
│   Latest: 6.1.4    2024-03-14                │
│ ┌────────────────────────────────────────┐   │
│ │ org.springframework.boot               │   │
│ │   Latest: 3.2.3    2024-02-22          │   │
│ │ [Copy] [Versions] [Download] [Add POM] │   │
│ └────────────────────────────────────────┘   │
│ org.apache.commons:commons-lang3             │
│   Latest: 3.14.0   2024-02-23                │
├──────────────────────────────────────────────┤
│ [< Prev]  Page 1/5  [Next >]    20 results   │
└──────────────────────────────────────────────┘
```

### SearchDialog (快捷键弹出)

- 与 Tool Window 内容一致,以模态对话框形式呈现
- 快捷键: `Ctrl+Shift+M` (默认)

### 版本查看对话框

```
┌──────────────────────────────────────┐
│ Versions of spring-core              │
├──────────────────────────────────────┤
│ 6.1.4    2024-03-14    [Copy] [DL]   │
│ 6.1.3    2024-02-22    [Copy] [DL]   │
│ 6.0.18   2024-02-22    [Copy] [DL]   │
├──────────────────────────────────────┤
│              [Close]                 │
└──────────────────────────────────────┘
```

## 功能列表

### 始终启用
- 查看所有版本: 点击查看 artifact 的历史版本列表
- 复制 dependency XML: 复制 Maven `<dependency>` 片段到剪贴板

### 可配置 (默认关闭)
- 下载 jar 包: 下载到当前项目 `lib/` 目录,不存在则自动创建
- 添加到 pom.xml: 将依赖插入当前项目的 pom.xml

## 设置面板 (Settings → Tools → Maven Search)

- 搜索源选择: Default / Sonatype Central / Auto-fallback
- 防抖延迟: 300ms (可调)
- 启用下载 jar 功能 (默认关)
- 启用添加到 pom.xml 功能 (默认关)
- jar 下载目录: 默认 `${PROJECT_DIR}/lib`

## 技术选型

- UI: IntelliJ 原生 Swing 组件 (JBPanel, JBList 等)
- 网络: IntelliJ 内置 `com.intellij.util.io.HttpRequests`
- 异步: `com.intellij.util.Alarm` + `Task.Backgroundable`
- JSON 解析: IntelliJ 内置 `com.intellij.json` 或手动解析
- 构建: Gradle Kotlin DSL + Gradle IntelliJ Plugin
