package net.k1lllagt.andraxng.core

import android.os.Build
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class Mode { PROOT, CHROOT }

enum class RootStatus { UNKNOWN, NO_SU, DENIED, GRANTED }

object DeviceInfo {
    val model: String get() = Build.MODEL ?: "unknown"
    val isT870: Boolean get() = model.startsWith("SM-T870")
    val isS24Ultra: Boolean get() = model.startsWith("SM-S928")
    val has32BitAbi: Boolean get() = Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()
    val hasArm64: Boolean get() = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")

    @Volatile
    var rootStatus: RootStatus = RootStatus.UNKNOWN
        private set

    @Volatile
    var suPath: String? = null
        private set

    private val suCandidates = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
        "/product/bin/su", "/vendor/bin/su", "/data/adb/magisk/su"
    )

    fun findSu(): String? {
        suPath?.let { return it }
        val fromPath = (System.getenv("PATH") ?: "").split(':').filter { it.isNotEmpty() }.map { "$it/su" }
        val found = (suCandidates + fromPath).firstOrNull {
            val f = File(it)
            f.exists() && f.canExecute()
        }
        suPath = found
        return found
    }

    /** Blocking (a Magisk prompt may appear). Call from a background thread. */
    fun probeRoot(): RootStatus {
        val su = findSu()
        if (su == null) {
            rootStatus = RootStatus.NO_SU
            return rootStatus
        }
        rootStatus = try {
            val p = ProcessBuilder(su, "-c", "id").redirectErrorStream(true).start()
            if (!p.waitFor(25, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                RootStatus.DENIED
            } else {
                val out = p.inputStream.bufferedReader().readText()
                if (out.contains("uid=0")) RootStatus.GRANTED else RootStatus.DENIED
            }
        } catch (e: IOException) {
            RootStatus.NO_SU
        }
        return rootStatus
    }

    fun autoMode(): Mode = if (rootStatus == RootStatus.GRANTED) Mode.CHROOT else Mode.PROOT

    fun effectiveMode(settings: Settings): Mode = when (settings.modeOverride) {
        "proot" -> Mode.PROOT
        "chroot" -> Mode.CHROOT
        else -> autoMode()
    }
}

object RootShell {
    class Result(val exit: Int, val out: String)

    fun run(command: String, timeoutSec: Long = 60): Result {
        val su = DeviceInfo.suPath ?: DeviceInfo.findSu() ?: return Result(127, "su not found")
        return try {
            val p = ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread {
                try {
                    out.append(p.inputStream.bufferedReader().readText())
                } catch (e: IOException) {
                }
            }
            reader.start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                reader.join(1000)
                Result(124, out.toString() + "\n[timeout]")
            } else {
                reader.join(2000)
                Result(p.exitValue(), out.toString())
            }
        } catch (e: IOException) {
            Result(127, e.message ?: "su failed")
        }
    }
}
