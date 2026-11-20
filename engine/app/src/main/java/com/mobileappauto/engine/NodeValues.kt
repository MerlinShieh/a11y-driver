package com.mobileappauto.engine

/**
 * 节点属性的纯数据快照。
 *
 * 存在的意义：把匹配逻辑从 `AccessibilityNodeInfo`（Android 类，JVM 单测里不可用）
 * 解耦出来，这样选择器逻辑可以脱离设备做单元测试。
 */
data class NodeValues(
    val text: String = "",
    val desc: String = "",
    val id: String = "",
    val className: String = "",
    val packageName: String = "",
    val visible: Boolean = true,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false
)
