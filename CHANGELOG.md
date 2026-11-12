# Changelog (hero builds)

Builds of this fork (sinful1992/jellyfin-androidtv) on top of upstream Jellyfin Android TV 0.20.0.
Version `0.20.0-hero.N`, versionCode `2000NN`, tag `v0.20.0-hero.N`.
Every commit that changes `app/` adds its line here, under `Unreleased` until the build is tagged.

## Unreleased
- Build only (no app change): Kover 0.9.11 line coverage on every Android module. `./gradlew testDebugUnitTest koverXmlReportDebug`, then `node scripts/kover-total.mjs` prints each module and the total (audit 137 rank 17, task 143). A report that is cut off or empty (no LINE counter) stops the script with exit 1 instead of counting as a module with 0 lines, and so does a report cut off inside a package, which used to print that package's last class as the total (task 145, linus #10664).
- Release headings for hero.55 to hero.59, sorted by the build each line first shipped in; tags v0.20.0-hero.55..59 point at the commits those APKs were built from (audit 137, task 145).

## [v0.20.0-hero.61](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.61) (2026-11-12)
hero.60 was built on 2026-11-11 but never installed or tagged; its line is the second one here.
- The details page no longer stays black with nothing on it when it is opened while the app is stopped. It asked the server for its item, and when the answer came while the page was not started (the TV off), it threw the item away and never asked again, so the page stayed empty until Back (caught live on the Shield on 2026-11-12: the page was full size with no rows, task 148). The item and its rows are now kept and shown when the page starts. The refresh after playback no longer reads an item that has not arrived yet. Tests: RunWhenStartedTests (8), FullDetailsHeldUntilStartedTests (5).
- The player no longer goes back to the details page while the app is stopped after the TV was switched off during playback. Switching the TV off drops the HDMI link, and the page was then built while the app was stopped (task 147; the 0x0 size seen on 2026-11-11 was the hidden window, not the cause, see the line above). The player now waits until it is resumed again before leaving (a link lost between pause and stop counts too; the line "Display link lost" now shows in release logcat), and the black-screen heal also checks when the window gains focus, which only happens once it is on screen (the resume checks at 0.5 s and 1.5 s could both run before a waking TV showed the window). Tests: LeaveWhenShownTests (6), BlackScreenHealTests +2.

## [v0.20.0-hero.59](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.59) (2026-11-10)
- Progress is no longer reported twice every 10 seconds after an episode boundary or a skip: the two parts of the play session reporter that start and stop the 10-second progress loop could interleave so that a loop just started was forgotten, never stopped, and kept reporting beside the next one until playback paused. The loop's handle is now changed under its own lock and a stop clears only the loop it stopped (JF-1, audit 137, task 139). A stress test with the thread pool kept busy failed on the old code in 4 of 4 runs. After the stress, pause, play and stop still end and start the one loop; a skip while paused opens none.
- An episode skipped (or stopped with Back) while the server was still being asked for its stream is no longer started for a moment when the stream arrives: the player goes straight to the episode the queue is on, and the server is not asked to open a stream nobody watches. An episode with no playable stream that was skipped meanwhile no longer skips the episode after it either, and shows no message. Its resume position is kept for when it is played after all (JF-2, audit 137, task 139). The same when it is left during the second request a carried audio track makes for a converted stream (test added after hero.59 was built; no code change).
- A pause pressed while an audio or subtitle change (or a direct-play fallback) waits for the server is kept: the new stream starts paused. It used to start playing, because the pause state was read before the server was asked. Play pressed during that wait is kept the same way (JF-5, audit 137, task 139).
- No crash when Back is pressed in the last moment of an episode: the player's own end-of-item message, arriving after the stop, found nothing playing and stopped the app. It is now ignored, as a player error with nothing playing already was (JF-3, audit 137, task 139). The message is handed to the listener directly in the test; that the player sends it after a stop on a TV is read from its code, not seen on a device.

