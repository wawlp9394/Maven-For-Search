package com.mavensearch.api

import com.google.gson.JsonParser
import com.intellij.util.io.HttpRequests
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * search.maven.org Solr API (仅版本时间戳查询)
 *
 * v1.0.9 起搜索功能固定使用 SonatypeCentralApi (central.sonatype.com browse API,
 * 数据实时), 本类不再承担搜索职责, 仅保留 Solr core=gav 查询用于:
 * 获取每个版本的独立发布时间戳 (maven-metadata.xml 只有全局 lastUpdated, 无每版本时间戳)。
 *
 * 注意: Solr 索引有数月延迟, 最新版本可能查不到时间戳 (缺失部分由上层
 * MavenSearchService.fetchMissingTimestamps 用 HEAD Last-Modified 实时补全)。
 */
class SearchMavenOrgApi(
    private val timeoutMs: Int = 10000
) {
    companion object {
        private const val BASE_URL = "https://search.maven.org/solrsearch/select"
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
            .userAgent("Mozilla/5.0")
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
