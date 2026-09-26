package app.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val W = 1080
private const val H = 2400
private const val NAV_TOP = 2200
private val SCREEN = Box(0, 0, W, H)

private fun n(
    box: Box,
    id: String? = null,
    desc: String? = null,
    text: String? = null,
    state: String? = null,
    selected: Boolean = false,
    clickable: Boolean = false,
    scrollable: Boolean = false,
    visible: Boolean = true,
    children: List<SnapNode> = emptyList(),
) = SnapNode(
    id = id, className = "android.view.View", desc = desc, text = text, state = state, box = box,
    selected = selected, clickable = clickable, scrollable = scrollable, visible = visible, children = children,
)

private val ORDER = listOf(Tab.MAP, Tab.CHAT, Tab.CAMERA, Tab.STORIES, Tab.SPOTLIGHT)
private val ID_KEY = mapOf(
    Tab.MAP to "map", Tab.CHAT to "chat", Tab.CAMERA to "camera", Tab.STORIES to "community", Tab.SPOTLIGHT to "spotlight",
)

private fun tabBox(i: Int) = Box(i * W / 5, NAV_TOP, (i + 1) * W / 5, H)

/** Bottom bar identified by view ids, with the selected flag on the tappable container. */
private fun navById(selected: Tab?) = n(
    Box(0, NAV_TOP, W, H), id = "ngs_navigation_bar",
    children = ORDER.mapIndexed { i, tab ->
        n(
            tabBox(i), id = "ngs_${ID_KEY[tab]}_icon_container", clickable = true, selected = tab == selected,
            children = listOf(n(tabBox(i).let { Box(it.left + 60, it.top + 40, it.right - 60, it.bottom - 60) }, id = "icon")),
        )
    },
)

/** Bottom bar with obfuscated ids: only content descriptions and state descriptions to go on. */
private fun navByLabel(selected: Tab?) = n(
    Box(0, NAV_TOP, W, H), id = "a1",
    children = ORDER.mapIndexed { i, tab ->
        val label = if (tab == Tab.CHAT) "Chat, 3 new" else tab.title.removePrefix("Snap ")
        n(tabBox(i), id = "b$i", desc = label, clickable = true, state = if (tab == selected) "Selected" else "Not selected")
    },
)

private fun screenOf(vararg parts: SnapNode) = n(SCREEN, id = "root", children = parts.toList())

class SnapDetectorTest {

    @Test
    fun `selected Spotlight tab is found via view ids`() {
        val screen = SnapDetector.read(screenOf(navById(Tab.SPOTLIGHT)))
        assertNotNull(screen.navBar)
        assertEquals(5, screen.navBar!!.tabs.size)
        assertEquals(NAV_TOP, screen.navBar!!.top)
        assertEquals(Tab.SPOTLIGHT, screen.activeTab)
        assertEquals(DetectedBy.SELECTED_STATE, screen.detectedBy)
    }

    @Test
    fun `tabs are found via content descriptions and state descriptions`() {
        val screen = SnapDetector.read(screenOf(navByLabel(Tab.STORIES)))
        assertEquals(setOf(Tab.MAP, Tab.CHAT, Tab.CAMERA, Tab.STORIES, Tab.SPOTLIGHT), screen.navBar!!.tabs.keys)
        assertEquals(Tab.STORIES, screen.activeTab)
    }

    @Test
    fun `a couple of tab-like buttons at the bottom are not a nav bar`() {
        // e.g. the send-to screen after taking a snap has a "Spotlight" destination.
        val sendTo = screenOf(
            n(Box(0, 2250, 300, 2350), text = "Spotlight", clickable = true),
            n(Box(320, 2250, 620, 2350), text = "Camera Roll", clickable = true),
            n(Box(0, 800, W, 900), text = "Spotlight", clickable = true),
        )
        val screen = SnapDetector.read(sendTo)
        assertNull(screen.navBar)
        assertNull(screen.activeTab)
    }

    @Test
    fun `a bigger tab-named button above the bar doesn't displace the real tab`() {
        val root = screenOf(
            n(Box(300, 1750, 780, 1950), text = "Spotlight", clickable = true),
            navById(Tab.SPOTLIGHT),
        )
        val screen = SnapDetector.read(root)
        assertEquals("ngs_spotlight_icon_container", screen.navBar!!.tabs[Tab.SPOTLIGHT]!!.id)
        assertEquals(Tab.SPOTLIGHT, screen.activeTab)
    }

