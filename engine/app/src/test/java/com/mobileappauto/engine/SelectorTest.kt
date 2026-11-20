package com.mobileappauto.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选择器匹配逻辑的单元测试（工程规范第 6 条）。
 *
 * 只测纯逻辑，不依赖设备与 Android 运行时。
 */
class SelectorTest {

    private val node = NodeValues(
        text = "网络和互联网",
        desc = "打开网络设置",
        id = "com.android.settings:id/title",
        className = "android.widget.TextView",
        packageName = "com.android.settings",
        visible = true,
        clickable = false,
        enabled = true,
        checked = false,
        selected = false,
        focused = false,
        scrollable = false,
        editable = false
    )

    @Test
    fun `空选择器匹配任意节点`() {
        assertTrue(Selector().matchesValues(node))
    }

    @Test
    fun `文本精确匹配`() {
        assertTrue(Selector(text = "网络和互联网").matchesValues(node))
        assertFalse(Selector(text = "网络").matchesValues(node))
    }

    @Test
    fun `文本包含与前缀`() {
        assertTrue(Selector(textContains = "互联网").matchesValues(node))
        assertTrue(Selector(textStartsWith = "网络").matchesValues(node))
        assertFalse(Selector(textStartsWith = "互联网").matchesValues(node))
    }

    @Test
    fun `正则匹配`() {
        assertTrue(Selector(textMatches = "^网络.*网$").matchesValues(node))
        assertFalse(Selector(textMatches = "^登录$").matchesValues(node))
    }

    @Test
    fun `描述字段独立匹配`() {
        assertTrue(Selector(desc = "打开网络设置").matchesValues(node))
        assertTrue(Selector(descContains = "网络").matchesValues(node))
        assertFalse(Selector(desc = "网络和互联网").matchesValues(node))
    }

    @Test
    fun `id 与 className 正则`() {
        assertTrue(Selector(idMatches = ".*:id/title").matchesValues(node))
        assertTrue(Selector(classNameMatches = ".*TextView").matchesValues(node))
        assertFalse(Selector(classNameMatches = ".*Button").matchesValues(node))
    }

    @Test
    fun `状态谓词是与关系`() {
        assertTrue(Selector(clickable = false, enabled = true).matchesValues(node))
        assertFalse(Selector(clickable = true).matchesValues(node))
        assertFalse(Selector(checked = true).matchesValues(node))
        assertTrue(Selector(editable = false, scrollable = false).matchesValues(node))
    }

    @Test
    fun `不可见节点默认被过滤`() {
        val hidden = node.copy(visible = false)
        assertFalse(Selector(textContains = "互联网").matchesValues(hidden))
        assertTrue(
            Selector(textContains = "互联网", visibleOnly = false).matchesValues(hidden)
        )
    }

    @Test
    fun `index 不参与匹配`() {
        // index 只在结果列表中取第 N 个，不影响单节点是否命中
        assertTrue(Selector(textContains = "互联网", index = 3).matchesValues(node))
    }

    @Test
    fun `空选择器判定`() {
        assertTrue(Selector().isEmpty())
        assertFalse(Selector(text = "x").isEmpty())
        // 只有 index 不算有效选择器
        assertTrue(Selector(index = 2).isEmpty())
    }
}
