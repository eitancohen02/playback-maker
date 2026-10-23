# Builds dist\PlaybackMaker\ and PlaybackMaker-windows.zip. Run on Windows (not WSL).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

uv sync --group dev

# Third-party executables bundled into the app's bin\ folder.
$vendor = "vendor"
New-Item -ItemType Directory -Force $vendor | Out-Null
if (-not (Test-Path "$vendor\ffmpeg.exe")) {
    Write-Host "Downloading ffmpeg..."
    Invoke-WebRequest "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip" -OutFile "$vendor\ffmpeg.zip"
    Expand-Archive "$vendor\ffmpeg.zip" "$vendor\ffmpeg" -Force
    Get-ChildItem "$vendor\ffmpeg" -Recurse -Include ffmpeg.exe, ffprobe.exe | Copy-Item -Destination $vendor
    Get-ChildItem "$vendor\ffmpeg" -Recurse -Filter LICENSE* | Select-Object -First 1 | Copy-Item -Destination "$vendor\ffmpeg-LICENSE.txt"
    Remove-Item "$vendor\ffmpeg.zip", "$vendor\ffmpeg" -Recurse -Force
}
if (-not (Test-Path "$vendor\deno.exe")) {
    # yt-dlp needs a JavaScript runtime to fully support YouTube.
    Write-Host "Downloading deno..."
    Invoke-WebRequest "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip" -OutFile "$vendor\deno.zip"
    Expand-Archive "$vendor\deno.zip" $vendor -Force
    Remove-Item "$vendor\deno.zip"
}

uv run pyinstaller --noconfirm --clean playback_maker.spec

# License notices (ffmpeg is GPL: its license must ship with the binaries).
Copy-Item LICENSE, THIRD_PARTY_LICENSES.txt dist\PlaybackMaker\
if (Test-Path "$vendor\ffmpeg-LICENSE.txt") {
    Copy-Item "$vendor\ffmpeg-LICENSE.txt" dist\PlaybackMaker\_internal\bin\
}

if (Test-Path PlaybackMaker-windows.zip) { Remove-Item PlaybackMaker-windows.zip }
Compress-Archive dist\PlaybackMaker PlaybackMaker-windows.zip
Write-Host "Built PlaybackMaker-windows.zip"
