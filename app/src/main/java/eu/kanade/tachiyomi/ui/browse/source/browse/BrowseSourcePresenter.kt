package eu.kanade.tachiyomi.ui.browse.source.browse

import android.os.Bundle
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.data.DatabaseHandler
import eu.kanade.data.exh.savedSearchMapper
import eu.kanade.domain.category.interactor.GetCategories
import eu.kanade.domain.chapter.interactor.GetChapterByMangaId
import eu.kanade.domain.chapter.interactor.SyncChaptersWithTrackServiceTwoWay
import eu.kanade.domain.manga.interactor.GetDuplicateLibraryManga
import eu.kanade.domain.manga.model.toDbManga
import eu.kanade.domain.track.interactor.InsertTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.database.models.toMangaInfo
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.toSManga
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.browse.source.filter.AutoComplete
import eu.kanade.tachiyomi.ui.browse.source.filter.AutoCompleteSectionItem
import eu.kanade.tachiyomi.ui.browse.source.filter.CheckboxItem
import eu.kanade.tachiyomi.ui.browse.source.filter.CheckboxSectionItem
import eu.kanade.tachiyomi.ui.browse.source.filter.GroupItem
import eu.kanade.tachiyomi.ui.browse.source.filter.HeaderItem
import eu.kanade.tachiyomi.ui.browse.source.filter.SelectItem
import eu.kanade.tachiyomi.ui.browse.source.filter.SelectSectionItem
import eu.kanade.tachiyomi.ui.browse.source.filter.SeparatorItem
import eu.kanade.tachiyomi.ui.browse.source.filter.SortGroup
import eu.kanade.tachiyomi.ui.browse.source.filter.SortItem
import eu.kanade.tachiyomi.ui.browse.source.filter.TextItem
import eu.kanade.tachiyomi.ui.browse.source.filter.TextSectionItem
import eu.kanade.tachiyomi.ui.browse.source.filter.TriStateItem
import eu.kanade.tachiyomi.ui.browse.source.filter.TriStateSectionItem
import eu.kanade.tachiyomi.util.chapter.ChapterSettingsHelper
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.withIOContext
import eu.kanade.tachiyomi.util.lang.withUIContext
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.logcat
import exh.log.xLogE
import exh.savedsearches.EXHSavedSearch
import exh.savedsearches.models.SavedSearch
import exh.source.isEhBasedSource
import exh.util.nullIfBlank
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import logcat.LogPriority
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import java.util.Date
import eu.kanade.domain.category.model.Category as DomainCategory

