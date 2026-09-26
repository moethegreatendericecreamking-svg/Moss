package app.moss

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * Watches the official Snapchat app and steers away from blocked tabs (Spotlight, and optionally
 * Stories or Snap Map), and covers the Discover section of the Stories tab.
 *
 * Nothing is modified inside Snapchat and nothing leaves the phone: the app has no internet permission.
 */
class SnapGuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var curtain: DiscoverCurtain
    private val planner = CurtainPlanner()
    /** The list under the Discover curtain, scrolled back up when the curtain is swiped down. */
    private var discoverList: SnapNode? = null
    /** Once the Stories list stops scrolling, re-read the screen to shrink the curtain back. */
    private val settleRunnable = Runnable { scheduleEvaluate(0) }

    private var evaluatePending = false
    private val evaluateRunnable = Runnable {
        evaluatePending = false
        evaluate()
    }

    /** Where to send the user when they land on a blocked tab. */
    private var lastAllowedTab: Tab? = null
    private var clickedBlockedTab: Tab? = null
    private var clickedAt = 0L
    private var lastBlockAt = 0L
    private val recentBlocks = ArrayDeque<Long>()
    private var pausedUntil = 0L
    private var lastToastAt = 0L
    private var lastSummary: String? = null

    override fun onServiceConnected() {
        prefs = Prefs(this)
        curtain = DiscoverCurtain(this, ::scrollDiscoverUp)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::prefs.isInitialized) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return
        if (pkg != SNAPCHAT_PACKAGE) {
            // Another app (or the home screen) came forward: don't leave the curtain over it.
            val windowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            if (curtain.isShowing && windowChange) scheduleEvaluate(0)
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) onSnapchatClick(event)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) onSnapchatScroll(event)
        scheduleEvaluate(EVALUATE_DELAY_MS)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        if (::curtain.isInitialized) curtain.hide()
        return super.onUnbind(intent)
    }

    private fun scheduleEvaluate(delayMs: Long) {
        if (evaluatePending) return
        evaluatePending = true
        handler.postDelayed(evaluateRunnable, delayMs)
    }

    /** A tap on a blocked tab in the bottom bar is remembered so it's caught even mid-animation. */
    private fun onSnapchatClick(event: AccessibilityEvent) {
        val source = event.source ?: return
        val screen = Rect().also { (rootInActiveWindow ?: return).getBoundsInScreen(it) }
        val tapped = NodeSnapshot.capture(source, maxNodes = 40)
        if (tapped.box.centerY < screen.top + screen.height() * 0.7 || tapped.box.width > screen.width() / 2) return
        val tab = tapped.walk().firstNotNullOfOrNull { SnapDetector.tabOf(it) } ?: return
        if (prefs.rules().blocks(tab)) {
            clickedBlockedTab = tab
            clickedAt = SystemClock.uptimeMillis()
        }
    }

    /** The Stories list started or kept moving: cover all of it at once, without waiting for a re-read. */
    private fun onSnapchatScroll(event: AccessibilityEvent) {
        val source = event.source ?: return
        val r = Rect().also(source::getBoundsInScreen)
        val now = SystemClock.uptimeMillis()
        val cover = planner.onScrolled(Box(r.left, r.top, r.right, r.bottom), now) ?: return
        curtain.show(cover)
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, planner.settleDelay(now) + 10)
    }

    private fun snapchatRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        val pkg = active?.packageName?.toString()
        if (pkg == SNAPCHAT_PACKAGE) return active
        if (pkg != null && pkg != packageName) return null
        // After it's touched, our own curtain can become the "active" window; look past it.
        return windows.firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.root?.packageName?.toString() == SNAPCHAT_PACKAGE
        }?.root
    }

    private fun evaluate() {
        val root = snapchatRoot()
        if (root == null) {
            curtain.hide()
            return
        }
        val screen = SnapDetector.read(NodeSnapshot.capture(root))
        val now = SystemClock.uptimeMillis()
        val rules = prefs.rules()
        record(screen, now)

        // The circuit breaker only pauses actions that could loop; the curtain can't, so it stays.
        if (now >= pausedUntil && enforce(screen, rules, now)) {
            curtain.hide()
            return
        }

        val area = planner.plan(screen, rules.hideDiscover, now)
        discoverList = if (area != null) screen.discoverList ?: screen.storiesList else null
        if (area != null) curtain.show(area) else curtain.hide()
    }

    /** Leaves blocked tabs and closes Discover stories. Returns true if it acted. */
    private fun enforce(screen: SnapScreen, rules: Rules, now: Long): Boolean {
        var active = screen.activeTab
        val clicked = clickedBlockedTab
        if (clicked != null && now - clickedAt < CLICK_MEMORY_MS && screen.navBar != null) active = clicked

        if (active != null && rules.blocks(active)) {
            redirect(screen, active, rules, now)
            return true
        }
        if (active != null) lastAllowedTab = active

        // A Discover story opened anyway (from search, a shared link…).
        if (screen.unfollowedStoryOpen && rules.closeUnfollowedStories && active != Tab.SPOTLIGHT) {
            block(now, getString(R.string.toast_story_closed)) { performGlobalAction(GLOBAL_ACTION_BACK) }
            return true
        }
        return false
    }

    private fun redirect(screen: SnapScreen, blocked: Tab, rules: Rules, now: Long) =
        block(now, getString(R.string.toast_blocked, blocked.title)) {
            val target = lastAllowedTab?.takeUnless { rules.blocks(it) } ?: Tab.CAMERA
            val tapped = screen.navBar?.tabs?.get(target)?.clickTarget()?.ref
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (!tapped) performGlobalAction(GLOBAL_ACTION_BACK)
        }

    /** Runs a blocking [action], rate-limited, and pauses Moss if it keeps firing. */
    private fun block(now: Long, message: String, action: () -> Unit) {
        val sinceLast = now - lastBlockAt
        if (sinceLast < BLOCK_COOLDOWN_MS) {
            scheduleEvaluate(BLOCK_COOLDOWN_MS - sinceLast)
            return
        }
        clickedBlockedTab = null
        lastBlockAt = now

        // Circuit breaker: if we keep bouncing, detection is probably wrong for this Snapchat build.
        recentBlocks.addLast(now)
        while (recentBlocks.first() < now - LOOP_WINDOW_MS) recentBlocks.removeFirst()
        if (recentBlocks.size > LOOP_LIMIT) {
            recentBlocks.clear()
            pausedUntil = now + PAUSE_MS
            toast(getString(R.string.toast_paused))
            return
        }

        action()
        toast(message)
        scheduleEvaluate(RECHECK_DELAY_MS)
    }

    private fun scrollDiscoverUp() {
        discoverList?.ref?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        scheduleEvaluate(EVALUATE_DELAY_MS)
    }

    private fun record(screen: SnapScreen, now: Long) {
        val summary = LayoutReport.summarize(screen)
        if (summary != lastSummary) {
            lastSummary = summary
            prefs.status = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) + " · " + summary
        }
        if (System.currentTimeMillis() < prefs.recordUntil) runCatching { LayoutReport.offer(this, screen, now) }
    }

    private fun toast(message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastToastAt < TOAST_INTERVAL_MS) return
        lastToastAt = now
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val EVALUATE_DELAY_MS = 150L
        const val RECHECK_DELAY_MS = 450L
        const val BLOCK_COOLDOWN_MS = 700L
        const val CLICK_MEMORY_MS = 1500L
        const val LOOP_WINDOW_MS = 10_000L
        const val LOOP_LIMIT = 8
        const val PAUSE_MS = 20_000L
        const val TOAST_INTERVAL_MS = 2500L
    }
}
