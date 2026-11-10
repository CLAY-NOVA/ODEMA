package ma.odema.rain.data

import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import ma.odema.core.AlertEngine
import ma.odema.core.AlertKind
import ma.odema.core.AlertMemory
import ma.odema.core.AlertSettings
import ma.odema.core.CurrentConditions
import ma.odema.core.DailyForecast
import ma.odema.core.ForecastReport
import ma.odema.core.ForecastService
import ma.odema.core.GeoPoint
import ma.odema.core.MoroccoBounds
import ma.odema.core.MoroccoCities
import ma.odema.core.MoroccoGeocoder
import ma.odema.core.MoroccoRegions
import ma.odema.core.OdemaJson
import ma.odema.core.Place
import ma.odema.core.UrlConnectionHttpClient
import ma.odema.core.distanceKm
import ma.odema.core.providers.OpenMeteoOverview
import ma.odema.rain.OdemaApplication
import ma.odema.rain.location.DeviceLocator
import ma.odema.rain.localized
import ma.odema.rain.notify.Notifier
import ma.odema.rain.widget.RainWidget
import java.io.File
import java.util.UUID

/** App-level preferences (alert thresholds live in [AlertSettings]). */
@Serializable
data class AppSettings(
    /** "" follows the phone; otherwise "ar", "fr" or "en". */
    val language: String = "",
    /** "system", "light" or "dark". */
    val theme: String = "system",
    val checkIntervalMinutes: Int = 30,
    val wifiOnly: Boolean = false,
    val pauseOnLowBattery: Boolean = true,
    /** Place shown in the home-screen widget; the first place if unset. */
    val widgetPlaceId: String? = null,
    val askedNotifications: Boolean = false,
    /** "general" (Moroccan towns) or "odema" (only ODEMA's parcels). */
    val mode: String = "general",
) {
    val odema: Boolean get() = mode == "odema"
}

@Serializable
data class OverviewEntry(val cityId: String, val current: CurrentConditions? = null, val daily: List<DailyForecast> = emptyList())

@Serializable
data class OverviewData(val fetchedAt: Long, val entries: List<OverviewEntry>)

data class AppState(
    val loaded: Boolean = false,
    val places: List<Place> = emptyList(),
    val alertSettings: AlertSettings = AlertSettings(),
    val appSettings: AppSettings = AppSettings(),
    val reports: Map<String, ForecastReport> = emptyMap(),
    val memories: Map<String, Map<AlertKind, AlertMemory>> = emptyMap(),
    val refreshing: Set<String> = emptySet(),
    val lastRefresh: Long? = null,
    /** ODEMA parcels with a location, as alert places. */
    val odemaPlaces: List<Place> = emptyList(),
) {
    /** What is watched right now: towns, or ODEMA's parcels in ODEMA mode. */
    val activePlaces: List<Place> get() = if (appSettings.odema) odemaPlaces else places
    val widgetPlace: Place? get() = activePlaces.firstOrNull { it.id == appSettings.widgetPlaceId } ?: activePlaces.firstOrNull()
    fun anyPlace(id: String?): Place? = places.firstOrNull { it.id == id } ?: odemaPlaces.firstOrNull { it.id == id }
}

/**
 * Single source of truth for places, settings and forecasts. The UI and the
 * background worker share it (same process), so both always see the same data.
 */
class RainRepository(private val app: OdemaApplication) {
    private val dataStore = PreferenceDataStoreFactory.create { app.preferencesDataStoreFile("odema") }
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val loadLock = Mutex()
    private val refreshLock = Mutex()
    private val writeLock = Mutex()
    /** Swappable so tests can serve recorded forecasts instead of calling the network. */
    @VisibleForTesting
    internal var service = ForecastService(UrlConnectionHttpClient())
    val geocoder = MoroccoGeocoder(UrlConnectionHttpClient())
    val locator = DeviceLocator(app)
    private val reportsFile get() = File(app.filesDir, "reports.json")
    private val overviewFile get() = File(app.cacheDir, "overview.json")

    private object Keys {
        val places = stringPreferencesKey("places")
        val alertSettings = stringPreferencesKey("alert_settings")
        val appSettings = stringPreferencesKey("app_settings")
        val memories = stringPreferencesKey("memories")
        val lastRefresh = longPreferencesKey("last_refresh")
    }

    private val placesSerializer = ListSerializer(Place.serializer())
    private val memoriesSerializer: KSerializer<Map<String, Map<AlertKind, AlertMemory>>> =
        MapSerializer(String.serializer(), MapSerializer(AlertKind.serializer(), AlertMemory.serializer()))
    private val reportsSerializer = MapSerializer(String.serializer(), ForecastReport.serializer())

