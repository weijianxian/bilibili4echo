package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings

/** Echo music extension: Bilibili video soundtracks and legacy au audio entries. */
class BilibiliExtension : ExtensionClient, HomeFeedClient, SearchFeedClient, TrackClient,
    LoginClient.WebView, ArtistClient, PlaylistClient {
    private val api = BilibiliApi()
    private var activeUser: User? = null

    override val webViewRequest = object : WebViewRequest.Cookie<List<User>> {
        override val initialUrl = "https://passport.bilibili.com/login".toGetRequest()
        override val stopUrlRegex = Regex("^https://www\\.bilibili\\.com/(?:\\?.*)?$")

        override suspend fun onStop(url: NetworkRequest, cookie: String): List<User> {
            val account = api.currentAccount(cookie)
            return listOf(User(
                id = account["mid"].str(), name = account["uname"].str(),
                cover = image(account["face"].str()),
                extras = mapOf("cookie" to api.normalizedCookies(cookie))
            ))
        }
    }

    override fun setLoginUser(user: User?) {
        activeUser = user
        api.setLoginCookies(user?.extras?.get("cookie"))
    }

    override suspend fun getCurrentUser(): User? = activeUser?.copy(extras = emptyMap())

    override fun setSettings(settings: Settings) = Unit
    override suspend fun getSettingItems(): List<Setting> = emptyList()

    override suspend fun loadHomeFeed(): Feed<Shelf> = PagedData.Continuous<Shelf> { cursor ->
        val page = (cursor?.toIntOrNull() ?: 1).coerceAtLeast(1)
        homePage(page, api.popular(page))
    }.toFeed()

    internal fun homePage(page: Int, result: kotlinx.serialization.json.JsonObject): Page<Shelf> {
        val items = result["list"].array().mapNotNull { row ->
            val video = row.obj()
            val owner = video["owner"].obj()
            val artist = Artist(owner["mid"].str(), owner["name"].str(),
                image(owner["face"].str()), isFollowable = false)
            uploadTrack(video, artist)?.let { Shelf.Item(it) }
        }
        return Page<Shelf>((if (page == 1) homeHeader() else emptyList()) + items,
            if (items.isNotEmpty() && result["no_more"].str() != "true")
            (page + 1).toString() else null)
    }

    private fun homeHeader(): List<Shelf> = listOf<Shelf>(Shelf.Lists.Categories(
        id = "home-regions", title = "分区", list = listOf(
            Shelf.Category("region:3", "音乐", search("音乐", 3)),
            Shelf.Category("region:28", "原创音乐", search("原创音乐", 28)),
            Shelf.Category("region:31", "翻唱", search("翻唱", 31)),
            Shelf.Category("region:59", "演奏", search("演奏", 59)),
            Shelf.Category("region:30", "VOCALOID", search("VOCALOID", 30)),
            Shelf.Category("region:267", "电台", search("电台", 267)),
            Shelf.Category("region:36", "知识", search("知识", 36)),
            Shelf.Category("region:188", "科技", search("科技", 188))
        )
    )) + listOfNotNull(
        activeUser?.let { Shelf.Item(Artist(it.id, it.name, it.cover, subtitle = "我的 B 站主页")) }
    )

    private fun discover(): Feed<Shelf> = homeHeader().toFeed()

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val q = query.trim()
        if (q.isEmpty()) return discover()
        val collectionUrl = Regex("space\\.bilibili\\.com/([0-9]+)/.*[?&]sid=([0-9]+)")
            .find(q)
        if (collectionUrl != null) {
            val (mid, sid) = collectionUrl.destructured
            return listOf<Shelf>(Shelf.Item(loadPlaylist(Playlist(
                "season:$mid:$sid", "视频合集", isEditable = false
            )))).toFeed()
        }
        Regex("(?:space\\.bilibili\\.com/|^mid:)([0-9]+)").find(q)?.groupValues?.get(1)
            ?.let { return listOf<Shelf>(Shelf.Item(loadArtist(Artist(it, "UP 主")))).toFeed() }
        val au = Regex("(?:^|[/\\s])(?:au)([0-9]+)(?:$|[?/#\\s])", RegexOption.IGNORE_CASE)
            .find(q)?.groupValues?.get(1)
        if (au != null) return listOf<Shelf>(Shelf.Item(audioTrack(api.audioInfo(au)))).toFeed()
        val bv = Regex("BV[0-9A-Za-z]{10}", RegexOption.IGNORE_CASE).find(q)?.value
            ?.replaceRange(0, 2, "BV")
        val av = if (bv == null) Regex("(?:^|[/\\s])av([0-9]+)(?:$|[?/#\\s])", RegexOption.IGNORE_CASE)
            .find(q)?.groupValues?.get(1) else null
        if (bv != null || av != null) {
            val detail = api.view(bv, av)
            val bvid = detail["bvid"].str()
            val page = Regex("[?&]p=([0-9]+)").find(q)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val pages = detail["pages"].array()
            val selected = pages.getOrNull(page - 1)?.obj() ?: pages.firstOrNull()?.obj()
            return listOfNotNull<Shelf>(
                Shelf.Item(videoTrack(detail, selected, bvid)),
                collectionFromVideo(detail)?.let { Shelf.Item(it) }
            ).toFeed()
        }
        return search(q)
    }

    private fun search(keyword: String, tid: Int? = null): Feed<Shelf> = PagedData.Continuous<Shelf> { cursor ->
        val page = (cursor?.toIntOrNull() ?: 1).coerceIn(1, 50)
        val result = api.searchVideos(keyword, page, tid)
        val tracks = result["result"].array().mapNotNull { item ->
            val row = item.obj()
            val bvid = row["bvid"].str()
            if (bvid.isBlank()) null else Shelf.Item(Track(
                id = "v:$bvid", title = cleanTitle(row["title"].str()),
                type = Track.Type.Song,
                cover = image(row["pic"].str()),
                artists = listOf(Artist(row["mid"].str(), row["author"].str())),
                duration = parseDuration(row["duration"].str()),
                description = row["description"].str(),
                subtitle = "B站视频音轨 · ${row["author"].str()}",
                isSaveable = false, isLikeable = false, isHideable = false,
                isRadioSupported = false
            ))
        }
        val totalPages = result["numPages"].integer().coerceAtMost(50)
        Page(tracks, if (tracks.isNotEmpty() && page < totalPages) (page + 1).toString() else null)
    }.toFeed()

    override suspend fun loadArtist(artist: Artist): Artist {
        val mid = validId(artist.id)
        val profile = api.userInfo(mid)
        return artist.copy(
            name = profile["name"].str().ifBlank { artist.name },
            cover = image(profile["face"].str()) ?: artist.cover,
            bio = profile["sign"].str(),
            subtitle = "B 站 UP 主 · UID $mid",
            isFollowable = false, isSaveable = false
        )
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val mid = validId(artist.id)
        return PagedData.Continuous<Shelf> { cursor ->
            val page = (cursor?.toIntOrNull() ?: 1).coerceAtLeast(1)
            // The only upload shelf is on page one. Further homepage pages contain collections.
            val uploads = if (page == 1) api.userVideos(mid, 1) else null
            val collections = api.userCollections(mid, page)
            val shelves = artistShelves(artist, uploads, collections)
            val total = collections["page"].obj()["total"].number()
            val collectionCount = collections["seasons_list"].array().size +
                collections["series_list"].array().size
            Page(shelves, if (collectionCount > 0 && page * 20 < total)
                (page + 1).toString() else null)
        }.toFeed()
    }

    internal fun artistShelves(artist: Artist, uploads: kotlinx.serialization.json.JsonObject?,
                               collections: kotlinx.serialization.json.JsonObject): List<Shelf> {
        val shelves = mutableListOf<Shelf>()
        if (uploads != null) {
            val videos = uploads["list"].obj()["vlist"].array()
                .mapNotNull { uploadTrack(it.obj(), artist) }
            if (videos.isNotEmpty()) shelves += Shelf.Lists.Tracks(
                id = "uploads:${artist.id}", title = "全部投稿", list = videos,
                more = uploadFeed(artist.id, artist)
            )
        }
        listOf("seasons_list" to "season", "series_list" to "series").forEach { (key, type) ->
            collections[key].array().forEach collection@{ item ->
                val row = item.obj()
                val playlist = playlistFromMeta(row["meta"].obj(), type) ?: return@collection
                val previews = row["archives"].array()
                    .mapNotNull { uploadTrack(it.obj(), artist) }
                shelves += if (previews.isEmpty()) Shelf.Item(playlist) else Shelf.Lists.Tracks(
                    id = playlist.id, title = playlist.title, subtitle = playlist.subtitle,
                    list = previews, more = collectionTrackFeed(playlist)
                )
            }
        }
        return shelves
    }

    private fun collectionTrackFeed(playlist: Playlist): Feed<Shelf> = Feed(emptyList()) {
        val source = loadTracks(playlist).getPagedData(null).pagedData
        val items: PagedData<Shelf> = source.map { result ->
            result.getOrThrow().map { track -> Shelf.Item(track) }
        }
        Feed.Data(items)
    }

    private fun uploadFeed(mid: String, artist: Artist): Feed<Shelf> =
        PagedData.Continuous<Shelf> { cursor ->
            val page = (cursor?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val data = api.userVideos(mid, page)
            val items = data["list"].obj()["vlist"].array().mapNotNull {
                uploadTrack(it.obj(), artist)?.let { track -> Shelf.Item(track) }
            }
            val count = data["page"].obj()["count"].number()
            Page<Shelf>(items, if (items.isNotEmpty() && page * 30 < count) (page + 1).toString() else null)
        }.toFeed()

    private fun playlistFromMeta(meta: kotlinx.serialization.json.JsonObject, type: String): Playlist? {
        val mid = meta["mid"].str().takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return null
        val id = meta[if (type == "season") "season_id" else "series_id"].str()
            .takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return null
        return Playlist(
            id = "$type:$mid:$id", title = meta["name"].str().ifBlank { "视频合集" },
            isEditable = false, isPrivate = false, cover = image(meta["cover"].str()),
            description = meta["description"].str(), trackCount = meta["total"].number(),
            subtitle = if (type == "season") "B 站视频合集" else "B 站视频列表",
            isSaveable = false, isRadioSupported = false
        )
    }

    private fun collectionFromVideo(detail: kotlinx.serialization.json.JsonObject): Playlist? {
        val season = detail["ugc_season"].obj()
        val mid = season["mid"].str().ifBlank { detail["owner"].obj()["mid"].str() }
        val id = season["id"].str()
        if (!mid.matches(Regex("[0-9]+")) || !id.matches(Regex("[0-9]+"))) return null
        return Playlist("season:$mid:$id", season["title"].str(), isEditable = false,
            isPrivate = false, cover = image(season["cover"].str()),
            description = season["intro"].str(), isSaveable = false)
    }

    private fun uploadTrack(row: kotlinx.serialization.json.JsonObject, artist: Artist): Track? {
        val bvid = row["bvid"].str().takeIf { it.matches(Regex("BV[0-9A-Za-z]{10}")) }
            ?: return null
        return Track("v:$bvid", cleanTitle(row["title"].str()), Track.Type.Song,
            cover = image(row["pic"].str()), artists = listOf(artist),
            duration = row["length"].str().let(::parseDuration)
                ?: row["duration"].number().takeIf { it > 0 }?.times(1000),
            description = row["description"].str(), subtitle = "B 站视频音轨",
            isSaveable = false, isLikeable = false, isHideable = false,
            isRadioSupported = false)
    }

    private fun validId(value: String): String {
        require(value.matches(Regex("[0-9]+"))) { "Invalid Bilibili ID" }
        return value
    }

    private fun playlistParts(playlist: Playlist): Triple<String, String, String> {
        val parts = playlist.id.split(':')
        require(parts.size == 3 && parts[0] in listOf("season", "series")) {
            "Unsupported Bilibili collection"
        }
        return Triple(parts[0], validId(parts[1]), validId(parts[2]))
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val (type, mid, id) = playlistParts(playlist)
        val meta = if (type == "season") api.collection(mid, id, 1)["meta"].obj()
                   else api.seriesInfo(id)
        return playlistFromMeta(meta, type) ?: playlist
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val (type, mid, id) = playlistParts(playlist)
        val artist = playlist.authors.firstOrNull() ?: Artist(mid, "UP 主", isFollowable = false)
        return PagedData.Continuous<Track> { cursor ->
            val page = (cursor?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val data = if (type == "season") api.collection(mid, id, page)
                       else api.series(mid, id, page)
            val items = data["archives"].array().mapNotNull { uploadTrack(it.obj(), artist) }
            val total = data["page"].obj()["total"].number()
            Page(items, if (items.isNotEmpty() && page * 30 < total) (page + 1).toString() else null)
        }.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return when {
            track.id.startsWith("a:") -> {
                val sid = track.id.removePrefix("a:")
                audioTrack(api.audioInfo(sid)).copy(
                    streamables = listOf(Streamable.server("a:$sid", 192, "B站音频"))
                )
            }
            track.id.startsWith("v:") -> {
                val parts = track.id.split(':')
                val bvid = parts.getOrNull(1) ?: error("Invalid video ID")
                val detail = api.view(bvid)
                val cid = parts.getOrNull(2) ?: track.extras["cid"]
                    ?: detail["pages"].array().firstOrNull()?.obj()?.get("cid").str()
                        .ifBlank { detail["cid"].str() }
                if (cid.isBlank()) error("This video has no playable part")
                val page = detail["pages"].array().firstOrNull { it.obj()["cid"].str() == cid }?.obj()
                videoTrack(detail, page, bvid).copy(
                    id = track.id, streamables = listOf(Streamable.server("v:$bvid:$cid", 192, "音轨"))
                )
            }
            else -> error("Unsupported Bilibili track: ${track.id}")
        }
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val parts = streamable.id.split(':')
        val headers = mapOf(
            "Referer" to "https://www.bilibili.com/",
            "Origin" to "https://www.bilibili.com",
            "User-Agent" to BilibiliApi.USER_AGENT
        )
        val sources = when (parts.firstOrNull()) {
            "v" -> {
                val bvid = parts.getOrNull(1) ?: error("Missing BV number")
                val cid = parts.getOrNull(2) ?: error("Missing CID")
                api.videoAudio(bvid, cid).mapNotNull { item ->
                    val row = item.obj()
                    val url = row["baseUrl"].str().ifBlank { row["base_url"].str() }
                    if (!url.startsWith("https://")) null else Streamable.Source.Http(
                        request = NetworkRequest(url, headers),
                        quality = row["id"].integer(),
                        title = "${row["id"].str()} · ${row["codecs"].str()}"
                    )
                }
            }
            "a" -> {
                val sid = parts.getOrNull(1) ?: error("Missing au number")
                val info = api.audioUrl(sid)
                val url = info["cdns"].array().firstOrNull().str()
                if (!url.startsWith("https://")) emptyList() else listOf(
                    Streamable.Source.Http(NetworkRequest(url, headers), title = "B站音频")
                )
            }
            else -> error("Unsupported stream: ${streamable.id}")
        }
        if (sources.isEmpty()) error("Bilibili supplied no audio stream for this item")
        // URLs expire. Echo calls this method again when resolving a new playback session.
        return Streamable.Media.Server(sources, merged = false)
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        if (!track.id.startsWith("v:")) return null
        val bvid = track.id.split(':').getOrNull(1) ?: return null
        val detail = api.view(bvid)
        val pages = detail["pages"].array()
        val shelves = mutableListOf<Shelf>()
        collectionFromVideo(detail)?.let { shelves += Shelf.Item(it) }
        if (pages.size > 1) shelves += Shelf.Lists.Tracks(
            id = "parts:$bvid", title = "分 P", list = pages.map { videoTrack(detail, it.obj(), bvid) }
        )
        return shelves.takeIf { it.isNotEmpty() }?.toFeed()
    }

    private fun videoTrack(detail: kotlinx.serialization.json.JsonObject,
                           page: kotlinx.serialization.json.JsonObject?, bvid: String): Track {
        val owner = detail["owner"].obj()
        val cid = page?.get("cid").str().ifBlank { detail["cid"].str() }
        val partNo = page?.get("page").integer()
        val title = cleanTitle(detail["title"].str())
        return Track(
            id = if (partNo <= 1) "v:$bvid" else "v:$bvid:$cid",
            title = if (partNo <= 1) title else "$title · P$partNo ${page?.get("part").str()}",
            type = Track.Type.Song,
            cover = image(detail["pic"].str()),
            artists = listOf(Artist(owner["mid"].str(), owner["name"].str(), image(owner["face"].str()))),
            description = detail["desc"].str(),
            duration = (page?.get("duration").number().takeIf { it > 0 }
                ?: detail["duration"].number()) * 1000,
            extras = mapOf("cid" to cid), subtitle = "B站视频音轨 · ${owner["name"].str()}",
            streamables = listOf(Streamable.server("v:$bvid:$cid", 192, "音轨")),
            isSaveable = false, isLikeable = false, isHideable = false, isRadioSupported = false
        )
    }

    private fun audioTrack(info: kotlinx.serialization.json.JsonObject): Track {
        val sid = info["id"].str()
        return Track(
            id = "a:$sid", title = cleanTitle(info["title"].str()),
            cover = image(info["cover"].str()),
            artists = listOf(Artist(info["uid"].str(), info["author"].str().ifBlank { info["uname"].str() })),
            description = info["intro"].str(), duration = info["duration"].number() * 1000,
            subtitle = "B站音频 · au$sid",
            streamables = listOf(Streamable.server("a:$sid", 192, "B站音频")),
            isSaveable = false, isLikeable = false, isHideable = false, isRadioSupported = false
        )
    }

    private fun image(url: String) = url.takeIf { it.isNotBlank() }?.let {
        (if (it.startsWith("//")) "https:$it" else it.replaceFirst("http://", "https://")).toImageHolder()
    }

    private fun cleanTitle(raw: String): String = raw.replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")

    private fun parseDuration(text: String): Long? {
        val segments = text.split(':').mapNotNull { it.toLongOrNull() }
        if (segments.isEmpty()) return null
        return segments.fold(0L) { seconds, n -> seconds * 60 + n } * 1000
    }
}
