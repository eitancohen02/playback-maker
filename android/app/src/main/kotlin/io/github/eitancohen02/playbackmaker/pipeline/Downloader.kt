package io.github.eitancohen02.playbackmaker.pipeline

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

class DownloadedAudio(val file: File, val title: String)

/** yt-dlp side of the pipeline (`download_audio()` in playback.py). */
object Downloader {
    private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /**
     * YouTube changes often break old yt-dlp versions, so update it from GitHub at most once a
     * day. Failure (e.g. offline) is not fatal: the bundled or last version is used.
     */
    fun updateIfDue(context: Context, log: (String) -> Unit) {
        val prefs = context.getSharedPreferences("downloader", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_update", 0)
        if (System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            log("Couldn't update yt-dlp (${e.message}); using the current version.")
        }
    }

    /** Downloads the best audio stream of [url] into [workDir]. [onProgress] gets 0..1. */
    fun download(url: String, workDir: File, processId: String, onProgress: (Float) -> Unit): DownloadedAudio {
        val request = YoutubeDLRequest(url).apply {
            addOption("-f", "bestaudio/best")
            addOption("--no-playlist")
            addOption("--write-info-json")
            addOption("-o", File(workDir, "source.%(ext)s").path)
        }
        YoutubeDL.getInstance().execute(request, processId) { progress, _, _ ->
            onProgress((progress / 100f).coerceIn(0f, 1f))
        }
        val info = File(workDir, "source.info.json")
        val title = runCatching { JSONObject(info.readText()).optString("title") }.getOrNull()
        val audio = workDir.listFiles { f -> f.name.startsWith("source.") && !f.name.endsWith(".json") && !f.name.endsWith(".part") }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("yt-dlp didn't produce an audio file")
        return DownloadedAudio(audio, title?.takeIf { it.isNotBlank() } ?: "track")
    }

    fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }
}
