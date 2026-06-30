package com.mavensearch.api

import com.google.gson.JsonParser
import com.intellij.util.io.HttpRequests
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Maven Central 官方搜索 API (search.maven.org)
 *
 * 搜索策略 (双查询合并, 模仿 central.sonatype.com 的搜索体验):
 *
 * 1. 精确查询: a:"完整查询词" - 匹配 artifactId 完整名称
 *    例如 a:"spring-boot-starter-web" 能直接命中 Spring Boot 官方包
 *
 * 2. 模糊查询: 按连字符/空格分词, 每个 token 在 g/a/text 字段做 OR, token 之间做 AND
 *    例如 "spring-boot-web-starter" 分词为 [spring, boot, web, starter]
 *    -> (g:spring OR a:spring OR text:spring) AND (g:boot OR a:boot OR text:boot) AND ...
 *    这样即使输入顺序不同 (如 spring-boot-web-starter vs spring-boot-starter-web) 也能匹配
 *
 * 3. 合并去重: 精确匹配结果排在前面, 模糊匹配结果补充在后
 *
 * 支持 "g:a" 精确坐标查询 (带冒号)
 */
class SearchMavenOrgApi(
    private val timeoutMs: Int = 10000
) : MavenSearchApiClient {

    companion object {
        private const val BASE_URL = "https://search.maven.org/solrsearch/select"
    }

    override fun search(query: String, page: Int, pageSize: Int): List<SearchApiResponse> {
        // 第一页: 双查询合并; 其他页: 只用模糊查询 (避免重复)
        if (page == 0) {
            val exactResults = tryExactSearch(query, pageSize)
            val fuzzyResults = tryFuzzySearch(query, page, pageSize)
            return mergeResults(exactResults, fuzzyResults, pageSize)
        }
        return tryFuzzySearch(query, page, pageSize)
    }

    override fun getVersions(groupId: String, artifactId: String, pageSize: Int): List<VersionApiResponse> {
        // 查询所有版本: 使用 core=gav, 按 g+a 精确匹配, 返回 v 字段
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

    /**
     * 精确查询 artifactId
     * 例如查询 "spring-boot-starter-web" -> a:"spring-boot-starter-web"
     */
    private fun tryExactSearch(query: String, pageSize: Int): List<SearchApiResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.contains(":")) return emptyList()

        // 去掉通配符, 避免 Solr 语法错误
        val safeQuery = trimmed.replace("*", "").replace("?", "")
        if (safeQuery.isEmpty()) return emptyList()

        val solrQuery = "a:\"$safeQuery\""
        return executeSearch(solrQuery, 0, pageSize)
    }

    /**
     * 模糊查询 (分词后多字段搜索)
     */
    private fun tryFuzzySearch(query: String, page: Int, pageSize: Int): List<SearchApiResponse> {
        val solrQuery = buildFuzzySolrQuery(query)
        return executeSearch(solrQuery, page, pageSize)
    }

    /**
     * 执行 Solr 查询并解析响应
     *
     * @param page 页码 (从 0 开始), 内部转换为 Solr 的 start 偏移量 (start = page * rows)
     */
    private fun executeSearch(solrQuery: String, page: Int, rows: Int): List<SearchApiResponse> {
        val start = page * rows
        val encodedQuery = URLEncoder.encode(solrQuery, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL?q=$encodedQuery&start=$start&rows=$rows&wt=json"

        val json = try {
            HttpRequests.request(url)
                .userAgent("Mozilla/5.0")
                .connectTimeout(timeoutMs)
                .readTimeout(timeoutMs)
                .readString()
        } catch (e: Exception) {
            return emptyList()
        }

        val root = JsonParser.parseString(json).asJsonObject
        val response = root.getAsJsonObject("response") ?: return emptyList()
        val docs = response.getAsJsonArray("docs") ?: return emptyList()

        return docs.map { doc ->
            val obj = doc.asJsonObject
            SearchApiResponse(
                groupId = obj.get("g")?.asString ?: "",
                artifactId = obj.get("a")?.asString ?: "",
                latestVersion = obj.get("latestVersion")?.asString ?: "",
                timestamp = obj.get("timestamp")?.asLong ?: 0L
            )
        }.filter { it.groupId.isNotEmpty() && it.artifactId.isNotEmpty() }
    }

    /**
     * 合并精确查询和模糊查询结果
     * 精确匹配在前, 模糊匹配补充在后, 按 groupId:artifactId 去重
     */
    private fun mergeResults(
        exact: List<SearchApiResponse>,
        fuzzy: List<SearchApiResponse>,
        pageSize: Int
    ): List<SearchApiResponse> {
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<SearchApiResponse>()

        // 精确匹配优先
        for (item in exact) {
            val key = "${item.groupId}:${item.artifactId}"
            if (seen.add(key)) {
                merged.add(item)
                if (merged.size >= pageSize) return merged
            }
        }

        // 模糊匹配补充
        for (item in fuzzy) {
            val key = "${item.groupId}:${item.artifactId}"
            if (seen.add(key)) {
                merged.add(item)
                if (merged.size >= pageSize) return merged
            }
        }

        return merged
    }

    /**
     * 构造模糊查询的 Solr 查询字符串
     *
     * 规则:
     * 1. 空查询 -> *:* (匹配所有)
     * 2. 含冒号 "g:a" -> g:"g" AND a:"a" (精确坐标查询)
     * 3. 其他 -> 按连字符/空格分词, 每个词在 g/a/text 三个字段做 OR, 词之间做 AND
     *    例如 "spring-boot-web" ->
     *    (g:spring OR a:spring OR text:spring) AND
     *    (g:boot OR a:boot OR text:boot) AND
     *    (g:web OR a:web OR text:web)
     */
    private fun buildFuzzySolrQuery(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return "*:*"

        // 支持 "groupId:artifactId" 精确坐标查询
        if (trimmed.contains(":")) {
            val parts = trimmed.split(":", limit = 2)
            val g = parts[0].trim()
            val a = if (parts.size > 1) parts[1].trim() else ""
            return if (a.isNotEmpty()) {
                "g:\"$g\" AND a:\"$a\""
            } else {
                "g:\"$g\""
            }
        }

        // 按连字符和空格分词
        val tokens = trimmed.split('-', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (tokens.isEmpty()) return "*:*"

        // 单 token 直接搜索 (Solr dismax 自动处理)
        if (tokens.size == 1) {
            return tokens[0]
        }

        // 多 token: 每个 token 在 g/a/text 字段做 OR, token 之间做 AND
        // 这样 "spring-boot-web-starter" 能匹配 "spring-boot-starter-web"
        return tokens.joinToString(" AND ") { token ->
            "(g:$token OR a:$token OR text:$token)"
        }
    }
}
