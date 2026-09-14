package com.mavensearch.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.TextTransferable
import com.mavensearch.action.DownloadJarAction
import com.mavensearch.api.MavenSearchService
import com.mavensearch.config.MavenSearchSettings
import com.mavensearch.model.Artifact
import com.mavensearch.model.VersionInfo
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.ListCellRenderer
import javax.swing.event.ListSelectionListener

/**
 * 版本查看对话框
 *
 * 显示指定 artifact 的所有历史版本, 支持:
 * - 一次性显示全部版本 (无需翻页, 滚动浏览)
 * - 版本过滤搜索 (在全部版本中实时过滤, 输入即过滤)
 * - 双击复制 dependency XML
 * - 下载指定版本 jar (可配置)
 * - 最新版本高亮
 */
class VersionViewerDialog(
    private val project: Project,
    private val artifact: Artifact
) : DialogWrapper(project) {

    private val searchService = MavenSearchService.getInstance()
    private val settings = MavenSearchSettings.getInstance()

    /** 全部已加载版本 (最新在前) */
    private var allVersions: List<VersionInfo> = emptyList()

    /** 列表数据模型 (已过滤后的全部版本) */
    private val listModel = DefaultListModel<VersionInfo>()

    /** 选中变化监听器 (用于联动按钮状态) */
    private val selectionListener = ListSelectionListener { updateButtonStates() }

    private val filterField = JBTextField().apply {
        emptyText.text = "Filter versions (e.g. 5.8, 2.x, 3.5.3) - 在全部版本中实时过滤"
        // 同 SearchPanel 搜索框: IME 友好的文本变化触发, 修复中文输入法过滤 bug
        installImeAwareTextTrigger(this) { applyFilter() }
    }

    private val versionList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = VersionListCellRenderer()
        // 选中变化时联动更新复制/下载按钮的启用状态
        addListSelectionListener(selectionListener)
        // 双击复制 XML 并关闭弹窗
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) {
                    if (copySelectedVersion()) {
                        doCancelAction()
                    }
                }
            }
        })
        // 保留选中行可见的滚动行数
        visibleRowCount = 20
    }

    private val statusLabel = JBLabel("Loading versions...").apply {
        border = JBUI.Borders.empty(2, 4)
    }

    private val copyButton = JButton("Copy XML").apply {
        toolTipText = "Copy Maven dependency XML for selected version (or double-click a version)"
        addActionListener { copySelectedVersion() }
    }

    private val downloadButton = JButton("Download jar").apply {
        toolTipText = "Download selected version jar to project lib/"
        addActionListener { downloadSelectedVersion() }
    }

    init {
        title = "Versions — ${artifact.groupId}:${artifact.artifactId}"
        init()
        loadVersions()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4)))

        // 头部: 构件坐标 + 提示
        val headerPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(4, 2, 4, 2)
            add(JBLabel("<html><b style='font-size:13px'>${artifact.groupId}:${artifact.artifactId}</b></html>"), BorderLayout.WEST)
            add(JBLabel("<html><font color='gray'>Double-click a version to copy XML</font></html>"), BorderLayout.EAST)
        }
        panel.add(headerPanel, BorderLayout.NORTH)

        // 中间: 过滤框 + 版本列表 (滚动浏览, 无分页)
        val centerPanel = JPanel(BorderLayout(JBUI.scale(2), JBUI.scale(2)))
        centerPanel.add(filterField, BorderLayout.NORTH)
        centerPanel.add(JBScrollPane(versionList), BorderLayout.CENTER)
        panel.add(centerPanel, BorderLayout.CENTER)

        // 底部: 操作按钮 + 状态
        val bottomPanel = JPanel(BorderLayout(JBUI.scale(2), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(4, 2, 2, 2)

            val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(copyButton)
                if (settings.state.enableDownloadJar) {
                    add(downloadButton)
                }
            }
            add(actionPanel, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.SOUTH)
        }
        panel.add(bottomPanel, BorderLayout.SOUTH)

        panel.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(440))
        return panel
    }

    override fun createActions(): Array<javax.swing.Action> {
        return arrayOf(cancelAction)
    }

    /** 加载所有版本 (后台请求) */
    private fun loadVersions() {
        statusLabel.text = "Loading versions for ${artifact.groupId}:${artifact.artifactId}..."
        statusLabel.foreground = JBColor.gray
        copyButton.isEnabled = false
        downloadButton.isEnabled = false

        searchService.getVersions(
            groupId = artifact.groupId,
            artifactId = artifact.artifactId,
            onResult = { versions ->
                allVersions = versions
                applyFilter()
                statusLabel.text = if (versions.isEmpty()) {
                    "No versions found for ${artifact.groupId}:${artifact.artifactId}"
                } else {
                    "${versions.size} versions loaded - 在上方输入版本号可即时过滤"
                }
                statusLabel.foreground = JBColor.gray
                updateButtonStates()
            },
            onError = { error ->
                listModel.clear()
                statusLabel.text = "Error: $error"
                statusLabel.foreground = JBColor.RED
            }
        )
    }

    /**
     * 根据过滤框内容过滤全部版本, 并一次性渲染到列表
     *
     * 过滤基于 [allVersions] (全部版本), 而非当前已显示的版本,
     * 因此用户无需先翻页找版本, 直接输入即可命中任意历史版本。
     * JBList 使用 ListCellRenderer 虚拟渲染, 即使几百条数据也只渲染可见行, 性能无忧。
     */
    private fun applyFilter() {
        val filter = filterField.text.trim().lowercase()
        val filtered = if (filter.isEmpty()) {
            allVersions
        } else {
            allVersions.filter { it.version.lowercase().contains(filter) }
        }

        listModel.clear()
        filtered.forEach { listModel.addElement(it) }

        // 过滤后更新状态文本, 让用户知道过滤命中数
        if (filter.isNotEmpty() && allVersions.isNotEmpty()) {
            statusLabel.text = "${filtered.size} / ${allVersions.size} versions match \"$filter\""
            statusLabel.foreground = JBColor.gray
        }

        updateButtonStates()
    }

    /** 更新按钮启用状态 (依据是否有选中项) */
    private fun updateButtonStates() {
        val hasSelection = versionList.selectedValue != null
        copyButton.isEnabled = hasSelection
        downloadButton.isEnabled = hasSelection && settings.state.enableDownloadJar
    }

    /**
     * 复制选中版本的 dependency XML 到剪贴板, 并弹出 IDE 通知提示。
     * @return true 表示复制成功 (有选中项); false 表示无选中项 (调用方可据此决定是否关闭弹窗)
     */
    private fun copySelectedVersion(): Boolean {
        val selected = versionList.selectedValue ?: return false
        val xml = artifact.toDependencyXml(selected.version)
        CopyPasteManager.getInstance().setContents(TextTransferable(xml as CharSequence))
        statusLabel.text = "Copied: ${artifact.groupId}:${artifact.artifactId}:${selected.version}"
        statusLabel.foreground = JBColor.green.darker()
        // 通过 IDE 通知系统弹出"已复制到剪贴板"提示 (右下角气泡, 自动消失)
        val coordinate = "${artifact.groupId}:${artifact.artifactId}:${selected.version}"
        Notifications.Bus.notify(
            Notification(
                "Maven For Search",
                "已复制到剪贴板",
                coordinate,
                NotificationType.INFORMATION
            ),
            project
        )
        return true
    }

    /** 下载选中版本的 jar */
    private fun downloadSelectedVersion() {
        val selected = versionList.selectedValue ?: return
        val versionedArtifact = artifact.copy(latestVersion = selected.version)
        DownloadJarAction(project, versionedArtifact).performDownload { msg ->
            SwingUtilities.invokeLater {
                statusLabel.text = msg
                statusLabel.foreground = JBColor.gray
            }
        }
    }

    /**
     * 版本列表渲染器
     *
     * 显示格式:
     *   [LATEST] 5.8.46    2026-05-25
     *   5.8.45             2026-05-20
     */
    private class VersionListCellRenderer : ListCellRenderer<VersionInfo> {
        private val label = JBLabel()

        override fun getListCellRendererComponent(
            list: JList<out VersionInfo>,
            value: VersionInfo?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): JComponent {
            if (value == null) return label

            val datePart = if (value.formattedDate.isNotEmpty()) {
                "    <font color='gray'>${value.formattedDate}</font>"
            } else ""

            // 第一个版本 (最新) 加 [LATEST] 标记
            val latestTag = if (index == 0) {
                "<font color='#67A7FF'><b>[LATEST]</b></font> "
            } else {
                ""
            }

            label.text = "<html>$latestTag<b>${value.version}</b>$datePart</html>"

            if (isSelected) {
                label.background = list.selectionBackground
                label.foreground = list.selectionForeground
            } else {
                label.background = list.background
                label.foreground = list.foreground
            }
            label.isOpaque = true
            label.border = JBUI.Borders.empty(3, 10)
            return label
        }
    }
}
