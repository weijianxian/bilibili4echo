package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliExtensionTest {
    private val extension = BilibiliExtension()

    @Test fun homeShowsCategoriesOnceAndLoadsTheFeedByPage() {
        val first = extension.homePage(1, Json.parseToJsonElement("""
            {"list":[{"bvid":"BV1XN411K7g9","title":"视频","owner":{"mid":42,"name":"UP"}}],
             "no_more":false}
        """).obj())
        assertTrue(first.data.first() is Shelf.Lists.Categories)
        assertEquals(1, first.data.count { it is Shelf.Item })
        assertEquals("2", first.continuation)

        val second = extension.homePage(2, Json.parseToJsonElement("""
            {"list":[{"bvid":"BV1XN411K7g9","title":"下一页","owner":{"mid":42,"name":"UP"}}],
             "no_more":true}
        """).obj())
        assertEquals(1, second.data.size)
        assertTrue(second.data.single() is Shelf.Item)
        assertEquals(null, second.continuation)
    }

    @Test fun creatorHasOneUploadsRowAndEachCollectionIsExpanded() {
        val artist = Artist("42", "UP")
        val uploads = Json.parseToJsonElement("""
            {"list":{"vlist":[{"bvid":"BV1XN411K7g9","title":"投稿"}]}}
        """).obj()
        val collections = Json.parseToJsonElement("""
            {"seasons_list":[
                {"meta":{"mid":42,"season_id":7,"name":"合集一"},
                 "archives":[{"bvid":"BV1XN411K7g9","title":"曲目一"}]},
                {"meta":{"mid":42,"season_id":8,"name":"合集二"},
                 "archives":[{"bvid":"BV1XN411K7g9","title":"曲目二"}]}
            ],"series_list":[]}
        """).obj()
        val first = extension.artistShelves(artist, uploads, collections)
        assertEquals(listOf("全部投稿", "合集一", "合集二"), first.map { it.title })
        assertTrue(first.all { it is Shelf.Lists.Tracks })
        assertNotNull((first[0] as Shelf.Lists.Tracks).more)
        assertNotNull((first[1] as Shelf.Lists.Tracks).more)

        val moreCollections = extension.artistShelves(artist, null, collections)
        assertFalse(moreCollections.any { it.title == "全部投稿" })
    }

    @Test fun directShareLinksPreserveTheRightBilibiliIdentifier() = runBlocking {
        assertEquals("https://www.bilibili.com/video/BV1XN411K7g9",
            extension.onShare(Track("v:BV1XN411K7g9", "视频")))
        assertEquals("https://www.bilibili.com/audio/am10624",
            extension.onShare(Album("am:10624", "歌单")))
        assertEquals("https://space.bilibili.com/42/favlist?fid=101",
            extension.onShare(Playlist("fav:42:101", "收藏夹", isEditable = false)))
    }

    @Test fun lrcTimestampsUseMillisecondsAndEndAtTheNextLine() {
        val parsed = extension.parseAudioLyrics("[ar:歌手]\n[00:02.5]第一句\n" +
            "[00:03.025]第二句\n[01:00]结尾") as Lyrics.Timed
        assertEquals(listOf("第一句", "第二句", "结尾"), parsed.list.map { it.text })
        assertEquals(listOf(2500L, 3025L, 60000L), parsed.list.map { it.startTime })
        assertEquals(3025L, parsed.list.first().endTime)
    }

    @Test fun videoPartsSurviveRecommendationFailure() = runBlocking {
        val detail = Json.parseToJsonElement("""
            {"title":"多 P 视频","owner":{"mid":42,"name":"UP"},"cid":10,
             "ugc_season":{"id":7,"mid":42,"title":"合集"},
             "pages":[{"cid":10,"page":1,"part":"第一集"},
                      {"cid":20,"page":2,"part":"第二集"}]}
        """).obj()
        val feed = extension.trackFeed("BV1XN411K7g9", detail) {
            throw java.io.IOException("recommendations unavailable")
        }
        val shelves = feed!!.getPagedData(null).pagedData.loadPage(null).data
        assertEquals(listOf("合集", "分 P"), shelves.map { it.title })
        val parts = (shelves[1] as Shelf.Lists.Tracks).list
        assertEquals(listOf("v:BV1XN411K7g9", "v:BV1XN411K7g9:20"), parts.map { it.id })

        var cancelled = false
        try {
            extension.trackFeed("BV1XN411K7g9", detail) { throw CancellationException() }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
