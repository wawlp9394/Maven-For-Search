# Maven For Search

一个 IntelliJ IDEA 插件，直接在 IDE 内搜索 Maven Central 仓库构件，体验接近 [central.sonatype.com](https://central.sonatype.com)。支持 IntelliJ Platform 2024.2+（兼容到 2026+）。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)
[![Platform](https://img.shields.io/badge/IntelliJ-2024.2%2B-orange.svg)](https://plugins.jetbrains.com/docs/intellij/intellij-platform.html)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-purple.svg)](https://kotlinlang.org/)

***

## 功能特性

- **智能搜索**：精确匹配优先 + 分词模糊匹配（输入 `spring-boot-web-starter` 可找到 `spring-boot-starter-web`），大小写不敏感，300ms 防抖
- **无限滚动**：滚动到底部自动加载下一页，无需翻页按钮
- **版本查看**：双击搜索结果查看所有历史版本，支持过滤；Solr gav 优先（每版本真实发布日期），metadata 回退（全量实时无日期）
- **双击复制 XML**：版本列表双击即复制 `dependency` XML 到剪贴板并自动关闭弹窗
- **复制 dependency XML**：一键复制选中构件的 Maven 依赖片段
- **下载 jar**（可选）：下载到 `${PROJECT_DIR}/${jarDownloadDir}/`，默认 `lib/`
- **添加到 pom**（可选）：自动插入 `dependency` 到 `pom.xml`
- **三种入口**：右侧 Tool Window、快捷键 `Ctrl+Shift+M`、Tools 菜单

## 环境要求

| 依赖            | 版本                      | 说明                      |
| ------------- | ----------------------- | ----------------------- |
| JDK           | 21                      | 编译与 toolchain 要求        |
| IntelliJ IDEA | 2024.2+                 | Community 或 Ultimate 均可 |
| Gradle        | 9.6.0（wrapper 自带）       | 无需手动安装                  |
| 操作系统          | Windows / macOS / Linux | 跨平台                     |

## 快速开始（开发者）

### 1. 克隆仓库

```bash
git clone https://github.com/czshao/Maven-For-Search.git
cd Maven-For-Search
```

### 2. 一键配置开发环境（推荐）

仓库自带幂等的环境配置脚本，会自动检测并下载缺失的 JDK 21、设置 `GRADLE_USER_HOME` 离开系统盘、切换国内镜像加速。

**Windows（PowerShell 5+）：**

```powershell
# 切换到非 C 盘的任意目录后运行（脚本会在此目录下创建 jdk21/ 和 gradle-home/）
cd D:\dev
powershell -File <仓库路径>\scripts\setup.ps1
```

**Linux / macOS：**

```bash
cd ~/dev
bash <仓库路径>/scripts/setup.sh
```

脚本会完成：

1. 询问安装根目录，校验非系统盘
2. 检测 / 下载 JDK 21（华为云镜像）
3. 设置 `JAVA_HOME`、`GRADLE_USER_HOME` 环境变量（永久写入）
4. 改写 `gradle-wrapper.properties` 为腾讯云镜像
5. 改写 `gradle.properties` 的 `org.gradle.java.installations.paths` 为实际 JDK 路径

> **重要**：脚本配置完成后，**请重新打开终端**让环境变量生效。

### 3. 构建插件

```bash
# 在仓库根目录执行
./gradlew.bat buildPlugin -x test      # Windows
./gradlew buildPlugin -x test          # Linux/macOS
```

构建产物位于 `build/distributions/`（文件名 `maven-for-search-1.0.5.zip`），可直接安装到 IntelliJ IDEA。

> **注意**：首次构建会下载 IntelliJ Platform SDK（约 800MB），无国内镜像，请耐心等待。所有依赖缓存会落到 `GRADLE_USER_HOME` 指定的非系统盘路径。

### 4. 在 IDEA 中安装

1. 启动 IntelliJ IDEA → `File` → `Settings` → `Plugins`
2. 点击齿轮图标 → `Install Plugin from Disk...`
3. 选择 `build/distributions/maven-for-search-1.0.5.zip`
4. 重启 IDE

## 使用说明

| 入口          | 操作                            |
| ----------- | ----------------------------- |
| Tool Window | 点击右侧工具栏 "Maven For Search" 图标 |
| 快捷键         | `Ctrl+Shift+M` 弹出搜索对话框        |
| 菜单          | `Tools` → `Maven For Search`  |

**搜索**：在输入框输入关键词（如 `spring-boot-starter-web`），300ms 后自动搜索，回车立即搜索。滚动到底部自动加载下一页。

**查看版本**：双击搜索结果列表项，弹出该构件的所有历史版本。在版本弹窗内可继续输入过滤；双击版本项即复制对应 `dependency` XML 并关闭弹窗。

**操作按钮**（按配置可见）：

| 按钮      | 触发条件                     | 行为                                      |
| ------- | ------------------------ | --------------------------------------- |
| 复制 XML  | 始终可见                     | 复制选中构件的 `dependency` XML                |
| 查看版本    | 始终可见                     | 打开版本弹窗                                  |
| 下载 jar  | `enableDownloadJar=true` | 下载到 `${PROJECT_DIR}/${jarDownloadDir}/` |
| 添加到 pom | `enableAddToPom=true`    | 插入 `dependency` 到 `pom.xml`             |

## 配置项

入口：`Settings` → `Tools` → `Maven For Search`
持久化文件：`maven-search.xml`（位于 IDE 配置目录）

| 字段                  | 类型     | 默认值             | 说明                                                                                                   |
| ------------------- | ------ | --------------- | ---------------------------------------------------------------------------------------------------- |
| `searchSource`      | enum   | `AUTO_FALLBACK` | 搜索源：`MAVEN_CENTRAL` / `SONATYPE_CENTRAL` / `AUTO_FALLBACK`（自动回退：优先 Sonatype，失败回退 Maven Central Solr） |
| `debounceDelayMs`   | int    | `300`           | 搜索防抖延迟（毫秒）                                                                                           |
| `pageSize`          | int    | `20`            | 每页结果数                                                                                                |
| `networkTimeoutSec` | int    | `10`            | 网络超时（秒）                                                                                              |
| `enableDownloadJar` | bool   | `false`         | 是否启用"下载 jar"按钮                                                                                       |
| `enableAddToPom`    | bool   | `false`         | 是否启用"添加到 pom"按钮                                                                                      |
| `jarDownloadDir`    | string | `lib`           | jar 下载目录（相对项目根目录）                                                                                    |

## 数据源

| API                                   | 用途                                           | 特点                                    |
| ------------------------------------- | -------------------------------------------- | ------------------------------------- |
| central.sonatype.com (browse API)     | 搜索构件（AUTO\_FALLBACK 主源）                      | 数据实时，结果稳定，与 central.sonatype.com 页面一致 |
| search.maven.org Solr (`core=search`) | 搜索构件（AUTO\_FALLBACK 回退 / `MAVEN_CENTRAL` 强制） | 官方索引，有数月延迟                            |
| search.maven.org Solr (`core=gav`)    | 版本列表                                         | 每版本独立时间戳，有延迟                          |
| repo1.maven.org `maven-metadata.xml`  | 版本列表回退                                       | 全量实时，无每版本时间戳                          |

**版本时间显示策略**：Solr gav 优先（每版本有真实发布时间戳）→ Solr 失败或返回空时回退到 metadata（无每版本时间戳，不显示日期以避免错误的统一日期）。

## 仓库结构

```
Maven-For-Search/
├── build.gradle.kts              # Gradle Kotlin DSL 构建文件
├── settings.gradle.kts           # Gradle 设置
├── gradle.properties             # 版本与 toolchain 配置
├── gradle/wrapper/               # Gradle wrapper
├── gradlew / gradlew.bat         # Gradle wrapper 启动脚本
├── src/main/
│   ├── kotlin/com/mavensearch/
│   │   ├── action/               # 菜单/快捷键动作
│   │   ├── api/                  # 搜索 API 客户端
│   │   ├── config/               # 配置项管理
│   │   ├── model/                # 数据模型
│   │   └── ui/                   # Swing UI 组件
│   └── resources/META-INF/
│       └── plugin.xml            # 插件清单
├── scripts/                      # 环境配置脚本
│   ├── setup.ps1                 # Windows PowerShell
│   ├── setup.sh                  # Linux/macOS Bash
│   └── setup.bat                 # Windows 批处理入口
├── docs/                         # 文档
│   ├── REQUIREMENTS.md           # 需求文档
│   └── superpowers/specs/        # 设计快照
├── LICENSE
├── .gitignore
└── README.md
```

## 版本历史

| 版本    | 主要变更                                                        |
| ----- | ----------------------------------------------------------- |
| 1.0.5 | 修复关闭版本弹窗后结果被清空、快速输入结果错乱、版本弹窗加载慢、回车搜索不可靠、Sonatype 失败不回退 Solr |
| 1.0.4 | 修复无限滚动不触发（滚轮事件过滤 bug）、版本时间显示恢复（Solr gav 优先）                 |
| 1.0.3 | 版本弹窗双击复制关闭+通知、搜索无限滚动、Solr 分页修复                              |
| 1.0.2 | 智能搜索词序无关匹配、版本弹窗分页                                           |
| 1.0.1 | 修复 Solr 搜索参数、版本弹窗加载、双击查看版本                                  |
| 1.0.0 | 初版：搜索、版本查看、复制 XML                                           |

完整变更记录见 [plugin.xml](./src/main/resources/META-INF/plugin.xml) 中的 `<change-notes>`。

## 如果你觉得这个项目帮助到了你，你可以帮作者买一杯果汁表示鼓励

![WeChat Pay](image.png)

## 许可证

[MIT License](./LICENSE)

作者：[czshao](https://github.com/czshao)