## [v0.20.0-hero.58](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.58) (2026-11-06)
- Resume no longer crashes Dolby Vision profile 7 films whose second (enhancement) layer starts at different frames from the picture, such as Alice in Wonderland (1951) on the Shield: a new decoder gets that layer only from the layer's own next start point (under a second in the files measured, with its full setup), so the picture and its Dolby Vision data play from the first frame. NVIDIA's decoder crashed the media process on every such resume, leaving a black screen (task 127). Films whose layers start together are passed untouched, and a seek while playing is unchanged. Any decoder given such a file skips that layer for the same moment after a resume, not only the Shield's.

## [v0.20.0-hero.57](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.57) (2026-11-06)
- Display mode switch settle cut from 5 s to 2 s (db739ac64, branch perf/shield-start-settle). Rolled back the same day: the TV blanked for about 4 s more often, so hero.56 went back on the Shield and this change is not in later builds (task 127).

## [v0.20.0-hero.56](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.56) (2026-11-06)
- Refresh rate switching changes the TV's mode for 23.976/24 fps films (their own rate) and 25/50 fps shows (50 Hz, below); 29.97 and 30 fps shows stay in the TV's normal mode, which they already fit evenly (task 127).
- No black player when an HDMI re-sync during the frame-rate switch keeps the player stopped past the end of the switch: the film now starts when the player is shown again, after 5 s for the returned HDMI link to settle (so TrueHD passthrough is kept), where before it never started and the TV stayed at the film's rate until Back (task 126 review, linus #8791).
- Back in the player now ends the play session on the server: it used to be opened again as paused about 2 s after the stop and stayed in the dashboard for about 9 minutes. The pause's progress report was still being sent when Back's stop went out and reached the server after it; play session reports now go out one at a time in the order they were asked for, and one queued behind the stop is dropped (#8757, task 127).
- 25 and 50 fps shows switch the TV to 50 Hz, never 25 Hz: each 25 fps frame is shown twice, evenly, without the low-refresh look the household found hard on the eyes at 25 Hz (USER, task 127).
- A 29.97/30 fps show opened straight after a film no longer plays at the film's 24 Hz: when a video wants no mode of its own, the TV is put back in its default mode an earlier player left changed (linus #8873); a video already in its right mode keeps it.
- The HDMI-link monitors wait for a film start held back by a long re-sync, so they can't close the player mid re-sync before the film begins (linus #8873).

## [v0.20.0-hero.55](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.55) (2026-11-05)
- Refresh rate switching works again in the new player: films switch the TV to their own frame rate (23.976 or 24; see below for the other rates) before playback starts, and the TV goes back to its normal mode when the player closes. The setting was left behind when the legacy player was removed, so it did nothing; on the Shield, which has no system frame-rate matching, 24 fps films played at 59.94 Hz (task 125). The display-link monitors start only after the switch, so the HDMI re-sync can't end playback. The queue is filled only once the switch has settled, so the audio track pick (TrueHD passthrough) is never made mid re-sync. The next episode keeps the first item's mode: a per-entry switch could make the audio pick mid re-sync with the monitors off.
- Leaving the player (Back or Home) while the TV is still switching modes no longer starts the film behind the home screen: a cancelled or left player now skips filling the queue, where before the swallowed cancellation still filled it and played sound with nothing on screen (task 126 review). Coming back to the player before the switch ends still starts the film, and a player closed while it fetches the item no longer keeps the TV at the film's rate.

## [v0.20.0-hero.54](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.54) (2026-10-30)
- Upstream master merged (7ec47a001, d55498aff): the home and search rows are pinned to reach RESUMED (de2dced2b, upstream's fix for home content missing after the app is reopened from the background; this fork already reached RESUMED in every AVD path tried, so here it is a safety net); Gradle 9.8.2, kotlinx-serialization-json 1.12.0; translations (the Lithuanian merge left two "auto-play next episode" lines, which breaks the build; only the new one is kept).

## [v0.20.0-hero.53](https://github.com/sinful1992/jellyfin-androidtv/releases/tag/v0.20.0-hero.53) (2026-10-23)
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
