package com.mavensearch.ui

import org.junit.jupiter.api.Test
import javax.swing.JTextField
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证 IME 合成状态跟踪器的核心逻辑 (修复中文输入法搜索 bug 的关键).
 *
 * 直接驱动纯方法 handleImeTextChanged / handleDocumentChanged,
 * 避免构造 InputMethodEvent/DocumentEvent 的类路径问题.
 *
 * 覆盖场景:
 * 1. 普通文本输入: 文档变化立即触发回调
 * 2. IME 合成中: 半成品文本的文档变化被拦截, 不触发回调
 * 3. IME 提交候选词: 合成结束, 触发回调读到最终文本
 * 4. IME 取消合成 (Esc): 恢复触发
 * 5. 与 JTextField 文档监听器集成: 真实输入路径
 */
class ImeCompositionTrackerTest {

    @Test
    fun `plain text change triggers callback`() {
        var fired = 0
        val tracker = ImeCompositionTracker { fired++ }

        assertTrue(tracker.handleDocumentChanged(), "非合成状态文档变化应触发")
        assertEquals(1, fired)
    }

    @Test
    fun `composition blocks document trigger`() {
        var fired = 0
        val tracker = ImeCompositionTracker { fired++ }

        // 进入合成 (拼音缓冲 "chunwen" 未上屏)
        assertTrue(tracker.handleImeTextChanged(committedCount = 0, hasComposingText = true))
        // 合成中的文档变化 (半成品文本) 应被拦截
        assertFalse(tracker.handleDocumentChanged(), "合成中的文档变化不应触发")
        assertEquals(0, fired)
    }

    @Test
    fun `commit ends composition and triggers callback`() {
        var fired = 0
        val tracker = ImeCompositionTracker { fired++ }

        tracker.handleImeTextChanged(committedCount = 0, hasComposingText = true)
        // 提交候选词 (committed=2, 如 "纯文"), 同步触发便于断言
        assertFalse(tracker.handleImeTextChanged(committedCount = 2, hasComposingText = false, deferredCommit = false))
        assertEquals(1, fired, "提交后应触发一次回调")

        // 提交后恢复普通触发
        assertTrue(tracker.handleDocumentChanged())
        assertEquals(2, fired)
    }

    @Test
    fun `cancel composition restores triggering`() {
        var fired = 0
        val tracker = ImeCompositionTracker { fired++ }

        tracker.handleImeTextChanged(committedCount = 0, hasComposingText = true)
        // 取消合成: 无提交 + 空合成文本 (如按 Esc)
        assertFalse(tracker.handleImeTextChanged(committedCount = 0, hasComposingText = false, deferredCommit = false))
        assertTrue(tracker.handleDocumentChanged(), "取消合成后文档变化应恢复触发")
        assertEquals(1, fired)
    }

    @Test
    fun `end-to-end pinyin to hanzi search flow`() {
        // 模拟完整流程: 输入拼音 -> 合成中多次文档变化 -> 提交候选词 -> 最终文本搜索
        val queries = mutableListOf<String>()
        val field = JTextField()
        val tracker = installImeAwareTextTrigger(field) { queries.add(field.text) }

        // 1. 用户敲 "chunwen", IME 进入合成, Swing 文档临时显示拼音
        tracker.handleImeTextChanged(committedCount = 0, hasComposingText = true)
        field.document.insertString(0, "chunwen", null) // 触发 insertUpdate -> 应被拦截
        assertEquals(0, queries.size, "合成中的拼音不应触发搜索")

        // 2. 选词提交 "纯文本": 文档替换为中文, 提交事件触发搜索
        field.document.remove(0, field.document.length)
        field.document.insertString(0, "纯文本", null)
        tracker.handleImeTextChanged(committedCount = 3, hasComposingText = false, deferredCommit = false)
        assertEquals(listOf("纯文本"), queries, "提交后应以最终中文文本触发一次搜索")
    }
}
