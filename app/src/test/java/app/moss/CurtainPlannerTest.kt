package app.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Uses the real Snapchat 14.24 Stories layout: list 261–2001, Discover from 1274. */
class CurtainPlannerTest {

    private val stories = SnapDetector.read(ReportParser.parse(ReportParser.fixture("snapchat-14.24/stories-top.txt")))
    private val spotlight = SnapDetector.read(ReportParser.parse(ReportParser.fixture("snapchat-14.24/spotlight.txt")))
    private val list = Box(0, 261, 1080, 2001)
    private val discover = Box(0, 1274, 1080, 2001)
    private val friendsRow = Box(0, 347, 1080, 731)

    @Test
    fun `at rest only Discover is covered`() {
        assertEquals(discover, CurtainPlanner().plan(stories, hideDiscover = true, now = 0))
    }

    @Test
    fun `while the list scrolls all of it is covered, then it shrinks back`() {
        val planner = CurtainPlanner(holdMs = 300)
        planner.plan(stories, hideDiscover = true, now = 0)

        assertEquals(list, planner.onScrolled(list, now = 1_000))
        assertEquals(list, planner.plan(stories, hideDiscover = true, now = 1_150))
        assertEquals(150L, planner.settleDelay(now = 1_150))

        // Each new scroll event extends the hold.
        planner.onScrolled(list, now = 1_250)
        assertEquals(list, planner.plan(stories, hideDiscover = true, now = 1_500))

        assertEquals(discover, planner.plan(stories, hideDiscover = true, now = 1_551))
        assertEquals(0L, planner.settleDelay(now = 1_551))
    }

    @Test
    fun `scrolling the sideways friends row doesn't cover anything extra`() {
        val planner = CurtainPlanner()
        planner.plan(stories, hideDiscover = true, now = 0)
        assertNull(planner.onScrolled(friendsRow, now = 10))
        assertEquals(discover, planner.plan(stories, hideDiscover = true, now = 20))
    }

    @Test
    fun `nothing is covered when Discover isn't being hidden`() {
        val planner = CurtainPlanner()
        assertNull(planner.plan(stories, hideDiscover = false, now = 0))
        assertNull(planner.onScrolled(list, now = 10))
    }

    @Test
    fun `leaving the Stories tab mid-scroll uncovers the screen`() {
        val planner = CurtainPlanner()
        planner.plan(stories, hideDiscover = true, now = 0)
        planner.onScrolled(list, now = 10)
        assertNull(planner.plan(spotlight, hideDiscover = true, now = 20))
    }
}
