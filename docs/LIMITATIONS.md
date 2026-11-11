# Known limitations

## By design (Android, not this app)
- Not a VM and not a custom kernel: the rootfs runs on the phone's own kernel.
- No monitor mode, no packet injection, no kernel modules. The S24 kernel has none of this; on the Tab S7
  only what the installed kernel supports can work.
- No systemd and no boot services. List init.d services in `/etc/andrax-ng/services` and run `andrax-init`.
- USB, Bluetooth and radios are reachable only if Android already exposes them to apps.
- The shared folder is Android shared storage: no symlinks, no Unix permissions. Never install tools there.
- Android may kill background processes (phantom process killer, battery limits). See Settings > help.

## proot mode (S24, or T870 without root)
- Slower than native; a few syscalls are unsupported.
- No raw sockets: ping and some scan types can fail. TCP connect scans work.
- No mounts, iptables or chroot inside.
- Hardlinks are emulated with symlinks (`--link2symlink`). Tools that check link counts can notice.
- Everything is owned by the app's uid; "root" is faked by proot. setuid binaries do nothing.

## chroot mode (T870, rooted)
- Needs a working `su`. Mounts are removed on exit, on hangup and on the next launch, but a hard crash
  can leave mounts until the next launch or "Unmount leftover chroot mounts" in Settings.
- Raw sockets and mounts work, subject to the installed kernel and SELinux policy.

## ANDRAX r5 archive
- 32-bit ARM and Debian 10 era (2020). The S24 has no 32-bit support, so it only runs on the Tab S7.
- Many tools are outdated. apt is pointed at archive.debian.org, where only old packages exist.
- Import only; the app cannot download it.

## App
- Extraction of the 10.7 GiB ANDRAX archive cannot resume mid-stream (one xz block); an interruption
  restarts extraction. Downloads of Kali do resume. Extraction speed has not been measured.
- One terminal session per screen; no tabs. Back keeps the session alive in the background; type `exit`
  or use the notification's Stop action.
- No X11 or graphical desktop.
- Optional "split install" and chunked repacking from the original brief were not built.
- Not on Google Play (targetSdk 28). Android may show an install warning; sideload only.
- The CI workflow and the on-device behavior (proot from `nativeLibraryDir`, chroot /dev/pts handling) are
  untested until you run them; report results and they get fixed.
