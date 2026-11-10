package net.k1lllagt.andraxng.core

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object Fmt {
    fun bytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var v = n.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }
}

object Sha256 {
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun of(file: File, cancel: AtomicBoolean, onProgress: (Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var done = 0L
        var last = 0L
        FileInputStream(file).use { ins ->
            while (true) {
                if (cancel.get()) throw InstallCancelled()
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (now - last > 250) {
                    last = now
                    onProgress(done)
                }
            }
        }
        onProgress(done)
        return hex(md.digest())
    }
}
