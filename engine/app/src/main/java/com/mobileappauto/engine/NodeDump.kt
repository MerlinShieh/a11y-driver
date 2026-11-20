package com.mobileappauto.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 AccessibilityNodeInfo 树序列化成 JSON。
 * 字段命名尽量贴近 uiautomator2 的 dump_hierarchy，方便脚本迁移。
 */
object NodeDump {

    fun dump(maxDepth: Int = 64, maxNodes: Int = 4000, includeInvisible: Boolean = false): JSONObject {
        val svc = AutoAccessibilityService.instance
            ?: return JSONObject().put("error", "ACCESSIBILITY_NOT_READY")
        val root = svc.appRoot()
            ?: return JSONObject().put("error", "NO_WINDOW")

        val counter = intArrayOf(0)
        val tree = toJson(root, 0, maxDepth, counter, maxNodes, includeInvisible)

        return JSONObject()
            .put("ok", true)
            .put("package", root.packageName?.toString() ?: AutoAccessibilityService.currentPackage)
            .put("timestamp", System.currentTimeMillis())
            .put("nodeCount", counter[0])
            .put("root", tree)
    }

    private fun toJson(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        counter: IntArray,
        maxNodes: Int,
        includeInvisible: Boolean
    ): JSONObject {
        counter[0]++

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val o = JSONObject()
        o.put("class", node.className?.toString() ?: "")
        o.put("text", node.text?.toString() ?: "")
        o.put("desc", node.contentDescription?.toString() ?: "")
        o.put("hint", if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString() ?: "" else "")
        o.put("id", node.viewIdResourceName ?: "")
        o.put("pkg", node.packageName?.toString() ?: "")
        o.put("bounds", JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom))
        o.put("center", JSONArray().put(rect.centerX()).put(rect.centerY()))
        o.put("clickable", node.isClickable)
        o.put("longClickable", node.isLongClickable)
        o.put("scrollable", node.isScrollable)
        o.put("editable", node.isEditable)
        o.put("checkable", node.isCheckable)
        o.put("checked", node.isChecked)
        o.put("selected", node.isSelected)
        o.put("focused", node.isFocused)
        o.put("enabled", node.isEnabled)
        o.put("visible", node.isVisibleToUser)
        o.put("depth", depth)

        if (depth < maxDepth && counter[0] < maxNodes) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                if (counter[0] >= maxNodes) break
                val child = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    null
                } ?: continue
                if (!includeInvisible && !child.isVisibleToUser) continue
                children.put(toJson(child, depth + 1, maxDepth, counter, maxNodes, includeInvisible))
            }
            if (children.length() > 0) o.put("children", children)
        }

        return o
    }
}
