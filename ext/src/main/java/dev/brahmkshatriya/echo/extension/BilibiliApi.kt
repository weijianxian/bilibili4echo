package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonElement?.array(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
internal fun JsonElement?.str(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonElement?.number(): Long = (this as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonElement?.integer(): Int = (this as? JsonPrimitive)?.intOrNull ?: 0

/** Requests only Bilibili hosts. Playback CDN URLs are passed to Echo, never fetched here. */
internal class BilibiliApi {
    // Echo's minified OkHttp expects its default NO_COOKIES jar to return EmptyList.
    // Supplying a custom CookieJar that returns an ArrayList crashes its dispatcher.
    // Keep guest cookies here and attach them as request headers instead.
    private val cookies = mutableMapOf<String, Cookie>()
    @Volatile private var loginCookies: String = ""

    internal fun setLoginCookies(header: String?) {
        loginCookies = normalizedCookies(header.orEmpty())
    }

    /** CookieManager supplies a Cookie header; reject malformed names and header separators. */
    internal fun normalizedCookies(header: String): String = header.split(';').mapNotNull { pair ->
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', "").trim()
        if (name.matches(Regex("[A-Za-z0-9_]+")) && value.isNotEmpty() &&
            value.none { it == '\r' || it == '\n' || it == ';' }) "$name=$value" else null
    }.joinToString("; ")

    internal fun rememberCookies(response: Response) {
        val received = Cookie.parseAll(response.request.url, response.headers)
        synchronized(cookies) {
            received.filter { it.name in setOf("buvid3", "buvid4", "b_nut", "_uuid") }
                .forEach { cookies["${it.domain}|${it.name}"] = it }
        }
    }

    internal fun cookieHeader(url: HttpUrl): String = synchronized(cookies) {
        if (url.host != "bilibili.com" && !url.host.endsWith(".bilibili.com"))
            return@synchronized ""
        val now = System.currentTimeMillis()
        cookies.entries.removeAll { it.value.expiresAt < now }
        val pairs = cookies.values.filter { it.matches(url) }
            .associate { it.name to it.value }.toMutableMap()
        loginCookies.split(';').map { it.trim() }.filter { it.contains('=') }.forEach {
            pairs[it.substringBefore('=')] = it.substringAfter('=')
        }
        pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private var mixinKey: String? = null
    private var keyFetchedAt = 0L
    private var seeded = false

    private suspend fun get(url: String, referer: String = "https://www.bilibili.com/",
                            authOverride: String? = null): JsonObject {
        require(url.startsWith("https://api.bilibili.com/") || url.startsWith("https://www.bilibili.com/audio/"))
        val builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", "https://www.bilibili.com")
        (authOverride ?: cookieHeader(builder.build().url)).takeIf { it.isNotEmpty() }
            ?.let { builder.header("Cookie", it) }
        val request = builder.build()
        return client.newCall(request).await().use { response ->
            rememberCookies(response)
            if (!response.isSuccessful) error("Bilibili HTTP ${response.code}")
            val body = response.body?.string() ?: error("Bilibili returned an empty response")
            json.parseToJsonElement(body).obj()
        }
    }

    private suspend fun data(url: String): JsonObject {
        val response = get(url)
        val code = response["code"].integer()
        if (code != 0) error("Bilibili API $code: ${response["message"].str().ifBlank { response["msg"].str() }}")
        val value = response["data"].obj()
        if (value.isEmpty() || value["v_voucher"] != null) error("Bilibili requires additional verification")
        return value
    }

    suspend fun currentAccount(cookie: String): JsonObject {
        val session = normalizedCookies(cookie)
        require(session.split(';').any { it.trim().startsWith("SESSDATA=") }) {
            "登录尚未完成，请在网页中完成哔哩哔哩登录"
        }
        // Echo has not selected the account yet, so validate this candidate cookie alone.
        val response = get("https://api.bilibili.com/x/web-interface/nav", authOverride = session)
        require(response["code"].integer() == 0 && response["data"].obj()["isLogin"].str() == "true") {
            "哔哩哔哩会话无效，请重新登录"
        }
        return response["data"].obj()
    }

    private suspend fun seedCookies() {
        if (seeded) return
        // Search requires buvid3. A normal visit lets the site issue its own guest cookie.
        val request = Request.Builder().url("https://www.bilibili.com/")
            .header("User-Agent", USER_AGENT).build()
        client.newCall(request).await().use { rememberCookies(it) }
        seeded = true
    }

    private suspend fun key(refresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!refresh && now - keyFetchedAt < 60 * 60 * 1000) mixinKey?.let { return it }
        val response = get("https://api.bilibili.com/x/web-interface/nav")
        // nav returns -101 for a guest, but still supplies wbi_img.
        val wbi = response["data"].obj()["wbi_img"].obj()
        fun imageKey(field: String) = wbi[field].str().substringAfterLast('/').substringBefore('.')
        val raw = imageKey("img_url") + imageKey("sub_url")
        require(raw.length >= 64) { "Bilibili did not provide WBI keys" }
        return mixin(raw).also { mixinKey = it; keyFetchedAt = now }
    }

    private suspend fun signed(path: String, params: Map<String, String>): JsonObject {
        seedCookies()
        suspend fun request(refresh: Boolean): JsonObject {
            val wts = (System.currentTimeMillis() / 1000).toString()
            val signedParams = (params + ("wts" to wts)).toSortedMap()
            val query = signedParams.entries.joinToString("&") { (k, v) ->
                "${percentEncode(k)}=${percentEncode(v.filterNot { it in "!'()*" })}"
            }
            val hash = md5(query + key(refresh))
            return get("https://api.bilibili.com$path?$query&w_rid=$hash")
        }
        var response = request(false)
        if (response["code"].integer() == -403 || response["code"].integer() == -412 ||
            response["data"].obj()["v_voucher"] != null
        ) response = request(true)
        val code = response["code"].integer()
        if (code != 0) error("Bilibili API $code: ${response["message"].str()}")
        val value = response["data"].obj()
        if (value.isEmpty() || value["v_voucher"] != null) error("Bilibili rejected the request; try again later")
        return value
    }

    suspend fun searchVideos(keyword: String, page: Int): JsonObject = signed(
        "/x/web-interface/wbi/search/type",
        mapOf("search_type" to "video", "keyword" to keyword, "page" to page.toString())
    )

    suspend fun userInfo(mid: String): JsonObject = signed(
        "/x/space/wbi/acc/info", mapOf("mid" to mid)
    )

    suspend fun region(rid: Int, page: Int): JsonObject = data(
        "https://api.bilibili.com/x/web-interface/dynamic/region?rid=$rid&pn=$page&ps=30"
    )

    suspend fun userVideos(mid: String, page: Int): JsonObject = signed(
        "/x/space/wbi/arc/search", mapOf("mid" to mid, "pn" to page.toString(),
            "ps" to "30", "order" to "pubdate")
    )

    suspend fun userCollections(mid: String, page: Int): JsonObject = data(
        "https://api.bilibili.com/x/polymer/web-space/seasons_series_list" +
            "?mid=$mid&page_num=$page&page_size=20"
    )["items_lists"].obj()

    suspend fun collection(mid: String, seasonId: String, page: Int): JsonObject = data(
        "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list" +
            "?mid=$mid&season_id=$seasonId&page_num=$page&page_size=30"
    )

    suspend fun series(mid: String, seriesId: String, page: Int): JsonObject = data(
        "https://api.bilibili.com/x/series/archives" +
            "?mid=$mid&series_id=$seriesId&only_normal=true&sort=desc&pn=$page&ps=30"
    )

    suspend fun seriesInfo(seriesId: String): JsonObject = data(
        "https://api.bilibili.com/x/series/series?series_id=$seriesId"
    )["meta"].obj()

    suspend fun view(bvid: String? = null, aid: String? = null): JsonObject {
        val query = if (bvid != null) "bvid=$bvid" else "aid=${aid ?: error("Missing video ID")}" 
        return data("https://api.bilibili.com/x/web-interface/view?$query")
    }

    suspend fun videoAudio(bvid: String, cid: String): JsonArray = signed(
        "/x/player/wbi/playurl", mapOf("bvid" to bvid, "cid" to cid, "fnval" to "16")
    )["dash"].obj()["audio"].array()

    suspend fun audioInfo(sid: String): JsonObject = data(
        "https://www.bilibili.com/audio/music-service-c/web/song/info?sid=$sid"
    )

    suspend fun audioUrl(sid: String): JsonObject = data(
        "https://www.bilibili.com/audio/music-service-c/web/url?sid=$sid"
    )

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private val MIXIN = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,
            27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,
            40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)

        internal fun mixin(raw: String): String = MIXIN.take(32).map { raw[it] }.joinToString("")
        internal fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        internal fun percentEncode(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xff
                if (c in 65..90 || c in 97..122 || c in 48..57 || c in listOf(45, 46, 95, 126))
                    append(c.toChar())
                else append("%%%02X".format(c))
            }
        }
    }
}
