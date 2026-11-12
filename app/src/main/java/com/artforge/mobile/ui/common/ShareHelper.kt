package com.artforge.mobile.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Shares or opens exported files through the system intents. Uses an application
 * context registered at startup so it works from view-model callbacks.
 */
object ShareHelper {

    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun share(uri: Uri, mimeType: String, title: String) {
        val context = appContext ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun open(uri: Uri, mimeType: String) {
        val context = appContext ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
