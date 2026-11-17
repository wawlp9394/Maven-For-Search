package com.mavensearch.api

import com.mavensearch.model.Artifact
import com.mavensearch.model.SearchResult
import com.mavensearch.model.VersionInfo

/** 搜索 API 响应数据 */
data class SearchApiResponse(
    val groupId: String,
    val artifactId: String,
    val latestVersion: String,
    val timestamp: Long,
    /** 历史版本数量 (Solr 返回的 versionCount, 作为"流行度"排序的近似依据) */
    val versionCount: Int = 0
) {
    fun toSearchResult(): SearchResult =
        SearchResult(
            artifact = Artifact(groupId, artifactId, latestVersion, timestamp),
            versionCount = versionCount
        )
}

/** 版本 API 响应数据 */
data class VersionApiResponse(
    val version: String,
    val timestamp: Long
) {
    fun toVersionInfo(): VersionInfo = VersionInfo(version, timestamp)
}

/** 搜索 API 客户端接口 */
interface MavenSearchApiClient {
    /** 搜索 artifact */
    fun search(query: String, page: Int, pageSize: Int): List<SearchApiResponse>
}
/* 版本获取不在本接口: 版本列表统一由 MavenMetadataApi (maven-metadata.xml, 走配置镜像) 提供,
 * 每版本时间戳由 SearchMavenOrgApi (Sonatype Solr gav) 补充。 */
