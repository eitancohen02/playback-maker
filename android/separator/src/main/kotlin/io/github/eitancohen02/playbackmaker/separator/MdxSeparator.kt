package io.github.eitancohen02.playbackmaker.separator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** Runs the separation network on one spectrogram `[1, 4, dimF, dimT]` (flattened) and returns the same shape. */
fun interface SpectrogramModel {
    fun run(spectrogram: FloatArray): FloatArray
}

/** Fixed parameters of an MDX-Net model (from audio-separator's model data). */
data class MdxParams(
    val nFft: Int,
    val hop: Int,
    val dimF: Int,
    val dimT: Int,
    val compensate: Float,
) {
    companion object {
        /** UVR-MDX-NET-Inst_HQ_4.onnx: its primary stem is the instrumental. */
        val INST_HQ_4 = MdxParams(nFft = 5120, hop = 1024, dimF = 2560, dimT = 256, compensate = 1.019f)
    }
}

class Stems(val instrumental: Array<FloatArray>, val vocals: Array<FloatArray>?)

/**
 * Kotlin port of audio-separator's `MDXSeparator` (non-denoise ONNX path), so phone output
 * matches the desktop app's `fast` preset.
 */
class MdxSeparator(
    private val model: SpectrogramModel,
    private val params: MdxParams = MdxParams.INST_HQ_4,
    private val overlap: Float = 0.25f,
) {
    private val stft = Stft(params.nFft, params.hop, params.dimF)
    private val trim = params.nFft / 2
    private val chunkSize = params.hop * (params.dimT - 1)
    private val genSize = chunkSize - 2 * trim

    /** Number of model runs [demix] will make for [samples] input samples (for progress bars). */
    fun chunkCount(samples: Int): Int {
        val total = trim + samples + padFor(samples)
        val step = stepSize()
        return (total + step - 1) / step
    }

    /**
     * Full equivalent of `MDXSeparator.separate()`: normalizes the mix, demixes the instrumental,
     * derives vocals as `mix - instrumental * compensate`, and applies the output normalization.
     * To save memory (a 4-minute song is ~85 MB per stereo copy), [mix] is overwritten: it ends
     * up holding the vocals when [withVocals] is set. [onChunk] gets (done, total) after each
     * model run; throw from it to cancel.
     */
    fun separate(
        mix: Array<FloatArray>,
        withVocals: Boolean = false,
        onChunk: (Int, Int) -> Unit = { _, _ -> },
    ): Stems {
        val peak = peakOf(mix)
        val normalized = normalize(mix, MAX_PEAK)
        val primary = demix(normalized, onChunk)
        // audio-separator multiplies by the original peak here; kept for identical loudness.
        for (ch in primary) for (i in ch.indices) ch[i] *= peak
        val vocals = if (withVocals) {
            for (c in 0 until 2) for (i in normalized[c].indices) normalized[c][i] -= primary[c][i] * params.compensate
            normalize(normalized, MAX_PEAK)
        } else {
            null
        }
        return Stems(normalize(primary, MAX_PEAK), vocals)
    }

    /** `MDXSeparator.demix(mix, is_match_mix=False)`: model output for a stereo mix, same length. */
    fun demix(mix: Array<FloatArray>, onChunk: (Int, Int) -> Unit = { _, _ -> }): Array<FloatArray> {
        val samples = mix[0].size
        // The padded mixture is [trim zeros, mix, pad zeros]; it's read from mix by offset instead of copied.
        val total = trim + samples + padFor(samples)
        val result = Array(2) { FloatArray(total) }
        val divider = FloatArray(total)
        val step = stepSize()
        val chunks = chunkCount(samples)
        val part = Array(2) { FloatArray(chunkSize) }

        var done = 0
        var start = 0
        while (start < total) {
            val end = minOf(start + chunkSize, total)
            val actual = end - start
            // Copy mixture[start, end) where mixture[j] = mix[j - trim] inside the signal, else 0.
            val from = maxOf(start, trim)
            val to = minOf(end, trim + samples)
            for (c in 0 until 2) {
                part[c].fill(0f)
                if (to > from) System.arraycopy(mix[c], from - trim, part[c], from - start, to - from)
            }
            val out = runModel(part)
            val window = if (overlap != 0f) hanning(actual) else null
            for (i in 0 until actual) {
                val w = window?.get(i) ?: 1f
                divider[start + i] += w
                for (c in 0 until 2) result[c][start + i] += out[c][i] * w
            }
            onChunk(++done, chunks)
            start += step
        }

        for (c in 0 until 2) {
            val r = result[c]
            for (i in 0 until samples) {
                val d = divider[trim + i]
                r[i] = if (d != 0f) r[trim + i] / d else 0f
            }
        }
        return Array(2) { c -> result[c].copyOf(samples) }
    }

    /** `MDXSeparator.run_model()`: STFT, zero the 3 lowest bins, network, inverse STFT. */
    private fun runModel(part: Array<FloatArray>): Array<FloatArray> {
        val spec = stft.forward(part)
        val frames = params.dimT
        for (plane in 0 until 4) {
            val base = plane * params.dimF * frames
            spec.fill(0f, base, base + 3 * frames)
        }
        return stft.inverse(model.run(spec), frames)
    }

    private fun padFor(samples: Int) = genSize + trim - samples % genSize

    private fun stepSize() = ((1 - overlap) * chunkSize).toInt()

    companion object {
        /** audio-separator's default normalization_threshold. */
        const val MAX_PEAK = 0.9f

        fun peakOf(audio: Array<FloatArray>): Float = audio.maxOf { ch -> ch.maxOfOrNull { abs(it) } ?: 0f }

        /** `spec_utils.normalize`: scale down (in place) to [maxPeak] if louder. */
        fun normalize(audio: Array<FloatArray>, maxPeak: Float): Array<FloatArray> {
            val peak = peakOf(audio)
            require(peak.isFinite()) { "Audio data must contain only finite values" }
            if (peak > maxPeak) {
                val scale = maxPeak / peak
                for (ch in audio) for (i in ch.indices) ch[i] *= scale
            }
            return audio
        }

        /** `np.hanning`: symmetric Hann window. */
        fun hanning(n: Int): FloatArray =
            if (n == 1) floatArrayOf(1f)
            else FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    }
}
