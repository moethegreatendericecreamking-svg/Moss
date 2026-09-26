package app.moss

import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val SNAPCHAT_PACKAGE = "com.snapchat.android"

/** Screen-space rectangle. (android.graphics.Rect is a stub in JVM unit tests, so we use our own.) */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerY get() = (top + bottom) / 2
    val area get() = width.toLong() * height
}

enum class Tab(val title: String, val keywords: Set<String>) {
    MAP("Snap Map", setOf("map")),
    CHAT("Chat", setOf("chat")),
    CAMERA("Camera", setOf("camera")),
    // Older Snapchat builds called this tab "Discover"; some internal ids say "community".
    STORIES("Stories", setOf("stories", "discover", "community")),
    SPOTLIGHT("Spotlight", setOf("spotlight")),
}

/**
 * An immutable copy of one accessibility node. Snapshotting the tree once per pass keeps the
 * detection logic pure (and testable) and avoids repeated IPC calls into Snapchat's process.
 */
class SnapNode(
    val id: String? = null,
    val className: String? = null,
    val desc: String? = null,
    val text: String? = null,
    val state: String? = null,
    val box: Box,
    val selected: Boolean = false,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val visible: Boolean = true,
    val children: List<SnapNode> = emptyList(),
    val ref: AccessibilityNodeInfo? = null,
) {
    var parent: SnapNode? = null
        private set

    init {
        children.forEach { it.parent = this }
    }

    val labels: List<String> get() = listOfNotNull(desc, text).map { it.trim() }.filter { it.isNotEmpty() }

    fun walk(): Sequence<SnapNode> = sequence {
        yield(this@SnapNode)
        children.forEach { yieldAll(it.walk()) }
    }

    fun ancestors(): Sequence<SnapNode> = generateSequence(parent) { it.parent }

    /** The node Snapchat actually wants tapped: this one or its closest clickable ancestor/descendant. */
    fun clickTarget(): SnapNode? =
        (sequenceOf(this) + ancestors()).firstOrNull { it.clickable } ?: walk().firstOrNull { it.clickable }
}

class NavBar(val tabs: Map<Tab, SnapNode>, val top: Int)

enum class DetectedBy { SELECTED_STATE, PAGE_TITLE, PAGE_LAYOUT }

class SnapScreen(
    val root: SnapNode,
    val navBar: NavBar?,
    val activeTab: Tab?,
    val detectedBy: DetectedBy?,
    val discoverHeader: SnapNode?,
)

object SnapDetector {

    /** Section headers inside the Stories tab that mark where the Discover feed starts. */
    private val DISCOVER_HEADERS = setOf("discover")
    private val DISCOVER_HEADERS_ON_STORIES = setOf("discover", "for you")

    fun read(root: SnapNode): SnapScreen {
        val nav = findNavBar(root)
        var active: Tab? = null
        var by: DetectedBy? = null
        if (nav != null) {
            selectedTab(root, nav)?.let { active = it; by = DetectedBy.SELECTED_STATE }
            if (active == null) pageTitleTab(root, nav)?.let { active = it; by = DetectedBy.PAGE_TITLE }
            if (active == null) pageLayoutTab(root, nav)?.let { active = it; by = DetectedBy.PAGE_LAYOUT }
        }
        val header = if (nav != null && (active == null || active == Tab.STORIES)) {
            findDiscoverHeader(root, nav, active)
        } else {
            null
        }
        return SnapScreen(root, nav, active, by, header)
    }

    fun tabOf(node: SnapNode): Tab? {
        node.id?.let { id ->
            val tokens = id.lowercase().split('_', '-', '.')
            Tab.entries.firstOrNull { tab -> tab.keywords.any { it in tokens } }?.let { return it }
        }
        for (raw in node.labels) {
            val label = raw.lowercase()
            Tab.entries.firstOrNull { tab ->
                tab.keywords.any { kw -> label == kw || label.startsWith("$kw,") || label.startsWith("$kw ") }
            }?.let { return it }
        }
        return null
    }

    /**
     * Finds Snapchat's bottom navigation: at least three tab-like nodes (Map, Chat, Camera, Stories,
     * Spotlight) sitting on one row in the bottom part of the screen. Requiring a whole row keeps a
     * lone "Spotlight" entry — e.g. in the send-to list after taking a snap — from matching.
     */
    fun findNavBar(root: SnapNode): NavBar? {
        val screen = root.box
        if (screen.height <= 0 || screen.width <= 0) return null
        val minCenterY = screen.top + screen.height * 0.70
        val byTab = root.walk()
            .filter { n ->
                n.visible && n.box.height in 1..(screen.height / 5) && n.box.width in 1..(screen.width / 2) &&
                    n.box.centerY >= minCenterY
            }
            .mapNotNull { n -> tabOf(n)?.let { it to n } }
            .groupBy({ it.first }, { it.second })
        if (byTab.size < 3) return null
        // One representative per tab: the lowest match on screen (the bar hugs the bottom edge), and
        // of those the outermost, which is usually the tappable container.
        val reps = byTab.mapValues { (_, nodes) ->
            nodes.maxWith(compareBy<SnapNode> { it.box.bottom }.thenBy { it.box.area })
        }
        val medianY = reps.values.map { it.box.centerY }.sorted()[reps.size / 2]
        val row = reps.filterValues { abs(it.box.centerY - medianY) <= screen.height * 0.06 }
        if (row.size < 3) return null
        return NavBar(row, top = row.values.minOf { it.box.top })
    }

