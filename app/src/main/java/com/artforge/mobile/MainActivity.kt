package com.artforge.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.artforge.mobile.ui.navigation.AppNavigation

/**
 * Single-activity host. The app is fully native Compose — no WebView anywhere.
 */
class MainActivity : ComponentActivity() {

    private var projectFromIntent by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        projectFromIntent = intent?.data?.let { uri ->
            // Deep link: content://…/name.artforge — import it, then open.
            runCatching {
                val app = application as ArtForgeApplication
                kotlinx.coroutines.runBlocking {
                    app.projectRepository.importProject(uri)?.id
                }
            }.getOrNull()
        }
        setContent {
            AppNavigation(startProjectId = projectFromIntent)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent.data ?: return
        val app = application as ArtForgeApplication
        kotlinx.coroutines.runBlocking {
            app.projectRepository.importProject(uri)?.let { doc ->
                projectFromIntent = doc.id
            }
        }
    }
}
