# ANDRAX-NG

An Android app (Kotlin + Gradle, built by GitHub Actions) that installs a Linux root filesystem
into its private storage and runs it in a terminal. Sideload-only. For your own authorized testing.

| Device | Mode | Profiles |
|---|---|---|
| Galaxy S24 Ultra (SM-S928U), not rooted | proot | Kali NetHunter arm64 (minimal / full / nano) |
| Galaxy Tab S7 (SM-T870), rooted | chroot via su, proot fallback | Kali arm64, plus your ANDRAX r5 armhf archive (import only) |

Package id: `net.k1lllagt.andraxng` (separate from `com.termux` and `com.t3rmux`).
Docs: `docs/TESTING.md` (install, test, troubleshoot), `docs/LIMITATIONS.md`.

## Architecture decisions

**proot vs chroot.** Without root only proot works (ptrace-based, no mounts). With root a real chroot is
used (faster, raw sockets, real mounts). Mode is auto-detected from `su` (not from the model name) and
can be overridden in Settings. If `su` is denied, auto mode falls back to proot.

**targetSdk 28.** Android 10+ blocks `exec()` from writable app directories for apps targeting API 29+,
and proot then executes the rootfs's own binaries from `filesDir`. Targeting 28 (as Termux's F-Droid
build does) keeps that working. proot and its helpers are also shipped as native libraries and run from
`nativeLibraryDir`, so the app itself does not depend on the exemption. Consequence: Google Play will
not accept this app. It is sideload-only. Android 14 installs targetSdk 28 apps without problems.

**Download vs bundle.** The rootfs is never inside the APK (about 7 MB). Kali is downloaded at first run
and checked against Kali's own `SHA256SUMS`; any archive can be imported through the file picker and is
checksummed in the same pass as extraction.

**Extraction.** A pure-JVM tar.xz reader writes into `filesDir` and applies symlinks, hardlinks and
modes with `android.system.Os` syscalls. This replaces the "native tar" idea: no helper binary is needed
before a rootfs exists, and progress and checksums come for free. A single xz block cannot be resumed
mid-stream, so extraction goes to `rootfs/<id>.partial` and is renamed on success. Downloads do resume.

**Symlinks are never followed** by extraction or deletion, and deletion refuses while anything is
mounted inside the rootfs (chroot mode bind-mounts shared storage).

**Native helpers.** `scripts/fetch-native.sh` takes proot, libtalloc and libandroid-shmem from the
official Termux package repo, verifies SHA-256 against the repo index, and installs them as
`jniLibs/arm64-v8a/lib*.so` (not committed). Versions are recorded in `app/native-sources.txt`.

## Build

CI: `.github/workflows/build.yml` fetches the helpers, builds debug and release APKs (arm64-v8a only)
and uploads them as the `andrax-ng-apks` artifact. Optional repository secrets sign the release with
your key: `ANDRAX_KEYSTORE_B64` (base64 of the keystore), `ANDRAX_KEYSTORE_PASSWORD`,
`ANDRAX_KEY_ALIAS`, `ANDRAX_KEY_PASSWORD`. Without them the release uses the debug key.

Locally on a PC: `bash scripts/fetch-native.sh && gradle :app:assembleDebug` (Gradle 8.7, JDK 17, SDK 34).

## Licenses

See `NOTICE.md`. The app is GPLv3 (`LICENSE`).
