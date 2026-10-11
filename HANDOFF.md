# Handoff: the sinful1992 fork of Jellyfin for Android TV

Rewritten 2026-10-12 (task 39) to describe how things stand **now**. It replaces the 2026-09-10 handoff about the
track-switch rebase, which was never committed. Fuller history is in the team repo under `jellyfin/` (crash, heal,
playback, server, verify, check).

---

## Repos and remotes

| Clone | `origin` | other remote | Who |
|---|---|---|---|
| ada (Windows laptop, the build clone) | upstream `jellyfin/jellyfin-androidtv` | `fork` = `sinful1992/jellyfin-androidtv` | builds, installs, pushes `dev` / `master` / tags |
| linus (home server, read + branch push) | `sinful1992/jellyfin-androidtv` | `upstream` = jellyfin | reviews, server-side checks, sync branch |

**Never push to upstream.** In ada's clone that is `origin`. In linus's clone it is `upstream`.

## Branches and releases

- **`dev`**: the work branch. Every release is a tag on it. At the time of writing it is at `f5ab84eab` (= hero.42).
- **`master`**: an ancestor of `dev`, fast-forwarded to the released `dev` after each release (task 39 brings it up
  from the `43f2be166` era).
- **`sync/upstream-2026-10`**: upstream `jellyfin/master` merged into the fork (task 39). It merges into `dev` only after
  the unit tests and the device check pass on it.
- **Tags** `v0.20.0-hero.N`, versionCode `2000NN`. The version comes from the `JELLYFIN_VERSION` env var
  (`buildSrc/.../VersionUtils.kt`).
- **Signed release:** USER runs `./gradlew assembleRelease` with the keystore. Keep the R8 mapping for each build:
  crash stacks are obfuscated, and each report names its map id (`r8-map-id-…`).
- Old branches (`spike/track-selection`, `feat/display-link-loss-*`, `release-0.x.z`, `renovate/*`) are history only.

## Devices

| Jellyfin device name | Hardware | adb | Build (10-12) |
|---|---|---|---|
| `Living Room TV` | Sony Bravia KD-65X75WL (VH22, MediaTek MT5895, Android 12, **32-bit armeabi-v7a**) | `192.168.1.10:5555` | hero.42 |
| `Master bedroom TV` | Chromecast with Google TV (`sabrina`, Amlogic) | wireless adb (tls-connect) | hero.41 |

