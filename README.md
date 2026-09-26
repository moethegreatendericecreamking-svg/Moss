# Moss

**Snapchat for your friends, without Spotlight or Discover.** Moss is a small Android companion app
that sits next to the official Snapchat app:

- **Spotlight** – tapping or swiping to the Spotlight tab bounces you straight back.
- **Discover** – on the Stories tab, the Discover section is covered by a plain panel, so friends'
  stories stay but the endless feed is gone. (Or block the whole Stories tab.) If a Discover story
  opens anyway, for example from search or a shared link, Moss closes it.
- **Snap Map** – optional, off by default.

Chat, Camera, snaps and friends' stories work exactly as before.

## Why a companion app and not a modified Snapchat?

A patched Snapchat APK would mean redistributing Snap's app, and Snap bans accounts that use
modified or third-party clients. Moss never touches Snapchat itself. It uses Android's accessibility
service API, the same approach as "Reels/Shorts blocker" apps. It reads which Snapchat tab is on
screen and reacts. Your account stays safe.

**Privacy:** Moss has no internet permission, so nothing can leave your phone. It only looks at
Snapchat's screen and ignores every other app.

## Install

1. Download the APK: open this repo's **Actions** tab → the latest **Build APK** run → download the
   `moss-apk` artifact (a zip containing `app-debug.apk`). Copy it to your phone and open it.
   Android will ask you to allow installs from that app (your browser or file manager).
2. Open **Moss** → **Open accessibility settings** → **Moss – calm Snapchat** → turn it on.
3. **If the switch is greyed out ("Restricted setting")**: this is normal on Android 13+ for apps
   installed outside the Play Store. Go to Settings → Apps → Moss → ⋮ (top-right) →
   **Allow restricted settings**, then repeat step 2. The Moss screen has a button for this.
4. Open Snapchat. Try swiping to Spotlight, and you'll land back where you were.

Every build is signed with the same key (`app/debug.keystore`, a non-secret debug key), so a new
APK installs over the old one and keeps your settings.

## If something slips through

Snapchat changes its layout often, and Moss has to recognize its screens. If Spotlight or Discover
gets through:

1. In Moss, tap **Record Snapchat layout (20 s)**. Snapchat opens.
2. Visit Chat, Stories and Spotlight.
3. Come back to Moss and tap **Share layout report**.

The report lists Snapchat's screen structure: view ids, short labels, and positions. It can include
names that are visible on screen, so look it over before you share it. The detection rules live in
[`SnapScreen.kt`](app/src/main/java/app/moss/SnapScreen.kt) and can be updated from the report.

"What Moss last saw in Snapchat" on the main screen gives a quick check: it should list the tabs
of Snapchat's bottom bar and which one is active.

## How it works

- **Tab detection** (`SnapDetector`): finds Snapchat's bottom bar by looking for at least three
  tab-like items (Map, Chat, Camera, Stories, Spotlight) on one row at the bottom of the screen,
  using view ids first and labels second. It then works out the active tab from the tab's
  selected state. If that fails it uses the page title, and after that a large page container
  whose id names the page. Requiring a full row stops a lone "Spotlight" button, like the one on
  the send-to screen, from triggering a block.
- **Blocking** (`SnapGuardService`): when a blocked tab is active, Moss taps the last allowed tab
  (Camera by default), or presses Back if it can't. If it keeps bouncing, which means detection
  has gone wrong, it pauses itself for 20 seconds instead of locking you out of Snapchat.
- **Discover curtain** (`SnapDetector.findDiscover`, `DiscoverCurtain`): Discover tiles have view
  ids starting with `df_`. The opaque overlay starts at the "Discover" header if it's visible.
  Otherwise it starts at the first half-width grid cell of the feed, which works in any language
  and covers the ads between tiles too. It runs to the bottom of the list. Moss re-checks this on
  every pass without relying on memory, so a fast fling can't get past it. Swipe down on the
  overlay to scroll back up to friends' stories. It never covers a story that's playing.
- **Discover stories** (`hasFollowButton`): when Snapchat's story viewer (`opera_viewer`) shows an
  "Add" button, the story is from an account you don't follow, and Moss presses Back. Friends'
  stories and accounts you follow don't have that button. This has its own switch.

Known limits: the curtain follows scrolling with a short delay. Spotlight videos a friend sends
you in chat still open. A friend's *public profile* story may show an "Add" button and get closed;
turn off "Close Discover stories that open anyway" if that happens.

`app/src/test/resources/snapchat-*/` holds screens recorded with the layout recorder, with names
replaced by placeholders. The tests run the detector against them, so every fix is checked against
real Snapchat layouts.

## Build it yourself

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
