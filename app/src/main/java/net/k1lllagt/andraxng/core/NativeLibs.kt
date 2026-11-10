package net.k1lllagt.andraxng.core

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * proot is dynamically linked against "libtalloc.so.2", a file name Android will not package.
 * We ship it as libtalloc.so and create a symlink with the expected name in filesDir/lib.
 */
object NativeLibs {
    private val required = listOf(
        "libproot.so", "libproot-loader.so", "libproot-loader32.so",
        "libtalloc.so", "libandroid-shmem.so"
    )

    fun missing(paths: Paths): List<String> = required.filter { !File(paths.nativeDir, it).exists() }

    fun prepare(paths: Paths, log: Logger) {
        paths.ensureDirs()
        val link = paths.libDir.path + "/libtalloc.so.2"
        val target = File(paths.nativeDir, "libtalloc.so").path
        try {
            val st = SafeFs.lstatOrNull(link)
            if (st != null) {
                val cur = if (OsConstants.S_ISLNK(st.st_mode)) Os.readlink(link) else null
                if (cur == target) return
                Os.remove(link)
            }
            Os.symlink(target, link)
        } catch (e: ErrnoException) {
            log.e("could not create libtalloc.so.2 symlink", e)
        }
    }

    fun prootEnv(paths: Paths): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to "${paths.libDir.path}:${paths.nativeDir.path}",
        "PROOT_LOADER" to paths.prootLoader.path,
        "PROOT_LOADER_32" to paths.prootLoader32.path,
        "PROOT_TMP_DIR" to paths.tmpDir.path
    )

    /** Runs "proot --version" with the same environment a session uses. */
    fun selfTest(paths: Paths): String {
        val gone = missing(paths)
        if (gone.isNotEmpty()) return "FAIL: missing native libs $gone"
        return try {
            val pb = ProcessBuilder(paths.proot.path, "--version").redirectErrorStream(true)
            pb.environment().putAll(prootEnv(paths))
            val p = pb.start()
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                "FAIL: proot --version timed out"
            } else {
                val out = p.inputStream.bufferedReader().readText().trim().take(400)
                if (p.exitValue() == 0) "OK: " + out.lineSequence().firstOrNull().orEmpty()
                else "FAIL (exit ${p.exitValue()}): $out"
            }
        } catch (e: IOException) {
            "FAIL: ${e.message}"
        }
    }
}
