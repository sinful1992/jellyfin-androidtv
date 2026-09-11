# Scope — removing the legacy video player

Written 2026-09-11, after `v0.20.0-hero.7` shipped the new player as the default.

Decision taken: live TV is **not used** and may be deleted outright rather than ported. If upstream
later builds live TV (or zoom, or chapters) on the rewrite player, take their implementation then —
it will be new-player code, so it lands cleanly on a tree that no longer has the legacy one.

## What the measurements actually say

| | lines | upstream commits, 12mo | upstream commits, 3mo |
|---|---|---|---|
| `ui/playback` top level (legacy + shared) | 5,144 | 31 | **0** |
| `ui/playback/overlay` (23 files) | 1,607 | (included above) | **0** |
| `ui/livetv` (6 files) | 1,439 | 5 | **0** |
| `MediaManager` (audio) | — | 0 | **0** |

Zero upstream churn in three months across all of it. The usual objection to deleting upstream
files — every future rebase hits a delete/modify conflict — is weak here.

## The one correction that shrinks this job

**Audio is already on the rewrite core.** `PlaybackModule.kt:42` binds
`single<MediaManager> { RewriteMediaManager(get(), get()) }`, and `RewriteMediaManager` drives
`PlaybackManager`. Music does not run through `PlaybackController` at all.

What the music screens actually borrow is one **enum**: `PlaybackController.PlaybackState`, used by
`AudioEventListener.kt:6`, `ItemListFragment.java`, `MusicFavoritesListFragment.java`,
`AudioNowPlayingFragment.java` (missed on the first count — five call sites, not four), and mapped
from `PlayState` in `RewriteMediaManager.kt:90-93`. That is the whole coupling.

So music is **out of scope** — it keeps working, untouched, once the enum moves.

## Stages

Each stage leaves the app building and usable, and is one commit.

### 1. Move `PlaybackState` off the legacy controller — **done**
Taken the second way: the call sites moved onto `PlayState` directly rather than the enum being
extracted. `AudioEventListener.onPlaybackStateChange` now takes `PlayState`, and the translation
`when` block in `RewriteMediaManager` is gone entirely — the audio path only ever produced the four
states `PlayState` already has (`IDLE`→`STOPPED`, `PLAYING`, `PAUSED`, `ERROR`); `BUFFERING`,
`SEEKING` and `UNDEFINED` were legacy-video-only. Net deletion rather than a move.
`PlaybackController` keeps its own enum for its own use until stage 3.
Afterwards the music screens no longer reference the legacy player. `PlaybackControllerContainer`
still does, from `AppModule.kt:141`, `SocketHandler.kt:57` and `InteractionTrackerViewModel.kt:22`;
that goes in stage 3, because the `SocketHandler` branch it feeds is only unreachable once the
legacy player cannot play.
**Small. No behaviour change.**

### 2. Make the legacy video player unreachable — **done** (`2fe1974c8`)
- Drop the "Built-in video player" option from `SettingsPlaybackPlayerScreen`
- Remove the `playbackRewriteVideoEnabled` preference and the branch in `PlaybackLauncher.launch`
- Remove `Destinations.videoPlayer` (keep `videoPlayerNew`)
- External player path stays exactly as it is

**Decided 2026-09-11: delete.** The fork's releases are installed by one person on two TVs, so
the "someone installs it and needs a way back" case the caution was written for does not exist.

Recovery is the archived `v0.20.0-hero.7` APK, which stays downloadable. Note that going back to it
is a *downgrade* of the same `applicationId`, so it needs uninstall → reinstall and therefore a
Quick Connect re-auth. If that is ever too costly, the cheap variant is a fallback build with a
different `applicationId` so both can sit on the TV at once — but the Chromecast runs ~95% full and
needs ~450MB free to install, so a second permanent release build is not free there.

Rejected alternatives, for the record:

- **Hide the option, keep the code.** Shrinks nothing: every future rebase still lands on
  `PlaybackController.java`, `CustomPlaybackOverlayFragment.java`, `ui/playback/overlay` and
  `ui/livetv`. Its only real advantage is in-place recovery without re-auth.
- **Two build flavours from one branch.** Needs the legacy player to stay in the tree, so stages
  3-5 cannot happen. It is "hide" by another name.
- **Two branches, both maintained.** The fork is 80 commits ahead of upstream and rebases by hand;
  doing that twice for a player upstream is not developing either is the cost this plan exists to
  avoid. A *frozen* second branch is free, and that is what the archived hero.7 already is.

After this stage the legacy player is dead in the literal sense, which it is not today.

