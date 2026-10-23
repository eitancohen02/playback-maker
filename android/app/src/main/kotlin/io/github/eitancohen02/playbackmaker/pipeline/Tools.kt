package io.github.eitancohen02.playbackmaker.pipeline

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File

/**
 * yt-dlp, Python and ffmpeg come from youtubedl-android. Its ffmpeg is a plain executable in
 * nativeLibraryDir that needs the library folders unpacked by init(); we run it directly for
 * decoding and encoding (the desktop app's `export()` step).
 */
object Tools {
    @Volatile private var ready = false

    /** Unpacks Python/ffmpeg on first launch (a few seconds). Safe to call repeatedly. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
        ready = true
    }

    /** Runs ffmpeg with [args]; throws with ffmpeg's error output on failure. */
    fun ffmpeg(context: Context, vararg args: String) {
        val packages = File(context.noBackupFilesDir, "${YoutubeDL.baseName}/packages")
        val binary = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val process = ProcessBuilder(listOf(binary.path, "-nostdin", "-hide_banner", "-loglevel", "error", "-y") + args)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = "$packages/ffmpeg/usr/lib:$packages/python/usr/lib"
            }
            .start()
        try {
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) throw IllegalStateException("ffmpeg failed ($code): ${output.trim().takeLast(500)}")
        } catch (e: InterruptedException) {
            process.destroy()
            throw e
        }
    }
}
