package com.comicshelf.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.comicshelf.app.reader.ReaderPageKeys
import com.comicshelf.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val readerPageKeys = ReaderPageKeys()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComicShelfTheme {
                AppRoot(
                    onOpenAllFilesAccess = ::openAllFilesAccess,
                    readerPageKeys = readerPageKeys,
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        readerPageKeys.onKeyDown(keyCode, event.repeatCount) || super.onKeyDown(keyCode, event)

    private fun openAllFilesAccess() {
        try {
            startActivity(Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        } catch (t: Throwable) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

@Composable
fun ComicShelfTheme(content: @Composable () -> Unit) {
    // A reader is a dark-ambient app; comic pages read best on dark chrome.
    val scheme = darkColorScheme(
        primary = Color(0xFF8AB4F8),
        onPrimary = Color(0xFF002E5B),
        secondary = Color(0xFFB9C4D9),
        surface = Color(0xFF101318),
        background = Color(0xFF0A0C10),
        surfaceVariant = Color(0xFF1C2027),
        onSurface = Color(0xFFE4E7EC),
        onSurfaceVariant = Color(0xFFB0B7C3),
        outline = Color(0xFF565E6B),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
