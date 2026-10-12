# Player improvements — findings & suggestions

Analysis from 2026-10-12, nothing implemented yet. Covers `PlayerActivity.kt` as of v0.2.

Effort: **S** = small (hours), **M** = medium (about a day), **L** = large or needs backend work.

## What the library contains

Sampled read-only with `ffprobe` against the stream urls. 60 movie folders were listed for file
types, 10 movies and 6 show episodes were probed for tracks, and the first episode of every show
(56) was probed for chapters.

- **Containers:** almost all MKV (123 MKV, 2 AVI, 1 MP4 in 60 movie folders).
- **Video:** mostly H.264 SD/720p/1080p. Some HEVC 10-bit, and 4K HDR10/Dolby Vision files of
  about 16–21 GB (roughly 15–20 Mbps), e.g. The Matrix series.
- **Audio:** nearly every movie has a **Lithuanian dub plus the English original**, with descriptive
  track titles ("Lietuvių profesionalus daugiabalsis dubliažas", "Prof. dubliuotas", "Original",
  "Anglų (Originali)"). Language tags are mostly correct (`lit`, `eng`, sometimes `rus`). Some
  files have no tags at all (`und`, e.g. `lilo.mp4`, PAW Patrol). Codecs: AAC (LC/HE), AC3, E-AC3
  5.1/7.1 Atmos, DTS, FLAC.
- **Subtitles:** mostly SRT, LT and/or EN. Sometimes split by title into "LT (Forced)" and
  "LT (Full)", and English is sometimes split into normal and "SDH". 4K remuxes add PGS image
  subtitles. Disney+ rips carry up to 13 subtitle languages.
- **Extra streams:** some MKVs contain cover art as mjpeg "video" streams, and some have TTF font
  attachments. Neither matters for playback.
- **Chapters:** 18 of 56 shows have chapters. 9 have **useful markers**:

  | Show | Markers |
  |---|---|
  | Zootopia+, Alien Earth, Spider Noir | Intro, Credits |
  | Westworld | Intro, Credits |
  | And Just Like That... | Credits |
  | The Expanse, American Gods | Amazon Original, Previously On, Opening Credits |
  | Reacher | Amazon Original, Title Sequence |
  | Elementary | descriptive scene names, Opening ... |

  The rest are numbered ("Chapter 1", "00:05:23.990"). Some movies have named scene chapters
  (The Matrix: "Trinity in a Jam", ...).
- **Media3 ignores MKV chapters.** The 1.9.4 `MatroskaExtractor` doesn't even know the
  Chapters element id. `Player` has no chapter API either, so we have to read chapters ourselves.

## Problems in the current player

1. **Hold-to-seek sends one seek per key repeat** (`dispatchKeyEvent` → `seekBy` → `p.seekTo`,
   `PlayerActivity.kt:233-234`, `:263-273`). Each seek is a new HTTP range request into a large
   file, so holding Left/Right on 4K files is likely to stutter. Better: move a preview position
   while the key is held and seek once on release (or after ~500 ms idle).
2. **No back buffer.** `DefaultLoadControl` keeps 0 ms behind the play position by default, so
   every 10 s rewind re-downloads from the server and rebuffers. Fix:
   `DefaultLoadControl.Builder().setBackBuffer(30_000–60_000, true)` (and maybe a bigger forward
   buffer). Watch memory on 4K: 30 s at 20 Mbps ≈ 75 MB, and the default video byte cap is about
   125 MB. Raise it with `setTargetBufferBytes` only within what the TV's RAM allows.

## Suggestions

### Controls & look

- **Own TV-style overlay** (M). Turn off the default controller (`useController = false`) and
  keep `PlayerView` for the video surface and subtitle rendering. The overlay would have:
  - a big title with the show/season/episode
  - the clock and "ends at 22:41"
  - a progress bar with buffered range and chapter ticks
  - one row of large focusable buttons: Audio · Subtitles · Episodes · Chapters · Zoom · Speed · Info
- **Scrub then seek** with a time preview (S). Fixes problem 1.
- **Thumbnail previews while scrubbing** (L, backend). The backend would generate sprite sheets with
  ffmpeg, like Jellyfin's trickplay. Generating them on the TV from remote 20 GB files isn't practical.
- **Extra keys** (S):
  - while controls are hidden, Up/Down = previous/next chapter
  - number keys 1–9 = jump to 10–90 % (if the remote has them)
  - media keys (play/pause, ff/rew, next/prev) should keep working

### Audio & subtitles

- **Automatic languages** (S). Use `TrackSelectionParameters`: `setPreferredAudioLanguages("lt", "en")`,
  then choose subtitles from the audio that got selected: none with LT audio, LT subtitles with EN
  audio. Treat titles matching `forced` as forced-only tracks. Configurable later in settings.
- **Remember the choice per show/folder** (S). If you switch Westworld to EN + LT subs once, the
  next episodes keep it. Store it next to `Progress`, keyed by folder; apply it with
  `TrackSelectionOverride` by language + title, since track indexes differ between files.
