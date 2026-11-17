package com.mavensearch.model

/**
 * 搜索结果项
 */
data class SearchResult(
    val artifact: Artifact,
    val score: Double = 0.0,
    /** 历史版本数量 (用于"流行度"排序, 数值越大说明构件迭代越活跃) */
    val versionCount: Int = 0
) {
    val groupId: String get() = artifact.groupId
    val artifactId: String get() = artifact.artifactId
    val latestVersion: String get() = artifact.latestVersion
    val formattedDate: String get() = artifact.formattedDate
}
