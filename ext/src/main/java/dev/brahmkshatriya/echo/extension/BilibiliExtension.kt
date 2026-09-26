package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditPrivacyClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.TrackChapterClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Chapter
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings

/** Echo music extension: Bilibili video soundtracks and legacy au audio entries. */
class BilibiliExtension : ExtensionClient, HomeFeedClient, QuickSearchClient, TrackClient,
    LoginClient.WebView, ArtistClient, AlbumClient, PlaylistEditPrivacyClient, LibraryFeedClient, LikeClient,
    FollowClient, SaveClient, LyricsClient, RadioClient, ShareClient, TrackChapterClient {
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
                image(owner["face"].str()), isSaveable = false)
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
        activeUser?.let { Shelf.Item(Artist(it.id, it.name, it.cover,
            subtitle = "我的 B 站主页", isSaveable = false)) }
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
        val am = Regex("(?:^|[/\\s])am([0-9]+)(?:$|[?/#\\s])", RegexOption.IGNORE_CASE)
            .find(q)?.groupValues?.get(1)
        if (am != null) return listOf<Shelf>(Shelf.Item(loadAlbum(Album("am:$am", "B站音频歌单")))).toFeed()
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
                extras = mapOf("aid" to row["aid"].str()),
                subtitle = "B站视频音轨 · ${row["author"].str()}",
                isHideable = false
            ))
        }
        val totalPages = result["numPages"].integer().coerceAtMost(50)
        Page(tracks, if (tracks.isNotEmpty() && page < totalPages) (page + 1).toString() else null)
    }.toFeed()

    override suspend fun quickSearch(query: String): List<QuickSearchItem> =
        if (query.isBlank()) emptyList() else api.suggestions(query.trim()).mapNotNull {
            it.obj()["value"].str().takeIf(String::isNotBlank)?.let { word ->
                QuickSearchItem.Query(word, searched = false)
            }
        }

    // Suggestions come from Bilibili's live index, not a stored search history.
    override suspend fun deleteQuickSearch(item: QuickSearchItem) = Unit

    override suspend fun loadArtist(artist: Artist): Artist {
        val mid = validId(artist.id)
        val profile = api.userInfo(mid)
        return artist.copy(
            name = profile["name"].str().ifBlank { artist.name },
            cover = image(profile["face"].str()) ?: artist.cover,
            bio = profile["sign"].str(),
            subtitle = "B 站 UP 主 · UID $mid",
            isFollowable = true, isSaveable = false
        )
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val mid = validId(artist.id)
        return PagedData.Continuous<Shelf> { cursor ->
            val page = (cursor?.toIntOrNull() ?: 1).coerceAtLeast(1)
            // The only upload shelf is on page one. Further homepage pages contain collections.
            val uploads = if (page == 1) api.userVideos(mid, 1) else null
            val collections = api.userCollections(mid, page)
            val shelves = artistShelves(artist, uploads, collections) +
                if (page == 1 && activeUser?.id == mid) api.favoriteFolders(mid)
                    .mapNotNull { favoriteFromRow(it.obj()) }.map { Shelf.Item(it) }
                else emptyList()
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

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val user = activeUser ?: error("请先登录哔哩哔哩以查看资料库")
        return Feed(listOf(Tab("favorites", "收藏夹"), Tab("following", "关注"),
            Tab("audio", "音频歌单"), Tab("later", "稍后再看"),
            Tab("history", "观看历史"), Tab("uploads", "我的投稿"))) { tab ->
            val data = when (tab?.id) {
                "following" -> PagedData.Continuous<Shelf> { cursor ->
                    val page = (cursor?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val result = api.followings(user.id, page)
                    val items = result["list"].array().map { item ->
                        val person = item.obj()
                        Shelf.Item(Artist(person["mid"].str(), person["uname"].str(),
                            image(person["face"].str()), isSaveable = false))
                    }
                    Page(items, if (items.isNotEmpty() && page * 50 < result["total"].number())
                        (page + 1).toString() else null)
                }
                "uploads" -> PagedData.Continuous<Shelf> { cursor ->
                    val page = (cursor?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val result = api.userVideos(user.id, page)
                    val artist = Artist(user.id, user.name, user.cover, isSaveable = false)
                    val items = result["list"].obj()["vlist"].array().mapNotNull { row ->
                        uploadTrack(row.obj(), artist)?.let { Shelf.Item(it) }
                    }
                    val total = result["page"].obj()["count"].number()
                    Page(items, if (items.isNotEmpty() && page * 30 < total)
                        (page + 1).toString() else null)
                }
                "audio" -> PagedData.Continuous<Shelf> { cursor ->
                    val page = (cursor?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val result = api.audioCollections(page)
                    val albums = result["data"].array().mapNotNull { element ->
                        audioAlbum(element.obj())?.let { Shelf.Item(it) }
                    }
                    Page(albums, if (albums.isNotEmpty() && page < result["pageCount"].integer())
                        (page + 1).toString() else null)
                }
                "later" -> PagedData.Continuous<Shelf> { cursor ->
                    val start = (cursor?.toIntOrNull() ?: 0).coerceAtLeast(0)
                    val list = api.watchLater()["list"].array()
                    val items = list.drop(start).take(20).mapNotNull { element ->
                        val row = element.obj()
                        val owner = row["owner"].obj()
                        uploadTrack(row, Artist(owner["mid"].str(), owner["name"].str(),
                            image(owner["face"].str()), isSaveable = false))?.let { Shelf.Item(it) }
                    }
                    Page(items, if (start + 20 < list.size) (start + 20).toString() else null)
                }
                "history" -> PagedData.Continuous<Shelf> { cursor ->
                    val (max, viewAt) = cursor?.split(':')?.takeIf { it.size == 2 }
                        ?: listOf("0", "0")
                    val result = api.history(validId(max), validId(viewAt))
                    val items = result["list"].array().mapNotNull { element ->
                        val row = element.obj()
                        val history = row["history"].obj()
                        val bvid = history["bvid"].str()
                        if (!bvid.matches(Regex("BV[0-9A-Za-z]{10}"))) return@mapNotNull null
                        Shelf.Item(Track("v:$bvid", cleanTitle(row["title"].str()),
                            cover = image(row["cover"].str()),
                            artists = listOf(Artist(row["author_mid"].str(),
                                row["author_name"].str(), image(row["author_face"].str()),
                                isSaveable = false)),
                            duration = row["duration"].number() * 1000,
                            playedDuration = row["progress"].number().takeIf { it > 0 }?.times(1000),
                            extras = mapOf("aid" to history["oid"].str()),
                            subtitle = "B 站观看历史", isHideable = false))
                    }
                    val next = result["cursor"].obj()
                    val nextMax = next["max"].str()
                    val nextAt = next["view_at"].str()
                    Page(items, if (result["list"].array().isNotEmpty() &&
                        nextMax.matches(Regex("[0-9]+")) && nextAt.matches(Regex("[0-9]+")) &&
                        "$max:$viewAt" != "$nextMax:$nextAt") "$nextMax:$nextAt" else null)
                }
                else -> PagedData.Continuous<Shelf> { cursor ->
                    val start = (cursor?.toIntOrNull() ?: 0).coerceAtLeast(0)
                    val folders = api.favoriteFolders(user.id)
                    val items = folders.drop(start).take(20).mapNotNull { row ->
                        favoriteFromRow(row.obj())?.let { Shelf.Item(it) }
                    }
                    Page(items, if (start + 20 < folders.size) (start + 20).toString() else null)
                }
            }
            Feed.Data(data)
        }
    }

    private fun favoriteFromRow(row: kotlinx.serialization.json.JsonObject): Playlist? {
        val id = row["id"].str().takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        val mid = row["mid"].str()
        val private = row["attr"].integer() and 1 != 0
        return Playlist("fav:$mid:$id", row["title"].str(),
            isEditable = activeUser?.id == mid, isPrivate = private,
            cover = image(row["cover"].str()), description = row["intro"].str(),
            trackCount = row["media_count"].number(), subtitle = "B 站收藏夹",
            isSaveable = false, isRadioSupported = false)
    }

    private fun favoriteTrack(row: kotlinx.serialization.json.JsonObject): Track? {
        if (row["attr"].integer() != 0) return null
        val owner = row["upper"].obj()
        val artist = Artist(owner["mid"].str(), owner["name"].str(),
            image(owner["face"].str()), isSaveable = false)
        return when (row["type"].integer()) {
            2 -> {
                val bvid = row["bvid"].str().ifBlank { row["bv_id"].str() }
                if (!bvid.matches(Regex("BV[0-9A-Za-z]{10}"))) return null
                Track("v:$bvid", cleanTitle(row["title"].str()), Track.Type.Song,
                    cover = image(row["cover"].str()), artists = listOf(artist),
                    duration = row["duration"].number() * 1000,
                    extras = mapOf("aid" to row["id"].str()),
                    subtitle = "B 站视频音轨", isHideable = false)
            }
            12 -> Track("a:${row["id"].str()}", cleanTitle(row["title"].str()),
                cover = image(row["cover"].str()), artists = listOf(artist),
                duration = row["duration"].number() * 1000,
                subtitle = "B 站音频", isSaveable = false, isLikeable = false,
                isHideable = false, isRadioSupported = false)
            else -> null
        }
    }

    private fun audioAlbum(row: kotlinx.serialization.json.JsonObject): Album? {
        val sid = row["menuId"].str().ifBlank { row["id"].str() }
            .takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        return Album("am:$sid", row["title"].str(), cover = image(row["cover"].str()),
            description = row["intro"].str().ifBlank { row["desc"].str() },
            trackCount = row["snum"].number().takeIf { it > 0 } ?: row["song"].number(),
            subtitle = "B 站音频歌单", isRadioSupported = false, isSaveable = false)
    }

    override suspend fun loadAlbum(album: Album): Album {
        require(album.id.startsWith("am:")) { "不支持此歌单" }
        val sid = validId(album.id.removePrefix("am:"))
        return audioAlbum(api.audioMenu(sid))?.copy(id = "am:$sid") ?: album
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        require(album.id.startsWith("am:")) { "不支持此歌单" }
        val sid = validId(album.id.removePrefix("am:"))
        return PagedData.Continuous<Track> { cursor ->
            val page = (cursor?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val result = api.audioMenuSongs(sid, page)
            val items = result["data"].array().mapNotNull { row ->
                row.obj().takeIf { it["id"].str().isNotBlank() }?.let(::audioTrack)
            }
            Page(items, if (items.isNotEmpty() && page < result["pageCount"].integer())
                (page + 1).toString() else null)
        }.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    private fun videoBvid(item: EchoMediaItem): String =
        (item as? Track)?.id?.split(':')?.takeIf { it.firstOrNull() == "v" }
            ?.getOrNull(1)?.takeIf { it.matches(Regex("BV[0-9A-Za-z]{10}")) }
            ?: error("仅支持操作 B 站视频")

    private suspend fun videoAid(track: Track): String =
        track.extras["aid"]?.takeIf { it.matches(Regex("[0-9]+")) }
            ?: api.view(videoBvid(track))["aid"].str().let(::validId)

    private fun requireFavorite(playlist: Playlist): String {
        val (type, mid, id) = playlistParts(playlist)
        require(type == "fav" && activeUser?.id == mid) { "只能编辑自己的 B 站收藏夹" }
        return id
    }

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        val mid = activeUser?.id ?: error("请先登录哔哩哔哩")
        if (track != null && !track.id.startsWith("v:")) return emptyList()
        val rid = track?.takeIf { it.id.startsWith("v:") }?.let { videoAid(it) }
        return api.favoriteFolders(mid, rid).mapNotNull { element ->
            val row = element.obj()
            favoriteFromRow(row)?.let { it to (row["fav_state"].integer() == 1) }
        }
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        val mid = activeUser?.id ?: error("请先登录哔哩哔哩")
        require(title.isNotBlank()) { "收藏夹标题不能为空" }
        return favoriteFromRow(api.createdFolder(title.trim(), description))
            ?: error("B 站未返回新收藏夹信息（UID $mid）")
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        api.deleteFolder(requireFavorite(playlist))
    }

    override suspend fun editPlaylistMetadata(playlist: Playlist, title: String,
                                              description: String?) {
        require(title.isNotBlank()) { "收藏夹标题不能为空" }
        api.editFolder(requireFavorite(playlist), title.trim(), description, playlist.isPrivate)
    }

    override suspend fun setPrivacy(playlist: Playlist, isPrivate: Boolean) {
        api.editFolder(requireFavorite(playlist), playlist.title, playlist.description, isPrivate)
    }

    override suspend fun addTracksToPlaylist(playlist: Playlist, tracks: List<Track>,
                                             index: Int, new: List<Track>) {
        val folder = requireFavorite(playlist)
        new.forEach { track -> api.favoriteVideo(videoAid(track), folder, true) }
    }

    override suspend fun removeTracksFromPlaylist(playlist: Playlist, tracks: List<Track>,
                                                  indexes: List<Int>) {
        val resources = indexes.map { index ->
            val track = tracks[index]
            if (track.id.startsWith("a:")) "${validId(track.id.removePrefix("a:"))}:12"
            else "${videoAid(track)}:2"
        }
        api.removeFavorites(requireFavorite(playlist), resources)
    }

    override suspend fun moveTrackInPlaylist(playlist: Playlist, tracks: List<Track>,
                                             fromIndex: Int, toIndex: Int) {
        requireFavorite(playlist)
        error("B 站收藏夹按收藏时间排序，不支持手动调整顺序")
    }

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean {
        val user = activeUser ?: return false
        if (item !is Track || !item.id.startsWith("v:")) return false
        val rid = videoAid(item)
        val folders = api.favoriteFolders(user.id, rid)
        val default = folders.firstOrNull { it.obj()["attr"].integer() and 2 == 0 }
            ?: folders.firstOrNull()
        return default?.obj()?.get("fav_state").integer() == 1
    }

    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) {
        val track = item as? Track ?: error("仅支持收藏 B 站视频")
        val user = activeUser ?: error("请先登录哔哩哔哩")
        val folders = api.favoriteFolders(user.id)
        val default = folders.firstOrNull { it.obj()["attr"].integer() and 2 == 0 }
            ?: folders.firstOrNull() ?: error("账号尚未创建 B 站收藏夹")
        api.favoriteVideo(videoAid(track), validId(default.obj()["id"].str()), shouldSave)
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        api.likeVideo(videoBvid(item), shouldLike)
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean =
        activeUser != null && api.isVideoLiked(videoBvid(item))

    override suspend fun isFollowing(item: EchoMediaItem): Boolean =
        activeUser != null && api.relation(validId((item as Artist).id))["attribute"].integer()
            .let { it == 2 || it == 6 }

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? =
        api.followerCount(validId((item as Artist).id))

    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) {
        api.follow(validId((item as Artist).id), shouldFollow)
    }

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
            description = season["intro"].str(), isSaveable = false, isRadioSupported = false)
    }

    private fun uploadTrack(row: kotlinx.serialization.json.JsonObject, artist: Artist): Track? {
        val bvid = row["bvid"].str().takeIf { it.matches(Regex("BV[0-9A-Za-z]{10}")) }
            ?: return null
        return Track("v:$bvid", cleanTitle(row["title"].str()), Track.Type.Song,
            cover = image(row["pic"].str()), artists = listOf(artist),
            duration = row["length"].str().let(::parseDuration)
                ?: row["duration"].number().takeIf { it > 0 }?.times(1000),
            description = row["description"].str(), subtitle = "B 站视频音轨",
            extras = mapOf("aid" to row["aid"].str()), isHideable = false)
    }

    private fun validId(value: String): String {
        require(value.matches(Regex("[0-9]+"))) { "Invalid Bilibili ID" }
        return value
    }

    private fun playlistParts(playlist: Playlist): Triple<String, String, String> {
        val parts = playlist.id.split(':')
        require(parts.size == 3 && parts[0] in listOf("season", "series", "fav")) {
            "Unsupported Bilibili collection"
        }
        return Triple(parts[0], validId(parts[1]), validId(parts[2]))
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val (type, mid, id) = playlistParts(playlist)
        if (type == "fav") return favoriteFromRow(api.favoriteFolder(id)) ?: playlist
        val meta = if (type == "season") api.collection(mid, id, 1)["meta"].obj()
                   else api.seriesInfo(id)
        return playlistFromMeta(meta, type) ?: playlist
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val (type, mid, id) = playlistParts(playlist)
        if (type == "fav") return PagedData.Continuous<Track> { cursor ->
            val page = (cursor?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val result = api.favoriteItems(id, page)
            val tracks = result["medias"].array().mapNotNull { favoriteTrack(it.obj()) }
            Page(tracks, if (result["has_more"].str() == "true")
                (page + 1).toString() else null)
        }.toFeed()
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
        val related = api.related(bvid).mapNotNull { row ->
            val video = row.obj()
            val owner = video["owner"].obj()
            uploadTrack(video, Artist(owner["mid"].str(), owner["name"].str(),
                image(owner["face"].str()), isSaveable = false))
        }.filterNot { it.id == "v:$bvid" }
        if (related.isNotEmpty()) shelves += Shelf.Lists.Tracks(
            id = "related:$bvid", title = "相关视频", list = related.take(8),
            more = related.mapTo(mutableListOf<Shelf>()) { Shelf.Item(it) }.toFeed()
        )
        return shelves.takeIf { it.isNotEmpty() }?.toFeed()
    }

    override suspend fun onShare(item: EchoMediaItem): String = when (item) {
        is Track -> when {
            item.id.startsWith("v:") -> "https://www.bilibili.com/video/${videoBvid(item)}" +
                (item.id.split(':').getOrNull(2)?.let { cid ->
                    val index = api.view(videoBvid(item))["pages"].array()
                        .indexOfFirst { it.obj()["cid"].str() == cid }
                    if (index > 0) "?p=${index + 1}" else ""
                } ?: "")
            item.id.startsWith("a:") -> "https://www.bilibili.com/audio/au${validId(item.id.removePrefix("a:"))}"
            else -> error("不支持分享此音轨")
        }
        is Artist -> "https://space.bilibili.com/${validId(item.id)}"
        is Album -> "https://www.bilibili.com/audio/am${validId(item.id.removePrefix("am:"))}"
        is Playlist -> {
            val (type, mid, id) = playlistParts(item)
            when (type) {
                "fav" -> "https://space.bilibili.com/$mid/favlist?fid=$id"
                "season" -> "https://space.bilibili.com/$mid/channel/collectiondetail?sid=$id"
                else -> "https://space.bilibili.com/$mid/channel/seriesdetail?sid=$id"
            }
        }
        else -> error("不支持分享此内容")
    }

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = when (item) {
        is Track -> Radio("related:${videoBvid(item)}", "${item.title} · 相关视频",
            item.cover, isShareable = false)
        is Artist -> Radio("uploads:${validId(item.id)}", "${item.name} · 投稿",
            item.cover, isShareable = false)
        else -> error("此内容没有推荐电台")
    }

    override suspend fun loadRadio(radio: Radio): Radio = radio

    override suspend fun loadTracks(radio: Radio): Feed<Track> = when {
        radio.id.startsWith("related:") -> {
            val bvid = radio.id.removePrefix("related:")
            require(bvid.matches(Regex("BV[0-9A-Za-z]{10}"))) { "无效的视频号" }
            PagedData.Single {
                api.related(bvid).mapNotNull { element ->
                    val row = element.obj()
                    val owner = row["owner"].obj()
                    uploadTrack(row, Artist(owner["mid"].str(), owner["name"].str(),
                        image(owner["face"].str()), isSaveable = false))
                }.filterNot { it.id == "v:$bvid" }
            }.toFeed()
        }
        radio.id.startsWith("uploads:") -> {
            val mid = validId(radio.id.removePrefix("uploads:"))
            val artist = Artist(mid, radio.title.substringBefore(" · "), isSaveable = false)
            PagedData.Continuous<Track> { cursor ->
                val page = (cursor?.toIntOrNull() ?: 1).coerceAtLeast(1)
                val result = api.userVideos(mid, page)
                val tracks = result["list"].obj()["vlist"].array()
                    .mapNotNull { uploadTrack(it.obj(), artist) }
                Page(tracks, if (tracks.isNotEmpty() && page * 30 <
                    result["page"].obj()["count"].number()) (page + 1).toString() else null)
            }.toFeed()
        }
        else -> error("无效的 B 站电台")
    }

    private suspend fun videoCid(track: Track): Pair<String, String> {
        val bvid = videoBvid(track)
        val cid = track.id.split(':').getOrNull(2) ?: track.extras["cid"]
            ?: api.view(bvid)["cid"].str()
        return bvid to validId(cid)
    }

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        if (track.id.startsWith("a:")) {
            val sid = validId(track.id.removePrefix("a:"))
            val raw = api.audioLyrics(sid)
            if (raw.isBlank()) return emptyList<Lyrics>().toFeed()
            return listOf(Lyrics("a:$sid", "歌词", "B 站音频歌词",
                lyrics = parseAudioLyrics(raw))).toFeed()
        }
        if (!track.id.startsWith("v:")) return emptyList<Lyrics>().toFeed()
        val (bvid, cid) = videoCid(track)
        return PagedData.Single {
            api.playerInfo(bvid, cid)["subtitle"].obj()["subtitles"].array()
                .mapNotNull { element ->
                    val row = element.obj()
                    val url = row["subtitle_url"].str()
                    if (url.isBlank()) null else Lyrics(
                        id = "v:$bvid:$cid:${row["lan"].str()}", title = "${row["lan_doc"].str()}字幕",
                        subtitle = "B 站视频字幕", extras = mapOf("url" to url)
                    )
                }
        }.toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        if (lyrics.lyrics != null) return lyrics
        val url = lyrics.extras["url"] ?: error("字幕地址不存在")
        val lines = api.subtitle(url).mapNotNull { element ->
            val row = element.obj()
            val start = row["from"].str().toDoubleOrNull()
            val end = row["to"].str().toDoubleOrNull()
            val content = row["content"].str()
            if (start == null || end == null || end <= start || content.isBlank()) null
            else Lyrics.Item(content, (start * 1000).toLong(), (end * 1000).toLong())
        }
        return lyrics.copy(lyrics = Lyrics.Timed(lines))
    }

    internal fun parseAudioLyrics(raw: String): Lyrics.Lyric {
        val timed = Regex("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?]")
        val lines = raw.lineSequence().flatMap { line ->
            val content = line.substringAfterLast(']').trim()
            if (content.isBlank()) emptySequence() else timed.findAll(line).map { match ->
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                val ms = (match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) *
                    1000 + fraction
                ms to content
            }
        }.sortedBy { it.first }.toList()
        if (lines.isEmpty()) return Lyrics.Simple(raw)
        return Lyrics.Timed(lines.mapIndexed { index, (start, content) ->
            Lyrics.Item(content, start, lines.getOrNull(index + 1)?.first ?: start + 5000)
        })
    }

    override suspend fun getChapters(track: Track): List<Chapter> {
        if (!track.id.startsWith("v:")) return emptyList()
        val (bvid, cid) = videoCid(track)
        return api.playerInfo(bvid, cid)["view_points"].array().mapNotNull { element ->
            val row = element.obj()
            val start = row["from"].str().toDoubleOrNull()
            val end = row["to"].str().toDoubleOrNull()
            val title = row["content"].str()
            if (start == null || title.isBlank()) null
            else Chapter(title, (start * 1000).toLong(), end?.let { (it * 1000).toLong() })
        }
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
            artists = listOf(Artist(owner["mid"].str(), owner["name"].str(),
                image(owner["face"].str()), isSaveable = false)),
            description = detail["desc"].str(),
            duration = (page?.get("duration").number().takeIf { it > 0 }
                ?: detail["duration"].number()) * 1000,
            extras = mapOf("cid" to cid, "aid" to detail["aid"].str()),
            subtitle = "B站视频音轨 · ${owner["name"].str()}",
            streamables = listOf(Streamable.server("v:$bvid:$cid", 192, "音轨")),
            isHideable = false
        )
    }

    private fun audioTrack(info: kotlinx.serialization.json.JsonObject): Track {
        val sid = info["id"].str()
        return Track(
            id = "a:$sid", title = cleanTitle(info["title"].str()),
            cover = image(info["cover"].str()),
            artists = listOf(Artist(info["uid"].str(),
                info["author"].str().ifBlank { info["uname"].str() }, isSaveable = false)),
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
