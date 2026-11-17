package com.mavensearch.api

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.Disposable
import com.intellij.util.Alarm
import com.mavensearch.config.MavenSearchSettings
import com.mavensearch.config.SearchSort
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
            // 400: 查询表达式被服务端拒绝 (通常是输入了非法字符), 提示用户简化关键词
            rawMsg.contains("HTTP 400") ->
                " (invalid query — try a plain artifact name such as \"hutool-all\")"
            // 5xx: 服务端问题, 稍后重试即可
            rawMsg.contains("HTTP 5") ->
                " (server-side issue, try again later)"
            else -> ""
        }
    }

    private fun performSearch(query: String, page: Int): List<SearchResult> {
        val pageSize = settings.state.pageSize
        val timeoutMs = settings.state.networkTimeoutSec * 1000

        // 唯一搜索源: central.sonatype.com 公开 Solr 端点 (solrsearch/select)。
        // v1.0.9 修复: browse API 被 Cloudflare 按 TLS 指纹拦截 (所有 Java 直连 400/403),
        // 且 JCEF 通道在部分 IDE 发行版不存在 (NoClassDefFoundError); 该 Solr 端点
        // 索引实时、Java 可直连, 无需浏览器。失败时异常向上传播, 由 doSearch 带源名报错。
        //
        // 一次抓取 100 条再在客户端排序/切片 (而不是服务端分页), 原因:
        // 1) 输入 "spring-boot-starter-web" 时, 官方构件之后还跟着十几个第三方同名复刻,
        //    真正的变体 "spring-boot-starter-webmvc" 排在第 47 位, 服务端分页时第一页看不到;
        // 2) 客户端重排后跨页顺序一致, 无限滚动不会出现重复/跳项。
        val all = trySearch(SonatypeCentralApi(timeoutMs), query, 0, SonatypeCentralApi.DEFAULT_FETCH_SIZE)

        // 该 Solr 端点不支持 sort 参数 (传 sort 直接返回 400), 排序在客户端完成。
        val ordered = sortResults(all, settings.searchSort, query)

        // 按页切片 (无限滚动每次只取 pageSize 条)
        val fromIndex = page.coerceAtLeast(0) * pageSize
        if (fromIndex >= ordered.size) return emptyList()
        return ordered.subList(fromIndex, minOf(fromIndex + pageSize, ordered.size)).toList()
    }

    /**
     * 按用户选择的排序方式排序 (Solr 端点不支持服务端排序)
     *
     * @param query 原始查询词, 用于把精确匹配/前缀变体提前 (相关性排序时)
     */
    private fun sortResults(results: List<SearchResult>, sort: SearchSort, query: String): List<SearchResult> =
        when (sort) {
            // 相关性: Solr 原始顺序 + 精确匹配/变体优先重排
            SearchSort.RELEVANCE -> reorderResults(results, query)
            // 最新发布: 按最新版本发布时间倒序, 仅把精确匹配的第一条提到最前
            SearchSort.NEWEST -> movePrimaryExactFirst(
                results.sortedByDescending { it.artifact.timestamp }, query
            )
        }

    /**
     * 相关性重排: 精确匹配 → 前缀变体 → 后缀片段 → 中间片段 → 其他同名复刻 → 其余结果。
     *
     * 排序依据是 artifactId 与查询词的匹配位置:
     * - 精确匹配 (artifactId == query) 放最前
     * - 前缀匹配 (spring-boot-starter-webmvc 之于 spring-boot-starter-web)
     * - 后缀匹配 (spring-boot-starter-webmvc 之于 webmvc)
     * - 中间匹配 (xxx-webmvc-yyy 之于 webmvc)
     * 同组内按 artifactId 长度升序, 更短的命名通常更接近官方库名。
     * 其余同名复刻放在变体之后 (不丢弃, 用户仍可滚动看到), 避免十几个复刻占满首页。
     */
    private fun reorderResults(results: List<SearchResult>, query: String): List<SearchResult> {
        val target = extractArtifactQuery(query)
        if (target.isEmpty()) return results

        val exact = results.filter { it.artifactId.lowercase() == target }
        val prefixed = groupMatches(results, target) { a -> a.startsWith(target) }
        val suffixed = groupMatches(results, target) { a -> !a.startsWith(target) && a.endsWith(target) }
        val contained = groupMatches(results, target) { a ->
            !a.startsWith(target) && !a.endsWith(target) && a.contains(target)
        }
        val used = (exact + prefixed + suffixed + contained).toSet()
        val rest = results.filter { it !in used }

        // 精确匹配只把第一条放最前, 其余同名复刻放到变体之后
        val primary = exact.firstOrNull()
        val otherExact = if (exact.size > 1) exact.drop(1) else emptyList()

        return listOfNotNull(primary) + prefixed + suffixed + contained + otherExact + rest
    }

    /** 按条件筛选 + 去重 + 按 artifactId 长度升序 (排除精确匹配项) */
    private fun groupMatches(
        results: List<SearchResult>,
        target: String,
        predicate: (String) -> Boolean
    ): List<SearchResult> = results
        .filter { it.artifactId.lowercase().let { a -> a != target && predicate(a) } }
        .distinctBy { it.artifactId.lowercase() }
        .sortedBy { it.artifactId.length }

    /** 最新排序时只把精确匹配的第一条提到最前, 其余保持时间倒序 */
    private fun movePrimaryExactFirst(results: List<SearchResult>, query: String): List<SearchResult> {
        val target = extractArtifactQuery(query)
        if (target.isEmpty()) return results
        val index = results.indexOfFirst { it.artifactId.lowercase() == target }
        if (index <= 0) return results
        return listOf(results[index]) + results.filterIndexed { i, _ -> i != index }
    }

    /**
     * 从查询词中提取要匹配的 artifactId。
     * 坐标 ("cn.hutool:hutool-all") 取冒号后的部分; 纯关键词原样返回 (小写)。
     */
    private fun extractArtifactQuery(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ""
        val artifactPart = if (trimmed.contains(':')) trimmed.substringAfter(':') else trimmed
        return artifactPart.trim().lowercase()
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
        // - SearchMavenOrgApi (补充): Sonatype Solr gav, 每版本时间戳, 索引实时 (v1.0.9 切换)
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

            // 第二阶段: 对仍缺失时间戳的版本 (通常是索引尚未收录的极新版本),
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

        // MavenMetadataApi 失败时回退到 Solr gav (仍能拿到带时间戳的版本列表)
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
     * Solr gav 对极新版本可能尚未收录时间戳。这里直接请求 Maven Central 仓库文件的
     * Last-Modified HTTP 头 (实时, 无延迟) 补全。
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
