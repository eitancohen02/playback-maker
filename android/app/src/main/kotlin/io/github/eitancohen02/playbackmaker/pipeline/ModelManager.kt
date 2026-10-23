package io.github.eitancohen02.playbackmaker.pipeline

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads the separation model once and keeps it in app storage (`model_dir()` on desktop). */
object ModelManager {
    const val MODEL_NAME = "UVR-MDX-NET-Inst_HQ_4.onnx"
    // Same source audio-separator downloads from on desktop.
    private const val MODEL_URL =
        "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/$MODEL_NAME"
    private const val MODEL_SHA256 = "3c4b5b9b05090fdf238f38ba5046813982d50e2a652e9cb3324ea79720c3c9c8"

    fun modelFile(context: Context) = File(context.filesDir, "models/$MODEL_NAME")

    fun isDownloaded(context: Context) = modelFile(context).exists()

    /** Returns the model file, downloading it first if needed. [onProgress] gets 0..1. */
    fun ensureModel(context: Context, isCancelled: () -> Boolean, onProgress: (Float) -> Unit): File {
        val file = modelFile(context)
        if (file.exists()) return file
        file.parentFile!!.mkdirs()
        val partial = File(file.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")

        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            if (connection.responseCode != 200) {
                throw IllegalStateException("Model download failed: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            var done = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (isCancelled()) throw InterruptedException("cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != MODEL_SHA256) {
            partial.delete()
            throw IllegalStateException("Model download was corrupted; please try again.")
        }
        partial.renameTo(file)
        return file
    }
}