open class BrowseSourcePresenter(
    private val sourceId: Long,
    searchQuery: String? = null,
    // SY -->
    private val filters: String? = null,
    private val savedSearch: Long? = null,
    // SY <--
    private val sourceManager: SourceManager = Injekt.get(),
    private val db: DatabaseHelper = Injekt.get(),
    private val database: DatabaseHandler = Injekt.get(),
    private val prefs: PreferencesHelper = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getChapterByMangaId: GetChapterByMangaId = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
    private val syncChaptersWithTrackServiceTwoWay: SyncChaptersWithTrackServiceTwoWay = Injekt.get(),
) : BasePresenter<BrowseSourceController>() {

    /**
     * Selected source.
     */
    lateinit var source: CatalogueSource

    /**
     * Modifiable list of filters.
     */
    var sourceFilters = FilterList()
        set(value) {
            field = value
            filterItems = value.toItems()
        }

    var filterItems: List<IFlexible<*>> = emptyList()

    /**
     * List of filters used by the [Pager]. If empty alongside [query], the popular query is used.
     */
    var appliedFilters = FilterList()

    /**
     * Pager containing a list of manga results.
     */
    private lateinit var pager: Pager

    /**
     * Subscription for the pager.
     */
    private var pagerSubscription: Subscription? = null

    /**
     * Subscription for one request from the pager.
     */
    private var nextPageJob: Job? = null

    private val loggedServices by lazy { Injekt.get<TrackManager>().services.filter { it.isLogged } }

    // SY -->
    private val filterSerializer = FilterSerializer()
    // SY <--

    init {
        query = searchQuery ?: ""
    }

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        source = sourceManager.get(sourceId) as? CatalogueSource ?: return

        sourceFilters = source.getFilterList()

        // SY -->
        val savedSearchFilters = savedSearch
        val jsonFilters = filters
        if (savedSearchFilters != null) {
            runCatching {
                val savedSearch = runBlocking {
                    database.awaitOneOrNull {
                        saved_searchQueries.selectById(savedSearchFilters, savedSearchMapper)
                    }
                } ?: return@runCatching
                query = savedSearch.query.orEmpty()
                val filtersJson = savedSearch.filtersJson
                    ?: return@runCatching
                val filters = Json.decodeFromString<JsonArray>(filtersJson)
                filterSerializer.deserialize(sourceFilters, filters)
                appliedFilters = sourceFilters
            }
        } else if (jsonFilters != null) {
            runCatching {
                val filters = Json.decodeFromString<JsonArray>(jsonFilters)
                filterSerializer.deserialize(sourceFilters, filters)
                appliedFilters = sourceFilters
            }
        }

        database.subscribeToList { saved_searchQueries.selectBySource(source.id, savedSearchMapper) }
            .map { loadSearches(it) }
            .onEach {
                withUIContext {
                    view?.setSavedSearches(it)
                }
            }
            .launchIn(presenterScope)
        // SY <--

        if (savedState != null) {
            query = savedState.getString(::query.name, "")
        }

        restartPager()
    }

    override fun onSave(state: Bundle) {
        state.putString(::query.name, query)
        super.onSave(state)
    }

    /**
     * Restarts the pager for the active source with the provided query and filters.
     *
     * @param query the query.
     * @param filters the current state of the filters (for search mode).
     */
    fun restartPager(query: String = this.query, filters: FilterList = this.appliedFilters) {
        this.query = query
        this.appliedFilters = filters

        // Create a new pager.
        pager = createPager(query, filters)

        val sourceId = source.id

        val sourceDisplayMode = prefs.sourceDisplayMode()

        // Prepare the pager.
        pagerSubscription?.let { remove(it) }
        pagerSubscription = pager.results()
            .observeOn(Schedulers.io())
            // SY -->
            .map { (page, mangas, metadata) ->
                Triple(page, mangas.map { networkToLocalManga(it, sourceId) }, metadata)
            }
            // SY <--
            .doOnNext { initializeMangas(it.second) }
            // SY -->
            .map { (page, mangas, metadata) ->
                page to mangas.mapIndexed { index, manga ->
                    SourceItem(manga, sourceDisplayMode, metadata?.getOrNull(index))
                }
            }
            // SY <--
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeReplay(
                { view, (page, mangas) ->
                    view.onAddPage(page, mangas)
                },
                { _, error ->
                    logcat(LogPriority.ERROR, error)
                },
            )

        // Request first page.
        requestNext()
    }

    /**
     * Requests the next page for the active pager.
     */
    fun requestNext() {
        if (!hasNextPage()) return

        nextPageJob?.cancel()
        nextPageJob = launchIO {
            try {
                pager.requestNextPage()
            } catch (e: Throwable) {
                withUIContext { view?.onAddPageError(e) }
            }
        }
    }

    /**
     * Returns true if the last fetched page has a next page.
     */
    fun hasNextPage(): Boolean {
        return pager.hasNextPage
    }

    /**
     * Returns a manga from the database for the given manga from network. It creates a new entry
     * if the manga is not yet in the database.
     *
     * @param sManga the manga from the source.
     * @return a manga from the database.
     */
    private fun networkToLocalManga(sManga: SManga, sourceId: Long): Manga {
        var localManga = db.getManga(sManga.url, sourceId).executeAsBlocking()
        if (localManga == null) {
            val newManga = Manga.create(sManga.url, sManga.title, sourceId)
            newManga.copyFrom(sManga)
            val result = db.insertManga(newManga).executeAsBlocking()
            newManga.id = result.insertedId()
            localManga = newManga
        } else if (!localManga.favorite) {
            // if the manga isn't a favorite, set its display title from source
            // if it later becomes a favorite, updated title will go to db
            localManga.title = sManga.title
        }
        return localManga
    }

    /**
     * Initialize a list of manga.
     *
     * @param mangas the list of manga to initialize.
     */
    fun initializeMangas(mangas: List<Manga>) {
        presenterScope.launchIO {
            mangas.asFlow()
                .filter { it.thumbnail_url == null && !it.initialized }
                .map { getMangaDetails(it) }
                .onEach {
                    withUIContext {
                        @Suppress("DEPRECATION")
                        view?.onMangaInitialized(it)
                    }
                }
                .catch { e -> logcat(LogPriority.ERROR, e) }
                .collect()
        }
    }

    /**
     * Returns the initialized manga.
     *
     * @param manga the manga to initialize.
     * @return the initialized manga
     */
    private suspend fun getMangaDetails(manga: Manga): Manga {
        try {
            val networkManga = source.getMangaDetails(manga.toMangaInfo())
            manga.copyFrom(networkManga.toSManga())
            manga.initialized = true
            db.insertManga(manga).executeAsBlocking()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
        return manga
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: Manga) {
        manga.favorite = !manga.favorite
        manga.date_added = when (manga.favorite) {
            true -> Date().time
            false -> 0
        }

        if (!manga.favorite) {
            manga.removeCovers(coverCache)
        } else {
            ChapterSettingsHelper.applySettingDefaults(manga)

            autoAddTrack(manga)
        }

        db.insertManga(manga).executeAsBlocking()
    }

    private fun autoAddTrack(manga: Manga) {
        launchIO {
            loggedServices
                .filterIsInstance<EnhancedTrackService>()
                .filter { it.accept(source) }
                .forEach { service ->
                    try {
                        service.match(manga)?.let { track ->
                            track.manga_id = manga.id!!
                            (service as TrackService).bind(track)
                            insertTrack.await(track.toDomainTrack()!!)

                            val chapters = getChapterByMangaId.await(manga.id!!)
                            syncChaptersWithTrackServiceTwoWay.await(chapters, track.toDomainTrack()!!, service)
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Could not match manga: ${manga.title} with service $service" }
                    }
                }
        }
    }

    /**
     * Set the filter states for the current source.
     *
     * @param filters a list of active filters.
     */
    fun setSourceFilter(filters: FilterList) {
        restartPager(filters = filters)
    }

    open fun createPager(query: String, filters: FilterList): Pager {
        // SY -->
        return if (source.isEhBasedSource()) {
            EHentaiPager(source, query, filters)
        } else {
            SourcePager(source, query, filters)
        }
        // SY <--
    }

    // SY -->
    companion object {
        // SY <--
        fun FilterList.toItems(): List<IFlexible<*>> {
            return mapNotNull { filter ->
                when (filter) {
                    is Filter.Header -> HeaderItem(filter)
                    // --> EXH
                    is Filter.AutoComplete -> AutoComplete(filter)
                    // <-- EXH
                    is Filter.Separator -> SeparatorItem(filter)
                    is Filter.CheckBox -> CheckboxItem(filter)
                    is Filter.TriState -> TriStateItem(filter)
                    is Filter.Text -> TextItem(filter)
                    is Filter.Select<*> -> SelectItem(filter)
                    is Filter.Group<*> -> {
                        val group = GroupItem(filter)
                        val subItems = filter.state.mapNotNull {
                            when (it) {
                                is Filter.CheckBox -> CheckboxSectionItem(it)
                                is Filter.TriState -> TriStateSectionItem(it)
                                is Filter.Text -> TextSectionItem(it)
                                is Filter.Select<*> -> SelectSectionItem(it)
                                // SY -->
                                is Filter.AutoComplete -> AutoCompleteSectionItem(it)
                                // SY <--
                                else -> null
                            }
                        }
                        subItems.forEach { it.header = group }
                        group.subItems = subItems
                        group
                    }
                    is Filter.Sort -> {
                        val group = SortGroup(filter)
                        val subItems = filter.values.map {
                            SortItem(it, group)
                        }
                        group.subItems = subItems
                        group
                    }
                }
            }
        }
        // SY -->
    }
    // SY <--

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<DomainCategory> {
        return getCategories.subscribe().firstOrNull() ?: emptyList()
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): Manga? {
        return getDuplicateLibraryManga.await(manga.title, manga.source)?.toDbManga()
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    suspend fun getMangaCategoryIds(manga: Manga): Array<Long?> {
        val categories = getCategories.await(manga.id!!)
        return categories.map { it.id }.toTypedArray()
    }

    /**
     * Move the given manga to categories.
     *
     * @param categories the selected categories.
     * @param manga the manga to move.
     */
    private fun moveMangaToCategories(manga: Manga, categories: List<Category>) {
        val mc = categories.filter { it.id != 0 }.map { MangaCategory.create(manga, it) }
        db.setMangaCategories(mc, listOf(manga))
    }

    /**
     * Move the given manga to the category.
     *
     * @param category the selected category.
     * @param manga the manga to move.
     */
    fun moveMangaToCategory(manga: Manga, category: Category?) {
        moveMangaToCategories(manga, listOfNotNull(category))
    }

    /**
     * Update manga to use selected categories.
     *
     * @param manga needed to change
     * @param selectedCategories selected categories
     */
    fun updateMangaCategories(manga: Manga, selectedCategories: List<Category>) {
        if (!manga.favorite) {
            changeMangaFavorite(manga)
        }

        moveMangaToCategories(manga, selectedCategories)
    }

    // EXH -->
    fun saveSearch(name: String, query: String, filterList: FilterList) {
        launchIO {
            kotlin.runCatching {
                database.await {
                    saved_searchQueries.insertSavedSearch(
                        _id = null,
                        source = source.id,
                        name = name.trim(),
                        query = query.nullIfBlank(),
                        filters_json = filterSerializer.serialize(filterList).ifEmpty { null }?.let { Json.encodeToString(it) },
                    )
                }
            }
        }
    }

    fun deleteSearch(searchId: Long) {
        launchIO {
            database.await { saved_searchQueries.deleteById(searchId) }
        }
    }

    suspend fun loadSearch(searchId: Long): EXHSavedSearch? {
        return withIOContext {
            val search = database.awaitOneOrNull {
                saved_searchQueries.selectById(searchId, savedSearchMapper)
            } ?: return@withIOContext null
            EXHSavedSearch(
                id = search.id!!,
                name = search.name,
                query = search.query.orEmpty(),
                filterList = runCatching {
                    val originalFilters = source.getFilterList()
                    filterSerializer.deserialize(
                        filters = originalFilters,
                        json = search.filtersJson
                            ?.let { Json.decodeFromString<JsonArray>(it) }
                            ?: return@runCatching null,
                    )
                    originalFilters
                }.getOrNull(),
            )
        }
    }

    suspend fun loadSearches(searches: List<SavedSearch>? = null): List<EXHSavedSearch> {
        return withIOContext {
            (searches ?: (database.awaitList { saved_searchQueries.selectBySource(source.id, savedSearchMapper) }))
                .map {
                    val filtersJson = it.filtersJson ?: return@map EXHSavedSearch(
                        id = it.id!!,
                        name = it.name,
                        query = it.query.orEmpty(),
                        filterList = null,
                    )
                    val filters = try {
                        Json.decodeFromString<JsonArray>(filtersJson)
                    } catch (e: Exception) {
                        xLogE("Failed to load saved search!", e)
                        null
                    } ?: return@map EXHSavedSearch(
                        id = it.id!!,
                        name = it.name,
                        query = it.query.orEmpty(),
                        filterList = null,
                    )

                    try {
                        val originalFilters = source.getFilterList()
                        filterSerializer.deserialize(originalFilters, filters)
                        EXHSavedSearch(
                            id = it.id!!,
                            name = it.name,
                            query = it.query.orEmpty(),
                            filterList = originalFilters,
                        )
                    } catch (t: RuntimeException) {
                        // Load failed
                        xLogE("Failed to load saved search!", t)
                        EXHSavedSearch(
                            id = it.id!!,
                            name = it.name,
                            query = it.query.orEmpty(),
                            filterList = null,
                        )
                    }
                }
        }
    }
    // EXH <--
}
