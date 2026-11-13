package com.mavensearch.api

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.Disposable
import com.intellij.util.Alarm
import com.mavensearch.config.MavenSearchSettings
import com.mavensearch.model.SearchResult
import com.mavensearch.model.VersionInfo
import org.jetbrains.annotations.TestOnly

/**
 * Maven 搜索服务 (统一入口)
 *
 * 职责:
 * - 搜索固定使用 central.sonatype.com browse API (唯一实时搜索源, v1.0.9 起移除 Solr 搜索源与回退机制)
 * - 处理防抖 (通过 Alarm)
 * - 后台线程执行网络请求 (使用 ApplicationManager.executeOnPooledThread)
 */
@Service
class MavenSearchService : Disposable {

    private val log = logger<MavenSearchService>()
    private val settings get() = MavenSearchSettings.getInstance()

    /** 防抖 Alarm (单线程,可取消) */
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /**
     * 带防抖的搜索
     * @param query 搜索关键词
     * @param page 页码 (从 0 开始)
     * @param onResult 回调 (EDT 线程)
     * @param onError 错误回调 (EDT 线程)
     */
    fun searchWithDebounce(
        query: String,
        page: Int,
        onResult: (List<SearchResult>) -> Unit,
        onError: (String) -> Unit
    ) {
        // 取消之前的待执行请求 (防抖核心)
        alarm.cancelAllRequests()

        val delayMs = settings.state.debounceDelayMs
        alarm.addRequest({
            doSearch(query, page, onResult, onError)
        }, delayMs)
    }

    /**
     * 立即搜索 (无防抖)
     */
    fun search(
        query: String,
        page: Int,
        onResult: (List<SearchResult>) -> Unit,
        onError: (String) -> Unit
    ) {
        alarm.cancelAllRequests()
        doSearch(query, page, onResult, onError)
    }

