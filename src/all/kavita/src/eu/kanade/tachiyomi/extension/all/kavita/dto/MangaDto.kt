package eu.kanade.tachiyomi.extension.all.kavita.dto

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface ConvertibleToSManga {
    fun toSManga(baseUrl: String, apiUrl: String, apiKey: String): SManga
}

@Serializable
enum class MangaFormat(val format: Int) {
    Image(0),
    Archive(1),
    Unknown(2),
    Epub(3),
    Pdf(4),
    ;

    companion object {
        private val map = values().associateBy(MangaFormat::format)
        fun fromInt(type: Int): MangaFormat? = map[type]
    }
}

@Serializable
data class LibraryDto(
    val id: Int,
    val name: String,
    val type: Int,
)

@Serializable // https://github.com/Kareadita/Kavita/blob/develop/API/Entities/Enums/LibraryType.cs
enum class LibraryTypeEnum(val type: Int) {
    Manga(0),
    Comic(1),
    Book(2),
    Image(3),
    LightNovel(4),
    ComicVine(5),
    ;

    companion object {
        private val map = values().associateBy(LibraryTypeEnum::type)
        fun fromInt(type: Int) = map[type]
    }
}

@Serializable
data class SeriesDto(
    val id: Int,
    val name: String,
    val originalName: String = "",
    val thumbnail_url: String? = "",
    val localizedName: String? = "",
    val sortName: String? = "",
    val pages: Int,
    val coverImageLocked: Boolean = true,
    val pagesRead: Int,
    val userRating: Float,
    val userReview: String? = "",
    val format: Int,
    val created: String? = "",
    val libraryId: Int,
    val libraryName: String? = "",
    val ratings: List<RatingDto> = emptyList(),
)

@Serializable
data class SeriesDetailPlusDto(
    val seriesId: Int? = null,
    val libraryName: String? = "",
    val libraryId: Int? = null,
    val summary: String? = null,
    val genres: List<MetadataGenres> = emptyList(),
    val tags: List<MetadataTag> = emptyList(),
    val writers: List<MetadataPeople> = emptyList(),
    val coverArtists: List<MetadataPeople> = emptyList(),
    val publicationStatus: Int? = null,
    val averageScore: Float = 0f,
    val ratings: List<RatingDto> = emptyList(),
) {
    // Helper function to get the library name from SeriesDto if needed
    fun getLibraryName(seriesDto: SeriesDto?): String? {
        return if (!libraryName.isNullOrEmpty()) {
            libraryName
        } else {
            seriesDto?.libraryName
        }
    }

    // Helper function to get the library ID from SeriesDto if needed
    fun getLibraryId(seriesDto: SeriesDto?): Int? {
        return libraryId ?: seriesDto?.libraryId
    }
}

@Serializable
data class SeriesDetailPlusWrapperDto(
    val series: SeriesPlus? = null,
    val ratings: List<RatingDto> = emptyList(),
    val recommendations: RecommendationsDto? = null,
)

@Serializable
data class SeriesPlus(
    val averageScore: Float = 0f,
    val summary: String = "",
    val relations: List<RelationDto> = emptyList(),
)

@Serializable
data class RecommendationsDto(
    val ownedSeries: List<SeriesDto> = emptyList(),
)

@Serializable
data class RatingDto(
    val averageScore: Float = 0f,
    val favoriteCount: Int = 0,
    val provider: Int = 0,
    val authority: Int = 0,
    val providerUrl: String? = null,
)

@Serializable
data class RelationDto(
    val seriesName: RelationName,
    val relation: Int,
    val aniListId: Int? = null,
    val malId: Int? = null,
    val provider: Int? = null,
    val plusMediaFormat: Int? = null,
)

@Serializable
data class RelationName(
    val englishTitle: String? = null,
    val romajiTitle: String? = null,
    val nativeTitle: String? = null,
    val preferredTitle: String? = null,
)

