package net.k1lllagt.andraxng.core

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class InstallCancelled : IOException("Cancelled")

class ExtractStats {
    var files = 0L
    var dirs = 0L
    var symlinks = 0L
    var hardlinks = 0L
    var hardlinkFallbacks = 0L
    var skippedSpecial = 0L
    var skippedUnsafe = 0L
    var errors = 0L

    override fun toString() =
        "files=$files dirs=$dirs symlinks=$symlinks hardlinks=$hardlinks (symlink fallback $hardlinkFallbacks) " +
            "skippedSpecial=$skippedSpecial skippedUnsafe=$skippedUnsafe errors=$errors"
}

class ExtractResult(val sha256: String, val stats: ExtractStats)

class CountingInputStream(ins: InputStream) : FilterInputStream(ins) {
    @Volatile
    var count = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = super.skip(n)
        if (s > 0) count += s
        return s
    }
}

/**
 * Pure-JVM tar(.xz/.gz) extractor. Symlinks, hardlinks and permissions are applied with
 * android.system.Os (real syscalls), so the result is equivalent to a native tar into filesDir.
 * Ownership cannot be set without root; proot's fake-root mode (-0) covers that at runtime.
 */
object TarExtractor {
    private const val MODE_0600 = 384
    private const val MODE_0700 = 448
    private const val PERM_MASK = 511
    private val SKIP_CHILDREN_OF = setOf("dev", "proc", "sys")

    fun extract(
        raw: InputStream,
        totalBytes: Long,
        dest: File,
        profile: Profile,
        cancel: AtomicBoolean,
        log: Logger,
        onProgress: (done: Long, total: Long, detail: String) -> Unit
    ): ExtractResult {
        val counting = CountingInputStream(raw)
        val md = MessageDigest.getInstance("SHA-256")
        val digesting = DigestInputStream(counting, md)
        val buffered = BufferedInputStream(digesting, 1 shl 20)
        val tar = TarArchiveInputStream(openDecoder(buffered))

        val stats = ExtractStats()
        val root = dest.path
        val okDirs = HashSet<String>()
        val dirModes = ArrayList<Pair<String, Int>>()
        val buf = ByteArray(256 * 1024)
        var lastReport = 0L
        var loggedErrors = 0
        dest.mkdirs()

        while (true) {
            if (cancel.get()) throw InstallCancelled()
            val entry = tar.nextEntry ?: break
            val now = System.currentTimeMillis()
            if (now - lastReport > 250) {
                lastReport = now
                onProgress(counting.count, totalBytes, "${stats.files} files, ${stats.symlinks} links")
            }

            if (isUnsafe(entry.name)) {
                stats.skippedUnsafe++
                continue
            }
            val rel = normalize(entry.name, profile) ?: continue

            if (entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO) {
                stats.skippedSpecial++
                continue
            }
            if (rel.contains('/') && rel.substringBefore('/') in SKIP_CHILDREN_OF) {
                stats.skippedSpecial++
                continue
            }

            val path = "$root/$rel"
            if (!SafeFs.ensureDirChain(root, rel.substringBeforeLast('/', ""), okDirs)) {
                stats.skippedUnsafe++
                continue
            }

            try {
                when {
                    entry.isDirectory -> {
                        if (SafeFs.ensureDirChain(root, rel, okDirs)) {
                            dirModes.add(rel to entry.mode)
                            stats.dirs++
                        } else {
                            stats.skippedUnsafe++
                        }
                    }
                    entry.isSymbolicLink -> {
                        SafeFs.removeExisting(path)
                        Os.symlink(entry.linkName, path)
                        stats.symlinks++
                    }
                    entry.isLink -> {
                        val targetRel = normalize(entry.linkName, profile)
                        if (targetRel == null || isUnsafe(entry.linkName) ||
                            !SafeFs.ensureDirChain(root, targetRel.substringBeforeLast('/', ""), okDirs)
                        ) {
                            stats.skippedUnsafe++
                        } else {
                            SafeFs.removeExisting(path)
                            try {
                                Os.link("$root/$targetRel", path)
                                stats.hardlinks++
                            } catch (e: ErrnoException) {
                                // SELinux or filesystem refused link(): an absolute symlink resolves
                                // inside the rootfs under proot/chroot and behaves the same.
                                Os.symlink("/$targetRel", path)
                                stats.hardlinkFallbacks++
                            }
                        }
                    }
                    else -> {
                        SafeFs.removeExisting(path)
                        FileOutputStream(path).use { out ->
                            while (true) {
                                if (cancel.get()) throw InstallCancelled()
                                val n = tar.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                        Os.chmod(path, (entry.mode and PERM_MASK) or MODE_0600)
                        stats.files++
                    }
                }
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.ENOSPC) throw IOException("No space left on device")
                stats.errors++
                if (loggedErrors++ < 50) log.w("extract error on $rel: ${e.message}")
            } catch (e: IOException) {
                if (e is InstallCancelled || (e.message ?: "").contains("No space left")) throw e
                stats.errors++
                if (loggedErrors++ < 50) log.w("extract error on $rel: ${e.message}")
            }
        }

        // Read whatever is left so the checksum covers the entire file (padding, xz index).
        val sink = ByteArray(64 * 1024)
        while (digesting.read(sink) >= 0) {
            if (cancel.get()) throw InstallCancelled()
        }
        tar.close()

        // Directory modes last, always with owner rwx so the app can still manage the tree.
        for ((rel, mode) in dirModes) {
            try {
                Os.chmod("$root/$rel", (mode and PERM_MASK) or MODE_0700)
            } catch (e: ErrnoException) {
            }
        }
        onProgress(counting.count, totalBytes, "${stats.files} files, ${stats.symlinks} links")
        log.i("extract finished: $stats")
        return ExtractResult(Sha256.hex(md.digest()), stats)
    }

    fun extractFile(file: File, dest: File, profile: Profile, cancel: AtomicBoolean, log: Logger,
                    onProgress: (Long, Long, String) -> Unit): ExtractResult =
        FileInputStream(file).use { extract(it, file.length(), dest, profile, cancel, log, onProgress) }

    private fun openDecoder(b: BufferedInputStream): InputStream {
        b.mark(16)
        val head = ByteArray(6)
        var n = 0
        while (n < head.size) {
            val r = b.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        b.reset()
        val isXz = n >= 6 && head[0] == 0xFD.toByte() && head[1] == 0x37.toByte() &&
            head[2] == 0x7A.toByte() && head[3] == 0x58.toByte() && head[4] == 0x5A.toByte() && head[5] == 0.toByte()
        val isGz = n >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()
        return when {
            isXz -> XZCompressorInputStream(b)
            isGz -> GzipCompressorInputStream(b)
            else -> b // plain tar
        }
    }

    private fun isUnsafe(name: String): Boolean = name.split('/').any { it == ".." }

    /** Strips "./", leading/trailing "/", and the optional wrapper directory. Null = nothing to extract. */
    private fun normalize(raw: String, profile: Profile): String? {
        var segs = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.isEmpty()) return null
        val wrapper = profile.wrapperRegex
        if (wrapper != null && wrapper.matches(segs[0])) segs = segs.drop(1)
        if (segs.isEmpty()) return null
        return segs.joinToString("/")
    }
}
