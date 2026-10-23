package io.github.eitancohen02.playbackmaker.pipeline

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

const val SAMPLE_RATE = 44100

/** Decodes any audio file to 44.1 kHz stereo float channels (what the model expects). */
fun decodeAudio(context: Context, source: File, workDir: File): Array<FloatArray> {
    val raw = File(workDir, "decoded.f32")
    Tools.ffmpeg(context, "-i", source.path, "-vn", "-ac", "2", "-ar", "$SAMPLE_RATE", "-f", "f32le", raw.path)
    try {
        RandomAccessFile(raw, "r").use { file ->
            val buffer = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val frames = buffer.remaining() / 2
            val left = FloatArray(frames)
            val right = FloatArray(frames)
            for (i in 0 until frames) {
                left[i] = buffer.get()
                right[i] = buffer.get()
            }
            return arrayOf(left, right)
        }
    } finally {
        raw.delete()
    }
}

enum class OutputFormat(val extension: String, val mimeType: String, val codecArgs: List<String>) {
    MP3("mp3", "audio/mpeg", listOf("-c:a", "libmp3lame", "-b:a", "320k")),
    WAV("wav", "audio/wav", listOf("-c:a", "pcm_s16le")),
}

/** Encodes stereo float channels to [dest] (the desktop app's `export()`). */
fun encodeAudio(context: Context, audio: Array<FloatArray>, format: OutputFormat, dest: File, workDir: File) {
    val raw = File(workDir, "encode.f32")
    try {
        RandomAccessFile(raw, "rw").use { file ->
            val frames = audio[0].size
            val buffer = file.channel.map(FileChannel.MapMode.READ_WRITE, 0, frames * 8L)
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            for (i in 0 until frames) {
                buffer.put(audio[0][i])
                buffer.put(audio[1][i])
            }
        }
        Tools.ffmpeg(
            context,
            "-f", "f32le", "-ar", "$SAMPLE_RATE", "-ac", "2", "-i", raw.path,
            *format.codecArgs.toTypedArray(), dest.path,
        )
    } finally {
        raw.delete()
    }
}

/** Same rules as `safe_filename()` in playback.py. */
fun safeFilename(name: String): String {
    val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim(' ', '.')
    return cleaned.take(150).ifEmpty { "track" }
}