@Serializable
data class RelatedSeriesResponse(
    val sourceSeriesId: Int,
    val sequels: List<RelatedSeriesItem> = emptyList(),
    val prequels: List<RelatedSeriesItem> = emptyList(),
    val spinOffs: List<RelatedSeriesItem> = emptyList(),
    val adaptations: List<RelatedSeriesItem> = emptyList(),
    val sideStories: List<RelatedSeriesItem> = emptyList(),
    val characters: List<RelatedSeriesItem> = emptyList(),
    val contains: List<RelatedSeriesItem> = emptyList(),
    val others: List<RelatedSeriesItem> = emptyList(),
    val alternativeSettings: List<RelatedSeriesItem> = emptyList(),
    val alternativeVersions: List<RelatedSeriesItem> = emptyList(),
    val doujinshis: List<RelatedSeriesItem> = emptyList(),
    val parent: List<RelatedSeriesItem> = emptyList(),
    val editions: List<RelatedSeriesItem> = emptyList(),
    val annuals: List<RelatedSeriesItem> = emptyList(),
)

@Serializable
data class RelatedSeriesItem(
    val id: Int,
    val name: String,
    val coverImage: String? = null,
    val libraryId: Int,
    val libraryName: String? = null,
    val format: Int = 0,
) : ConvertibleToSManga {
    override fun toSManga(baseUrl: String, apiUrl: String, apiKey: String): SManga = SManga.create().apply {
        title = name
        url = "$baseUrl/Series/$id"
        thumbnail_url = when {
            !coverImage.isNullOrBlank() && (coverImage.startsWith("http://") || coverImage.startsWith("https://")) -> coverImage
            else -> "$apiUrl/image/series-cover?seriesId=$id&apiKey=$apiKey"
        }
        initialized = true
    }
}

@Serializable
data class AuthorDto(
    val name: String,
    val role: String,
)

@Serializable
data class VolumeDto(
    val id: Int,
    val number: Int,
    val name: String,
    val pages: Int,
    val pagesRead: Int,
    val lastModified: String,
    val created: String,
    val seriesId: Int,
    val coverImage: String,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
enum class ChapterType {
    Regular, // chapter with volume information
    Chapter, // manga chapter without volume information
    SingleFileVolume,
    Special,
    Issue, // For comics
    ;

    companion object {
        fun of(chapter: ChapterDto, volume: VolumeDto, libraryType: LibraryTypeEnum? = null): ChapterType =
            when {
                volume.number == 100_000 -> Special
                volume.number == -100_000 -> when (libraryType) {
                    LibraryTypeEnum.Comic, LibraryTypeEnum.ComicVine -> Issue
                    LibraryTypeEnum.Manga, LibraryTypeEnum.LightNovel, LibraryTypeEnum.Book -> Chapter
                    else -> Chapter // Default to Chapter for other types
                }
                chapter.number == "-100000" -> SingleFileVolume
                else -> when (libraryType) {
                    LibraryTypeEnum.Comic, LibraryTypeEnum.ComicVine -> Issue
                    LibraryTypeEnum.Manga, LibraryTypeEnum.LightNovel, LibraryTypeEnum.Book -> Chapter
                    else -> Regular
                }
            }
    }
}

@Serializable
data class ChapterDto(
    val id: Int,
    val range: String,
    val number: String,
    val pages: Int,
    val isSpecial: Boolean,
    val title: String,
    val titleName: String,
    val pagesRead: Int,
    val coverImageLocked: Boolean,
    val volumeId: Int,
    val created: String,
    val lastModifiedUtc: String,
    val files: List<FileDto>? = null,
) {
    val fileCount: Int
        get() = files?.size ?: 0
}

@Serializable
data class FileDto(
    val id: Int,
)

@Serializable
data class ReadingListDto(
    val id: Int,
    val title: String,
    val coverImage: String? = null,
    val promoted: Boolean = false,
    val summary: String?,
    val itemCount: Int,
    val startingYear: Int,
    val startingMonth: Int,
    val endingYear: Int,
    val endingMonth: Int,
    val ownerUserName: String?,
    @SerialName("items") val items: List<ReadingListItemDto> = emptyList(),
)

@Serializable
data class ReadingListItemDto(
    val id: Int,
    val order: Int,
    val chapterId: Int?,
    val seriesId: Int,
    val seriesName: String,
    val chapterNumber: String?,
    val volumeNumber: String?,
    val chapterTitleName: String?,
    val volumeId: Int?,
    val title: String?,
    val summary: String?,
    val releaseDate: String?,
    val libraryName: String?,
    @SerialName("readingListId") val readingListId: Int,
)
