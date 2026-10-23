package io.github.eitancohen02.playbackmaker.separator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

/** [SpectrogramModel] backed by an MDX-Net `.onnx` file (input and output named like UVR's models). */
class OnnxSpectrogramModel(
    modelPath: String,
    private val params: MdxParams = MdxParams.INST_HQ_4,
    options: OrtSession.SessionOptions = OrtSession.SessionOptions(),
) : SpectrogramModel, Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(modelPath, options)
    private val shape = longArrayOf(1, 4, params.dimF.toLong(), params.dimT.toLong())

    override fun run(spectrogram: FloatArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(spectrogram), shape).use { input ->
            session.run(mapOf("input" to input)).use { result ->
                val output = result[0] as OnnxTensor
                val buffer = output.floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }

    override fun close() = session.close()
}
