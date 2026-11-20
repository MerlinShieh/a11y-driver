package com.mobileappauto.engine

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * 选择器：对齐 uiautomator2 / AutoJs 的写法，字段之间是「与」关系。
 *
 * 文本类：text / textContains / textMatches / textStartsWith
 * 描述类：desc / descContains / descMatches
 * 标识类：id / idMatches / className / classNameMatches / packageName
 * 状态类：clickable / enabled / checked / selected / focused / scrollable / editable
 * 位置类：index（匹配列表中的第 N 个，0 起）
 *
 * 匹配逻辑写在 `matchesValues`，以 `NodeValues` 为入参，便于单测；
 * `matches(node)` 只是把节点拍平成 `NodeValues`。
 */
data class Selector(
    val text: String? = null,
    val textContains: String? = null,
    val textMatches: String? = null,
    val textStartsWith: String? = null,

    val desc: String? = null,
    val descContains: String? = null,
    val descMatches: String? = null,

    val id: String? = null,
    val idMatches: String? = null,
    val className: String? = null,
    val classNameMatches: String? = null,
    val packageName: String? = null,

    val clickable: Boolean? = null,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val focused: Boolean? = null,
    val scrollable: Boolean? = null,
    val editable: Boolean? = null,

    val index: Int? = null,
    val visibleOnly: Boolean = true
) {
    private val reText = textMatches?.let { safeRegex(it) }
    private val reDesc = descMatches?.let { safeRegex(it) }
    private val reId = idMatches?.let { safeRegex(it) }
    private val reClass = classNameMatches?.let { safeRegex(it) }

    /** 纯逻辑匹配，可单测 */
    fun matchesValues(v: NodeValues): Boolean {
        if (visibleOnly && !v.visible) return false

        if (text != null && v.text != text) return false
        if (textContains != null && !v.text.contains(textContains)) return false
        if (textStartsWith != null && !v.text.startsWith(textStartsWith)) return false
        if (reText != null && !reText.containsMatchIn(v.text)) return false

        if (desc != null && v.desc != desc) return false
        if (descContains != null && !v.desc.contains(descContains)) return false
        if (reDesc != null && !reDesc.containsMatchIn(v.desc)) return false

        if (id != null && v.id != id) return false
        if (reId != null && !reId.containsMatchIn(v.id)) return false
        if (className != null && v.className != className) return false
        if (reClass != null && !reClass.containsMatchIn(v.className)) return false
        if (packageName != null && v.packageName != packageName) return false

        if (clickable != null && v.clickable != clickable) return false
        if (enabled != null && v.enabled != enabled) return false
        if (checked != null && v.checked != checked) return false
        if (selected != null && v.selected != selected) return false
        if (focused != null && v.focused != focused) return false
        if (scrollable != null && v.scrollable != scrollable) return false
        if (editable != null && v.editable != editable) return false

        return true
    }

    fun matches(node: AccessibilityNodeInfo): Boolean = matchesValues(snapshot(node))

    fun isEmpty(): Boolean =
        text == null && textContains == null && textMatches == null && textStartsWith == null &&
            desc == null && descContains == null && descMatches == null &&
            id == null && idMatches == null && className == null && classNameMatches == null &&
            packageName == null && clickable == null && enabled == null && checked == null &&
            selected == null && focused == null && scrollable == null && editable == null

    companion object {
        private fun safeRegex(p: String): Regex? = runCatching { Regex(p) }.getOrNull()

        fun snapshot(node: AccessibilityNodeInfo): NodeValues = NodeValues(
            text = node.text?.toString() ?: "",
            desc = node.contentDescription?.toString() ?: "",
            id = node.viewIdResourceName ?: "",
            className = node.className?.toString() ?: "",
            packageName = node.packageName?.toString() ?: "",
            visible = node.isVisibleToUser,
            clickable = node.isClickable,
            enabled = node.isEnabled,
            checked = node.isChecked,
            selected = node.isSelected,
            focused = node.isFocused,
            scrollable = node.isScrollable,
            editable = node.isEditable
        )

        private fun JSONObject.optStr(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.optBool(key: String): Boolean? =
            if (has(key) && !isNull(key)) getBoolean(key) else null

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) getInt(key) else null

        fun fromJson(o: JSONObject): Selector = Selector(
            text = o.optStr("text"),
            textContains = o.optStr("textContains"),
            textMatches = o.optStr("textMatches"),
            textStartsWith = o.optStr("textStartsWith"),
            desc = o.optStr("desc"),
            descContains = o.optStr("descContains"),
            descMatches = o.optStr("descMatches"),
            id = o.optStr("id"),
            idMatches = o.optStr("idMatches"),
            className = o.optStr("className"),
            classNameMatches = o.optStr("classNameMatches"),
            packageName = o.optStr("packageName"),
            clickable = o.optBool("clickable"),
            enabled = o.optBool("enabled"),
            checked = o.optBool("checked"),
            selected = o.optBool("selected"),
            focused = o.optBool("focused"),
            scrollable = o.optBool("scrollable"),
            editable = o.optBool("editable"),
            index = o.optIntOrNull("index"),
            visibleOnly = if (o.has("visibleOnly")) o.getBoolean("visibleOnly") else true
        )
    }
}
