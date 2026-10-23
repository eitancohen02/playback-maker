# Playback Maker

Turns a YouTube link into a high-quality **no-vocal playback** (instrumental).

> For personal use (practice, karaoke at home). Respect copyright and YouTube's Terms of Service:
> don't publish or sell playbacks of songs you don't have rights to.

## How it works
| Step | Tool (all free / open source) |
|---|---|
| Download best audio stream | [yt-dlp](https://github.com/yt-dlp/yt-dlp) |
| Remove vocals | [audio-separator](https://github.com/nomadkaraoke/python-audio-separator) running **BS-RoFormer** (top-ranked open vocal-separation model) |
| Decode / encode | ffmpeg (system one, or auto-downloaded static build) |

## Download for Windows (no install needed)
1. Download `PlaybackMaker-windows.zip` from the GitHub **Releases** page and unzip it.
2. Double-click `PlaybackMaker\PlaybackMaker.exe`, paste a YouTube link, click **Make playback**.
   - Windows may show *"Windows protected your PC"* (the app isn't code-signed): click **More info -> Run anyway**.
   - The first run downloads the separation model (one time, up to ~600 MB) to `%LOCALAPPDATA%\PlaybackMaker\models`.
   - Needs ~3 GB free disk space. Results go to `Music\Playback Maker` by default.

## Download for Android
1. On your phone, download `PlaybackMaker-android.apk` from the GitHub **Releases** page and open it.
   - **Samsung (One UI 6+):** first turn off *Settings -> Security and privacy -> Auto Blocker*, which blocks
     installing apps from outside the stores. You can turn it back on after installing (turn it off again for updates).
   - Allow *Install unknown apps* for your browser when asked. Play Protect may warn about an unknown app: tap *Install anyway*.
2. In the YouTube app, open a song and tap **Share -> Playback Maker** (or paste a link in the app), then **Make playback**.
   - The first run downloads the separation model (59 MB, one time only).
   - It keeps running if you leave the app; progress shows in the notification. Results go to `Music/Playback Maker`.
   - The phone uses the fast MDX-Net model (the desktop `-q fast` preset): good results, a bit less clean than
     the desktop `balanced`/`best` presets. Expect about 1.5-2x the song's length (e.g. ~10 min for a 6-min song on a Galaxy A56).
   - Android 10 or newer, 64-bit phone; 6 GB+ RAM recommended.

## Setup (running from source)
```bash
curl -LsSf https://astral.sh/uv/install.sh | sh   # once, if you don't have uv
uv sync
```

## Usage
```bash
uv run playback.py "https://www.youtube.com/watch?v=XXXXXXXXXXX"
uv run playback.py URL -q fast              # quickest (MDX-Net); -q best = slowest, max quality
uv run playback.py URL -f flac               # lossless output (wav | flac | mp3)
uv run playback.py URL --keep-vocals         # also save the isolated vocals
uv run playback.py URL -o ~/Music/playbacks  # custom output folder
uv run playback.py --list-models             # other models (e.g. Demucs, MDX, karaoke models)
```
Results go to `./output/<title> (Playback).mp3`. Run `uv run playback.py` with no arguments to open the window instead.

The model (~600 MB) is downloaded on first run. On CPU a 4-minute song takes a few minutes;
with an NVIDIA GPU, swap `audio-separator[cpu]` for `audio-separator[gpu]` in `pyproject.toml` for a large speed-up.

Tip: to keep backing vocals (karaoke style), try `-m mel_band_roformer_karaoke_aufr33_viperx_sdr_10.1956.ckpt`.

## Building the Windows app
On a Windows machine (not WSL): `powershell -ExecutionPolicy Bypass -File build_windows.ps1`
-> produces `dist\PlaybackMaker\` and `PlaybackMaker-windows.zip` (bundles ffmpeg and deno).

Or let GitHub build it: push a tag (`git tag v0.1.0 && git push --tags`) and the
*Build Windows app* workflow attaches the zip to a release (or run it manually from the Actions tab).
Rebuild every so often: YouTube changes regularly break old yt-dlp versions.

## Building the Android app
The app lives in `android/` (Kotlin + Jetpack Compose). The separation code (`android/separator/`) is a port of
audio-separator's MDX-Net path, tested against reference output from `tools/make_mdx_golden.py`:
```bash
cd android
./gradlew :separator:test       # compares the Kotlin port with audio-separator's output
./gradlew :app:assembleDebug    # debug APK in app/build/outputs/apk/debug/
```
Needs JDK 17 and the Android SDK (`ANDROID_HOME` or `android/local.properties`). Release builds are signed with
the key in `~/.android-keys/keystore.properties` locally, or from repository secrets in the *Build Android app*
workflow, which attaches `PlaybackMaker-android.apk` to the release on `v*` tags.

## License
MIT (see `LICENSE`). Bundled third-party components keep their own licenses: see `THIRD_PARTY_LICENSES.txt`.
The Android APK includes youtubedl-android (GPL-3.0), so the APK as a whole is distributed under GPL-3.0; its full source is this repository.
