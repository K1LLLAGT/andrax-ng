package net.k1lllagt.andraxng.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object Downloader {
    private const val UA = "ANDRAX-NG/0.1 (Android)"

    private fun open(url: String, connectTimeoutMs: Int, rangeFrom: Long = 0): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
        return c
    }

    fun fetchText(url: String): String {
        val c = open(url, 20_000)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Resumable download with retries. Safe to call again on the same [dest] to continue. */
    fun download(
        url: String,
        dest: File,
        cancel: AtomicBoolean,
        onProgress: (done: Long, total: Long) -> Unit,
        log: Logger?
    ) {
        dest.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            if (cancel.get()) throw InstallCancelled()
            try {
                downloadOnce(url, dest, cancel, onProgress)
                return
            } catch (e: InstallCancelled) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= 5) throw e
                log?.w("download attempt $attempt failed: ${e.message}; retrying")
                Thread.sleep(2000L * attempt)
            }
        }
    }

    private fun downloadOnce(url: String, dest: File, cancel: AtomicBoolean, onProgress: (Long, Long) -> Unit) {
        val have = if (dest.exists()) dest.length() else 0L
        val c = open(url, 20_000, have)
        try {
            val code = c.responseCode
            var append = have > 0
            val total: Long
            when (code) {
                206 -> {
                    total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: (have + c.contentLengthLong)
                }
                200 -> {
                    append = false // server ignored Range: start over
                    total = c.contentLengthLong
                }
                416 -> return // nothing left to fetch; the checksum decides if the file is good
                else -> throw IOException("HTTP $code for $url")
            }
            FileOutputStream(dest, append).use { out ->
                c.inputStream.use { ins ->
                    var done = if (append) have else 0L
                    val buf = ByteArray(256 * 1024)
                    var last = 0L
                    while (true) {
                        if (cancel.get()) throw InstallCancelled()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 250) {
                            last = now
                            onProgress(done, total)
                        }
                    }
                    if (total > 0 && done < total) throw IOException("connection closed early at $done of $total bytes")
                    onProgress(done, total)
                }
            }
        } finally {
            c.disconnect()
        }
    }
}
