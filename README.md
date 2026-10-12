# SKLK TV

Android TV app for browsing and playing the collection served by
[kodi-api-server](https://github.com/sakaljurgis/kodi-api-server) (the backend of the Kodi add-on
plugin.video.sklk): same JSON API (`/api?path=...`), native TV UI and player.

- **Server**: set its address on the home screen (*Settings › Server*, asked for on first start), the
  same as in the Kodi add-on's settings, e.g. `https://media.example.com`.

- **Browse**: one list per api folder with a details panel for the focused item (duration, size,
  file name, watch progress, and the item's `thumb` image when the api provides one).
  The remote's Back key goes up a level.
- **Watched state is shared with Kodi**: the server keeps resume points and watched marks, so
  stopping an episode on Kodi and picking it up here (or the other way round) just works.
- **Continue watching** (home screen, from the server): unfinished files and the next episode of shows
  in progress, for titles played in the last 3 months. Playing from there continues with the rest of
  the season.
- **⋯ menu**: hold OK (or press Menu) on an item. Server actions from the api's `contextMenus`
  (mark watched/unwatched — a file, a season or a whole show —, hide from Continue watching, delete
  file, ...) plus play from beginning. Delete-like actions ask for confirmation, with Cancel focused.
- **Play**: Media3/ExoPlayer streams the files directly (MKV/MP4, HEVC if the TV decodes it).
  Audio the TV can't decode (DTS, TrueHD, ...) falls back to FFmpeg (`org.jellyfin.media3:media3-ffmpeg-decoder`).
  Playing a file queues the rest of its folder, so episodes continue automatically.
- **Remote in the player**: OK = pause/play, Left/Right = seek 10s (hold to speed up),
  with controls shown the D-pad moves between buttons (subtitles, audio track, next/previous); Back hides controls / exits.
- **Resume**: rows show a progress bar / ✓ when watched; shows and seasons how much is watched.
- Searches (`action: search`, e.g. Torr) open a text prompt.

## Build

Requires the Android SDK (`local.properties` → `sdk.dir`) and a JDK 17+ with `jlink` (e.g. Temurin;
JetBrains' bundled runtime lacks it):

```sh
export JAVA_HOME=/path/to/jdk-21
./gradlew lintDebug assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Optionally preset the server in `local.properties` (not in git), so it needn't be typed with the remote:

```properties
sklk.serverUrl=https://media.example.com
```

## Install on the TV

On the TV enable *Developer options* (Settings → Device Preferences → About → click *Build* 7×),
then turn on *USB/Network debugging*. Then:

```sh
adb connect <tv-ip>:5555        # accept the prompt on the TV
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app appears in the TV's app list as **SKLK TV**.
Alternatively copy the APK to a USB stick and install it with a file manager on the TV.

## Code

| File | |
|---|---|
| `Api.kt` | api client + models (items, context menus), sorting like the web ui |
| `MainActivity.kt` | fragment back stack (folders) |
| `BrowseFragment.kt` | one folder: loading, clicks (open / play / search), ⋯ menu, Continue watching |
| `Rows.kt` | list rows + adapter (OK / hold OK / Menu key) |
| `DetailsPanel.kt` | right-hand details for the focused row |
| `PlayerActivity.kt` | ExoPlayer, remote keys, progress tracking |
| `Progress.kt` | playback progress reports to the server (kept and resent if they fail) |
| `Settings.kt` | the server's address |
| `Format.kt` | duration, size, time formatting |

The server address is a setting (`Settings.kt`), not in the code.