    /**
     * 获取版本列表 (无防抖)
     *
     * 在后台线程池执行网络请求, 完成后在 EDT 回调。
     * 使用 executeOnPooledThread 替代 ProgressManager.Task.Backgroundable,
     * 避免 project=null 时后台任务可能不启动的问题。
     */
    fun getVersions(
        groupId: String,
        artifactId: String,
        onResult: (List<VersionInfo>) -> Unit,
        onError: (String) -> Unit
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val versions = doGetVersions(groupId, artifactId)
                invokeOnEdt { onResult(versions) }
            } catch (e: Exception) {
                log.warn("Failed to fetch versions for $groupId:$artifactId", e)
                val msg = e.message ?: "Unknown error"
                invokeOnEdt { onError(msg) }
            }
        }
    }

    /** 取消所有待执行的搜索请求 */
    fun cancel() {
        alarm.cancelAllRequests()
    }

    @TestOnly
    fun doSearch(
        query: String,
        page: Int,
        onResult: (List<SearchResult>) -> Unit,
        onError: (String) -> Unit
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (query.isBlank()) {
                invokeOnEdt { onResult(emptyList()) }
                return@executeOnPooledThread
            }
            try {
                val results = performSearchWithRetry(query, page)
                invokeOnEdt { onResult(results) }
            } catch (e: Exception) {
                log.warn("Search failed for query: $query", e)
                // 错误消息带唯一搜索源名称 + 分类提示, 让用户明确知道是哪个源出问题、该怎么应对
                // (不回退换源, 直接报错)
                val msg = e.message ?: "Search failed"
                invokeOnEdt { onError("[central.sonatype.com] $msg${buildErrorHint(msg)}") }
            }
        }
    }

    /**
     * 带一次重试的搜索。
     *
     * "搜索不到"的主因是国内网络抖动 (实测延迟 1~7 秒波动, 偶发超时/连接失败),
     * 对同一搜索源自动重试一次可显著提高成功率。这是网络层重试, 不是源回退 —
     * 重试仍走同一个源 (Sonatype), 两次都失败才向上报错。
     */
    private fun performSearchWithRetry(query: String, page: Int): List<SearchResult> {
        return try {
            performSearch(query, page)
        } catch (e: Exception) {
            log.info("Search failed, retrying once on the same source: ${e.message}")
            performSearch(query, page)
        }
    }

    /**
     * 根据错误信息生成可操作的应对提示。
     *
     * @param rawMsg 原始错误消息 (如 "HTTP 403 from ..." / timeout 相关)
     * @return 附加提示 (空格开头), 无匹配场景返回空字符串
     */
    private fun buildErrorHint(rawMsg: String): String {
        return when {
            // 连接/读取超时: 网络抖动或代理问题, 引导用户调大超时
            rawMsg.contains("timeout", ignoreCase = true) ||
                    rawMsg.contains("timed out", ignoreCase = true) ->
                " (network timeout — check your network/proxy or increase Network timeout in Settings)"
            // 403: Cloudflare 防护拦截, 提示可能需要更新浏览器请求头
            rawMsg.contains("HTTP 403") ->
                " (blocked by Cloudflare — browser headers may need updating)"
            // 5xx: 服务端问题, 稍后重试即可
            rawMsg.contains("HTTP 5") ->
                " (server-side issue, try again later)"
            else -> ""
        }
    }

    private fun performSearch(query: String, page: Int): List<SearchResult> {
        val pageSize = settings.state.pageSize
        val timeoutMs = settings.state.networkTimeoutSec * 1000

        // 唯一搜索源: Sonatype browse API (数据实时, 与 central.sonatype.com 官网搜索一致)。
        // v1.0.9 起移除 search.maven.org Solr 搜索源 (索引延迟数月, 新构件搜不到) 和回退机制;
        // 失败时异常向上传播, 由 doSearch 统一带源名报错。
        // 排序方式由用户在搜索框旁下拉框选择 (Popularity 等效 mvnrepository.com 的流行度排序)。
        val results = trySearch(SonatypeCentralApi(timeoutMs, settings.searchSort.apiValue), query, page, pageSize)

        // Sonatype browse API 已返回准确的 latestVersion 和 timestamp, 无需再补充 metadata。
        // 客户端重排序: artifactId 精确匹配查询词的排到最前。
        // Sonatype browse API 的默认排序未必把精确匹配放第一 (例如搜 "hutool" 时 hutool-poi 可能排在 hutool-all 前),
        // 这里保证用户搜 "hutool-all" / "spring-boot-starter-webmvc" 等精确名时, 官方同名构件必排第一。
        return reorderExactMatchFirst(results, query)
    }

    /**
     * 把 artifactId 精确匹配查询词的结果排到最前。
     *
     * 查询词可能是纯关键词 ("hutool-all") 或坐标 ("groupId:artifactId"),
     * 取冒号后的 artifactId 部分做精确匹配。其余结果保持 API 原始顺序 (相关性排序)。
     */
    private fun reorderExactMatchFirst(results: List<SearchResult>, query: String): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return results
        // 支持 "groupId:artifactId" 坐标查询: 只取冒号后的 artifactId 部分
        val targetArtifactId = trimmed.substringAfter(':').trim()
        if (targetArtifactId.isEmpty()) return results
        // 大小写不敏感比较: 用户输入 "SPRING-CORE" 时也能把 spring-core 排到最前
        val target = targetArtifactId.lowercase()
        val (exact, others) = results.partition { it.artifact.artifactId.lowercase() == target }
        return if (exact.isEmpty()) results else exact + others
    }

    private fun trySearch(
        api: MavenSearchApiClient,
        query: String,
        page: Int,
        pageSize: Int
    ): List<SearchResult> {
        return api.search(query, page, pageSize).map { it.toSearchResult() }
    }

    private fun doGetVersions(groupId: String, artifactId: String): List<VersionInfo> {
        val timeoutMs = settings.state.networkTimeoutSec * 1000
        val app = ApplicationManager.getApplication()

        // 并行发起两个请求:
        // - MavenMetadataApi (主数据源): maven-metadata.xml, 实时版本列表 + lastUpdated (走配置的镜像源)
        // - SearchMavenOrgApi (补充): Solr gav, 有每版本时间戳, 索引有数月延迟
        val metadataFuture = app.executeOnPooledThread<MavenMetadataApi.MetadataResult?> {
            try {
                MavenMetadataApi(timeoutMs, settings.repoMirror.baseUrl).getMetadata(groupId, artifactId)
            } catch (e: Exception) {
                log.info("MavenMetadataApi failed for $groupId:$artifactId: ${e.message}")
                null
            }
        }
        val solrFuture = app.executeOnPooledThread<Map<String, Long>> {
            try {
                SearchMavenOrgApi(timeoutMs).getVersions(groupId, artifactId, 500)
                    .associate { it.version to it.timestamp }
            } catch (e: Exception) {
                log.info("Solr gav failed for $groupId:$artifactId: ${e.message}")
                emptyMap()
            }
        }

        // 等待主数据源 (maven-metadata.xml 通常很快, 几百毫秒)
        val metadata = try {
            metadataFuture.get(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }

        if (metadata != null && metadata.versions.isNotEmpty()) {
            // 版本列表倒序 (最新在前)
            val versions = metadata.versions.reversed()

            // Solr 仅用于补充时间戳 (次要信息), 最多再等 3 秒
            val solrTimestamps = try {
                solrFuture.get(3000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                solrFuture.cancel(true)
                emptyMap()
            }

            // 第一阶段合并: Solr 时间戳优先; 最新版本用 lastUpdated (maven-metadata.xml 全局最后更新时间, 等于最新版本发布时间)
            val firstPass = versions.mapIndexed { index, v ->
                val ts = solrTimestamps[v] ?: if (index == 0) metadata.lastUpdated else 0L
                VersionInfo(v, ts)
            }

            // 第二阶段: 对仍缺失时间戳的版本 (通常是 Solr 索引延迟期内的新版本),
            // 并行 HEAD 请求 Maven Central 文件的 Last-Modified 补充时间戳
            val missingVersions = firstPass.filter { it.timestamp == 0L }.map { it.version }
            if (missingVersions.isEmpty()) return firstPass

            val headTimestamps = fetchMissingTimestamps(groupId, artifactId, missingVersions, timeoutMs)
            return firstPass.map { v ->
                if (v.timestamp == 0L) {
                    val headTs = headTimestamps[v.version] ?: 0L
                    if (headTs > 0L) VersionInfo(v.version, headTs) else v
                } else v
            }
        }

        // MavenMetadataApi 失败时回退到 Solr gav (索引有延迟但能拿到带时间戳的版本列表)
        log.info("MavenMetadataApi returned empty for $groupId:$artifactId, falling back to Solr gav")
        return try {
            solrFuture.get(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .map { (version, ts) -> VersionInfo(version, ts) }
        } catch (e: Exception) {
            log.warn("Solr gav also failed for $groupId:$artifactId: ${e.message}")
            emptyList()
        }
    }

    /**
     * 对缺失时间戳的版本, 并行 HEAD 请求 Maven Central 文件的 Last-Modified 补充时间戳。
     *
     * Solr gav 索引有数月延迟, 最新版本 (如 hutool-all 5.8.37~5.8.46) 在 Solr 中没有时间戳。
     * 这里直接请求 Maven Central 仓库文件的 Last-Modified HTTP 头 (实时, 无延迟) 补全。
     * 总超时 3 秒, 超时后已拿到的填充, 未拿到的留 0 (不显示日期)。
     */
    private fun fetchMissingTimestamps(
        groupId: String,
        artifactId: String,
        versions: List<String>,
        timeoutMs: Int
    ): Map<String, Long> {
        if (versions.isEmpty()) return emptyMap()
        val groupPath = groupId.replace('.', '/')
        val perRequestTimeout = minOf(timeoutMs, 3000)

        val futures = versions.map { version ->
            java.util.concurrent.CompletableFuture.supplyAsync {
                // HEAD 请求走配置的仓库镜像源 (国内镜像 Last-Modified 响应更快更稳)
                val url = "${settings.repoMirror.baseUrl}/$groupPath/$artifactId/$version/$artifactId-$version.pom"
                try {
                    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = perRequestTimeout
                        readTimeout = perRequestTimeout
                        instanceFollowRedirects = true
                    }
                    try {
                        // lastModified 触发实际 HTTP 连接, 可能抛 IOException
                        version to conn.lastModified
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    version to 0L
                }
            }
        }

        return try {
            java.util.concurrent.CompletableFuture.allOf(*futures.toTypedArray())
                .get(3, java.util.concurrent.TimeUnit.SECONDS)
            futures.map { it.get() }.filter { it.second > 0L }.toMap()
        } catch (e: Exception) {
            // 超时: 取消未完成的请求, 收集已完成的 future 结果
            futures.forEach { it.cancel(true) }
            futures.filter { !it.isCancelled && it.isDone }.mapNotNull {
                try { it.get() } catch (e: Exception) { null }
            }.filter { it.second > 0L }.toMap()
        }
    }

    private fun invokeOnEdt(runnable: () -> Unit) {
        // 关键: 必须使用 ModalityState.any(), 否则在后台线程调用 invokeLater 时,
        // 默认使用 ModalityState.NON_MODAL, 回调在模态对话框 (如 VersionViewerDialog) 期间不会执行,
        // 导致版本对话框永远显示 "Loading versions..." 且列表为空。
        ApplicationManager.getApplication().invokeLater(runnable, ModalityState.any())
    }

    override fun dispose() {
        alarm.dispose()
    }

    companion object {
        fun getInstance(): MavenSearchService =
            ApplicationManager.getApplication().getService(MavenSearchService::class.java)
    }
}
