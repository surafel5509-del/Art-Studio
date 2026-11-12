package com.artforge.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.artforge.mobile.ui.navigation.AppNavigation
import kotlinx.coroutines.launch

/**
 * Single-activity host. The app is fully native Compose — no WebView anywhere.
 */
class MainActivity : ComponentActivity() {

    private var projectFromIntent by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AppNavigation(startProjectId = projectFromIntent)
        }
        // Deep link (content://…/name.artforge) is imported off the main thread so the
        // first frame is never blocked by ZIP extraction; navigation happens when done.
        handleIncomingUri(intent?.data)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop activity: a second VIEW intent arrives here, not in onCreate.
        handleIncomingUri(intent.data)
    }

    /** Copies a shared `.artforge` package into app storage and opens it. */
    private fun handleIncomingUri(uri: android.net.Uri?) {
        if (uri == null) return
        val app = application as ArtForgeApplication
        lifecycleScope.launch {
            val doc = app.projectRepository.importProject(uri)
            projectFromIntent = doc?.id
        }
    }
}
