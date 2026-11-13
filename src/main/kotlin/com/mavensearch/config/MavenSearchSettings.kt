package com.mavensearch.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 搜索结果排序方式 (通过 Sonatype browse API 的 sort 参数实现, 已 curl 实测验证)
 *
 * 目标: 在 IDE 内复刻 mvnrepository.com 的搜索体验 (按流行度排序),
 * 无需打开浏览器 — 数据源同为 Maven Central, 实时性一致。
 */
enum class SearchSort(val displayName: String, val apiValue: String?) {
    /** 相关性排序 (API 默认, 不传 sort 参数) */
    RELEVANCE("Relevance", null),

    /** 按流行度排序 (引用该命名空间的应用数量, 等效 mvnrepository 的 popularity 排序) */
    POPULARITY("Popularity", "nsPopularityAppCount"),

    /** 按最新发布时间排序 */
    NEWEST("Newest", "publishedDate");

    /** 下拉框显示可读名称而非枚举常量名 */
    override fun toString(): String = displayName
}

/**
 * 仓库镜像源 (用于 jar 下载、maven-metadata.xml、版本时间戳 HEAD 请求等仓库文件操作)
 *
 * 注意: 国内镜像 (阿里云/华为云/腾讯云) 只提供仓库文件代理, 不提供搜索 API,
 * 因此仅仓库文件类请求走镜像; 搜索 API 固定使用 central.sonatype.com browse
 * (通用 Maven 搜索 API 仅有 search.maven.org Solr 与 Sonatype browse 两家,
 * Solr 索引延迟数月已于 v1.0.9 移除, 现仅存 Sonatype 一家实时源)。
 */
enum class RepoMirror(val displayName: String, val baseUrl: String) {
    /** Maven Central 官方仓库 (全球 CDN, 实时性最好) */
    MAVEN_CENTRAL("Maven Central (official)", "https://repo1.maven.org/maven2"),

    /** 阿里云公共仓库镜像 (国内速度快) */
    ALIYUN("Aliyun (maven.aliyun.com)", "https://maven.aliyun.com/repository/public"),

    /** 华为云镜像 (国内速度快) */
    HUAWEI("Huawei Cloud (repo.huaweicloud.com)", "https://repo.huaweicloud.com/repository/maven"),

    /** 腾讯云镜像 (国内速度快) */
    TENCENT("Tencent Cloud (mirrors.cloud.tencent.com)", "https://mirrors.cloud.tencent.com/nexus/repository/maven-public");

    /** 下拉框显示可读名称而非枚举常量名 */
    override fun toString(): String = displayName
}

/**
 * Maven For Search 插件持久化配置
 */
@Service
@State(name = "MavenSearchSettings", storages = [Storage("maven-search.xml")])
class MavenSearchSettings : PersistentStateComponent<MavenSearchSettings.State> {

    data class State(
        /** 仓库镜像源 (jar 下载 / metadata / 时间戳等仓库文件请求使用) */
        var repoMirror: String = RepoMirror.MAVEN_CENTRAL.name,
        /** 搜索结果排序方式 */
        var searchSort: String = SearchSort.RELEVANCE.name,
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

    /** 获取仓库镜像源枚举 */
    val repoMirror: RepoMirror
        get() = runCatching { RepoMirror.valueOf(state.repoMirror) }
            .getOrDefault(RepoMirror.MAVEN_CENTRAL)

    /** 获取搜索排序枚举 */
    val searchSort: SearchSort
        get() = runCatching { SearchSort.valueOf(state.searchSort) }
            .getOrDefault(SearchSort.RELEVANCE)

    companion object {
        fun getInstance(): MavenSearchSettings =
            ApplicationManager.getApplication().getService(MavenSearchSettings::class.java)
    }
}