    private fun <T> decode(serializer: KSerializer<T>, text: String?, fallback: T): T =
        text?.let { runCatching { OdemaJson.decodeFromString(serializer, it) }.getOrNull() } ?: fallback

    suspend fun ensureLoaded() {
        if (_state.value.loaded) return
        loadLock.withLock {
            if (_state.value.loaded) return
            val prefs = dataStore.data.first()
            val reports = withContext(Dispatchers.IO) {
                decode(reportsSerializer, reportsFile.takeIf { it.exists() }?.readText(), emptyMap())
            }
            _state.value = AppState(
                loaded = true,
                places = decode(placesSerializer, prefs[Keys.places], emptyList()),
                alertSettings = decode(AlertSettings.serializer(), prefs[Keys.alertSettings], AlertSettings()),
                appSettings = decode(AppSettings.serializer(), prefs[Keys.appSettings], AppSettings()),
                memories = decode(memoriesSerializer, prefs[Keys.memories], emptyMap()),
                reports = reports,
                lastRefresh = prefs[Keys.lastRefresh],
            )
        }
    }

    @VisibleForTesting
    internal fun replaceStateForTests(transform: (AppState) -> AppState) = _state.update(transform)

    private suspend fun save(reports: Boolean = false) = writeLock.withLock {
        val s = _state.value
        dataStore.edit {
            it[Keys.places] = OdemaJson.encodeToString(placesSerializer, s.places)
            it[Keys.alertSettings] = OdemaJson.encodeToString(AlertSettings.serializer(), s.alertSettings)
            it[Keys.appSettings] = OdemaJson.encodeToString(AppSettings.serializer(), s.appSettings)
            it[Keys.memories] = OdemaJson.encodeToString(memoriesSerializer, s.memories)
            s.lastRefresh?.let { t -> it[Keys.lastRefresh] = t }
        }
        if (reports) withContext(Dispatchers.IO) {
            val tmp = File(reportsFile.path + ".tmp")
            tmp.writeText(OdemaJson.encodeToString(reportsSerializer, s.reports))
            tmp.renameTo(reportsFile)
        }
    }

    // Editing places and settings

    enum class AddResult { ADDED, DUPLICATE, OUTSIDE_MOROCCO }

    suspend fun addPlace(place: Place): AddResult {
        ensureLoaded()
        if (!MoroccoBounds.contains(place.point)) return AddResult.OUTSIDE_MOROCCO
        val duplicate = _state.value.places.any {
            distanceKm(it.point, place.point) < 1.0 || (it.followsDevice && place.followsDevice)
        }
        if (duplicate) return AddResult.DUPLICATE
        _state.update { it.copy(places = it.places + place) }
        save()
        refresh(listOf(place.id), foreground = true)
        return AddResult.ADDED
    }

    fun newPlace(name: String, point: GeoPoint, elevation: Double? = null, followsDevice: Boolean = false): Place {
        val (city, km) = MoroccoCities.nearest(point)
        val isTown = km < 3.0 && !followsDevice
        return Place(
            id = UUID.randomUUID().toString(),
            name = name,
            point = point,
            cityId = if (isTown) city.id else null,
            region = city.region,
            elevation = elevation ?: if (km < 3.0) city.elevation else null,
            followsDevice = followsDevice,
        )
    }

    suspend fun updatePlace(id: String, transform: (Place) -> Place) {
        _state.update { s -> s.copy(places = s.places.map { if (it.id == id) transform(it) else it }) }
        save()
        RainWidget.updateAll(app)
    }

    suspend fun removePlace(id: String) {
        _state.update { s ->
            s.copy(
                places = s.places.filterNot { it.id == id },
                reports = s.reports - id,
                memories = s.memories - id,
                appSettings = if (s.appSettings.widgetPlaceId == id) s.appSettings.copy(widgetPlaceId = null) else s.appSettings,
            )
        }
        save(reports = true)
        RainWidget.updateAll(app)
    }

    suspend fun movePlace(id: String, delta: Int) {
        _state.update { s ->
            val list = s.places.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            val j = (i + delta).coerceIn(0, list.lastIndex)
            if (i >= 0 && i != j) list.add(j, list.removeAt(i))
            s.copy(places = list)
        }
        save()
    }

    suspend fun updateAlertSettings(transform: (AlertSettings) -> AlertSettings) {
        _state.update { it.copy(alertSettings = transform(it.alertSettings)) }
        save()
    }

    suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        _state.update { it.copy(appSettings = transform(it.appSettings)) }
        save()
        RainWidget.updateAll(app)
    }

    // Refreshing and alerting

    /**
     * Fetches every enabled source for the places (all by default) in parallel, decides
     * per place and alert type whether to notify, saves, and updates the widget.
     */
    suspend fun refresh(ids: Collection<String>? = null, foreground: Boolean = false, force: Boolean = false) {
        ensureLoaded()
        syncOdemaPlaces()
        if (foreground && !_state.value.appSettings.odema) updateFollowingPlaces()
        val targets = _state.value.activePlaces.filter { ids == null || it.id in ids }
        if (targets.isEmpty()) return
        val targetIds = targets.map { it.id }.toSet()
        _state.update { it.copy(refreshing = it.refreshing + targetIds) }
        try {
            refreshLock.withLock {
                val snapshot = _state.value
                val now = System.currentTimeMillis() / 1000
                val sources = snapshot.alertSettings.enabledSources
                val fresh = coroutineScope {
                    targets.map { place ->
                        async { place.id to service.report(place, sources, now, snapshot.reports[place.id], force) }
                    }.awaitAll()
                }.toMap()

                val context = app.localized(snapshot.appSettings.language)
                val reports = snapshot.reports.toMutableMap()
                val memories = snapshot.memories.toMutableMap()
                val pending = mutableListOf<Pair<ma.odema.core.AlertEvent, Place>>()
                for (place in targets) {
                    val report = fresh[place.id] ?: continue
                    // Offline: keep showing the last good forecast rather than a wall of errors.
                    if (report.hasData || reports[place.id] == null) reports[place.id] = report
                    if (!report.hasData) continue
                    val result = AlertEngine.evaluate(place, report, snapshot.alertSettings, memories[place.id].orEmpty(), now)
                    memories[place.id] = result.memory
                    result.events.forEach { pending += it to place }
                }
                // ODEMA parcels are close together: one notification per alert type, listing the parcels.
                if (snapshot.appSettings.odema) {
                    pending.groupBy { it.first.kind }.values.forEach { group ->
                        if (group.size == 1) Notifier.postAlert(context, group[0].first, group[0].second)
                        else Notifier.postAlertGroup(context, group)
                    }
                } else {
                    pending.forEach { (event, place) -> Notifier.postAlert(context, event, place) }
                }
                _state.update { s ->
                    val alive = (s.places + s.odemaPlaces).map { it.id }.toSet()
                    s.copy(
                        reports = reports.filterKeys { it in alive },
                        memories = memories.filterKeys { it in alive },
                        lastRefresh = now,
                    )
                }
                save(reports = true)
            }
        } finally {
            _state.update { it.copy(refreshing = it.refreshing - targetIds) }
            RainWidget.updateAll(app)
        }
    }

    /** Mirrors ODEMA parcels with a location into [AppState.odemaPlaces]. */
    suspend fun syncOdemaPlaces() {
        app.farm.ensureLoaded()
        val places = app.farm.alertPlaces()
        _state.update { it.copy(odemaPlaces = places) }
    }

    /** Moves "my location" places to where the phone is. Foreground only: we never ask for background location. */
    private suspend fun updateFollowingPlaces() {
        val following = _state.value.places.filter { it.followsDevice }
        if (following.isEmpty() || !locator.hasPermission()) return
        val fix = locator.current() ?: return
        val point = GeoPoint(fix.latitude, fix.longitude)
        if (!MoroccoBounds.contains(point)) return
        val (city, _) = MoroccoCities.nearest(point)
        _state.update { s ->
            s.copy(places = s.places.map {
                if (it.followsDevice) it.copy(point = point, region = city.region, elevation = null) else it
            })
        }
        save()
    }

    suspend fun postDailySummary() {
        ensureLoaded()
        syncOdemaPlaces()
        val s = _state.value
        Notifier.postDailySummary(app.localized(s.appSettings.language), s.activePlaces.filter { it.alertsEnabled }, s.reports)
    }

    // Morocco overview: the 12 regional capitals in one request, cached for an hour.

    suspend fun overview(force: Boolean = false): OverviewData {
        val now = System.currentTimeMillis() / 1000
        val cached = withContext(Dispatchers.IO) {
            runCatching { OdemaJson.decodeFromString(OverviewData.serializer(), overviewFile.readText()) }.getOrNull()
        }
        if (!force && cached != null && now - cached.fetchedAt < 3600) return cached
        val capitals = MoroccoRegions.all.mapNotNull { MoroccoCities.byId(it.capitalId) }
        val entries = try {
            OpenMeteoOverview.fetch(capitals.map { it.lat to it.lon }, UrlConnectionHttpClient())
        } catch (e: Exception) {
            if (cached != null) return cached
            throw e
        }
        val data = OverviewData(now, capitals.zip(entries).map { (c, e) -> OverviewEntry(c.id, e.current, e.daily) })
        withContext(Dispatchers.IO) {
            overviewFile.writeText(OdemaJson.encodeToString(OverviewData.serializer(), data))
        }
        return data
    }
}
