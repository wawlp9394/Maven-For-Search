package com.mavensearch.api

import com.google.gson.JsonParser
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Sonatype Central 搜索 API (central.sonatype.com 公开 Solr 端点)
 *
 * v1.0.9 修复背景:
 * central.sonatype.com 的 browse API (/api/internal/browse/components) 已被 Cloudflare
 * 升级为 TLS 指纹级 Bot 检测, curl / Node / JDK (HttpURLConnection 与 java.net.http.HttpClient)
 * 直连一律返回 400/403, 只有真实浏览器 TLS 栈能通过; 而 IDE 内嵌 JCEF 在部分 IDE 发行版
 * 中不存在 (NoClassDefFoundError: com.intellij.ui.jcef.JBCefApp), 无法作为兜底。
 *
 * 现改用同域名下的公开 Solr 端点:
 *   GET https://central.sonatype.com/solrsearch/select?q=<term>&rows=<n>&wt=json
 *
 * 该端点特性 (curl + JDK 实测):
 * - 索引实时: hutool 最新 5.8.48 可立即搜到 (search.maven.org 的 Solr 索引仍停在 5.8.36)
 * - 不校验 TLS 指纹: Java 直连返回 200, 无需浏览器/JCEF
 * - URL query 不允许出现空格 (含空格直接 400), 多关键词用 "|" (OR) 连接
 * - 通配符 "*" 有效: "token*" 为前缀匹配, "*token*" 为子串匹配
 * - 通配符查询按索引顺序返回 (没有相关性打分), 因此排序必须由客户端完成
 * - 不支持 sort 参数 (传 sort 会 400)
 *
 * 查询策略 (并行两次请求, 合并去重):
 * 1. 前缀查询 "tok1|tok2*": 有 Solr 相关性, 完整 artifact 名/前缀匹配的结果排前
 * 2. 子串查询 "*tok1*tok2*": 覆盖"只知道中间或结尾片段"的场景
 *    (例如输入 webmvc 能搜到 spring-boot-starter-webmvc)
 */
