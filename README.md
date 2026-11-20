
# Kuroba Experimental

### Changes in this fork:
Newest added features on top of v1.3.44

- Album: autoplaying video
- High-res GIF/MP4/WebM cell thumbnails
- Pinch to zoom: gallery column count, video/GIF zoom and pan
- MPV: control bar size settings, gpu-next surface fix, DoNotStrip annotations
- UI options: bottom navigation bar, never-hide toolbar, classic board dropdown, classic settings sizing
- 4chan flags parsed from API + new flag picker; spoiler toggle on attachments
- Cloudflare bypass popup only for sites actually opened
- Built-in media converter (ffmpeg): long-press an attached file in the reply box → "Convert media to…" — WebM (VP9/VP8), MP4 (H264), incl. HEVC→H264 re-encode, with in-place progress and cancel
- Thread scrollbar: always visible + wider by default so it can actually be grabbed and dragged for fast scrolling; width and always-show are configurable in Appearance settings
- Tablet friendliness: post thumbnail size slider now goes up to 200% (was 125%); SPLIT layout divider is draggable (15–60%), dragging it all the way left hides the catalog pane entirely (tap the highlighted strip at the left edge to bring it back at its previous width); position persists; taller 64dp toolbar on tablets (phones stay 48dp); hardware keyboard hotkeys (toggle in Behavior settings) — arrows/PgUp/PgDn switch media, Esc closes the media viewer
- Fix: after rotating to portrait, the bottom part of the thread could stay covered by an empty (mispositioned) reply sheet until the reply box was opened/closed
- Separate catalog column count settings for SPLIT vs other layout modes; up to 10 columns on tablets (5 on phones)
- Experimental: autoplay cached videos inline in catalog thumbnails (muted/looped, at most 4 at once; paused while a thread is open in SPLIT mode)
- Bigger media caches: ExoPlayer video cache 128→256MB, media cache default 256→384MB (max 2GB), prefetch cache default 512→768MB (max 4GB)
- Changing the layout mode now restarts the app automatically so it applies immediately
- Fix: MPV seeking used keyframe seeks, so dragging the time bar snapped to a few coarse positions (and always jumped back to the start on single-keyframe webms). Seeks are now exact, and the position label updates live while scrubbing

### [Latest stable release](https://github.com/K1rakishou/Kuroba-Experimental/releases/latest)

### [Latest beta version](https://github.com/K1rakishou/Kuroba-Experimental-beta/releases/latest)

KurobaEx is a fast Android app for browsing imageboards, such as 4chan and 8chan. It's a fork of Kuroba. This fork provides lots of new features:

- New technological stack (Kotlin, RxJava/Coroutines, Room etc).

- On demand content loading (includes prefetching, youtube videos titles and durations fetching, inlined files size fetching etc).

- Third-party archives support.

- New thread navigation (tabs).

- New in-app navigation (bottom nav bar).

- New bookmarks (they were fully rewritten from scratch, now use way less memory, don't use wakelocks, show separate notifications per thread (and notifications can be swiped away).

- Edge-to-edge theme support.

- New database.

- 4chan global search support.

- Fully dynamic themes with Android Q Day/Night mode support.

- Per-site proxies.

- Ability to attach multiple media files to reply, attach media files that was shared by external apps (even by some keyboards), attach remote media files by URL, etc.

- New image downloader. Allows downloading images while the app is in background, retrying failed to download images, resolving duplicates, etc. 

- New posting. Posting code was moved into a foreground service which now allows stuff like using automatic captcha solvers (2captcha API) seamlessly or queueing multiple replies in different threads (only one reply per thread).

- New Media Viewer. It was rewritten from scratch and now lives in a separate activity. It now also supports stuff like viewing links to media files shared into the app.

- Thread downloader with ability to export threads as HTML pages with all downloaded media.

- Composite catalogs (ability to combine multiple boards of any available sites (except archives) together into a single catalog).

- Mpv video player (downloadable).

- Bookmark groups with ability to setup regex matchers to automatically move newly created bookmarks into them.

- ~~Automatic captcha solver for 4chan captcha (See https://github.com/K1rakishou/4chanCaptchaSolver)~~

- Lots of other tiny improvements.

### Screenshots:

[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/1.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/2.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/3.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/4.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/5.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/6.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/7.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/8.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/8.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/9.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/9.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/10.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/10.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/11.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/11.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/12.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/12.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/13.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/13.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/14.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/14.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/15.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/15.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/16.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/16.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/17.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/17.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/18.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/18.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/19.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/19.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/20.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/20.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/21.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/21.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/22.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/22.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/23.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/23.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/24.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/24.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/25.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/25.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/26.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/26.png)

##### Currently supported sites
- 4Chan
- Dvach
- 8Kun (thanks to @jirn073-76)
- Lainchan
- Sushichan
- Wired-7 (thanks to @Wired-7)
- 370chan.info (thanks to @alcharkov)
- Endchan
- Kohlchan
- Krautchan
- 8chan.moe
- Lefypol (thanks to @yuiopmbv)
- Diochan (thanks to @Dashchanon)
- ~~420Chan (thanks to @Lolzen)~~
- ~~YesHoney (thanks to @SomeGuy719)~~
- ~~Vhschan (thanks to @MrPurple666)~~
- ~~Soyjak.party (thanks to @absurd-shaman)~~

##### Currently supported 4chan archives
- ArchivedMoe
- ArchiveOfSins
- B4k
- DesuArchive
- Fireden 
- 4Plebs 
- Warosu
- ~~Nyafuu~~
- ~~TokyoChronos~~
- ~~Wakarimasen.moe~~
- ~~RozenArcana~~

## License
[Kuroba is GPLv3](https://github.com/K1rakishou/Kuroba-Experimental/blob/develop/COPYING.txt), [licenses of the used libraries.](https://github.com/K1rakishou/Kuroba-Experimental/blob/develop/Kuroba/app/src/main/assets/html/licenses.html)
