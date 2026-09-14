package com.mavensearch.ui

import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/**
 * IME (中文输入法) 合成状态跟踪器.
 *
 * 背景 (修复中文输入法搜索 bug):
 * - 旧实现用 KeyListener.keyReleased 触发搜索. 中文输入法下存在两个问题:
 *   1. 合成期间 (拼音缓冲未上屏) 每次按键都触发 keyReleased (keyCode=VK_PROCESSKEY),
 *      此时 Swing 文档里是半成品拼音文本, 搜索词被拼音/已提交字符交错分割;
 *   2. 用户选词提交中文时走 InputMethodEvent, 往往不触发 keyReleased,
 *      导致提交后的完整文本不会触发搜索 (必须再敲一个英文字符才行).
 *
 * 本类改用 DocumentListener + InputMethodListener 组合:
 * - 通过 IME 事件 (committedCharacterCount / 合成文本) 维护"正在合成"状态标志;
 * - 合成中的文档变化直接跳过, 不触发回调;
 * - 提交候选词 (committedCharacterCount > 0) 后, 在 EDT 下一轮再触发一次回调,
 *   确保读到已写入文档的最终文本.
 *
 * 普通英文输入 / 粘贴: 不经过 IME 合成, composing 恒为 false, 文档变化即触发回调。
 *
 * 核心状态逻辑在 [handleImeTextChanged] / [handleDocumentChanged] 两个纯方法中,
 * 监听器回调只做事件字段解包, 便于单元测试直接驱动.
 */
internal class ImeCompositionTracker(private val onTextSettled: () -> Unit) :
    DocumentListener, InputMethodListener {

    /** 是否处于 IME 合成中 (拼音缓冲未上屏) */
    private var composing = false

    /** 当前是否处于合成状态 (测试/调试用) */
    val isComposing: Boolean get() = composing

    /**
     * 文档变化的核心处理: 合成中跳过 (半成品文本), 否则触发回调.
     * @return true 表示已触发回调
     */
    fun handleDocumentChanged(): Boolean {
        // 合成中的文档变化是半成品文本 (如拼音 "chunwen"), 跳过, 等提交后再触发
        if (composing) return false
        onTextSettled()
        return true
    }

    /**
     * IME 文本事件的核心处理.
     *
     * @param committedCount 本次提交的字符数 (>0 表示候选词上屏, 合成结束)
     * @param hasComposingText 事件中是否携带非空合成文本 (拼音缓冲)
     * @param deferredCommit 提交后是否用 invokeLater 延迟触发 (生产路径为 true,
     *   确保文档已写入最终文本; 测试可传 false 同步验证)
     * @return 处理后的合成状态
     */
    fun handleImeTextChanged(
        committedCount: Int,
        hasComposingText: Boolean,
        deferredCommit: Boolean = true
    ): Boolean {
        when {
            // 有提交字符: 候选词上屏, 合成结束。文档更新可能先于本监听器完成,
            // 用 invokeLater 确保读到最终文本后再触发搜索/过滤
            committedCount > 0 -> {
                composing = false
                if (deferredCommit) SwingUtilities.invokeLater(onTextSettled) else onTextSettled()
            }
            // 无提交但有合成文本: 进入/继续合成 (拼音缓冲), 标记 composing 拦截文档触发
            hasComposingText -> composing = true
            // 无提交且合成文本为空: 合成结束但未提交 (如按 Esc 取消), 恢复触发
            else -> composing = false
        }
        return composing
    }

    // ---- DocumentListener: 文本变化时, 非合成状态才触发回调 ----

    override fun insertUpdate(e: DocumentEvent) { handleDocumentChanged() }
    override fun removeUpdate(e: DocumentEvent) { handleDocumentChanged() }
    override fun changedUpdate(e: DocumentEvent) { handleDocumentChanged() }

    // ---- InputMethodListener: 维护合成状态 ----

    override fun inputMethodTextChanged(e: InputMethodEvent) {
        val text = e.text
        handleImeTextChanged(
            committedCount = e.committedCharacterCount,
            hasComposingText = text != null && text.endIndex > text.beginIndex
        )
    }

    override fun caretPositionChanged(e: InputMethodEvent) {
        // 光标位置变化与搜索无关, 忽略
    }
}

/**
 * 为文本组件安装 "IME 友好" 的文本变化触发器.
 *
 * @param component 目标文本组件 (JBTextField 等)
 * @param onTextSettled 文本稳定后的回调 (EDT 线程), 调用方在此读取 component 文本并执行搜索/过滤
 */
internal fun installImeAwareTextTrigger(component: JTextComponent, onTextSettled: () -> Unit): ImeCompositionTracker {
    val tracker = ImeCompositionTracker(onTextSettled)
    component.document.addDocumentListener(tracker)
    component.addInputMethodListener(tracker)
    return tracker
}
