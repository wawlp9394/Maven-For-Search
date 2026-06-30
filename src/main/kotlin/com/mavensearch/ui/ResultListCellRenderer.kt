package com.mavensearch.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.mavensearch.model.SearchResult
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * 搜索结果列表项渲染器
 *
 * 显示格式:
 *   📦 org.springframework:spring-core
 *      Latest: 6.1.4 · 2024-03-14
 */
class ResultListCellRenderer : ListCellRenderer<SearchResult> {

    private val iconLabel = JLabel(AllIcons.Nodes.PpLib)

    private val nameLabel = JBLabel().apply {
        font = font.deriveFont(Font.BOLD, (font.size + 1).toFloat())
    }

    private val versionLabel = JBLabel().apply {
        font = font.deriveFont(Font.PLAIN, (font.size - 1).toFloat())
    }

    private val separatorLabel = JBLabel("·").apply {
        font = font.deriveFont(Font.PLAIN, (font.size - 1).toFloat())
    }

    private val dateLabel = JBLabel().apply {
        font = font.deriveFont(Font.PLAIN, (font.size - 1).toFloat())
    }

    // 图标 + 名称 (第一行)
    private val topPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
        isOpaque = false
        add(iconLabel)
        add(nameLabel)
    }

    // 版本 + 日期 (第二行, 缩进对齐图标)
    private val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(22)
        add(versionLabel)
        add(separatorLabel)
        add(dateLabel)
    }

    private val panel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        add(topPanel, BorderLayout.NORTH)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    override fun getListCellRendererComponent(
        list: JList<out SearchResult>,
        value: SearchResult?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        if (value == null) return panel

        nameLabel.text = "${value.groupId}:${value.artifactId}"
        versionLabel.text = "Latest: ${value.latestVersion}"
        dateLabel.text = value.formattedDate

        if (isSelected) {
            panel.background = list.selectionBackground
            panel.foreground = list.selectionForeground
            nameLabel.foreground = list.selectionForeground
            versionLabel.foreground = list.selectionForeground
            dateLabel.foreground = list.selectionForeground
            separatorLabel.foreground = list.selectionForeground
        } else {
            // 隔行变色 (浅色/深色主题自适应)
            panel.background = if (index % 2 == 0) list.background
                else JBColor(0xF7F8FA, 0x2D2E30)
            panel.foreground = list.foreground
            nameLabel.foreground = list.foreground
            versionLabel.foreground = JBColor(0x2E7D32, 0x7CB342)
            dateLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            separatorLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }

        panel.isOpaque = true
        return panel
    }
}
