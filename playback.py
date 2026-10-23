#!/usr/bin/env python3
"""Turn a YouTube link into a no-vocal playback (instrumental track).

Pipeline:
  1. yt-dlp downloads the best available audio stream and decodes it to WAV.
  2. audio-separator runs a state-of-the-art source-separation model
     (BS-RoFormer by default) to split vocals from the instrumental.
  3. The instrumental is written in the requested format (WAV/FLAC/MP3).
"""

import argparse
import logging
import multiprocessing
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path

# Best free vocal/instrumental separation model available in audio-separator
# (BS-RoFormer, SDR ~12.9 on MUSDB18). Run `--list-models` to see alternatives.
ROFORMER_MODEL = "model_bs_roformer_ep_317_sdr_12.9755.ckpt"

# Speed/quality presets. "overlap" is how many times each audio chunk is
# processed and averaged: runtime scales roughly linearly with it.
PRESETS = {
    # MDX-Net ONNX model: several times faster on CPU, slightly more vocal bleed.
    "fast": {"model": "UVR-MDX-NET-Inst_HQ_4.onnx", "overlap": 0.25},
    # BS-RoFormer with light overlap: ~4x faster than "best", nearly identical sound.
    "balanced": {"model": ROFORMER_MODEL, "overlap": 2},
    # BS-RoFormer with the library's default (heavy) overlap.
    "best": {"model": ROFORMER_MODEL, "overlap": 8},
}


def model_dir():
    """Persistent per-user cache for separation models (~600 MB, downloaded once)."""
    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local")) / "PlaybackMaker"
    else:
        base = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "playback-maker"
    return base / "models"


def ensure_ffmpeg(log=print):
    """Use bundled ffmpeg (frozen app), else system ffmpeg, else fetch a static build."""
    if getattr(sys, "frozen", False):
        # PyInstaller build: ffmpeg/ffprobe/deno are shipped in bin/ next to the app.
        bundled = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent)) / "bin"
        os.environ["PATH"] = str(bundled) + os.pathsep + os.environ.get("PATH", "")
    if shutil.which("ffmpeg") and shutil.which("ffprobe"):
        return
    import static_ffmpeg

    log("ffmpeg not found on PATH; using a static ffmpeg build...")
    static_ffmpeg.add_paths()


def safe_filename(name):
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", name).strip(" .")
    return name[:150] or "track"


def download_audio(url, workdir):
    import yt_dlp

    opts = {
        "format": "bestaudio/best",
        "outtmpl": str(workdir / "source.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "postprocessors": [{"key": "FFmpegExtractAudio", "preferredcodec": "wav"}],
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
    return workdir / "source.wav", info.get("title") or info.get("id") or "track"


def separate(source, workdir, model, overlap, keep_vocals):
    from audio_separator.separator import Separator

    models = model_dir()
    models.mkdir(parents=True, exist_ok=True)
    separator = Separator(
        log_level=logging.INFO,
        log_formatter=logging.Formatter("      %(message)s"),
        model_file_dir=str(models),
        output_dir=str(workdir),
        output_format="WAV",
        output_single_stem=None if keep_vocals else "Instrumental",
        # Applies to RoFormer models (MDXC architecture).
        mdxc_params={"segment_size": 256, "override_model_segment_size": False,
                     "batch_size": 1, "overlap": max(2, int(overlap)), "pitch_shift": 0},
        # Applies to MDX-Net ONNX models.
        mdx_params={"hop_length": 1024, "segment_size": 256, "batch_size": 1,
                    "overlap": min(overlap, 0.99), "enable_denoise": False},
    )
    separator.load_model(model_filename=model)
    outputs = [workdir / Path(f).name for f in separator.separate(str(source))]

    def find(stem):
        for f in outputs:
            if f"({stem.lower()})" in f.name.lower():
                return f
        return None

    instrumental = find("Instrumental") or find("No Vocals")
    if instrumental is None:
        sys.exit(f"Separation produced no instrumental stem: {[f.name for f in outputs]}")
    return instrumental, find("Vocals") if keep_vocals else None


def export(src, dest, fmt):
    import subprocess

    codec = {
        "wav": ["-c:a", "pcm_s16le"],
        "flac": ["-c:a", "flac"],
        "mp3": ["-c:a", "libmp3lame", "-b:a", "320k"],
    }[fmt]
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(src), *codec, str(dest)],
        check=True,
        # Don't flash a console window when running as a windowed Windows app.
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )


def make_playback(url, out_dir, fmt="mp3", quality="balanced", model=None, keep_vocals=False, log=print):
    """Run the full pipeline and return the path of the saved playback."""
    ensure_ffmpeg(log)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="playback_") as tmp:
        workdir = Path(tmp)

        log("[1/3] Downloading audio...")
        source, title = download_audio(url, workdir)
        log(f"      Got: {title}")

        preset = PRESETS[quality]
        model = model or preset["model"]
        if not (model_dir() / model).exists():
            log("      First run with this model: downloading it (one time only, can be ~600 MB)...")
        log(f"[2/3] Separating vocals with {model} ({quality} preset)...")
        instrumental, vocals = separate(source, workdir, model, preset["overlap"], keep_vocals)

        log("[3/3] Exporting...")
        base = safe_filename(title)
        dest = out_dir / f"{base} (Playback).{fmt}"
        export(instrumental, dest, fmt)
        log(f"Playback saved to: {dest}")
        if vocals:
            vdest = out_dir / f"{base} (Vocals).{fmt}"
            export(vocals, vdest, fmt)
            log(f"Vocals saved to:   {vdest}")
    return dest


def main():
    parser = argparse.ArgumentParser(
        description="Create a no-vocal playback (instrumental) from a YouTube link."
    )
    parser.add_argument("url", nargs="?", help="YouTube video URL")
    parser.add_argument("-o", "--output-dir", default="output", help="where to save results (default: ./output)")
    parser.add_argument("-f", "--format", choices=["wav", "flac", "mp3"], default="mp3",
                        help="output format (default: mp3 @ 320 kbps)")
    parser.add_argument("-q", "--quality", choices=PRESETS, default="balanced",
                        help="speed/quality preset: fast | balanced | best (default: balanced)")
    parser.add_argument("-m", "--model", help="override the preset's separation model (see --list-models)")
    parser.add_argument("--keep-vocals", action="store_true", help="also save the isolated vocal track")
    parser.add_argument("--list-models", action="store_true", help="list available separation models and exit")
    args = parser.parse_args()

    if args.list_models:
        from audio_separator.separator import Separator

        for arch, models in Separator(info_only=True).list_supported_model_files().items():
            print(f"\n== {arch} ==")
            for name, details in models.items():
                filename = details.get("filename") if isinstance(details, dict) else details
                print(f"  {filename}    ({name})")
        return

    if not args.url:
        parser.error("a YouTube URL is required")

    make_playback(args.url, args.output_dir, args.format, args.quality, args.model, args.keep_vocals)


if __name__ == "__main__":
    multiprocessing.freeze_support()
    if len(sys.argv) == 1:
        # No arguments (e.g. double-clicked): open the window.
        import gui

        gui.main()
    else:
        main()
