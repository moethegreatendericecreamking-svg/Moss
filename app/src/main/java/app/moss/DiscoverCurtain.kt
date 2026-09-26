package app.moss

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * An opaque panel drawn over Snapchat's Discover section. It swallows taps so Discover tiles can't
 * be opened; a downward swipe on it scrolls the list back up to friends' stories.
 */
class DiscoverCurtain(
    private val service: AccessibilityService,
    private val onSwipeDown: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var shown: Box? = null

    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    val isShowing get() = view != null

    fun show(box: Box) {
        if (box == shown && view != null) return
        params.x = box.left
        params.y = box.top
        params.width = box.width
        params.height = box.height
        val v = view
        runCatching {
            if (v == null) {
                view = build().also { wm.addView(it, params) }
            } else {
                wm.updateViewLayout(v, params)
            }
            shown = box
        }.onFailure { hide() }
    }

    fun hide() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
        shown = null
    }

    private fun build(): View {
        val night = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val label = TextView(service).apply {
            text = service.getString(R.string.curtain_label)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (night) Color.rgb(0xA0, 0xA0, 0xA0) else Color.rgb(0x60, 0x60, 0x60))
            val pad = (24 * service.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val detector = GestureDetector(service, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (velocityY > 0 && velocityY > kotlin.math.abs(velocityX)) onSwipeDown()
                return true
            }
        })
        return FrameLayout(service).apply {
            setBackgroundColor(if (night) Color.rgb(0x12, 0x12, 0x12) else Color.rgb(0xF4, 0xF4, 0xF4))
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ))
            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { _, event -> detector.onTouchEvent(event); true }
        }
    }
}
