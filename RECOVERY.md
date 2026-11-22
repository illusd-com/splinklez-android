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
