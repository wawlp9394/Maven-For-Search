# Maven For Search 插件需求文档

## 1. 功能需求

### 1.1 搜索
- **输入框**：支持大小写不敏感模糊查询，300ms 防抖（Enter 键立即搜索）
- **中文输入法兼容**：搜索框用 DocumentListener + InputMethodListener 触发搜索——IME 合成中（拼音未上屏）不触发搜索，候选词提交后以最终文本自动触发，无需重输英文
- **智能匹配**：精确匹配优先 + 分词模糊匹配（如 "spring-boot-web-starter" 命中 "spring-boot-starter-web"），精确匹配大小写不敏感
- **无限滚动**：滚动到底部自动加载下一页，移除传统翻页按钮
- **结果展示**：groupId:artifactId、最新版本、发布日期

### 1.2 版本查看
- **触发**：双击搜索结果列表项
- **数据源**：Solr gav 优先（每版本独立时间戳），metadata 回退（全量实时无时间戳）
- **过滤**：搜索框直接过滤全部版本（非已有结果），支持中文输入法（IME 提交后触发过滤）
- **双击复制**：双击版本项 → 复制 dependency XML → 关闭弹窗 → 弹"已复制到剪贴板"通知
- **日期展示**：Solr 路径显示每版本真实发布日期；metadata 路径无时间戳则不显示

### 1.3 操作按钮（按配置可见）
- **复制 XML**：始终可见，复制选中构件的 dependency XML
- **查看版本**：始终可见，打开版本弹窗
- **下载 jar**：需开启 `enableDownloadJar`，下载到 `${PROJECT_DIR}/${jarDownloadDir}/`
- **添加到 pom**：需开启 `enableAddToPom`，插入 dependency 到 pom.xml

## 2. 配置文件详解

配置入口：**Settings → Tools → Maven For Search**
持久化文件：`maven-search.xml`（IDE 配置目录）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `repoMirror` | enum | `MAVEN_CENTRAL` | 仓库镜像：`MAVEN_CENTRAL`/`ALIYUN`/`HUAWEI`/`TENCENT`，作用于 jar 下载、maven-metadata、版本时间戳 HEAD 请求（搜索不走镜像） |
| `searchSort` | enum | `RELEVANCE` | 排序方式：`RELEVANCE`（精确→前缀→后缀→包含）/`NEWEST`（最新发布） |
| `debounceDelayMs` | int | `300` | 搜索防抖延迟（毫秒） |
| `pageSize` | int | `20` | 每页结果数（无限滚动单次加载条数） |
| `networkTimeoutSec` | int | `10` | 网络超时（秒） |
| `enableDownloadJar` | bool | `false` | 是否启用下载 jar 按钮（默认关） |
| `enableAddToPom` | bool | `false` | 是否启用添加到 pom 按钮（默认关） |
| `jarDownloadDir` | string | `lib` | jar 下载目录（相对项目根目录） |

### 下载 jar 功能默认关闭说明
`enableDownloadJar` 默认 `false`，搜索面板不显示"下载 jar"按钮。需在 Settings 勾选 **Enable download jar to project** 后才显示。这是避免误下载的设计。

## 3. 入口

- **Tool Window**：右侧 Maven For Search 面板
- **快捷键**：`Ctrl+Shift+M` 弹出搜索对话框
- **菜单**：Tools → Maven For Search

## 4. 数据源

| API | 用途 | 特点 |
|-----|------|------|
| `central.sonatype.com/solrsearch/select`（q + 通配） | 搜索构件（唯一搜索源） | 索引实时、Java 可直连；前缀 + 子串两次查询并行合并，客户端重排（无服务端排序） |
| `central.sonatype.com/solrsearch/select`（core=gav） | 版本时间戳 | 每版本独立时间戳，索引实时 |
| 仓库镜像 maven-metadata.xml | 版本列表（主数据源） | 全量实时，走 `repoMirror` 配置 |
| 仓库文件 HEAD Last-Modified | 缺失时间戳补充 | 实时，最多等待 3 秒 |

> 历史说明：browse API (`/api/internal/browse/components`) 已被 Cloudflare TLS 指纹检测拦截，JCEF 通道在部分 IDE 发行版不存在，均不再使用。

## 5. 版本时间显示策略

1. **maven-metadata.xml 提供版本列表**：仓库真实状态，实时无延迟
2. **Solr gav 补充每版本时间戳**：`central.sonatype.com/solrsearch/select`（core=gav），索引实时
3. **最新版本用 metadata 的 lastUpdated**，其余缺失时间戳用仓库文件 HEAD Last-Modified 补全

## 6. 无限滚动策略

- 监听垂直滚动条 `AdjustmentListener`
- 剩余可滚动距离 < 可视区 1/4 时触发加载
- `isLoading` 标志位防重入（加载中不重复触发）
- `hasMore` 标志位防无效请求（返回数 < pageSize 时置 false）
- 不依赖 `valueIsAdjusting`（鼠标滚轮事件该值为 false 会被误过滤）

## 7. 版本历史

| 版本 | 主要变更 |
|------|---------|
| 1.0.0 | 初版：搜索、版本查看、复制 XML |
| 1.0.1 | 修复 Solr 搜索参数 |
| 1.0.2 | 智能搜索词序无关匹配、版本弹窗分页 |
| 1.0.3 | 版本弹窗双击复制关闭+通知、搜索无限滚动、Solr 分页修复 |
| 1.0.4 | 修复无限滚动不触发（滚轮事件过滤 bug）、版本时间显示恢复（Solr gav 优先） |
| 1.0.5 | 修复关闭版本弹窗后结果被清空、快速输入结果错乱、版本弹窗加载慢、回车搜索不可靠、Sonatype 失败不回退 Solr |
| 1.0.6 | 版本弹窗所有版本显示发布日期（HEAD 补充时间戳）、资源泄漏修复、超时取消未完成请求 |
| 1.0.7 | 修复中文输入法导致搜索异常（IME 合成结束触发搜索）、版本过滤框 IME 兼容、精确匹配大小写不敏感、JSON 转义补全 |
| 1.0.8 | 修复 Cloudflare Bot 防护导致的搜索 403（改用完整浏览器 UA + sec-fetch 请求头） |
| 1.0.9 | 搜索改走 `central.sonatype.com/solrsearch/select`（browse API 被 Cloudflare TLS 指纹拦截、JCEF 在部分 IDE 不存在）；前缀 + 子串并行查询支持任意片段模糊搜索；排序改为 Relevance/Newest 客户端排序；版本时间戳同步实时 Solr；适配 IDEA 2026.3 |
