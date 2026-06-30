package com.mavensearch.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 搜索源类型
 */
enum class SearchSource {
    /** 默认: search.maven.org 官方 API */
    MAVEN_CENTRAL,

    /** 备用: central.sonatype.com API */
    SONATYPE_CENTRAL,

    /** 自动回退: 优先 Sonatype (数据实时、结果稳定), 失败回退 Maven Central Solr (有索引延迟) */
    AUTO_FALLBACK
}

/**
 * Maven For Search 插件持久化配置
 */
@Service
@State(name = "MavenSearchSettings", storages = [Storage("maven-search.xml")])
class MavenSearchSettings : PersistentStateComponent<MavenSearchSettings.State> {

    data class State(
        /** 搜索源 */
        var searchSource: String = SearchSource.AUTO_FALLBACK.name,
        /** 防抖延迟 (毫秒) */
        var debounceDelayMs: Int = 300,
        /** 是否启用下载 jar 功能 (默认关) */
        var enableDownloadJar: Boolean = false,
        /** 是否启用添加到 pom.xml 功能 (默认关) */
        var enableAddToPom: Boolean = false,
        /** jar 下载目录 (相对项目根目录,默认 lib) */
        var jarDownloadDir: String = "lib",
        /** 每页结果数 */
        var pageSize: Int = 20,
        /** 网络超时 (秒) */
        var networkTimeoutSec: Int = 10
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** 获取搜索源枚举 */
    val searchSource: SearchSource
        get() = runCatching { SearchSource.valueOf(state.searchSource) }
            .getOrDefault(SearchSource.AUTO_FALLBACK)

    companion object {
        fun getInstance(): MavenSearchSettings =
            ApplicationManager.getApplication().getService(MavenSearchSettings::class.java)
    }
}
