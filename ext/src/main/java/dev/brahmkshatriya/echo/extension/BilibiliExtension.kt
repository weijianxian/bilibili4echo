package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings

/** Echo music extension: Bilibili video soundtracks and legacy au audio entries. */
class BilibiliExtension : ExtensionClient, HomeFeedClient, SearchFeedClient, TrackClient {
    private val api = BilibiliApi()

    override fun setSettings(settings: Settings) = Unit
    override suspend fun getSettingItems(): List<Setting> = emptyList()

    override suspend fun loadHomeFeed(): Feed<Shelf> = discover()

    private fun discover(): Feed<Shelf> = listOf<Shelf>(
        Shelf.Category("music", "音乐", search("音乐")),
        Shelf.Category("cover", "翻唱", search("翻唱")),
        Shelf.Category("instrumental", "演奏", search("演奏")),
        Shelf.Category("vocaloid", "VOCALOID", search("VOCALOID"))
    ).toFeed()

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val q = query.trim()
        if (q.isEmpty()) return discover()
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
            return listOf<Shelf>(Shelf.Item(videoTrack(detail, selected, bvid))).toFeed()
        }
        return search(q)
    }

    private fun search(keyword: String): Feed<Shelf> = PagedData.Continuous<Shelf> { cursor ->
        val page = (cursor?.toIntOrNull() ?: 1).coerceIn(1, 50)
        val result = api.searchVideos(keyword, page)
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
        if (pages.size <= 1) return null
        return listOf<Shelf>(Shelf.Lists.Tracks(
            id = "parts:$bvid", title = "分 P", list = pages.map { videoTrack(detail, it.obj(), bvid) }
        )).toFeed()
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