    @Test
    fun `falls back to the visible page title when no tab is marked selected`() {
        val root = screenOf(
            n(Box(W, 60, W + 400, 160), text = "Spotlight", visible = false), // neighbouring page, off screen
            n(Box(40, 60, 400, 160), text = "Chat"),
            navById(selected = null),
        )
        val screen = SnapDetector.read(root)
        assertEquals(Tab.CHAT, screen.activeTab)
        assertEquals(DetectedBy.PAGE_TITLE, screen.detectedBy)
    }

    @Test
    fun `falls back to a large Spotlight container`() {
        val root = screenOf(n(Box(0, 0, W, NAV_TOP), id = "spotlight_feed_pager"), navByLabel(selected = null))
        val screen = SnapDetector.read(root)
        assertEquals(Tab.SPOTLIGHT, screen.activeTab)
        assertEquals(DetectedBy.PAGE_LAYOUT, screen.detectedBy)
    }

    @Test
    fun `no nav bar means nothing is blocked`() {
        // Full-screen story viewer: no bottom bar, so even a Spotlight-ish id must not trigger.
        val root = screenOf(n(Box(0, 0, W, H), id = "spotlight_feed_pager"))
        val screen = SnapDetector.read(root)
        assertNull(screen.activeTab)
    }

    @Test
    fun `selected state parsing ignores negatives`() {
        assertTrue(SnapDetector.isMarkedSelected(n(SCREEN, state = "Selected")))
        assertTrue(SnapDetector.isMarkedSelected(n(SCREEN, desc = "Stories, selected")))
        assertFalse(SnapDetector.isMarkedSelected(n(SCREEN, state = "Not selected")))
        assertFalse(SnapDetector.isMarkedSelected(n(SCREEN, state = "unselected")))
    }

    @Test
    fun `tab names match whole words only`() {
        assertNull(SnapDetector.tabOf(n(SCREEN, id = "bitmap_view")))
        assertNull(SnapDetector.tabOf(n(SCREEN, text = "Chatty")))
        assertEquals(Tab.CHAT, SnapDetector.tabOf(n(SCREEN, desc = "Chat, 12 new")))
        assertEquals(Tab.STORIES, SnapDetector.tabOf(n(SCREEN, id = "ngs_community_icon_container")))
    }

    @Test
    fun `click target climbs to the tappable container`() {
        val nav = navById(Tab.CAMERA)
        val icon = nav.children[1].children[0]
        assertEquals("ngs_chat_icon_container", icon.clickTarget()!!.id)
    }
}

class DiscoverAreaTest {

    /** Stories list: optional friends row (200–600), optional header, then an ad cell and a tile. */
    private fun list(
        headerTop: Int?,
        friends: Boolean = true,
        tiles: Boolean = true,
        tileId: String = "df_large_story",
        headerText: String = "Discover",
    ) = n(
        Box(0, 200, W, NAV_TOP), id = "stories_list", scrollable = true,
        children = buildList {
            if (friends) add(n(Box(0, 200, W, 600), id = "friend_story_row", text = "Friend"))
            if (headerTop != null) add(n(Box(0, headerTop, W, headerTop + 80), text = headerText))
            if (tiles) {
                val top = headerTop?.plus(80) ?: if (friends) 600 else 200
                add(n(Box(28, top, 531, NAV_TOP), children = listOf(n(Box(28, top, 531, NAV_TOP), text = "Sponsored"))))
                add(n(Box(554, top, 1057, NAV_TOP), children = listOf(n(Box(554, top, 1057, NAV_TOP), id = tileId))))
            }
        },
    )

    private fun stories(
        headerTop: Int?,
        selected: Tab? = Tab.STORIES,
        friends: Boolean = true,
        tiles: Boolean = true,
        tileId: String = "df_large_story",
        headerText: String = "Discover",
    ) = SnapDetector.read(screenOf(list(headerTop, friends, tiles, tileId, headerText), navById(selected)))

    @Test
    fun `curtain runs from the Discover header to the bottom of the list`() {
        val s = stories(headerTop = 1200)
        assertEquals(Box(0, 1200, W, NAV_TOP), s.discoverArea)
        assertEquals("stories_list", s.discoverList!!.id)
    }

    @Test
    fun `scrolled into the feed, the whole list is covered`() {
        assertEquals(Box(0, 200, W, NAV_TOP), stories(headerTop = null, friends = false).discoverArea)
    }

