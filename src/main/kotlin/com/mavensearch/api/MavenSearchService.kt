package com.mavensearch.api

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.Disposable
import com.intellij.util.Alarm
import com.mavensearch.config.MavenSearchSettings
import com.mavensearch.config.SearchSource
import com.mavensearch.model.SearchResult
import com.mavensearch.model.VersionInfo
import org.jetbrains.annotations.TestOnly

/**
 * Maven 搜索服务 (统一入口)
 *
 * 职责:
 * - 根据配置选择 API 源
 * - 处理防抖 (通过 Alarm)
 * - 自动回退 (主 API 失败时切换备用)
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
                val results = performSearch(query, page)
                invokeOnEdt { onResult(results) }
            } catch (e: Exception) {
                log.warn("Search failed for query: $query", e)
                val msg = e.message ?: "Search failed"
                invokeOnEdt { onError(msg) }
            }
        }
    }

    private fun performSearch(query: String, page: Int): List<SearchResult> {
        val pageSize = settings.state.pageSize
        val timeoutMs = settings.state.networkTimeoutSec * 1000
        val source = settings.searchSource

        // AUTO_FALLBACK: 优先用 Sonatype browse API (结果稳定、数据实时、与 central.sonatype.com 一致)
        // 失败时回退到 search.maven.org Solr (兼容性后备)
        val (results, usedApi) = when (source) {
            SearchSource.MAVEN_CENTRAL -> {
                trySearch(SearchMavenOrgApi(timeoutMs), query, page, pageSize) to "solr"
            }
            SearchSource.SONATYPE_CENTRAL -> {
                trySearch(SonatypeCentralApi(timeoutMs), query, page, pageSize) to "sonatype"
            }
            SearchSource.AUTO_FALLBACK -> {
                try {
                    trySearch(SonatypeCentralApi(timeoutMs), query, page, pageSize) to "sonatype"
                } catch (e: Exception) {
                    log.info("Sonatype API failed, falling back to Maven Central Solr: ${e.message}")
                    trySearch(SearchMavenOrgApi(timeoutMs), query, page, pageSize) to "solr"
                }
            }
        }

        // Sonatype browse API 已返回准确的 latestVersion 和 timestamp, 无需再补充 metadata。
        // 仅当使用 Solr 回退时, 才用 maven-metadata.xml 补充最新版本 (Solr 索引有数月延迟)。
        val finalResults = if (usedApi == "solr") {
            enrichWithLatestVersions(results, timeoutMs)
        } else {
            results
        }

        // 客户端重排序: artifactId 精确匹配查询词的排到最前。
        // Sonatype browse API 的默认排序未必把精确匹配放第一 (例如搜 "hutool" 时 hutool-poi 可能排在 hutool-all 前),
        // 这里保证用户搜 "hutool-all" / "spring-boot-starter-webmvc" 等精确名时, 官方同名构件必排第一。
        return reorderExactMatchFirst(finalResults, query)
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
        val (exact, others) = results.partition { it.artifact.artifactId == targetArtifactId }
        return if (exact.isEmpty()) results else exact + others
    }

    /**
     * 用 repo1.maven.org 的 maven-metadata.xml 实时补充最新版本信息
     *
     * search.maven.org 的 Solr 索引数据有延迟 (实测 hutool-all 最新 5.8.46,
     * 但 Solr 索引停在 5.8.36), 通过 maven-metadata.xml 获取仓库真实最新版本。
     *
     * 并行请求 (使用平台线程池), 单个构件请求失败或超时不影响其他结果 (保留原始结果)。
     * 总超时为 timeoutMs, 超时后未完成的请求取消并保留原始结果。
     */
    private fun enrichWithLatestVersions(
        results: List<SearchResult>,
        timeoutMs: Int
    ): List<SearchResult> {
        if (results.isEmpty()) return results

        val metadataApi = MavenMetadataApi(timeoutMs)
        val app = ApplicationManager.getApplication()
        // 并行请求每个构件的最新版本 (使用平台线程池, 而非 ForkJoinPool)
        val pairs = results.map { result ->
            result to app.executeOnPooledThread<SearchResult> {
                try {
                    val metadata = metadataApi.getMetadata(result.groupId, result.artifactId)
                    if (metadata != null && metadata.latestVersion.isNotEmpty()) {
                        result.copy(
                            artifact = result.artifact.copy(
                                latestVersion = metadata.latestVersion,
                                timestamp = metadata.lastUpdated
                            )
                        )
                    } else {
                        result
                    }
                } catch (e: Exception) {
                    result
                }
            }
        }

        // 逐个等待, 总超时为 timeoutMs (从现在开始计算 deadline)
        val deadline = System.currentTimeMillis() + timeoutMs
        return pairs.map { (original, future) ->
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                future.cancel(true)
                return@map original
            }
            try {
                future.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                future.cancel(true)
                original
            }
        }
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

        // 并行发起两个请求, 避免串行等待:
        // - MavenMetadataApi (主数据源): maven-metadata.xml, 快, 实时版本列表, 无每版本时间戳
        // - SearchMavenOrgApi (补充): Solr gav, 慢, 有每版本时间戳, 索引有数月延迟
        val metadataFuture = app.executeOnPooledThread<List<VersionApiResponse>> {
            try {
                MavenMetadataApi(timeoutMs).getVersions(groupId, artifactId, 500)
            } catch (e: Exception) {
                log.info("MavenMetadataApi failed for $groupId:$artifactId: ${e.message}")
                emptyList()
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
        val metadataVersions = try {
            metadataFuture.get(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            emptyList()
        }

        if (metadataVersions.isNotEmpty()) {
            // Solr 仅用于补充时间戳 (次要信息), 最多再等 3 秒, 超时就放弃时间戳不阻塞用户
            val solrTimestamps = try {
                solrFuture.get(3000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                solrFuture.cancel(true)
                emptyMap()
            }
            return metadataVersions.map { v ->
                VersionInfo(v.version, solrTimestamps[v.version] ?: 0L)
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
