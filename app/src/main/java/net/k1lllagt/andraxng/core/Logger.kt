package net.k1lllagt.andraxng.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Logger(private val dir: File) {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    val file: File get() = File(dir, "andrax-ng.log")

    @Synchronized
    private fun write(level: String, msg: String) {
        try {
            dir.mkdirs()
            val f = file
            if (f.length() > 1_000_000) {
                val old = File(dir, "andrax-ng.log.1")
                old.delete()
                f.renameTo(old)
            }
            f.appendText("${fmt.format(Date())} $level $msg\n")
        } catch (ignored: Throwable) {
        }
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        write("I", msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        write("W", msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        write("E", msg + (if (t != null) " :: " + Log.getStackTraceString(t) else ""))
    }

    fun tail(maxChars: Int = 60_000): String {
        return try {
            val t = file.readText()
            if (t.length > maxChars) t.substring(t.length - maxChars) else t
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        const val TAG = "ANDRAX-NG"
    }
}
