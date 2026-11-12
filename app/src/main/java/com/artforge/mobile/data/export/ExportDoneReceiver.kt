package com.artforge.mobile.data.export

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.artforge.mobile.core.util.ForgeLog

/**
 * Receives completion broadcasts for long-running background exports so the UI can
 * refresh its recent-exports list even if it was recreated.
 */
class ExportDoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        ForgeLog.i(TAG, "export completed: $path")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(KEY_EXPORTS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_EXPORTS, (existing + path).toList().takeLast(50).toSet()).apply()
    }

    companion object {
        private const val TAG = "ExportDoneReceiver"
        const val ACTION_EXPORT_DONE = "com.artforge.mobile.EXPORT_DONE"
        const val EXTRA_PATH = "path"
        private const val PREFS = "artforge_exports"
        private const val KEY_EXPORTS = "recent"

        /** Records a finished export path so the UI can list it after recreation. */
        fun record(context: Context, file: java.io.File) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getStringSet(KEY_EXPORTS, emptySet()) ?: emptySet()
            prefs.edit().putStringSet(KEY_EXPORTS, (existing + file.absolutePath).toList().takeLast(50).toSet()).apply()
        }

        fun recent(context: Context): List<String> =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_EXPORTS, emptySet())?.toList()?.sortedDescending() ?: emptyList()
    }
}
