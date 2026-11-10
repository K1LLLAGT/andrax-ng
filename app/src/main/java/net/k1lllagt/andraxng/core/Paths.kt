package net.k1lllagt.andraxng.core

import android.content.Context
import java.io.File

/** All on-disk locations the app uses. Everything lives in the app-private filesDir. */
class Paths(ctx: Context) {
    val files: File = ctx.filesDir
    val nativeDir: File = File(ctx.applicationInfo.nativeLibraryDir)

    val libDir = File(files, "lib")              // runtime symlinks (libtalloc.so.2)
    val tmpDir = File(files, "tmp")
    val logsDir = File(files, "logs")
    val binDir = File(files, "bin")              // start-andrax, start-andrax-root
    val runDir = File(files, "run")              // generated env file
    val fakeProcDir = File(files, "fakeproc")
    val downloadsDir = File(files, "downloads")
    val rootfsRoot = File(files, "rootfs")
    val homeDir = File(files, "home")

    val proot = File(nativeDir, "libproot.so")
    val prootLoader = File(nativeDir, "libproot-loader.so")
    val prootLoader32 = File(nativeDir, "libproot-loader32.so")

    fun rootfs(p: Profile) = File(rootfsRoot, p.id)
    fun partial(p: Profile) = File(rootfsRoot, p.id + ".partial")
    fun envFile() = File(runDir, "andrax.env")
    fun isInstalled(p: Profile) = File(rootfs(p), MARKER).exists()

    fun ensureDirs() {
        listOf(libDir, tmpDir, logsDir, binDir, runDir, fakeProcDir, downloadsDir, rootfsRoot, homeDir)
            .forEach { it.mkdirs() }
    }

    companion object {
        const val MARKER = ".andrax-ng-installed"
    }
}
