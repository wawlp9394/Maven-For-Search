package com.mavensearch.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.mavensearch.ui.SearchDialog

/**
 * 打开 Maven For Search 对话框的 Action
 *
 * 快捷键: Ctrl+Shift+M
 */
class OpenSearchDialogAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = SearchDialog(project)
        dialog.show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
