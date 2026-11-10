package net.k1lllagt.andraxng.core

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException

/**
 * What to start in the terminal. [args] does NOT include argv[0]; the UI adds [exe] first.
 * [env] is "KEY=VALUE" strings for the terminal process.
 */
class LaunchSpec(
    val exe: String,
    val args: List<String>,
    val env: List<String>,
    val cwd: String,
    val title: String,
    val mode: Mode
)

object ScriptInstaller {
    private const val MODE_0755 = 493

    fun install(ctx: Context, paths: Paths) {
        paths.binDir.mkdirs()
        for (name in listOf("start-andrax", "start-andrax-root")) {
            val text = try {
                ctx.assets.open("scripts/$name").bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                throw IOException("Launch script assets/scripts/$name is missing from the APK")
            }
            val f = File(paths.binDir, name)
            f.writeText(text)
            try {
                Os.chmod(f.path, MODE_0755)
            } catch (e: ErrnoException) {
            }
        }
    }
}

object LaunchBuilder {
    /**
     * Prepares scripts, network files, fake /proc files and the env file, then returns the command.
     * The scripts read everything from the env file (su does not pass the environment through).
     */
    fun prepare(
        ctx: Context,
        paths: Paths,
        settings: Settings,
        log: Logger,
        profile: Profile,
        requestedMode: Mode
    ): LaunchSpec {
        if (!paths.isInstalled(profile)) throw IOException("${profile.title} is not installed")
        val gone = NativeLibs.missing(paths)
        if (gone.isNotEmpty()) throw IOException("Missing native helpers: $gone")
        if (profile.arch == Arch.ARMHF && !DeviceInfo.has32BitAbi) {
            throw IOException("${profile.title} is 32-bit and this device has no 32-bit ABI")
        }

        var mode = requestedMode
        if (mode == Mode.CHROOT && DeviceInfo.rootStatus != RootStatus.GRANTED) {
            if (settings.modeOverride == "chroot") {
                throw IOException("Chroot mode needs root, but su was denied or not found. Use proot mode.")
            }
            log.w("root not granted, falling back to proot")
            mode = Mode.PROOT
        }

        paths.ensureDirs()
        NativeLibs.prepare(paths, log)
        ScriptInstaller.install(ctx, paths)

        val root = paths.rootfs(profile)
        PostInstall.refreshNetwork(root, settings)
        writeFakeProc(paths)
        val shared = prepareShared(settings, log)

        val vars = linkedMapOf(
            "ANDRAX_MODE" to (if (mode == Mode.CHROOT) "chroot" else "proot"),
            "ANDRAX_PROFILE" to profile.id,
            "ANDRAX_ARCH" to profile.arch.label,
            "ANDRAX_ROOTFS" to root.path,
            "ANDRAX_SHARED" to shared,
            "ANDRAX_SHELL" to settings.shell,
            "ANDRAX_EXTRA_PATH" to profile.extraPath,
            "ANDRAX_HOSTNAME" to settings.hostname,
            "ANDRAX_PROOT" to paths.proot.path,
            "ANDRAX_LD_PATH" to "${paths.libDir.path}:${paths.nativeDir.path}",
            "ANDRAX_LOADER" to paths.prootLoader.path,
            "ANDRAX_LOADER32" to paths.prootLoader32.path,
            "ANDRAX_TMP" to paths.tmpDir.path,
            "ANDRAX_FAKEPROC" to paths.fakeProcDir.path,
            "ANDRAX_LOG" to File(paths.logsDir, "session.log").path
        )
        paths.envFile().writeText(vars.entries.joinToString("\n", postfix = "\n") { "${it.key}=${shq(it.value)}" })

        val env = listOf(
            "HOME=${paths.homeDir.path}",
            "TMPDIR=${paths.tmpDir.path}",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "PATH=" + (System.getenv("PATH") ?: "/system/bin:/system/xbin"),
            "ANDROID_ROOT=" + (System.getenv("ANDROID_ROOT") ?: "/system"),
            "ANDROID_DATA=" + (System.getenv("ANDROID_DATA") ?: "/data")
        )

        val scriptName = if (mode == Mode.CHROOT) "start-andrax-root" else "start-andrax"
        val script = File(paths.binDir, scriptName).path
        val envFile = paths.envFile().path

        val spec = if (mode == Mode.CHROOT) {
            val su = DeviceInfo.findSu() ?: throw IOException("su not found")
            LaunchSpec(su, listOf("-c", "/system/bin/sh $script $envFile"), env, paths.homeDir.path,
                "${profile.title} (chroot)", mode)
        } else {
            LaunchSpec("/system/bin/sh", listOf(script, envFile), env, paths.homeDir.path,
                "${profile.title} (proot)", mode)
        }
        log.i("launch ${profile.id} mode=$mode exe=${spec.exe}")
        return spec
    }

    private fun shq(v: String) = "'" + v.replace("'", "'\\''") + "'"

    private fun prepareShared(settings: Settings, log: Logger): String {
        val dir = settings.sharedFolder.trim()
        if (dir.isEmpty()) return ""
        val f = File(dir)
        if (!f.exists() && !f.mkdirs()) {
            log.w("shared folder $dir does not exist and could not be created (storage permission?). Not binding it.")
            return ""
        }
        return dir
    }

    /** Android hides several /proc files from apps; tools like ps/top/uptime need something there. */
    private fun writeFakeProc(paths: Paths) {
        paths.fakeProcDir.mkdirs()
        fun put(name: String, text: String) {
            val f = File(paths.fakeProcDir, name)
            if (!f.exists()) f.writeText(text)
        }
        put("loadavg", "0.12 0.07 0.02 2/165 765\n")
        put("uptime", "5000.00 4000.00\n")
        put("version", "Linux version 5.4.0-andrax-ng (build@android) #1 SMP PREEMPT\n")
        put("stat", "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 1957 0 2877 93280 262 342 254 87 0 0\n" +
            "intr 0\nctxt 0\nbtime 1700000000\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
    }
}
