package eu.kanade.tachiyomi.extension.all.kavita.dto

import kotlinx.serialization.Serializable
import kotlin.Triple

@Serializable
data class FilterV2Dto(
    val id: Int? = null,
    val name: String? = null,
    val statements: MutableList<FilterStatementDto> = mutableListOf(),
    val combination: Int = FilterCombination.And.ordinal,
    val sortOptions: SortOptions = SortOptions(),
    val limitTo: Int = 0,
) {
    fun addStatement(comparison: FilterComparison, field: FilterField, value: String) {
        if (value.isNotBlank()) {
            statements.add(FilterStatementDto(comparison.type, field.type, value))
        }
    }

    fun addStatement(comparison: FilterComparison, field: FilterField, values: List<Any>) {
        if (values.isNotEmpty()) {
            statements.add(FilterStatementDto(comparison.type, field.type, values.joinToString(",")))
        }
    }

    fun addContainsNotTriple(list: List<Triple<FilterField, List<Any>, List<Int>>>) {
        list.forEach {
            addStatement(FilterComparison.Contains, it.first, it.second)
            addStatement(FilterComparison.NotContains, it.first, it.third)
        }
    }

    fun addPeople(list: List<Pair<FilterField, List<Int>>>) {
        list.forEach {
            addStatement(FilterComparison.MustContains, it.first, it.second)
        }
    }
}

@Serializable
data class FilterStatementDto(
    val comparison: Int,
    val field: Int,
    val value: String,
)

@Serializable
data class SortOptions(
    var sortField: Int = SortFieldEnum.AverageRating.type,
    var isAscending: Boolean = true,
)

@Serializable
enum class SortFieldEnum(val type: Int) {
    SortName(1),
    CreatedDate(2),
    LastModifiedDate(3),
    LastChapterAdded(4),
    TimeToRead(5),
    ReleaseYear(6),
    ReadProgress(7),
    AverageRating(8),
    Random(9),
    ;

    companion object {
        private val map = values().associateBy(SortFieldEnum::type)
        fun fromInt(type: Int) = map[type]
    }
}

@Serializable
enum class FilterCombination {
    Or,
    And,
}

@Serializable
enum class FilterField(val type: Int) {
    Summary(0),
    SeriesName(1),
    PublicationStatus(2),
    Languages(3),
    AgeRating(4),
    UserRating(5),
    Tags(6),
    CollectionTags(7),
    Translators(8),
    Characters(9),
    Publisher(10),
    Editor(11),
    CoverArtist(12),
    Letterer(13),
    Colorist(14),
    Inker(15),
    Penciller(16),
    Writers(17),
    Genres(18),
    Libraries(19),
    ReadProgress(20),
    Formats(21),
    ReleaseYear(22),
    ReadTime(23),
    Path(24),
    FilePath(25),
    WantToRead(26),
    ReadingDate(27),
    AverageRating(28),
    Imprint(29),
    Team(30),
    Location(31),
    ReadLast(32),
    People(33),
    ;

    companion object {
        fun fromType(type: Int): FilterField? = values().find { it.type == type }
    }
}

@Serializable
enum class FilterComparison(val type: Int) {
    Equal(0),
    GreaterThan(1),
    GreaterThanEqual(2),
    LessThan(3),
    LessThanEqual(4),
    Contains(5),
    MustContains(6),
    Matches(7),
    NotContains(8),
    NotEqual(9),
    BeginsWith(10),
    EndsWith(11),
    IsBefore(12),
    IsAfter(13),
    IsInLast(14),
    IsNotInLast(15),
    IsEmpty(16),
}

@Serializable
data class PersonSearchDto(
    val id: Int,
    val name: String,
    val role: Int? = null,
)

enum class PersonRole(val id: Int) {
    Writer(0),
    Penciller(1),
    Inker(2),
    Colorist(3),
    Letterer(4),
    CoverArtist(5),
    Editor(6),
    Publisher(7),
    Character(8),
    Translator(9),
    ;

    companion object {
        private val map = values().associateBy(PersonRole::id)
        fun fromId(id: Int): PersonRole? = map[id]
    }
}

fun PersonRole.toFilterField(): FilterField? {
    return when (this) {
        PersonRole.Writer -> FilterField.Writers
        PersonRole.Penciller -> FilterField.Penciller
        PersonRole.Inker -> FilterField.Inker
        PersonRole.Colorist -> FilterField.Colorist
        PersonRole.Letterer -> FilterField.Letterer
        PersonRole.CoverArtist -> FilterField.CoverArtist
        PersonRole.Editor -> FilterField.Editor
        PersonRole.Publisher -> FilterField.Publisher
        PersonRole.Character -> FilterField.Characters
        PersonRole.Translator -> FilterField.Translators
    }
}
