package eu.kanade.tachiyomi.extension.all.kavita

import android.annotation.SuppressLint
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
import java.util.Locale

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

    //  @todo Rating Providers from Series-Details-Plus
//    private fun getProviderName(provider: Int): String = when (provider) {
//        0 -> "User"
//        1 -> "AniList"
//        2 -> "MyAnimeList"
//        3 -> "MangaUpdates"
//        else -> "Rating"
//    }

    fun createSeriesDto(obj: SeriesDto, baseUrl: String, apiKey: String): SManga =
        SManga.create().apply {
            url = "$baseUrl/Series/${obj.id}"
            title = obj.name
            thumbnail_url = "$baseUrl/image/series-cover?seriesId=${obj.id}&apiKey=$apiKey"
        }

    fun chapterFromVolume(
        chapter: ChapterDto,
        volume: VolumeDto,
        singleFileVolumeNumber: Double? = null,
        libraryType: LibraryTypeEnum? = null,
        isWebtoon: Boolean = false,
        mangaTitle: String = "",
    ): SChapter =
        SChapter.create().apply {
            val type = ChapterType.of(chapter, volume, libraryType)
            val titleName = chapter.titleName
            val title = chapter.title
            val range = chapter.range
            // For webtoon
            val chapterLabel = if (isWebtoon) "Episode" else "Chapter"
            val volumeLabel = if (isWebtoon) "Season" else "Volume"

            if (titleName != null) {
                name = when (type) {
                    ChapterType.Regular -> {
                        val chapterNum = formatChapterNumber(chapter)
                        when {
                            titleName.isNotBlank() && titleName.any { it.isLetter() } ->
                                "$chapterNum - ${cleanChapterTitle(
                                    titleName,
                                    ChapterTitleContext(
                                        mangaTitle = mangaTitle,
                                        chapterNumber = chapterNum,
                                        volumeName = volume.name.ifBlank { title },
                                    ),
                                )}"

                            else ->
                                "$volumeLabel ${formatVolumeNumber(volume)} $chapterLabel $chapterNum"
                        }
                    }

                    ChapterType.SingleFileVolume -> {
                        val volumeNumber = formatVolumeNumber(volume)
                        val cleanVolumeName = volume.name.trim()

                        // Always use the API's volume number, never parse from title
                        when {
                            // If name is empty or just numbers (Kavita default)
                            cleanVolumeName.isEmpty() || cleanVolumeName.none { it.isLetter() } -> {
                                cleanChapterTitle("Volume $volumeNumber")
                            }
                            // If name already contains volume info (Vol. 3, V3, etc.)
                            cleanVolumeName.contains(Regex("(?i)(vol|volume|v)[.\\s]*\\d+")) -> {
                                cleanChapterTitle(cleanVolumeName)
                            }
                            // Normal case - prefix with volume number
                            else -> {
                                cleanChapterTitle("$volumeNumber - $cleanVolumeName")
                            }
                        }
                    }

                    ChapterType.Special -> {
                        cleanChapterTitle(
                            when {
                                title.isNotBlank() -> title
                                range.isNotBlank() -> range
                                else -> "Special"
                            },
                            ChapterTitleContext(
                                mangaTitle = mangaTitle,
                                volumeName = volume.name,
                            ),
                        )
                    }

                    ChapterType.Chapter -> {
                        val chapterNum = formatChapterNumber(chapter)
                        when {
                            titleName.isNotBlank() && titleName.any { it.isLetter() } ->
                                "$chapterNum - ${cleanChapterTitle(
                                    titleName,
                                    ChapterTitleContext(
                                        mangaTitle = mangaTitle,
                                        chapterNumber = chapterNum,
                                        volumeName = volume.name,
                                    ),
                                )}"

                            else ->
                                "$chapterLabel $chapterNum"
                        }
                    }

                    ChapterType.Issue -> {
                        val issueNum = chapter.number.toIntOrNull()?.toString()?.padStart(3, '0') ?: chapter.number
                        cleanChapterTitle(
                            when {
                                titleName.any { it.isLetter() } -> "$titleName (#$issueNum)"
                                else -> "Issue #$issueNum"
                            },
                            ChapterTitleContext(
                                mangaTitle = mangaTitle,
                                chapterNumber = issueNum,
                                volumeName = volume.name,
                            ),
                        )
                    }
                }
            }

            // Handle decimal chapter numbers properly
            chapter_number = when {
                type == ChapterType.SingleFileVolume && singleFileVolumeNumber != null ->
                    volume.minNumber.toFloat()

                type == ChapterType.SingleFileVolume -> {
                    // For standalone volumes, use positive numbers
                    volume.minNumber.toFloat()
                }

                // For regular chapters
                type != ChapterType.SingleFileVolume && type != ChapterType.Special -> {
                    // Handle decimal chapter numbers
                    if (chapter.minNumber % 1 != 0.0) {
                        chapter.minNumber.toFloat()
                    } else {
                        chapter.minNumber.toInt().toFloat()
                    }
                }

                // For volumes/specials in mixed content, place them below chapter 0
                else -> {
                    val volumeNum = try {
                        if (volume.minNumber % 1 != 0.0) {
                            volume.minNumber.toFloat()
                        } else {
                            volume.minNumber.toInt().toFloat()
                        }
                    } catch (e: NumberFormatException) {
                        0f
                    }
                    volumeNum / KavitaConstants.VOLUME_NUMBER_OFFSET
                }
            }

            url = when {
                type == ChapterType.SingleFileVolume && singleFileVolumeNumber != null ->
                    "volume_${volume.id}"
                else -> "chapter_${chapter.id}"
            }

            if (chapter.fileCount > 1) {
                // salt/offset to recognize chapters with new merged part-chapters as new and hence unread
                chapter_number += 0.001f * chapter.fileCount
                url = "${url}_${chapter.fileCount}"
            }

            date_upload = parseDateSafe(chapter.created)
            scanlator = when (type) {
                ChapterType.SingleFileVolume -> if (isWebtoon) "Season" else "Volume"
                ChapterType.Special -> "Special"
                ChapterType.Issue -> "Issue"
                ChapterType.Chapter -> if (isWebtoon) "Episode" else "Chapter"
                ChapterType.Regular -> if (isWebtoon) "Episode" else "Chapter"
            }
        }

    private fun formatVolumeNumber(volume: VolumeDto): String {
        return when {
            volume.maxNumber > volume.minNumber ->
                "${removeTrailingZero(volume.minNumber)}-${removeTrailingZero(volume.maxNumber)}"
            else -> removeTrailingZero(volume.minNumber)
        }
    }

    private fun formatChapterNumber(chapter: ChapterDto, padLength: Int = 2): String {
        val chapterNum = when {
            chapter.maxNumber > chapter.minNumber ->
                "${removeTrailingZero(chapter.minNumber)}-${removeTrailingZero(chapter.maxNumber)}"
            else -> removeTrailingZero(chapter.minNumber)
        }

        // Only pad if it's a single whole number (not a range and not decimal)
        return if (!chapterNum.contains('-') && !chapterNum.contains('.')) {
            chapterNum.toIntOrNull()?.toString()?.padStart(padLength, '0') ?: chapterNum
        } else {
            chapterNum
        }
    }

    // Helper function to remove .0 from whole numbers while preserving actual decimals
    @SuppressLint("DefaultLocale")
    private fun removeTrailingZero(number: Double): String {
        return if (number % 1 == 0.0) {
            number.toInt().toString()
        } else {
            // Use String.format to avoid scientific notation for very small decimals
            String.format("%.10f", number).replace(",", ".").trimEnd('0').trimEnd('.')
        }
    }

    private fun cleanChapterTitle(
        originalTitle: String,
        context: ChapterTitleContext? = null,
    ): String {
        var title = originalTitle.trim()
        val mangaTitle = context?.mangaTitle ?: ""

        // Remove manga name if present
        if (mangaTitle.isNotBlank()) {
            title = title.replace(Regex("(?i)\\b${Regex.escape(mangaTitle)}\\b[\\s\\-:]*"), "").trim()
        }

        // Remove chapter number patterns like c0116, c001, etc.
        title = title.replace(Regex("""(?i)\bc\d+\b"""), "").trim()

        // Clean up problematic hyphen/space patterns
        title = title.replace(Regex("""\s*[-.]\s*[-.]\s*"""), " - ") // Handles -.-, - . -, etc.
            .replace(Regex("""\s*-\s*-\s*"""), " - ") // Double hyphens with spaces
            .replace(Regex("""^\s*[-.]\s*|\s*[-.]\s*$"""), "") // Leading/trailing hyphens or dots
            .trim()

        return title.ifBlank { originalTitle }
    }

    /**
     * Data class to hold context information for cleaning chapter titles
     */
    data class ChapterTitleContext(
        val mangaTitle: String = "",
        val chapterNumber: String = "",
        val volumeName: String = "",
        val isWebtoon: Boolean = false,
    )

    fun isWebtoonOrLongStrip(tags: List<String>): Boolean {
        return tags.any {
            val normalized = it.trim().lowercase()
            normalized.contains("webtoon") || normalized.contains("long strip")
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
