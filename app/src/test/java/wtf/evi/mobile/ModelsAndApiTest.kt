package wtf.evi.mobile

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wtf.evi.mobile.data.model.AudioQuality
import wtf.evi.mobile.data.model.LyricsResponse
import wtf.evi.mobile.data.model.SearchResponse
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.data.model.TicketResponse
import wtf.evi.mobile.data.model.TrendingResponse
import wtf.evi.mobile.data.network.EviApiClient

class ModelsAndApiTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun songResolvesCoverAndStreamUrls() {
        val song = Song(
            id = "RmNS51PihtL",
            title = "Blinding Lights",
            artist = "The Weeknd",
            duration = 200.0,
            coverUrl = "/api/artwork?id=RmNS51PihtL",
            url = "/api/stream/RmNS51PihtL"
        )
        assertEquals(
            "https://evi.wtf/api/artwork?id=RmNS51PihtL&size=512&v=3",
            song.resolvedCoverUrl(512)
        )
        assertEquals(
            "https://evi.wtf/api/stream/RmNS51PihtL?quality=lossless&direct=1",
            song.resolvedStreamUrl(AudioQuality.LOSSLESS)
        )
        assertEquals(
            "https://evi.wtf/api/stream/RmNS51PihtL?quality=320&direct=1",
            song.resolvedStreamUrl(AudioQuality.HIGH)
        )
        assertEquals("3:20", song.formattedDuration)
    }

    @Test
    fun songStreamUrlSanitizesExistingQueryParamsWithoutDuplicates() {
        val songWithExistingParams = Song(
            id = "test123",
            title = "Test",
            artist = "Artist",
            url = "https://evi.wtf/api/stream/test123?quality=128&direct=1"
        )
        assertEquals(
            "https://evi.wtf/api/stream/test123?quality=lossless&direct=1",
            songWithExistingParams.resolvedStreamUrl(AudioQuality.LOSSLESS)
        )

        val songWithExtraParams = Song(
            id = "test456",
            title = "Test 2",
            artist = "Artist 2",
            url = "https://evi.wtf/api/stream/test456?token=sec_abc&quality=high&direct=0&other=true"
        )
        assertEquals(
            "https://evi.wtf/api/stream/test456?token=sec_abc&other=true&quality=320&direct=1",
            songWithExtraParams.resolvedStreamUrl(AudioQuality.HIGH)
        )
    }

    @Test
    fun parsesLyricsJson() {
        val payload = """
            {
              "lines": [
                {
                  "words": [
                    {"word": "I ", "startTime": 27395, "endTime": 27549},
                    {"word": "been ", "startTime": 27549, "endTime": 27740}
                  ],
                  "startTime": 27395,
                  "endTime": 28960
                }
              ],
              "source": "binimum"
            }
        """.trimIndent()
        val parsed = json.decodeFromString<LyricsResponse>(payload)
        assertEquals(1, parsed.lines.size)
        assertEquals("I been", parsed.lines.first().fullText)
        assertEquals(27395L, parsed.lines.first().startTime)
    }

    @Test
    fun parsesTrendingSearchAndTicketJson() {
        val ticket = json.decodeFromString<TicketResponse>("""{"id":"abc123","code":"BK2S-KF"}""")
        assertEquals("abc123", ticket.id)
        assertEquals("BK2S-KF", ticket.code)

        val trending = json.decodeFromString<TrendingResponse>("""{"albums":[],"songs":[],"country":"US"}""")
        assertEquals("US", trending.country)

        val search = json.decodeFromString<SearchResponse>(
            """{"query":"weeknd","results":[{"id":"RmNS51PihtL","title":"Blinding Lights","artist":"The Weeknd","duration":200}],"artists":[]}"""
        )
        assertEquals(1, search.results.size)
        assertEquals("RmNS51PihtL", search.results.first().id)
    }

    @Test
    fun dateKeysHaveExpectedFormat() {
        val dayKey = EviApiClient.currentDayKey()
        assertTrue(dayKey.matches(Regex("""\d{4}-\d{1,2}-\d{1,2}""")))
        val weekKey = EviApiClient.currentWeekKey(1)
        assertTrue(weekKey.matches(Regex("""\d{4}-\d{1,2}-\d{1,2}""")))
        assertTrue(EviApiClient.currentDaypart() in setOf("morning", "afternoon", "evening", "night"))
    }
}
