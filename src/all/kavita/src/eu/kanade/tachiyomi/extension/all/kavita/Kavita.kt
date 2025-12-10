package eu.kanade.tachiyomi.extension.all.kavita

import android.app.AlertDialog
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.extension.all.kavita.dto.AuthenticationDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.ChapterDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.ChapterType
import eu.kanade.tachiyomi.extension.all.kavita.dto.FilterComparison
import eu.kanade.tachiyomi.extension.all.kavita.dto.FilterField
import eu.kanade.tachiyomi.extension.all.kavita.dto.FilterStatementDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.FilterV2Dto
import eu.kanade.tachiyomi.extension.all.kavita.dto.LibraryDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.LibraryTypeEnum
import eu.kanade.tachiyomi.extension.all.kavita.dto.MangaFormat
import eu.kanade.tachiyomi.extension.all.kavita.dto.MetadataAgeRatings
import eu.kanade.tachiyomi.extension.all.kavita.dto.MetadataCollections
import eu.kanade.tachiyomi.extension.all.kavita.dto.MetadataGenres
import eu.kanade.tachiyomi.extension.all.kavita.dto.MetadataLanguages
import eu.kanade.tachiyomi.extension.all.kavita.dto.MetadataLibrary
import eu.kanade.tachiyomi.extension.all.kavita.dto.MetadataPeople
import eu.kanade.tachiyomi.extension.all.kavita.dto.MetadataPubStatus
import eu.kanade.tachiyomi.extension.all.kavita.dto.MetadataTag
import eu.kanade.tachiyomi.extension.all.kavita.dto.ReadingListDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.ReadingListItemDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.RelatedSeriesResponse
import eu.kanade.tachiyomi.extension.all.kavita.dto.SeriesDetailPlusDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.SeriesDetailPlusWrapperDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.SeriesDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.ServerInfoDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.SmartFilter
import eu.kanade.tachiyomi.extension.all.kavita.dto.SortFieldEnum
import eu.kanade.tachiyomi.extension.all.kavita.dto.SortOptions
import eu.kanade.tachiyomi.extension.all.kavita.dto.VolumeDto
import eu.kanade.tachiyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Filter.TriState.Companion.STATE_EXCLUDE
import eu.kanade.tachiyomi.source.model.Filter.TriState.Companion.STATE_INCLUDE
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.let
import kotlin.runCatching

class Kavita(private val suffix: String = "") : ConfigurableSource, UnmeteredSource, HttpSource() {
    private val helper = KavitaHelper()

    // Initialize the intl object
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
    override val client: OkHttpClient =
        network.client.newBuilder()
            .dns(Dns.SYSTEM)
            .build()

    /**
     * Unique source ID, generated from suffix and version.
     */
    override val id by lazy {
        val key = "${"kavita_$suffix"}/all/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    /** SharedPreferences for storing source-specific settings. */
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    override val name = "${KavitaInt.KAVITA_NAME} (${preferences.getString(KavitaConstants.customSourceNamePref, suffix)})"
    override val lang = "all"
    override val supportsLatest = true
    private val apiUrl: String by lazy { getPrefApiUrl() }
    private val apiKey: String by lazy { getPrefApiKey() }
    override val baseUrl by lazy { getPrefBaseUrl() }
    private val address by lazy { getPrefAddress() } // Address for the Kavita OPDS url. Should be http(s)://host:(port)/api/opds/api-key
    private var jwtToken = "" // * JWT Token for authentication with the server. Stored in memory.
    private val LOG_TAG = """Kavita_${"[$suffix]_" + preferences.getString(KavitaConstants.customSourceNamePref, "[$suffix]")!!.replace(' ', '_')}"""
    private var isLogged = false // Used to know if login was correct and not send login requests anymore
    private val json: Json by injectLazy()

    // Act as a cache
    private var series = emptyList<SeriesDto>()
    private val libraryTypeCache = mutableMapOf<Int, LibraryTypeEnum>()

    // Metadata caching with TTL (Time To Live)
    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(ttl: Long): Boolean = System.currentTimeMillis() - timestamp > ttl
    }

    private val metadataCache = ConcurrentHashMap<String, CacheEntry<*>>()
    private val CACHE_TTL = 30 * 60 * 1000L // 30 minutes in milliseconds

    /**
     * Extension function to parse a network [Response] as a given type [T].
     * Throws IOException with detailed error message if the response is not successful or body is empty.
     */
    private inline fun <reified T> Response.parseAs(): T =
        use {
            if (!it.isSuccessful) {
                val errorMessage = try {
                    when (it.code) {
                        400 -> intl["http_errors_400"]
                        401 -> intl["http_errors_401"]
                        403 -> intl["http_errors_403"]
                        404 -> intl["http_errors_404"]
                        429 -> intl["http_errors_429"]
                        500 -> intl["http_errors_500"]
                        502 -> intl["http_errors_502"]
                        503 -> intl["http_errors_503"]
                        else -> "HTTP error ${it.code}"
                    }
                } catch (e: Exception) {
                    "HTTP error ${it.code}"
                }
                Log.e(LOG_TAG, "HTTP error ${it.code}: $errorMessage - ${it.request.url}")
                val versionCheck = try {
                    intl["check_version"]
                } catch (e: Exception) {
                    "If issues persist, contact support with logs"
                }
                throw IOException("$errorMessage\n$versionCheck")
            }

            val body = it.body.string()
            if (body.isEmpty()) {
                Log.e(LOG_TAG, "Empty body for: ${it.request.url}")
                throw EmptyRequestBody("Empty response body")
            }
            json.decodeFromString(body)
        }

    // @todo To remove next update
    private fun showMigrationToast() {
        val prefs = Injekt.get<Application>().getSharedPreferences("kavita_update_1_22", 0)

        // Check if we already showed the message
        if (!prefs.getBoolean("shown_fix_toast", false)) {
            // Run on UI Thread (Safe for background updates)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    Injekt.get<Application>(),
                    "Kavita Update: Please pull-to-refresh your chapter list to apply the changes.",
                    Toast.LENGTH_LONG,
                ).show()
            }

