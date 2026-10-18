package dev.alex.folderplayer

import android.content.Intent
import android.content.pm.*
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExternalExtensionsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun extension(signature: String = "1234", api: Int = 1, enabled: Boolean = true) {
        val pm = shadowOf(app.packageManager)
        fun install(name: String, cert: String) {
            pm.installPackage(PackageInfo().apply {
                packageName = name
                signatures = arrayOf(Signature(cert))
                applicationInfo = ApplicationInfo().apply { packageName = name; this.enabled = true }
            })
        }
        install(app.packageName, "1234")
        install("example.extension", signature)
        pm.addResolveInfoForIntent(Intent(ExternalExtensions.ACTION), ResolveInfo().apply {
            nonLocalizedLabel = "Example"
            activityInfo = ActivityInfo().apply {
                packageName = "example.extension"; name = "example.extension.Main"
                exported = true; this.enabled = enabled
                applicationInfo = app.packageManager.getApplicationInfo(packageName, 0)
                metaData = Bundle().apply { putInt(ExternalExtensions.API, api) }
            }
        })
    }
    @Test fun absentExtensionLeavesNoMenuEntry() { assertTrue(ExternalExtensions.installed(app).isEmpty()) }
    @Test fun matchingSignatureAndProtocolAppear() {
        extension()
        assertEquals(listOf("Example"), ExternalExtensions.installed(app).map { it.title })
    }
    @Test fun unrelatedSignatureIsRejected() { extension(signature = "5678"); assertTrue(ExternalExtensions.installed(app).isEmpty()) }
    @Test fun unsupportedProtocolIsRejected() { extension(api = 2); assertTrue(ExternalExtensions.installed(app).isEmpty()) }
    @Test fun disabledExtensionIsHidden() { extension(enabled = false); assertTrue(ExternalExtensions.installed(app).isEmpty()) }
}
