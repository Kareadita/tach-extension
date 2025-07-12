package eu.kanade.tachiyomi.extension.all.kavita

object KavitaConstants {

    const val UNNUMBERED_VOLUME = -100000
    const val UNNUMBERED_VOLUME_STR = "-100000"

    val PERSON_ROLES = listOf(
        "Writer", "Penciller", "Inker", "Colorist",
        "Letterer", "CoverArtist", "Editor",
        "Publisher", "Character", "Translator",
    )

    // toggle filters
    const val toggledFiltersPref = "toggledFilters"
    val filterPrefEntries = arrayOf(
        "Sort Options",
        "Special Lists",
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
        "ReleaseYearRange",
        "Random",
        "Read Progress",
    )
    val filterPrefEntriesValue = arrayOf(
        "Sort Options",
        "Special Lists",
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
        "ReleaseYearRange",
        "Random",
        "Read Progress",
    )
    val defaultFilterPrefEntries = setOf(
        "Sort Options",
        "Special Lists",
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
        "ReleaseYearRange",
        "Random",
        "Read Progress",
    )

    const val customSourceNamePref = "customSourceName"
    const val noSmartFilterSelected = "No smart filter loaded"
}
