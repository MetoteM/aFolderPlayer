package dev.alex.afponline
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class YandexClientTest {
    @Test fun anonymous200IsNotLogin() {
        val c=YandexClient("secret") { _, _ -> "{\"result\":{\"account\":{}}}" }
        try { c.checkSession(); fail() } catch(e:YandexClient.Problem) { assertFalse(e.reason.contains("secret")) }
        try { c.collection(); fail() } catch(_:YandexClient.Problem) { }
    }
    @Test fun searchDoesNotAuthorizePlayback() {
        val c=YandexClient("") { _, _ -> "{\"result\":{\"tracks\":{\"results\":[]}}}" }
        assertTrue(c.search("test").isEmpty())
        try { c.stream("1"); fail() } catch(_:YandexClient.Problem) { }
    }
    @Test fun previewNeverResolved() {
        var calls=0
        val c=YandexClient("secret") { url, _ ->
            calls++
            if(url.endsWith("/account/status")) "{\"result\":{\"account\":{\"uid\":42}}}"
            else "{\"result\":[{\"codec\":\"mp3\",\"preview\":true,\"downloadInfoUrl\":\"https://evil.example\"}]}"
        }
        c.checkSession()
        try { c.stream("1"); fail() } catch(_:YandexClient.Problem) { }
        assertEquals(2,calls)
    }
    @Test fun fullDescriptorReceivesNoToken() {
        val c=YandexClient("secret") { url, token ->
            when {
                url.endsWith("/account/status") -> "{\"result\":{\"account\":{\"uid\":42}}}"
                url.endsWith("/download-info") -> "{\"result\":[{\"codec\":\"mp3\",\"preview\":false,\"downloadInfoUrl\":\"https://storage.yandex.net/info\"}]}"
                else -> { assertNull(token); "<download-info><host>storage.yandex.net</host><path>/test</path><ts>123</ts><s>abc</s></download-info>" }
            }
        }
        c.checkSession()
        assertEquals("storage.yandex.net",c.stream("1").host)
    }
    @Test fun reportsMissingFieldsWithoutResponseData() {
        try { YandexClient.resolve("<download-info><host>private-account-data</host></download-info>"); fail() }
        catch(e:YandexClient.Problem) {
            assertTrue(e.reason.contains("поле path"))
            assertFalse(e.reason.contains("private-account-data"))
        }
    }
    @Test fun malformedXmlHasSafeError() {
        try { YandexClient.resolve("<download-info><host>private-token</download-info>"); fail() }
        catch(e:YandexClient.Problem) { assertEquals("Аудиодескриптор: некорректный XML", e.reason) }
    }
    @Test fun rejectsUnsafeHostsAndEntities() {
        for(url in listOf("http://storage.yandex.net/a","https://yandex.net.evil.example/a","https://127.0.0.1/a","https://user@storage.yandex.net/a")) {
            try { YandexClient.safeStream(url); fail(url) } catch(_:YandexClient.Problem) { }
        }
        try { YandexClient.resolve("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><x/>"); fail() } catch(_:YandexClient.Problem) { }
    }
}
