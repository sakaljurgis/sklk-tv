# SKLK TV — working notes

Android TV app (Kotlin, leanback widgets + Media3/ExoPlayer) for the user's personal media server
(kodi-api-server; web UI at `/if/`). Features, build and install steps are in `README.md`.

**Never write the server's address into this repo** (code, docs, commits): it's private. It lives in
the app's settings and, for builds, in `local.properties` (`sklk.serverUrl`, not in git).

## Status (2026-10-12)

- **v0.2** is installed on the user's TV.
- **v0.3** (watch state on the server, shared with the Kodi add-on; server address as a setting) is
  built and tested in the emulator, not on the TV yet. The backend for it is deployed.
- Related repos (sibling checkouts): backend `~/WebstormProjects/kodi-api-server` (pushing `main`
  deploys it, ~2–5 min), Kodi add-on `~/WebstormProjects/plugin.video.sklk`. Each has its own notes.
- Git: `git@github.com:sakaljurgis/sklk-tv.git`.

## Rules

- **The API is live and personal.** Reading listings is fine. Never call state-changing paths while
  exploring/testing: `all/delete/<id>`, `/torr/delete/<id>`, `/torr/stop-transmission/<id>`,
  `all/scan`, `all/scan-all`, `torr/search/...` (records recent searches), and the watch state ones
  (`POST /api/state/<id>`, `all/watched|unwatched|hide/...`) — playing anything in the app writes
  those, so test playback against a local backend (see Testing).
- Lint must pass — `lintDebug` fails the build on errors.

## Build

```sh
JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./gradlew lintDebug assembleDebug
```

