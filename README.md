# ShortsGen — offline text → vertical video generator (Android)

Type a script **line by line** → the app speaks every line with a bundled offline
Piper voice and renders **one 1080×1920 H.264 video per line** (black background,
white text, your `font.ttf`) → then packs **all videos into one ZIP** that you save
to your Download folder.

Everything runs **on the device**: the app has **no `INTERNET` permission at all**.

| Requirement | Implementation |
|---|---|
| Video size | 1080 × 1920 (9:16) |
| Text wrap box | 680 × 1320 px, centred; text is shrunk/scaled so it never leaves the box |
| Video codec | H.264 / AVC (`video/avc`, MediaCodec) + AAC-LC audio, MP4 container |
| Background | pure black |
| Text colour / font | white, rendered with the bundled `font.ttf` |
| Duration | exactly the TTS duration of the line (+0.1 s tail so audio is never cut) |
| Voice | Piper `en_US-ljspeech-medium` VITS via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (ONNX Runtime + espeak-ng), 22.05 kHz |
| Output | `line_01.mp4`, `line_02.mp4`, … + `lines.txt` inside one ZIP |

---

## ⚠️ Why this repository is tiny (~300 KB) while the app needs ~112 MB

The voice model, the phonemizer data, the font and the native TTS library are
**too big for git / for GitHub's web uploader** (25 MB per file), so they are
**not committed**. They are downloaded by the build and baked into the APK:

| File | Size | Where it lives at build time |
|---|---|---|
| `en_US-ljspeech-medium.onnx` | 63 MB | `app/src/main/assets/vits-piper-en_US-ljspeech-medium/` |
| `en_US-ljspeech-medium.onnx.json` | 5 KB | same folder |
| `vits-piper-en_US-ljspeech-medium.tar.bz2` | 67 MB | source of the two files above + `tokens.txt` |
| `espeak-ng-data.tar.bz2` | 7 MB | `…/vits-piper-en_US-ljspeech-medium/espeak-ng-data/` (355 files) |
| `font.ttf` | 150 KB | `app/src/main/assets/font.ttf` |
| `sherpa-onnx-1.13.8.aar` | 50 MB | `app/libs/` (native ONNX Runtime + JNI) |

`scripts/prepare_assets.sh` (called by the GitHub Actions workflow, or by you once
for local builds) downloads them from the
[Shorts-Gen release](https://github.com/vishala5000/Shorts-Gen/releases/tag/assets)
and unpacks them into those folders. `.gitignore` keeps them out of git, and the
**built APK contains all of them**, which is why the installed app is 100 % offline.

So: **you only upload this small repository.** GitHub Actions fetches the 112 MB on
every build, and the finished APK (≈110 MB) is delivered as a build *artifact* /
release asset — GitHub allows up to 2 GB per artifact, so nothing big is ever
uploaded by hand.

---

## Build it on GitHub Actions (no Android Studio needed)

1. Create an empty repository on GitHub (e.g. `Shorts-Gen-App`).
2. Upload **the contents of this folder** (drag & drop works — every file is small),
   or:
   ```bash
   git init && git add . && git commit -m "ShortsGen"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
3. Open the **Actions** tab. The workflow `Build Android APK` runs automatically on
   push (or trigger it manually with **Run workflow**).
4. What it does:
   * installs JDK 17 + Android SDK 34,
   * runs `scripts/prepare_assets.sh` (downloads model/espeak/font/AAR),
   * runs `./gradlew assembleDebug assembleRelease`,
   * uploads `ShortsGen-release.apk` (signed, installable) and `ShortsGen-debug.apk`
     as artifacts for 30 days.
5. Download the APK from **Actions → run → Artifacts** and install it
   (`adb install -r ShortsGen-release.apk`, or tap the file on the phone).
6. Optional: push a tag (`git tag v1.0 && git push --tags`) and the APKs are also
   published as a **GitHub Release** automatically.

The release APK is signed with the repo's `keystore/release.jks` (a throw-away
sideload key, password `shortsgen`), so every CI build installs **over** the
previous one. To use your own key, set the repository secrets
`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD` (or edit `keystore.properties`). **Use your own key before
ever publishing to Google Play.**

## Build it locally

```bash
# JDK 17 + Android SDK (API 34) required
bash scripts/prepare_assets.sh     # one-time: fetches the ~112 MB into the repo
./gradlew assembleDebug            # or assembleRelease
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Android Studio: open the folder, let it sync, run `prepare_assets.sh` once, hit Run.

---

## Using the app

1. **Add lines** – type a line, tap *Add line* (or the ✔ key). Each line = one video.
   Menu → *Paste a whole script…* splits a pasted script into lines.
2. **Voice** – speed slider (0.75×–1.5×).
3. **Generate videos** – first launch unpacks the voice data once (a few seconds),
   then per line: TTS → frame render → H.264/AAC encode. Progress is shown per clip.
4. **Save ZIP to Downloads** – writes `Download/ShortsGen/ShortsGen_<date>.zip`
   containing `line_01.mp4 … line_NN.mp4` + `lines.txt`. You can also play, save or
   share single clips from the list.

Clips are kept in the app's private folder
(`Android/data/com.shortsgen.app/files/videos`) until the next run.

---

## Project layout

```
.github/workflows/android.yml   CI: fetch assets → build → upload APKs / release
scripts/prepare_assets.sh       downloads model + espeak-ng-data + font + AAR
app/libs/                       (generated) sherpa-onnx AAR
app/src/main/assets/            (generated) font.ttf + vits-piper-… model + espeak-ng-data
app/src/main/java/com/shortsgen/app/
    MainActivity.kt             UI (lines, speed, progress, results)
    ShortGenEngine.kt           glue: TTS → composer → ZIP
    tts/TtsEngine.kt            sherpa-onnx / Piper wrapper + espeak-ng-data unpacking
    video/VideoComposer.kt      MediaCodec H.264 + MediaMuxer (the core)
    video/AudioEncoder.kt       PCM → AAC-LC
    video/TextFrameRenderer.kt  1080×1920 black frame, white text in the 680×1320 box
    video/YuvFrame.kt           RGB → YUV420 (BT.709 limited range)
    util/Zip.kt, util/Storage.kt
keystore/release.jks            sideload signing key (replace for Play Store)
```

### Tuning knobs

* `video/VideoSpec.kt` – resolution, fps (30), bitrate (8 Mbit/s VBR), I-frame
  interval, audio bitrate, tail length.
* `video/TextFrameRenderer.kt` – text box (680×1320), max/min text size, line spacing.
* `tts/TtsEngine.kt` – noise scales / length scale of the voice, thread count.

## Troubleshooting

* **"Missing offline assets …" at build time** → run `bash scripts/prepare_assets.sh`.
* **First app start is slow** → one-time unpack of `espeak-ng-data` (18 MB, 355 files).
* **APK is ~110 MB** → it contains the 63 MB voice model, 18 MB phonemizer data and
  native libraries for arm64 / arm32 / x86_64. Delete an ABI in
  `app/build.gradle.kts` → `supportedAbis` to shrink it.
* **Video looks wrong on an exotic device** → the composer prefers the AOSP software
  AVC encoder and falls back to hardware encoders and packed YUV layouts automatically.

## Credits

* [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) – ONNX Runtime TTS runtime (Apache-2.0)
* [Piper](https://github.com/rhasspy/piper) / `en_US-ljspeech-medium` voice model
* [espeak-ng](https://github.com/espeak-ng/espeak-ng) – phonemizer data
* Android MediaCodec / MediaMuxer for H.264 + AAC encoding
