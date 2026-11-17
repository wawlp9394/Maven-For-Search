package com.mavensearch.api

import com.google.gson.JsonParser
import com.intellij.util.io.HttpRequests
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Sonatype Solr API (仅版本时间戳查询)
 *
 * v1.0.9 修复: 端点从 search.maven.org 切换到 central.sonatype.com/solrsearch/select。
 * 两者协议相同 (Solr select), 但 Sonatype 域名下的索引是实时的 — 实测 hutool 最新
 * 5.8.48 可立即查到, 而 search.maven.org 仍停在 5.8.36 (延迟约 20 个月)。
 *
 * 本类只负责 core=gav 查询, 用于获取每个版本的独立发布时间戳
 * (maven-metadata.xml 只有全局 lastUpdated, 无每版本时间戳)。
 */
class SearchMavenOrgApi(
    private val timeoutMs: Int = 10000
) {
    companion object {
        private const val BASE_URL = "https://central.sonatype.com/solrsearch/select"

        /** 完整浏览器 UA (保持与搜索请求一致, 防止端点后续加严) */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    /**
     * 查询指定 artifact 的所有版本及发布时间戳
     *
     * 使用 core=gav, 按 g+a 精确匹配, 返回 v (版本号) 和 timestamp (发布时间) 字段。
     *
     * @param pageSize 最多返回的版本数
     */
    fun getVersions(groupId: String, artifactId: String, pageSize: Int): List<VersionApiResponse> {
        val solrQuery = "g:\"$groupId\" AND a:\"$artifactId\""
        val encodedQuery = URLEncoder.encode(solrQuery, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL?q=$encodedQuery&core=gav&rows=$pageSize&wt=json"

        val json = HttpRequests.request(url)
            .userAgent(USER_AGENT)
            .connectTimeout(timeoutMs)
            .readTimeout(timeoutMs)
            .readString()

        val root = JsonParser.parseString(json).asJsonObject
        val response = root.getAsJsonObject("response") ?: return emptyList()
        val docs = response.getAsJsonArray("docs") ?: return emptyList()

        return docs.map { doc ->
            val obj = doc.asJsonObject
            VersionApiResponse(
                version = obj.get("v")?.asString ?: "",
                timestamp = obj.get("timestamp")?.asLong ?: 0L
            )
        }.filter { it.version.isNotEmpty() }
    }
}