- **Readable track picker** (S). Show language, then the track title, then codec and channels, e.g.
  "Lietuvių · Profesionalus daugiabalsis dubliažas · AAC 2.0" or "English · Original · DTS 5.1".
  Mark tracks that go through FFmpeg. Show `und` tracks as "Track 1/2" rather than "Unknown".
- **Subtitle style** (S). `SubtitleView.setStyle(CaptionStyleCompat(...))` for background box and
  outline, `setFractionalTextSize` for size, `setBottomPaddingFraction` for position.
- **Subtitle / audio delay** (M–L). Media3 has no public API for either. Only build this if
  out-of-sync files actually come up.

### Episodes & chapters

- **"Next episode" card** (S). Show it near the end, or when the "Credits" chapter starts:
  "Next: E04 in 15 s [Play now] [Stop]". Today the credits play in full before auto-advance.
- **Episode strip in the player** (M). Down or the Episodes button opens a row of the folder's
  episodes with watched marks, so you can jump without leaving playback. The playlist is already
  passed in.
- **Skip intro / skip recap** (M). A button appears while inside a chapter whose title matches
  `intro|opening credits|title sequence|previously on|recap|amazon original`. It works for the 9
  shows above. Shows without chapters would need server-side intro detection (audio
  fingerprinting, like Jellyfin's intro-skipper plugin) (L).
- **Chapter list** (S, once chapters are available). For movies with named chapters.
- **Where chapters come from.** Two options:
  - (a) **Client:** read the MKV header with 1–2 range requests. Find the `SeekHead` entry for
    Chapters, read the Chapters element, parse EBML (~150 lines).
    Element ids: Segment `0x18538067`, SeekHead `0x114D9B74`, Seek `0x4DBB`, SeekID `0x53AB`,
    SeekPosition `0x53AC`, Chapters `0x1043A770`, EditionEntry `0x45B9`, ChapterAtom `0xB6`,
    ChapterTimeStart `0x91` (ns), ChapterDisplay `0x80`, ChapString `0x85`.
  - (b) **Backend:** run `ffprobe -show_chapters` once per file and add
    `chapters: [{start, end, title}]` to the playable items. It's cached, adds no requests at play
    time, and works for MP4 too.

  **Preferred: (b)**, if the backend is being changed anyway for posters/plots.

### Comfort

- **Sleep timer** (S): after this episode / 30 / 60 / 90 min.
- **Zoom modes** (S): fit / fill / zoom via `PlayerView.setResizeMode`. Useful for very wide films
  like Rumble (1024×426).
- **Speed** 0.75–2× (S). It's already in the default gear menu; move it into the new button row.
- **"Start over" button** (S) for the first ~10 s after an automatic resume. Today there's only
  the "Resuming from" toast plus the ⋯ menu entry.
- **Info screen** (S): resolution, HDR/DV, video and audio codecs, whether audio is decoded by the
  TV, passed through or decoded by FFmpeg, bitrate, buffer, dropped frames. `DebugTextViewHelper`
  is a quick start. Useful when something stutters.

### Playback quality / system

- **Back buffer + buffer tuning** (S). Fixes problem 2.
- **24p frame-rate matching** (S–M). Media3 only switches refresh rate when it's seamless
  (`VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS`). Forcing a switch via
  `window.attributes.preferredDisplayModeId` removes judder on films, but the screen blanks for
  1–2 s. Whether it works depends on the TV model.
- **MediaSession** (M, `media3-session`): "Hey Google, pause", phone and HDMI-CEC remote media
  controls, the system "now playing" card.
- **Passthrough vs. decode setting** for DTS/Atmos (S). Only relevant with a soundbar/AVR.

## Recommended approach

Build an **own overlay on top of `PlayerView`** with its controller off. That gives full control
over the layout, track pickers, episode strip and next-up card, and it fits the views/leanback
code in the rest of the app.

- Restyling the default controller (`controller_layout_id`) is cheaper, but it keeps the
  phone-style popups.
- Leanback's `PlaybackSupportFragment` + `media3-ui-leanback` is dated and hard to customize.
- Compose for TV + `media3-ui-compose` is only worth it if the whole app moves to Compose.

## Possible phasing

1. **v0.3:** own overlay + scrub-then-seek, back buffer, automatic languages remembered per show,
   readable track picker, next-episode card, episode strip, sleep timer, zoom, info screen.
2. **v0.4:** chapters (backend or client), skip intro/recap, chapter list, subtitle style,
   24p matching.
3. **Later / backend:** trickplay thumbnails, intro detection, MediaSession.

## Open questions for the user

- Does the TV remote have number keys? A microphone (relevant for search too)?
- Is there a soundbar/AVR (passthrough setting)?
- Chapters: add to the backend, or read on the TV?
- Language rules: always LT dub first, or depends (e.g. EN + LT subs for adult shows, LT dub for
  kids' films)?
