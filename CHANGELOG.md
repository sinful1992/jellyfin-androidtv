# Changelog (hero builds)

Builds of this fork (sinful1992/jellyfin-androidtv) on top of upstream Jellyfin Android TV 0.20.0.
Version `0.20.0-hero.N`, versionCode `2000NN`, tag `v0.20.0-hero.N`.
Every commit that changes `app/` adds its line here, under `Unreleased` until the build is tagged.

## Unreleased
- Upstream master merged (56c6fc44d = v0.20.0-beta.4 344d26eb5 + 9 translation updates): saved logins are written with AtomicFile so a crash mid-write can't lose them (3867cfa48); 3D badge on movies (8c7879ceb); browse grids take focus when they open (22fbde87e); the expandable overview in list pages works again (dd6701828); Kotlin 2.4.21, Gradle 9.8.1, kotest 6.2.6, aboutlibraries 15.3.0; translations. Not taken because the code is gone in this fork: the previous-episode button fix (button removed in hero.48) and two legacy-player fixes (legacy player deleted; the media3 player already counts only in-container subtitle tracks).

## [v0.20.0-hero.52](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.52) (2026-10-21)
- No black screen when Jellyfin is opened after the TV dropped it in standby, or after a display setting such as text size changes: the reopened screen used to throw away the page it was given and show nothing until Back (bae0ce400).
- Pages restored after a standby restart are removed instead of piling up in the saved state, one more after every restart (45431f981).
- BlackScreenHeal also catches a nav host that was never given a page 2 s after it starts (a page still being committed does not count): it carries out the pending page again (a deep link included), logs cured=true/false and sends one report per process (bfacec3d4, b3d7fb599, a723984b4).
- When a display setting such as text size rebuilds the screen on a page deeper than home, Back from that page now leaves the app instead of returning to the page before it (it used to go black instead).

## [v0.20.0-hero.51](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.51) (2026-10-20)
- Next Up card during playback is smaller (at most 380 dp, 64 dp thumbnail, fits its text) and sits in the bottom-right corner; it moves up above the controls only while they are open (f2bcb1480).
- No BlackScreenHeal report when the app is in the background or the TV goes off, before or during the check (a resume on a hidden window looks once more a second later): a hidden window always reads 0x0, so those reports were false alarms. A shown window is still healed and reported (16a21680a, ae59c1a0b, 0692a3b2e).

## [v0.20.0-hero.50](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.50) (2026-10-19)
- Movies sorted by Name keep each collection together, in release order (Harry Potter 1–8, Ice Age 1–6…), placed where the collection's first film sorts (c0b67c86b). A start letter or another sort uses the old A–Z list.
- In vertical library grids, Right on the last card of a line goes to the first card of the next line, and Left goes back (0cc22adee).

## [v0.20.0-hero.49](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.49) (2026-10-19)
- The Collections library opens one grid of the movies themselves: collections A–Z, each collection in release order. Sort and letter are hidden there (f8a3499c4).
- Next Up card: season/episode on the label line, the full episode name on up to 3 lines, Watch now under it (b0fb5c402).
- Library grids scroll up and down unless a library is set to sideways, and the columns fit the screen width (608c22990, 9400fdd76).

## [v0.20.0-hero.48](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.48) (2026-10-19)
- Episode page: a whole-season Episodes row opened on the current episode, and a Seasons row to switch season in place (hidden for one-season shows). The Prev button and the Next episode row are removed (4dc7da438).
- Moving between episodes in that row replaces the page, so Back goes to the series (ffdd74735). Only the Episodes row replaces the page; OK on the opened episode's own card goes back to its buttons; a season that fails to load removes its empty row (e6906d1eb).
- Refreshing a swapped season's episodes after playback keeps the focus (ef3074155), and a failed refresh keeps the cards already shown (7227d63ff).
- About 660 lines of dead code removed: legacy PlaybackManager and helpers, the interaction tracker and sibling row (0378f38fe, 59930a438).

## [v0.20.0-hero.47](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.47) (2026-10-19)
- Playing a series no longer fails with "Video playback error" when the server's Next Up is empty: resumable → episode after the latest played → first unplayed → S1E1. Fully watched series show "Play next up" for a rewatch (605f709e5).

## v0.20.0-hero.46 (no tag, built locally)
- No record of its contents.

