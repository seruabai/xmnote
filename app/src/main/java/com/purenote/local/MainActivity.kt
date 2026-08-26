package com.purenote.local

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.purenote.local.data.NotePrefill
import com.purenote.local.notify.Reminders
import com.purenote.local.ui.AppRoot
import com.purenote.local.ui.theme.PureNoteTheme

class MainActivity : ComponentActivity() {

    private val vm: NoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncoming(intent)
        setContent {
            val mode by vm.themeMode.collectAsState()
            PureNoteTheme(mode) { AppRoot(vm) }
            val notifPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    .ifBlank { intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty() }
                @Suppress("DEPRECATION")
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (text.isNotBlank() || stream != null) {
                    vm.pendingShare = NotePrefill(
                        body = text,
                        imageUris = listOfNotNull(stream?.toString()),
                    )
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val streams = IntentCompat.getParcelableArrayListExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java,
                ).orEmpty()
                if (streams.isNotEmpty()) {
                    vm.pendingShare = NotePrefill(imageUris = streams.map { it.toString() })
                }
            }
            else -> {
                val id = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
                if (id > 0) {
                    val kind = intent.getStringExtra(Reminders.EXTRA_KIND) ?: Reminders.KIND_NOTE
                    vm.pendingOpenTarget = kind to id
                }
            }
        }
    }
}
