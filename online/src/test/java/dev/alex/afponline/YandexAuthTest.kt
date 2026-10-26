package dev.alex.afponline

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class YandexAuthTest {
    @Test fun consumesOnlyExpectedCallbackAndState() {
        assertEquals("local-secret",YandexAuth.callback("https://music.yandex.ru/#access_token=local-secret&state=random-state","random-state"))
        assertNull(YandexAuth.callback("https://evil.example/#access_token=secret&state=random-state","random-state"))
        assertNull(YandexAuth.callback("https://music.yandex.ru/","random-state"))
        try { YandexAuth.callback("https://music.yandex.ru/#access_token=secret&state=wrong","random-state");fail() }
        catch(_:IllegalArgumentException) { }
    }
    @Test fun rejectsDuplicateParametersAndOutsideNavigation() {
        try { YandexAuth.callback("https://music.yandex.ru/#access_token=a&access_token=b&state=s","s");fail() }
        catch(_:IllegalArgumentException) { }
        assertFalse(YandexAuth.navigationAllowed("https://yandex.ru.evil.example/"))
        assertFalse(YandexAuth.navigationAllowed("http://oauth.yandex.ru/"))
        assertFalse(YandexAuth.navigationAllowed("file:///etc/passwd"))
        assertTrue(YandexAuth.navigationAllowed("https://passport.yandex.ru/auth"))
    }
}
