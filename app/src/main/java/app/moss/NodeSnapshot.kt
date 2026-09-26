package app.moss

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

object NodeSnapshot {
    private const val MAX_NODES = 1500
    private const val MAX_DEPTH = 60

    fun capture(root: AccessibilityNodeInfo, maxNodes: Int = MAX_NODES): SnapNode {
        var budget = maxNodes
        val rect = Rect()

        fun copy(node: AccessibilityNodeInfo, depth: Int): SnapNode {
            budget--
            val children = ArrayList<SnapNode>()
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    if (budget <= 0) break
                    val child = node.getChild(i) ?: continue
                    children += copy(child, depth + 1)
                }
            }
            node.getBoundsInScreen(rect)
            return SnapNode(
                id = node.viewIdResourceName?.substringAfter(":id/"),
                className = node.className?.toString(),
                desc = node.contentDescription?.toString(),
                text = node.text?.toString(),
                state = if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString() else null,
                box = Box(rect.left, rect.top, rect.right, rect.bottom),
                selected = node.isSelected,
                clickable = node.isClickable,
                scrollable = node.isScrollable,
                visible = node.isVisibleToUser,
                children = children,
                ref = node,
            )
        }

        return copy(root, 0)
    }
}
