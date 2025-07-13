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
data class MetadataUserRatings(val value: Int, val title: String)

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
