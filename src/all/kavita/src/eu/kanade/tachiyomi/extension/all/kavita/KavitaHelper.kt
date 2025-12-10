package eu.kanade.tachiyomi.extension.all.kavita

import android.annotation.SuppressLint
import android.util.Log
import eu.kanade.tachiyomi.extension.all.kavita.dto.ChapterDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.ChapterType
import eu.kanade.tachiyomi.extension.all.kavita.dto.LibraryTypeEnum
import eu.kanade.tachiyomi.extension.all.kavita.dto.PaginationInfo
import eu.kanade.tachiyomi.extension.all.kavita.dto.SeriesDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.VolumeDto
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

    // Cache for series information
    private val seriesCache = mutableMapOf<Int, SeriesDto>()

    // Inner class to provide access to series info
    inner class SeriesMapper {
        fun addSeries(series: SeriesDto) {
            seriesCache[series.id] = series
        }

        fun getSeries(id: Int): SeriesDto? = seriesCache[id]

        fun clear() = seriesCache.clear()
    }

    val seriesMap = SeriesMapper()

    fun hasNextPage(response: Response): Boolean {
        val paginationHeader = response.header("Pagination")
        var hasNextPage = false
        if (!paginationHeader.isNullOrEmpty()) {
            val paginationInfo = json.decodeFromString<PaginationInfo>(paginationHeader)
            hasNextPage = paginationInfo.currentPage < paginationInfo.totalPages
        }
        return hasNextPage
    }

    fun getIdFromUrl(url: String): Int {
        return try {
            val lastSegment = url.substringAfterLast("/")
            val cleaned = lastSegment
                .substringBefore("?")
                .substringBefore("#")
                .substringBefore("/")
            cleaned.toInt()
        } catch (e: Exception) {
            Log.e("KavitaHelper", "Failed to extract ID from URL: $url", e)
            -1
        }
    }

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
        useReleaseDate: Boolean = false,
        chapterTitleFormat: String = "\$CleanTitle",
        scanlatorFormat: String = "\$Type",
        volumePageCount: Int? = null,
    ): SChapter =
        SChapter.create().apply {
            val type = ChapterType.of(chapter, volume, libraryType)
            val titleName = chapter.titleName ?: ""
            val title = chapter.title
            val range = chapter.range

            // Always ensure we have the series name, even if mangaTitle is blank
            val seriesName = mangaTitle.ifBlank {
                seriesMap.getSeries(volume.seriesId)?.name ?: ""
            }

            // Debug logging
            Log.d("KavitaHelper", "Chapter ${chapter.id}: seriesName = '$seriesName', mangaTitle = '$mangaTitle'")

            name = when (type) {
                ChapterType.Regular -> {
                    val chapterNum = formatChapterNumber(chapter)
                    val volNum = formatVolumeNumber(volume)
                    val cleanedTitle = cleanChapterTitle(
                        titleName,
                        ChapterTitleContext(
                            mangaTitle = mangaTitle,
                            chapterNumber = chapterNum,
                            volumeNumber = volNum,
                            volumeName = volume.name.ifBlank { title },
                            isWebtoon = isWebtoon,
                        ),
                    )
                    val finalCleanTitle = cleanedTitle.ifBlank {
                        defaultCleanTitle(type, chapterNum, volNum, isWebtoon)
                    }

                    val variables = ChapterTemplateVariables(
                        type = if (isWebtoon) "Episode" else "Chapter",
                        number = chapterNum,
                        title = titleName,
                        pages = chapter.pages,
                        fileSize = chapter.files?.sumOf { it.bytes }?.toDouble() ?: 0.0,
                        volumeNumber = volNum,
                        cleanTitle = finalCleanTitle,
                        seriesName = seriesName, // SeriesName should NOT be processed through cleanChapterTitle
                        libraryName = volume.let { v ->
                            seriesMap.getSeries(v.seriesId)?.libraryName ?: ""
                        },
                        formats = chapter.files?.firstOrNull()?.extension?.uppercase() ?: "",
                        created = chapter.created,
                        releaseDate = chapter.releaseDate,
                    )

                    // Debug logging for Chapter Title Format variables
                    Log.d("KavitaHelper", "ChapterTitleFormat variables for chapter ${chapter.id}:")
                    Log.d("KavitaHelper", "  chapterTitleFormat: '$chapterTitleFormat'")
                    Log.d("KavitaHelper", "  seriesName: '${variables.seriesName}'")
                    Log.d("KavitaHelper", "  libraryName: '${variables.libraryName}'")
                    Log.d("KavitaHelper", "  title: '${variables.title}'")
                    Log.d("KavitaHelper", "  cleanTitle: '${variables.cleanTitle}'")

                    val processedName = processChapterTemplate(chapterTitleFormat, variables)
                    Log.d("KavitaHelper", "Final processed chapter name: '$processedName'")
                    processedName
                }

                ChapterType.SingleFileVolume -> {
                    val volumeNumber = formatVolumeNumber(volume)

                    val variables = ChapterTemplateVariables(
                        type = if (isWebtoon) "Season" else "Volume",
                        number = volumeNumber,
                        title = volume.name,
                        pages = volume.pages,
                        fileSize = volume.chapters.flatMap { it.files ?: emptyList() }.sumOf { it.bytes }.toDouble(),
                        volumeNumber = volumeNumber,
                        cleanTitle = when {
                            volume.name.isNotBlank() && !volume.name.matches(Regex("^\\d+$")) -> volume.name
                            volume.name.isNotBlank() && volume.name.matches(Regex("^\\d+$")) -> {
                                // Handle case where volume name is only a number (e.g., "2")
                                if (isWebtoon) {
                                    val volNum = volume.name.toIntOrNull()?.toString() ?: volume.name
                                    "Season $volNum"
                                } else {
                                    val volNum = volume.name.toIntOrNull()?.toString() ?: volume.name
                                    "Volume $volNum"
                                }
                            }
                            else -> {
                                if (isWebtoon) "Season $volumeNumber" else "Volume $volumeNumber"
                            }
                        },
                        seriesName = seriesName,
                        libraryName = seriesMap.getSeries(volume.seriesId)?.libraryName ?: "",
                        formats = volume.chapters.flatMap { it.files ?: emptyList() }.firstOrNull()?.extension?.uppercase() ?: "",
                        created = volume.created,
                        releaseDate = "",
                    )

                    val processedName = processChapterTemplate(chapterTitleFormat, variables)
                    Log.d("KavitaHelper", "Final processed SFV name: '$processedName'")
                    processedName
                }

                ChapterType.Special -> {
                    val specialTitle = when {
                        title.isNotBlank() -> title
                        range.isNotBlank() -> range
                        else -> "Special"
                    }

                    val variables = ChapterTemplateVariables(
                        type = "Special",
                        number = "",
                        title = specialTitle,
                        pages = chapter.pages,
                        fileSize = chapter.files?.sumOf { it.bytes }?.toDouble() ?: 0.0,
                        volumeNumber = formatVolumeNumber(volume),
                        cleanTitle = when {
                            titleName.isNotBlank() && !titleName.matches(Regex("^\\d+$")) -> titleName
                            titleName.isNotBlank() && titleName.matches(Regex("^\\d+$")) -> {
                                // Handle case where titleName is only a number (e.g., "2")
                                val num = titleName.toIntOrNull()?.toString()?.padStart(2, '0') ?: titleName
                                "Special $num"
                            }
                            title.isNotBlank() && !title.matches(Regex("^\\d+$")) -> title
                            title.isNotBlank() && title.matches(Regex("^\\d+$")) -> {
                                // Handle case where title is only a number (e.g., "2")
                                val num = title.toIntOrNull()?.toString()?.padStart(2, '0') ?: title
                                "Special $num"
                            }
                            range.isNotBlank() && !range.matches(Regex("^\\d+$")) -> range
                            range.isNotBlank() && range.matches(Regex("^\\d+$")) -> {
                                // Handle case where range is only a number (e.g., "2")
                                val num = range.toIntOrNull()?.toString()?.padStart(2, '0') ?: range
                                "Special $num"
                            }
                            else -> "Special"
                        },
                        seriesName = seriesName,
                        libraryName = seriesMap.getSeries(volume.seriesId)?.libraryName ?: "",
                        formats = chapter.files?.firstOrNull()?.extension?.uppercase() ?: "",
                        created = chapter.created,
                        releaseDate = chapter.releaseDate,
                    )

                    val processedName = processChapterTemplate(chapterTitleFormat, variables)
                    Log.d("KavitaHelper", "Final processed Special name: '$processedName'")
                    processedName
                }

                ChapterType.Chapter -> {
                    val chapterNum = formatChapterNumber(chapter)
                    val cleanedTitle = cleanChapterTitle(
                        titleName,
                        ChapterTitleContext(
                            mangaTitle = mangaTitle,
                            chapterNumber = chapterNum,
                            volumeName = volume.name,
                            isWebtoon = isWebtoon,
                        ),
                    )
                    val finalCleanTitle = cleanedTitle.ifBlank {
                        defaultCleanTitle(type, chapterNum, formatVolumeNumber(volume), isWebtoon)
                    }

                    val variables = ChapterTemplateVariables(
                        type = if (isWebtoon) "Episode" else "Chapter",
                        number = chapterNum,
                        title = titleName,
                        pages = chapter.pages,
                        fileSize = chapter.files?.sumOf { it.bytes }?.toDouble() ?: 0.0,
                        volumeNumber = "",
                        cleanTitle = finalCleanTitle,
                        seriesName = seriesName,
                        libraryName = seriesMap.getSeries(volume.seriesId)?.libraryName ?: "",
                        formats = chapter.files?.firstOrNull()?.extension?.uppercase() ?: "",
                        created = chapter.created,
                        releaseDate = chapter.releaseDate,
                    )

                    val processedName = processChapterTemplate(chapterTitleFormat, variables)
                    Log.d("KavitaHelper", "Final processed Chapter name: '$processedName'")
                    processedName
                }

                ChapterType.Issue -> {
                    val issueNum = chapter.number.toIntOrNull()?.toString()?.padStart(3, '0') ?: chapter.number

                    val variables = ChapterTemplateVariables(
                        type = "Issue",
                        number = issueNum,
                        title = titleName,
                        pages = chapter.pages,
                        fileSize = chapter.files?.sumOf { it.bytes }?.toDouble() ?: 0.0,
                        volumeNumber = formatVolumeNumber(volume),
                        cleanTitle = when {
                            titleName.isNotBlank() && !titleName.matches(Regex("^\\d+$")) && titleName.any { it.isLetter() } -> titleName
                            titleName.isNotBlank() && titleName.matches(Regex("^\\d+$")) -> {
                                // Handle case where titleName is only a number (e.g., "2")
                                val num = titleName.toIntOrNull()?.toString()?.padStart(3, '0') ?: titleName
                                "Issue #$num"
                            }
                            else -> "Issue #$issueNum"
                        }.ifBlank {
                            defaultCleanTitle(type, issueNum, formatVolumeNumber(volume), isWebtoon)
                        },
                        seriesName = seriesName,
                        libraryName = seriesMap.getSeries(volume.seriesId)?.libraryName ?: "",
                        formats = chapter.files?.firstOrNull()?.extension?.uppercase() ?: "",
                        created = chapter.created,
                        releaseDate = chapter.releaseDate,
                    )

                    val processedName = processChapterTemplate(chapterTitleFormat, variables)
                    Log.d("KavitaHelper", "Final processed Issue name: '$processedName'")
                    processedName
                }
            }

            chapter_number = when {
                // Regular, Chapter, Issue (1.0, 2.0...)
                type == ChapterType.Regular ||
                    type == ChapterType.Chapter ||
                    type == ChapterType.Issue -> {
                    if (chapter.minNumber % 1 != 0.0) {
                        chapter.minNumber.toFloat()
                    } else {
                        chapter.minNumber.toInt().toFloat()
                    }
                }

                // Volumes and Specials (< 1.0)
                else -> {
                    val rawNum = try {
                        if (volume.minNumber % 1 != 0.0) {
                            volume.minNumber.toFloat()
                        } else {
                            volume.minNumber.toInt().toFloat()
                        }
                    } catch (e: NumberFormatException) {
                        0f
                    }

                    when (type) {
                        // Volume 1 -> 0.0001
                        ChapterType.SingleFileVolume -> rawNum / KavitaConstants.VOLUME_NUMBER_OFFSET
                        // Special 100k -> 0.00001
                        ChapterType.Special -> rawNum / KavitaConstants.SPECIAL_NUMBER_OFFSET
                        else -> rawNum
                    }
                }
            }

            url = "/Chapter/${chapter.id}"

            // Only apply salt to Chapters.
            // DO NOT apply to Volume or Special as it corrupts the 0.0001/0.00001 sorting logic.
            if (chapter.fileCount > 1 &&
                (type == ChapterType.Regular || type == ChapterType.Chapter || type == ChapterType.Issue)
            ) {
                chapter_number += 0.001f * chapter.fileCount
                url = "$url?split=${chapter.fileCount}"
            }

            date_upload = if (useReleaseDate && chapter.releaseDate.isNotBlank()) {
                parseDateSafe(chapter.releaseDate)
            } else {
                parseDateSafe(chapter.created)
            }

            // Prepare template variables for scanlator
            val scanlatorVariables = ChapterTemplateVariables(
                type = when (type) {
                    ChapterType.SingleFileVolume -> if (isWebtoon) "Season" else "Volume"
                    ChapterType.Special -> "Special"
                    ChapterType.Issue -> "Issue"
                    ChapterType.Chapter -> if (isWebtoon) "Episode" else "Chapter"
                    ChapterType.Regular -> if (isWebtoon) "Episode" else "Chapter"
                },
                number = when (type) {
                    ChapterType.Regular, ChapterType.Chapter, ChapterType.Issue -> formatChapterNumber(chapter)
                    ChapterType.SingleFileVolume -> formatVolumeNumber(volume)
                    else -> ""
                },
                title = titleName,
                pages = if (singleFileVolumeNumber != null && volumePageCount != null) {
                    volumePageCount
                } else {
                    chapter.pages
                },
                fileSize = if (singleFileVolumeNumber != null) {
                    volume.chapters.flatMap { it.files ?: emptyList() }.sumOf { it.bytes }.toDouble()
                } else {
                    chapter.files?.sumOf { it.bytes }?.toDouble() ?: 0.0
                },
                volumeNumber = formatVolumeNumber(volume),
                cleanTitle = when {
                    titleName.isNotBlank() && !titleName.matches(Regex("^\\d+$")) -> titleName
                    titleName.isNotBlank() && titleName.matches(Regex("^\\d+$")) -> {
                        // Handle numeric titles
                        when (type) {
                            ChapterType.Issue -> {
                                val num = titleName.toIntOrNull()?.toString()?.padStart(3, '0') ?: titleName
                                "Issue #$num"
                            }
                            else -> {
                                val num = titleName.toIntOrNull()?.toString()?.padStart(2, '0') ?: titleName
                                if (isWebtoon) "Episode $num" else "Chapter $num"
                            }
                        }
                    }
                    else -> {
                        // Generate fallback based on chapter type and volume info
                        val chNum = when (type) {
                            ChapterType.Regular, ChapterType.Chapter -> formatChapterNumber(chapter)
                            ChapterType.Issue -> chapter.number.toIntOrNull()?.toString()?.padStart(3, '0') ?: chapter.number
                            else -> ""
                        }
                        val volNum = formatVolumeNumber(volume)
                        when {
                            isWebtoon && volNum != "0" -> "Season $volNum Episode $chNum"
                            isWebtoon -> "Episode $chNum"
                            volNum != "0" -> "Volume $volNum Chapter $chNum"
                            else -> "Chapter $chNum"
                        }
                    }
                },
                seriesName = seriesName,
                libraryName = volume.let { v ->
                    seriesMap.getSeries(v.seriesId)?.libraryName ?: ""
                },
                formats = if (singleFileVolumeNumber != null) {
                    volume.chapters.flatMap { it.files ?: emptyList() }.firstOrNull()?.extension?.uppercase() ?: ""
                } else {
                    chapter.files?.firstOrNull()?.extension?.uppercase() ?: ""
                },
                created = chapter.created,
                releaseDate = chapter.releaseDate,
            )

            // Debug logging for Scanlator Format variables
            Log.d("KavitaHelper", "ScanlatorFormat variables for chapter ${chapter.id}:")
            Log.d("KavitaHelper", "  scanlatorFormat: '$scanlatorFormat'")
            Log.d("KavitaHelper", "  seriesName: '${scanlatorVariables.seriesName}'")
            Log.d("KavitaHelper", "  libraryName: '${scanlatorVariables.libraryName}'")
            Log.d("KavitaHelper", "  title: '${scanlatorVariables.title}'")
            Log.d("KavitaHelper", "  cleanTitle: '${scanlatorVariables.cleanTitle}'")

            scanlator = processChapterTemplate(scanlatorFormat, scanlatorVariables)
        }

    internal fun formatVolumeNumber(volume: VolumeDto): String {
        return when {
            volume.maxNumber > volume.minNumber ->
                "${removeTrailingZero(volume.minNumber)}-${removeTrailingZero(volume.maxNumber)}"
            else -> removeTrailingZero(volume.minNumber)
        }
    }

    internal fun formatChapterNumber(chapter: ChapterDto, padLength: Int = 2): String {
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
    internal fun removeTrailingZero(number: Double): String {
        return if (number % 1 == 0.0) {
            number.toInt().toString()
        } else {
            // Use String.format to avoid scientific notation for very small decimals
            String.format("%.10f", number).replace(",", ".").trimEnd('0').trimEnd('.')
        }
    }

    internal fun cleanChapterTitle(
        originalTitle: String,
        context: ChapterTitleContext? = null,
    ): String {
        var title = originalTitle.trim()
        val mangaTitle = context?.mangaTitle ?: ""
        val isWebtoon = context?.isWebtoon ?: false

        // Don't modify titles that start with a number followed by a pattern like "12. It's my fate"
        if (title.matches(Regex("^\\d+\\.\\s+.*"))) {
            return title
        }

        // Remove manga name if present
        if (mangaTitle.isNotBlank()) {
            title = title.replace(Regex("(?i)\\b${Regex.escape(mangaTitle)}\\b[\\s\\-:]*"), "").trim()
        }

        // Helper function to check if we have to shorten terms
        fun hasMoreContent(currentTitle: String, match: MatchResult): Boolean {
            val beforeMatch = currentTitle.take(match.range.first).trim()
            val afterMatch = currentTitle.substring(match.range.last + 1).trim()
            return (beforeMatch.isNotEmpty() && beforeMatch.any { it.isLetterOrDigit() }) ||
                (afterMatch.isNotEmpty() && afterMatch.any { it.isLetterOrDigit() })
        }

        // Process patterns with conditional shortening
        title = title.replace(Regex("""(?i)\bvolume\s+(\d+)""")) { match ->
            val number = match.groupValues[1].padStart(2, '0')
            if (isWebtoon) {
                if (hasMoreContent(title, match)) "S. $number" else "Season $number"
            } else {
                if (hasMoreContent(title, match)) "Vol. $number" else "Volume $number"
            }
        }

        title = title.replace(Regex("""(?i)\bvol\s+(\d+)""")) { match ->
            val number = match.groupValues[1].padStart(2, '0')
            if (isWebtoon) {
                if (hasMoreContent(title, match)) "S. $number" else "Season $number"
            } else {
                if (hasMoreContent(title, match)) "Vol. $number" else "Vol. $number"
            }
        }

        // Chapter/Episode handling
        title = title.replace(Regex("""(?i)\bchapter\s+(\d+)""")) { match ->
            val number = match.groupValues[1].padStart(2, '0')
            if (isWebtoon) {
                if (hasMoreContent(title, match)) "Ep. $number" else "Episode $number"
            } else {
                if (hasMoreContent(title, match)) "Ch. $number" else "Chapter $number"
            }
        }

        title = title.replace(Regex("""(?i)\bch\s+(\d+)""")) { match ->
            val number = match.groupValues[1].padStart(2, '0')
            val word = if (isWebtoon && hasMoreContent(title, match)) "Ep." else if (isWebtoon) "Episode" else "Ch."
            "$word $number"
        }

        title = title.replace(Regex("""(?i)\bepisode\s+(\d+)""")) { match ->
            val number = match.groupValues[1].padStart(2, '0')
            if (hasMoreContent(title, match)) "Ep. $number" else "Episode $number"
        }

        title = title.replace(Regex("""(?i)\bep\s+(\d+)""")) { match ->
            val number = match.groupValues[1].padStart(2, '0')
            if (hasMoreContent(title, match)) "Ep. $number" else "Ep. $number"
        }

        // cXXX pattern always shortened if more content
        title = title.replace(Regex("""(?i)\bc(\d+)\b""")) { match ->
            val number = match.groupValues[1].padStart(2, '0')
            val term = if (isWebtoon) "Ep." else "Ch."
            if (hasMoreContent(title, match)) "$term $number" else "c$number"
        }

        // Clean up spaces
        title = title.replace(Regex("""\s+"""), " ").trim()

        // Final check: If title is just "Ch. 08" or "Ep. 08" with no other content, expand it
        val justNumberedPattern = Regex("""^(Ch|Ep|Vol|S)\. (\d{2})$""", RegexOption.IGNORE_CASE)
        title = title.replace(justNumberedPattern) { match ->
            val type = match.groupValues[1].lowercase()
            val number = match.groupValues[2]
            when {
                isWebtoon -> {
                    when (type) {
                        "ep", "ch" -> "Episode ${number.toInt()}"
                        "vol", "s" -> "Season ${number.toInt()}"
                        else -> "Episode ${number.toInt()}"
                    }
                }
                else -> {
                    when (type) {
                        "ep" -> "Episode ${number.toInt()}"
                        "vol" -> "Volume ${number.toInt()}"
                        "s" -> "Season ${number.toInt()}"
                        else -> "Chapter ${number.toInt()}"
                    }
                }
            }
        }

        // Remove leading numbers if no other patterns present
        if (!title.contains(Regex("""(?i)(vol|volume|ch|chapter|ep|episode|c\d+)"""))) {
            title = title.replace(Regex("""^\d+(\s*-\s*)?"""), "").trim()
        }

        // If title becomes blank, return the original title to prevent empty titles
        val finalTitle = title.ifBlank { originalTitle }

        // Debug logging for troubleshooting
        if (finalTitle.isBlank() && originalTitle.isNotBlank()) {
            Log.d("KavitaHelper", "Warning: Title processing resulted in blank title for '$originalTitle'. Using original.")
        }

        return finalTitle
    }

    /**
     * Checks if a title contains chapter/episode/volume numbering patterns
     */
    internal fun hasNumberingPattern(title: String, isWebtoon: Boolean = false): Boolean {
        return title.contains(Regex("""(?i)(ch\.?\s*\d+|\bchapter\s+\d+|\bc\d+\b|\bep\.?\s*\d+|\bepisode\s+\d+)""")) ||
            title.contains(Regex("""(?i)(vol\.?\s*\d+|\bvolume\s+\d+)"""))
    }

    internal fun defaultCleanTitle(
        type: ChapterType,
        number: String,
        volumeNumber: String,
        isWebtoon: Boolean,
    ): String {
        val safeNumber = number.ifBlank { "01" }
        val safeVolume = volumeNumber.ifBlank { "0" }
        return when (type) {
            ChapterType.Regular -> {
                when {
                    isWebtoon -> "Episode $safeNumber"
                    safeVolume != "0" -> "Volume $safeVolume Chapter $safeNumber"
                    else -> "Chapter $safeNumber"
                }
            }
            ChapterType.Chapter -> {
                if (isWebtoon) "Episode $safeNumber" else "Chapter $safeNumber"
            }
            ChapterType.Issue -> "Issue #${safeNumber.padStart(3, '0')}"
            ChapterType.SingleFileVolume -> if (isWebtoon) "Season $safeVolume" else "Volume $safeVolume"
            ChapterType.Special -> "Special ${safeNumber.ifBlank { safeVolume.padStart(2, '0') }}"
        }
    }

    /**
     * Data class to hold context information for cleaning chapter titles
     */
    data class ChapterTitleContext(
        val mangaTitle: String = "",
        val chapterNumber: String = "",
        val volumeNumber: String = "",
        val volumeName: String = "",
        val isWebtoon: Boolean = false,
    )

    fun isWebtoonOrLongStrip(
        genres: List<String>,
        tags: List<String>,
        libraryName: String? = null,
        format: String? = null,
        demographic: String? = null,
        allTags: List<String>? = null,
    ): Boolean {
        val allFields = mutableListOf<String>().apply {
            addAll(genres)
            addAll(tags)
            libraryName?.let { add(it) }
            format?.let { add(it) }
            demographic?.let { add(it) }
            allTags?.let { addAll(it) }
        }

        return allFields.any { field ->
            val normalized = field.trim().lowercase()
            normalized.contains("webtoon") || normalized.contains("long strip")
        }
    }

    // Legacy compatibility
    fun isWebtoonOrLongStrip(tags: List<String>): Boolean {
        return isWebtoonOrLongStrip(
            genres = emptyList(),
            tags = tags,
            libraryName = null,
            format = null,
            demographic = null,
            allTags = null,
        )
    }

    /**
     * Extracts demographic and format information from genres and tags
     * @param genres List of genres
     * @param tags List of tags
     * @param format List of formats
     * @return Triple of found demographic (if any), and filtered lists
     */
    fun extractDemographicAndFormat(genres: List<String>, tags: List<String>): Triple<String?, List<String>, Pair<List<String>, List<String>>> {
        val demographicKeywords = listOf("Shounen", "Seinen", "Josei", "Shoujo", "Hentai", "Doujinshi")
        val formatKeywords = listOf("Long Strip", "4-koma", "4 Koma", "Full Color", "Full Colour", "Color", "Colour", "Graphic Novel", "Manga", "Manhua", "Manhwa")

        val foundDemographic = demographicKeywords.firstOrNull { demo ->
            genres.any { it.equals(demo, ignoreCase = true) } ||
                tags.any { it.equals(demo, ignoreCase = true) }
        }?.let { demo ->
            // Get the actual matched keyword with correct case
            genres.find { it.equals(demo, ignoreCase = true) }
                ?: tags.find { it.equals(demo, ignoreCase = true) }
        }

        // Find ALL matching formats, preserving original case
        val foundFormats = formatKeywords.mapNotNull { formats ->
            genres.find { it.equals(formats, ignoreCase = true) }
                ?: tags.find { it.equals(formats, ignoreCase = true) }
        }.distinct()

        val filteredGenres = genres.filterNot { genre ->
            genre.equals(foundDemographic, ignoreCase = true) ||
                foundFormats.any { it.equals(genre, ignoreCase = true) }
        }
        val filteredTags = tags
            .filterNot { tag ->
                tag.equals(foundDemographic, ignoreCase = true) ||
                    foundFormats.any { it.equals(tag, ignoreCase = true) }
            }
            .filterNot { tag -> filteredGenres.any { genre -> genre.equals(tag, ignoreCase = true) } }

        return Triple(foundDemographic, foundFormats, filteredGenres to filteredTags)
    }

    /**
     * Builds genre string based on user preferences
     * @param libraryName Library name for grouping
     * @param demographic Demographic information
     * @param formats Format information (list of all formats)
     * @param genres List of genres
     * @param tags List of tags
     * @param groupTags Whether to group tags with prefixes
     * @return Formatted genre string
     */
    fun buildGenreString(
        libraryName: String?,
        demographic: String?,
        formats: List<String>,
        genres: List<String>,
        tags: List<String>,
        groupTags: Boolean,
    ): String {
        return if (groupTags) {
            buildList {
                libraryName?.takeIf { it.isNotEmpty() }?.let { add("Type:$it") }
                demographic?.let { add("Demographic:$it") }
                formats.forEach { format ->
                    if (format.isNotBlank()) {
                        add("Formats:$format")
                    }
                }
                genres.forEach { add("Genres:$it") }
                tags.forEach { add("Tags:$it") }
            }.joinToString(", ")
        } else {
            (genres + tags + formats).toSet().toList().sorted().joinToString(", ")
        }
    }

    /**
     * Extracts demographic information from genres and tags (legacy compatibility)
     * @param genres List of genre titles
     * @param tags List of tag titles
     * @return Pair of found demographic (if any) and filtered lists
     */
    fun extractDemographic(genres: List<String>, tags: List<String>): Pair<String?, Pair<List<String>, List<String>>> {
        val (demographic, _, filteredPair) = extractDemographicAndFormat(genres, tags)
        return demographic to filteredPair
    }

    /**
     * Safely parses a string to Int with null safety
     */
    fun parseStringToIntSafely(value: String?): Int? {
        return value?.toIntOrNull()
    }

    /**
     * Safely extracts series ID from URL with null safety
     */
    fun extractSeriesIdFromUrl(url: String): Int? {
        return try {
            url.substringAfterLast("/").toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Safely parses date string with null safety
     */
    fun parseDateSafe(date: String?): Long {
        return date?.let {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(it)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        } ?: 0L
    }

    /**
     * Data class to hold template variables for chapter formatting
     */
    data class ChapterTemplateVariables(
        val type: String,
        val number: String,
        val title: String,
        val pages: Int,
        val fileSize: Double,
        val volumeNumber: String = "",
        val cleanTitle: String = "",
        val seriesName: String = "",
        val libraryName: String = "",
        val formats: String = "",
        val created: String = "",
        val releaseDate: String = "",
    )

    /**
     * Processes a template string with chapter variables
     * Available variables: $Type, $No, $Title, $CleanTitle, $Pages, $Size, $Volume, $SeriesName, $LibraryName, $Format, $Created, $ReleaseDate
     */
    fun processChapterTemplate(
        template: String,
        variables: ChapterTemplateVariables,
    ): String {
        if (template.isBlank()) return ""

        val fileSizeMB = if (variables.fileSize > 0) {
            "%.1f".format(variables.fileSize / (1024.0 * 1024.0))
        } else {
            ""
        }

        val formattedSize = if (fileSizeMB.isNotEmpty()) "$fileSizeMB MB" else ""

        // Create replacement map
        val replacements = mutableMapOf<String, String>()
        replacements["Type"] = variables.type
        replacements["No"] = variables.number
        replacements["Title"] = variables.title
        replacements["CleanTitle"] = variables.cleanTitle
        replacements["Pages"] = if (variables.pages > 0) variables.pages.toString() else ""
        replacements["Size"] = formattedSize
        replacements["Volume"] = variables.volumeNumber
        replacements["SeriesName"] = variables.seriesName.ifEmpty { "" }
        replacements["LibraryName"] = variables.libraryName
        replacements["Format"] = variables.formats
        replacements["Created"] = variables.created
        replacements["ReleaseDate"] = variables.releaseDate

        // Debug logging before processing
        Log.d("KavitaHelper", "Template processing - BEFORE: template='$template', seriesName='${variables.seriesName}', libraryName='${variables.libraryName}'")

        // Process the template
        var result = template

        // First replace escaped dollar signs with temporary placeholder
        result = result.replace("\\$", "\u0000TEMP_DOLLAR\u0000")

        // Replace all variables using regex for more reliable matching
        replacements.forEach { (key, value) ->
            // Fixed regex pattern - use negative lookahead to prevent partial matches
            val pattern = Regex("\\\$${Regex.escape(key)}(?!\\w)")
            val before = result
            result = result.replace(pattern, value)
            // Debug logging for all variables
            if (before != result) {
                Log.d("KavitaHelper", "Template processing: Replaced \$$key with: '$value'")
            }
        }

        // Remove any remaining unrecognized variables
        result = result.replace(Regex("\\$\\w+"), "")

        // Normalize whitespace
        result = result.replace(Regex("\\s+"), " ").trim()

        // Restore escaped dollar signs
        result = result.replace("\u0000TEMP_DOLLAR\u0000", "$")

        // Debug logging after processing
        Log.d("KavitaHelper", "Template processing - AFTER: result='$result'")

        // WORKAROUND: If the result starts with the series name, add a zero-width space to prevent truncation
        val finalResult = if (result.startsWith(variables.seriesName)) {
            "\u200B$result" // Zero-width space prevents UI truncation
        } else {
            result
        }

        if (finalResult != result) {
            Log.d("KavitaHelper", "Template processing - WORKAROUND applied (zero-width space)")
        }

        return finalResult
    }
}
