package eu.kanade.tachiyomi.extension.all.kavita

import eu.kanade.tachiyomi.extension.all.kavita.dto.ChapterDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.ChapterType
import eu.kanade.tachiyomi.extension.all.kavita.dto.LibraryTypeEnum
import eu.kanade.tachiyomi.extension.all.kavita.dto.PaginationInfo
import eu.kanade.tachiyomi.extension.all.kavita.dto.SeriesDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.VolumeDto
import eu.kanade.tachiyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KavitaHelper {
    val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        allowSpecialFloatingPointValues = true
        useArrayPolymorphism = true
        prettyPrint = true
    }
    inline fun <reified T : Enum<T>> safeValueOf(type: String): T {
        return java.lang.Enum.valueOf(T::class.java, type)
    }
    val dateFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSS", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun parseDate(dateAsString: String): Long =
        dateFormatter.parse(dateAsString)?.time ?: 0

    fun hasNextPage(response: Response): Boolean {
        val paginationHeader = response.header("Pagination")
        var hasNextPage = false
        if (!paginationHeader.isNullOrEmpty()) {
            val paginationInfo = json.decodeFromString<PaginationInfo>(paginationHeader)
            hasNextPage = paginationInfo.currentPage + 1 < paginationInfo.totalPages
        }
        return hasNextPage
    }

    fun getIdFromUrl(url: String): Int {
        return try {
            val id = url.substringAfterLast("/").toInt()
//            Log.d("KavitaHelper", "Extracted ID $id from URL: $url")
            id
        } catch (e: Exception) {
//            Log.e("KavitaHelper", "Failed to extract ID from URL: $url", e)
            -1
        }
    }

    //    Rating Providers from Series-Details-Plus
    private fun getProviderName(provider: Int): String = when (provider) {
        0 -> "User"
        1 -> "AniList"
        2 -> "MyAnimeList"
        3 -> "MangaUpdates"
        else -> "Rating"
    }

    fun createSeriesDto(obj: SeriesDto, baseUrl: String, apiUrl: String, apiKey: String): SManga =
        SManga.create().apply {
//             url = "$baseUrl/library/${obj.libraryId}/series/${obj.id}"
            url = "$baseUrl/Series/${obj.id}"
            title = obj.name
            thumbnail_url = "$baseUrl/image/series-cover?seriesId=${obj.id}&apiKey=$apiKey"
        }

    fun chapterFromVolume(chapter: ChapterDto, volume: VolumeDto, singleFileVolumeNumber: Int? = null, libraryType: LibraryTypeEnum? = null): SChapter =
        SChapter.create().apply {
            val type = ChapterType.of(chapter, volume, libraryType)
            val titleName = chapter.titleName ?: ""
            val title = chapter.title ?: ""
            val range = chapter.range ?: ""

            name = when (type) {
                ChapterType.Regular -> {
                    val chapterNum = chapter.number.toIntOrNull()?.let {
                        it.toString().padStart(2, '0')
                    } ?: chapter.number
                    when {
                        titleName.any { it.isLetter() } -> "$chapterNum - $titleName"
                        else -> "Vol.${volume.minNumber} Ch.$chapterNum"
                    }
                }
                ChapterType.SingleFileVolume -> {
                    when {
                        volume.name.any { it.isLetter() } -> "v${volume.minNumber} - ${volume.name}"
                        else -> "Volume ${volume.minNumber}"
                    }
                }
                ChapterType.Special -> {
                    when {
                        title.isNotBlank() -> title
                        range.isNotBlank() -> range
                        else -> "Special"
                    }
                }
                ChapterType.Chapter -> {
                    val chapterNum = chapter.number.toIntOrNull()?.let {
                        it.toString().padStart(2, '0')
                    } ?: chapter.number
                    when {
                        titleName.any { it.isLetter() } -> "$chapterNum - $titleName"
                        else -> "Chapter $chapterNum"
                    }
                }
                ChapterType.Issue -> {
                    val issueNum = chapter.number.toIntOrNull()?.let {
                        it.toString().padStart(3, '0')
                    } ?: chapter.number
                    when {
                        titleName.any { it.isLetter() } -> "$titleName (#$issueNum)"
                        else -> "Issue #$issueNum"
                    }
                }
            }

            chapter_number = when {
                type == ChapterType.SingleFileVolume && singleFileVolumeNumber != null ->
                    singleFileVolumeNumber.toFloat()

                type == ChapterType.SingleFileVolume -> {
                    // For standalone volumes, use positive numbers
                    volume.minNumber.toFloat()
                }

                // If this is a regular chapter (not a volume)
                type != ChapterType.SingleFileVolume ->
                    chapter.number.toFloatOrNull() ?: 0f

                // For volumes in mixed content, place them below chapter 0
                else -> {
                    val volumeNum = try {
                        volume.minNumber.toString().toFloatOrNull() ?: 0f
                    } catch (e: NumberFormatException) {
                        0f
                    }
                    -volumeNum - VOLUME_NUMBER_OFFSET
                }
            }

            url = when {
                type == ChapterType.SingleFileVolume && singleFileVolumeNumber != null ->
                    "volume_${volume.id}"
                else -> chapter.id.toString()
            }

            if (chapter.fileCount > 1) {
                chapter_number += 0.001f * chapter.fileCount
                url = "${url}_${chapter.fileCount}"
            }

            date_upload = parseDateSafe(chapter.created)
            scanlator = when (type) {
                ChapterType.SingleFileVolume -> "Volume"
                ChapterType.Special -> "Special"
                ChapterType.Issue -> "Issue"
                ChapterType.Chapter -> "Chapter"
                ChapterType.Regular -> "Chapter"
            }
        }

    val intl = Intl(
        language = Locale.getDefault().toString(),
        baseLanguage = "en",
        availableLanguages = KavitaInt.AVAILABLE_LANGS,
        classLoader = this::class.java.classLoader!!,
        createMessageFileName = { lang ->
            when (lang) {
                KavitaInt.SPANISH_LATAM -> Intl.createDefaultMessageFileName(KavitaInt.SPANISH)
                else -> Intl.createDefaultMessageFileName(lang)
            }
        },
    )

    companion object {
        private const val VOLUME_NUMBER_OFFSET = 100000f
    }
}
