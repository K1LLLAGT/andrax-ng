package net.k1lllagt.andraxng.core

import android.content.Context

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("andrax-ng", Context.MODE_PRIVATE)

    /** "auto", "proot" or "chroot" */
    var modeOverride: String
        get() = sp.getString("modeOverride", "auto") ?: "auto"
        set(v) { sp.edit().putString("modeOverride", v).apply() }

    var activeProfile: String
        get() = sp.getString("activeProfile", "") ?: ""
        set(v) { sp.edit().putString("activeProfile", v).apply() }

    /** Host folder bound to /sdcard inside the rootfs. Empty = no bind. */
    var sharedFolder: String
        get() = sp.getString("sharedFolder", "/storage/emulated/0/ANDRAX-NG-share") ?: ""
        set(v) { sp.edit().putString("sharedFolder", v).apply() }

    var dns1: String
        get() = sp.getString("dns1", "1.1.1.1") ?: "1.1.1.1"
        set(v) { sp.edit().putString("dns1", v).apply() }

    var dns2: String
        get() = sp.getString("dns2", "8.8.8.8") ?: "8.8.8.8"
        set(v) { sp.edit().putString("dns2", v).apply() }

    var shell: String
        get() = sp.getString("shell", "/bin/bash") ?: "/bin/bash"
        set(v) { sp.edit().putString("shell", v).apply() }

    var hostname: String
        get() = sp.getString("hostname", "andrax") ?: "andrax"
        set(v) { sp.edit().putString("hostname", v).apply() }

    var keepArchive: Boolean
        get() = sp.getBoolean("keepArchive", false)
        set(v) { sp.edit().putBoolean("keepArchive", v).apply() }

    var wakeLock: Boolean
        get() = sp.getBoolean("wakeLock", true)
        set(v) { sp.edit().putBoolean("wakeLock", v).apply() }
}
