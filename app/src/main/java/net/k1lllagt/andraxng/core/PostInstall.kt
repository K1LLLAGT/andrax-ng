package net.k1lllagt.andraxng.core

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException

/**
 * Fixes applied to a freshly extracted rootfs. All writes go through ensureDirChain/removeExisting,
 * so nothing is ever written through a symlink that points outside the rootfs.
 * Script bodies live in assets/rootfs/ (they contain shell, not Kotlin).
 */
object PostInstall {
    private const val MODE_0644 = 420
    private const val MODE_0755 = 493
    private const val MODE_0700 = 448
    private const val MODE_1777 = 1023

    fun apply(ctx: Context, profile: Profile, root: File, settings: Settings, log: Logger) {
        ensureMountPoints(root)
        refreshNetwork(root, settings)
        writeApt(profile, root, log)
        writeFile(root, "etc/apt/apt.conf.d/99andrax-ng-sandbox",
            "// proot cannot switch to the _apt user\nAPT::Sandbox::User \"root\";\n", MODE_0644)
        // systemd cannot run here and package installs must not try to start services.
        writeFile(root, "usr/sbin/policy-rc.d", "#!/bin/sh\nexit 101\n", MODE_0755)
        writeAsset(ctx, root, "etc/profile.d/00-andrax-ng.sh", "rootfs/00-andrax-ng.sh", MODE_0644)
        writeAsset(ctx, root, "usr/local/bin/andrax-init", "rootfs/andrax-init", MODE_0755)
        writeAsset(ctx, root, "usr/local/sbin/andrax-ng-firstboot", "rootfs/firstboot.sh", MODE_0755)
        writeAsset(ctx, root, "etc/profile.d/10-andrax-ng-firstboot.sh", "rootfs/profile-hook.sh", MODE_0644)
        if (!SafeFs.exists("${root.path}/etc/andrax-ng/services")) {
            writeFile(root, "etc/andrax-ng/services",
                "# One init.d service name per line; andrax-init starts them.\n", MODE_0644)
        }
        log.i("post-install fixes applied to ${root.path}")
    }

    fun ensureMountPoints(root: File) {
        val ok = HashSet<String>()
        for (d in listOf("dev", "proc", "sys", "tmp", "sdcard", "mnt", "run", "root", "home", "media")) {
            SafeFs.ensureDirChain(root.path, d, ok)
        }
        chmod("${root.path}/tmp", MODE_1777)
        chmod("${root.path}/root", MODE_0700)
    }

    /** Called at install time and again before every launch so Settings changes take effect. */
    fun refreshNetwork(root: File, settings: Settings) {
        val dns = listOf(settings.dns1, settings.dns2).map { sanitizeIp(it) }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        writeFile(root, "etc/resolv.conf", dns.joinToString("") { "nameserver $it\n" }, MODE_0644)
        val host = settings.hostname.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "andrax" }
        writeFile(root, "etc/hostname", host + "\n", MODE_0644)
        writeFile(root, "etc/hosts",
            "127.0.0.1 localhost\n127.0.1.1 $host\n::1 localhost ip6-localhost ip6-loopback\n", MODE_0644)
    }

    private fun readTextSafe(f: File): String = try {
        if (f.isFile) f.readText() else ""
    } catch (e: IOException) {
        ""
    }

    private fun writeApt(profile: Profile, root: File, log: Logger) {
        val existing = readTextSafe(File(root, "etc/apt/sources.list"))
        when (profile.kind) {
            Kind.KALI -> {
                // Current Kali images use /etc/apt/sources.list.d/kali.sources (deb822) and ship no
                // sources.list. Only add a default if no Kali source exists anywhere, otherwise apt
                // warns about the same target being configured twice.
                val dir = File(root, "etc/apt/sources.list.d")
                val inDir = if (dir.isDirectory) (dir.listFiles() ?: emptyArray()).any { readTextSafe(it).contains("kali-rolling") } else false
                if (!existing.contains("kali-rolling") && !inDir) {
                    writeFile(root, "etc/apt/sources.list",
                        "deb http://http.kali.org/kali kali-rolling main contrib non-free non-free-firmware\n", MODE_0644)
                    log.w("no Kali apt source found in the rootfs; wrote a default sources.list")
                }
            }
            Kind.ANDRAX -> {
                // Debian 10 (buster) moved to archive.debian.org. Rewrite only Debian hosts, keep the rest.
                if (existing.isNotEmpty()) {
                    val bak = "${root.path}/etc/apt/sources.list.andrax-ng.bak"
                    if (!SafeFs.exists(bak)) writeFile(root, "etc/apt/sources.list.andrax-ng.bak", existing, MODE_0644)
                    val hosts = "(deb\\.debian\\.org|ftp\\.debian\\.org|httpredir\\.debian\\.org|ftp\\.[a-z]{2}\\.debian\\.org)"
                    var fixed = existing
                    fixed = Regex("https?://$hosts/debian-security").replace(fixed, "http://archive.debian.org/debian-security")
                    fixed = Regex("https?://security\\.debian\\.org(/debian-security)?").replace(fixed, "http://archive.debian.org/debian-security")
                    fixed = Regex("https?://$hosts/debian").replace(fixed, "http://archive.debian.org/debian")
                    writeFile(root, "etc/apt/sources.list", fixed, MODE_0644)
                    log.i("apt sources.list now:\n$fixed")
                }
                writeFile(root, "etc/apt/apt.conf.d/99andrax-ng-archive",
                    "Acquire::Check-Valid-Until \"false\";\n", MODE_0644)
            }
        }
    }

    private fun sanitizeIp(s: String): String = s.trim().filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' }

    private fun chmod(path: String, mode: Int) {
        try {
            Os.chmod(path, mode)
        } catch (e: ErrnoException) {
        }
    }

    /** Writes [text] at [rel] under [root], replacing any existing file or symlink. */
    fun writeFile(root: File, rel: String, text: String, mode: Int): Boolean {
        if (!SafeFs.ensureDirChain(root.path, rel.substringBeforeLast('/', ""), HashSet())) return false
        val p = "${root.path}/$rel"
        return try {
            SafeFs.removeExisting(p)
            File(p).writeText(text)
            Os.chmod(p, mode)
            true
        } catch (e: ErrnoException) {
            false
        } catch (e: IOException) {
            false
        }
    }

    private fun writeAsset(ctx: Context, root: File, rel: String, asset: String, mode: Int) {
        val text = ctx.assets.open(asset).bufferedReader().use { it.readText() }
        if (!writeFile(root, rel, text, mode)) throw IOException("Could not write $rel into the rootfs")
    }
}
