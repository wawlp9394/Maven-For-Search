package com.mavensearch.api

import com.mavensearch.model.Artifact
import com.mavensearch.model.SearchResult
import com.mavensearch.model.VersionInfo

/** 搜索 API 响应数据 */
data class SearchApiResponse(
    val groupId: String,
    val artifactId: String,
    val latestVersion: String,
    val timestamp: Long
) {
    fun toSearchResult(): SearchResult =
        SearchResult(Artifact(groupId, artifactId, latestVersion, timestamp))
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

    /** 获取指定 artifact 的所有版本 */
    fun getVersions(groupId: String, artifactId: String, pageSize: Int = 200): List<VersionApiResponse>
}
