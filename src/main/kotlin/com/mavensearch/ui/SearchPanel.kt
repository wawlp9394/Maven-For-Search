package com.mavensearch.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.TextTransferable
import com.mavensearch.action.AddToPomAction
import com.mavensearch.action.DownloadJarAction
import com.mavensearch.api.MavenSearchService
import com.mavensearch.config.MavenSearchSettings
import com.mavensearch.model.SearchResult
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Maven 搜索面板 (Tool Window 和 Dialog 共用)
 *
 * 功能:
 * - 搜索框 (防抖 300ms, 大小写不敏感, 模糊查询)
 * - 结果列表 (groupId:artifactId, latest version, date)
 * - 双击结果查看所有版本
 * - 无限滚动 (滚动到底部自动加载下一页)
 * - 操作按钮: 复制 XML / 查看版本 / 下载 jar / 添加到 pom
 */
class SearchPanel(private val project: Project) {

    private val searchService = MavenSearchService.getInstance()
    private val settings = MavenSearchSettings.getInstance()

    private val searchField = JBTextField().apply {
        emptyText.text = "Search Maven Central (e.g. spring-core, org.springframework:spring-core)..."
        addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                // Enter 由 InputMap 绑定处理 (见下), 这里只处理其他键的防抖搜索
                if (e.keyCode != KeyEvent.VK_ENTER) {
                    performSearchWithDebounce()
                }
            }
        })
        // 用 InputMap/ActionMap 绑定 Enter, 比 keyListener 更可靠
        // (JBTextField 的 keyListener 可能被默认 LAF 行为拦截)
        val enterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        getInputMap(JComponent.WHEN_FOCUSED).put(enterKey, "performSearch")
        actionMap.put("performSearch", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                performSearchImmediately()
            }
        })
    }

    private val listModel = DefaultListModel<SearchResult>()
    private val resultList = JBList(listModel).apply {
        cellRenderer = ResultListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        // 双击查看所有版本
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) {
                    showVersions()
                }
            }
        })
        addListSelectionListener { updateButtonStates() }
    }

    private val statusLabel = JBLabel("Type to search...").apply {
        border = BorderFactory.createEmptyBorder(3, 6, 3, 6)
    }

    private val copyButton = JButton("Copy XML").apply {
        icon = AllIcons.Actions.Copy
        toolTipText = "Copy Maven dependency XML to clipboard"
        addActionListener { copySelectedDependency() }
    }
    private val versionsButton = JButton("Versions").apply {
        icon = AllIcons.Actions.ListFiles
        toolTipText = "View all versions (or double-click a result)"
        addActionListener { showVersions() }
    }
    private val downloadButton = JButton("Download").apply {
        icon = AllIcons.Actions.Download
        toolTipText = "Download jar to project lib/ directory"
        addActionListener { downloadSelectedJar() }
    }
    private val addToPomButton = JButton("Add to POM").apply {
        icon = AllIcons.Nodes.PpJar
        toolTipText = "Add dependency to pom.xml"
        addActionListener { addToPom() }
    }

    private var currentPage = 0
    private var currentQuery = ""

    /** 是否正在加载 (防止滚动触发的重复请求) */
    private var isLoading = false

    /** 是否还有更多页可加载 (返回数 < pageSize 时置 false) */
    private var hasMore = true

    /**
     * 搜索请求代次 (单调递增), 用于丢弃过期请求的回调。
     *
     * 为什么需要: Alarm 只能取消"尚未执行"的请求, 一旦 doSearch 进入 executeOnPooledThread,
     * 它就脱离 Alarm 管理了。快速连续输入或打开/关闭版本弹窗时, 可能有多个后台请求并发,
     * 后返回的旧请求回调会覆盖新结果, 或迟到回调清空列表。
     * 每次新搜索/取消时递增 generation, 回调返回时检查是否仍为当前代次, 不匹配则丢弃。
     */
    @Volatile
    private var searchGeneration: Long = 0L

    /** 结果列表的滚动面板 (用于监听滚动到底部触发加载下一页) */
    private val scrollPane = JBScrollPane(resultList)

    /** 主面板 */
    val panel: JPanel = JPanel(BorderLayout(JBUI.scale(2), JBUI.scale(2))).apply {
        // 顶部: 搜索框 (带图标)
        val topPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = BorderFactory.createEmptyBorder(6, 6, 4, 6)
            add(JBLabel(AllIcons.Actions.Search), BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
        }
        add(topPanel, BorderLayout.NORTH)

        // 中间: 结果列表 (滚动浏览, 滚动到底部自动加载下一页)
        add(scrollPane, BorderLayout.CENTER)

        // 底部: 状态 + 操作按钮
        val bottomPanel = JPanel(BorderLayout(JBUI.scale(2), JBUI.scale(2))).apply {
            border = BorderFactory.createEmptyBorder(2, 6, 6, 6)

            // 状态栏
            add(statusLabel, BorderLayout.NORTH)

            // 操作按钮行
            val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(copyButton)
                add(versionsButton)
                add(downloadButton)
                add(addToPomButton)
            }
            add(actionPanel, BorderLayout.CENTER)
        }
        add(bottomPanel, BorderLayout.SOUTH)
    }

    init {
        updateButtonStates()
        updateConfigVisibility()
        setupInfiniteScroll()
    }

    /**
     * 配置无限滚动: 滚动到接近底部时自动加载下一页
     *
     * 通过监听垂直滚动条, 当剩余可滚动距离小于阈值时触发加载。
     * [isLoading] 和 [hasMore] 共同防止重复请求和无意义请求。
     */
    private fun setupInfiniteScroll() {
        val bar = scrollPane.verticalScrollBar
        bar.addAdjustmentListener {
            // 不依赖 valueIsAdjusting: 鼠标滚轮事件 valueIsAdjusting 为 false 会被误过滤。
            // 防重入由 loadNextPageIfNeeded 内部的 isLoading 标志位保证。
            val visibleAmount = bar.visibleAmount
            val max = bar.maximum
            // 距底部不足一屏的 1/4 时触发 (阈值, 避免刚好到底才加载的卡顿)
            if (max - visibleAmount - bar.value < visibleAmount / 4) {
                loadNextPageIfNeeded()
            }
        }
    }

    /** 滚动到底部时加载下一页 (若条件满足) */
    private fun loadNextPageIfNeeded() {
        if (isLoading || !hasMore || currentQuery.isEmpty()) return
        currentPage++
        isLoading = true
        // 翻页用当前 generation, 不递增 (避免主搜索回调被误判过期)
        val gen = searchGeneration
        statusLabel.text = "Loading more results..."
        statusLabel.foreground = JBColor.gray
        searchService.search(
            query = currentQuery,
            page = currentPage,
            onResult = { results -> handleSearchResult(results, isInitialSearch = false, gen = gen) },
            onError = { error -> handleSearchError(error, isInitialSearch = false, gen = gen) }
        )
    }

    /** 根据配置更新按钮可见性 */
    private fun updateConfigVisibility() {
        downloadButton.isVisible = settings.state.enableDownloadJar
        addToPomButton.isVisible = settings.state.enableAddToPom
    }

    /** 防抖搜索 */
    private fun performSearchWithDebounce() {
        val query = searchField.text.trim()
        currentQuery = query

        if (query.isEmpty()) {
            resetSearchState()
            // 清空时递增 generation, 让所有在途请求回调失效
            searchGeneration++
            listModel.clear()
            statusLabel.text = "Type to search..."
            statusLabel.foreground = JBColor.gray
            return
        }

        resetSearchState()
        // 新搜索递增 generation, 之前所有在途请求的回调都将被丢弃
        val gen = ++searchGeneration
        statusLabel.text = "Searching \"$query\"..."
        statusLabel.foreground = JBColor.gray
        searchService.searchWithDebounce(
            query = query,
            page = currentPage,
            onResult = { results -> handleSearchResult(results, isInitialSearch = true, gen = gen) },
            onError = { error -> handleSearchError(error, isInitialSearch = true, gen = gen) }
        )
    }

    /** 立即搜索 (Enter 键触发) */
    private fun performSearchImmediately() {
        val query = searchField.text.trim()
        if (query.isEmpty()) return
        currentQuery = query
        resetSearchState()
        // 立即搜索同样递增 generation, 取消防抖中所有在途请求
        val gen = ++searchGeneration
        statusLabel.text = "Searching \"$query\"..."
        statusLabel.foreground = JBColor.gray
        searchService.search(
            query = query,
            page = currentPage,
            onResult = { results -> handleSearchResult(results, isInitialSearch = true, gen = gen) },
            onError = { error -> handleSearchError(error, isInitialSearch = true, gen = gen) }
        )
    }

    /** 重置分页状态 (新搜索时调用) */
    private fun resetSearchState() {
        currentPage = 0
        isLoading = false
        hasMore = true
    }

    /**
     * 处理搜索结果 (EDT)
     *
     * @param isInitialSearch true=新搜索 (清空列表后填充首页); false=滚动加载下一页 (追加)
     * @param gen 发起请求时的 generation, 与当前 [searchGeneration] 不匹配则丢弃回调
     */
    private fun handleSearchResult(results: List<SearchResult>, isInitialSearch: Boolean, gen: Long) {
        SwingUtilities.invokeLater {
            // 丢弃过期请求的回调: 防止快速输入时旧请求覆盖新结果, 或弹窗后迟到请求清空列表
            if (gen != searchGeneration) return@invokeLater

            isLoading = false
            val pageSize = settings.state.pageSize
            // 返回数不足一页, 说明已到末尾
            if (results.size < pageSize) hasMore = false

            if (isInitialSearch) {
                listModel.clear()
            }
            results.forEach { listModel.addElement(it) }

            val total = listModel.size()
            statusLabel.text = when {
                total == 0 -> "No results for \"$currentQuery\". " +
                    "Try \"groupId:artifactId\" (e.g. org.springframework.boot:spring-boot-starter-web). " +
                    "New artifacts may not be indexed yet."
                hasMore -> "$total results loaded (scroll down for more)"
                else -> "$total results (all loaded)"
            }
            statusLabel.foreground = JBColor.gray
            updateButtonStates()
        }
    }

    /**
     * 处理搜索错误 (EDT)
     *
     * @param isInitialSearch true=新搜索出错; false=滚动加载出错
     * @param gen 发起请求时的 generation, 与当前 [searchGeneration] 不匹配则丢弃回调
     *
     * 注意: 新搜索出错时**不清空已有结果**, 保留上次成功的结果列表, 只更新状态栏显示错误。
     */
    private fun handleSearchError(error: String, isInitialSearch: Boolean, gen: Long) {
        SwingUtilities.invokeLater {
            if (gen != searchGeneration) return@invokeLater
            isLoading = false
            statusLabel.text = "Error: $error"
            statusLabel.foreground = JBColor.RED
            updateButtonStates()
        }
    }

    /** 更新按钮状态 */
    private fun updateButtonStates() {
        val hasSelection = resultList.selectedValue != null
        copyButton.isEnabled = hasSelection
        versionsButton.isEnabled = hasSelection
        downloadButton.isEnabled = hasSelection && settings.state.enableDownloadJar
        addToPomButton.isEnabled = hasSelection && settings.state.enableAddToPom
    }

    /** 复制选中的 dependency XML */
    private fun copySelectedDependency() {
        val selected = resultList.selectedValue ?: return
        val xml = selected.artifact.toDependencyXml()
        CopyPasteManager.getInstance().setContents(TextTransferable(xml as CharSequence))
        statusLabel.text = "Copied: ${selected.groupId}:${selected.artifactId}:${selected.latestVersion}"
        statusLabel.foreground = JBColor.green.darker()
    }

    /** 显示版本列表 */
    private fun showVersions() {
        val selected = resultList.selectedValue ?: return
        // 打开版本弹窗前取消待执行的防抖搜索请求, 并递增 generation
        // 让所有已派发到线程池但尚未回调的搜索请求回调全部失效,
        // 避免弹窗期间或关闭后迟到的回调 (尤其是空结果/错误) 清空当前结果列表
        searchService.cancel()
        searchGeneration++
        val dialog = VersionViewerDialog(project, selected.artifact)
        dialog.show()
    }

    /** 下载选中的 jar */
    private fun downloadSelectedJar() {
        val selected = resultList.selectedValue ?: return
        DownloadJarAction(project, selected.artifact).performDownload { msg ->
            SwingUtilities.invokeLater {
                statusLabel.text = msg
                statusLabel.foreground = JBColor.gray
            }
        }
    }

    /** 添加到 pom.xml */
    private fun addToPom() {
        val selected = resultList.selectedValue ?: return
        AddToPomAction(project, selected.artifact).performAdd { msg ->
            SwingUtilities.invokeLater {
                statusLabel.text = msg
                statusLabel.foreground = JBColor.gray
            }
        }
    }

    /** 当面板获得焦点时调用 */
    fun requestFocus() {
        searchField.requestFocusInWindow()
    }

    /** 当配置变更时刷新 */
    fun onSettingsChanged() {
        updateConfigVisibility()
        updateButtonStates()
    }
}
