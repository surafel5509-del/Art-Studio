package com.artforge.mobile.core.util

import android.util.Log

/**
 * Thin logging wrapper so engine code has one switch for verbose logging and a
 * single place to route diagnostics. In release builds [verbose] is false.
 */
object ForgeLog {
    var verbose: Boolean = false

    fun d(tag: String, msg: String) {
        if (verbose) Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) = Log.i(tag, msg)

    fun w(tag: String, msg: String) = Log.w(tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) = Log.e(tag, msg, t)
}
