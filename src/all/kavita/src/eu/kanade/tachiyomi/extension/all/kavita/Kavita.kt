package eu.kanade.tachiyomi.extension.all.kavita

import android.app.AlertDialog
import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
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
import eu.kanade.tachiyomi.extension.all.kavita.dto.MetadataUserRatings
import eu.kanade.tachiyomi.extension.all.kavita.dto.PersonRole
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
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
import rx.Observable
import rx.Single
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.net.ConnectException
import java.security.MessageDigest
import java.util.Locale

class Kavita(private val suffix: String = "") : ConfigurableSource, UnmeteredSource, HttpSource() {
    private val helper = KavitaHelper()

    /** OkHttp client for network requests. */
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

    /**
     * Extension function to parse a network [Response] as a given type [T].
     * Throws IOException if the response is not successful or body is empty.
     */
    private inline fun <reified T> Response.parseAs(): T =
        use {
            if (!it.isSuccessful) {
                Log.e(LOG_TAG, "HTTP error ${it.code}: ${it.request.url}")
                throw IOException("HTTP error ${it.code}")
            }

            val body = it.body.string()
            if (body.isEmpty()) {
                Log.e(LOG_TAG, "Empty body for: ${it.request.url}")
                throw EmptyRequestBody("Empty response body")
            }
            json.decodeFromString(body)
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
    private fun fetch(request: Request): Observable<MangasPage> {
        return client.newCall(request)
            .asObservableSuccess()
            .onErrorResumeNext { throwable ->
                val field = throwable.javaClass.getDeclaredField("code")
                field.isAccessible = true
                try {
                    var code = field.get(throwable)
                    Log.e(LOG_TAG, "Error fetching manga: ${throwable.message}", throwable)
                    if (code as Int !in intArrayOf(401, 201, 500)) {
                        code = 500
                    }
                    return@onErrorResumeNext Observable.error(IOException("Http Error: $code\n ${helper.intl["http_errors_$code"]}\n${helper.intl["check_version"]}"))
                } catch (e: Exception) {
                    Log.e(LOG_TAG, e.toString(), e)
                    return@onErrorResumeNext Observable.error(e)
                }
            }
            .map { response ->
                popularMangaParse(response)
            }
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        replaceWith = ReplaceWith("getPopularManga(page)"),
    )
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return client.newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP error ${response.code}")
                }
                popularMangaParse(response)
            }
            .onErrorReturn { error ->
                Log.e(LOG_TAG, "Error fetching popular manga", error)
                MangasPage(emptyList(), false)
            }
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        replaceWith = ReplaceWith("getLatestUpdates(page)"),
    )
    override fun fetchLatestUpdates(page: Int) =
        fetch(latestUpdatesRequest(page))

    @Deprecated(
        "Use the non-RxJava API instead",
        replaceWith = ReplaceWith("getSearchManga(page, query, filters)"),
    )
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val specialListFilter = filters.find { it is SpecialListFilter } as? SpecialListFilter

        return when (specialListFilter?.state) {
            1 -> {
                // Want to Read selected
                val payload = """{
                "id": 0,
                "name": "",
                "statements": [],
                "combination": 0,
                "sortOptions": {
                    "sortField": 1,
                    "isAscending": true
                },
                "limitTo": 0
            }"""
                val request = POST(
                    "$apiUrl/want-to-read/v2?pageNumber=$page&pageSize=20",
                    headersBuilder().build(),
                    payload.toRequestBody(JSON_MEDIA_TYPE),
                )
                return client.newCall(request)
                    .asObservableSuccess()
                    .map { popularMangaParse(it) }
            }
            2 -> { // Reading Lists
                client.newCall(readingListRequest())
                    .asObservableSuccess()
                    .map { readingListParse(it) }
            }
            else -> { // Regular searches
                fetch(searchMangaRequest(page, query, filters))
            }
        }
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        replaceWith = ReplaceWith("getChapterList(manga)"),
    )
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return if (manga.url.contains("/ReadingList/")) {
            fetchReadingListItems(manga)
        } else {
            super.fetchChapterList(manga)
        }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        try {
            val result = response.parseAs<List<SeriesDto>>()
            series = result
            val mangaList = result.map { item -> helper.createSeriesDto(item, apiUrl, baseUrl, apiKey) }
            return MangasPage(mangaList, helper.hasNextPage(response))
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Unhandled exception", e)
            throw IOException(helper.intl["check_version"])
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
        if (genresListMeta.isEmpty() || tagsListMeta.isEmpty() /*...*/) {
            Log.w(LOG_TAG, "Metadata not loaded, retrying filters")
            getFilterList() // Re-initialize metadata
        }

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
                    return prepareRequest(page, json.encodeToJsonElement(decoded_filter).toString())
                } else {
                    Log.e(LOG_TAG, "Failed to decode SmartFilter: ${it.code}\n" + it.message)
                    throw IOException(helper.intl["version_exceptions_smart_filter"])
                }
            }
        }

        // Always build filterV2 from all filters
        val filterV2 = FilterV2Dto(
            sortOptions = SortOptions(SortFieldEnum.SortName.type, true),
            statements = mutableListOf(),
        )

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
                            val statusFilter = filter.state.firstOrNull() as? StatusFilter
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
//                                Log.d(LOG_TAG, "Checking genre: ${genreFilter.name}, state=${genreFilter.state}, match=${genre?.id}")
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
                            filter.state.forEach { tagFilter ->
                                val tag = tagsListMeta.find { it.title == (tagFilter as Filter.TriState).name }
                                tag?.let {
                                    when (tagFilter.state) {
                                        STATE_INCLUDE -> filterV2.addStatement(
                                            FilterComparison.Contains,
                                            FilterField.Tags,
                                            tag.id.toString(),
                                        )
                                        STATE_EXCLUDE -> filterV2.addStatement(
                                            FilterComparison.NotContains,
                                            FilterField.Tags,
                                            tag.id.toString(),
                                        )
                                    }
                                }
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
                            filter.state.forEach { libraryFilter ->
                                val library = libraryListMeta.find { it.name == (libraryFilter as Filter.TriState).name }
                                library?.let {
                                    when (libraryFilter.state) {
                                        STATE_INCLUDE -> filterV2.addStatement(
                                            FilterComparison.Contains,
                                            FilterField.Libraries,
                                            library.id.toString(),
                                        )
                                        STATE_EXCLUDE -> filterV2.addStatement(
                                            FilterComparison.NotContains,
                                            FilterField.Libraries,
                                            library.id.toString(),
                                        )
                                    }
                                }
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

        // @todo Apply search query and people filters via metadata filter
        if (query.isNotBlank()) {
            val queryLower = query.trim().lowercase()
            val matchedPeople = peopleListMeta.filter {
                it.name.equals(query.trim(), ignoreCase = true) ||
                    it.name.trim().lowercase().contains(queryLower)
            }

            if (matchedPeople.isNotEmpty()) {
                Log.d(LOG_TAG, "Matched people for query '$query': ${matchedPeople.map { it.name }}")
                matchedPeople.forEach { person ->
                    when (PersonRole.fromId(person.role ?: -1)) {
                        PersonRole.Writer -> filterV2.addStatement(FilterComparison.Matches, FilterField.Writers, person.name)
                        PersonRole.Penciller -> filterV2.addStatement(FilterComparison.Matches, FilterField.Penciller, person.name)
                        PersonRole.Inker -> filterV2.addStatement(FilterComparison.Matches, FilterField.Inker, person.name)
                        PersonRole.Colorist -> filterV2.addStatement(FilterComparison.Matches, FilterField.Colorist, person.name)
                        PersonRole.Letterer -> filterV2.addStatement(FilterComparison.Matches, FilterField.Letterer, person.name)
                        PersonRole.CoverArtist -> filterV2.addStatement(FilterComparison.Matches, FilterField.CoverArtist, person.name)
                        PersonRole.Editor -> filterV2.addStatement(FilterComparison.Matches, FilterField.Editor, person.name)
                        PersonRole.Publisher -> filterV2.addStatement(FilterComparison.Matches, FilterField.Publisher, person.name)
                        PersonRole.Character -> filterV2.addStatement(FilterComparison.Matches, FilterField.Characters, person.name)
                        PersonRole.Translator -> filterV2.addStatement(FilterComparison.Matches, FilterField.Translators, person.name)
                        else -> {
                            Log.d(LOG_TAG, "Unrecognized role for person: ${person.name}")
                        }
                    }
                }
            } else {
                Log.d(LOG_TAG, "No people matched query '$query'. Falling back to SeriesName search.")
                filterV2.addStatement(FilterComparison.Matches, FilterField.SeriesName, query)
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
        return prepareRequest(page, payload)
    }

    /*
     * MANGA DETAILS (metadata about series)
     * **/

    @Deprecated(
        "Use the non-RxJava API instead",
        replaceWith = ReplaceWith("getMangaDetails(manga)"),
    )
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val serieId = helper.getIdFromUrl(manga.url)

        return if (manga.url.contains("source=readinglist")) {
            val readingListId = manga.url.substringAfter("readingListId=").substringBefore("&").toIntOrNull()
            val now = java.util.Calendar.getInstance()

            Observable.fromCallable {
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
                        val endCal = java.util.Calendar.getInstance().apply {
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
                    manga.apply {
                        initialized = true
                    }
                }
            }.onErrorReturn { error ->
                Log.e(LOG_TAG, "Error fetching reading list details", error)
                manga.apply {
                    description = helper.intl["error_loading_reading_list"]
                    initialized = true
                }
            }
        } else {
            client.newCall(GET("$apiUrl/series/metadata?seriesId=$serieId", headersBuilder().build()))
                .asObservableSuccess()
                .map { response ->
                    mangaDetailsParse(response).apply { initialized = true }
                }
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
            throw IOException("Failed to parse series details")
        }
        val existingSeries = series.find { dto -> dto.id == result.seriesId }
        val ratingLine = try {
            val responsePlus = client.newCall(
                GET(
                    "$apiUrl/Metadata/series-detail-plus?seriesId=${result.seriesId}",
                    headersBuilder().build(),
                ),
            ).execute()

            val rawResponse = responsePlus.body.string()
            if (rawResponse.isBlank() || rawResponse.trim().startsWith("<")) {
                Log.e(LOG_TAG, "Invalid response for series-detail-plus: $rawResponse")
                // Optionally, show a default or skip averageScore
                ""
            } else {
                try {
                    val detailPlusWrapper = json.decodeFromString<SeriesDetailPlusWrapperDto>(rawResponse)
                    val score = detailPlusWrapper.series?.averageScore?.takeIf { it > 0f }
                        ?: detailPlusWrapper.ratings.firstOrNull()?.averageScore ?: 0f
                    if (score > 0) "⭐ Score: ${"%.1f".format(score)}\n" else ""
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error parsing series detail plus", e)
                    ""
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error fetching ratings", e)
            ""
        }
        if (existingSeries != null) {
            val demographicKeywords = listOf("Shounen", "Seinen", "Josei", "Shoujo", "Hentai", "Doujinshi")
            val genreTitles = result.genres.map { it.title }
            val tagTitles = result.tags.map { it.title }

            // Find the demographic, if any
            val foundDemographic = demographicKeywords.firstOrNull { demo ->
                genreTitles.any { it.equals(demo, ignoreCase = true) } ||
                    tagTitles.any { it.equals(demo, ignoreCase = true) }
            }

            // Remove demographic from genres and tags
            val filteredGenres = genreTitles.filterNot { it.equals(foundDemographic, ignoreCase = true) }
            val filteredTags = tagTitles
                .filterNot { it.equals(foundDemographic, ignoreCase = true) }
                .filterNot { tag -> filteredGenres.any { genre -> genre.equals(tag, ignoreCase = true) } }

            val manga = helper.createSeriesDto(existingSeries, apiUrl, baseUrl, apiKey)
            manga.title = existingSeries.name
            manga.url = "$baseUrl/library/${existingSeries.libraryId}/series/${result.seriesId}" // "$apiUrl/Series/${result.seriesId}"
            manga.artist = result.coverArtists.joinToString { it.name }
            manga.description = listOfNotNull(
                ratingLine.takeIf { it.isNotBlank() },
                result.summary,
            ).joinToString("\n")
            manga.author = result.writers.joinToString { it.name }

            manga.genre = if (preferences.groupTags) {
                buildList {
                    existingSeries.libraryName?.takeIf { it.isNotEmpty() }?.let { add("Type:$it") }
                    foundDemographic?.let { add("Demographic:$it") }
                    filteredGenres.forEach { add("Genres:$it") }
                    filteredTags.forEach { add("Tags:$it") }
                }.joinToString(", ")
            } else {
                (genreTitles + tagTitles).toSet().toList().sorted().joinToString(", ")
            }

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
            val demographicKeywords = listOf("Shounen", "Seinen", "Josei", "Shoujo", "Hentai", "Doujinshi")
            val genreTitles = result.genres.map { it.title }
            val tagTitles = result.tags.map { it.title }

            // Find the demographic, if any
            val foundDemographic = demographicKeywords.firstOrNull { demo ->
                genreTitles.any { it.equals(demo, ignoreCase = true) } ||
                    tagTitles.any { it.equals(demo, ignoreCase = true) }
            }

            // Remove demographic from genres and tags
            val filteredGenres = genreTitles.filterNot { it.equals(foundDemographic, ignoreCase = true) }
            val filteredTags = tagTitles
                .filterNot { it.equals(foundDemographic, ignoreCase = true) }
                .filterNot { tag -> filteredGenres.any { genre -> genre.equals(tag, ignoreCase = true) } }

            url = "$baseUrl/library/${result.getLibraryId(serieDto)}/series/${result.seriesId}" // "$apiUrl/Series/${result.seriesId}"
            artist = result.coverArtists.joinToString { it.name }
            description = listOfNotNull(
                ratingLine.takeIf { it.isNotBlank() },
                result.summary,
            ).joinToString("\n")
            author = result.writers.joinToString { it.name }

            genre = if (preferences.groupTags) {
                buildList {
                    result.getLibraryName(serieDto)?.takeIf { it.isNotEmpty() }?.let { add("Type:$it") }
                    foundDemographic?.let { add("Demographic:$it") }
                    filteredGenres.forEach { add("Genres:$it") }
                    filteredTags.forEach { add("Tags:$it") }
                }.joinToString(", ")
            } else {
                (genreTitles + tagTitles).toSet().toList().sorted().joinToString(", ")
            }

            title = serieDto.name
            thumbnail_url = "$apiUrl/image/series-cover?seriesId=${result.seriesId}&apiKey=$apiKey"
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
            val now = java.util.Calendar.getInstance()
            val mangaList = readingLists.map { list ->
                val hasDates = list.startingYear > 0 && list.endingYear > 0
                val status = if (hasDates) {
                    val endCal = java.util.Calendar.getInstance().apply {
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

    private fun fetchReadingListItems(manga: SManga): Observable<List<SChapter>> {
        val readingListId = manga.url.substringAfter("readingListId=").substringBefore("&")
        val request = GET("$apiUrl/ReadingList/items?readingListId=$readingListId", headersBuilder().build())

        return client.newCall(request)
            .asObservableSuccess()
            .map { parseReadingListItems(it) }
    }

    private fun parseReadingListItems(response: Response): List<SChapter> {
        val items = response.parseAs<List<ReadingListItemDto>>()
        val readingListId = response.request.url.queryParameter("readingListId")

        val firstItem = items.firstOrNull()
        val isWebtoon = firstItem?.let { item ->
            runCatching {
                val meta = client.newCall(GET("$apiUrl/series/metadata?seriesId=${item.seriesId}", headersBuilder().build()))
                    .execute().parseAs<SeriesDetailPlusDto>()

                val genreTitles = meta.genres.map { it.title }
                val tagTitles = meta.tags.map { it.title }
                val typeLabels = listOfNotNull(meta.libraryName)
                helper.isWebtoonOrLongStrip(genreTitles + tagTitles + typeLabels)
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
                        helper.createSeriesDto(seriesDto, apiUrl, baseUrl, apiKey)
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

                filteredRelated.map { (item, relation) ->
                    item.toSManga(baseUrl, apiUrl, apiKey).apply {
                        if (relation.isNotBlank()) {
                            title = "[$relation] $title"
                        } else {
                            Log.e(LOG_TAG, "No related title")
                        }
                    }
                }
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

    private fun getLibraryType(seriesId: Int): LibraryTypeEnum? {
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

                type?.let { libraryTypeCache[seriesId] = it }
                type
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error getting library type for series $seriesId", e)
                null
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        try {
            val seriesId = response.request.url.queryParameter("seriesId")?.toIntOrNull()
            val libraryType = seriesId?.let { getLibraryType(it) }
            val volumes = response.parseAs<List<VolumeDto>>()
            val chapters = mutableListOf<SChapter>()
            val volumeItems = mutableListOf<SChapter>()

            // Get the series name to use as mangaTitle
            val seriesName = seriesId?.let {
                runCatching {
                    client.newCall(GET("$apiUrl/Series/$seriesId", headersBuilder().build()))
                        .execute().parseAs<SeriesDto>().name
                }.getOrNull()
            } ?: ""

            // Get series metadata to determine if it's a webtoon
            val isWebtoon = seriesId?.let {
                runCatching {
                    val meta = client.newCall(GET("$apiUrl/series/metadata?seriesId=$it", headersBuilder().build()))
                        .execute().parseAs<SeriesDetailPlusDto>()
                    val genreTitles = meta.genres.map { it.title }
                    val tagTitles = meta.tags.map { it.title }
                    val typeLabels = listOfNotNull(meta.libraryName)
                    helper.isWebtoonOrLongStrip(genreTitles + tagTitles + typeLabels)
                }.getOrElse { false }
            } ?: false

            volumes.forEach { volume ->
                // This fixes specials being parsed as volumes sometimes
                if (volume.chapters.size == 1 && volume.minNumber.toInt() != KavitaConstants.SPECIAL_NUMBER) {
                    val chapter = volume.chapters.first()
                    val sChapter = helper.chapterFromVolume(
                        chapter,
                        volume,
                        singleFileVolumeNumber = volume.minNumber,
                        libraryType = libraryType,
                        isWebtoon = isWebtoon,
                        mangaTitle = seriesName,
                    )
                    sChapter.url = "/Chapter/${chapter.id}" // Needed to read volumes as chapters
                    sChapter.chapter_number = volume.minNumber.toFloat()
                    sChapter.scanlator = if (isWebtoon) "Season" else "Volume"
                    volumeItems.add(sChapter)
                } else {
                    volume.chapters.forEach { chapter ->
                        val type = ChapterType.of(chapter, volume, libraryType)
                        val sChapter = helper.chapterFromVolume(
                            chapter,
                            volume,
                            libraryType = libraryType,
                            isWebtoon = isWebtoon,
                            mangaTitle = seriesName,
                        )
                        if (type == ChapterType.SingleFileVolume) {
                            sChapter.url = "/Chapter/${chapter.id}" // Needed to read volumes as chapters
                            sChapter.chapter_number = volume.minNumber.toFloat()
                            volumeItems.add(sChapter)
                        } else {
                            chapters.add(sChapter)
                        }
                    }
                }
            }

            return when {
                // Case 1: Only chapters
                chapters.isNotEmpty() && volumeItems.isEmpty() ->
                    chapters.sortedByDescending { it.chapter_number }

                // Case 2: Only volumes - treat as chapters with positive numbers
                volumeItems.isNotEmpty() && chapters.isEmpty() ->
                    volumeItems.sortedByDescending { it.chapter_number }

                // Case 3: Mixed content - chapters first, then volumes
                else -> {
                    volumeItems.forEachIndexed { index, it ->
                        if (it.chapter_number <= 0f) {
                            it.chapter_number = chapters.size + index + 1f
                        }
                    }
                    (
                        chapters.sortedByDescending { it.chapter_number } +
                            volumeItems.sortedByDescending { it.chapter_number }
                        )
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Unhandled exception parsing chapters", e)
            throw IOException(helper.intl["version_exceptions_chapters_parse"])
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
                ?: throw IOException(helper.intl["error_chapter_not_found"])

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

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPageList(chapter)"))
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        // Check if this is a reading list item (has readingListId in URL)
        if (chapter.url.contains("readingListId=")) {
            return Observable.fromCallable {
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
                val chapterDetails = if (listItem.chapterId != null) {
                    val request = GET("$apiUrl/Chapter?chapterId=${listItem.chapterId}", headersBuilder().build())
                    client.newCall(request).execute().parseAs<ChapterDto>()
                } else if (listItem.volumeId != null) {
                    // For volumes, get all chapters and let user choose
                    val volumeRequest = GET("$apiUrl/Volume/${listItem.volumeId}", headersBuilder().build())
                    val volume = client.newCall(volumeRequest).execute().parseAs<VolumeDto>()
                    volume.chapters.firstOrNull() ?: throw IOException(helper.intl["error_no_chapters_found"])
                } else {
                    throw IOException(helper.intl["error_invalid_reading_list_item"])
                }

                (0 until chapterDetails.pages).map { i ->
                    Page(
                        index = i,
                        imageUrl = "$apiUrl/Reader/image?chapterId=${chapterDetails.id}&page=$i&extractPdf=true&apiKey=$apiKey",
                    )
                }
            }.onErrorResumeNext { error ->
                Log.e(LOG_TAG, "Error fetching reading list chapter pages", error)
                Observable.error(error)
            }
        }

        // For normal titles
        return Observable.fromCallable {
            // Check if this is a volume URL
            if (chapter.url.contains("/Volume/") || chapter.url.startsWith("volume_")) {
                val volumeId = when {
                    chapter.url.startsWith("volume_") -> chapter.url.substringAfter("volume_").substringBefore("_")
                    chapter.url.contains("/Volume/") -> chapter.url.substringAfter("/Volume/").substringBefore("_")
                    else -> throw IOException("Invalid volume URL format")
                }

                // Get all pages in this volume
                val volumeRequest = GET("$apiUrl/Volume/$volumeId", headersBuilder().build())
                val volume = client.newCall(volumeRequest).execute().parseAs<VolumeDto>()
                val matchingChapter = volume.chapters.firstOrNull() // or match a chapterId if available
                    ?: throw IOException(helper.intl["error_no_chapters_found"])

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

                (initialPages + remainingPages).toList()
            } else {
                // Original chapter handling
                val chapterId = when {
                    chapter.url.startsWith("chapter_") -> chapter.url.substringAfter("chapter_").substringBefore("_")
                    chapter.url.contains("/Chapter/") -> chapter.url.substringAfter("/Chapter/").substringBefore("_")
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
                    throw IOException("${helper.intl["error_failed_parse_chapter"]}: ${e.message}")
                }

                (0 until chapterDetails.pages).map { i ->
                    Page(
                        index = i,
                        imageUrl = "$apiUrl/Reader/image?chapterId=$chapterId&page=$i&extractPdf=true&apiKey=$apiKey",
                    )
                }
            }
        }.onErrorResumeNext { error ->
            // Fallback to using the scanlator field if we can't get chapter details
            Log.e(LOG_TAG, "Error fetching chapter details, using fallback", error)
            val fallbackPageCount = chapter.scanlator?.replace(" pages", "")?.toIntOrNull() ?: 1
            (0 until fallbackPageCount).map { i ->
                Page(
                    index = i,
                    imageUrl = "$apiUrl/Reader/image?chapterId=${chapter.url.substringBefore("_")}&page=$i&extractPdf=true&apiKey=$apiKey",
                )
            }.let { Observable.just(it) }
        }
    }

    override fun latestUpdatesParse(response: Response): MangasPage =
        throw UnsupportedOperationException("Not used")

    override fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException("Not used")

    override fun searchMangaParse(response: Response): MangasPage {
        return try {
            val result = response.parseAs<List<SeriesDto>>()
            val mangaList = result.map { helper.createSeriesDto(it, apiUrl, baseUrl, apiKey) }
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
    private var userRatingsListMeta = emptyList<MetadataUserRatings>()
    private var languagesListMeta = emptyList<MetadataLanguages>()
    private var libraryListMeta = emptyList<MetadataLibrary>()
    private var collectionsListMeta = emptyList<MetadataCollections>()
    private var smartFilters = emptyList<SmartFilter>()
    private var cachedReadingLists: List<ReadingListDto> = emptyList()

    /**
     * Loads the enabled filters if they are not empty so Mihon can show them to the user
     */
    override fun getFilterList(): FilterList {
        val toggledFilters = getToggledFilters()

        val filters = try {
            val filtersLoaded = mutableListOf<Filter<*>>()

            filtersLoaded.add(SortFilter(sortableList.map { it.first }.toTypedArray()))

            if (smartFilters.isNotEmpty()) {
                filtersLoaded.add(SmartFiltersFilter(smartFilters.map { it.name }.toTypedArray()))
            }

            filtersLoaded.add(SpecialListFilter())

            if (toggledFilters.contains("Read Status")) {
                filtersLoaded.add(StatusFilterGroup())
            }
            if (toggledFilters.contains("ReleaseYearRange")) {
                filtersLoaded.add(
                    ReleaseYearRangeGroup(
                        listOf("Min", "Max").map { ReleaseYearRange(it) },
                    ),
                )
            }

            if (genresListMeta.isNotEmpty() and toggledFilters.contains("Genres")) {
                filtersLoaded.add(
                    GenreFilterGroup(genresListMeta.map { GenreFilter(it.title) }),
                )
            }
            if (tagsListMeta.isNotEmpty() and toggledFilters.contains("Tags")) {
                filtersLoaded.add(
                    TagFilterGroup(tagsListMeta.map { TagFilter(it.title) }),
                )
            }
            if (ageRatingsListMeta.isNotEmpty() and toggledFilters.contains("Age Rating")) {
                filtersLoaded.add(
                    AgeRatingFilterGroup(ageRatingsListMeta.map { AgeRatingFilter(it.title) }),
                )
            }
            if (toggledFilters.contains("Format")) {
                filtersLoaded.add(
                    FormatsFilterGroup(
                        listOf(
                            "Image",
                            "Archive",
                            "Pdf",
                            "Epub",
                            "Unknown",
                        ).map { FormatFilter(it) },
                    ),
                )
            }
            if (collectionsListMeta.isNotEmpty() and toggledFilters.contains("Collections")) {
                filtersLoaded.add(
                    CollectionFilterGroup(collectionsListMeta.map { CollectionFilter(it.title) }),
                )
            }
            if (languagesListMeta.isNotEmpty() and toggledFilters.contains("Languages")) {
                filtersLoaded.add(
                    LanguageFilterGroup(languagesListMeta.map { LanguageFilter(it.title) }),
                )
            }
            if (libraryListMeta.isNotEmpty() and toggledFilters.contains("Libraries")) {
                filtersLoaded.add(
                    LibrariesFilterGroup(libraryListMeta.map { LibraryFilter(it.name) }),
                )
            }
            if (pubStatusListMeta.isNotEmpty() and toggledFilters.contains("Publication Status")) {
                filtersLoaded.add(
                    PubStatusFilterGroup(
                        listOf(
                            PubStatusFilter("Ongoing"),
                            PubStatusFilter("Hiatus"),
                            PubStatusFilter("Completed"),
                            PubStatusFilter("Cancelled"),
                            PubStatusFilter("Ended"),
                        ),
                    ),
                )
            }
            if (userRatingsListMeta.isNotEmpty() and toggledFilters.contains("Rating")) {
                filtersLoaded.add(
                    UserRating(),
                )
            }

            filtersLoaded
        } catch (e: Exception) {
            Log.e(LOG_TAG, "[FILTERS] Error while creating filter list", e)
            FilterList(emptyList())
        }
        return FilterList(filters)
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
            if (jwtToken.isEmpty()) throw LoginErrorException(helper.intl["login_errors_header_token_empty"])
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

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val opdsAddressPref = screen.editTextPreference(
            ADDRESS_TITLE,
            "OPDS url",
            "",
            helper.intl["pref_opds_summary"],
        )

        val enabledFiltersPref = MultiSelectListPreference(screen.context).apply {
            key = KavitaConstants.toggledFiltersPref
            title = helper.intl["pref_filters_title"]
            summary = helper.intl["pref_filters_summary"]
            entries = KavitaConstants.filterPrefEntries
            entryValues = KavitaConstants.filterPrefEntriesValue
            setDefaultValue(KavitaConstants.defaultFilterPrefEntries)
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                val checkValue = newValue as Set<String>
                preferences.edit()
                    .putStringSet(KavitaConstants.toggledFiltersPref, checkValue)
                    .commit()
            }
        }
        val customSourceNamePref = EditTextPreference(screen.context).apply {
            key = KavitaConstants.customSourceNamePref
            title = helper.intl["pref_customsource_title"]
            summary = helper.intl["pref_edit_customsource_summary"]
            setOnPreferenceChangeListener { _, newValue ->
                val res = preferences.edit()
                    .putString(KavitaConstants.customSourceNamePref, newValue.toString())
                    .commit()
                Toast.makeText(
                    screen.context,
                    helper.intl["restartapp_settings"],
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
            title = helper.intl["pref_group_tags_title"]
            summaryOn = helper.intl["pref_group_tags_summary_on"]
            summaryOff = helper.intl["pref_group_tags_summary_off"]
            setDefaultValue(GROUP_TAGS_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putBoolean(GROUP_TAGS_PREF, newValue as Boolean)
                    .commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = LAST_VOLUME_COVER_PREF
            title = helper.intl["pref_last_volume_cover_title"]
            summaryOff = helper.intl["pref_last_volume_cover_summary_off"]
            summaryOn = helper.intl["pref_last_volume_cover_summary_on"]
            setDefaultValue(LAST_VOLUME_COVER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putBoolean(LAST_VOLUME_COVER_PREF, newValue as Boolean)
                    .commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_EPUB_PREF
            title = helper.intl["pref_show_epub_title"]
            summaryOff = helper.intl["pref_show_epub_summary_off"]
            summaryOn = helper.intl["pref_show_epub_summary_on"]
            setDefaultValue(SHOW_EPUB_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putBoolean(SHOW_EPUB_PREF, newValue as Boolean)
                    .commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = RD_DATE_PREF
            title = helper.intl["pref_rd_date_title"]
            summaryOff = helper.intl["pref_rd_date_summary_off"]
            summaryOn = helper.intl["pref_rd_date_summary_on"]
            setDefaultValue(RD_DATE_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putBoolean(RD_DATE_PREF, newValue as Boolean)
                    .commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = "reset_preferences"
            title = helper.intl["pref_reset_title"]
            summary = helper.intl["pref_reset_summary"]
            setOnPreferenceClickListener {
                AlertDialog.Builder(screen.context)
                    .setTitle(helper.intl["dialog_reset_title"])
                    .setMessage(helper.intl["dialog_reset_message"])
                    .setPositiveButton(helper.intl["dialog_reset_positive"]) { _, _ ->
                        resetAllPreferences()
                        Toast.makeText(
                            screen.context,
                            helper.intl["toast_settings_reset"],
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .setNegativeButton(helper.intl["dialog_reset_negative"], null)
                    .show()
                true
            }
        }.also(screen::addPreference)

        screen.addPreference(enabledFiltersPref)
    }

    private fun androidx.preference.PreferenceScreen.editTextPreference(
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
                            helper.intl["pref_opds_duplicated_source_url"] + ": " + opdsUrlInPref,
                            Toast.LENGTH_LONG,
                        ).show()
                        throw OpdsurlExistsInPref(helper.intl["pref_opds_duplicated_source_url"] + opdsUrlInPref)
                    }

                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(
                        context,
                        helper.intl["restartapp_settings"],
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
    private fun getToggledFilters() = preferences.getStringSet(KavitaConstants.toggledFiltersPref, KavitaConstants.defaultFilterPrefEntries)!!

    private val SharedPreferences.groupTags: Boolean
        get() = getBoolean(GROUP_TAGS_PREF, GROUP_TAGS_DEFAULT)

    private val SharedPreferences.LastVolumeCover: Boolean
        get() = getBoolean(LAST_VOLUME_COVER_PREF, LAST_VOLUME_COVER_DEFAULT)

    private val SharedPreferences.RdDate: Boolean
        get() = getBoolean(RD_DATE_PREF, RD_DATE_DEFAULT)

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
            // Clear all preferences
            preferences.edit().clear().commit()

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
            throw Exception("${helper.intl["login_errors_invalid_url"]}: $baseUrlSetup")
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
            throw IOException(helper.intl["pref_opds_must_setup_address"])
        }
        if (address.split("/opds/").size != 2) {
            throw IOException(helper.intl["pref_opds_badformed_url"])
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
                    throw IOException(helper.intl["login_errors_parse_tokendto"])
                }
            } else {
                if (it.code == 500) {
                    Log.e(LOG_TAG, "[LOGIN] login failed. There was some error -> Code: ${it.code}.Response message: ${it.message} Response body: $peekbody.")
                    throw LoginErrorException(helper.intl["login_errors_failed_login"])
                } else {
                    Log.e(LOG_TAG, "[LOGIN] login failed. Authentication was not successful -> Code: ${it.code}.Response message: ${it.message} Response body: $peekbody.")
                    throw LoginErrorException(helper.intl["login_errors_failed_login"])
                }
            }
        }
        Log.v(LOG_TAG, "[Login] Login successful")
    }

    init {
        if (apiUrl.isNotBlank()) {
            Single.fromCallable {
                doLogin()
                try { // Get current version
                    val requestUrl = "$apiUrl/Server/server-info-slim"
                    val serverInfoDto = client.newCall(GET(requestUrl, headersBuilder().build()))
                        .execute()
                        .parseAs<ServerInfoDto>()
                    Log.e(
                        LOG_TAG,
                        "Extension version: code=${AppInfo.getVersionCode()}  name=${AppInfo.getVersionName()}" +
                            " - - Kavita version: ${serverInfoDto.kavitaVersion} - - Lang:${Locale.getDefault()}",
                    ) // this is not a real error. Using this so it gets printed in dump logs if there's any error
                } catch (e: EmptyRequestBody) {
                    Log.e(LOG_TAG, "Extension version: code=${AppInfo.getVersionCode()} - name=${AppInfo.getVersionName()}")
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Tachiyomi version: code=${AppInfo.getVersionCode()} - name=${AppInfo.getVersionName()}", e)
                }
                try { // Load Filters
                    // Genres
                    Log.v(LOG_TAG, "[Filter] Fetching filters ")
                    client.newCall(GET("$apiUrl/Metadata/genres", headersBuilder().build()))
                        .execute().use { response ->

                            genresListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "[Filter] Error decoding JSON for genres filter -> ${response.body}", e)
                                emptyList()
                            }
                        }
                    // tagsListMeta
                    client.newCall(GET("$apiUrl/Metadata/tags", headersBuilder().build()))
                        .execute().use { response ->
                            tagsListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "[Filter] Error decoding JSON for tagsList filter", e)
                                emptyList()
                            }
                        }
                    // age-ratings
                    client.newCall(GET("$apiUrl/Metadata/age-ratings", headersBuilder().build()))
                        .execute().use { response ->
                            ageRatingsListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    LOG_TAG,
                                    "[Filter] Error decoding JSON for age-ratings filter",
                                    e,
                                )
                                emptyList()
                            }
                        }
                    // collectionsListMeta
                    client.newCall(GET("$apiUrl/Collection", headersBuilder().build()))
                        .execute().use { response ->
                            collectionsListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    LOG_TAG,
                                    "[Filter] Error decoding JSON for collectionsListMeta filter",
                                    e,
                                )
                                emptyList()
                            }
                        }
                    // languagesListMeta
                    client.newCall(GET("$apiUrl/Metadata/languages", headersBuilder().build()))
                        .execute().use { response ->
                            languagesListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    LOG_TAG,
                                    "[Filter] Error decoding JSON for languagesListMeta filter",
                                    e,
                                )
                                emptyList()
                            }
                        }
                    // libraries
                    client.newCall(GET("$apiUrl/Library/libraries", headersBuilder().build()))
                        .execute().use { response ->
                            libraryListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    LOG_TAG,
                                    "[Filter] Error decoding JSON for libraries filter",
                                    e,
                                )
                                emptyList()
                            }
                        }
                    // peopleListMeta
                    client.newCall(GET("$apiUrl/Metadata/people", headersBuilder().build()))
                        .execute().use { response ->
                            peopleListMeta = try {
                                val jsonString = response.body.string()
//                                Log.d(LOG_TAG, "Raw people metadata: $jsonString") // Debug log
                                json.decodeFromString<List<MetadataPeople>>(jsonString)
                                    .filter { it.role != null } // Filter out entries without role
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Error decoding people metadata", e)
                                emptyList()
                            }
                        }
                    client.newCall(GET("$apiUrl/Metadata/publication-status", headersBuilder().build()))
                        .execute().use { response ->
                            if (!response.isSuccessful) {
                                Log.e(LOG_TAG, "Failed to fetch publication status: ${response.code}")
                                pubStatusListMeta = emptyList()
                                return@use
                            }
                            try {
                                pubStatusListMeta = response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Error decoding publication status JSON", e)
                                pubStatusListMeta = emptyList()
                            }
                        }
                    client.newCall(GET("$apiUrl/filter", headersBuilder().build()))
                        .execute().use { response ->
                            smartFilters = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    LOG_TAG,
                                    "error while decoding JSON for smartfilters",
                                    e,
                                )
                                emptyList()
                            }
                        }
                } catch (e: Exception) {
                    throw LoadingFilterFailed("Failed Loading Filters", e.cause)
                }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                    {},
                    { tr ->
                        // Avoid polluting logs with traces of exception
                        if (tr is EmptyRequestBody || tr is LoginErrorException) {
                            Log.e(LOG_TAG, "error while doing initial calls\n${tr.cause}")
                            return@subscribe
                        }
                        if (tr is ConnectException) { // avoid polluting logs with traces of exception
                            Log.e(LOG_TAG, "Error while doing initial calls\n${tr.cause}")
                            return@subscribe
                        }
                        Log.e(LOG_TAG, "error while doing initial calls", tr)
                    },
                )
        }
    }
}
