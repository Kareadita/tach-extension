package eu.kanade.tachiyomi.extension.all.kavita

import eu.kanade.tachiyomi.extension.all.kavita.dto.VolumeDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kavita 0.9.1.24 ("A new Image Pipeline") replaced `coverImage` with `hasCoverImage`
 * on VolumeDto/ChapterDto. Both server shapes must keep parsing.
 */
class VolumeDtoParsingTest {
    // Same settings as KavitaHelper.json
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun volumesJson(coverFields: String) = """
        [{
          "id": 1, "minNumber": 1.0, "maxNumber": 1.0, "name": "1",
          "pages": 20, "pagesRead": 0,
          "lastModified": "2026-11-20T00:00:00", "created": "2026-11-20T00:00:00",
          "seriesId": 7, $coverFields,
          "chapters": [{
            "id": 10, "range": "1", "number": "1", "minNumber": 1.0, "maxNumber": 1.0,
            "pages": 20, "isSpecial": false, "title": "1", "titleName": null,
            "pagesRead": 0, "coverImageLocked": false, $coverFields, "volumeId": 1,
            "created": "2026-11-20T00:00:00", "lastModifiedUtc": "2026-11-20T00:00:00",
            "releaseDate": "0001-01-01T00:00:00"
          }]
        }]
    """.trimIndent()

    @Test
    fun `parses new image pipeline response`() {
        val volume = json.decodeFromString<List<VolumeDto>>(volumesJson("\"hasCoverImage\": true")).single()
        assertTrue(volume.hasCover)
        assertTrue(volume.chapters.single().hasCover)
    }

    @Test
    fun `parses new response without cover`() {
        val volume = json.decodeFromString<List<VolumeDto>>(volumesJson("\"hasCoverImage\": false")).single()
        assertFalse(volume.hasCover)
        assertFalse(volume.chapters.single().hasCover)
    }

    @Test
    fun `parses legacy response`() {
        val volume = json.decodeFromString<List<VolumeDto>>(volumesJson("\"coverImage\": \"v1.png\"")).single()
        assertTrue(volume.hasCover)
        assertEquals("v1.png", volume.coverImage)
        assertTrue(volume.chapters.single().hasCover)
    }

    @Test
    fun `parses legacy response with blank cover`() {
        val volume = json.decodeFromString<List<VolumeDto>>(volumesJson("\"coverImage\": \"\"")).single()
        assertFalse(volume.hasCover)
        assertFalse(volume.chapters.single().hasCover)
    }
}