- JDK: Temurin 21 in `~/.jdks` (the system has only a JRE 11; PhpStorm's JBR has no `jlink`, which
  AGP needs for the SDK's `core-for-system-modules.jar`).
- AGP 9.4.1 with built-in Kotlin (don't apply `org.jetbrains.kotlin.android`), Gradle 9.8.1,
  compile/target SDK 36, minSdk 23. SDK at `~/Android/Sdk` (reinstalled 2026-10-12 with cmdline-tools,
  licenses accepted, AGP auto-installs). `BuildConfig` is on for `DEFAULT_SERVER_URL`.
- media3 is pinned to **1.9.x**: `org.jellyfin.media3:media3-ffmpeg-decoder` (FFmpeg audio fallback)
  only exists up to `1.9.0+1` and must match media3. Check for a newer Jellyfin build before bumping.
- Leanback Java getters/setters with mismatched nullability aren't Kotlin properties — call `setX()`.
- Suppressed lint false positives: `RestrictedApi` on `PlayerActivity.dispatchKeyEvent`;
  `UseAppTint` in `row_item.xml` (no AppCompat, so `android:tint` is the one that works).

## Testing

Emulator: AVD `sklk_tv` (Android TV 14, x86, KVM works). Headless:
`QEMU_AUDIO_DRV=none ~/Android/Sdk/emulator/emulator -avd sklk_tv -no-window -no-boot-anim -no-snapshot -gpu swiftshader_indirect`,
then `adb -e install -r ...`, drive it with `adb -e shell input keyevent ...` (`input keycombination 113 29`
= Ctrl+A in a text field), look with `adb -e exec-out screencap -p > shot.png`.

So that playing doesn't write the user's real watch state: run the backend locally on a copy of the
prod DB (backend notes: `DATA_ROOT`, never `all/play` against it), and `node tools/stream-proxy.js <server
host>` (port 3998): everything goes to the local backend except the read-only `all/play/<id>` streams,
which come from the real server. In the app set the server to `http://10.0.2.2:3998` (the host's
localhost from the emulator).

## API

`GET <server>/api?path=<path>[&search=<text>]` (no path = root; `%2F`-encoded paths work).

Response handling, same precedence as the web UI's `processApiResult()`:
1. `forceUpdate.urlParams.path` → re-query
2. `notification` / `msgBoxOK` → message; a notification reloads the current folder unless `refreshContainer: false`
3. `play` → stream url (server-relative or absolute)
4. `updateList` + `items` → listing; `category` = title; sort by label unless `nosort`

Item fields: `label`, `path`, `isFolder`, `IsPlayable`/`isPlayable`, `mediatype: "video"`,
`action` (`"search"` | `"query"`), `plot` (multi-line, e.g. `"Size 3.28 GB\nLM + transm"`),
`size` (bytes), `duration` (s), `thumb` (url), `searchFor`,
`contextMenus: [{name, urlParams: {action: "query", path}}]`.
Watch state (server-side, shared with Kodi): files `playcount` (> 0 = watched), `resume {position,
total}` (s), `lastplayed`; folders `watchedCount`/`totalCount`; Continue watching items `folder`,
`folderTitle`. Mark (un)watched / hide are server `contextMenus`.

Players report to `POST /api/state/<file id>` `{position, duration, event: start|tick|pause|stop|end,
client, at?}` (s; `at` ms only for late resends) and `GET /api/state/<file id>` gives the current
state. The server decides watched (≥ 90 % or `end`) and ignores positions < 10 s.

- folder → navigate to `path`
- playable, no action → `path` *is* the stream (`/api?path=all/play/<id>`, range requests OK,
  `video/mp4` or `video/x-matroska`)
- playable + `action: "query"` → query `path`, use `response.play`
- `action: "search"` → prompt, then query `path` + `search`

Tree (new backend): root → Continue watching (`all/continue`) / Movies (`all/movie`, ~213 folders
`all/<id>`, 1–26 files each) / Shows (`all/show`, ~56; show → `"N Sezonas"` → episode files, in
episode order) / Scan items. **Torr** (`torr`) and **LRT.lt** (`lrt`) are unlisted (unused) but still
work by path; `all` is the old All Files menu. No posters/plots in the API yet.

## Design notes

- Code map: see the table in `README.md`. Server address: `Settings.serverUrl` (prefs, else
  `BuildConfig.DEFAULT_SERVER_URL` from local.properties); `Settings.normalize` takes it with or
  without scheme (default https) and `/api`. Changing it clears pending progress reports.
- Home = the server's Continue watching (inline, its root folder entry hidden) + the API root items +
  Settings › Server (shown once loaded, else it'd take the focus; asked for at once if not set).
- `Progress` = progress reports to the server, sent in order; a failed one is kept (latest per file,
  SharedPreferences `progress_pending`) and resent with its `at`. `Progress.sync()` (await in-flight +
  resend, ≤ 3 s) runs before every listing load and before server menu actions. The player reports in
  `onPause` — before the list behind it resumes and reloads. On first start v0.3 converts the v0.2 local
  history (prefs `progress`) into such late reports and clears it.
- Playing: the start position is fetched fresh (`GET /api/state`), the listed resume point is the
  fallback; resume only from ≥ 30 s. The playlist is the whole folder; auto-next into an episode with a
  resume point seeks there. Continue watching items re-list their `folder` for the playlist.
- `RowAdapter` uses stable ids + no item animator so focus survives full rebinds on refresh.
- Folders refresh silently on return (>5 s since last load); unchanged listings aren't re-rendered.
- ⋯ menu: hold OK (View long-click) or Menu key. `MenuAction.isDestructive` (regex
  `delete|remove|trin|šalin` on name or path) → confirmation dialog with Cancel focused.
- Player keys while controls are hidden: OK = pause/play, Left/Right = seek 10 s (30 s/60 s when held);
  key-ups are swallowed so the controller doesn't act twice.

## Backlog (discussed with the user)

- **Posters & plots**: will come from the backend. Put the image url in the item's `thumb` — the
  details panel already shows it. Maybe later a poster grid for Movies.
- Search across Movies + Shows (client-side filter, voice if the remote has a mic).
- Preferred audio/subtitle languages (e.g. LT, then EN), picked automatically.
- **Player improvements**: findings + suggestions (overlay, seeking, buffer, tracks, chapters/skip
  intro, next-up, ...) in `docs/player-improvements.md` — not started, user to pick what to build.
- Smaller things: proper release signing (release uses the debug key), possibly newer media3 once a
  matching FFmpeg decoder exists. Server-side: duplicate releases and `.sample.` files are listed.
