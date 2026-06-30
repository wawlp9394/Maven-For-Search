package com.mavensearch.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.JComponent

/**
 * Maven For Search 弹出对话框
 *
 * 通过快捷键 Ctrl+Shift+M 打开
 */
class SearchDialog(project: Project) : DialogWrapper(project) {

    private val searchPanel = SearchPanel(project)

    init {
        title = "Maven For Search"
        setModal(false)
        init()
    }

    override fun createCenterPanel(): JComponent {
        return searchPanel.panel.apply {
            preferredSize = java.awt.Dimension(600, 400)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return searchPanel.panel
    }

    override fun show() {
        super.show()
        searchPanel.requestFocus()
    }
}
