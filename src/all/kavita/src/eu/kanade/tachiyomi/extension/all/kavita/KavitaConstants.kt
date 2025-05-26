package eu.kanade.tachiyomi.extension.all.kavita

object KavitaConstants {
    // toggle filters
    @Suppress("ktlint:standard:property-naming")
    const val toggledFiltersPref = "toggledFilters"
    val filterPrefEntries =
        arrayOf(
            "Sort Options",
            "Format",
            "Libraries",
            "Read Status",
            "Genres",
            "Tags",
            "Collections",
            "Languages",
            "Publication Status",
            "Rating",
            "Age Rating",
            "Writers",
            "Penciller",
            "Inker",
            "Colorist",
            "Letterer",
            "Cover Artist",
            "Editor",
            "Publisher",
            "Character",
            "Translators",
            "ReleaseYearRange",
        )
    val filterPrefEntriesValue =
        arrayOf(
            "Sort Options",
            "Format",
            "Libraries",
            "Read Status",
            "Genres",
            "Tags",
            "Collections",
            "Languages",
            "Publication Status",
            "Rating",
            "Age Rating",
            "Writers",
            "Penciller",
            "Inker",
            "Colorist",
            "Letterer",
            "CoverArtist",
            "Editor",
            "Publisher",
            "Character",
            "Translators",
            "ReleaseYearRange",
        )
    val defaultFilterPrefEntries =
        setOf(
            "Sort Options",
            "Format",
            "Libraries",
            "Read Status",
            "Genres",
            "Tags",
            "Collections",
            "Languages",
            "Publication Status",
            "Rating",
            "Age Rating",
            "Writers",
            "Penciller",
            "Inker",
            "Colorist",
            "Letterer",
            "CoverArtist",
            "Editor",
            "Publisher",
            "Character",
            "Translators",
            "ReleaseYearRange",
        )

    @Suppress("ktlint:standard:property-naming")
    const val customSourceNamePref = "customSourceName"

    @Suppress("ktlint:standard:property-naming")
    const val noSmartFilterSelected = "No smart filter loaded"
}