    @Test
    fun `with the header in another language, the curtain starts at the feed and spares friends`() {
        val s = stories(headerTop = 1200, headerText = "Entdecken")
        assertNull(s.discoverHeader)
        assertEquals(Box(0, 1280, W, NAV_TOP), s.discoverArea)
        assertEquals(Box(0, 600, W, NAV_TOP), stories(headerTop = null).discoverArea)
    }

    @Test
    fun `the header alone is enough if the tile ids ever change`() {
        assertEquals(Box(0, 1500, W, NAV_TOP), stories(headerTop = 1500, tileId = "renamed_tile").discoverArea)
    }

    @Test
    fun `friends' stories alone are never covered`() {
        assertNull(stories(headerTop = null, tiles = false).discoverArea)
    }

    @Test
    fun `nothing is covered on other tabs`() {
        assertNull(stories(headerTop = 1200, selected = Tab.CHAT).discoverArea)
    }

    @Test
    fun `nothing is covered while a story is playing on top`() {
        val viewer = n(Box(0, 0, W, H), id = "opera_viewer")
        val s = SnapDetector.read(screenOf(list(headerTop = 1200), viewer, navById(Tab.STORIES)))
        assertTrue(s.storyViewerOpen)
        assertFalse(s.unfollowedStoryOpen)
        assertNull(s.discoverArea)
    }

    @Test
    fun `an Add button marks a story from an account you don't follow`() {
        fun viewerWith(button: SnapNode) = SnapDetector.read(
            screenOf(n(Box(0, 0, W, H), id = "opera_viewer", children = listOf(button)), navById(Tab.STORIES))
        )
        val add = n(Box(677, 111, 954, 219), id = "context_chrome_header/chrome_subscribe_button", desc = "Add")
        assertTrue(viewerWith(add).unfollowedStoryOpen)
        val subscribed = n(Box(677, 111, 954, 219), id = "chrome_subscribe_button", desc = "Subscribed")
        assertFalse(viewerWith(subscribed).unfollowedStoryOpen)
        val offscreen = n(Box(-1483, 111, -1206, 219), id = "chrome_subscribe_button", desc = "Add")
        assertFalse(viewerWith(offscreen).unfollowedStoryOpen)
    }
}

class RulesTest {
    @Test
    fun `rules block what the settings say`() {
        val rules = Rules(blockSpotlight = true, storiesMode = StoriesMode.HIDE_DISCOVER, blockMap = false)
        assertTrue(rules.blocks(Tab.SPOTLIGHT))
        assertFalse(rules.blocks(Tab.STORIES))
        assertTrue(rules.hideDiscover)
        assertTrue(rules.closeUnfollowedStories)
        assertFalse(rules.blocks(Tab.CHAT))
        assertFalse(rules.blocks(Tab.CAMERA))
        assertTrue(rules.copy(storiesMode = StoriesMode.BLOCK_TAB).blocks(Tab.STORIES))
        assertTrue(rules.copy(blockMap = true).blocks(Tab.MAP))
    }
}

class LayoutReportTest {
    @Test
    fun `hidden elements without ids or labels are left out of reports`() {
        val root = screenOf(
            n(Box(0, 0, 10, 10), visible = false, children = listOf(n(Box(0, 0, 10, 10), visible = false))),
            n(Box(0, 0, 10, 10), visible = false, children = listOf(n(Box(0, 0, 10, 10), id = "chrome_subscribe_button", visible = false))),
            n(Box(0, 0, 10, 10), visible = false, children = listOf(n(Box(0, 0, 10, 10), text = "My Story", visible = false))),
        )
        val out = LayoutReport.render(root)
        assertEquals(out, 5, out.lines().count { it.isNotBlank() })
        assertTrue(out.contains("#chrome_subscribe_button [hidden]"))
        assertTrue(out.contains("text=\"My Story\" [hidden]"))
    }

    @Test
    fun `report lists ids, flags and bounds`() {
        val out = LayoutReport.render(screenOf(navById(Tab.SPOTLIGHT)))
        assertTrue(out, out.contains("#ngs_spotlight_icon_container [sel clk] (864,2200,1080,2400)"))
        val summary = LayoutReport.summarize(SnapDetector.read(screenOf(navById(Tab.SPOTLIGHT))))
        assertTrue(summary, summary.contains("active tab: Spotlight (selected_state)"))
    }
}