### 3. Delete the legacy video player — **done** (`253106202`, 58 files, 7,011 deletions)
`PlaybackController.java` (1,361), `CustomPlaybackOverlayFragment.java` (1,356),
`VideoManager.java` (677), the whole `ui/playback/overlay` package (1,607), `VideoPlayerAdapter`,
`PlaybackControllerContainer` plus its wiring in `AppModule.kt:141`, `SocketHandler.kt:57`,
`InteractionTrackerViewModel.kt:22`, and `ReportingHelper` entirely — which is
`util/apiclient/ReportingHelper.kt` (116 lines, Kotlin), not `ui/playback/ReportingHelper.java`, and
is bound in `AppModule.kt` so the Koin binding goes with it.
Plus the layouts and view bindings they own.

**Found during stage 2, not in the original list:** `ui/playback/nextup/NextUpFragment` and
`ui/playback/stillwatching/StillWatchingFragment` are legacy-only screens. The only navigation to
them is `CustomPlaybackOverlayFragment.java:1333` and `:1340`, driven by
`PlaybackController.java:1196-1209`; the rewrite player draws its own in `ui/player/video/PlayerNextUp.kt`.
Stage 2 left them orphaned, so they delete here along with `Destinations.nextUp` / `stillWatching`.
Checked 2026-09-11, and the three preferences behind them split three ways:

| preference | read by the rewrite player? | consequence |
|---|---|---|
| `nextUpBehavior` | **yes** — `PlayerNextUp.kt:88,101,111` | keep; its settings screen keeps working |
| `nextUpTimeout` | **no** — `PlayerNextUp.kt:58` ignores it on purpose, using a 30s lead time instead | the slider in `SettingsPlaybackNextUpScreen.kt:73-105` becomes a dead control |
| `stillWatchingBehavior` | **no** — only `PlaybackController.java:1204` reads it | `SettingsPlaybackInactivityPromptScreen` becomes a dead control, and "are you still watching?" is lost as a feature |

`InteractionTrackerViewModel.getShowStillWatching()` has no consumer once `PlaybackController` goes.

**This is a feature loss the "what is lost" list did not cover.** Leaving the controls in place while
they do nothing is worse than removing them, so either they go with the player or still-watching is
ported to the rewrite player first.

Two things checked rather than assumed:

- **`ReportingHelper` is legacy-only.** Its sole caller is `PlaybackController.java:68`; the
  rewrite player reports progress through its own path. It deletes rather than needing its
  signature rewritten.
- **Remote control already works without it.** `SocketHandler` branches on `rewritePlayerActive`
  and drives `state.*` for the rewrite player; the `PlaybackControllerContainer` branch is the
  `else`. Deleting it removes a branch that is already unreachable whenever the new player is what
  is playing.

**Medium.** Mechanical once stage 2 is in.

### 4. Delete live TV — **done** (`28901cff5`, 69 files, 5,384 deletions)
Forced by stage 3: `CustomPlaybackOverlayFragment implements LiveTvGuide` and owns
`OverlayTvGuideBinding`, so the in-player guide dies with it and the rest is orphaned.
`ui/livetv` (6 files, 1,439 lines), `GuideChannelHeader`, `GuidePagingButton`, live TV preferences
and strings.

The entry points are the risk. The first count said 5 files outside `ui/livetv`; measured after
stage 3 it is **13**, because the first pass only looked for `BaseItemKind` uses and missed
everything importing `ui.livetv` directly:

```
ui/GuideChannelHeader.java          ui/browsing/BrowseScheduleFragment.java
ui/GuidePagingButton.java           ui/browsing/EnhancedBrowseFragment.java
ui/LiveProgramDetailPopup.java      ui/browsing/BrowseViewFragmentHelper.kt
ui/ProgramGridCell.java             ui/browsing/composable/inforow/BaseItemInfoRow.kt
ui/itemhandling/ItemLauncher.java   ui/home/HomeRowsFragment.kt
ui/navigation/Destinations.kt       ui/itemdetail/FullDetailsFragment.java
ui/settings/routes.kt + 3 screens under ui/settings/screen/livetv/
```

`GuideChannelHeader`, `GuidePagingButton`, `ProgramGridCell` and `LiveProgramDetailPopup` are
whole-file deletions. `BaseItemInfoRow`, `HomeRowsFragment`, `FullDetailsFragment` and
`ItemLauncher` are the surgical ones — they render or route every item, not only live TV.

**Medium, and the one stage with real regression risk outside playback** — `BaseItemInfoRow` and
`FullDetailsFragment` render for every item, not only live TV, so the edits there have to be
surgical.

### 5. Sweep — **done** (`eb2002365`)
110 strings and 15 drawables, scoped to what stages 1-4 orphaned by diffing references at
`v0.20.0-hero.7` against `HEAD`. 69 strings that were already dead before this work were left
alone: removing them widens the fork's diff against upstream for no gain.

