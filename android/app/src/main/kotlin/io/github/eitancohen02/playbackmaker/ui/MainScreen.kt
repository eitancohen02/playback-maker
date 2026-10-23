package io.github.eitancohen02.playbackmaker.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.eitancohen02.playbackmaker.pipeline.ModelManager
import io.github.eitancohen02.playbackmaker.pipeline.OutputFormat
import io.github.eitancohen02.playbackmaker.work.PlaybackWorker
import io.github.eitancohen02.playbackmaker.work.PlaybackWorker.Companion as W

@Composable
fun PlaybackTheme(content: @Composable () -> Unit) {
    val primary = Color(0xFF3D3A8C)
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFB9B6FF))
    else lightColorScheme(primary = primary)
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(sharedUrl: String?, onSharedUrlUsed: () -> Unit) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf(OutputFormat.MP3) }
    var keepVocals by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) {
            url = sharedUrl
            onSharedUrlUsed()
        }
    }

    val workManager = remember { WorkManager.getInstance(context) }
    val infos by workManager.getWorkInfosForUniqueWorkFlow(W.WORK_NAME).collectAsState(initial = emptyList())
    val work = infos.firstOrNull()
    val running = work != null && !work.state.isFinished

    Scaffold(topBar = { TopAppBar(title = { Text("Playback Maker") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Paste a YouTube link, or share a video to Playback Maker from the YouTube app.")

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("YouTube link") },
                singleLine = true,
                enabled = !running,
                trailingIcon = {
                    TextButton(onClick = { pasteFromClipboard(context)?.let { url = it } }, enabled = !running) {
                        Text("Paste")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                OutputFormat.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = format == f,
                        onClick = { format = f },
                        enabled = !running,
                        shape = SegmentedButtonDefaults.itemShape(i, OutputFormat.entries.size),
                    ) { Text(f.name) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = keepVocals, onCheckedChange = { keepVocals = it }, enabled = !running)
                Text("Also save the vocals track")
            }

            if (running) {
                OutlinedButton(onClick = { PlaybackWorker.cancel(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
                ProgressCard(work!!)
            } else {
                Button(
                    onClick = { PlaybackWorker.start(context, url.trim(), format, keepVocals) },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Make playback") }
                when (work?.state) {
                    WorkInfo.State.SUCCEEDED -> ResultCard(work.outputData)
                    WorkInfo.State.FAILED -> ErrorCard(work.outputData.getString(W.KEY_ERROR) ?: "Something went wrong.")
                    else -> {}
                }
            }

            Spacer(Modifier.height(8.dp))
            if (!ModelManager.isDownloaded(context)) {
                Text(
                    "The first run downloads the separation model (59 MB, one time only).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Files are saved to Music/Playback Maker. For personal use: respect copyright and YouTube's terms.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProgressCard(work: WorkInfo) {
    val stage = work.progress.getString(W.KEY_STAGE)?.let { PlaybackWorker.Stage.valueOf(it) }
        ?: PlaybackWorker.Stage.PREPARING
    val progress = work.progress.getFloat(W.KEY_PROGRESS, -1f)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stage.label)
            if (progress >= 0) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (stage == PlaybackWorker.Stage.SEPARATING) {
                Text(
                    "This takes a few minutes. You can leave the app; progress shows in the notification.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(output: androidx.work.Data) {
    val context = LocalContext.current
    val playback = output.getString(W.KEY_PLAYBACK_URI)?.let(Uri::parse) ?: return
    val vocals = output.getString(W.KEY_VOCALS_URI)?.let(Uri::parse)
    val seconds = output.getInt(W.KEY_AUDIO_SECONDS, 0)
    val ms = output.getLong(W.KEY_SEPARATION_MS, 0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Done: ${output.getString(W.KEY_TITLE)}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Removed vocals from a ${formatDuration(seconds * 1000L)} song in ${formatDuration(ms)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            StemButtons("Playback", playback, context)
            if (vocals != null) StemButtons("Vocals", vocals, context)
        }
    }
}

@Composable
private fun StemButtons(label: String, uri: Uri, context: Context) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text("Play $label")
        }
        OutlinedButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Share $label"))
        }) {
            Icon(Icons.Default.Share, contentDescription = null)
            Text("Share")
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

private fun pasteFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
