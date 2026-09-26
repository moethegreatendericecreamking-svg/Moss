package app.moss

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        find<Button>(R.id.enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        find<Button>(R.id.app_info).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        find<View>(R.id.restricted_help).visibility =
            if (Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
        find<View>(R.id.app_info).visibility =
            if (Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE

        find<Switch>(R.id.block_spotlight).apply {
            isChecked = prefs.blockSpotlight
            setOnCheckedChangeListener { _, checked -> prefs.blockSpotlight = checked }
        }
        find<Switch>(R.id.close_unfollowed).apply {
            isChecked = prefs.closeUnfollowedStories
            setOnCheckedChangeListener { _, checked -> prefs.closeUnfollowedStories = checked }
        }
        find<Switch>(R.id.block_map).apply {
            isChecked = prefs.blockMap
            setOnCheckedChangeListener { _, checked -> prefs.blockMap = checked }
        }
        find<RadioGroup>(R.id.stories_mode).apply {
            check(
                when (prefs.storiesMode) {
                    StoriesMode.HIDE_DISCOVER -> R.id.stories_hide_discover
                    StoriesMode.BLOCK_TAB -> R.id.stories_block
                    StoriesMode.ALLOW -> R.id.stories_allow
                }
            )
            setOnCheckedChangeListener { _, id ->
                prefs.storiesMode = when (id) {
                    R.id.stories_block -> StoriesMode.BLOCK_TAB
                    R.id.stories_allow -> StoriesMode.ALLOW
                    else -> StoriesMode.HIDE_DISCOVER
                }
            }
        }

        find<Button>(R.id.open_snapchat).setOnClickListener { openSnapchat() }
        find<Button>(R.id.record).setOnClickListener { recordLayout() }
        find<Button>(R.id.share).setOnClickListener { shareReport() }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        find<TextView>(R.id.status).setText(if (enabled) R.string.status_on else R.string.status_off)
        find<Button>(R.id.enable).setText(if (enabled) R.string.enable_again else R.string.enable)
        find<TextView>(R.id.last_seen).text = prefs.status ?: getString(R.string.last_seen_none)
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, SnapGuardService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openSnapchat(): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(SNAPCHAT_PACKAGE)
        if (launch == null) {
            Toast.makeText(this, R.string.snapchat_missing, Toast.LENGTH_LONG).show()
            return false
        }
        startActivity(launch)
        return true
    }

    private fun recordLayout() {
        if (!isServiceEnabled()) {
            Toast.makeText(this, R.string.record_needs_service, Toast.LENGTH_LONG).show()
            return
        }
        @Suppress("DEPRECATION")
        val snapVersion = runCatching { packageManager.getPackageInfo(SNAPCHAT_PACKAGE, 0).versionName }.getOrNull()
        val header = buildString {
            appendLine("Moss layout report")
            appendLine("Moss ${BuildConfig.VERSION_NAME} · Snapchat ${snapVersion ?: "?"}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            append("Settings: ${prefs.rules()}")
        }
        LayoutReport.start(this, header)
        prefs.recordUntil = System.currentTimeMillis() + RECORD_MS
        if (openSnapchat()) Toast.makeText(this, R.string.record_started, Toast.LENGTH_LONG).show()
    }

    private fun shareReport() {
        val file = LayoutReport.file(this)
        val text = if (file.exists()) file.readText() else ""
        if (!text.contains("=== snapshot")) {
            Toast.makeText(this, R.string.share_empty, Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
            putExtra(Intent.EXTRA_TEXT, text.take(MAX_SHARE_CHARS))
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun <T : View> find(id: Int): T = findViewById(id)

    private companion object {
        const val RECORD_MS = 20_000L
        const val MAX_SHARE_CHARS = 90_000
    }
}
