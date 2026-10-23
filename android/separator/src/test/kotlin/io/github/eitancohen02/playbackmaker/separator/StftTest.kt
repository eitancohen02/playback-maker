package io.github.eitancohen02.playbackmaker.separator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StftTest {
    private val p = MdxParams.INST_HQ_4
    private val stft = Stft(p.nFft, p.hop, p.dimF)

    @Test
    fun `forward matches torch stft`() {
        val chunk = Golden.stereo("chunk_in.f32")
        val spec = stft.forward(chunk)
        assertEquals(p.dimT, stft.frames(chunk[0].size))

        // Golden holds the first 8 frames of each [4, dimF] plane, with the 3 lowest bins zeroed.
        val expected = Golden.floats("spek_first8.f32")
        val actual = FloatArray(expected.size)
        var i = 0
        for (plane in 0 until 4) for (k in 0 until p.dimF) for (t in 0 until 8) {
            actual[i++] = if (k < 3) 0f else spec[(plane * p.dimF + k) * p.dimT + t]
        }
        val snr = snrDb(expected, actual)
        assertTrue(snr > 60, "STFT SNR $snr dB")
    }

    @Test
    fun `inverse reconstructs the signal`() {
        val chunk = Golden.stereo("chunk_in.f32")
        val back = stft.inverse(stft.forward(chunk), p.dimT)
        // Only the Nyquist bin is dropped (dimF = nFft / 2); the synthetic clip has white noise up there.
        for (c in 0 until 2) {
            val snr = snrDb(chunk[c], back[c])
            assertTrue(snr > 50, "round-trip SNR $snr dB on channel $c")
        }
    }
}
