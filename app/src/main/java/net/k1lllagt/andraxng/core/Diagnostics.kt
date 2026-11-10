package net.k1lllagt.andraxng.core

import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diagnostics {
    /** Builds the report the Settings screen shares ("export logs"). */
    fun buildReport(paths: Paths, settings: Settings, log: Logger): File {
        val sb = StringBuilder()
        sb.appendLine("ANDRAX-NG diagnostic report")
        sb.appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
        sb.appendLine()
        sb.appendLine("== Device ==")
        sb.appendLine("model: ${Build.MODEL} (${Build.MANUFACTURER} ${Build.DEVICE})")
        sb.appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("abis32: ${Build.SUPPORTED_32_BIT_ABIS.joinToString()}")
        sb.appendLine("root: ${DeviceInfo.rootStatus} su=${DeviceInfo.suPath}")
        sb.appendLine("mode: override=${settings.modeOverride} effective=${DeviceInfo.effectiveMode(settings)}")
        sb.appendLine()
        sb.appendLine("== Native helpers ==")
        sb.appendLine("nativeDir: ${paths.nativeDir}")
        sb.appendLine("missing: ${NativeLibs.missing(paths)}")
        sb.appendLine("proot self-test: ${NativeLibs.selfTest(paths)}")
        sb.appendLine()
        sb.appendLine("== Profiles ==")
        for (p in Profiles.all) {
            val marker = File(paths.rootfs(p), Paths.MARKER)
            val state = if (marker.exists()) "installed: " + marker.readText().replace('\n', ' ') else "not installed"
            sb.appendLine("${p.id}: $state")
        }
        sb.appendLine("active profile: ${settings.activeProfile}")
        sb.appendLine()
        sb.appendLine("== Settings ==")
        sb.appendLine("shared=${settings.sharedFolder} dns=${settings.dns1},${settings.dns2} shell=${settings.shell} " +
            "hostname=${settings.hostname} keepArchive=${settings.keepArchive} wakeLock=${settings.wakeLock}")
        sb.appendLine("free space: ${Fmt.bytes(paths.files.usableSpace)}")
        sb.appendLine("install state: ${InstallState.snapshot}")
        sb.appendLine()
        sb.appendLine("== Log (tail) ==")
        sb.appendLine(log.tail())
        val out = File(paths.logsDir, "andrax-ng-report.txt")
        out.writeText(sb.toString())
        return out
    }
}
