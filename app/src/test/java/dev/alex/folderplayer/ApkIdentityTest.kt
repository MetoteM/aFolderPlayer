package dev.alex.folderplayer

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class ApkIdentityTest {
    private fun info(vararg keys: Int) = PackageInfo().apply {
        signingInfo = SigningInfo().also { value ->
            shadowOf(value).setSignatures(keys.map { Signature(byteArrayOf(it.toByte())) }.toTypedArray())
        }
    }
    @Test fun modernApiRequiresTheWholeCurrentSignerSet() {
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES, ApkIdentity.flags)
        assertTrue(ApkIdentity.matches(info(1,2),info(2,1)))
        assertFalse(ApkIdentity.matches(info(1),info(1,2)))
        assertFalse(ApkIdentity.matches(info(1,3),info(1,2)))
        assertFalse(ApkIdentity.matches(info(),info()))
        assertFalse(ApkIdentity.matches(PackageInfo(),info(1)))
    }
    @Test @Config(sdk=[26]) @Suppress("DEPRECATION") fun android26UsesLegacySigners() {
        assertEquals(PackageManager.GET_SIGNATURES, ApkIdentity.flags)
        val a=PackageInfo().apply { signatures=arrayOf(Signature(byteArrayOf(1))) }
        val b=PackageInfo().apply { signatures=arrayOf(Signature(byteArrayOf(1))) }
        assertTrue(ApkIdentity.matches(a,b))
        b.signatures=arrayOf(Signature(byteArrayOf(2)))
        assertFalse(ApkIdentity.matches(a,b))
    }
}
