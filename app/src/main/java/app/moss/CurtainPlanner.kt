package app.moss

import kotlin.math.max

/**
 * Decides where the Discover curtain goes.
 *
 * At rest it covers exactly the Discover feed. But re-reading the screen takes a moment, so while
 * the Stories list is scrolling a curtain that follows the feed would lag behind it, leaving
 * freshly scrolled-in tiles exposed (and tappable) for a split second. So while the list moves the
 * curtain covers all of it, and shrinks back to the feed once scrolling has stopped for [holdMs].
 */
class CurtainPlanner(private val holdMs: Long = 300L) {
    private var list: Box? = null
    private var listArea: Box? = null
    private var movingUntil = 0L

    /**
     * A view scrolled. If it was the Stories list, returns the area to cover right away; the next
     * [plan] after [settleDelay] shrinks the curtain back.
     */
    fun onScrolled(source: Box, now: Long): Box? {
        if (source != list) return null
        movingUntil = now + holdMs
        return listArea
    }

    fun plan(screen: SnapScreen, hideDiscover: Boolean, now: Long): Box? {
        if (!hideDiscover) {
            list = null
            listArea = null
            return null
        }
        list = screen.storiesList?.box
        listArea = screen.storiesListArea
        val moving = now < movingUntil
        return if (moving && listArea != null) listArea else screen.discoverArea
    }

    /** How long until the list counts as settled again. */
    fun settleDelay(now: Long): Long = max(0L, movingUntil - now)
}
