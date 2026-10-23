package io.github.eitancohen02.playbackmaker.separator

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

/** Reference data written by tools/make_mdx_golden.py. */
object Golden {
    fun floats(name: String): FloatArray {
        val bytes = Golden::class.java.getResourceAsStream("/golden/$name")!!.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** A `[2, n]` file as two channels. */
    fun stereo(name: String): Array<FloatArray> {
        val flat = floats(name)
        val n = flat.size / 2
        return arrayOf(flat.copyOfRange(0, n), flat.copyOfRange(n, 2 * n))
    }

    val modelFile: File? = System.getProperty("mdx.model")?.let(::File)?.takeIf { it.exists() }
}

/** Signal-to-noise ratio of [actual] against [expected], in dB. */
fun snrDb(expected: FloatArray, actual: FloatArray): Double {
    require(expected.size == actual.size) { "size ${actual.size} != ${expected.size}" }
    var signal = 0.0
    var noise = 0.0
    for (i in expected.indices) {
        signal += expected[i].toDouble() * expected[i]
        val d = expected[i].toDouble() - actual[i]
        noise += d * d
    }
    return 10 * log10(signal / maxOf(noise, 1e-30))
}