**APK size, measured:** hero.7 27,879,793 bytes -> hero.8 27,589,188 bytes. **290,605 bytes, 1.0%.**
Less than the 12,000 deleted lines suggest, because the APK is dominated by native libraries and
resources rather than dex.

## What is lost, deliberately

- Live TV entirely — guide, channel bar, recordings, record/cancel, previous channel
- Zoom / aspect ratio control (fit, stretch, zoom, auto-crop)
- Chapter navigation
- The ability to fall back to the old player when the new one misbehaves

The last one is the one to think about: today a bad rewrite-player bug is two clicks away from a
working fallback. After this it is a reinstall of an older APK.

## Order of work

Stages 1 and 2 are worth doing regardless — they are small, they are reversible, and they turn
"legacy is still wired in everywhere" into "legacy is genuinely dead code". Stages 3-5 are the
commitment. Stage 4 is the only one that removes a feature that currently works.


## Resolved 2026-09-11 — the seek/resume warning is a false alarm

`Trying to seek but ExoPlayer doesn't support it for the current item` fires once per start and
means nothing. Resume works.

`ExoPlayerBackend.seekTo` logs the warning and then seeks anyway — it is not a guard. The check is
simply made at the wrong moment:

1. `playItem` calls `prepareItem` internally, which does `addMediaItem` + `prepare()`
2. That puts ExoPlayer in `STATE_BUFFERING` with `playWhenReady`, which the backend maps to
   `PLAYING` (`ExoPlayerBackend.kt:213`)
3. `VideoPlayerFragment.awaitPlaybackStart` is waiting for exactly that first `PLAYING`, so it
   seeks — 12 ms before `playItem` reaches `exoPlayer.play()`
4. The timeline is not built yet, so `isCurrentMediaItemSeekable` is false and the warning prints.
   ExoPlayer keeps the seek as a pending initial position and applies it when the timeline arrives

Measured 2026-09-11: resume requested 219392 ms, first reported position **219691 ms** — a 299 ms
keyframe snap, not a dropped seek.

Two things worth doing about it, neither urgent:

- **The message is wrong and has now cost debugging time twice.** Either drop it or only warn when
  a seek was genuinely discarded.
- **`awaitPlaybackStart` is correct by accident.** Its KDoc says waiting for the first `PLAYING`
  "guarantees a prepared, seekable timeline". Since the buffering-with-intent mapping, that is no
  longer true — it works only because ExoPlayer defers the pending seek. Waiting for a seekable
  timeline would say what is actually meant.


## Outcome — all five stages landed 2026-09-11

| stage | commit | files | deletions |
|---|---|---|---|
| 1. `PlaybackState` off the legacy controller | `115ea49d4` | 5 | net -6 |
| 2. Legacy player unreachable | `2fe1974c8` | 7 | -40 |
| 3. Legacy player deleted | `253106202` | 58 | -7,011 |
| 4. Live TV deleted | `28901cff5` | 69 | -5,384 |
| 5. Resource sweep | `eb2002365` | 16 | -266 |

Green at every stage: `assembleDebug`, 71 tests, detekt. The test count fell from 91 because 20
tests covered deleted code (`VideoSpeedControllerTests`, `CustomSeekProviderTests`).

### Things the plan had wrong, found while doing it

- **Stage 1 had five call sites, not four.** `AudioNowPlayingFragment` was missed.
- **Stage 2 broke two files the plan never listed.** `NextUpFragment` and `StillWatchingFragment`
  navigated to `Destinations.videoPlayer` directly, *ignoring the preference* — so under hero.7 the
  Next Up screen would have opened the legacy player even with the new one selected. They were
  reachable only from `CustomPlaybackOverlayFragment`, so they were deleted in stage 3.
- **Stage 3 silently removed two features.** `nextUpTimeout` and `stillWatchingBehavior` had no
  reader but the legacy player, so "are you still watching?" and the next-up countdown are gone.
  Decided with the user rather than left as settings that do nothing.
- **Stage 4 was 13 files outside `ui/livetv`, not 5**, and reached `ItemRowAdapter`, `BrowseRowDef`,
  `CardPresenter`, `KeyProcessor` and `SdkPlaybackHelper` — the adapter and card layer behind every
  row in the app, not just live TV screens.
- **`ReportingHelper` was `util/apiclient/ReportingHelper.kt`**, not `ui/playback/ReportingHelper.java`.

### Not verified on a device

None of this has run on the Chromecast. The risk concentrates in stage 4's edits to
`ItemRowAdapter`, `CardPresenter` and `KeyProcessor`, which render and route every item, not only
live TV ones.
