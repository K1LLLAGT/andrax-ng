package net.k1lllagt.andraxng.ui

/** Texts shown in more than one screen. */
object HelpText {
    val LIMITS = """
What this app is: a Linux userland (a root filesystem) running on top of Android's own kernel. It is not a virtual machine and not a custom kernel.

Both devices
- No monitor mode and no packet injection. Those need Wi-Fi drivers and kernel support that this app cannot add.
- No kernel modules, no systemd, no services at boot. A simple init script (andrax-init) starts services you list.
- USB, Bluetooth and radios are only reachable if Android already exposes them to apps.
- Files in the shared folder live on Android shared storage: no symlinks, no Unix permissions. Never install tools there.
- Android may kill background processes (phantom process killer, battery limits). See the background-running help in Settings.

Galaxy S24 Ultra (not rooted)
- Runs through proot: it translates system calls, so it is slower than native and a few calls are unsupported.
- No raw sockets: ping and some scan types can fail. TCP connect scans work.
- No raw kernel or NetHunter features. No chroot, no mounts, no iptables.
- This device has no 32-bit support, so the 32-bit ANDRAX archive cannot run here.

Galaxy Tab S7 (rooted)
- Root mode uses a real chroot. You get raw sockets and mounts, but only the features your installed kernel supports. This app does not change the kernel.
- If root is denied, the app falls back to proot.
- 32-bit ARM works, so the ANDRAX archive can run here. It is an old Debian 10 era image, so many of its tools are outdated.

Distribution
- Built with targetSdk 28 so downloaded tools can execute. That makes it sideload-only; Google Play will not accept it.
- Use it only on systems you own or are authorized to test.
""".trimIndent()

    val BACKGROUND = """
Android 12 and newer can kill the background processes an app starts ("phantom process killer"). Symptom: your session ends with "signal 9". These steps come from the Termux and agnostic-apollo documentation; they are not tested on your devices.

Android 14 or newer (no computer needed)
Settings > Developer options > turn on "Disable child process restrictions", then reboot.

Android 12L and 13 (over adb)
adb shell settings put global settings_enable_monitor_phantom_procs false

Android 12 (over adb)
adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"

Rooted device (no computer)
su -c "settings put global settings_enable_monitor_phantom_procs false"

Reboot afterwards. The setting can be reset by a system update, so reapply it if "signal 9" comes back.

Also: tap "Allow background running" in the setup screen, and on Samsung devices remove ANDRAX-NG from Settings > Battery > Background usage limits (Sleeping apps).
""".trimIndent()
}
