"""Generate reference data for the Android (Kotlin) port of the MDX separator.

Runs audio-separator's MDX code on a deterministic synthetic stereo clip and saves the
input, the model's spectrogram input for the first chunk, the first chunk's separated
wave, and the final instrumental, so Kotlin tests can check they match.

    uv run tools/make_mdx_golden.py [out_dir]
"""

import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import playback  # noqa: E402

MODEL = "UVR-MDX-NET-Inst_HQ_4.onnx"
SR = 44100
SECONDS = 8
OVERLAP = 0.25  # the "fast" preset


def synthetic_mix():
    rng = np.random.default_rng(1234)
    t = np.arange(SR * SECONDS) / SR
    left = 0.3 * np.sin(2 * np.pi * 220 * t) + 0.2 * np.sin(2 * np.pi * 440 * t * (1 + 0.01 * np.sin(t)))
    right = 0.3 * np.sin(2 * np.pi * 330 * t) + 0.15 * np.sin(2 * np.pi * 880 * t)
    noise = 0.05 * rng.standard_normal((2, t.size))
    return (np.stack([left, right]) + noise).astype(np.float32)


def save(path, array):
    np.ascontiguousarray(array, dtype="<f4").tofile(path)


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "android/separator/src/test/resources/golden")
    out.mkdir(parents=True, exist_ok=True)

    playback.ensure_ffmpeg()
    from audio_separator.separator import Separator

    sep = Separator(model_file_dir=str(playback.model_dir()), output_single_stem="Instrumental",
                    mdx_params={"hop_length": 1024, "segment_size": 256, "batch_size": 1,
                                "overlap": OVERLAP, "enable_denoise": False})
    sep.load_model(model_filename=MODEL)
    mdx = sep.model_instance

    captured = {}

    def run_model(mix, is_match_mix=False):
        spek = mdx.stft(mix.to(mdx.torch_device))
        spek[:, :, :3, :] *= 0
        result = original_run_model(mix, is_match_mix)
        if "spek" not in captured:
            captured["spek"] = spek.cpu().numpy()[0]
            captured["chunk_in"] = mix.cpu().numpy()[0]
            captured["chunk_out"] = result[0].copy()  # demix() windows it in place afterwards
        return result

    original_run_model = mdx.run_model
    mdx.run_model = run_model

    mix = synthetic_mix()
    peak = np.abs(mix).max()
    from audio_separator.separator.uvr_lib_v5 import spec_utils
    normalized = spec_utils.normalize(wave=mix.copy(), max_peak=0.9, min_peak=0.0)
    instrumental = mdx.demix(normalized) * peak

    save(out / "mix.f32", mix)                       # [2, n]
    save(out / "chunk_in.f32", captured["chunk_in"])  # [2, chunk_size]
    save(out / "spek_first8.f32", captured["spek"][..., :8])  # [4, dim_f, 8]
    save(out / "chunk_out.f32", captured["chunk_out"])  # [2, chunk_size]
    save(out / "instrumental.f32", instrumental)      # [2, n]
    meta = {
        "model": MODEL, "sample_rate": SR, "samples": int(mix.shape[1]), "overlap": OVERLAP,
        "n_fft": mdx.n_fft, "hop_length": mdx.hop_length, "dim_f": mdx.dim_f, "dim_t": mdx.dim_t,
        "compensate": mdx.compensate, "chunk_size": int(captured["chunk_in"].shape[-1]),
        "primary_stem": mdx.primary_stem_name, "peak": float(peak),
    }
    (out / "meta.json").write_text(json.dumps(meta, indent=2))
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
