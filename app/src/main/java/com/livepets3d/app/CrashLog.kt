package com.livepets3d.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the last crash on the phone so it can be viewed and copied from Settings. */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun install(app: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            try { record(app, e, "thread=${th.name}") } catch (_: Throwable) {}
            prev?.uncaughtException(th, e)
        }
    }

    fun record(c: Context, t: Throwable, extra: String = "handled") {
        try {
            val sw = StringWriter(); t.printStackTrace(PrintWriter(sw))
            file(c).writeText("LivePets ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})  Android ${Build.VERSION.RELEASE} ${Build.MODEL}  $extra\n" +
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "\n\n" + sw.toString().take(12000))
        } catch (_: Throwable) {}
    }

    fun read(c: Context): String? = file(c).takeIf { it.exists() }?.readText()
    fun clear(c: Context) { file(c).delete() }
}
