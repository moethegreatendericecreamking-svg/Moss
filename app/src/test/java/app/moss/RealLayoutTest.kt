package app.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screens recorded from Snapchat 14.24 on a Galaxy S22 (1080×2340) with Moss's layout recorder.
 * Names of people and accounts are replaced with placeholders.
 */
class RealLayoutTest {

    private fun screen(name: String) = SnapDetector.read(ReportParser.parse(ReportParser.fixture("snapchat-14.24/$name")))

    @Test
    fun `reports round-trip through the parser`() {
        for (name in listOf("stories-top.txt", "stories-discover-scrolled.txt", "story-viewer-unfollowed.txt", "spotlight.txt")) {
            val text = ReportParser.fixture("snapchat-14.24/$name")
            assertEquals(name, text, LayoutReport.render(ReportParser.parse(text)))
        }
    }

    @Test
    fun `Spotlight is recognised by its page title`() {
        val s = screen("spotlight.txt")
        assertEquals(Tab.SPOTLIGHT, s.activeTab)
        assertEquals(DetectedBy.PAGE_TITLE, s.detectedBy)
        // Spotlight plays in the same viewer as stories, but it has no follow button to act on.
        assertTrue(s.storyViewerOpen)
        assertFalse(s.unfollowedStoryOpen)
        assertNull(s.storiesList)
        assertEquals("ngs_community_icon_container", s.navBar!!.tabs[Tab.STORIES]!!.clickTarget()!!.id)
    }

    @Test
    fun `Spotlight is still recognised by its container if the title changes`() {
        val text = ReportParser.fixture("snapchat-14.24/spotlight.txt").lines()
            .filterNot { "text=\"Spotlight\"" in it }
            .joinToString("\n")
        val s = SnapDetector.read(ReportParser.parse(text))
        assertEquals(Tab.SPOTLIGHT, s.activeTab)
        assertEquals(DetectedBy.PAGE_LAYOUT, s.detectedBy)
    }

    @Test
    fun `the main Stories list is found for covering while it scrolls`() {
        val s = screen("stories-top.txt")
        assertEquals(Box(0, 261, 1080, 2001), s.storiesList!!.box)
        assertEquals(Box(0, 261, 1080, 2001), s.storiesListArea)
        assertNull(screen("story-viewer-unfollowed.txt").storiesList)
    }

    @Test
    fun `bottom bar and Stories tab are recognised`() {
        val s = screen("stories-top.txt")
        assertEquals(Tab.entries.toSet(), s.navBar!!.tabs.keys)
        assertEquals(2023, s.navBar!!.top)
        // This build exposes no selected state on the tabs; the "Stories" page title gives it away.
        assertEquals(Tab.STORIES, s.activeTab)
        assertEquals(DetectedBy.PAGE_TITLE, s.detectedBy)
        assertEquals("ngs_camera_icon_container", s.navBar!!.tabs[Tab.CAMERA]!!.clickTarget()!!.id)
        assertEquals("ngs_spotlight_icon_container", s.navBar!!.tabs[Tab.SPOTLIGHT]!!.clickTarget()!!.id)
    }

    @Test
    fun `curtain covers Discover from its header down, leaving friends and Following visible`() {
        val s = screen("stories-top.txt")
        assertEquals(Box(0, 1274, 1080, 2001), s.discoverArea)
        assertEquals(Box(0, 261, 1080, 2001), s.discoverList!!.box)
        assertFalse(s.storyViewerOpen)
    }

    @Test
    fun `after a fling past the header, the whole feed is covered`() {
        // The failure from the first report: the header left the screen between two passes.
        val s = screen("stories-discover-scrolled.txt")
        assertNull(s.discoverHeader)
        assertEquals(Tab.STORIES, s.activeTab)
        assertEquals(Box(0, 261, 1080, 2001), s.discoverArea)
    }

    @Test
    fun `in another language the curtain still spares friends and Following`() {
        val text = ReportParser.fixture("snapchat-14.24/stories-top.txt").replace("text=\"Discover\"", "text=\"Entdecken\"")
        val s = SnapDetector.read(ReportParser.parse(text))
        assertNull(s.discoverHeader)
        // Starts at the first feed cell (an ad) right under the heading.
        assertEquals(Box(0, 1357, 1080, 2001), s.discoverArea)
    }

    @Test
    fun `a Discover story that got opened is recognised`() {
        val s = screen("story-viewer-unfollowed.txt")
        assertTrue(s.storyViewerOpen)
        assertTrue(s.unfollowedStoryOpen)
        // The curtain must never sit on top of a story that's playing.
        assertNull(s.discoverArea)
    }

    @Test
    fun `a story without an Add button, like a friend's, is left alone`() {
        val text = ReportParser.fixture("snapchat-14.24/story-viewer-unfollowed.txt").lines()
            .filterNot { "chrome_subscribe_button" in it && "[hidden]" !in it } // keep only the off-screen one
            .joinToString("\n")
        val s = SnapDetector.read(ReportParser.parse(text))
        assertTrue(s.storyViewerOpen)
        assertFalse(s.unfollowedStoryOpen)
        assertNull(s.discoverArea)
    }
}
