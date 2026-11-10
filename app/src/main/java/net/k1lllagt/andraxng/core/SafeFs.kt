package net.k1lllagt.andraxng.core

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File helpers that never follow symlinks. A rootfs is full of absolute symlinks
 * (some may point at /sdcard or other host paths), so a normal recursive delete
 * could destroy real data. Everything here uses lstat.
 */
object SafeFs {
    private const val MODE_0700 = 448
    private const val MODE_0755 = 493

    fun lstatOrNull(path: String): StructStat? = try {
        Os.lstat(path)
    } catch (e: ErrnoException) {
        null
    }

    fun exists(path: String): Boolean = lstatOrNull(path) != null

    fun isRealDir(path: String): Boolean {
        val st = lstatOrNull(path) ?: return false
        return OsConstants.S_ISDIR(st.st_mode)
    }

    /** Removes a file, symlink or whole directory tree without following symlinks. */
    fun deleteTree(root: File, cancel: AtomicBoolean? = null) {
        val p = root.path
        val st = lstatOrNull(p) ?: return
        if (OsConstants.S_ISDIR(st.st_mode)) {
            try {
                Os.chmod(p, MODE_0700)
            } catch (e: ErrnoException) {
            }
            val names = root.list() ?: emptyArray()
            for (n in names) {
                if (cancel?.get() == true) throw InstallCancelled()
                deleteTree(File(root, n), cancel)
            }
        }
        try {
            Os.remove(p)
        } catch (e: ErrnoException) {
            throw IOException("Cannot delete $p: ${e.message}")
        }
    }

    fun removeExisting(path: String) {
        val st = lstatOrNull(path) ?: return
        if (OsConstants.S_ISDIR(st.st_mode)) deleteTree(File(path)) else Os.remove(path)
    }

    /**
     * Makes sure every component of [rel] under [root] is a REAL directory (created if missing).
     * Returns false if a component is a symlink or file, so callers never write through a symlink.
     */
    fun ensureDirChain(root: String, rel: String, ok: HashSet<String>): Boolean {
        if (rel.isEmpty() || rel in ok) return true
        val parent = rel.substringBeforeLast('/', "")
        if (!ensureDirChain(root, parent, ok)) return false
        val p = "$root/$rel"
        try {
            val st = lstatOrNull(p)
            if (st == null) Os.mkdir(p, MODE_0755)
            else if (!OsConstants.S_ISDIR(st.st_mode)) return false
        } catch (e: ErrnoException) {
            return false
        }
        ok.add(rel)
        return true
    }

    // ---- mounts (chroot mode) ----

    fun mountsUnder(root: File): List<String> {
        val text = if (DeviceInfo.rootStatus == RootStatus.GRANTED) {
            RootShell.run("cat /proc/mounts", 20).out
        } else {
            try {
                File("/proc/self/mounts").readText()
            } catch (e: IOException) {
                ""
            }
        }
        val prefixes = setOf(root.path, root.canonicalPath)
        val out = ArrayList<String>()
        for (line in text.lineSequence()) {
            val parts = line.split(' ')
            if (parts.size < 2) continue
            val mp = decodeOctal(parts[1])
            if (prefixes.any { mp == it || mp.startsWith("$it/") }) out.add(mp)
        }
        return out.distinct()
    }

    private fun decodeOctal(s: String): String =
        Regex("\\\\([0-7]{3})").replace(s) { m -> m.groupValues[1].toInt(8).toChar().toString() }

    fun unmountAll(root: File, log: Logger) {
        val mounts = mountsUnder(root)
        if (mounts.isEmpty()) return
        if (DeviceInfo.rootStatus != RootStatus.GRANTED) return
        for (mp in mounts.sortedByDescending { it.length }) {
            val r = RootShell.run("umount -l '${mp.replace("'", "'\\''")}'", 20)
            log.i("umount $mp -> exit ${r.exit} ${r.out.trim()}")
        }
    }

    /** Deletes a rootfs, refusing if anything is still mounted inside it. */
    fun deleteRootfs(root: File, log: Logger, cancel: AtomicBoolean? = null) {
        if (!exists(root.path)) return
        unmountAll(root, log)
        val left = mountsUnder(root)
        if (left.isNotEmpty()) {
            throw IOException("Refusing to delete: still mounted inside the rootfs: $left. Reboot or unmount them first.")
        }
        deleteTree(root, cancel)
    }
}
