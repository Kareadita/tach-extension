package eu.kanade.tachiyomi.extension.all.kavita

import android.util.Log
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
            hasNextPage = paginationInfo.currentPage + 1 > paginationInfo.totalPages
        }
        return !hasNextPage
    }

    fun getIdFromUrl(url: String): Int {
        return try {
            val id = url.substringAfterLast("/").toInt()
            Log.d("KavitaHelper", "Extracted ID $id from URL: $url")
            id
        } catch (e: Exception) {
            Log.e("KavitaHelper", "Failed to extract ID from URL: $url", e)
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
            // url = "$baseUrl/library/${obj.libraryId}/series/${obj.id}"
            url = "$baseUrl/Series/${obj.id}"
            title = obj.name
            thumbnail_url = "$baseUrl/image/series-cover?seriesId=${obj.id}&apiKey=$apiKey"

            // Set status based on read progress
            status = when {
                obj.pagesRead >= obj.pages -> SManga.COMPLETED
                obj.pagesRead > 0 -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }

//    class CompareChapters {
//        companion object : Comparator<SChapter> {
//            override fun compare(a: SChapter, b: SChapter): Int {
//                if (a.chapter_number < 1.0 && b.chapter_number < 1.0) {
//                    // Both are volumes, multiply by 100 and do normal sort
//                    return if ((a.chapter_number * 100) < (b.chapter_number * 100)) {
//                        1
//                    } else {
//                        -1
//                    }
//                } else {
//                    if (a.chapter_number < 1.0 && b.chapter_number >= 1.0) {
//                        // A is volume, b is not. A should sort first
//                        return 1
//                    } else if (a.chapter_number >= 1.0 && b.chapter_number < 1.0) {
//                        return -1
//                    }
//                }
//                if (a.chapter_number < b.chapter_number) return 1
//                if (a.chapter_number > b.chapter_number) return -1
//                return 0
//            }
//        }
//    }

    fun chapterFromVolume(chapter: ChapterDto, volume: VolumeDto, singleFileVolumeNumber: Int? = null, libraryType: LibraryTypeEnum? = null): SChapter =
        SChapter.create().apply {
            val type = ChapterType.of(chapter, volume, libraryType)

            name = when (type) {
                ChapterType.Regular -> {
                    val volumeNum = volume.number.toString().toIntOrNull()?.let {
                        it.toString().padStart(2, '0')
                    } ?: volume.number.toString()
                    val chapterNum = chapter.number.toIntOrNull()?.let {
                        it.toString().padStart(2, '0')
                    } ?: chapter.number
                    when {
                        chapter.titleName.isBlank() -> "Volume $volumeNum Chapter $chapterNum"
                        chapter.titleName.trim().matches(Regex("^\\d+$")) -> "Volume $volumeNum Chapter ${chapter.titleName.trim().padStart(2, '0')}"
                        else -> "Volume $volumeNum $chapterNum - ${chapter.titleName}"
                    }
                }
                ChapterType.SingleFileVolume -> {
                    when {
                        volume.name.isBlank() -> "Volume ${volume.number}"
                        volume.name.trim().matches(Regex("^\\d+$")) -> "Volume ${volume.name.trim().toIntOrNull()?.toString() ?: volume.name.trim()}"
                        else -> "${volume.number} - ${volume.name}"
                    }
                }
                ChapterType.Special -> chapter.titleName.takeIf { it.isNotBlank() } ?: chapter.range
                ChapterType.Chapter -> {
                    val chapterNum = chapter.number.toIntOrNull()?.let {
                        it.toString().padStart(2, '0')
                    } ?: chapter.number
                    when {
                        chapter.titleName.isBlank() -> "Chapter $chapterNum"
                        chapter.titleName.trim().matches(Regex("^\\d+$")) -> "Chapter ${chapter.titleName.trim().padStart(2, '0')}"
                        else -> "$chapterNum - ${chapter.titleName}"
                    }
                }
                ChapterType.Issue -> {
                    val issueNum = chapter.number.toIntOrNull()?.let {
                        it.toString().padStart(3, '0')
                    } ?: chapter.number
                    when {
                        chapter.titleName.isNotBlank() && !chapter.titleName.trim().matches(Regex("^\\d+$")) ->
                            "#$issueNum ${chapter.titleName}"
                        chapter.title.isNotBlank() && !chapter.title.trim().matches(Regex("^\\d+$")) ->
                            "#$issueNum ${chapter.title}"
                        else -> "Issue #$issueNum"
                    }
                }
            }

//            chapter_number = try {
// //                if (type == ChapterType.SingleFileVolume) {
// //                    volume.number.toFloat() / 10000
// //                } else {
// //                    chapter.number.toFloatOrNull() ?: 0f
// //                }
// //            } catch (e: NumberFormatException) {
// //                0f
// //            }

            chapter_number = when {
                // If this is a SingleFileVolume and we have an explicit number (from single-file processing)
                type == ChapterType.SingleFileVolume && singleFileVolumeNumber != null ->
                    singleFileVolumeNumber.toFloat()

                // If this is a regular chapter (not a volume)
                type != ChapterType.SingleFileVolume ->
                    chapter.number.toFloatOrNull() ?: 0f

                // For volumes in mixed content, place them below chapter 0
                else -> {
                    val volumeNum = try {
                        volume.number.toString().toFloatOrNull() ?: 0f
                    } catch (e: NumberFormatException) {
                        0f
                    }
                    -volumeNum - 100000f
                }
            }

            //            url = chapter.id.toString()

            if (chapter.fileCount > 1) {
                // salt/offset to recognize chapters with new merged part-chapters as new and hence unread
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
}
