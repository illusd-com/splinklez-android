# Splinklez 1.1.0 source recovery

The original source tree and release keystore were lost. This project was rebuilt from the
published release APK (`v1.1.0/splinklez.apk`, sha256 `45d9c108…4580`) so that the built APK
matches the published one in behaviour, resources and code.

## How the build is put together

* Resources, manifest, fonts, `google_services.xml`: recovered from the APK; every XML file is
  byte-identical to the original (line layout preserved so aapt2 line numbers match).
* Kotlin under `app/src/main/java`: hand-written source, each file checked to compile to the same
  smali as the original classes (debug line info and SMAP ignored).
* `recovery/splinklez-1.1.0-classes.jar`: the original app classes (dex2jar of the APK).
  `recovery/build_recovered_jar.py` copies it to `app/libs/splinklez-1.1.0-recovered.jar`, leaving out
  every class whose source file already exists in `app/src/main/java`. As more files are rewritten,
  the jar shrinks; when every file is source, the jar goes away.
  Run `python3 recovery/build_recovered_jar.py` before `./gradlew assembleRelease`.
* Decompiled reference (jadx Java, read-only, not compiled) for classes still in the jar:
  `recovery/reference/`.

## Signing

Release signing reads `keystore.properties` (gitignored). The original key is lost, so the
rebuild is signed with a NEW key: an installed 1.1.0 cannot be updated in place by this build,
and Google Sign-In / Firebase need the new SHA-1 registered.

## Status (2026-11-22)

Rewritten as Kotlin source, smali-identical to the original (142 classes, 18 files):
`SplinklezApp`, `alerts/*` (AlertStore, BootReceiver, JapanEewService, Notifier, Push, PushService),
`auth/GoogleAuth`, `data/` (Models, Community, Graph, SessionStore, VoiceIO), `sensor/*`
(SensorService, SensorStore, ShakeDetector), `ui/RocDate`, `widget/Widgets`.
Only remaining difference among them: `Notifier` lacks an (empty-content) SourceDebugExtension
annotation — debug metadata only.

Still recovered bytecode (in the jar): `data/Api` (43 classes), `MainActivity`, and all Compose UI
and view models under `ui/` (about 1000 classes). Compose compiler output is very hard to reproduce
byte-for-byte from hand-written source, so these stay as the original bytecode to guarantee parity.

## 1.2.0 (versionCode 11): voice assistant

New, hand-written code only (no jar class touched): `app/src/main/java/cc/huhm/splinklez/assistant/`.

* `SplinklezVoiceService` (VoiceInteractionService) + `AssistantSessionService` + `AssistantRecognitionService`,
  declared in the manifest with `res/xml/voice_interaction.xml` (supportsAssist, not from keyguard). Once the user picks
  Splinklez as the default digital assistant, long-pressing power/home shows `AssistantSession`.
* `AssistantSession`: bottom strip over the current app with the Canvas-drawn `OrbView`, captions, 「在 App 中繼續」
  (opens MainActivity with `Notifier.EXTRA_OPEN = "chat"`). Records (`LevelRecorder`, `Endpointer` ≈1.2 s quiet),
  transcribes with `Graph.api.speechToText`, asks `/api/chat` with `voice: true` (`VoiceChat`, Bearer session token like
  `Api`), speaks with `Graph.api.textToSpeech` + `VoicePlayer`, captions follow the TTS alignment.
* `AssistantRecognitionService` is a working recognizer (same STT endpoint), since Android makes the assistant's
  recognizer the phone's default.
* `AssistantSetupActivity` (static launcher shortcut 「設定語音助理」, `res/xml/shortcuts.xml`) and `MicPermissionActivity`.
* Unit tests for end-of-speech detection and speech text cleanup: `./gradlew testReleaseUnitTest`.

## 1.2.1 (versionCode 12): version fix, full captions, announcements

