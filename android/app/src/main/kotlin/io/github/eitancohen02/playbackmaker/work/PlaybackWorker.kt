package io.github.eitancohen02.playbackmaker.work

import ai.onnxruntime.OrtSession
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.eitancohen02.playbackmaker.MainActivity
import io.github.eitancohen02.playbackmaker.R
import io.github.eitancohen02.playbackmaker.pipeline.Downloader
import io.github.eitancohen02.playbackmaker.pipeline.MediaSaver
import io.github.eitancohen02.playbackmaker.pipeline.ModelManager
import io.github.eitancohen02.playbackmaker.pipeline.OutputFormat
import io.github.eitancohen02.playbackmaker.pipeline.SAMPLE_RATE
import io.github.eitancohen02.playbackmaker.pipeline.Tools
import io.github.eitancohen02.playbackmaker.pipeline.decodeAudio
import io.github.eitancohen02.playbackmaker.pipeline.encodeAudio
import io.github.eitancohen02.playbackmaker.pipeline.safeFilename
import io.github.eitancohen02.playbackmaker.separator.MdxSeparator
import io.github.eitancohen02.playbackmaker.separator.OnnxSpectrogramModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Runs the whole pipeline (`make_playback()` in playback.py) as foreground work with a notification. */
class PlaybackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var lastReport = 0L

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val format = OutputFormat.valueOf(inputData.getString(KEY_FORMAT) ?: OutputFormat.MP3.name)
        val keepVocals = inputData.getBoolean(KEY_KEEP_VOCALS, false)
        val workDir = File(applicationContext.cacheDir, "job-$id").apply { mkdirs() }
        val processId = UUID.randomUUID().toString()

        report(Stage.PREPARING, -1f, force = true)
        return try {
            runInterruptible(Dispatchers.IO) { run(url, format, keepVocals, workDir, processId) }
        } catch (e: CancellationException) {
            Downloader.cancel(processId)
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Playback failed", e)
            if (isStopped) Result.failure()
            else Result.failure(workDataOf(KEY_ERROR to friendlyError(e)))
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun run(url: String, format: OutputFormat, keepVocals: Boolean, workDir: File, processId: String): Result {
        val context = applicationContext
        Tools.init(context)

        val model = ModelManager.ensureModel(context, { isStopped }) { report(Stage.MODEL, it) }

        report(Stage.DOWNLOADING, -1f, force = true)
        Downloader.updateIfDue(context) { Log.w(TAG, it) }
        val download = Downloader.download(url, workDir, processId) { report(Stage.DOWNLOADING, it) }
        report(Stage.DECODING, -1f, force = true)
        val mix = decodeAudio(context, download.file, workDir)
        download.file.delete()
        val seconds = mix[0].size / SAMPLE_RATE

        val started = System.currentTimeMillis()
        // Measured on a Galaxy A56: these cut onnxruntime's peak memory from ~2.2 GB to ~0.9 GB
        // for ~4% slower separation, so the app also fits on 4 GB phones.
        val options = OrtSession.SessionOptions().apply {
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
        val stems = OnnxSpectrogramModel(model.path, options = options).use { onnx ->
            MdxSeparator(onnx, overlap = 0.25f).separate(mix, withVocals = keepVocals) { done, total ->
                if (isStopped) throw InterruptedException("cancelled")
                report(Stage.SEPARATING, done.toFloat() / total)
            }
        }
        val separationMs = System.currentTimeMillis() - started
        Log.i(TAG, "Separated ${seconds}s of audio in ${separationMs}ms")

        report(Stage.SAVING, -1f, force = true)
        val base = safeFilename(download.title)
        val playbackUri = saveStem(stems.instrumental, "$base (Playback)", format, workDir)
        val vocalsUri = stems.vocals?.let { saveStem(it, "$base (Vocals)", format, workDir) }

        return Result.success(
            workDataOf(
                KEY_TITLE to download.title,
                KEY_PLAYBACK_URI to playbackUri,
                KEY_VOCALS_URI to vocalsUri,
                KEY_AUDIO_SECONDS to seconds,
                KEY_SEPARATION_MS to separationMs,
            ),
        )
    }

    private fun saveStem(audio: Array<FloatArray>, name: String, format: OutputFormat, workDir: File): String {
        val file = File(workDir, "out.${format.extension}")
        encodeAudio(applicationContext, audio, format, file, workDir)
        val uri = MediaSaver.save(applicationContext, file, "$name.${format.extension}", format.mimeType)
        file.delete()
        return uri.toString()
    }

    /** Publishes progress to the UI and the notification, throttled to a few updates a second. */
    private fun report(stage: Stage, progress: Float, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReport < 300) return
        lastReport = now
        setProgressAsync(workDataOf(KEY_STAGE to stage.name, KEY_PROGRESS to progress))
        setForegroundAsync(foregroundInfo(stage, progress))
    }

    override suspend fun getForegroundInfo() = foregroundInfo(Stage.PREPARING, -1f)

    private fun foregroundInfo(stage: Stage, progress: Float): ForegroundInfo {
        ensureChannel()
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(stage.label)
            .setProgress(100, (progress * 100).toInt().coerceAtLeast(0), progress < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun ensureChannel() {
        if (notifications.getNotificationChannel(CHANNEL_ID) == null) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Making playbacks", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun friendlyError(e: Throwable): String {
        val message = e.message.orEmpty()
        return when {
            e is OutOfMemoryError -> "Not enough memory for this song. Try a shorter one."
            "Unsupported URL" in message || "is not a valid URL" in message -> "That doesn't look like a YouTube link."
            "Video unavailable" in message || "Private video" in message -> "This video is unavailable."
            e is java.net.UnknownHostException -> "No internet connection."
            else -> message.lines().lastOrNull { it.isNotBlank() }?.take(300) ?: e.javaClass.simpleName
        }
    }

    enum class Stage(val label: String) {
        PREPARING("Getting ready…"),
        MODEL("Downloading the separation model (one time only)…"),
        DOWNLOADING("Downloading audio…"),
        DECODING("Reading audio…"),
        SEPARATING("Removing vocals…"),
        SAVING("Saving…"),
    }

    companion object {
        private const val TAG = "PlaybackWorker"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        const val WORK_NAME = "playback"

        const val KEY_URL = "url"
        const val KEY_FORMAT = "format"
        const val KEY_KEEP_VOCALS = "keep_vocals"
        const val KEY_STAGE = "stage"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_TITLE = "title"
        const val KEY_PLAYBACK_URI = "playback_uri"
        const val KEY_VOCALS_URI = "vocals_uri"
        const val KEY_AUDIO_SECONDS = "audio_seconds"
        const val KEY_SEPARATION_MS = "separation_ms"

        fun start(context: Context, url: String, format: OutputFormat, keepVocals: Boolean) {
            val input: Data = workDataOf(KEY_URL to url, KEY_FORMAT to format.name, KEY_KEEP_VOCALS to keepVocals)
            val request = OneTimeWorkRequestBuilder<PlaybackWorker>().setInputData(input).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
