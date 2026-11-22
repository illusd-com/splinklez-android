# splinklez-android
Splinklez Android APK 下載 — https://splinklez.huhm.cc

## Build

Binary files (fonts, jars) are stored as base64 text in `binaries/`.

```
python3 tools/binaries.py unpack          # restore binary files
python3 recovery/build_recovered_jar.py   # app/libs/splinklez-1.1.0-recovered.jar (JDK 17)
./gradlew testReleaseUnitTest assembleRelease
```

See `RECOVERY.md` for how the source was recovered.

## CI / releases (GitHub Actions, `.github/workflows/android.yml`)

* Every push to `main`: unit tests + signed release APK, uploaded as a workflow artifact
  (signer SHA-1 checked against `C6:8E:91:99:28:6C:C9:A6:55:41:06:C7:2E:ED:62:20:CC:59:84:BD`).
* Release: bump `versionCode`/`versionName` in `app/build.gradle.kts`, add `release-notes/<versionName>.md`
  (first line = release title, blank line, then the notes), then either push tag `v<versionName>` or run the
  workflow manually with `release` checked. The release gets `splinklez.apk` and is marked Latest.
* Signing secrets (repo → Settings → Secrets and variables → Actions): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD`. Without them CI builds an unsigned APK and releasing is refused.
