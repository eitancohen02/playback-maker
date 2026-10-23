package io.github.eitancohen02.playbackmaker.separator

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end checks against audio-separator; need the model file (see build.gradle.kts). */
class MdxSeparatorTest {
    private fun withModel(block: (OnnxSpectrogramModel) -> Unit) {
        val file = Golden.modelFile
        assumeTrue(file != null, "model file not found; set MDX_MODEL")
        OnnxSpectrogramModel(file!!.path).use(block)
    }

    @Test
    fun `one chunk matches audio-separator run_model`() = withModel { model ->
        val expected = Golden.stereo("chunk_out.f32")
        val p = MdxParams.INST_HQ_4
        val stft = Stft(p.nFft, p.hop, p.dimF)
        val spec = stft.forward(Golden.stereo("chunk_in.f32"))
        for (plane in 0 until 4) spec.fill(0f, plane * p.dimF * p.dimT, plane * p.dimF * p.dimT + 3 * p.dimT)
        val actual = stft.inverse(model.run(spec), p.dimT)
        for (c in 0 until 2) {
            val snr = snrDb(expected[c], actual[c])
            assertTrue(snr > 40, "chunk SNR $snr dB on channel $c")
        }
    }

    @Test
    fun `full demix matches audio-separator`() = withModel { model ->
        val separator = MdxSeparator(model, overlap = 0.25f)
        val mix = Golden.stereo("mix.f32")
        var calls = 0
        val stems = separator.demix(MdxSeparator.normalize(mix.map { it.copyOf() }.toTypedArray(), 0.9f)) { done, total ->
            calls = done
            assertEquals(separator.chunkCount(mix[0].size), total)
        }
        assertEquals(separator.chunkCount(mix[0].size), calls)

        val peak = MdxSeparator.peakOf(mix)
        val expected = Golden.stereo("instrumental.f32")
        for (c in 0 until 2) {
            val actual = FloatArray(stems[c].size) { stems[c][it] * peak }
            val snr = snrDb(expected[c], actual)
            println("instrumental channel $c: SNR $snr dB")
            assertTrue(snr > 40, "instrumental SNR $snr dB on channel $c")
        }
    }
}