- Only ada builds or installs the app (team rule #194). linus uses the server side and read-only adb.
- **Before any install, check `/Sessions` for a NowPlayingItem.** An install killed a live play on 10-06.
- `perf-tools/install.sh` installs and then AOT-compiles. Without that, a sideload runs JIT-only until the TV's idle
  dexopt runs, and home rows were measured 2–3× slower.
- Server: Jellyfin 12.x in Docker on the home server (`/mnt/data/docker-data/jellyfin`). Its API takes the
  `Authorization: MediaBrowser Token="…"` header. `X-Emby-Token` returns 401.

## What the fork changes (vs upstream)

- **Player:** the rewrite player (media3 ExoPlayer) is the only player. The legacy player, live TV, zoom/aspect,
  chapters, still-watching and the next-up timeout are deleted (`LEGACY-REMOVAL-PLAN.md`).
- **Tracks:** audio and subtitle pickers. A track choice is matched across files by what the track is (language,
  codec, title, forced), never by index. Choices carry per series and survive restarts (`TrackCarryStore`). The
  chosen tracks are reported to the server. Task 39: choices per user, a 60-day expiry, a reset in Settings →
  Playback, and forced subtitles survive "off".
- **Robustness:**
  - The server converts a file media3 refuses (zlib-compressed MKV tracks, hero.35).
  - A stalled renderer can't report PLAYING for more than 15 s after the first picture.
  - Playback stops when the HDMI link drops.
  - The player seeks after an audio switch on seekable items. This works around the DD+ passthrough
    "startup glitch", where the clock jumps +12 s and then stalls.
- **Navigation:** a stale-focus guard in `MainActivity.dispatchKeyEvent` (hero.41) fixes the FocusFinder
  "descendant of this view" crash after the TV wakes from standby. `BlackScreenHeal` (hero.42) detects a fragment
  view left at 0x0.
- **UI:** a home hero card and band, backdrops removed, a season button strip, a focus ring, trickplay while
  scrubbing, a quality override, and an English-only build (`localeFilters`).

## Tests and checks

- **Unit:** `./gradlew :playback:core:testDebugUnitTest :playback:media3:exoplayer:testDebugUnitTest`. Task 39 adds
  media3 test-utils + Robolectric cases: stop → clear → prepare, carry restore, seek after an audio switch, the
  per-user/expiry store, forced-after-off, and one resolve per start.
- **Device:** `perf-tools/playcheck.sh <adb-serial>` (task 39) runs steps C1–C6 and prints `STEP <id> PASS|FAIL`
  with UTC times.
- **Server oracle:** team repo `jellyfin/check/oracle.md` says what the server must show for each step. linus checks
  it with `tools/playcheck` (PlaybackInfo and stream requests per start) and `tools/trackcheck` (live stream indexes).
- JDK 21 is required; Gradle rejects 17. Don't pipe gradle through grep and trust the exit code.

## Crash reports

- ACRA uploads land on the server as `upload_Jellyfin for Android TV_<ver>_<ts>_<id>.log` in the Jellyfin log dir.
  The header always says `type: crash_report`, so read the first line of the stack trace.
- **Usher** (Discord, `homelab-monitoring-workflows/scripts/playback-watchdog.py`) posts every new report with a
  label and the file attached. The labels:
  - `heal`: `BlackScreenHeal … cured=true|false|detached`. `cured=false` means the user saw a black screen.
  - `stale-focus`: "Repaired stale focus" means the guard fired. "descendant of this view" means the guard missed.
  - `player-error`
  - `other`
- The server keeps logs and reports for 14 days (it was 3 until 2026-10-12).
- The signature table is in team repo `jellyfin/heal/signatures-linus.md`.

## Open problems (2026-10-12)

1. **Black screen after leaving the player** (Bravia, hero.42). 3 of 14 stops, 10-08 to 10-11. The
   `FullDetailsFragment` root is stuck with `layoutRequested=true`, and `BlackScreenHeal` reports `cured=false`
   every time. hero.43 ships extra diagnostics (the view chain, layout/draw counts, 1000 logcat lines).
2. **The `ExoPlayerImplInternal` NPE at teardown** (`reselectTracksInternalAndSeek` on an empty playlist after
   stop + clear) shows up at every stop and every power-off on the Sony. It is benign and not the black-screen
   cause, so a check must never pass or fail on that line alone.
3. **The first play after hours idle** holds the first stream GET for 9–13 s. This is client-side; the server
   answers in about 40 ms.
4. **EAC3 7.1 JOC in an HLS TS container stalls** on the Bravia, while fMP4 is fine.
5. **Stop position lags** up to about 14 s behind the screen. It is always behind, never ahead (comment in
   `ExoPlayerBackend.stop`).
6. Usher's live adb logcat feed from the Bravia has been unreachable since 2026-10-03, so client player errors
   reach the server only through ACRA uploads.

## Gotchas

- **`screencap` is black for the player window** (SurfaceView). Use `dumpsys media_session` for state and
  position, `dumpsys audio` for the open AudioTrack, and the HAL logs for the codec actually open.
- `C2BqBuffer: last successful dequeue was N us ago`: tens of thousands of µs is healthy; hundreds of millions
  means the decoder is wedged.
- Language codes: Jellyfin sends ISO 639-2/T (`zho`), files say `chi`, media3 uses 2-letter codes. Normalise both
  sides (`Util.normalizeLanguageCode`).
- On direct play, re-resolving the stream changes nothing: the server returns the same `static=true` URL, so
  tracks must be selected on the client.
- `VideoPlayerFragment` runs `queue.clear()` on every play, so anything keyed "per queue" is lost on a details-page
  start.
- Git Bash mangles adb device paths, so export `MSYS_NO_PATHCONV=1`. Compose exposes no accessibility semantics
  in the player, so `uiautomator dump` can't drive it.
- Dead-code traps: `<fraction>` resources, Leanback resource overrides resolved by name, and operator-convention
  imports (`getValue`, `setValue`, `times`, `plus`). Detekt's `UnusedImports` rule is off.
