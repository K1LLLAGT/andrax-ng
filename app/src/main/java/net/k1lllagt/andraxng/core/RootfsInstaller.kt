package net.k1lllagt.andraxng.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RootfsInstaller(
    private val ctx: Context,
    private val paths: Paths,
    private val settings: Settings,
    private val log: Logger
) {
    sealed class Source {
        object Download : Source()
        /** [sha256] may be null or blank: the profile's known hash (or Kali's SHA256SUMS) is tried instead. */
        class Import(val uri: Uri, val sha256: String?) : Source()
    }

    private companion object {
        /** Profile ids with an install in flight (process-wide, so two installer instances cannot race). */
        val ACTIVE: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    }

    fun install(
        profile: Profile,
        source: Source,
        cancel: AtomicBoolean,
        report: (phase: String, done: Long, total: Long, detail: String) -> Unit
    ) {
        if (!ACTIVE.add(profile.id)) {
            throw IOException("An install of ${profile.title} is already running.")
        }
        try {
            doInstall(profile, source, cancel, report)
        } finally {
            ACTIVE.remove(profile.id)
        }
    }

    private fun doInstall(
        profile: Profile,
        source: Source,
        cancel: AtomicBoolean,
        report: (phase: String, done: Long, total: Long, detail: String) -> Unit
    ) {
        paths.ensureDirs()
        NativeLibs.prepare(paths, log)
        recoverInterruptedSwap(profile, cancel)

        val free = paths.files.usableSpace
        if (free < profile.requiredFreeBytes) {
            throw IOException(
                "Not enough free space: about ${Fmt.bytes(profile.requiredFreeBytes)} needed, " +
                    "${Fmt.bytes(free)} available."
            )
        }

        val partial = paths.partial(profile)
        if (partial.exists()) {
            report("Cleaning up a previous unfinished install", 0, 0, "")
            SafeFs.deleteTree(partial, cancel)
        }
        partial.mkdirs()

        var result: ExtractResult? = null
        var archiveToDelete: File? = null
        val extractProgress = { d: Long, t: Long, detail: String -> report("Extracting", d, t, detail) }

        when (source) {
            is Source.Download -> {
                val url = profile.downloadUrl ?: throw IOException("${profile.title} has no download URL. Use import.")
                report("Fetching checksum list", 0, 0, "")
                val expected = profile.fixedSha256
                    ?: profile.sumsUrl?.let { findHash(Downloader.fetchText(it), profile.fileName) }
                    ?: throw IOException("Could not find a checksum for ${profile.fileName}")
                log.i("expected sha256 for ${profile.fileName}: $expected")

                val archive = File(paths.downloadsDir, profile.fileName)
                var actual = fetchAndHash(url, archive, cancel, report)
                if (!actual.equals(expected, ignoreCase = true)) {
                    log.w("checksum mismatch on first attempt (got $actual); re-downloading from scratch")
                    archive.delete()
                    actual = fetchAndHash(url, archive, cancel, report)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        archive.delete()
                        throw IOException("Checksum mismatch. Expected $expected but got $actual.")
                    }
                }
                archiveToDelete = archive
                result = TarExtractor.extractFile(archive, partial, profile, cancel, log, extractProgress)
            }

            is Source.Import -> {
                val meta = queryMeta(source.uri)
                log.i("importing '${meta.first}' (${meta.second} bytes) from ${source.uri}")
                var expected = source.sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: profile.fixedSha256
                if (expected == null && profile.sumsUrl != null && meta.first == profile.fileName) {
                    try {
                        expected = findHash(Downloader.fetchText(profile.sumsUrl), meta.first)
                    } catch (e: IOException) {
                        log.w("could not fetch SHA256SUMS to verify the imported file: ${e.message}")
                    }
                }
                val ins = ctx.contentResolver.openInputStream(source.uri)
                    ?: throw IOException("Cannot open the selected file")
                result = ins.use { TarExtractor.extract(it, meta.second, partial, profile, cancel, log, extractProgress) }
                val got = result.sha256
                if (expected == null) {
                    log.w("imported archive was NOT verified. Its SHA-256 is $got")
                } else if (!got.equals(expected, ignoreCase = true)) {
                    throw IOException("Checksum mismatch. Expected $expected but got $got.")
                }
            }
        }

        val installedSha = result.sha256

        // ---- Swap: the live rootfs is only set aside, never deleted, until the new one is complete ----
        report("Finalizing", 0, 0, "")
        val finalDir = paths.rootfs(profile)
        val oldDir = oldDirOf(finalDir)
        var haveOld = false
        try {
            if (SafeFs.exists(finalDir.path)) {
                if (SafeFs.exists(oldDir.path)) SafeFs.deleteTree(oldDir, null)
                if (!finalDir.renameTo(oldDir)) {
                    throw IOException(
                        "Could not set the current ${profile.id} install aside " +
                            "(is a session still running?). Nothing was changed."
                    )
                }
                haveOld = true
            }
            if (!partial.renameTo(finalDir)) throw IOException("Could not move the extracted rootfs into place")

            report("Applying post-install fixes", 0, 0, "")
            PostInstall.apply(ctx, profile, finalDir, settings, log)
            File(finalDir, Paths.MARKER).writeText(
                "profile=${profile.id}\ninstalled=${System.currentTimeMillis()}\nsha256=$installedSha\n"
            )
        } catch (e: Throwable) {
            rollbackSwap(finalDir, oldDir, haveOld)
            throw e
        }

        if (haveOld) {
            report("Removing the previous install", 0, 0, "")
            try {
                SafeFs.deleteTree(oldDir, null)
            } catch (e: Exception) {
                // Harmless: recoverInterruptedSwap() finishes this on the next install.
                log.w("could not remove the previous install yet: ${e.message}")
            }
        }

        if (!settings.keepArchive) archiveToDelete?.delete()
        if (settings.activeProfile.isEmpty() || Profiles.byId(settings.activeProfile) == null) {
            settings.activeProfile = profile.id
        }
        log.i("installed ${profile.id} into ${finalDir.path}")
    }

    /** Removes an installed rootfs (and leftovers). Refuses if mounts are still active inside it. */
    fun remove(profile: Profile, cancel: AtomicBoolean? = null) {
        val finalDir = paths.rootfs(profile)
        SafeFs.deleteRootfs(finalDir, log, cancel)
        SafeFs.deleteTree(oldDirOf(finalDir), cancel)
        SafeFs.deleteTree(paths.partial(profile), cancel)
        File(paths.downloadsDir, profile.fileName).delete()
        if (settings.activeProfile == profile.id) settings.activeProfile = ""
        log.i("removed ${profile.id}")
    }

    private fun oldDirOf(finalDir: File) = File(finalDir.parentFile, finalDir.name + ".old")

    /** Puts the previous install back after a failed swap or failed post-install. */
    private fun rollbackSwap(finalDir: File, oldDir: File, haveOld: Boolean) {
        if (!haveOld) return
        try {
            if (SafeFs.exists(finalDir.path)) SafeFs.deleteTree(finalDir, null)
            if (oldDir.renameTo(finalDir)) {
                log.w("install failed; the previous ${finalDir.name} was restored")
            } else {
                log.w("install failed AND the previous ${finalDir.name} could not be restored; it is at ${oldDir.path}")
            }
        } catch (e: Exception) {
            log.w("rollback failed: ${e.message}; the previous install is at ${oldDir.path}")
        }
    }

    /**
     * Finishes or undoes a swap that was cut short (app killed, device rebooted).
     * Marker present on the new tree: the swap completed, only the cleanup was missed.
     * Marker absent: the new tree is incomplete, so the previous one goes back.
     */
    private fun recoverInterruptedSwap(profile: Profile, cancel: AtomicBoolean) {
        val finalDir = paths.rootfs(profile)
        val oldDir = oldDirOf(finalDir)
        if (!SafeFs.exists(oldDir.path)) return
        if (File(finalDir, Paths.MARKER).exists()) {
            log.i("removing leftover ${oldDir.name} from an earlier install")
            SafeFs.deleteTree(oldDir, cancel)
        } else {
            log.w("found an interrupted swap for ${profile.id}; restoring the previous install")
            if (SafeFs.exists(finalDir.path)) SafeFs.deleteTree(finalDir, cancel)
            if (!oldDir.renameTo(finalDir)) {
                throw IOException("Could not restore the previous ${profile.id} install from ${oldDir.path}")
            }
        }
    }

    private fun fetchAndHash(
        url: String,
        archive: File,
        cancel: AtomicBoolean,
        report: (String, Long, Long, String) -> Unit
    ): String {
        Downloader.download(url, archive, cancel, { d, t -> report("Downloading", d, t, "") }, log)
        val size = archive.length()
        return Sha256.of(archive, cancel) { d -> report("Verifying download", d, size, "") }
    }

    private fun findHash(sums: String, fileName: String): String? {
        for (line in sums.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1].trimStart('*') == fileName) return parts[0].lowercase()
        }
        return null
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var name = ""
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni) ?: ""
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
        } catch (e: Exception) {
            log.w("could not read file metadata: ${e.message}")
        }
        return Pair(name, size)
    }
}
