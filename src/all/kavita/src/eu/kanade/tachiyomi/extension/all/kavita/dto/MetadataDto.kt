// Metadata descriptors for filters and categorization
package eu.kanade.tachiyomi.extension.all.kavita.dto

import kotlinx.serialization.Serializable

@Serializable
data class MetadataGenres(val id: Int, val title: String)

@Serializable
data class MetadataPeople(
    val id: Int,
    val name: String,
    val role: Int? = null,
)

@Serializable
data class MetadataPubStatus(val value: Int, val title: String)

@Serializable
data class MetadataTag(val id: Int, val title: String)

@Serializable
data class MetadataAgeRatings(val value: Int, val title: String)

@Serializable
data class MetadataLanguages(val isoCode: String, val title: String)

@Serializable
data class MetadataLibrary(val id: Int, val name: String, val type: Int)

@Serializable
data class MetadataCollections(val id: Int, val title: String)

@Serializable
data class SmartFilter(
    val id: Int,
    val name: String,
    val filter: String,
)

@Serializable
data class MetadataPayload(
    val forceUseMetadataPayload: Boolean = true,
    var sorting: Int = 1,
    var sorting_asc: Boolean = true,
    var readStatus: ArrayList<String> = arrayListOf("notRead", "inProgress", "read"),
    var genres_i: ArrayList<Int> = arrayListOf(),
    var genres_e: ArrayList<Int> = arrayListOf(),
    var tags_i: ArrayList<Int> = arrayListOf(),
    var tags_e: ArrayList<Int> = arrayListOf(),
    var ageRating_i: ArrayList<Int> = arrayListOf(),
    var ageRating_e: ArrayList<Int> = arrayListOf(),
    var formats: ArrayList<Int> = arrayListOf(),
    var collections_i: ArrayList<Int> = arrayListOf(),
    var collections_e: ArrayList<Int> = arrayListOf(),
    var userRating: Int = 0,
    var people: ArrayList<Int> = arrayListOf(),
    var language_i: ArrayList<String> = arrayListOf(),
    var language_e: ArrayList<String> = arrayListOf(),
    var libraries_i: ArrayList<Int> = arrayListOf(),
    var libraries_e: ArrayList<Int> = arrayListOf(),
    var pubStatus: ArrayList<Int> = arrayListOf(),
    var seriesNameQuery: String = "",
    var releaseYearRangeMin: Int = 0,
    var releaseYearRangeMax: Int = 0,
    var peopleWriters: ArrayList<Int> = arrayListOf(),
    var peoplePenciller: ArrayList<Int> = arrayListOf(),
    var peopleInker: ArrayList<Int> = arrayListOf(),
    var peopleColorist: ArrayList<Int> = arrayListOf(),
    var peopleLetterer: ArrayList<Int> = arrayListOf(),
    var peopleCoverArtist: ArrayList<Int> = arrayListOf(),
    var peopleEditor: ArrayList<Int> = arrayListOf(),
    var peoplePublisher: ArrayList<Int> = arrayListOf(),
    var peopleCharacter: ArrayList<Int> = arrayListOf(),
    var peopleTranslator: ArrayList<Int> = arrayListOf(),
)