## v0.20.0-hero.45 (2026-10-13)
- DTS-HD by default, with an automatic fallback to the DTS core if playback stalls.
- Audio language and subtitles come from the server user's settings. Passthrough-capable track picked first.
- Start-up AC3 freeze fixed.

## v0.20.0-hero.44 (no tag, built locally)
- DTS decoded on the device instead of passed through. A test build: it showed the TV downmixes to stereo. Its commits are part of hero.45.

## v0.20.0-hero.43 (2026-10-12)
- Track choices are per user (60-day expiry). Forced subtitles after subtitles are turned off. Player stop-error guard. Back-history fix. Upstream sync.

## v0.20.0-hero.42 (2026-10-04)
- Black-screen self-heal, with one silent report per process.

## v0.20.0-hero.41 (2026-10-04)
- Stale focus is repaired before key dispatch (crash guard).

## v0.20.0-hero.40 (2026-10-04)
- Only the picked tracks are reported. Same-language tracks are matched by title.

## v0.20.0-hero.39 (2026-10-04)
- Season titles in Recently Added.

## v0.20.0-hero.38 (2026-10-04)
- The per-series track carry is saved across restarts.

## v0.20.0-hero.37 (2026-10-04)
- Seek after an audio switch (fixes the passthrough freeze).

## v0.20.0-hero.36 (2026-10-04)
- Subtitle and audio choice carried across a series.

## v0.20.0-hero.35 (2026-10-04)
- Server fallback for files the player refuses (Matroska zlib).

## v0.20.0-hero.34 (2026-10-04)
- No stray previous-item fetch on play (stale playlist item and 401).

## v0.20.0-hero.33 (2026-10-03)
- Cards load at preview quality, and the focused card is sharpened.

## v0.20.0-hero.32 (2026-10-02)
- Home asks the server for the libraries once per cold start (43f2be166).
- Also from untagged hero.23–31 (fork commits between the hero.22 and hero.32 tags, plus an upstream sync):
  - Item backdrops removed from the whole app; the details page shows its item's picture, clear of the clock and logo.
  - Season page: every season as a text button strip under the episodes, loaded in one light request.
  - Movies grid stays ungrouped regardless of filters; the unwatched filter is an eye toggle.
  - Long focused titles scroll once; the seek bar moves in steps; backdrop/summary work waits while the selection moves.
  - Playback: tracks switched client side only on direct play and kept when the stream is re-resolved; progress sent only for the open play session; loudness enhancer only when there is gain.
  - Home rows laid out after a rebuild from saved state; resuming into a fresh process lands on a screen; folder page loads Continue watching and Latest.

## v0.20.0-hero.22 (2026-09-16)
- Includes untagged hero.10–21 (fork commits between the hero.9 and hero.22 tags):
  - Grid cards are recycled (enough views kept for scrolling); backdrops decoded once; unfocused cards don't ask for a layer.
  - Playback: tells the server when playback stopped and where; resumes where the item was left; keeps reporting progress; marks an entry watched when the queue moves on; refreshes the lists playback changed.
  - Playback: a single HDMI plug drop no longer stops playback; play can retry a failed item; skipped unplayable entries are announced; a scrub ends cleanly if the seek bar goes away.
  - Subtitles can be switched on files that carry external ones; the info panel closes with the controls.
  - Test builds no longer write into the real app's server settings.

## v0.20.0-hero.9 (2026-09-11)
- Live TV libraries the app can no longer browse are not listed (50cd8b9ab).

## v0.20.0-hero.8 (2026-09-11)
- Legacy video player and live TV removed.

## v0.20.0-hero.7 (2026-09-11)
- New video player by default. Audio track switching fixes.

## v0.20.0-hero.6 (2026-09-10)
- Fix back-navigation crash after onSaveInstanceState (fa983bc64).
- Also from untagged hero.2–5: the focused card is easier to see across a room (focus ring drawn from the modifier chain, grid tiles get a focus state, room reserved for the card to grow, Next Up ring matches).

## v0.20.0-hero.1 (2026-09-09)
- Home hero as a card, on the 0.20.0 line.

hero.2–5, 10–21, 23–31, 44 and 46 had no tag (built locally). Their changes are listed under the next tagged build, from the fork's commits; which numbered build first carried each change is not known.
