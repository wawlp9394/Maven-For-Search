package com.mavensearch.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * Maven For Search Tool Window 工厂
 *
 * 在 IDE 右侧停靠一个搜索面板
 */
class SearchToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val searchPanel = SearchPanel(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance()
            .createContent(searchPanel.panel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