            // Mark as shown so it doesn't appear again
            prefs.edit().putBoolean("shown_fix_toast", true).apply()
        }
    }

    /**
     * For Webview URLs, we need to handle different URL formats:
     * - API-style URLs (e.g., `/Series/123`)
     */
    override fun getMangaUrl(manga: SManga): String {
        return try {
            when {
                // Handle both API-style and web-style URLs
                manga.url.contains("/series/") || manga.url.contains("/Series/") -> {
                    val seriesId = manga.url
                        .substringAfterLast("/series/")
                        .substringAfterLast("/Series/")
                        .substringBefore("?")
                        .substringBefore("/")
                        .toIntOrNull()

                    if (seriesId == null) {
                        Log.w(LOG_TAG, "Couldn't extract series ID from URL: ${manga.url}")
                        return manga.url
                    }

                    // Try to get library ID from cached series first
                    val cachedSeries = series.find { it.id == seriesId }
                    val libraryId = cachedSeries?.libraryId

                    if (libraryId != null) {
                        // Use web-friendly URL format
                        "$baseUrl/library/$libraryId/series/$seriesId"
                    } else {
                        "$baseUrl/library/1/series/$seriesId"
                    }
                }

                // Handle reading list URLs
                manga.url.contains("readingListId=") -> {
                    val readingListId = manga.url.substringAfter("readingListId=").substringBefore("&")
                    "$baseUrl/lists/$readingListId"
                }

                // Fallback - use original URL if we don't recognize the pattern
                else -> manga.url
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in getMangaUrl", e)
            manga.url // Always fall back to original URL
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        // Match `chapter.url` in `chapterFromVolume`
        return chapter.url.substringBefore("_") // strips fileCount if appended
    }

    /**
     * Custom implementation for fetch popular, latest and search
     * Handles and logs errors to provide a more detailed exception to the users.
     */
    private suspend fun fetch(request: Request): MangasPage = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                throw IOException("Http Error: $code\n ${intl["http_errors_$code"]}\n${intl["check_version"]}")
            }
            popularMangaParse(response)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error fetching manga", e)
            throw e
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(popularMangaRequest(page)).execute()
            if (!response.isSuccessful) {
                throw IOException("HTTP error ${response.code}")
            }
            popularMangaParse(response)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error fetching popular manga", e)
            MangasPage(emptyList(), false)
        }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetch(latestUpdatesRequest(page))

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val specialListFilter = filters.find { it is SpecialListFilter } as? SpecialListFilter

        return when (specialListFilter?.state) {
            2 -> { // Reading Lists
                val response = client.newCall(readingListRequest()).execute()
                readingListParse(response)
            }
            else -> { // Regular searches (including Want to Read)
                fetch(searchMangaRequest(page, query, filters))
            }
        }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        return if (manga.url.contains("/ReadingList/")) {
            fetchReadingListItems(manga)
        } else {
            super.getChapterList(manga)
        }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        try {
            val result = response.parseAs<List<SeriesDto>>()
            series = result
            result.forEach { helper.seriesMap.addSeries(it) }

            Log.d(LOG_TAG, "Parsing ${result.size} series for popular feed")

            val allManga = result.map<SeriesDto, SManga> { item ->
                helper.createSeriesDto(item, apiUrl, apiKey)
            }

            Log.d(LOG_TAG, "Created ${allManga.size} titles")

            val filteredManga = allManga.filter { manga ->
                isLibraryAllowedForFeed(manga)
            }

            Log.d(LOG_TAG, "After filtering: ${filteredManga.size} titles remain")

            if (filteredManga.isEmpty()) {
                Log.w(LOG_TAG, "All titles were filtered out. This might indicate a library name mismatch.")
                Log.d(LOG_TAG, "Available library names in series: ${result.map { it.libraryName }.distinct()}")
                Log.d(LOG_TAG, "Allowed libraries from preferences: ${preferences.allowedLibrariesFeed}")
            }

            return MangasPage(filteredManga, helper.hasNextPage(response))
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Unhandled exception", e)
            throw IOException(intl["check_version"])
        }
    }

    private fun prepareRequest(page: Int, payload: String): Request {
        if (!isLogged) {
            doLogin()
        }
        return POST(
            "$apiUrl/series/all-v2?pageNumber=$page&pageSize=20",
            headersBuilder().build(),
            payload.toRequestBody(JSON_MEDIA_TYPE),
        )
    }

    override fun popularMangaRequest(page: Int): Request {
        val filter = FilterV2Dto(
            sortOptions = SortOptions(SortFieldEnum.AverageRating.type, false),
            statements = mutableListOf(),
        )

        // Always exclude EPUBs unless explicitly included
        if (!preferences.getBoolean(SHOW_EPUB_PREF, SHOW_EPUB_DEFAULT)) {
            filter.addStatement(
                FilterComparison.NotContains,
                FilterField.Formats,
                MangaFormat.Epub.format.toString(),
            )
        }

        val payload = json.encodeToJsonElement(filter).toString()
        return POST(
            "$apiUrl/Series/all-v2?pageNumber=$page&pageSize=20",
            headersBuilder().build(),
            payload.toRequestBody(JSON_MEDIA_TYPE),
        )
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val filter = FilterV2Dto(
            sortOptions = SortOptions(SortFieldEnum.LastChapterAdded.type, false),
            statements = mutableListOf(),
        )

        // Always exclude EPUBs unless explicitly included
        if (!preferences.getBoolean(SHOW_EPUB_PREF, SHOW_EPUB_DEFAULT)) {
            filter.addStatement(
                FilterComparison.NotContains,
                FilterField.Formats,
                MangaFormat.Epub.format.toString(),
            )
        }

        val payload = json.encodeToJsonElement(filter).toString()
        return prepareRequest(page, payload)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (genresListMeta.isEmpty() || tagsListMeta.isEmpty()) {
            Log.w(LOG_TAG, "Metadata not loaded, retrying filters")
            getFilterList() // Re-initialize metadata
        }

        val specialListFilter = filters.find { it is SpecialListFilter } as? SpecialListFilter
        val wantToReadSelected = specialListFilter?.state == 1

        Log.d(LOG_TAG, "Building search request for query: '$query' with filters: $filters")

        // Handle filtered search (no text query)
        val smartFilterFilter = filters.find { it is SmartFiltersFilter }

        // If a SmartFilter selected, apply its filter and return that
        if (smartFilterFilter?.state != 0 && smartFilterFilter != null) {
            val index = try {
                smartFilterFilter.state as Int - 1
            } catch (e: Exception) {
                Log.e(LOG_TAG, e.toString(), e)
                0
            }

            val filter: SmartFilter = smartFilters[index]
            val payload = buildJsonObject {
                put("EncodedFilter", filter.filter)
            }
            // Decode selected filters
            val request = POST(
                "$apiUrl/filter/decode",
                headersBuilder().build(),
                payload.toString().toRequestBody(JSON_MEDIA_TYPE),
            )
            client.newCall(request).execute().use {
                if (it.code == 200) {
                    // Hardcode exclude epub
                    val decoded_filter = json.decodeFromString<FilterV2Dto>(it.body.string())
                    decoded_filter.statements.add(FilterStatementDto(FilterComparison.NotContains.type, FilterField.Formats.type, "3"))

                    // Make request with selected filters
                    val url = if (wantToReadSelected) {
                        "$apiUrl/want-to-read/v2?pageNumber=$page&pageSize=20"
                    } else {
                        "$apiUrl/Series/all-v2?pageNumber=$page&pageSize=20"
                    }
                    return POST(
                        url,
                        headersBuilder().build(),
                        json.encodeToJsonElement(decoded_filter).toString().toRequestBody(JSON_MEDIA_TYPE),
                    )
                } else {
                    Log.e(LOG_TAG, "Failed to decode SmartFilter: ${it.code}\n" + it.message)
                    throw IOException(intl["version_exceptions_smart_filter"])
                }
            }
        }

        // Always build filterV2 from all filters
        val filterV2 = FilterV2Dto(
            sortOptions = SortOptions(SortFieldEnum.SortName.type, true),
            statements = mutableListOf(),
        )

        // Handle text query first - search with prefix modifiers
        if (query.isNotBlank()) {
            val queryLower = query.trim().lowercase()

            // Check for people search prefix modifiers
            when {
                queryLower.startsWith("artist:") -> {
                    val artistQuery = query.substringAfter(":").trim()

                    if (artistQuery.isNotBlank()) {
                        Log.d(LOG_TAG, "Searching for artist (text contains): '$artistQuery'")

                        val artistFields = listOf(
                            FilterField.CoverArtist,
                            FilterField.Penciller,
                            FilterField.Inker,
                            FilterField.Colorist,
                        )
                        artistFields.forEach { field ->
                            filterV2.addStatement(FilterComparison.Contains, field, artistQuery)
                        }
                        // Also hit aggregated People by text to keep prefix behavior
                        filterV2.addStatement(FilterComparison.Contains, FilterField.People, artistQuery)
                    }
                }
                queryLower.startsWith("people:") -> {
                    val peopleQuery = query.substringAfter(":").trim()

                    if (peopleQuery.isNotBlank()) {
                        Log.d(LOG_TAG, "Searching for people (text contains): '$peopleQuery'")

                        val peopleFields = listOf(
                            FilterField.Writers,
                            FilterField.Penciller,
                            FilterField.Inker,
                            FilterField.Colorist,
                            FilterField.Letterer,
                            FilterField.CoverArtist,
                            FilterField.Editor,
                            FilterField.Publisher,
                            FilterField.Characters,
                            FilterField.Translators,
                        )

                        peopleFields.forEach { field ->
                            filterV2.addStatement(FilterComparison.Contains, field, peopleQuery)
                        }
                        // Also hit aggregated People by text to keep prefix behavior
                        filterV2.addStatement(FilterComparison.Contains, FilterField.People, peopleQuery)
                    }
                }
                else -> {
                    // Regular search - only search series name
                    filterV2.addStatement(FilterComparison.Matches, FilterField.SeriesName, query)
                }
            }
        }

        filters.forEach { filter ->
            when (filter) {
                is Filter.Sort -> {
                    filter.state?.let { sortState ->
                        filterV2.sortOptions.sortField = sortState.index + 1
                        filterV2.sortOptions.isAscending = sortState.ascending
                    }
                }
                is UserRating -> {
                    if (filter.state > 0) {
                        filterV2.addStatement(
                            FilterComparison.GreaterThanEqual,
                            FilterField.UserRating,
                            filter.state.toString(),
                        )
                    }
                }
                is Filter.Group<*> -> {
                    when (filter) {
                        is StatusFilterGroup -> {
                            val statusFilter = filter.state.firstOrNull()
                            statusFilter?.state?.let { selectedIndex ->
                                when (selectedIndex) {
                                    1 -> { // Unread (0%)
                                        filterV2.addStatement(
                                            FilterComparison.Equal,
                                            FilterField.ReadProgress,
                                            "0",
                                        )
                                    }
                                    2 -> { // In Progress (1-99%)
                                        filterV2.addStatement(
                                            FilterComparison.GreaterThanEqual,
                                            FilterField.ReadProgress,
                                            "1",
                                        )
                                        filterV2.addStatement(
                                            FilterComparison.LessThanEqual,
                                            FilterField.ReadProgress,
                                            "99",
                                        )
                                    }
                                    3 -> { // Read (100%)
                                        filterV2.addStatement(
                                            FilterComparison.Equal,
                                            FilterField.ReadProgress,
                                            "100",
                                        )
                                    }
                                    // 0 is "Any" - no filter applied
                                }
                            }
                        }
                        is ReleaseYearRangeGroup -> {
                            filter.state.forEach { rangeFilter ->
                                if ((rangeFilter as Filter.Text).state.isNotEmpty()) {
                                    when (rangeFilter.name) {
                                        "Min" -> filterV2.addStatement(
                                            FilterComparison.GreaterThanEqual,
                                            FilterField.ReleaseYear,
                                            rangeFilter.state,
                                        )
                                        "Max" -> filterV2.addStatement(
                                            FilterComparison.LessThanEqual,
                                            FilterField.ReleaseYear,
                                            rangeFilter.state,
                                        )
                                    }
                                }
                            }
                        }

                        is GenreFilterGroup -> {
                            filter.state.forEach { genreFilter ->
                                val genre = genresListMeta.find { it.title == (genreFilter as Filter.TriState).name }
                                Log.d(LOG_TAG, "Checking genre: ${genreFilter.name}, state=${genreFilter.state}, match=${genre?.id}")
                                genre?.let {
                                    when (genreFilter.state) {
                                        STATE_INCLUDE -> filterV2.addStatement(
                                            FilterComparison.Contains,
                                            FilterField.Genres,
                                            genre.id.toString(),
                                        )
                                        STATE_EXCLUDE -> filterV2.addStatement(
                                            FilterComparison.NotContains,
                                            FilterField.Genres,
                                            genre.id.toString(),
                                        )
                                    }
                                }
                            }
                        }

                        is TagFilterGroup -> {
                            val included = filter.state
                                .filter { it.state == STATE_INCLUDE }
                                .mapNotNull { tagFilter ->
                                    tagsListMeta.find { it.title == tagFilter.name }?.id?.toString()
                                }
                            val excluded = filter.state
                                .filter { it.state == STATE_EXCLUDE }
                                .mapNotNull { tagFilter ->
                                    tagsListMeta.find { it.title == tagFilter.name }?.id?.toString()
                                }

                            if (included.isNotEmpty()) {
                                filterV2.addStatement(
                                    FilterComparison.Contains,
                                    FilterField.Tags,
                                    included.joinToString(","),
                                )
                            }
                            if (excluded.isNotEmpty()) {
                                filterV2.addStatement(
                                    FilterComparison.NotContains,
                                    FilterField.Tags,
                                    excluded.joinToString(","),
                                )
                            }
                        }
                        is AgeRatingFilterGroup -> {
                            val included = filter.state
                                .filter { it.state == STATE_INCLUDE }
                                .mapNotNull { ageFilter ->
                                    ageRatingsListMeta.find { it.title == ageFilter.name }?.value?.toString()
                                }
                            val excluded = filter.state
                                .filter { it.state == STATE_EXCLUDE }
                                .mapNotNull { ageFilter ->
                                    ageRatingsListMeta.find { it.title == ageFilter.name }?.value?.toString()
                                }

                            if (included.isNotEmpty()) {
                                filterV2.addStatement(
                                    FilterComparison.Contains,
                                    FilterField.AgeRating,
                                    included.joinToString(","),
                                )
                            }
                            if (excluded.isNotEmpty()) {
                                filterV2.addStatement(
                                    FilterComparison.NotContains,
                                    FilterField.AgeRating,
                                    excluded.joinToString(","),
                                )
                            }
                        }
                        is FormatsFilterGroup -> {
                            filter.state.forEach { formatFilter ->
                                if ((formatFilter as Filter.CheckBox).state) {
                                    val formatValue = when (formatFilter.name) {
                                        "Image" -> 0
                                        "Archive" -> 1
                                        "Pdf" -> 4
                                        "Epub" -> 3
                                        "Unknown" -> 2
                                        else -> null
                                    }
                                    formatValue?.let {
                                        filterV2.addStatement(
                                            FilterComparison.Contains,
                                            FilterField.Formats,
                                            it.toString(),
                                        )
                                    }
                                }
                            }
                        }
                        is CollectionFilterGroup -> {
                            val included = filter.state
                                .filter { it.state == STATE_INCLUDE }
                                .mapNotNull { collectionFilter ->
                                    collectionsListMeta.find { it.title == collectionFilter.name }?.id?.toString()
                                }
                            val excluded = filter.state
                                .filter { it.state == STATE_EXCLUDE }
                                .mapNotNull { collectionFilter ->
                                    collectionsListMeta.find { it.title == collectionFilter.name }?.id?.toString()
                                }

                            if (included.isNotEmpty()) {
                                filterV2.addStatement(
                                    FilterComparison.Contains,
                                    FilterField.CollectionTags,
                                    included.joinToString(","),
                                )
                            }
                            if (excluded.isNotEmpty()) {
                                filterV2.addStatement(
                                    FilterComparison.NotContains,
                                    FilterField.CollectionTags,
                                    excluded.joinToString(","),
                                )
                            }
                        }
                        is LanguageFilterGroup -> {
                            val included = filter.state
                                .filter { it.state == STATE_INCLUDE }
                                .mapNotNull { languageFilter ->
                                    languagesListMeta.find { it.title == languageFilter.name }?.isoCode
                                }
                            val excluded = filter.state
                                .filter { it.state == STATE_EXCLUDE }
                                .mapNotNull { languageFilter ->
                                    languagesListMeta.find { it.title == languageFilter.name }?.isoCode
                                }

                            if (included.isNotEmpty()) {
                                filterV2.addStatement(
                                    FilterComparison.Contains,
                                    FilterField.Languages,
                                    included.joinToString(","),
                                )
                            }
                            if (excluded.isNotEmpty()) {
                                filterV2.addStatement(
                                    FilterComparison.NotContains,
                                    FilterField.Languages,
                                    excluded.joinToString(","),
                                )
                            }
                        }
                        is LibrariesFilterGroup -> {
                            val includedLibraries = mutableListOf<String>()
                            val excludedLibraries = mutableListOf<String>()

                            filter.state.forEach { libraryFilter ->
                                val library = libraryListMeta.find { it.name == (libraryFilter as Filter.TriState).name }
                                library?.let {
                                    when (libraryFilter.state) {
                                        STATE_INCLUDE -> includedLibraries.add(library.id.toString())
                                        STATE_EXCLUDE -> excludedLibraries.add(library.id.toString())
                                    }
                                }
                            }

                            // Add combined statements to avoid conflicts
                            if (includedLibraries.isNotEmpty()) {
                                filterV2.addStatement(
                                    FilterComparison.Contains,
                                    FilterField.Libraries,
                                    includedLibraries.joinToString(","),
                                )
                            }
                            if (excludedLibraries.isNotEmpty()) {
                                filterV2.addStatement(
                                    FilterComparison.NotContains,
                                    FilterField.Libraries,
                                    excludedLibraries.joinToString(","),
                                )
                            }
                        }
                        is PubStatusFilterGroup -> {
                            filter.state.forEach { pubStatusFilter ->
                                if ((pubStatusFilter as Filter.CheckBox).state) {
                                    val statusValue = when (pubStatusFilter.name) {
                                        "Ongoing" -> 0
                                        "Hiatus" -> 1
                                        "Completed" -> 2
                                        "Cancelled" -> 3
                                        "Ended" -> 4
                                        else -> null
                                    }
                                    statusValue?.let {
                                        filterV2.addStatement(
                                            FilterComparison.Equal,
                                            FilterField.PublicationStatus,
                                            it.toString(),
                                        )
                                    }
                                }
                            }
                        }
                        // Editors
                        is EditorPeopleFilterGroup -> {
                            filter.state.forEach { peopleFilter ->
                                if ((peopleFilter as Filter.CheckBox).state) {
                                    val person = peopleListMeta.find { it.name == peopleFilter.name }
                                    person?.let {
                                        filterV2.addStatement(
                                            FilterComparison.Contains,
                                            FilterField.Editor,
                                            it.name,
                                        )
                                    }
                                }
                            }
                        }
                        // Publishers
                        is PublisherPeopleFilterGroup -> {
                            filter.state.forEach { peopleFilter ->
                                if ((peopleFilter as Filter.CheckBox).state) {
                                    val person = peopleListMeta.find { it.name == peopleFilter.name }
                                    person?.let {
                                        filterV2.addStatement(
                                            FilterComparison.Contains,
                                            FilterField.Publisher,
                                            it.name,
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            Log.d(LOG_TAG, "Unhandled filter group type: ${filter.javaClass.simpleName}")
                        }
                    }
                }
                is Filter.CheckBox -> {
                    // Handle standalone checkboxes
                    Log.d(LOG_TAG, "Standalone checkbox filter: ${filter.name}")
                }
                is Filter.Text -> {
                    // Handle standalone text filters
                    Log.d(LOG_TAG, "Standalone text filter: ${filter.name}")
                }
                is Filter.TriState -> {
                    // Handle standalone tri-state filters
                    Log.d(LOG_TAG, "Standalone tri-state filter: ${filter.name}")
                }
                is Filter.Header -> {
                    // Skip header filters
                }
                is Filter.Separator -> {
                    // Skip separator filters
                }
                else -> {
                    Log.d(LOG_TAG, "Unhandled filter type: ${filter.javaClass.simpleName}")
                }
            }
        }

        // Always apply selected people filters (even if query is blank)
        val roleToField = mapOf(
            WriterPeopleFilterGroup::class to FilterField.Writers,
            PencillerPeopleFilterGroup::class to FilterField.Penciller,
            InkerPeopleFilterGroup::class to FilterField.Inker,
            ColoristPeopleFilterGroup::class to FilterField.Colorist,
            LettererPeopleFilterGroup::class to FilterField.Letterer,
            CoverArtistPeopleFilterGroup::class to FilterField.CoverArtist,
            EditorPeopleFilterGroup::class to FilterField.Editor,
            PublisherPeopleFilterGroup::class to FilterField.Publisher,
            CharacterPeopleFilterGroup::class to FilterField.Characters,
            TranslatorPeopleFilterGroup::class to FilterField.Translators,
        )

        roleToField.forEach { (filterClass, filterField) ->
            val group = filters.find { filterClass.isInstance(it) } as? Filter.Group<*>
            group?.state?.forEach { item ->
                if (item is Filter.CheckBox && item.state) {
                    filterV2.addStatement(FilterComparison.Matches, filterField, item.name)
                }
            }
        }

        // Always exclude EPUBs unless explicitly included
        if (!preferences.getBoolean(SHOW_EPUB_PREF, SHOW_EPUB_DEFAULT)) {
            filterV2.addStatement(
                FilterComparison.NotContains,
                FilterField.Formats,
                MangaFormat.Epub.format.toString(),
            )
        }
        val payload = json.encodeToJsonElement(filterV2).toString()
        Log.d(LOG_TAG, "Search payload: $payload")
        Log.d(LOG_TAG, "Filter statements: ${filterV2.statements}")

        val url = if (wantToReadSelected) {
            "$apiUrl/want-to-read/v2?pageNumber=$page&pageSize=20"
        } else {
            "$apiUrl/Series/all-v2?pageNumber=$page&pageSize=20"
        }

        return POST(
            url,
            headersBuilder().build(),
            payload.toRequestBody(JSON_MEDIA_TYPE),
        )
    }

    /*
     * MANGA DETAILS (metadata about series)
     * **/

    @Deprecated(
        "Use the non-RxJava API instead",
        replaceWith = ReplaceWith("getMangaDetails(manga)"),
    )
    override suspend fun getMangaDetails(manga: SManga): SManga {
        val serieId = helper.getIdFromUrl(manga.url)

        return if (manga.url.contains("source=readinglist")) {
            val readingListId = manga.url.substringAfter("readingListId=").substringBefore("&").toIntOrNull()
            val now = Calendar.getInstance()
            val readingList = cachedReadingLists.find { it.id == readingListId }
            if (readingList != null) {
                val groupTags = preferences.groupTags
                val starting = readingList.startingYear.takeIf { it > 0 }?.let {
                    "${readingList.startingYear}-${readingList.startingMonth.toString().padStart(2, '0')}"
                }
                val ending = readingList.endingYear.takeIf { it > 0 }?.let {
                    "${readingList.endingYear}-${readingList.endingMonth.toString().padStart(2, '0')}"
                }

                val itemsRequest = GET("$apiUrl/ReadingList/items?readingListId=$readingListId", headersBuilder().build())
                val items = try {
                    client.newCall(itemsRequest).execute().parseAs<List<ReadingListItemDto>>()
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error parsing reading list items", e)
                    emptyList()
                }

                val allGenres = items.flatMap { item ->
                    try {
                        val seriesMetaRequest = GET("$apiUrl/series/metadata?seriesId=${item.seriesId}", headersBuilder().build())
                        client.newCall(seriesMetaRequest).execute()
                            .parseAs<SeriesDetailPlusDto>()
                            .genres.map { it.title }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Error fetching series metadata for item ${item.seriesId}", e)
                        emptyList()
                    }
                }.distinct()

                val genreString = if (groupTags) {
                    buildList {
                        add("Type: Reading List")
                        starting?.let { add("Starting: $it") }
                        ending?.let { add("Ending: $it") }
                        if (allGenres.isNotEmpty()) addAll(allGenres.map { "Genres: $it" })
                    }.joinToString(", ")
                } else {
                    buildList {
                        starting?.let { add("Starting: $it") }
                        ending?.let { add("Ending: $it") }
                        add("Reading List")
                        if (allGenres.isNotEmpty()) addAll(allGenres)
                    }.joinToString(", ")
                }

                val hasDates = readingList.startingYear > 0 && readingList.endingYear > 0
                val status = if (hasDates) {
                    val endCal = Calendar.getInstance().apply {
                        set(readingList.endingYear, readingList.endingMonth - 1, 1)
                    }
                    if (now.after(endCal)) SManga.PUBLISHING_FINISHED else SManga.ONGOING
                } else {
                    SManga.COMPLETED
                }

                manga.apply {
                    title = (if (readingList.promoted) "🔺 " else "") + readingList.title
                    artist = "${readingList.itemCount} items"
                    thumbnail_url = if (!readingList.coverImage.isNullOrBlank()) {
                        "$apiUrl/image/${readingList.coverImage}?apiKey=$apiKey"
                    } else {
                        "$apiUrl/image/readinglist-cover?readingListId=$readingListId&apiKey=$apiKey"
                    }
                    description = readingList.summary ?: "Reading List"
                    genre = genreString
                    url = manga.url
                    initialized = true
                    this.status = status
                }
            } else {
                manga.apply { initialized = true }
            }
        } else {
            val response = client.newCall(GET("$apiUrl/series/metadata?seriesId=$serieId", headersBuilder().build())).execute()
            mangaDetailsParse(response).apply { initialized = true }
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val serieId = helper.getIdFromUrl(manga.url)
        val foundSerie = series.find { dto -> dto.id == serieId }
        return GET(
            "$baseUrl/library/${foundSerie!!.libraryId}/series/$serieId",
            headersBuilder().build(),
        )
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = try {
            response.parseAs<SeriesDetailPlusDto>().takeIf {
                it.seriesId != null && it.summary != null
            } ?: throw IOException("Invalid series details response")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error parsing series details", e)
            throw IOException("${intl["version_exceptions_chapters_parse"]}\n${intl["check_version"]}")
        }
        val existingSeries = series.find { dto -> dto.id == result.seriesId }
        val ratingLine = try {
            val responsePlus = try {
                client.newCall(
                    GET(
                        "$apiUrl/Metadata/series-detail-plus?seriesId=${result.seriesId}",
                        headersBuilder().build(),
                    ),
                ).execute()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "series-detail-plus endpoint failed, falling back to ratings endpoint", e)
                null
            }

            if (responsePlus == null || !responsePlus.isSuccessful) {
                // Fallback: just get ratings from the main metadata
                val score = result.ratings.firstOrNull()?.averageScore ?: 0f
                if (score > 0) "⭐ Score: ${"%.1f".format(score)}\n" else ""
            } else {
                val rawResponse = responsePlus.body.string()
                if (rawResponse.isBlank() || rawResponse.trim().startsWith("<")) {
                    Log.e(LOG_TAG, "Invalid response for series-detail-plus: $rawResponse")
                    val score = result.ratings.firstOrNull()?.averageScore ?: 0f
                    if (score > 0) "⭐ Score: ${"%.1f".format(score)}\n" else ""
                } else {
                    try {
                        val detailPlusWrapper = json.decodeFromString<SeriesDetailPlusWrapperDto>(rawResponse)
                        val score = detailPlusWrapper.series?.averageScore?.takeIf { it > 0f }
                            ?: detailPlusWrapper.ratings.firstOrNull()?.averageScore
                            ?: result.ratings.firstOrNull()?.averageScore
                            ?: 0f
                        if (score > 0) "⭐ Score: ${"%.1f".format(score)}\n" else ""
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Error parsing series detail plus, using fallback", e)
                        val score = result.ratings.firstOrNull()?.averageScore ?: 0f
                        if (score > 0) "⭐ Score: ${"%.1f".format(score)}\n" else ""
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error fetching ratings, using fallback", e)
            val score = result.ratings.firstOrNull()?.averageScore ?: 0f
            if (score > 0) "⭐ Score: ${"%.1f".format(score)}\n" else ""
        }
        if (existingSeries != null) {
            val genreTitles = result.genres.map { it.title }
            val tagTitles = result.tags.map { it.title }

            // Extract demographic, formats, and filtered genres/tags using helper
            val triple = helper.extractDemographicAndFormat(genreTitles, tagTitles)
            val foundDemographic = triple.first
            val foundFormats = triple.second
            val filteredGenres = triple.third.first
            val filteredTags = triple.third.second

            val manga = helper.createSeriesDto(existingSeries, apiUrl, apiKey)
            manga.title = existingSeries.name
            manga.url = "$apiUrl/Series/${result.seriesId}"
            manga.artist = result.coverArtists.joinToString { it.name }
            manga.description = listOfNotNull(
                ratingLine.takeIf { it.isNotBlank() },
                result.summary,
            ).joinToString("\n")
            manga.author = result.writers.joinToString { it.name }

            manga.genre = helper.buildGenreString(
                libraryName = existingSeries.libraryName,
                demographic = foundDemographic,
                formats = foundFormats,
                genres = filteredGenres,
                tags = filteredTags,
                groupTags = preferences.groupTags,
            )

            manga.thumbnail_url = if (preferences.LastVolumeCover) {
                try {
                    Log.d(LOG_TAG, "Fetching volumes for series ${result.seriesId}")
                    val seriesId = result.seriesId ?: 0
                    val volumes = client.newCall(
                        GET("$apiUrl/Series/volumes?seriesId=$seriesId", headersBuilder().build()),
                    ).execute().parseAs<List<VolumeDto>>()

                    val libraryType = getLibraryType(seriesId)
                    val isComicLibrary = libraryType == LibraryTypeEnum.Comic || libraryType == LibraryTypeEnum.ComicVine
                    val coverCandidates = mutableListOf<Triple<String, Boolean, Float>>() // (url, isUnread, number)
                    val chapterMap = mutableMapOf<String, ChapterDto>()
                    val volumeMap = mutableMapOf<String, VolumeDto>()

                    for (volume in volumes) {
                        // Issue: use chapter covers
                        if (isComicLibrary && volume.minNumber.toInt() == KavitaConstants.UNNUMBERED_VOLUME) {
                            for (chapter in volume.chapters) {
                                if (chapter.coverImage.isNotBlank()) {
                                    val url = "$apiUrl/Image/chapter-cover?chapterId=${chapter.id}&apiKey=$apiKey"
                                    coverCandidates.add(
                                        Triple(url, chapter.pagesRead < chapter.pages, chapter.number.toFloatOrNull() ?: 0f),
                                    )
                                    chapterMap[url] = chapter
                                }
                            }
                        }
                        // Manga: use volume cover
                        else if (!isComicLibrary) {
                            val hasSingleFile = volume.chapters.any { chapter ->
                                ChapterType.of(chapter, volume) == ChapterType.SingleFileVolume
                            }
                            if (hasSingleFile && volume.coverImage.isNotBlank()) {
                                val url = "$apiUrl/Image/volume-cover?volumeId=${volume.id}&apiKey=$apiKey"
                                val isUnread = volume.pagesRead < volume.pages
                                val number = volume.minNumber.toFloat()
                                coverCandidates.add(Triple(url, isUnread, number))
                                volumeMap[url] = volume
                            }
                        }
                    }

                    Log.d(LOG_TAG, "Found ${coverCandidates.size} cover candidates")

                    // Prefer first unread (lowest number), else most recent (highest number)
                    val targetCover = coverCandidates
                        .filter { it.second }
                        .minByOrNull { it.third }
                        ?: coverCandidates.maxByOrNull { it.third }

                    targetCover?.first?.let { baseUrl ->
                        val timestamp = when {
                            baseUrl.contains("chapter-cover") -> {
                                val chapter = chapterMap[baseUrl]
                                chapter?.lastModifiedUtc
                            }
                            baseUrl.contains("volume-cover") -> {
                                val volume = volumeMap[baseUrl]
                                volume?.lastModified
                            }
                            else -> null
                        } ?: System.currentTimeMillis().toString()

                        // Append cache-busting timestamp to always get the latest cover
                        "$baseUrl&ts=$timestamp"
                    } ?: "$apiUrl/image/series-cover?seriesId=$seriesId&apiKey=$apiKey"
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error fetching volumes for cover selection", e)
                    "$apiUrl/image/series-cover?seriesId=${result.seriesId ?: 0}&apiKey=$apiKey"
                }
            } else {
                "$apiUrl/image/series-cover?seriesId=${result.seriesId ?: 0}&apiKey=$apiKey"
            }

            manga.status = when (result.publicationStatus) {
                4 -> SManga.PUBLISHING_FINISHED
                2 -> SManga.COMPLETED
                0 -> SManga.ONGOING
                3 -> SManga.CANCELLED
                1 -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            return manga
        }
        val serieDto = client.newCall(GET("$apiUrl/Series/${result.seriesId}", headersBuilder().build()))
            .execute()
            .parseAs<SeriesDto>()

        return SManga.create().apply {
            val genreTitles = result.genres.map { it.title }
            val tagTitles = result.tags.map { it.title }

            // Extract demographic, formats, and filtered genres/tags using helper
            val triple = helper.extractDemographicAndFormat(genreTitles, tagTitles)
            val foundDemographic = triple.first
            val foundFormats = triple.second
            val filteredGenres = triple.third.first
            val filteredTags = triple.third.second

            url = "$baseUrl/Series/${result.seriesId}" // "$baseUrl/library/${result.getLibraryId(serieDto)}/series/${result.seriesId}"
            artist = result.coverArtists.joinToString { it.name }
            description = listOfNotNull(
                ratingLine.takeIf { it.isNotBlank() },
                result.summary,
            ).joinToString("\n")
            author = result.writers.joinToString { it.name }

            genre = helper.buildGenreString(
                libraryName = result.getLibraryName(serieDto),
                demographic = foundDemographic,
                formats = foundFormats,
                genres = filteredGenres,
                tags = filteredTags,
                groupTags = preferences.groupTags,
            )

            title = serieDto.name
            status = when (result.publicationStatus) {
                4 -> SManga.PUBLISHING_FINISHED
                2 -> SManga.COMPLETED
                0 -> SManga.ONGOING
                3 -> SManga.CANCELLED
                1 -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
//            Log.d(LOG_TAG, "Publication status received: ${result.publicationStatus}")
        }
    }

    /**
     ** Reading Lists
     **/
    private fun readingListRequest(): Request {
        return POST(
            "$apiUrl/ReadingList/lists?includePromoted=true&sortByLastModified=true",
            headersBuilder().build(),
            "{}".toRequestBody(JSON_MEDIA_TYPE),
        )
    }

    private fun readingListParse(response: Response): MangasPage {
        return try {
            val readingLists = response.parseAs<List<ReadingListDto>>()
            cachedReadingLists = readingLists
            val now = Calendar.getInstance()
            val mangaList = readingLists.map { list ->
                val hasDates = list.startingYear > 0 && list.endingYear > 0
                val status = if (hasDates) {
                    val endCal = Calendar.getInstance().apply {
                        set(list.endingYear, list.endingMonth - 1, 1)
                    }
                    if (now.after(endCal)) SManga.PUBLISHING_FINISHED else SManga.ONGOING
                } else {
                    SManga.COMPLETED
                }
                SManga.create().apply {
                    title = (if (list.promoted) "🔺 " else "") + list.title
                    artist = "${list.itemCount} items"
                    author = list.ownerUserName
                    thumbnail_url = if (!list.coverImage.isNullOrBlank()) {
                        "$apiUrl/image/${list.coverImage}?apiKey=$apiKey"
                    } else {
                        "$apiUrl/Image/readinglist-cover?readingListId=${list.id}&apiKey=$apiKey"
                    }
                    description = list.summary ?: "Reading List"
                    url = "$baseUrl/ReadingList/items?readingListId=${list.id}&source=readinglist"
                    initialized = true
                    this.status = status
                }
            }
            MangasPage(mangaList, false)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error parsing reading lists", e)
            MangasPage(emptyList(), false)
        }
    }

    private fun fetchReadingListItems(manga: SManga): List<SChapter> {
        val readingListId = manga.url.substringAfter("readingListId=").substringBefore("&")
        val request = GET("$apiUrl/ReadingList/items?readingListId=$readingListId", headersBuilder().build())
        val response = client.newCall(request).execute()
        return parseReadingListItems(response)
    }

    private fun parseReadingListItems(response: Response): List<SChapter> {
        val items = response.parseAs<List<ReadingListItemDto>>()
        val readingListId = response.request.url.queryParameter("readingListId")

        val firstItem = items.firstOrNull()
        val isWebtoon = firstItem?.let { item ->
            runCatching<Boolean> {
                val meta = client.newCall(GET("$apiUrl/series/metadata?seriesId=${item.seriesId}", headersBuilder().build()))
                    .execute().parseAs<SeriesDetailPlusDto>()

                val genreTitles = meta.genres.map { it.title }
                val tagTitles = meta.tags.map { it.title }

                // Extract demographic and formats information
                val triple = helper.extractDemographicAndFormat(genreTitles, tagTitles)
                val foundDemographic = triple.first
                val foundFormats = triple.second

                // Get all tags when GroupTags is disabled
                val allTagsForGrouping = if (preferences.groupTags) {
                    emptyList()
                } else {
                    genreTitles + tagTitles
                }

                // Get library information
                val libraryName = meta.libraryName.takeIf { !it.isNullOrEmpty() }
                    ?: series.find { it.id == item.seriesId }?.libraryName
                    ?: runCatching {
                        val seriesDto = client.newCall(GET("$apiUrl/Series/${item.seriesId}", headersBuilder().build()))
                            .execute().parseAs<SeriesDto>()
                        seriesDto.libraryName
                    }.getOrNull()

                Log.d(LOG_TAG, "Reading list item ${item.seriesId} library: $libraryName")

                val isWebtoonDetected = helper.isWebtoonOrLongStrip(
                    genres = genreTitles,
                    tags = tagTitles,
                    libraryName = libraryName,
                    format = foundFormats.firstOrNull(),
                    demographic = foundDemographic,
                    allTags = allTagsForGrouping,
                )

                isWebtoonDetected
            }.getOrElse { false }
        } ?: false

        return items.map { item ->
            SChapter.create().apply {
                url = when {
                    item.chapterId != null && item.chapterId > 0 ->
                        "/Chapter/${item.chapterId}?readingListId=$readingListId&seriesId=${item.seriesId}&chapterId=${item.chapterId}"
                    item.volumeId != null && item.volumeId > 0 ->
                        "/Volume/${item.volumeId}?readingListId=$readingListId&seriesId=${item.seriesId}&volumeId=${item.volumeId}"
                    else ->
                        "/Series/${item.seriesId}?readingListId=$readingListId&seriesId=${item.seriesId}&order=${item.order}"
                }

                val type = when {
                    item.volumeId != null && item.volumeNumber != null -> {
                        when {
                            item.volumeNumber == KavitaConstants.UNNUMBERED_VOLUME_STR -> {
                                val libraryType = getLibraryType(item.seriesId)
                                when (libraryType) {
                                    LibraryTypeEnum.Comic, LibraryTypeEnum.ComicVine -> ChapterType.Issue
                                    else -> ChapterType.Chapter
                                }
                            }
                            item.chapterNumber == KavitaConstants.UNNUMBERED_VOLUME_STR -> ChapterType.SingleFileVolume
                            else -> ChapterType.Regular
                        }
                    }
                    item.chapterId != null && item.chapterNumber != null -> {
                        val libraryType = getLibraryType(item.seriesId)
                        when (libraryType) {
                            LibraryTypeEnum.Comic, LibraryTypeEnum.ComicVine -> ChapterType.Issue
                            else -> ChapterType.Chapter
                        }
                    }
                    else -> ChapterType.Special
                }

                name = buildString {
                    append("${item.order + 1}. ")
                    when (type) {
                        ChapterType.Regular -> {
                            val chapterNum = item.chapterNumber?.toIntOrNull()?.toString()
                                ?.padStart(2, '0')
                                ?: item.chapterNumber ?: ""
                            when {
                                item.chapterTitleName?.isNotBlank() == true ->
                                    append("$chapterNum - ${item.chapterTitleName}")
                                else ->
                                    append("Vol. ${item.volumeNumber} Ch. $chapterNum")
                            }
                        }
                        ChapterType.SingleFileVolume -> {
                            when {
                                item.chapterTitleName?.isNotBlank() == true ->
                                    append("v${item.volumeNumber} - ${item.chapterTitleName}")
                                else ->
                                    append("Volume ${item.volumeNumber}")
                            }
                        }
                        ChapterType.Issue -> {
                            val issueNum = item.chapterNumber?.toIntOrNull()?.toString()
                                ?.padStart(3, '0')
                                ?: item.chapterNumber ?: ""
                            when {
                                item.chapterTitleName?.isNotBlank() == true ->
                                    append("${item.chapterTitleName} (#$issueNum)")
                                else ->
                                    append("Issue #$issueNum")
                            }
                        }
                        ChapterType.Chapter -> {
                            val chapterNum = item.chapterNumber?.toIntOrNull()?.toString()
                                ?.padStart(2, '0')
                                ?: item.chapterNumber ?: ""
                            when {
                                item.chapterTitleName?.isNotBlank() == true ->
                                    append("$chapterNum - ${item.chapterTitleName}")
                                else ->
                                    append(if (isWebtoon) "Episode $chapterNum" else "Chapter $chapterNum")
                            }
                        }
                        ChapterType.Special -> {
                            append(item.chapterTitleName ?: "Item ${item.order + 1}")
                        }
                    }
                }

                date_upload = if (preferences.RdDate && !item.releaseDate.isNullOrBlank()) {
                    parseDateSafe(item.releaseDate)
                } else {
                    0L
                }
                chapter_number = item.order.toFloat()
                scanlator = item.seriesName
            }
        }.sortedBy { it.chapter_number }
    }

    /**
     * Related Titles / Suggestions
     * Respect the Allowed Libraries in Latest/Popular Feeds
     * This only works on Komikku
     */
    override fun relatedMangaListRequest(manga: SManga): Request {
        return if (manga.url.contains("/ReadingList/") || manga.url.contains("readingListId=")) {
            val readingListId = manga.url.substringAfter("readingListId=").substringBefore("&")
            GET("$apiUrl/ReadingList/items?readingListId=$readingListId", headersBuilder().build())
        } else {
            val seriesId = helper.getIdFromUrl(manga.url)
            if (seriesId == -1) throw Exception("Invalid series ID")
            val url = "$apiUrl/Series/all-related?seriesId=$seriesId"
            Log.d(LOG_TAG, "Fetching related manga from: $url")
            GET(url, headersBuilder().build())
        }
    }

    override fun relatedMangaListParse(response: Response): List<SManga> {
        val requestUrl = response.request.url.toString()
        return try {
            if (requestUrl.contains("/ReadingList/items")) {
                val items = response.parseAs<List<ReadingListItemDto>>()
                items.distinctBy { it.seriesId }.mapNotNull { item ->
                    try {
                        val seriesDto = client.newCall(GET("$apiUrl/Series/${item.seriesId}", headersBuilder().build()))
                            .execute().parseAs<SeriesDto>()

                        val manga = helper.createSeriesDto(seriesDto, apiUrl, apiKey)
                        val suggestionLibraryName = seriesDto.libraryName
                        val suggestionLibraryId = seriesDto.libraryId

                        manga.takeIf {
                            isLibraryAllowedForSuggestions(
                                it,
                                currentMangaLibrary = null,
                                suggestedLibraryName = suggestionLibraryName,
                                suggestedLibraryId = suggestionLibraryId,
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Error fetching series for related reading list", e)
                        null
                    }
                }
            } else {
                val rawResponse = response.body.string()
                if (rawResponse.isEmpty() || rawResponse.startsWith("<")) {
                    Log.e(LOG_TAG, "Invalid response for related manga: $rawResponse")
                    return emptyList()
                }
                val result = json.decodeFromString<RelatedSeriesResponse>(rawResponse)

                // Extract the current manga's library from the request URL
                val currentSeriesId = requestUrl.substringAfter("seriesId=").toIntOrNull()
                val currentMangaLibrary = currentSeriesId?.let { seriesId ->
                    series.find { it.id == seriesId }?.libraryName
                }

                val allRelated = listOf(
                    result.sequels.map { it to "Sequel" },
                    result.prequels.map { it to "Prequel" },
                    result.spinOffs.map { it to "Spin-off" },
                    result.adaptations.map { it to "Adaptation" },
                    result.sideStories.map { it to "Side Story" },
                    result.characters.map { it to "Character" },
                    result.contains.map { it to "Contains" },
                    result.others.map { it to "Other" },
                    result.alternativeSettings.map { it to "Alternative Setting" },
                    result.alternativeVersions.map { it to "Alternative Version" },
                    result.doujinshis.map { it to "Doujinshi" },
                    result.parent.map { it to "Parent" },
                    result.editions.map { it to "Edition" },
                    result.annuals.map { it to "Annual" },
                ).flatten()
                val filteredRelated = if (!preferences.getBoolean(SHOW_EPUB_PREF, SHOW_EPUB_DEFAULT)) {
                    allRelated.filterNot { (item, _) ->
                        item.format == MangaFormat.Epub.format
                    }
                } else {
                    allRelated
                }

                Log.d(LOG_TAG, "Processing suggestions for current manga library: $currentMangaLibrary")
                Log.d(LOG_TAG, "Total related items before filtering: ${filteredRelated.size}")

                val suggestions = filteredRelated.mapNotNull { (item, relation) ->
                    val suggestionLibraryName = item.libraryName
                        ?: libraryListMeta.find { it.id == item.libraryId }?.name

                    val manga = item.toSManga(baseUrl, apiUrl, apiKey).apply {
                        if (relation.isNotBlank()) {
                            title = "[$relation] $title"
                        } else {
                            Log.e(LOG_TAG, "No related title")
                        }
                    }
                    // Show suggestions only from the current library or allowed search libraries
                    manga.takeIf {
                        isLibraryAllowedForSuggestions(
                            it,
                            currentMangaLibrary,
                            suggestionLibraryName,
                            item.libraryId,
                        )
                    }
                }

                Log.d(LOG_TAG, "Final suggestions count: ${suggestions.size}")
                Log.d(LOG_TAG, "Allowed libraries for search: ${preferences.allowedLibrariesSearch}")
                suggestions
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error parsing related manga", e)
            emptyList()
        }
    }

    /*
     * CHAPTER LIST
     * **/
    override fun chapterListRequest(manga: SManga): Request {
        val seriesId = helper.getIdFromUrl(manga.url)
        val url = "$apiUrl/Series/volumes?seriesId=$seriesId"
        return GET(url, headersBuilder().build())
    }

    private fun getLibraryType(seriesId: Int): LibraryTypeEnum {
        return libraryTypeCache[seriesId] ?: run {
            try {
                val seriesDto = client.newCall(GET("$apiUrl/Series/$seriesId", headersBuilder().build()))
                    .execute()
                    .parseAs<SeriesDto>()

                val type = libraryListMeta.find { it.id == seriesDto.libraryId }?.type?.let { typeInt ->
                    LibraryTypeEnum.fromInt(typeInt)
                } ?: run {
                    val library = client.newCall(GET("$apiUrl/Library/${seriesDto.libraryId}", headersBuilder().build()))
                        .execute()
                        .parseAs<LibraryDto>()
                    LibraryTypeEnum.fromInt(library.type)
                }

                val result = type ?: LibraryTypeEnum.Manga // Default fallback
                libraryTypeCache[seriesId] = result
                result
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error getting library type for series $seriesId", e)
                LibraryTypeEnum.Manga // Default fallback
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val seriesId = response.request.url.queryParameter("seriesId")?.toIntOrNull()
        if (seriesId != null) {
            showMigrationToast()
        }
        try {
            val seriesId = response.request.url.queryParameter("seriesId")?.toIntOrNull()
            val libraryType = seriesId?.let { getLibraryType(it) }
            val volumes = response.parseAs<List<VolumeDto>>()

            val allChapters = mutableListOf<SChapter>()

            // Get the series name to use as mangaTitle
            val seriesName = seriesId?.let { id: Int ->
                // First try from cache
                series.find { it.id == id }?.name
                    ?: runCatching {
                        client.newCall(GET("$apiUrl/Series/$id", headersBuilder().build()))
                            .execute().parseAs<SeriesDto>().name
                    }.getOrNull()
            } ?: ""

            Log.d(LOG_TAG, "Processing chapters for series $seriesId with name: '$seriesName'")

            // Get series metadata to determine if it's a webtoon
            val isWebtoon = seriesId?.let {
                runCatching<Boolean> {
                    val meta = client.newCall(GET("$apiUrl/series/metadata?seriesId=$it", headersBuilder().build()))
                        .execute().parseAs<SeriesDetailPlusDto>()
                    val genreTitles = meta.genres.map { it.title }
                    val tagTitles = meta.tags.map { it.title }

                    // Extract demographic and formats information
                    val triple = helper.extractDemographicAndFormat(genreTitles, tagTitles)
                    val foundDemographic = triple.first
                    val foundFormats = triple.second

                    // Get all tags when GroupTags is disabled
                    val allTagsForGrouping = if (preferences.groupTags) {
                        emptyList()
                    } else {
                        genreTitles + tagTitles
                    }

                    // Get library information
                    val libraryName = meta.libraryName.takeIf { !it.isNullOrEmpty() }
                        ?: series.find { it.id == seriesId }?.libraryName
                        ?: runCatching {
                            val seriesDto = client.newCall(GET("$apiUrl/Series/$seriesId", headersBuilder().build()))
                                .execute().parseAs<SeriesDto>()
                            seriesDto.libraryName
                        }.getOrNull()

                    Log.d(LOG_TAG, "Checking webtoon detection for series $seriesId:")
                    Log.d(LOG_TAG, "  Genres: $genreTitles")
                    Log.d(LOG_TAG, "  Tags: $tagTitles")
                    Log.d(LOG_TAG, "  Library: $libraryName")
                    Log.d(LOG_TAG, "  Formats: $foundFormats")
                    Log.d(LOG_TAG, "  Demographic: $foundDemographic")
                    Log.d(LOG_TAG, "  AllTags (GroupTags off): $allTagsForGrouping")

                    val isWebtoonDetected = helper.isWebtoonOrLongStrip(
                        genres = genreTitles,
                        tags = tagTitles,
                        libraryName = libraryName,
                        format = foundFormats.firstOrNull(),
                        demographic = foundDemographic,
                        allTags = allTagsForGrouping,
                    )

                    Log.d(LOG_TAG, "Webtoon detected for series $seriesId: $isWebtoonDetected")
                    isWebtoonDetected
                }.getOrElse {
                    Log.e(LOG_TAG, "Error checking webtoon status for series $seriesId", it)
                    false
                }
            } ?: false

            // Pre-populate series map for this series ID to ensure context is available
            seriesId?.let { id: Int ->
                // First try from cache
                series.find { it.id == id }?.let { sDto: SeriesDto ->
                    helper.seriesMap.addSeries(sDto)
                } ?: run {
                    // If not in cache, fetch it before processing any volumes
                    runCatching {
                        val seriesDto = client.newCall(GET("$apiUrl/Series/$id", headersBuilder().build()))
                            .execute().parseAs<SeriesDto>()
                        helper.seriesMap.addSeries(seriesDto)
                    }.onFailure { e ->
                        Log.e(LOG_TAG, "Failed to fetch series $id for context", e)
                    }
                }
            }

            volumes.forEach { volume ->
                // Ensure we have the series name from cache before processing chapters
                seriesId?.let { id ->
                    series.find { it.id == id }?.let { seriesDto ->
                        helper.seriesMap.addSeries(seriesDto)
                    }
                }

                // Check if this is a Single File Volume
                val isSingleFileVolume = volume.chapters.size == 1 &&
                    volume.chapters.first().number == KavitaConstants.UNNUMBERED_VOLUME_STR

                if (isSingleFileVolume) {
                    val chapter = volume.chapters.first()
                    // Pass singleFileVolumeNumber explicitly to trigger SFV logic in helper
                    val sChapter = helper.chapterFromVolume(
                        chapter,
                        volume,
                        singleFileVolumeNumber = volume.minNumber,
                        libraryType = libraryType,
                        isWebtoon = isWebtoon,
                        mangaTitle = seriesName,
                        useReleaseDate = preferences.ReleaseDate,
                        chapterTitleFormat = preferences.chapterTitleFormat,
                        scanlatorFormat = preferences.scanlatorFormat,
                        volumePageCount = volume.pages,
                    )
                    sChapter.url = "/Chapter/${chapter.id}"

                    // For singleFileVolume, ensure the scanlator field reflects webtoon terminology
                    sChapter.scanlator = if (isWebtoon) "Season" else "Volume"
                    allChapters.add(sChapter)
                } else {
                    // Handle Regular Chapters, Issues, and Specials
                    volume.chapters.forEach { chapter ->
                        val sChapter = helper.chapterFromVolume(
                            chapter,
                            volume,
                            libraryType = libraryType,
                            isWebtoon = isWebtoon,
                            mangaTitle = seriesName,
                            useReleaseDate = preferences.ReleaseDate,
                            chapterTitleFormat = preferences.chapterTitleFormat,
                            scanlatorFormat = preferences.scanlatorFormat,
                        )

                        sChapter.url = "/Chapter/${chapter.id}"

                        allChapters.add(sChapter)
                    }
                }
            }

            // 1.0 (Chapter) > 0.0001 (Volume) > 0.00001 (Special)
            return allChapters.sortedByDescending { it.chapter_number }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Unhandled exception parsing chapters", e)
            throw IOException(intl["version_exceptions_chapters_parse"]) as Throwable
        }
    }

    /**
     * Fetches the "url" of each page from the chapter
     * **/
    override fun pageListRequest(chapter: SChapter): Request {
        // Handle reading list items differently
        if (chapter.url.contains("readingListId=")) {
            val seriesId = chapter.url.substringAfter("seriesId=").substringBefore("&").toInt()
            val readingListId = chapter.url.substringAfter("readingListId=").substringBefore("&").toInt()

            // Get the specific chapter ID from the reading list
            val itemsRequest = GET("$apiUrl/ReadingList/items?readingListId=$readingListId", headersBuilder().build())
            val items = client.newCall(itemsRequest).execute().parseAs<List<ReadingListItemDto>>()

            val chapterItem = items.find { it.seriesId == seriesId && it.chapterId != null }
                ?: throw IOException(intl["error_chapter_not_found"])

            return GET("$apiUrl/${chapterItem.chapterId}", headersBuilder().build())
        }

        // Handle volume URLs
        if (chapter.url.startsWith("Volume/")) {
            val volumeId = chapter.url.substringAfter("Volume/").substringBefore("_")
            return GET("$apiUrl/Volume/$volumeId", headersBuilder().build())
        }

        // Original handling for regular chapters
        val chapterId = chapter.url.substringBefore("_")
        return GET("$apiUrl/$chapterId", headersBuilder().build())
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        // Check if this is a reading list item (has readingListId in URL)
        if (chapter.url.contains("readingListId=")) {
            try {
                // Extract seriesId from the chapter URL
                val seriesId = chapter.url.substringAfter("seriesId=").substringBefore("&").toIntOrNull()
                val readingListId = chapter.url.substringAfter("readingListId=").substringBefore("&").toIntOrNull()

                if (seriesId == null || readingListId == null) {
                    throw IOException("Invalid reading list item URL")
                }

                // Get all items in the reading list to find the specific chapter
                val itemsRequest = GET("$apiUrl/ReadingList/items?readingListId=$readingListId", headersBuilder().build())
                val items = client.newCall(itemsRequest).execute().parseAs<List<ReadingListItemDto>>()

                // Find our specific item in the reading list
                val listItem = items.find { item ->
                    when {
                        chapter.url.contains("chapterId=") ->
                            item.seriesId == seriesId && item.chapterId == chapter.url.substringAfter("chapterId=").substringBefore("&").toIntOrNull()
                        chapter.url.contains("volumeId=") ->
                            item.seriesId == seriesId && item.volumeId == chapter.url.substringAfter("volumeId=").substringBefore("&").toIntOrNull()
                        else -> false
                    }
                } ?: throw IOException("Item not found in reading list")

                // Get item details based on type
                val chapterDetails = when {
                    listItem.chapterId != null -> {
                        // Standard chapter
                        val request = GET("$apiUrl/Chapter?chapterId=${listItem.chapterId}", headersBuilder().build())
                        client.newCall(request).execute().parseAs<ChapterDto>()
                    }
                    listItem.volumeId != null -> {
                        // Volume - get first chapter
                        val volumeRequest = GET("$apiUrl/Volume/${listItem.volumeId}", headersBuilder().build())
                        val volume = client.newCall(volumeRequest).execute().parseAs<VolumeDto>()
                        volume.chapters.firstOrNull()?.let {
                            val chapRequest = GET("$apiUrl/Chapter?chapterId=${it.id}", headersBuilder().build())
                            client.newCall(chapRequest).execute().parseAs<ChapterDto>()
                        } ?: throw IOException(intl["error_no_chapters_found"])
                    }
                    else -> throw IOException(intl["error_invalid_reading_list_item"])
                }

                // Generate pages with consistent URL format
                return@withContext (0 until chapterDetails.pages).map { i ->
                    Page(
                        index = i,
                        imageUrl = "$apiUrl/Reader/image?chapterId=${chapterDetails.id}&page=$i&extractPdf=true&apiKey=$apiKey",
                    )
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error processing reading list item", e)
                throw e
            }
        }

        // For normal titles
        try {
            // Check if this is a volume URL
            if (chapter.url.contains("/Volume/") || chapter.url.startsWith("volume_")) {
                val volumeId = when {
                    chapter.url.startsWith("volume_") -> {
                        val parts = chapter.url.substringAfter("volume_").split("_", "?")
                        parts.firstOrNull() ?: throw IOException("Invalid volume URL format")
                    }
                    chapter.url.contains("/Volume/") -> {
                        val parts = chapter.url.substringAfter("/Volume/").split("_", "?")
                        parts.firstOrNull() ?: throw IOException("Invalid volume URL format")
                    }
                    else -> throw IOException("Invalid volume URL format")
                }

                // Get all pages in this volume
                val volumeRequest = GET("$apiUrl/Volume/$volumeId", headersBuilder().build())
                val volume = client.newCall(volumeRequest).execute().parseAs<VolumeDto>()
                val matchingChapter = volume.chapters.firstOrNull() // or match a chapterId if available
                    ?: throw IOException(intl["error_no_chapters_found"])

                val initialPages = (0 until matchingChapter.pages).map { i ->
                    Page(
                        index = i,
                        imageUrl = "$apiUrl/Reader/image?chapterId=${matchingChapter.id}&page=$i&extractPdf=true&apiKey=$apiKey",
                    )
                }

                val remainingPages = volume.chapters
                    .sortedBy { it.number.toFloatOrNull() ?: 0f }
                    .runningFold(initialPages.size) { acc, chapter -> acc + chapter.pages }
                    .drop(1)
                    .zip(volume.chapters.sortedBy { it.number.toFloatOrNull() ?: 0f })
                    .flatMap { (offset, chapter) ->
                        (0 until chapter.pages).map { i ->
                            Page(
                                index = offset - chapter.pages + i,
                                imageUrl = "$apiUrl/Reader/image?chapterId=${chapter.id}&page=$i&extractPdf=true&apiKey=$apiKey",
                            )
                        }
                    }

                return@withContext (initialPages + remainingPages).toList()
            } else {
                // Original chapter handling
                val chapterId = when {
                    chapter.url.startsWith("chapter_") -> chapter.url.substringAfter("chapter_").substringBefore("_").substringBefore("?")
                    chapter.url.contains("/Chapter/") -> chapter.url.substringAfter("/Chapter/").substringBefore("?")
                    else -> throw IOException("Invalid chapter URL format")
                }

                val chapterRequest = GET("$apiUrl/Chapter?chapterId=$chapterId", headersBuilder().build())
                val response = client.newCall(chapterRequest).execute()

                if (!response.isSuccessful) {
                    throw IOException("Failed to fetch chapter details: HTTP ${response.code}")
                }

                val chapterDetails = try {
                    response.parseAs<ChapterDto>()
                } catch (e: Exception) {
                    val errorMessage = try {
                        "${intl["version_exceptions_chapters_parse"]}: ${e.message}"
                    } catch (ex: Exception) {
                        "Failed to parse chapter details: ${e.message}"
                    }
                    throw IOException(errorMessage)
                }

                return@withContext (0 until chapterDetails.pages).map { i ->
                    Page(
                        index = i,
                        imageUrl = "$apiUrl/Reader/image?chapterId=$chapterId&page=$i&extractPdf=true&apiKey=$apiKey",
                    )
                }
            }
        } catch (e: Exception) {
            // Fallback to using the scanlator field if we can't get chapter details
            Log.e(LOG_TAG, "Error fetching chapter details, using fallback", e)
            val fallbackPageCount = chapter.scanlator?.replace(" pages", "")?.toIntOrNull() ?: 1
            return@withContext (0 until fallbackPageCount).map { i ->
                Page(
                    index = i,
                    imageUrl = "$apiUrl/Reader/image?chapterId=${chapter.url.substringBefore("_")}&page=$i&extractPdf=true&apiKey=$apiKey",
                )
            }
        }
    }

    override fun latestUpdatesParse(response: Response): MangasPage =
        throw UnsupportedOperationException("Not used")

    override fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException("Not used")

    override fun searchMangaParse(response: Response): MangasPage {
        return try {
            val result = response.parseAs<List<SeriesDto>>()
            // Refresh cache with search results for downstream lookups
            series = result
            result.forEach { helper.seriesMap.addSeries(it) }

            val allowed = preferences.allowedLibrariesSearch
            val filteredSeries = if (allowed.isEmpty()) {
                result
            } else {
                result.filter { it.libraryName != null && allowed.contains(it.libraryName) }
            }

            val mangaList = filteredSeries.map { helper.createSeriesDto(it, apiUrl, apiKey) }
            MangasPage(mangaList, false)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error parsing search results", e)
            // Return empty result instead of throwing exception
            MangasPage(emptyList(), false)
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    /*
     * FILTERING
     **/

    /** Some variable names already exist. im not good at naming add Meta suffix */
    private var genresListMeta = emptyList<MetadataGenres>()
    private var tagsListMeta = emptyList<MetadataTag>()
    private var ageRatingsListMeta = emptyList<MetadataAgeRatings>()
    private var peopleListMeta = emptyList<MetadataPeople>()
    private var pubStatusListMeta = emptyList<MetadataPubStatus>()
    private var languagesListMeta = emptyList<MetadataLanguages>()
    private var libraryListMeta = emptyList<MetadataLibrary>()
    private var collectionsListMeta = emptyList<MetadataCollections>()
    private var smartFilters = emptyList<SmartFilter>()
    private var cachedReadingLists: List<ReadingListDto> = emptyList()

    /**
     * Loads the filters if they are not empty so Mihon can show them to the user
     */
    private fun getFilterList(
        genres: List<String>,
        tags: List<String>,
        ageRatings: List<String>,
        collections: List<String>,
        languages: List<String>,
        libraries: List<String>,
        people: List<String>,
        pubStatus: List<String>,
        smartFilters: Array<String>,
    ): FilterList {
        return FilterList(
            SortFilter(sortableList.map { it.first }.toTypedArray()),

            StatusSeparator(),
            StatusFilterGroup(),

            // Release year filter
            ReleaseYearRangeGroup(
                listOf(
                    ReleaseYearRange("Min"),
                    ReleaseYearRange("Max"),
                ),
            ),

            GenreFilterGroup(genres.map { GenreFilter(it) }),
            TagFilterGroup(tags.map { TagFilter(it) }),
            CollectionFilterGroup(collections.map { CollectionFilter(it) }),
            LibrariesFilterGroup(libraries.map { LibraryFilter(it) }),
            LanguageFilterGroup(languages.map { LanguageFilter(it) }),
            PubStatusFilterGroup(pubStatus.map { PubStatusFilter(it) }),
            AgeRatingFilterGroup(ageRatings.map { AgeRatingFilter(it) }),

            UserRating(),
            UserRatingSeparator(),

            // Special filters section
            Filter.Header(intl["filters_special"]),
            SpecialListFilter(intl["filters_special_list"]),
            SmartFiltersFilter(smartFilters),

        )
    }

    override fun getFilterList(): FilterList {
        return getFilterList(
            genres = genresListMeta.map { it.title },
            tags = tagsListMeta.map { it.title },
            ageRatings = ageRatingsListMeta.map { it.title },
            collections = collectionsListMeta.map { it.title },
            languages = languagesListMeta.map { it.title },
            libraries = libraryListMeta.map { it.name },
            people = peopleListMeta.map { it.name },
            pubStatus = pubStatusListMeta.map { it.title },
            smartFilters = smartFilters.map { it.name }.toTypedArray(),
        )
    }

    class LoginErrorException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable) : this(null, cause)
    }

    class OpdsurlExistsInPref(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable) : this(null, cause)
    }

    class EmptyRequestBody(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable) : this(null, cause)
    }

    class LoadingFilterFailed(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable) : this(null, cause)
    }

    override fun headersBuilder(): Headers.Builder {
        if (jwtToken.isEmpty()) {
            doLogin()
            if (jwtToken.isEmpty()) throw LoginErrorException(intl["login_errors_header_token_empty"])
        }
        return Headers.Builder()
            .add("User-Agent", "Tachiyomi Kavita v${AppInfo.getVersionName()}")
            .add("Content-Type", "application/json")
            .add("Authorization", "Bearer $jwtToken")
    }

    private fun setupLoginHeaders(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", "Tachiyomi Kavita v${AppInfo.getVersionName()}")
            .add("Content-Type", "application/json")
            .add("Authorization", "Bearer $jwtToken")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val opdsAddressPref = screen.editTextPreference(
            ADDRESS_TITLE,
            "OPDS url",
            "",
            intl["pref_opds_summary"],
        )

        val customSourceNamePref = EditTextPreference(screen.context).apply {
            key = KavitaConstants.customSourceNamePref
            title = intl["pref_customsource_title"]
            summary = intl["pref_edit_customsource_summary"]
            setOnPreferenceChangeListener { _, newValue ->
                val res = preferences.edit()
                    .putString(KavitaConstants.customSourceNamePref, newValue.toString())
                    .commit()
                Toast.makeText(
                    screen.context,
                    intl["restartapp_settings"],
                    Toast.LENGTH_LONG,
                ).show()
                Log.v(LOG_TAG, "[Preferences] Successfully modified custom source name: $newValue")
                res
            }
        }
        screen.addPreference(customSourceNamePref)
        screen.addPreference(opdsAddressPref)

        SwitchPreferenceCompat(screen.context).apply {
            key = GROUP_TAGS_PREF
            title = intl["pref_group_tags_title"]
            summaryOn = intl["pref_group_tags_summary_on"]
            summaryOff = intl["pref_group_tags_summary_off"]
            setDefaultValue(GROUP_TAGS_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putBoolean(GROUP_TAGS_PREF, newValue as Boolean)
                    .commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = LAST_VOLUME_COVER_PREF
            title = intl["pref_last_volume_cover_title"]
            summaryOff = intl["pref_last_volume_cover_summary_off"]
            summaryOn = intl["pref_last_volume_cover_summary_on"]
            setDefaultValue(LAST_VOLUME_COVER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putBoolean(LAST_VOLUME_COVER_PREF, newValue as Boolean)
                    .commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_EPUB_PREF
            title = intl["pref_show_epub_title"]
            summaryOff = intl["pref_show_epub_summary_off"]
            summaryOn = intl["pref_show_epub_summary_on"]
            setDefaultValue(SHOW_EPUB_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putBoolean(SHOW_EPUB_PREF, newValue as Boolean)
                    .commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = RD_DATE_PREF
            title = intl["pref_rd_date_title"]
            summaryOff = intl["pref_rd_date_summary_off"]
            summaryOn = intl["pref_rd_date_summary_on"]
            setDefaultValue(RD_DATE_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putBoolean(RD_DATE_PREF, newValue as Boolean)
                    .commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = RELEASE_DATE_PREF
            title = intl["pref_release_date_title"]
            summaryOff = intl["pref_release_date_summary_off"]
            summaryOn = intl["pref_release_date_summary_on"]
            setDefaultValue(RELEASE_DATE_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putBoolean(RELEASE_DATE_PREF, newValue as Boolean)
                    .commit()
            }
        }.also(screen::addPreference)

        screen.addEditTextPreference(
            title = intl["pref_chapter_title_format_title"],
            default = KavitaConstants.CHAPTER_TITLE_FORMAT_DEFAULT,
            summary = intl.format("pref_chapter_title_format_summary", preferences.chapterTitleFormat),
            dialogMessage = intl["pref_chapter_title_format_dialog"],
            key = CHAPTER_TITLE_FORMAT_PREF,
            restartRequired = false,
        )

        screen.addEditTextPreference(
            title = intl["pref_scanlator_format_title"],
            default = KavitaConstants.SCANLATOR_FORMAT_DEFAULT,
            summary = intl.format("pref_scanlator_format_summary", preferences.scanlatorFormat),
            dialogMessage = intl["pref_scanlator_format_dialog"],
            key = SCANLATOR_FORMAT_PREF,
            restartRequired = false,
        )

        // Library filtering preferences
        val allowedLibrariesFeedPref = MultiSelectListPreference(screen.context).apply {
            key = KavitaConstants.allowedLibrariesFeedPref
            title = intl["pref_allowed_libraries_feed_title"]
            summary = intl["pref_allowed_libraries_feed_summary"]
            entries = libraryListMeta.map { it.name }.toTypedArray()
            entryValues = libraryListMeta.map { it.name }.toTypedArray()
            setDefaultValue(emptySet<String>())
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                val checkValue = newValue as Set<String>
                preferences.edit()
                    .putStringSet(KavitaConstants.allowedLibrariesFeedPref, checkValue)
                    .commit()
            }
        }

        val allowedLibrariesSearchPref = MultiSelectListPreference(screen.context).apply {
            key = KavitaConstants.allowedLibrariesSearchPref
            title = intl["pref_allowed_libraries_search_title"]
            summary = intl["pref_allowed_libraries_search_summary"]
            entries = libraryListMeta.map { it.name }.toTypedArray()
            entryValues = libraryListMeta.map { it.name }.toTypedArray()
            setDefaultValue(emptySet<String>())
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                val checkValue = newValue as Set<String>
                preferences.edit()
                    .putStringSet(KavitaConstants.allowedLibrariesSearchPref, checkValue)
                    .commit()
            }
        }

        screen.addPreference(allowedLibrariesFeedPref)
        screen.addPreference(allowedLibrariesSearchPref)

        SwitchPreferenceCompat(screen.context).apply {
            key = "reset_preferences"
            title = intl["pref_reset_title"]
            summary = intl["pref_reset_summary"]
            setOnPreferenceClickListener {
                AlertDialog.Builder(screen.context)
                    .setTitle(intl["dialog_reset_title"])
                    .setMessage(intl["dialog_reset_message"])
                    .setPositiveButton(intl["dialog_reset_positive"]) { _, _ ->
                        resetAllPreferences()
                        Toast.makeText(
                            screen.context,
                            intl["toast_settings_reset"],
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .setNegativeButton(intl["dialog_reset_negative"], null)
                    .show()
                true
            }
        }.also(screen::addPreference)
    }

    private fun PreferenceScreen.editTextPreference(
        preKey: String,
        title: String,
        default: String,
        summary: String,
        isPassword: Boolean = false,
    ): EditTextPreference {
        return EditTextPreference(context).apply {
            key = preKey
            this.title = title
            val input = preferences.getString(title, null)
            this.summary = if (input == null || input.isEmpty()) summary else input
            this.setDefaultValue(default)
            dialogTitle = title

            if (isPassword) {
                setOnBindEditTextListener {
                    it.inputType =
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val opdsUrlInPref = opdsUrlInPreferences(newValue.toString()) // We don't allow hot have multiple sources with same ip or domain
                    if (opdsUrlInPref.isNotEmpty()) {
                        // TODO("Add option to allow multiple sources with same url at the cost of tracking")
                        preferences.edit().putString(title, "").apply()

                        Toast.makeText(
                            context,
                            intl["pref_opds_duplicated_source_url"] + ": " + opdsUrlInPref,
                            Toast.LENGTH_LONG,
                        ).show()
                        throw OpdsurlExistsInPref(intl["pref_opds_duplicated_source_url"] + opdsUrlInPref)
                    }

                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(
                        context,
                        intl["restartapp_settings"],
                        Toast.LENGTH_LONG,
                    ).show()
                    setupLogin(newValue)
                    Log.v(LOG_TAG, "[Preferences] Successfully modified OPDS URL")
                    res
                } catch (e: OpdsurlExistsInPref) {
                    Log.e(LOG_TAG, "Url exists in a different sourcce")
                    false
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Unrecognised error", e)
                    false
                }
            }
        }
    }

    private fun getPrefBaseUrl(): String = preferences.getString("BASEURL", "") ?: ""
    private fun getPrefApiUrl(): String = preferences.getString("APIURL", "") ?: ""
    private fun getPrefKey(): String = preferences.getString("APIKEY", "") ?: ""

    private val SharedPreferences.groupTags: Boolean
        get() = getBoolean(GROUP_TAGS_PREF, GROUP_TAGS_DEFAULT)

    private val SharedPreferences.LastVolumeCover: Boolean
        get() = getBoolean(LAST_VOLUME_COVER_PREF, LAST_VOLUME_COVER_DEFAULT)

    private val SharedPreferences.RdDate: Boolean
        get() = getBoolean(RD_DATE_PREF, RD_DATE_DEFAULT)

    private val SharedPreferences.ReleaseDate: Boolean
        get() = getBoolean(RELEASE_DATE_PREF, RELEASE_DATE_DEFAULT)

    private val SharedPreferences.chapterTitleFormat: String
        get() = getString(CHAPTER_TITLE_FORMAT_PREF, CHAPTER_TITLE_FORMAT_DEFAULT)
            .orEmpty()
            .ifBlank { CHAPTER_TITLE_FORMAT_DEFAULT }

    private val SharedPreferences.scanlatorFormat: String
        get() = getString(SCANLATOR_FORMAT_PREF, SCANLATOR_FORMAT_DEFAULT) ?: SCANLATOR_FORMAT_DEFAULT

    // Library filtering preferences
    private val SharedPreferences.allowedLibrariesFeed: Set<String>
        get() = getStringSet(KavitaConstants.allowedLibrariesFeedPref, emptySet()) ?: emptySet()

    private val SharedPreferences.allowedLibrariesSearch: Set<String>
        get() = getStringSet(KavitaConstants.allowedLibrariesSearchPref, emptySet()) ?: emptySet()

    // Helper functions for library filtering
    private fun isLibraryAllowedForFeed(manga: SManga): Boolean {
        val allowedLibraries = preferences.allowedLibrariesFeed
        if (allowedLibraries.isEmpty()) return true // No filtering applied

        val seriesId = helper.getIdFromUrl(manga.url)
        val series = series.find { it.id == seriesId }

        // Debug logging
        Log.d(LOG_TAG, "Feed filtering check:")
        Log.d(LOG_TAG, "  Allowed libraries: $allowedLibraries")
        Log.d(LOG_TAG, "  Manga URL: ${manga.url}")
        Log.d(LOG_TAG, "  Extracted seriesId: $seriesId")
        Log.d(LOG_TAG, "  Found series: ${series?.name}")
        Log.d(LOG_TAG, "  Series libraryName: ${series?.libraryName}")

        if (series == null) {
            val allow = allowedLibraries.isEmpty()
            Log.w(
                LOG_TAG,
                "  Series not found in cache, ${if (allow) "no feed restriction set - allowing" else "blocking (cannot resolve library to apply feed filter)"}",
            )
            return allow
        }

        val libraryName = series.libraryName
        val isAllowed = allowedLibraries.contains(libraryName)
        Log.d(LOG_TAG, "  Library '$libraryName' allowed: $isAllowed")

        return isAllowed
    }

    private fun isLibraryAllowedForSearch(manga: SManga): Boolean {
        val allowedLibraries = preferences.allowedLibrariesSearch
        if (allowedLibraries.isEmpty()) return true // No filtering applied

        val seriesId = helper.getIdFromUrl(manga.url)
        val series = series.find { it.id == seriesId }
        if (series == null) {
            Log.d(LOG_TAG, "Search filter: series not in cache for url=${manga.url}, blocking because allowedLibrariesSearch is set.")
            return false
        }

        val libraryName = series.libraryName
        val allowed = libraryName != null && allowedLibraries.contains(libraryName)
        Log.d(LOG_TAG, "Search filter: library='$libraryName' allowed=$allowed")
        return allowed
    }

    private fun isLibraryAllowedForSuggestions(
        manga: SManga,
        currentMangaLibrary: String? = null,
        suggestedLibraryName: String? = null,
        suggestedLibraryId: Int? = null,
    ): Boolean {
        val allowedLibrariesFeed = preferences.allowedLibrariesFeed
        val allowedLibrariesSearch = preferences.allowedLibrariesSearch
        val seriesId = helper.getIdFromUrl(manga.url)

        val libraryName = suggestedLibraryName
            ?: series.find { it.id == seriesId }?.libraryName
            ?: libraryListMeta.find { it.id == suggestedLibraryId }?.name

        if (libraryName == null) {
            Log.d(LOG_TAG, "Suggestion '${manga.title}' BLOCKED: no library information available")
            return false
        }

        // Always allow suggestions from the same library as the current manga
        if (currentMangaLibrary != null && libraryName == currentMangaLibrary) {
            Log.d(LOG_TAG, "Suggestion '${manga.title}' ALLOWED: same library as current manga ($libraryName)")
            return true
        }

        // Prioritize feed restrictions (Latest/Popular)
        if (allowedLibrariesFeed.isNotEmpty()) {
            val isAllowedFeed = allowedLibrariesFeed.contains(libraryName)
            Log.d(LOG_TAG, "Suggestion '${manga.title}' from '$libraryName' ${if (isAllowedFeed) "ALLOWED" else "BLOCKED"} by feed allowance")
            return isAllowedFeed
        }

        // Fall back to search restrictions when feed is unrestricted
        if (allowedLibrariesSearch.isNotEmpty()) {
            val isAllowedSearch = allowedLibrariesSearch.contains(libraryName)
            Log.d(LOG_TAG, "Suggestion '${manga.title}' from '$libraryName' ${if (isAllowedSearch) "ALLOWED" else "BLOCKED"} by search allowance")
            return isAllowedSearch
        }

        // No restrictions configured
        Log.d(LOG_TAG, "Suggestion '${manga.title}' ALLOWED: no library restrictions configured")
        return true
    }

    // We strip the last slash since we will append it above
    private fun getPrefAddress(): String {
        var path = preferences.getString(ADDRESS_TITLE, "")!!
        if (path.isNotEmpty() && path.last() == '/') {
            path = path.substring(0, path.length - 1)
        }
        return path
    }

    private fun resetAllPreferences() {
        try {
            // Save the current template settings
            val currentChapterTitleFormat = preferences.chapterTitleFormat
            val currentScanlatorFormat = preferences.scanlatorFormat

            // Clear all preferences
            preferences.edit().clear().commit()

            // Restore template settings with defaults
            preferences.edit()
                .putString(CHAPTER_TITLE_FORMAT_PREF, currentChapterTitleFormat ?: KavitaConstants.CHAPTER_TITLE_FORMAT_DEFAULT)
                .putString(SCANLATOR_FORMAT_PREF, currentScanlatorFormat ?: KavitaConstants.SCANLATOR_FORMAT_DEFAULT)
                .commit()

            // Reset all in-memory state (for debug)
//            jwtToken = ""
//            isLogged = false
//            genresListMeta = emptyList()
//            tagsListMeta = emptyList()
//            ageRatingsListMeta = emptyList()
//            peopleListMeta = emptyList()
//            pubStatusListMeta = emptyList()
//            languagesListMeta = emptyList()
//            libraryListMeta = emptyList()
//            collectionsListMeta = emptyList()
//            smartFilters = emptyList()
//            cachedReadingLists = emptyList()

            Log.d(LOG_TAG, "Successfully reset all OPDS URLs and preferences")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error resetting preferences", e)
            throw IOException("Failed to reset preferences: ${e.message}")
        }
    }

    private fun getPrefApiKey(): String {
        // http(s)://host:(port)/api/opds/api-key
        val existingKey = preferences.getString(APIKEY, "")
        if (!existingKey.isNullOrEmpty()) return existingKey
        val address = preferences.getString(ADDRESS_TITLE, "") ?: ""
        val parts = address.split("/opds/")
        return if (parts.size > 1) parts[1] else ""
    }

    companion object {
        private const val ADDRESS_TITLE = "Address"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

        private const val APIKEY = "APIKEY"

        private const val LAST_VOLUME_COVER_PREF = "LastVolumeCover"
        const val LAST_VOLUME_COVER_DEFAULT = false

        private const val GROUP_TAGS_PREF = "GroupTags"
        const val GROUP_TAGS_DEFAULT = false

        private const val SHOW_EPUB_PREF = "showEpub"
        const val SHOW_EPUB_DEFAULT = false

        private const val RD_DATE_PREF = "RdDate"
        const val RD_DATE_DEFAULT = false

        private const val RELEASE_DATE_PREF = "ReleaseDate"
        const val RELEASE_DATE_DEFAULT = false

        private const val CHAPTER_TITLE_FORMAT_PREF = "chapterTitleFormat"
        const val CHAPTER_TITLE_FORMAT_DEFAULT = "\$CleanTitle"

        private const val SCANLATOR_FORMAT_PREF = "scanlatorFormat"
        const val SCANLATOR_FORMAT_DEFAULT = "\$Type"
    }

    /**
     * Used to check if a url is configured already in any of the sources
     * This is a limitation needed for tracking.
     * **/
    private fun opdsUrlInPreferences(url: String): String {
        fun getCleanedApiUrl(url: String): String = "${url.split("/api/").first()}/api"

        for (sourceId in 1..3) { // There's 3 sources so 3 preferences to check
            val sourceSuffixID by lazy {
                val key = "${"kavita_$sourceId"}/all/1" // Hardcoded versionID to 1
                val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
                (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
                    .reduce(Long::or) and Long.MAX_VALUE
            }
            val preferences: SharedPreferences by lazy {
                Injekt.get<Application>().getSharedPreferences("source_$sourceSuffixID", 0x0000)
            }
            val prefApiUrl = preferences.getString("APIURL", "")!!

            if (prefApiUrl.isNotEmpty()) {
                if (prefApiUrl == getCleanedApiUrl(url)) {
                    if (sourceId.toString() != suffix) {
                        return preferences.getString(KavitaConstants.customSourceNamePref, sourceId.toString())!!
                    }
                }
            }
        }
        return ""
    }

    /**
     * LOGIN
     * **/
    private fun setupLogin(addressFromPreference: String = "") {
        Log.v(LOG_TAG, "[Setup Login] Starting setup")
        val validAddress = address.ifEmpty { addressFromPreference }
        val tokens = validAddress.split("/api/opds/")
        val apiKey = tokens[1]
        val baseUrlSetup = tokens[0].replace("\n", "\\n")

        if (baseUrlSetup.toHttpUrlOrNull() == null) {
            Log.e(LOG_TAG, "Invalid URL $baseUrlSetup")
            throw Exception("${intl["login_errors_invalid_url"]}: $baseUrlSetup")
        }
        preferences.edit().putString("BASEURL", baseUrlSetup).apply()
        preferences.edit().putString("APIKEY", apiKey).apply()
        preferences.edit().putString("APIURL", "$baseUrlSetup/api").apply()
        Log.v(LOG_TAG, "[Setup Login] Setup successful")
    }

    private fun doLogin() {
        Log.d(LOG_TAG, "Attempting login with address: ${address.takeIf { it.isNotEmpty() } ?: "EMPTY"}")
        if (address.isEmpty()) {
            Log.e(LOG_TAG, "OPDS URL is empty or null")
            throw IOException(intl["pref_opds_must_setup_address"])
        }
        if (address.split("/opds/").size != 2) {
            throw IOException(intl["pref_opds_badformed_url"])
        }
        if (jwtToken.isEmpty()) setupLogin()
        Log.v(LOG_TAG, "[Login] Starting login")
        val request = POST(
            "$apiUrl/Plugin/authenticate?apiKey=${getPrefKey()}&pluginName=Tachiyomi-Kavita",
            setupLoginHeaders().build(),
            "{}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
        )
        client.newCall(request).execute().use {
            val peekbody = it.peekBody(Long.MAX_VALUE).toString()

            if (it.code == 200) {
                try {
                    jwtToken = it.parseAs<AuthenticationDto>().token
                    isLogged = true
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Possible outdated kavita", e)
                    throw IOException(intl["login_errors_parse_tokendto"])
                }
            } else {
                if (it.code == 500) {
                    Log.e(LOG_TAG, "[LOGIN] login failed. There was some error -> Code: ${it.code}.Response message: ${it.message} Response body: $peekbody.")
                    throw LoginErrorException(intl["login_errors_failed_login"])
                } else {
                    Log.e(LOG_TAG, "[LOGIN] login failed. Authentication was not successful -> Code: ${it.code}.Response message: ${it.message} Response body: $peekbody.")
                    throw LoginErrorException(intl["login_errors_failed_login"])
                }
            }
        }
        Log.v(LOG_TAG, "[Login] Login successful")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Add initialization state tracking
    private var isInitialized = false
    private var initializationError: Exception? = null

    init {
        if (apiUrl.isNotBlank()) {
            scope.launch {
                try {
                    Log.d(LOG_TAG, "Starting Kavita extension initialization")

                    // First attempt login with timeout
                    val loginJob = async { doLogin() }
                    try {
                        loginJob.await()
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Login failed during initialization", e)
                        initializationError = e
                        return@launch
                    }

                    // Load all filters in parallel with error handling
                    val deferredFilters = listOf(
                        async {
                            try {
                                genresListMeta = loadMetadata("$apiUrl/Metadata/genres")
                                Log.d(LOG_TAG, "Loaded ${genresListMeta.size} genres")
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Failed to load genres", e)
                            }
                        },
                        async {
                            try {
                                tagsListMeta = loadMetadata("$apiUrl/Metadata/tags")
                                Log.d(LOG_TAG, "Loaded ${tagsListMeta.size} tags")
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Failed to load tags", e)
                            }
                        },
                        async {
                            try {
                                ageRatingsListMeta = loadMetadata("$apiUrl/Metadata/age-ratings")
                                Log.d(LOG_TAG, "Loaded ${ageRatingsListMeta.size} age ratings")
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Failed to load age ratings", e)
                            }
                        },
                        async {
                            try {
                                collectionsListMeta = loadMetadata("$apiUrl/Collection")
                                Log.d(LOG_TAG, "Loaded ${collectionsListMeta.size} collections")
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Failed to load collections", e)
                            }
                        },
                        async {
                            try {
                                languagesListMeta = loadMetadata("$apiUrl/Metadata/languages")
                                Log.d(LOG_TAG, "Loaded ${languagesListMeta.size} languages")
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Failed to load languages", e)
                            }
                        },
                        async {
                            try {
                                libraryListMeta = loadMetadata("$apiUrl/Library/libraries")
                                Log.d(LOG_TAG, "Loaded ${libraryListMeta.size} libraries")
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Failed to load libraries", e)
                            }
                        },
                        async {
                            try {
                                peopleListMeta = loadMetadata("$apiUrl/Metadata/people")
                                Log.d(LOG_TAG, "Loaded ${peopleListMeta.size} people")
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Failed to load people", e)
                            }
                        },
                        async {
                            try {
                                pubStatusListMeta = loadMetadata("$apiUrl/Metadata/publication-status")
                                Log.d(LOG_TAG, "Loaded ${pubStatusListMeta.size} publication statuses")
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Failed to load publication statuses", e)
                            }
                        },
                        async {
                            try {
                                smartFilters = loadMetadata("$apiUrl/filter")
                                Log.d(LOG_TAG, "Loaded ${smartFilters.size} smart filters")
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Failed to load smart filters", e)
                            }
                        },
                    )

                    deferredFilters.awaitAll()

                    // Get server info with error handling
                    try {
                        val serverInfoDto = client.newCall(GET("$apiUrl/Server/server-info-slim", headersBuilder().build()))
                            .execute()
                            .parseAs<ServerInfoDto>()
                        Log.d(LOG_TAG, "Kavita version: ${serverInfoDto.kavitaVersion}")

                        // Mark as successfully initialized
                        isInitialized = true
                        Log.d(LOG_TAG, "Kavita extension initialization completed successfully")
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Failed to get server info", e)
                        // Don't fail initialization for server info
                        isInitialized = true
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Critical initialization error", e)
                    initializationError = e
                }
            }
        } else {
            Log.w(LOG_TAG, "API URL is blank, skipping initialization")
        }
    }

    // Add cleanup for the coroutine scope
    fun cleanup() {
        scope.cancel()
        // Clear any cached data if needed
        genresListMeta = emptyList()
        tagsListMeta = emptyList()
        ageRatingsListMeta = emptyList()
        peopleListMeta = emptyList()
        pubStatusListMeta = emptyList()
        languagesListMeta = emptyList()
        libraryListMeta = emptyList()
        collectionsListMeta = emptyList()
        smartFilters = emptyList()
        cachedReadingLists = emptyList()

        // Clear metadata cache
        metadataCache.clear()
        libraryTypeCache.clear()

        Log.d(LOG_TAG, "Cleanup completed - cache cleared")
    }

    // Cached metadata loading with TTL
    private inline fun <reified T> loadMetadata(url: String): T {
        cleanupExpiredCache()

        val cacheKey = "${T::class.simpleName}_$url"

        @Suppress("UNCHECKED_CAST")
        val cachedEntry = metadataCache[cacheKey] as? CacheEntry<T>

        if (cachedEntry != null && !cachedEntry.isExpired(CACHE_TTL)) {
            Log.d(LOG_TAG, "Using cached metadata for ${T::class.simpleName}")
            return cachedEntry.data
        }

        Log.d(LOG_TAG, "Fetching fresh metadata for ${T::class.simpleName} from $url")
        val data = client.newCall(GET(url, headersBuilder().build()))
            .execute()
            .parseAs<T>()

        metadataCache[cacheKey] = CacheEntry(data)
        return data
    }

    // Clear expired cache entries
    private fun cleanupExpiredCache() {
        val now = System.currentTimeMillis()
        val expiredKeys = mutableListOf<String>()
        metadataCache.entries.forEach { (key, entry) ->
            if (entry.isExpired(CACHE_TTL)) {
                expiredKeys.add(key)
            }
        }
        expiredKeys.forEach { key ->
            metadataCache.remove(key)
        }
    }

    // Get cache statistics for debugging
    private fun getCacheStats(): String {
        val totalEntries = metadataCache.size
        val expiredEntries = metadataCache.values.count { it.isExpired(CACHE_TTL) }
        return "Cache stats: $totalEntries total entries, $expiredEntries expired"
    }
}
