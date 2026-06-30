package com.mavensearch.api

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Sonatype Central 搜索 API (central.sonatype.com 真实接口)
 *
 * 通过抓包分析 central.sonatype.com 前端 Next.js 代码, 发现它实际调用:
 *   POST https://central.sonatype.com/api/internal/browse/components
 *   Content-Type: application/json
 *   Body: {"searchTerm":"关键词","page":"0","size":"20"}
 *
 * 优势 (相比 search.maven.org Solr):
 * - 数据实时: 直接查询仓库, 新发布的构件立即可搜 (Solr 索引有数月延迟)
 * - 结果稳定: 默认排序确定性高, 重复查询结果一致 (Solr 按 score 排序会浮动)
 * - 与 central.sonatype.com 页面搜索结果完全一致
 *
 * 响应格式:
 * {"components":[{"namespace":"...","name":"...","latestVersionInfo":{"version":"...","timestampUnixWithMS":...}}]}
 *
 * 排序字段 (可选, 默认按相关性): id, name, namespace, nsPopularityAppCount, publishedDate
 */
class SonatypeCentralApi(
    private val timeoutMs: Int = 10000
) : MavenSearchApiClient {

    companion object {
        private const val BROWSE_URL = "https://central.sonatype.com/api/internal/browse/components"
    }

    /**
     * 搜索 artifact
     *
     * 调用 central.sonatype.com 的 browse API, 返回与官网搜索页面一致的结果。
     * 支持 "groupId:artifactId" 精确坐标查询 (searchTerm 直接传完整字符串)。
     *
     * @param query 搜索关键词, 例如 "spring-boot-starter-webmvc" 或 "org.springframework.boot:spring-boot-starter-web"
     * @param page 页码 (从 0 开始), API 要求字符串形式
     * @param pageSize 每页数量, API 要求字符串形式
     */
    override fun search(query: String, page: Int, pageSize: Int): List<SearchApiResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 构造请求 body (page/size 必须是字符串, 否则 API 返回 400)
        val body = buildString {
            append('{')
            append("\"searchTerm\":\"").append(escapeJson(trimmed)).append("\"")
            append(",\"page\":\"").append(page).append("\"")
            append(",\"size\":\"").append(pageSize).append("\"")
            append('}')
        }.toByteArray(StandardCharsets.UTF_8)

        // 失败时抛异常向上传播, 让 AUTO_FALLBACK 能捕获并回退到 Solr
        // (修复: 原先 catch 后返回 emptyList(), 导致 AUTO_FALLBACK 误以为"正常无结果"而不回退)
        val json = postJson(BROWSE_URL, body)

        return parseSearchResponse(json)
    }

    /**
     * 发送 POST 请求并返回响应字符串
     *
     * 使用原生 HttpURLConnection (而非 IntelliJ HttpRequests), 因为后者对 POST body 支持有限。
     * 连接/读取超时由 [timeoutMs] 控制, 避免网络慢导致 UI 卡死。
     */
    private fun postJson(urlStr: String, body: ByteArray): String {
        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Origin", "https://central.sonatype.com")
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code != 200) {
                throw java.io.IOException("HTTP $code from $urlStr")
            }
            return connection.inputStream.use { it.readBytes() }.toString(StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 获取指定 artifact 的所有版本
     *
     * Sonatype browse 版本 API 的 filter 格式限制较多且只返回版本号字符串 (无时间戳),
     * 因此版本获取统一由 MavenMetadataApi (maven-metadata.xml) 处理, 这里不再实现。
     * 调用此方法时回退到 maven-metadata.xml 风格的空结果, 由上层 doGetVersions 处理。
     */
    override fun getVersions(groupId: String, artifactId: String, pageSize: Int): List<VersionApiResponse> {
        return emptyList()
    }

    /** 解析搜索响应 JSON */
    private fun parseSearchResponse(json: String): List<SearchApiResponse> {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            return emptyList()
        }

        val components = root.getAsJsonArray("components") ?: return emptyList()

        return components.mapNotNull { elem ->
            val obj = elem.asJsonObject
            val namespace = obj.get("namespace")?.asString ?: return@mapNotNull null
            val name = obj.get("name")?.asString ?: return@mapNotNull null

            // latestVersionInfo 包含 version 和 timestampUnixWithMS
            val versionInfo = obj.getAsJsonObject("latestVersionInfo")
            val version = versionInfo?.get("version")?.asString ?: ""
            val timestamp = versionInfo?.get("timestampUnixWithMS")?.asLong ?: 0L

            if (namespace.isNotEmpty() && name.isNotEmpty()) {
                SearchApiResponse(
                    groupId = namespace,
                    artifactId = name,
                    latestVersion = version,
                    timestamp = timestamp
                )
            } else {
                null
            }
        }
    }

    /** 转义 JSON 字符串中的特殊字符 */
    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
