package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Shelf
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
}