* **Inlined version constants.** kotlinc inlined `BuildConfig.VERSION_NAME`/`VERSION_CODE` (`"1.1.0"` / `10`) into the
  1.1.0 bytecode, so 1.2.0 still compared the server's versionCode against 10 (always "有新版"), showed 「目前是 1.1.0」,
  and sent `Splinklez-Android/1.1.0`. `recovery/patcher/VersionPatch.java` (ASM, jars in `recovery/lib`) now runs inside
  `build_recovered_jar.py` and rewrites every `"1.1.0"` string and the four version-code literals (AppViewModel
  checkUpdate/checkUpdateNow, AppRootKt.Shell's `10 < minSupportedVersionCode`) to the values in `app/build.gradle.kts`.
  It fails the build if it doesn't find exactly 4 literals.
* **Assistant captions.** The caption is now a scroll box (≤40% of the screen) with the whole answer; the spoken part is
  highlighted and the box follows the voice. No 3-line ellipsis, no one-sentence-at-a-time view, no 420-char clip
  (the voice reads up to 1200 chars, the screen shows everything).
* **Announcements** (`announce/`): `AnnouncementPopup` (ActivityLifecycleCallbacks installed from `SplinklezApp`) shows the
  newest unseen announcement over MainActivity once (server per account + local per phone); `AnnouncementActivity`
  (list / detail, launcher shortcut 「公告」); `AnnouncementEditActivity` for admins. Server: `/api/announcements*`.
  `SplinklezApp` is therefore no longer smali-identical to 1.1.0 (one added call).

## Build inputs in git / CI (2026-11-22)

Binary files are tracked as base64 in `binaries/` (`tools/binaries.py pack|unpack`, sha256-checked); the real files are
gitignored. CI (`.github/workflows/android.yml`) runs `unpack`, `build_recovered_jar.py`, unit tests and `assembleRelease`.

## 1.2.2 (versionCode 13): steadier orb, announcement entry

* **Orb.** STT/TTS use cancellable OkHttp calls with retry (`VoiceChat.withRetry`); the answer is spoken in short pieces
  (`Speech.chunks`, first ≤60 chars) with the next piece fetched while one plays; `AssistantPlayer` waits for the real
  completion callback, asks for ducking audio focus (USAGE_ASSISTANT) and reports decode/stall errors. Every failure is shown
  in the caption with a reason; a tap on the orb redoes only the failed step (`Retry.Ask` / `Retry.Speak`). A second
  invocation frees the mic before recording again (`LevelRecorder` is synchronized). Long-press the orb for a small timing line.
* **Announcement entry** (`announce/AnnouncementEntry.kt`): a 「公告」 pill (bell, red dot for unread) at the lower right of
  頭版, overlaid on MainActivity's decor view and driven by the recovered `AppViewModel` state; admins (server `isAdmin`)
  also get a 「＋」 that opens the editor.

## 1.2.3 (versionCode 14): web parity on 頭版, empty states, motion, push dedupe

* **`ui/CivicBand.kt` is now source** (the recovered `CivicBandKt` is dropped from the jar). Same public JVM API that the
  recovered HomeScreen/HomeViewModel call: `CivicBand(String, Map, Map, Function3, Composer, int)` and `getCIVIC_HOME_IDS()`.
  Every card mirrors the website card (fields and wording) with explicit states: shimmer while loading, 「…暫時讀不到」,
  「…目前沒有…」 empty states, then data. Traffic gets 公車/台鐵/高鐵 chips. Motion: staggered rise-in, cross-fade on load,
  row stagger, reservoir bars fill, press-scale chips.
* **`extras/HomeExtras.kt`**: 颱風動態 card at the top of the band while a storm is fresh (< 18 h, like the web), and footer
  links (公告, 更新日誌, 隱私權政策, 服務條款). **`announce/UpdateLogActivity.kt`** reads `/api/updatelog`.
* **AlertsScreen hook** (`VersionPatch.hookAlerts`, ASM): inserts `AlertsExtrasKt.typhoonEmpty(LazyListScope)` before the
  block after the typhoon blocks (key `-299545250` in `AlertsScreen$lambda$24$0$0`), so 防災 says 「目前西北太平洋沒有…」 when
  there is no storm. The build fails unless exactly one hook is applied.
* **Push**: `Push.sync` re-registers at least every 12 h and whenever the signed-in account changes (`Push.watchSession` from
  `SplinklezApp`), and unlinks the token if notifications are blocked. The server skips web push for accounts with a live
  Android token.

## 1.2.4 (versionCode 15): CivicBand layout fix

* Cause of the overlap: `CivicCard` emitted head, spacer and body as three siblings inside `Rise`, which is a `Box`, so they were
  all drawn at the same top-left. `CivicCard` now wraps them in one `Column`. `SpzHead` puts the aside (更新時間、城市…) on its own
  line under the title, so a long aside can no longer squeeze the title into two lines.
* `HomeViewModel.loadCivic` pre-fills every card with `null` but never fetches `traffic` and `medical`; those two cards used to
  show 讀取中 forever. They now start as a search prompt. Every other card that has no answer after 15 s shows 讀取逾時 + 重試.
* 國道路況: year < 1990 timestamps are dropped, ISO times become `M/d HH:mm`, same-title incidents group into one row (共 N 則).
* `CivicBand` ends with 120 dp of space so the floating ＋ / 公告 buttons never cover the last card.

## 1.3.0 (versionCode 16): typhoon probability, facility map, bus widget, agent connectors

All new UI is plain Kotlin under `extras/` and `widget/`; the recovered jar is untouched.

* `extras/Net.kt`: small OkHttp JSON GET with a hard timeout (15 s default) and friendly errors; county-seat coordinates.
* `extras/WebPane.kt`: vertical spinner → note → WebView with a 20 s timeout + 重試; only the site's own host loads inside;
  `signedIn = true` copies the app session into the site's `spz_session` cookie.
* `TyphoonActivity`: `/typhoon?embed=1` map (only while a storm has a fresh fix) + native township list from
  `/api/alerts/typhoon/prob?county=`. Explicit empty state when CWA publishes nothing.
* `FacilitiesActivity`: `/facilities?embed=1` map (Leaflet/OSM) above a native nearest-list from `/api/facilities`
  (shelters 消防署, AED 衛福部, toilets 環境部). Coarse location, rounded to 3 decimals; falls back to the chosen city.
* `AgentConnectActivity`: `/agent?tab=connect&embed=1` signed in. Write-type tool calls wait for 執行/取消 there — the
  recovered native chat ignores the new `tool_confirm` stream event.
* `widget/BusWidget` + `BusConfigActivity`: RemoteViews widget (vertical rows only), per-widget stop in SharedPreferences,
  WorkManager refresh every ~15 min, tap to refresh, keeps last good lines when offline. Data `/api/bus/*` (TDX server-side).
* 防災 tab gets two entry rows (AlertsExtras), 頭版 footer gets 颱風潛勢・防災設施・代理人工具, launcher shortcut 防災設施.

## 1.3.1 (versionCode 17): 自動定位

New Kotlin under `location/`; the recovered jar is untouched (it only calls the public `HomeViewModel.pickCity` and reads `Prefs.city`).

* `AutoLocate`: on every MainActivity resume and after sign-in it gets one fix (`LocationManager`: FUSED on API 31+, NETWORK, then GPS when
  FINE is granted; `getCurrentLocation` on API 30+, `requestSingleUpdate` before; ~14 s overall, then the last known fix if < 3 h old),
  rounds it to 0.01° and asks `/api/sensors/locate` (NLSC, session-authenticated, returns 縣市＋鄉鎮區 text only; nothing stored server-side).
  The result is saved on the phone as text + time, drives 頭版 (`HomeViewModel.pickCity`, or `Prefs.city` + widgets when no UI) and,
  for phones with push registered, PUTs `county` to `/api/alerts/prefs` so alerts follow the 縣市. A manual city survives a resume; it is
  replaced when the 縣市 changes or on 「重新定位」.
* `LocateWorker`: WorkManager `PeriodicWorkRequest` (1 h, network required), scheduled only while 自動定位 is on and
  ACCESS_BACKGROUND_LOCATION is granted (Android 10+); silent on failure.
* `LocateLine` (Compose, top of `CivicBand`, vertical stack): 定位中 / 已自動定位：縣市區・HH:mm (tap = relocate) / 失敗＋原因＋重試 /
  關閉 / inline permission ask / inline 「一律允許」 hint. `LocateHooks` registers the permission launchers in `onActivityCreated`,
  relocates on resume and shows a small 「開啟自動定位」 chip at the lower left of 頭版 while the permission is still unanswered.
* Manifest: ACCESS_FINE_LOCATION, ACCESS_BACKGROUND_LOCATION, `uses-feature location/gps required=false`.
