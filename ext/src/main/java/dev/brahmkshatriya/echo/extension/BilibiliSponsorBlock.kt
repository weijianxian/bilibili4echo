package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Chapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/** Only public segment annotations are requested; Bilibili account cookies are never sent. */
internal class BilibiliSponsorBlock {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun adChapters(bvid: String, cid: String, durationMs: Long?): List<Chapter> {
        require(bvid.matches(Regex("BV[0-9A-Za-z]{10}"))) { "无效的 BV 号" }
        require(cid.matches(Regex("[0-9]+"))) { "无效的分 P CID" }
        // The hash-prefix endpoint does not disclose the full BV number to the server.
        val url = "https://bsbsb.top/api/skipSegments/${hashPrefix(bvid)}".toHttpUrl().newBuilder()
            .addQueryParameter("category", "sponsor")
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Echo-Bilibili-Audio/0.5.0-rc.1")
            .build()
        return client.newCall(request).await().use { response ->
            if (response.code == 404) return@use emptyList()
            if (!response.isSuccessful) error("空降助手 HTTP ${response.code}")
            val body = response.body?.string() ?: return@use emptyList()
            parseVideoChapters(json.parseToJsonElement(body).array(), bvid, cid, durationMs)
        }
    }

    internal fun hashPrefix(bvid: String): String = MessageDigest.getInstance("SHA-256")
        .digest(bvid.toByteArray(Charsets.UTF_8))
        .take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    internal fun parseVideoChapters(rows: JsonArray, bvid: String, cid: String,
                                    durationMs: Long?): List<Chapter> = rows
        .firstOrNull { it.obj()["videoID"].str() == bvid }
        ?.obj()?.get("segments").array()
        .let { parseChapters(it, cid, durationMs) }

    /** Filter again locally: a server-side category or CID filter is not a skip policy. */
    internal fun parseChapters(rows: JsonArray, cid: String, durationMs: Long?): List<Chapter> {
        val duration = durationMs?.takeIf { it > 0 }
        val ranges = rows.mapNotNull { element ->
            val row = element.obj()
            if (row["cid"].str() != cid || row["category"].str() != "sponsor" ||
                row["actionType"].str() != "skip") return@mapNotNull null
            val times = row["segment"].array()
            if (times.size != 2) return@mapNotNull null
            val start = times[0].str().toDoubleOrNull()
            val end = times[1].str().toDoubleOrNull()
            if (start == null || end == null || !start.isFinite() || !end.isFinite() ||
                start < 0 || end <= start || end > 24 * 60 * 60) return@mapNotNull null
            val annotatedDuration = row["videoDuration"].str().toDoubleOrNull()
            if (duration != null && annotatedDuration != null && annotatedDuration > 0 &&
                abs(annotatedDuration * 1000 - duration) > 2000) return@mapNotNull null
            val fromMs = (start * 1000).roundToLong()
            val toMs = (end * 1000).roundToLong()
            if (toMs <= fromMs || (duration != null && toMs > duration + 1000)) return@mapNotNull null
            fromMs to (duration?.let { minOf(toMs, it) } ?: toMs)
        }.filter { (start, end) -> end > start }.sortedBy { it.first }

        // Overlapping annotations describe a single skip, even if several people submitted it.
        val merged = mutableListOf<Pair<Long, Long>>()
        ranges.forEach { (start, end) ->
            val previous = merged.lastOrNull()
            if (previous != null && start <= previous.second) {
                merged[merged.lastIndex] = previous.first to maxOf(previous.second, end)
            } else merged += start to end
        }
        return merged.map { (start, end) ->
            Chapter("空降助手 · 广告", start, end, Chapter.SkipType.SKIP)
        }
    }
}
