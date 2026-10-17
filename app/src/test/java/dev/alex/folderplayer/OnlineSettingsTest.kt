package dev.alex.folderplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OnlineSettingsTest {
    @Test fun wifiOnlyRejectsCellularAndChoosesSeparateQuality() {
        val context=RuntimeEnvironment.getApplication()
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val caps=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork,caps)
        val prefs=context.getSharedPreferences("online",Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("wifiOnly",true).putString("wifiQuality","high").putString("mobileQuality","economy").commit()
        val settings=OnlineSettings(context)
        settings.requireNetwork();assertEquals("high",settings.quality())
        shadowOf(caps).removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork,caps)
        assertEquals("economy",settings.quality())
        try { settings.requireNetwork();fail("Mobile must be blocked") } catch(_:OnlinePlayback.Problem) { }
        prefs.edit().putBoolean("wifiOnly",false).commit();settings.requireNetwork()
    }
}
