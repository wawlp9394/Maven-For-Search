package com.mavensearch.model

/**
 * 版本信息
 */
data class VersionInfo(
    val version: String,
    val timestamp: Long = 0L
) {
    val formattedDate: String
        get() = if (timestamp > 0) {
            java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        } else {
            ""
        }
}
