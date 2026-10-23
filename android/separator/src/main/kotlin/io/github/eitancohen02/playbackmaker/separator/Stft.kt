package io.github.eitancohen02.playbackmaker.separator

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos

/**
 * Stereo STFT / inverse STFT matching audio-separator's `uvr_lib_v5/stft.py`, which wraps
 * `torch.stft(center=True)` (reflect padding, periodic Hann window, no normalization).
 *
 * Spectrogram layout is the model's input layout, flattened: `[4, dimF, frames]` where the
 * 4 planes are left-real, left-imag, right-real, right-imag.
 */
class Stft(private val nFft: Int, private val hop: Int, private val dimF: Int) {
    private val fft = FloatFFT_1D(nFft.toLong())
    private val window = FloatArray(nFft) { (0.5 - 0.5 * cos(2.0 * PI * it / nFft)).toFloat() }
    private val half = nFft / 2

    init {
        require(dimF <= half) { "dimF must be at most nFft / 2" }
    }

    fun frames(samples: Int) = 1 + samples / hop

    /** `channels`: 2 arrays of equal length. Returns `[4, dimF, frames]`. */
    fun forward(channels: Array<FloatArray>): FloatArray {
        val samples = channels[0].size
        val frames = frames(samples)
        val out = FloatArray(4 * dimF * frames)
        val buf = FloatArray(nFft)
        for (c in 0 until 2) {
            val padded = reflectPad(channels[c])
            val rePlane = (2 * c) * dimF * frames
            val imPlane = (2 * c + 1) * dimF * frames
            for (t in 0 until frames) {
                val start = t * hop
                for (n in 0 until nFft) buf[n] = padded[start + n] * window[n]
                fft.realForward(buf)
                // JTransforms packing: buf[2k] = Re(k), buf[2k+1] = Im(k) for 0 < k < n/2; buf[1] = Re(n/2).
                out[rePlane + t] = buf[0]
                out[imPlane + t] = 0f
                for (k in 1 until dimF) {
                    out[rePlane + k * frames + t] = buf[2 * k]
                    out[imPlane + k * frames + t] = buf[2 * k + 1]
                }
            }
        }
        return out
    }

    /**
     * Inverse of [forward]: `spec` is `[4, dimF, frames]`; bins above dimF are treated as zero.
     * Returns 2 channels of `hop * (frames - 1)` samples, like `torch.istft(center=True)`.
     */
    fun inverse(spec: FloatArray, frames: Int): Array<FloatArray> {
        val fullLength = nFft + hop * (frames - 1)
        val outLength = fullLength - nFft
        val envelope = FloatArray(fullLength)
        for (t in 0 until frames) {
            val start = t * hop
            for (n in 0 until nFft) envelope[start + n] += window[n] * window[n]
        }
        val buf = FloatArray(nFft)
        return Array(2) { c ->
            val acc = FloatArray(fullLength)
            val rePlane = (2 * c) * dimF * frames
            val imPlane = (2 * c + 1) * dimF * frames
            for (t in 0 until frames) {
                buf.fill(0f)
                // irfft ignores the imaginary part of the DC bin; the Nyquist bin is zero padding.
                buf[0] = spec[rePlane + t]
                for (k in 1 until dimF) {
                    buf[2 * k] = spec[rePlane + k * frames + t]
                    buf[2 * k + 1] = spec[imPlane + k * frames + t]
                }
                fft.realInverse(buf, true)
                val start = t * hop
                for (n in 0 until nFft) acc[start + n] += buf[n] * window[n]
            }
            FloatArray(outLength) { i ->
                val e = envelope[half + i]
                if (e > 1e-11f) acc[half + i] / e else 0f
            }
        }
    }

    /** torch's `center=True` padding: reflect `nFft / 2` samples on each side (edge not repeated). */
    private fun reflectPad(x: FloatArray): FloatArray {
        require(x.size > half) { "signal shorter than nFft / 2" }
        val out = FloatArray(x.size + 2 * half)
        for (i in 0 until half) out[i] = x[half - i]
        System.arraycopy(x, 0, out, half, x.size)
        for (j in 0 until half) out[half + x.size + j] = x[x.size - 2 - j]
        return out
    }
}
