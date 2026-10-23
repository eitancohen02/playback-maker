package io.github.eitancohen02.playbackmaker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import io.github.eitancohen02.playbackmaker.ui.MainScreen
import io.github.eitancohen02.playbackmaker.ui.PlaybackTheme

class MainActivity : ComponentActivity() {
    /** Link shared from YouTube (or another app), pre-filled into the URL field. */
    private val sharedUrl = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* progress still shows in the app */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            PlaybackTheme {
                MainScreen(sharedUrl = sharedUrl.value, onSharedUrlUsed = { sharedUrl.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // YouTube shares just the link, other apps may add a title around it.
        sharedUrl.value = Regex("https?://\\S+").find(text)?.value ?: text.trim()
    }
}
