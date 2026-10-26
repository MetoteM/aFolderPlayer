package dev.alex.folderplayer

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * Reading APK signing identities with the scheme-aware API, plus SHA-256 of downloaded files.
 *
 * PackageManager.GET_SIGNATURES is deprecated since API 28 and does not reflect
 * signature-scheme v3/v4 (key rotation). GET_SIGNING_CERTIFICATES exposes the
 * certificates via PackageInfo.signingInfo; validateInfo()/validateComponent()
 * compare PackageInfo.signatures, so the certificates are surfaced there as well.
 * Callers keep their fail-closed behaviour: a missing signature array still rejects the APK.
 */
internal object ApkIntegrity {
    @Suppress("DEPRECATION")
    fun archiveInfo(pm: PackageManager, path: String): PackageInfo? {
        val info =
            if (android.os.Build.VERSION.SDK_INT >= 28)
                pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
            else pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        info ?: return null
        harmonize(info)
        return info
    }

    @Suppress("DEPRECATION")
    fun installedInfo(pm: PackageManager, packageName: String): PackageInfo? {
        val info =
            if (android.os.Build.VERSION.SDK_INT >= 28)
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            else pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        info ?: return null
        harmonize(info)
        return info
    }

    private fun harmonize(info: PackageInfo) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val certs = info.signingInfo?.apkContentsSigners
            if (!certs.isNullOrEmpty() && info.signatures == null) info.signatures = certs
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
