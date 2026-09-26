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
    /** Part of the screen showing the Discover feed, which the curtain should cover. */
    val discoverArea: Box? = null,
    /** Scrollable list holding the Discover feed (scrolled back up when the curtain is swiped). */
    val discoverList: SnapNode? = null,
    /** A full-screen story is playing (Snapchat's "opera" viewer). */
    val storyViewerOpen: Boolean = false,
    /** ...and it's from an account the user doesn't follow (it offers an "Add" button). */
    val unfollowedStoryOpen: Boolean = false,
    /** The Stories tab's main vertical list (friends, Following, Discover), when it's on screen. */
    val storiesList: SnapNode? = null,
    /** The visible part of [storiesList], covered completely while it scrolls. */
    val storiesListArea: Box? = null,
)

object SnapDetector {

    /** Section headers inside the Stories tab that mark where the Discover feed starts. */
    private val DISCOVER_HEADERS = setOf("discover")
    private val DISCOVER_HEADERS_ON_STORIES = setOf("discover", "for you")

    /** Labels the viewer's follow button can have once you already follow the account. */
    private val FOLLOWING_LABELS = listOf("subscribed", "following", "added")

    fun read(root: SnapNode): SnapScreen {
        val nav = findNavBar(root)
        var active: Tab? = null
        var by: DetectedBy? = null
        if (nav != null) {
            selectedTab(root, nav)?.let { active = it; by = DetectedBy.SELECTED_STATE }
            if (active == null) pageTitleTab(root, nav)?.let { active = it; by = DetectedBy.PAGE_TITLE }
            if (active == null) pageLayoutTab(root, nav)?.let { active = it; by = DetectedBy.PAGE_LAYOUT }
        }
        val viewer = findStoryViewer(root)
        val onStories = nav != null && (active == null || active == Tab.STORIES)
        val header = if (onStories) findDiscoverHeader(root, nav!!, active) else null
        // With a story open on top, the feed underneath isn't visible, so there's nothing to cover.
        val discover = if (onStories && viewer == null) findDiscover(root, nav!!, header) else null
        val list = if (active == Tab.STORIES && viewer == null) findStoriesList(root, nav!!) else null
        return SnapScreen(
            root, nav, active, by, header,
            discoverArea = discover?.first,
            discoverList = discover?.second,
            storyViewerOpen = viewer != null,
            unfollowedStoryOpen = viewer != null && hasFollowButton(viewer),
            storiesList = list,
            storiesListArea = list?.let { Box(root.box.left, it.box.top, root.box.right, min(it.box.bottom, nav!!.top)) }
                ?.takeIf { it.height > 0 },
        )
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

    /**
     * Fallback: the page title ("Chat", "Stories", "Spotlight") centred in the fixed header at the
     * top. Anything inside a scrolling list or off-centre doesn't count: Chat's filter pills include
     * one labelled "Stories", and it stays visible while the real title fades during a tab swipe.
     */
    private fun pageTitleTab(root: SnapNode, nav: NavBar): Tab? {
        val screen = root.box
        val centerX = (screen.left + screen.right) / 2
        val found = root.walk()
            .filter { n ->
                n.visible && n.box.bottom <= nav.top && n.box.top < screen.top + screen.height * 0.15 &&
                    n.box.left >= screen.left && n.box.right <= screen.right &&
                    abs((n.box.left + n.box.right) / 2 - centerX) <= screen.width / 20 &&
                    n.ancestors().none { it.scrollable }
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

    /** Discover feed tiles carry ids like "df_large_story" (df = Discover feed), in every language. */
    fun isDiscoverTile(node: SnapNode): Boolean = node.id?.lowercase()?.startsWith("df_") == true

    /**
     * Where the Discover feed is on screen, and the list that scrolls it.
     *
     * While the "Discover" header is visible the curtain starts there. Otherwise it starts at the
     * first grid cell of the feed (see [feedStart]). Nothing is remembered between passes, so a fast
     * fling that throws the header off the screen in one go can't slip through.
     */
    private fun findDiscover(root: SnapNode, nav: NavBar, header: SnapNode?): Pair<Box, SnapNode?>? {
        val tile = root.walk().firstOrNull { n ->
            n.visible && n.box.height > 0 && n.box.top < nav.top && isDiscoverTile(n)
        }
        val anchor = header ?: tile ?: return null
        val list = anchor.ancestors().firstOrNull { it.scrollable && it.box.height >= root.box.height / 3 }
        val top = when {
            header != null -> max(header.box.top, list?.box?.top ?: root.box.top)
            list != null -> feedStart(list) ?: tile!!.box.top
            else -> tile!!.box.top
        }
        val bottom = min(list?.box?.bottom ?: nav.top, nav.top)
        if (top >= bottom) return null
        return Box(root.box.left, top, root.box.right, bottom) to list
    }

    /**
     * Top of the Discover feed inside the Stories list, without relying on the header's wording.
     * Feed items (Discover tiles and the ads between them) are half-width grid cells sitting directly
     * in the list, while section headers and the Friends/Following rows span its full width. The
     * feed starts at the first cell of the unbroken run of grid cells leading up to the first tile.
     */
    private fun feedStart(list: SnapNode): Int? {
        val rows = list.children.filter { it.visible && it.box.height > 0 }
        var start = rows.indexOfFirst { row -> row.walk().any(::isDiscoverTile) }
        if (start < 0) return null
        while (start > 0 && rows[start - 1].box.width < list.box.width * 3 / 5) start--
        return max(rows[start].box.top, list.box.top)
    }

    /** The big vertical list filling the Stories page (not the sideways rows inside it). */
    private fun findStoriesList(root: SnapNode, nav: NavBar): SnapNode? = root.walk()
        .filter { n ->
            n.visible && n.scrollable && n.box.top < nav.top &&
                n.box.height >= root.box.height / 3 && n.box.width >= root.box.width * 9 / 10
        }
        .maxByOrNull { it.box.area }

    /** Snapchat's full-screen story player, if one is open. */
    private fun findStoryViewer(root: SnapNode): SnapNode? = root.walk().firstOrNull { n ->
        n.visible && n.id?.substringAfterLast('/') == "opera_viewer" && n.box.area >= root.box.area / 2
    }

    /**
     * Stories from accounts you don't follow (Discover publishers, creators, suggestions) show an
     * "Add" button in the viewer's header; friends' stories and ones you follow don't.
     */
    private fun hasFollowButton(viewer: SnapNode): Boolean {
        val screen = viewer.box
        return viewer.walk().any { n ->
            n.visible && n.id?.substringAfterLast('/') == "chrome_subscribe_button" &&
                n.box.left >= screen.left && n.box.right <= screen.right && n.box.width > 0 &&
                n.labels.none { l -> FOLLOWING_LABELS.any { it in l.lowercase() } }
        }
    }
}
