package com.mavensearch.api

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Maven Central 仓库元数据 API (repo1.maven.org)
 *
 * 使用 Maven Central 仓库的 maven-metadata.xml 获取实时版本数据。
 * 这是 Maven Central 的真实仓库元数据,实时更新,包含所有已发布的版本。
 *
 * URL 格式: https://repo1.maven.org/maven2/{groupIdPath}/{artifactId}/maven-metadata.xml
 * 例如: https://repo1.maven.org/maven2/cn/hutool/hutool-all/maven-metadata.xml
 *
 * 优势:
 * - 数据实时 (search.maven.org 的 Solr 索引有数月延迟, maven-metadata.xml 是仓库真实状态)
 * - 包含所有版本
 * - 公开可用,无需认证
 *
 * 局限:
 * - 只能查询已知 groupId:artifactId 的版本, 不支持关键词搜索
 * - 搜索功能仍需依赖 search.maven.org
 */
class MavenMetadataApi(
    private val timeoutMs: Int = 10000
) {
    private val log = logger<MavenMetadataApi>()

    companion object {
        private const val BASE_URL = "https://repo1.maven.org/maven2"
    }

    data class MetadataResult(
        val latestVersion: String,
        val releaseVersion: String,
        val lastUpdated: Long,
        val versions: List<String>
    )

    /**
     * 获取构件的元数据 (包含所有版本)
     *
     * 同步调用 (调用方应保证在后台线程执行, 例如 MavenSearchService.doGetVersions)。
     * 超时由 HTTP connectTimeout/readTimeout 控制。
     *
     * @return 元数据; 构件不存在、网络超时或解析失败时返回 null
     */
    fun getMetadata(groupId: String, artifactId: String): MetadataResult? {
        val groupPath = groupId.replace('.', '/')
        val url = "$BASE_URL/$groupPath/$artifactId/maven-metadata.xml"

        val xml = try {
            HttpRequests.request(url)
                .userAgent("Mozilla/5.0")
                .connectTimeout(timeoutMs)
                .readTimeout(timeoutMs)
                .readString()
        } catch (e: Exception) {
            log.info("maven-metadata.xml request failed for $groupId:$artifactId: ${e.message}")
            return null
        }

        return parseMetadata(xml)
    }

    /**
     * 获取所有版本 (按发布顺序倒序, 最新在前)
     *
     * 注意: maven-metadata.xml 只有一个全局 lastUpdated 时间戳, 没有每个版本的发布时间。
     * 若把 lastUpdated 赋给所有版本, 会导致每个版本都显示同一个 (最新版本的) 发布日期, 产生误导。
     * 因此这里 timestamp 统一设为 0, 调用方渲染时不显示日期。
     * 如需每个版本的独立发布时间, 应使用 SearchMavenOrgApi.getVersions (core=gav 查询, 有延迟)。
     *
     * @param pageSize 最多返回的版本数
     * @return 版本列表, 最新版本在前; 超时或构件不存在返回空列表
     */
    fun getVersions(groupId: String, artifactId: String, pageSize: Int = 500): List<VersionApiResponse> {
        val metadata = getMetadata(groupId, artifactId) ?: return emptyList()
        // maven-metadata.xml 中 versions 按发布顺序排列 (旧→新), 反转后最新在前
        // timestamp 设 0: metadata 没有每版本独立时间戳, 避免显示错误的统一日期
        return metadata.versions
            .reversed()
            .take(pageSize)
            .map { VersionApiResponse(version = it, timestamp = 0L) }
    }

    /**
     * 获取最新版本 (优先 latest, 其次 release)
     * @return 最新版本号, 如果构件不存在返回 null
     */
    fun getLatestVersion(groupId: String, artifactId: String): String? {
        val metadata = getMetadata(groupId, artifactId) ?: return null
        return when {
            metadata.latestVersion.isNotEmpty() -> metadata.latestVersion
            metadata.releaseVersion.isNotEmpty() -> metadata.releaseVersion
            metadata.versions.isNotEmpty() -> metadata.versions.last()
            else -> null
        }
    }

    /**
     * 解析 maven-metadata.xml
     *
     * XML 格式:
     * <metadata>
     *   <groupId>cn.hutool</groupId>
     *   <artifactId>hutool-all</artifactId>
     *   <versioning>
     *     <latest>5.8.46</latest>
     *     <release>5.8.46</release>
     *     <versions>
     *       <version>4.0.0</version>
     *       ...
     *     </versions>
     *     <lastUpdated>20260525102812</lastUpdated>
     *   </versioning>
     * </metadata>
     */
    private fun parseMetadata(xml: String): MetadataResult? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            // 安全配置: 禁用外部实体, 防止 XXE 攻击
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            } catch (e: Exception) {
                // 某些 XML 解析器实现可能不支持这些特性, 忽略
            }
            factory.isNamespaceAware = false

            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xml.byteInputStream())

            val root = doc.documentElement
            val versioning = getFirstChildElement(root, "versioning") ?: return null

            val latest = getFirstChildElement(versioning, "latest")?.textContent?.trim() ?: ""
            val release = getFirstChildElement(versioning, "release")?.textContent?.trim() ?: ""
            val lastUpdatedStr = getFirstChildElement(versioning, "lastUpdated")?.textContent?.trim() ?: "0"
            // lastUpdated 格式: yyyyMMddHHmmss, 转换为毫秒时间戳
            val lastUpdated = parseLastUpdated(lastUpdatedStr)

            val versionsList = getFirstChildElement(versioning, "versions")
            val versions = mutableListOf<String>()
            if (versionsList != null) {
                val versionNodes = versionsList.getElementsByTagName("version")
                for (i in 0 until versionNodes.length) {
                    val v = versionNodes.item(i).textContent?.trim()
                    if (!v.isNullOrEmpty()) versions.add(v)
                }
            }

            MetadataResult(
                latestVersion = latest,
                releaseVersion = release,
                lastUpdated = lastUpdated,
                versions = versions
            )
        } catch (e: Exception) {
            log.warn("Failed to parse maven-metadata.xml: ${e.message}")
            null
        }
    }

    /**
     * 解析 lastUpdated 字段 (格式: yyyyMMddHHmmss) 为毫秒时间戳
     * 例如: 20260525102812 -> 2026-05-25 10:28:12 的毫秒时间戳
     */
    private fun parseLastUpdated(value: String): Long {
        if (value.length < 14) return 0L
        return try {
            val year = value.substring(0, 4).toInt()
            val month = value.substring(4, 6).toInt()
            val day = value.substring(6, 8).toInt()
            val hour = value.substring(8, 10).toInt()
            val minute = value.substring(10, 12).toInt()
            val second = value.substring(12, 14).toInt()
            java.time.LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取指定父元素下的第一个直接子元素 (按标签名)
     * 注意: getElementsByTagName 会递归查找, 这里需要只查找直接子元素
     */
    private fun getFirstChildElement(parent: Element, tagName: String): Element? {
        val childNodes = parent.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node is Element && node.tagName == tagName) {
                return node
            }
        }
        return null
    }
}
