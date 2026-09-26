package dev.brahmkshatriya.echo.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

class BilibiliApiTest {
    @Test fun guestCookiesDoNotReplaceHostCookieJar() {
        val api = BilibiliApi()
        val clientField = BilibiliApi::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        val client = clientField.get(api) as OkHttpClient
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)

        val request = Request.Builder().url("https://www.bilibili.com/").build()
        val response = Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .addHeader("Set-Cookie", "buvid3=guest; Domain=.bilibili.com; Path=/")
            .build()
        api.rememberCookies(response)
        assertEquals("buvid3=guest", api.cookieHeader("https://api.bilibili.com/".toHttpUrl()))
        assertEquals("", api.cookieHeader("https://example.com/".toHttpUrl()))
    }

    @Test fun documentedWbiSignature() {
        val raw = "7cd084941338484aae1ad9425b84077c" +
            "4932caff0ff746eab6f01bf08b70ac45"
        val mixin = BilibiliApi.mixin(raw)
        assertEquals("ea1db124af3c7062474693fa704f4ff8", mixin)
        assertEquals("%E4%BA%94%E4%B8%80%E5%9B%9B%20a", BilibiliApi.percentEncode("五一四 a"))
        val query = "bar=514&foo=114&wts=1702204169&zab=1919810"
        assertEquals("8f6f2b5b3d485fe1886cec6a0be8c5d4", BilibiliApi.md5(query + mixin))
    }
}
