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
import okhttp3.FormBody
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

    private fun csrf(): String {
        require(loginCookies.split(';').any { it.trim().startsWith("SESSDATA=") }) {
            "请先登录哔哩哔哩"
        }
        return loginCookies.split(';').firstOrNull { it.trim().startsWith("bili_jct=") }
            ?.substringAfter('=')?.trim()?.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }
            ?: error("缺少登录验证信息 bili_jct，请重新登录")
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
        val builder = Request.Builder().url(url)
        val host = builder.build().url.host
        require(builder.build().url.isHttps && (host == "api.bilibili.com" ||
            host == "s.search.bilibili.com" ||
            (host == "www.bilibili.com" && builder.build().url.encodedPath.startsWith("/audio/")) ||
            host == "hdslb.com" || host.endsWith(".hdslb.com")))
        builder.header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", "https://www.bilibili.com")
        (authOverride ?: if (host == "hdslb.com" || host.endsWith(".hdslb.com")) ""
            else cookieHeader(builder.build().url)).takeIf { it.isNotEmpty() }
            ?.let { builder.header("Cookie", it) }
        val request = builder.build()
        return client.newCall(request).await().use { response ->
            rememberCookies(response)
            if (!response.isSuccessful) error("Bilibili HTTP ${response.code}")
            val body = response.body?.string() ?: error("Bilibili returned an empty response")
            json.parseToJsonElement(body).obj()
        }
    }

    private suspend fun post(path: String, params: Map<String, String>): JsonObject {
        require(path.startsWith('/') && !path.startsWith("//"))
        val url = "https://api.bilibili.com$path"
        val body = FormBody.Builder().apply {
            (params + ("csrf" to csrf())).forEach { (name, value) -> add(name, value) }
        }.build()
        val builder = Request.Builder().url(url).post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
        cookieHeader(builder.build().url).takeIf { it.isNotEmpty() }
            ?.let { builder.header("Cookie", it) }
        return client.newCall(builder.build()).await().use { response ->
            rememberCookies(response)
            if (!response.isSuccessful) error("Bilibili HTTP ${response.code}")
            val payload = json.parseToJsonElement(response.body?.string()
                ?: error("Bilibili returned an empty response")).obj()
            val code = payload["code"].integer()
            if (code != 0) error("Bilibili API $code: ${payload["message"].str()}")
            payload["data"].obj()
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

    suspend fun searchVideos(keyword: String, page: Int, tid: Int? = null): JsonObject = signed(
        "/x/web-interface/wbi/search/type",
        mapOf("search_type" to "video", "keyword" to keyword, "page" to page.toString()) +
            (tid?.let { mapOf("tids" to it.toString()) } ?: emptyMap())
    )

    suspend fun popular(page: Int): JsonObject = data(
        "https://api.bilibili.com/x/web-interface/popular?pn=$page&ps=20"
    )

    suspend fun userInfo(mid: String): JsonObject = signed(
        "/x/space/wbi/acc/info", mapOf("mid" to mid)
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

    suspend fun audioLyrics(sid: String): String {
        val root = get("https://www.bilibili.com/audio/music-service-c/web/song/lyric?sid=$sid")
        val code = root["code"].integer()
        if (code != 0) error("Bilibili API $code: ${root["msg"].str()}")
        return root["data"].str()
    }

    suspend fun audioMenu(sid: String): JsonObject = data(
        "https://www.bilibili.com/audio/music-service-c/web/menu/info?sid=$sid"
    )

    suspend fun audioMenuSongs(sid: String, page: Int): JsonObject = data(
        "https://www.bilibili.com/audio/music-service-c/web/song/of-menu?sid=$sid&pn=$page&ps=20"
    )

    suspend fun audioCollections(page: Int): JsonObject = data(
        "https://www.bilibili.com/audio/music-service-c/web/collections/list?pn=$page&ps=20"
    )

    suspend fun watchLater(): JsonObject = data(
        "https://api.bilibili.com/x/v2/history/toview"
    )

    suspend fun history(max: String = "0", viewAt: String = "0"): JsonObject = data(
        "https://api.bilibili.com/x/web-interface/history/cursor?type=archive&ps=20" +
            "&max=$max&business=archive&view_at=$viewAt"
    )

    suspend fun favoriteFolders(mid: String, rid: String? = null): JsonArray = data(
        "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid" +
            (rid?.let { "&type=2&rid=$it" } ?: "")
    )["list"].array()

    suspend fun favoriteFolder(mediaId: String): JsonObject = data(
        "https://api.bilibili.com/x/v3/fav/folder/info?media_id=$mediaId"
    )

    suspend fun favoriteItems(mediaId: String, page: Int): JsonObject = data(
        "https://api.bilibili.com/x/v3/fav/resource/list?media_id=$mediaId&pn=$page&ps=20"
    )

    suspend fun createdFolder(title: String, description: String?): JsonObject = post(
        "/x/v3/fav/folder/add", mapOf("title" to title, "intro" to description.orEmpty(), "privacy" to "0")
    )

    suspend fun editFolder(mediaId: String, title: String, description: String?, private: Boolean) {
        post("/x/v3/fav/folder/edit", mapOf("media_id" to mediaId, "title" to title,
            "intro" to description.orEmpty(), "privacy" to if (private) "1" else "0"))
    }

    suspend fun deleteFolder(mediaId: String) {
        post("/x/v3/fav/folder/del", mapOf("media_ids" to mediaId))
    }

    suspend fun favoriteVideo(aid: String, folderId: String, add: Boolean) {
        post("/x/v3/fav/resource/deal", mapOf("rid" to aid, "type" to "2",
            "add_media_ids" to if (add) folderId else "",
            "del_media_ids" to if (add) "" else folderId))
    }

    suspend fun removeFavorites(folderId: String, resources: List<String>) {
        if (resources.isNotEmpty()) post("/x/v3/fav/resource/batch-del", mapOf(
            "media_id" to folderId, "resources" to resources.joinToString(",")
        ))
    }

    suspend fun isVideoFavorite(bvid: String): Boolean = data(
        "https://api.bilibili.com/x/v2/fav/video/favoured?aid=$bvid"
    )["favoured"].str() == "true"

    suspend fun likeVideo(bvid: String, shouldLike: Boolean) {
        post("/x/web-interface/archive/like", mapOf("bvid" to bvid,
            "like" to if (shouldLike) "1" else "2"))
    }

    suspend fun isVideoLiked(bvid: String): Boolean {
        val root = get("https://api.bilibili.com/x/web-interface/archive/has/like?bvid=$bvid")
        val code = root["code"].integer()
        if (code != 0) error("Bilibili API $code: ${root["message"].str()}")
        return root["data"].integer() == 1
    }

    suspend fun relation(mid: String): JsonObject = data(
        "https://api.bilibili.com/x/relation?fid=$mid"
    )

    suspend fun followerCount(mid: String): Long = data(
        "https://api.bilibili.com/x/relation/stat?vmid=$mid"
    )["follower"].number()

    suspend fun follow(mid: String, shouldFollow: Boolean) {
        post("/x/relation/modify", mapOf("fid" to mid,
            "act" to if (shouldFollow) "1" else "2", "re_src" to "11"))
    }

    suspend fun followings(mid: String, page: Int): JsonObject = data(
        "https://api.bilibili.com/x/relation/followings?vmid=$mid&pn=$page&ps=50"
    )

    suspend fun related(bvid: String): JsonArray {
        val root = get("https://api.bilibili.com/x/web-interface/archive/related?bvid=$bvid")
        val code = root["code"].integer()
        if (code != 0) error("Bilibili API $code: ${root["message"].str()}")
        return root["data"].array()
    }

    suspend fun playerInfo(bvid: String, cid: String): JsonObject = signed(
        "/x/player/wbi/v2", mapOf("bvid" to bvid, "cid" to cid)
    )

    suspend fun subtitle(url: String): JsonArray {
        val normalized = if (url.startsWith("//")) "https:$url" else url
        return get(normalized, authOverride = "")["body"].array()
    }

    suspend fun suggestions(query: String): JsonArray {
        val root = get("https://s.search.bilibili.com/main/suggest?term=${percentEncode(query)}")
        return root["result"].obj()["tag"].array()
    }

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