    fun isMarkedSelected(node: SnapNode): Boolean {
        if (node.selected) return true
        return listOfNotNull(node.state, node.desc).any { s ->
            val l = s.lowercase()
            "selected" in l && "not selected" !in l && "unselected" !in l
        }
    }

    private fun selectedTab(root: SnapNode, nav: NavBar): Tab? {
        val marked = nav.tabs.filter { (tab, rep) ->
            rep.walk().any(::isMarkedSelected) ||
                // Some builds put the selected flag on a sibling (icon vs. label) rather than a child.
                rep.parent?.children?.any { sib -> tabOf(sib) == tab && sib.walk().any(::isMarkedSelected) } == true
        }.keys
        return marked.singleOrNull()
    }

    /** Fallback: a visible page title such as "Stories" or "Spotlight" at the top of the screen. */
    private fun pageTitleTab(root: SnapNode, nav: NavBar): Tab? {
        val screen = root.box
        val found = root.walk()
            .filter { n ->
                n.visible && n.box.bottom <= nav.top && n.box.top < screen.top + screen.height * 0.15 &&
                    n.box.left >= screen.left && n.box.right <= screen.right
            }
            .flatMap { n -> n.labels.asSequence().map { it.lowercase() } }
            .mapNotNull { label ->
                when (label) {
                    "chat" -> Tab.CHAT
                    "stories", "discover" -> Tab.STORIES
                    "spotlight" -> Tab.SPOTLIGHT
                    else -> null
                }
            }
            .toSet()
        return found.singleOrNull()
    }

    /** Last resort: a large on-screen container whose view id names the page, e.g. a Spotlight player. */
    private fun pageLayoutTab(root: SnapNode, nav: NavBar): Tab? {
        val screen = root.box
        val found = root.walk()
            .filter { n ->
                n.visible && n.id != null && n.box.top < nav.top && n.box.area >= screen.area / 4 &&
                    n.box.left >= screen.left && n.box.right <= screen.right
            }
            .mapNotNull { n ->
                val tokens = n.id!!.lowercase().split('_', '-', '.')
                when {
                    "spotlight" in tokens -> Tab.SPOTLIGHT
                    "discover" in tokens -> Tab.STORIES
                    else -> null
                }
            }
            .toSet()
        return found.singleOrNull()
    }

    private fun findDiscoverHeader(root: SnapNode, nav: NavBar, active: Tab?): SnapNode? {
        val headers = if (active == Tab.STORIES) DISCOVER_HEADERS_ON_STORIES else DISCOVER_HEADERS
        val screen = root.box
        return root.walk()
            .filter { n ->
                n.visible && n.box.bottom <= nav.top && n.box.height in 1..(screen.height * 15 / 100) &&
                    n.labels.any { it.lowercase() in headers }
            }
            .minByOrNull { it.box.top }
    }
}

/**
 * Decides which part of the screen the Discover curtain should cover.
 *
 * While the "Discover" header is on screen the curtain runs from the header down to the nav bar.
 * Once the user scrolls far enough that the header leaves the top of the list, the whole list is
 * Discover content, so the curtain covers all of it — for as long as that same list stays visible.
 */
class DiscoverTracker {
    private class Memory(val listId: String?, val listBox: Box, val headerTop: Int)

    private var memory: Memory? = null

    fun reset() {
        memory = null
    }

    /** Scrollable list that holds the Discover feed, if known (used to scroll it back up). */
    var list: SnapNode? = null
        private set

    fun update(screen: SnapScreen): Box? {
        list = null
        val nav = screen.navBar ?: return null
        val active = screen.activeTab
        if (active != null && active != Tab.STORIES) {
            memory = null
            return null
        }
        val root = screen.root.box
        val header = screen.discoverHeader
        if (header != null) {
            val scroller = header.ancestors().firstOrNull { it.scrollable }
            list = scroller
            memory = scroller?.let { Memory(it.id, it.box, header.box.top) }
            val top = max(header.box.top, root.top)
            return if (top < nav.top) Box(root.left, top, root.right, nav.top) else null
        }
        val mem = memory ?: return null
        val scroller = screen.root.walk().firstOrNull { n ->
            n.visible && n.scrollable && n.id == mem.listId && n.box == mem.listBox
        } ?: return null
        if (mem.headerTop > scroller.box.top + scroller.box.height / 2) {
            // The header left through the bottom: the user scrolled back up to friends' stories.
            memory = null
            return null
        }
        list = scroller
        val bottom = min(scroller.box.bottom, nav.top)
        return if (scroller.box.top < bottom) Box(root.left, scroller.box.top, root.right, bottom) else null
    }
}
