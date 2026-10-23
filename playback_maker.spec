# PyInstaller spec: `uv run pyinstaller playback_maker.spec` -> dist/PlaybackMaker/
# On Windows, run build_windows.ps1 instead (it fetches ffmpeg/deno into vendor/ first).
import shutil
import sys
from pathlib import Path

from PyInstaller.utils.hooks import collect_all, collect_data_files, collect_submodules

exe = ".exe" if sys.platform == "win32" else ""

datas, binaries, hiddenimports = [], [], []
# torchvision: its compiled ops (_C_stable) are missed by the stock hook.
for pkg in ("audio_separator", "onnxruntime", "torchvision"):
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hiddenimports += h
datas += collect_data_files("librosa")
hiddenimports += collect_submodules("yt_dlp")

# ffmpeg, ffprobe and deno go into bin/, which playback.ensure_ffmpeg() puts on PATH.
for tool in ("ffmpeg", "ffprobe", "deno"):
    path = Path("vendor") / f"{tool}{exe}"
    if not path.exists() and shutil.which(tool):
        path = Path(shutil.which(tool))  # local smoke builds: use the system copy
    if path.exists():
        binaries.append((str(path), "bin"))
    else:
        print(f"WARNING: {tool} not found; it will not be bundled")

a = Analysis(
    ["playback.py"],
    datas=datas,
    binaries=binaries,
    hiddenimports=hiddenimports + ["gui"],
    excludes=["matplotlib", "IPython", "jupyter", "notebook", "pytest", "tensorboard"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe_ = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="PlaybackMaker",
    console=False,  # windowed app: no terminal pops up on double-click
    upx=False,
)
coll = COLLECT(exe_, a.binaries, a.datas, name="PlaybackMaker", upx=False)
