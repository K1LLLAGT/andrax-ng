package net.k1lllagt.andraxng.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
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

    fun install(
        profile: Profile,
        source: Source,
        cancel: AtomicBoolean,
        report: (phase: String, done: Long, total: Long, detail: String) -> Unit
    ) {
        paths.ensureDirs()
        NativeLibs.prepare(paths, log)

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

        report("Finalizing", 0, 0, "")
        val finalDir = paths.rootfs(profile)
        if (SafeFs.exists(finalDir.path)) SafeFs.deleteRootfs(finalDir, log, cancel)
        if (!partial.renameTo(finalDir)) throw IOException("Could not move the extracted rootfs into place")

        report("Applying post-install fixes", 0, 0, "")
        PostInstall.apply(ctx, profile, finalDir, settings, log)
        File(finalDir, Paths.MARKER).writeText(
            "profile=${profile.id}\ninstalled=${System.currentTimeMillis()}\nsha256=${result.sha256}\n"
        )

        if (!settings.keepArchive) archiveToDelete?.delete()
        if (settings.activeProfile.isEmpty() || Profiles.byId(settings.activeProfile) == null) {
            settings.activeProfile = profile.id
        }
        log.i("installed ${profile.id} into ${finalDir.path}")
    }

    /** Removes an installed rootfs (and leftovers). Refuses if mounts are still active inside it. */
    fun remove(profile: Profile, cancel: AtomicBoolean? = null) {
        SafeFs.deleteRootfs(paths.rootfs(profile), log, cancel)
        SafeFs.deleteTree(paths.partial(profile), cancel)
        File(paths.downloadsDir, profile.fileName).delete()
        if (settings.activeProfile == profile.id) settings.activeProfile = ""
        log.i("removed ${profile.id}")
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
