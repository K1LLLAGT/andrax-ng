package net.k1lllagt.andraxng.core

enum class Arch(val label: String) { ARM64("arm64"), ARMHF("armhf") }

enum class Kind { KALI, ANDRAX }

data class Profile(
    val id: String,
    val title: String,
    val summary: String,
    val kind: Kind,
    val arch: Arch,
    val fileName: String,
    /** null = import-only profile */
    val downloadUrl: String?,
    /** SHA256SUMS file the download is verified against (null = none) */
    val sumsUrl: String?,
    /** A hash known in advance (used for import verification) */
    val fixedSha256: String?,
    /** If the first path component of every entry matches, it is stripped (Kali wraps in kali-<arch>/) */
    val wrapperRegex: Regex?,
    /** Free-space guard. Minimal measured at about 780 MiB extracted; full and ANDRAX are estimates. */
    val requiredFreeBytes: Long,
    /** Appended to PATH inside the rootfs */
    val extraPath: String
)

object Profiles {
    const val KALI_BASE = "https://kali.download/nethunter-images/current/rootfs"
    private const val GIB = 1024L * 1024L * 1024L
    private val kaliWrapper = Regex("^kali-[a-z0-9]+$")

    private fun kali(size: String, title: String, summary: String, free: Long) = Profile(
        id = "kali-$size",
        title = title,
        summary = summary,
        kind = Kind.KALI,
        arch = Arch.ARM64,
        fileName = "kali-nethunter-rootfs-$size-arm64.tar.xz",
        downloadUrl = "$KALI_BASE/kali-nethunter-rootfs-$size-arm64.tar.xz",
        sumsUrl = "$KALI_BASE/SHA256SUMS",
        fixedSha256 = null,
        wrapperRegex = kaliWrapper,
        requiredFreeBytes = free,
        extraPath = ""
    )

    val kaliMinimal = kali(
        "minimal", "Kali NetHunter minimal (arm64)",
        "Small base system. Install tools afterwards with apt.", 2 * GIB
    )
    val kaliFull = kali(
        "full", "Kali NetHunter full (arm64)",
        "Large image with many tools preinstalled.", 12 * GIB
    )
    val kaliNano = kali(
        "nano", "Kali NetHunter nano (arm64)",
        "Smallest base system.", 2 * GIB
    )

    val andrax = Profile(
        id = "andrax-armhf",
        title = "ANDRAX r5 (armhf, 32-bit)",
        summary = "Your own archive, import only. 32-bit ARM, so it needs a device with a 32-bit ABI (Tab S7).",
        kind = Kind.ANDRAX,
        arch = Arch.ARMHF,
        fileName = "andrax.r5.v5-b5.tar.xz",
        downloadUrl = null,
        sumsUrl = null,
        fixedSha256 = "895e09e1254551822b07c9a213d38aad57283381d5690ba57c20f499945a9e3d",
        wrapperRegex = null,
        requiredFreeBytes = 13 * GIB,
        extraPath = "/opt/ANDRAX/bin"
    )

    val all = listOf(kaliMinimal, kaliFull, kaliNano, andrax)

    fun byId(id: String): Profile? = all.firstOrNull { it.id == id }

    /** Profiles this device can run. armhf needs a 32-bit ABI. */
    fun availableFor(has32BitAbi: Boolean): List<Profile> =
        all.filter { it.arch == Arch.ARM64 || has32BitAbi }
}
