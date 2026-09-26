package app.moss

import android.content.Context
import java.io.File

/**
 * A plain-text dump of Snapchat's screen structure (view ids, short labels, flags, bounds).
 * Snapchat changes its layout often; this report is what's needed to update the detection rules.
 * It never leaves the phone unless the user shares it themselves.
 */
object LayoutReport {
    private const val FILE_NAME = "layout-report.txt"
    private const val MAX_SNAPSHOTS = 12
    private const val MAX_LINES = 700

    private val seen = HashSet<Int>()
    private var count = 0

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun start(context: Context, header: String) {
        seen.clear()
        count = 0
        file(context).writeText(header + "\n")
    }

    fun append(context: Context, screen: SnapScreen) {
        if (count >= MAX_SNAPSHOTS) return
        val body = render(screen.root)
        if (!seen.add(body.hashCode())) return
        count++
        file(context).appendText("\n=== snapshot $count · ${summarize(screen)}\n$body")
    }

    fun summarize(screen: SnapScreen): String {
        val nav = screen.navBar?.tabs?.keys?.joinToString(", ") { it.title } ?: "not found"
        val active = screen.activeTab?.let { "${it.title} (${screen.detectedBy!!.name.lowercase()})" } ?: "unknown"
        val header = if (screen.discoverHeader != null) "yes" else "no"
        val covered = if (screen.discoverArea != null) "yes" else "no"
        val story = when {
            screen.unfollowedStoryOpen -> " · story open: not followed"
            screen.storyViewerOpen -> " · story open"
            else -> ""
        }
        return "nav bar: $nav · active tab: $active · Discover header: $header · Discover on screen: $covered$story"
    }

    fun render(root: SnapNode): String {
        val out = StringBuilder()
        var lines = 0
        fun line(node: SnapNode, depth: Int) {
            if (lines++ >= MAX_LINES) return
            out.append("  ".repeat(depth.coerceAtMost(30)))
            out.append(node.className?.substringAfterLast('.') ?: "?")
            node.id?.let { out.append(" #").append(it) }
            node.desc?.let { out.append(" desc=\"").append(it.clip(48)).append('"') }
            node.text?.let { out.append(" text=\"").append(it.clip(24)).append('"') }
            node.state?.let { out.append(" state=\"").append(it.clip(24)).append('"') }
            val flags = buildList {
                if (node.selected) add("sel")
                if (node.clickable) add("clk")
                if (node.scrollable) add("scr")
                if (!node.visible) add("hidden")
            }
            if (flags.isNotEmpty()) out.append(" [").append(flags.joinToString(" ")).append(']')
            val b = node.box
            out.append(" (").append(b.left).append(',').append(b.top).append(',')
                .append(b.right).append(',').append(b.bottom).append(")\n")
            node.children.forEach { line(it, depth + 1) }
        }
        line(root, 0)
        if (lines > MAX_LINES) out.append("… truncated\n")
        return out.toString()
    }

    private fun String.clip(max: Int): String {
        val oneLine = replace('\n', ' ').replace('"', '\'')
        return if (oneLine.length <= max) oneLine else oneLine.take(max - 1) + "…"
    }
}
