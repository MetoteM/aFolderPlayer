package dev.alex.folderplayer

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** Current signer sets must match exactly, including every signer of multi-signed APKs.
 * Key rotation needs a separate migration policy; an ancestor alone does not grant trust.
 */
internal object ApkIdentity {
    val flags: Int get() = if (Build.VERSION.SDK_INT >= 28)
        PackageManager.GET_SIGNING_CERTIFICATES else legacyFlags()

    @Suppress("DEPRECATION")
    private fun legacyFlags() = PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28)
        info.signingInfo?.apkContentsSigners else info.signatures

    fun matches(candidate: PackageInfo, installed: PackageInfo): Boolean {
        val actual = signers(candidate)?.toSet().orEmpty()
        val trusted = signers(installed)?.toSet().orEmpty()
        return actual.isNotEmpty() && actual == trusted
    }
}
