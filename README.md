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

## License
MIT (see `LICENSE`). Bundled third-party components keep their own licenses: see `THIRD_PARTY_LICENSES.txt`.
