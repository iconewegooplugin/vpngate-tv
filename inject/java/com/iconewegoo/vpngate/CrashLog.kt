package com.iconewegoo.vpngate

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the last uncaught exception (any process of this app) so VPN Gate TV can show it on next start. */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "vg_crash.txt")

    fun install(app: Application) {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val proc = try { Application.getProcessName() } catch (_: Throwable) { "?" }
                file(app).writeText("process=$proc thread=${t.name}\n$sw")
            } catch (_: Throwable) { }
            old?.uncaughtException(t, e)
        }
    }

    fun read(c: Context): String? = try { file(c).takeIf { it.exists() }?.readText() } catch (_: Exception) { null }
    fun clear(c: Context) { try { file(c).delete() } catch (_: Exception) { } }
}
