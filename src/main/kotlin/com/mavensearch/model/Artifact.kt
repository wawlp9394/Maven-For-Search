package com.mavensearch.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Maven artifact 坐标模型
 */
data class Artifact(
    val groupId: String,
    val artifactId: String,
    val latestVersion: String = "",
    val timestamp: Long = 0L
) {
    /**
     * 格式化时间戳为可读日期
     */
    val formattedDate: String
        get() = if (timestamp > 0) {
            Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        } else {
            ""
        }

    /**
     * 生成 Maven dependency XML 片段
     */
    fun toDependencyXml(version: String = latestVersion): String {
        return buildString {
            appendLine("<dependency>")
            appendLine("    <groupId>$groupId</groupId>")
            appendLine("    <artifactId>$artifactId</artifactId>")
            appendLine("    <version>$version</version>")
            appendLine("</dependency>")
        }.trimEnd()
    }

    /**
     * 生成显示用的坐标字符串
     */
    fun toCoordinate(): String = "$groupId:$artifactId"
}
