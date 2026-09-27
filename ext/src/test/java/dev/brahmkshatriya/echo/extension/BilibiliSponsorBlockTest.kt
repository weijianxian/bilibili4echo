package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Chapter
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class BilibiliSponsorBlockTest {
    @Test fun onlyCurrentPartSponsorSkipsBecomeChapters() {
        val rows = Json.parseToJsonElement("""
            [
              {"cid":"168885122","category":"sponsor","actionType":"skip",
               "segment":[10.125,20.0],"videoDuration":100},
              {"cid":"168885122","category":"sponsor","actionType":"skip",
               "segment":[19.5,25.0],"videoDuration":100},
              {"cid":"168885123","category":"sponsor","actionType":"skip",
               "segment":[30,40],"videoDuration":100},
              {"cid":"168885122","category":"intro","actionType":"skip",
               "segment":[40,50],"videoDuration":100},
              {"cid":"168885122","category":"sponsor","actionType":"mute",
               "segment":[50,60],"videoDuration":100},
              {"cid":"168885122","category":"sponsor","actionType":"skip",
               "segment":[60,70],"videoDuration":200},
              {"cid":"168885122","category":"sponsor","actionType":"skip",
               "segment":[90,110],"videoDuration":100},
              {"cid":"168885122","category":"sponsor","actionType":"skip",
               "segment":[-2,3],"videoDuration":100}
            ]
        """).array()
        val chapters = BilibiliSponsorBlock().parseChapters(rows, "168885122", 100_000)
        assertEquals(1, chapters.size)
        assertEquals(10_125L, chapters.single().startTime)
        assertEquals(25_000L, chapters.single().endTime)
        assertEquals(Chapter.SkipType.SKIP, chapters.single().skipType)
    }

    @Test fun settingIsOptInAndSeparateClientDoesNotUseACookieJar() = runBlocking {
        val settings = BilibiliExtension().getSettingItems()
        val setting = settings.single() as SettingSwitch
        assertEquals("sponsorblock_skip_sponsor", setting.key)
        assertFalse(setting.defaultValue)

        val field = BilibiliSponsorBlock::class.java.getDeclaredField("client")
        field.isAccessible = true
        assertSame(CookieJar.NO_COOKIES, (field.get(BilibiliSponsorBlock()) as OkHttpClient).cookieJar)
    }

    @Test fun hashLookupOnlyUsesTheRequestedVideo() {
        val api = BilibiliSponsorBlock()
        assertEquals("5759fbc7", api.hashPrefix("BV14741127BN"))
        val response = Json.parseToJsonElement("""
            [{"videoID":"BV1Q8P7z8Exw","segments":[
                {"cid":"168885122","category":"sponsor","actionType":"skip","segment":[1,3]}]},
             {"videoID":"BV14741127BN","segments":[
                {"cid":"168885122","category":"sponsor","actionType":"skip","segment":[300.019,600.014]}]}]
        """).array()
        val chapters = api.parseVideoChapters(response, "BV14741127BN", "168885122", 1_801_000)
        assertEquals(listOf(300_019L), chapters.map { it.startTime })
    }
}
