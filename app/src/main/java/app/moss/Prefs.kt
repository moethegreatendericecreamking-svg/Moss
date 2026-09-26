package app.moss

import android.content.Context

enum class StoriesMode { HIDE_DISCOVER, BLOCK_TAB, ALLOW }

data class Rules(val blockSpotlight: Boolean, val storiesMode: StoriesMode, val blockMap: Boolean) {
    fun blocks(tab: Tab): Boolean = when (tab) {
        Tab.SPOTLIGHT -> blockSpotlight
        Tab.STORIES -> storiesMode == StoriesMode.BLOCK_TAB
        Tab.MAP -> blockMap
        Tab.CHAT, Tab.CAMERA -> false
    }

    val hideDiscover get() = storiesMode == StoriesMode.HIDE_DISCOVER
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("moss", Context.MODE_PRIVATE)

    var blockSpotlight: Boolean
        get() = sp.getBoolean("block_spotlight", true)
        set(v) = sp.edit().putBoolean("block_spotlight", v).apply()

    var storiesMode: StoriesMode
        get() = runCatching { StoriesMode.valueOf(sp.getString("stories_mode", null)!!) }
            .getOrDefault(StoriesMode.HIDE_DISCOVER)
        set(v) = sp.edit().putString("stories_mode", v.name).apply()

    var blockMap: Boolean
        get() = sp.getBoolean("block_map", false)
        set(v) = sp.edit().putBoolean("block_map", v).apply()

    /** Wall-clock time until which the service records Snapchat's screen layout for a report. */
    var recordUntil: Long
        get() = sp.getLong("record_until", 0L)
        set(v) = sp.edit().putLong("record_until", v).apply()

    /** One-line summary of what the service last saw in Snapchat, shown in the app for debugging. */
    var status: String?
        get() = sp.getString("status", null)
        set(v) = sp.edit().putString("status", v).apply()

    fun rules() = Rules(blockSpotlight, storiesMode, blockMap)
}
