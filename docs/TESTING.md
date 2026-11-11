# Install, test and troubleshoot

Run Termux commands in zsh. `adb` commands run on a PC, or on the phone itself after the wireless
debugging setup below.

## 0. Get an APK
- **CI:** push to GitHub (below), then download the `andrax-ng-apks` artifact.
- Android 14+: allow "Install unknown apps" for the app you open the APK with.

```
cd ~/src/andrax-ng
pkg install -y gh
gh auth login                                     # follow the prompts
gh repo create andrax-ng --private --source=. --push
gh run watch                                      # waits for the build
gh run download -n andrax-ng-apks -D ~/apk
termux-open ~/apk/debug/app-debug.apk             # opens the package installer
```
If the repo already exists: `git remote add origin <url>` then `git push -u origin main`.

## 1. adb on the phone itself (optional)
Developer options > Wireless debugging > on > "Pair device with pairing code".
```
pkg install -y android-tools
adb pair localhost:<pairing-port>      # enter the 6-digit code
adb connect localhost:<connect-port>
adb devices
```

## 2. Galaxy S24 Ultra (SM-S928U, not rooted)

1. Install the APK and open it. The wizard starts.
2. Step 1 must show: `proot helper: OK: proot ...` and "32-bit support: no". Root: none. Mode: proot.
   **If the helper line says FAIL, stop and send me the full text.**
3. Tap "Grant storage access" and "Allow background running".
4. Step 2: Kali NetHunter minimal, Download. Progress should go Downloading, Verifying, Extracting,
   Applying fixes. Minimal is about 120 MiB to download and about 780 MiB extracted.
5. Open the terminal and run:
```
id                       # uid=0(root)
uname -m                 # aarch64
cat /etc/os-release | head -3
ls /sdcard               # shows the shared folder
touch /sdcard/hello && ls /sdcard
apt update
```
6. Shared folder check, from Termux: `ls /storage/emulated/0/ANDRAX-NG-share`.
7. Background test: start `apt update` (or `sleep 600`), lock the screen for 10 minutes, unlock. The session
   must still be alive. If it ends with "signal 9", apply the phantom process fix (section 5).
8. Settings > "Export logs" produces `andrax-ng-report.txt`.
9. Settings > "Reset / delete a rootfs", then reinstall via setup.

Expected on this device: ANDRAX is not offered (no 32-bit ABI); `ping 1.1.1.1` may fail (no raw sockets).

## 3. Galaxy Tab S7 (SM-T870, rooted)

1. Same install. Step 1 must show "32-bit support: yes". Approve the Magisk prompt when it appears.
   Root: granted. Mode: chroot.
2. Install Kali minimal (as above) and open it. The banner says "chroot as root".
3. Inside:
```
id; tty                  # root; /dev/pts/N (if "not a tty", send me this)
ping -c1 1.1.1.1         # raw sockets: should work as root
mount | head             # proc, sys, dev, /sdcard visible
exit
```
4. After exit, from Termux:
```
su -c "grep net.k1lllagt /proc/mounts"           # must print nothing
```
5. Kill test: open a session, then Settings > App info > Force stop. Then:
```
su -c "grep net.k1lllagt /proc/mounts"           # may show leftovers
```
   Reopen the app and the session: the launch script clears stale mounts first. Or use Settings >
   "Unmount leftover chroot mounts".
6. ANDRAX: setup > "ANDRAX r5 (armhf)" > Import. Pick `/sdcard/Download/ANDRAX-NG/andrax.r5.v5-b5.tar.xz`.
   The SHA-256 field is prefilled with your hash (`895e09e1...9945a9e3d`). Keep about 13 GiB free.
   Extraction is expected to take a long time; the notification shows progress. Afterwards:
```
cat /etc/apt/sources.list            # archive.debian.org lines
ls /opt/ANDRAX
cat /root/andrax-ng-firstboot.log    # appears after the first login
```
7. Fallback test: Settings > Mode > "Always proot", open ANDRAX again. It should start (32-bit through
   proot's `loader32`). If not, send the Export logs report.
8. Revoke root for the app in Magisk, set Mode back to Auto: it should fall back to proot.

## 4. Useful commands

```
adb logcat -s ANDRAX-NG                      # app log tag
adb logcat | grep -E 'ANDRAX|proot|AndroidRuntime'
adb shell run-as net.k1lllagt.andraxng ls files          # debug builds only
adb shell run-as net.k1lllagt.andraxng tail -50 files/logs/andrax-ng.log

# run the proot helper by hand (debug build)
D=$(adb shell pm path net.k1lllagt.andraxng | head -1 | sed 's/package://; s/base.apk//' | tr -d '\r')
adb shell "run-as net.k1lllagt.andraxng env LD_LIBRARY_PATH=/data/data/net.k1lllagt.andraxng/files/lib:${D}lib/arm64 ${D}lib/arm64/libproot.so --version"
```

## 5. Phantom process killer (Android 12+)
From the Termux/agnostic-apollo documentation, not tested on these devices.
- Android 14+: Settings > Developer options > "Disable child process restrictions", then reboot.
- Android 12L/13: `adb shell settings put global settings_enable_monitor_phantom_procs false`
- Android 12: `adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"` and
  `adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"`
- Rooted: `su -c "settings put global settings_enable_monitor_phantom_procs false"`
Reapply after system updates if "signal 9" returns.

## 6. Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| proot helper: FAIL, "libtalloc.so.2" | symlink missing | Reopen the app (it recreates `files/lib/libtalloc.so.2`); send the report if it persists |
| proot helper: FAIL, "Permission denied" or "not executable" | native libs not extracted | Reinstall the APK; confirm `adb shell pm path` shows a `lib/arm64` directory |
| Session dies with "signal 9" | phantom process killer, battery limits | Section 5; allow background running; remove the app from Samsung "Sleeping apps" |
| "ptrace" or "seccomp" errors from proot | kernel or SELinux restriction | Send the report; this needs a code change |
| "Not enough free space" | free-space guard | Free space or delete another rootfs |
| Checksum mismatch after download | corrupted download, or Kali updated the file | Retry; the app re-downloads once automatically |
| Imported archive: "not verified" in the log | no hash entered | Paste the SHA-256 and retry |
| apt: "Temporary failure resolving" | DNS | Settings > DNS 1/2 (applied on next launch) |
| apt: 404 on ANDRAX | Debian 10 is archived | Expected; only archive.debian.org packages exist |
| Chroot: "su denied" | Magisk grant | Magisk > Superuser > allow the app; Settings > Re-detect root |
| Chroot: mount errors | namespace or SELinux policy | Send the report (includes mount output) |
| Leftover mounts | killed session | Settings > Unmount leftover chroot mounts |
| Terminal opens then closes | launch error | The toast or status line has the message; Export logs |
| "Launch script ... is missing" | old APK | Reinstall the latest build |