class SonatypeCentralApi(
    private val timeoutMs: Int = 10000,
    /** 每个查询抓取的结果数 (不做服务端分页, 由上层排序后再切片) */
    private val fetchSize: Int = DEFAULT_FETCH_SIZE
) : MavenSearchApiClient {

    companion object {
        private const val BASE_URL = "https://central.sonatype.com/solrsearch/select"

        /** 默认单个查询抓取条数: 前缀 + 子串合并后足够覆盖同名变体 */
        const val DEFAULT_FETCH_SIZE = 100

        /**
         * 纯单词片段 (如 "webmvc") 的子串查询抓取条数。
         * 子串通配没有相关性排序, 目标构件可能排在索引顺序的几百位
         * (实测 "*webmvc*" 中 spring-boot-starter-webmvc 位于第 300 位),
         * 因此对片段型输入多取一些, 由客户端重排后再切片。
         */
        private const val FRAGMENT_FETCH_SIZE = 300

        /** 完整浏览器 UA (端点本身不校验, 保持与官网一致的请求特征以防后续加严) */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * Solr 查询中需要剔除的字符 (只保留字母/数字/点/短横线/下划线)。
         * 这些字符属于 Solr 语法符号 (+ - && || ! ( ) { } [ ] ^ " ~ * ? : \ /) 或 URL 保留字符,
         * 直接透传会导致 400 或查询语义变化。通配符 "*" 由本类统一追加, 不来自用户输入。
         */
        private val INVALID_CHARS = Regex("[^A-Za-z0-9._\\-]")
    }

    /**
     * 搜索 artifact (返回合并后的原始结果, 不含分页)
     *
     * 分页由 MavenSearchService 在客户端完成: 需要先对完整结果集重排/排序, 再按页切片。
     *
     * @param query 搜索关键词, 例如 "spring-boot-starter-web"、"webmvc" 或 "cn.hutool:hutool-all"
     */
    override fun search(query: String, page: Int, pageSize: Int): List<SearchApiResponse> {
        val tokens = tokenize(query)
        // 关键词清洗后为空 (例如只输入了符号), 直接返回空结果, 不发无意义请求
        if (tokens.isEmpty()) return emptyList()

        // 前缀查询: 完整名/前缀匹配 (Solr 会按相关性排序)
        val prefixQuery = tokens.joinToString("|") + "*"
        // 子串查询: 中间/后缀片段匹配 (Solr 按索引顺序返回, 排序由客户端负责)
        val substringQuery = tokens.joinToString("") { "*$it" } + "*"
        // 纯单词 (无连字符) 更像"只知道一部分"的片段输入, 子串查询多取一些
        val substringRows = if (!query.contains('-') && !query.contains(' ')) {
            FRAGMENT_FETCH_SIZE
        } else {
            fetchSize
        }

        val prefixFuture = CompletableFuture.supplyAsync { runCatching { fetch(prefixQuery, fetchSize) }.getOrNull() }
        val substringFuture = CompletableFuture.supplyAsync { runCatching { fetch(substringQuery, substringRows) }.getOrNull() }

        val prefix = awaitOrNull(prefixFuture)
        val substring = awaitOrNull(substringFuture)
        if (prefix == null && substring == null) {
            // 两次请求都失败才报错, 让 UI 显示明确的网络错误 (不静默返回空列表)
            throw IOException("Search failed: both prefix and substring queries to central.sonatype.com failed")
        }

        // 合并去重 (同一 group:artifact 可能同时出现在两种查询结果里)
        return ((prefix ?: emptyList()) + (substring ?: emptyList()))
            .distinctBy { it.groupId + ":" + it.artifactId }
    }

    /** 等待并行请求结果; 超时或异常返回 null, 并取消未完成的任务 */
    private fun awaitOrNull(future: CompletableFuture<List<SearchApiResponse>?>): List<SearchApiResponse>? {
        return try {
            future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            null
        }
    }

    /** 单次 Solr 请求 */
    private fun fetch(solrQuery: String, rows: Int): List<SearchApiResponse> {
        val url = "$BASE_URL?q=${encode(solrQuery)}&rows=$rows&wt=json"
        val json = HttpRequests.request(url)
            .userAgent(USER_AGENT)
            .connectTimeout(timeoutMs)
            .readTimeout(timeoutMs)
            .readString()
        return parseSearchResponse(json)
    }

    /**
     * 把用户输入拆成安全的查询 token。
     * - "groupId:artifactId" 坐标: 只取 artifactId 部分
     *   (端点对带冒号的输入返回 404, g:+a: 组合会路由到版本级索引)
     * - 空白分词, 剔除会破坏 Solr 语法的字符
     */
    private fun tokenize(raw: String): List<String> {
        val trimmed = raw.trim()
        val target = if (trimmed.contains(':')) trimmed.substringAfter(':') else trimmed
        return target.split(Regex("\\s+"))
            .map { it.replace(INVALID_CHARS, "") }
            .filter { it.isNotEmpty() }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /** 解析 Solr 响应 JSON (解析失败上抛异常 — 网络错误不能被误显示为"无结果") */
    private fun parseSearchResponse(json: String): List<SearchApiResponse> {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw IOException("Invalid search response (expected JSON, got: ${json.take(120)})")
        }

        val response = root.getAsJsonObject("response")
            ?: throw IOException("Search response missing 'response' field: ${json.take(120)}")

        val docs = response.getAsJsonArray("docs") ?: return emptyList()

        return docs.mapNotNull { elem ->
            val obj = elem.asJsonObject
            val groupId = obj.get("g")?.asString ?: return@mapNotNull null
            val artifactId = obj.get("a")?.asString ?: return@mapNotNull null
            val latestVersion = obj.get("latestVersion")?.asString ?: ""
            val timestamp = obj.get("timestamp")?.asLong ?: 0L
            val versionCount = obj.get("versionCount")?.asInt ?: 0

            if (groupId.isNotEmpty() && artifactId.isNotEmpty()) {
                SearchApiResponse(
                    groupId = groupId,
                    artifactId = artifactId,
                    latestVersion = latestVersion,
                    timestamp = timestamp,
                    versionCount = versionCount
                )
            } else {
                null
            }
        }
    }
}
