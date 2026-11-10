package ma.odema.rain.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import ma.odema.core.GeoPoint
import ma.odema.core.MoroccoCities
import ma.odema.core.OdemaJson
import ma.odema.core.Place
import ma.odema.core.UrlConnectionHttpClient
import ma.odema.core.farm.Campaigns
import ma.odema.core.farm.CropEntry
import ma.odema.core.farm.DailyOutlook
import ma.odema.core.farm.FarmAnalytics
import ma.odema.core.farm.FarmData
import ma.odema.core.farm.FarmSync
import ma.odema.core.farm.GaugeReading
import ma.odema.core.farm.Kml
import ma.odema.core.farm.KmlImport
import ma.odema.core.farm.KmlMerge
import ma.odema.core.farm.Parcel
import ma.odema.core.farm.RainSeries
import kotlinx.coroutines.launch
import ma.odema.core.ops.Col
import ma.odema.rain.OdemaApplication
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Sync bookkeeping, saved next to the data. */
@Serializable
data class FarmMeta(
    val lastSync: Long? = null,
    val chunksRemaining: Int? = null,
    val lastError: String? = null,
    val rateLimited: Boolean = false,
    /** The bundled ODEMA starter data is offered once, so deleting it sticks. */
    val seeded: Boolean = false,
    /** Version of the bundled data last merged in (new campaigns arrive with app updates). */
    val seedVersion: Int = 0,
)

data class FarmState(
    val loaded: Boolean = false,
    val data: FarmData = FarmData(),
    val series: Map<String, RainSeries> = emptyMap(),
    val outlooks: Map<String, DailyOutlook> = emptyMap(),
    val meta: FarmMeta = FarmMeta(),
    val syncing: Boolean = false,
) {
    fun parcel(id: String?): Parcel? = data.parcels.firstOrNull { it.id == id }
    fun seriesFor(p: Parcel): RainSeries? = p.historyKey?.let(series::get)
    fun outlookFor(p: Parcel): DailyOutlook? = p.historyKey?.let(outlooks::get)
}

/**
 * ODEMA's parcels, assolement, rain-gauge readings and the rainfall history of every
 * parcel since 1940. Everything is stored on the phone; nothing leaves it except the
 * coordinates sent to the weather APIs.
 */
class FarmRepository(private val app: OdemaApplication) {
    private val _state = MutableStateFlow(FarmState())
    val state: StateFlow<FarmState> = _state.asStateFlow()

    private val loadLock = Mutex()
    private val writeLock = Mutex()
    private val syncLock = Mutex()
    private val dir get() = File(app.filesDir, "odema").apply { mkdirs() }
    private val dataFile get() = File(dir, "farm.json")
    private val seriesFile get() = File(dir, "rain-history.json")
    private val outlookFile get() = File(dir, "outlooks.json")
    private val metaFile get() = File(dir, "meta.json")

    private val seriesSerializer = MapSerializer(String.serializer(), RainSeries.serializer())
    private val outlookSerializer = MapSerializer(String.serializer(), DailyOutlook.serializer())

    private fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? =
        file.takeIf { it.exists() }?.let { runCatching { OdemaJson.decodeFromString(serializer, it.readText()) }.getOrNull() }

    private fun <T> write(file: File, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(OdemaJson.encodeToString(serializer, value))
        tmp.renameTo(file)
    }

    suspend fun ensureLoaded() {
        if (_state.value.loaded) return
        loadLock.withLock {
            if (_state.value.loaded) return
            withContext(Dispatchers.IO) {
                var data = read(dataFile, FarmData.serializer()) ?: FarmData()
                var meta = read(metaFile, FarmMeta.serializer()) ?: FarmMeta()
                val seed = bundledSeed()
                if (!meta.seeded && data.parcels.isEmpty()) {
                    seed?.let { data = it }
                    meta = meta.copy(seeded = true, seedVersion = seed?.version ?: 0)
                    write(dataFile, FarmData.serializer(), data)
                    write(metaFile, FarmMeta.serializer(), meta)
                } else if (seed != null && seed.version > meta.seedVersion) {
                    // A newer bundled plan (e.g. next campaign's assolement): add what is new, keep the user's edits.
                    data = mergeSeed(data, seed)
                    meta = meta.copy(seeded = true, seedVersion = seed.version)
                    write(dataFile, FarmData.serializer(), data)
                    write(metaFile, FarmMeta.serializer(), meta)
                }
                _state.value = FarmState(
                    loaded = true,
                    data = data,
                    series = read(seriesFile, seriesSerializer).orEmpty(),
                    outlooks = read(outlookFile, outlookSerializer).orEmpty(),
                    meta = meta,
                )
            }
        }
    }

    /** Optional starter data shipped in the APK (assets/odema_seed.json, kept out of the public repo). */
    private fun bundledSeed(): FarmData? = runCatching {
        app.assets.open("odema_seed.json").bufferedReader().use { OdemaJson.decodeFromString(FarmData.serializer(), it.readText()) }
    }.getOrNull()

    private fun mergeSeed(data: FarmData, seed: FarmData): FarmData {
        val parcelIds = data.parcels.map { it.id }.toSet()
        val cropIds = data.crops.map { it.id }.toSet()
        val gaugeIds = data.gauges.map { it.id }.toSet()
        return data.copy(
            parcels = data.parcels + seed.parcels.filter { it.id !in parcelIds },
            crops = data.crops + seed.crops.filter { it.id !in cropIds && it.parcelId in parcelIds + seed.parcels.map { p -> p.id } },
            gauges = data.gauges + seed.gauges.filter { it.id !in gaugeIds },
        )
    }

    /** Receives every local change (collection, id, JSON or null when deleted). Set by the team sync. */
    @Volatile var sink: ((Col, String, String?) -> Unit)? = null

    private fun docs(data: FarmData, col: Col): Map<String, String> = when (col) {
        Col.PARCELS -> data.parcels.associate { it.id to OdemaJson.encodeToString(Parcel.serializer(), it) }
        Col.CROPS -> data.crops.associate { it.id to OdemaJson.encodeToString(CropEntry.serializer(), it) }
        Col.GAUGES -> data.gauges.associate { it.id to OdemaJson.encodeToString(GaugeReading.serializer(), it) }
        else -> emptyMap()
    }

    /** Local records, as JSON, for the first upload when the team sync is switched on. */
    fun localDocs(col: Col): Map<String, String> = docs(_state.value.data, col)

    /** A coworker's change. Nothing is sent back. */
    fun applyRemote(col: Col, id: String, json: String?) {
        fun <T> merge(list: List<T>, idOf: (T) -> String, decode: (String) -> T): List<T> {
            val item = json?.let { runCatching { decode(it) }.getOrNull() }
            if (json != null && item == null) return list
            val rest = list.filterNot { idOf(it) == id }
            return if (item == null) rest else if (list.any { idOf(it) == id }) list.map { if (idOf(it) == id) item else it } else rest + item
        }
        val before = _state.value.data
        _state.update { s ->
            val d = s.data
            s.copy(data = when (col) {
                Col.PARCELS -> d.copy(parcels = merge(d.parcels, { it.id }) { OdemaJson.decodeFromString(Parcel.serializer(), it) })
                Col.CROPS -> d.copy(crops = merge(d.crops, { it.id }) { OdemaJson.decodeFromString(CropEntry.serializer(), it) })
                Col.GAUGES -> d.copy(gauges = merge(d.gauges, { it.id }) { OdemaJson.decodeFromString(GaugeReading.serializer(), it) })
                else -> d
            })
        }
        if (_state.value.data != before) app.scope.launch { saveData() }
    }

    private suspend fun saveData() = writeLock.withLock {
        withContext(Dispatchers.IO) { write(dataFile, FarmData.serializer(), _state.value.data) }
    }

    private suspend fun saveHistory() = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val s = _state.value
            write(seriesFile, seriesSerializer, s.series)
            write(outlookFile, outlookSerializer, s.outlooks)
            write(metaFile, FarmMeta.serializer(), s.meta)
        }
    }

    private suspend fun edit(transform: (FarmData) -> FarmData) {
        ensureLoaded()
        var old = FarmData()
        var new = FarmData()
        _state.update { old = it.data; new = transform(it.data); it.copy(data = new) }
        saveData()
        val push = sink ?: return
        for (col in listOf(Col.PARCELS, Col.CROPS, Col.GAUGES)) {
            val before = docs(old, col)
            val after = docs(new, col)
            after.filter { (id, json) -> before[id] != json }.forEach { (id, json) -> push(col, id, json) }
            before.keys.filter { it !in after }.forEach { push(col, it, null) }
        }
    }

    /** Swappable so tests can serve recorded archive responses. */
    @androidx.annotation.VisibleForTesting
    internal var client: ma.odema.core.HttpClient = UrlConnectionHttpClient(timeoutMs = 60_000)

    fun newId(): String = UUID.randomUUID().toString()

    // Parcels

    suspend fun importKml(bytes: ByteArray): KmlMerge {
        ensureLoaded()
        val placemarks = withContext(Dispatchers.Default) { Kml.parse(bytes) }
        require(placemarks.isNotEmpty()) { "no placemarks" }
        val merge = KmlImport.merge(_state.value.data.parcels, placemarks, ::newId)
        edit { it.copy(parcels = merge.parcels) }
        return merge
    }

    suspend fun addParcel(name: String, areaHa: Double?, point: GeoPoint?): Parcel {
        val parcel = Parcel(newId(), name.trim(), declaredAreaHa = areaHa, center = point)
        edit { it.copy(parcels = it.parcels + parcel) }
        return parcel
    }

    suspend fun updateParcel(id: String, transform: (Parcel) -> Parcel) =
        edit { d -> d.copy(parcels = d.parcels.map { if (it.id == id) transform(it) else it }) }

    suspend fun removeParcel(id: String) = edit { d ->
        d.copy(
            parcels = d.parcels.filterNot { it.id == id },
            crops = d.crops.filterNot { it.parcelId == id },
            gauges = d.gauges.filterNot { it.parcelId == id },
        )
    }

    // Assolement

    suspend fun saveCrop(entry: CropEntry) = edit { d ->
        val exists = d.crops.any { it.id == entry.id }
        d.copy(crops = if (exists) d.crops.map { if (it.id == entry.id) entry else it } else d.crops + entry)
    }

    suspend fun removeCrop(id: String) = edit { d -> d.copy(crops = d.crops.filterNot { it.id == id }) }

    /** Starts a campaign from the previous one's blocks (same names and areas, crop to fill in). */
    suspend fun copyBlocks(fromCampaign: Int, toCampaign: Int): Int {
        ensureLoaded()
        val source = _state.value.data.crops.filter { it.campaign == fromCampaign }
        val existing = _state.value.data.crops.filter { it.campaign == toCampaign }.map { it.parcelId to it.block }.toSet()
        val copies = source.filter { (it.parcelId to it.block) !in existing }.map {
            it.copy(id = newId(), campaign = toCampaign, crop = ma.odema.core.farm.Crop.AUTRE.name, variety = "", generation = "",
                sowingDate = null, harvestDate = null, yieldTPerHa = null, notes = "")
        }
        edit { d -> d.copy(crops = d.crops + copies) }
        return copies.size
    }

    // Rain gauge

    suspend fun saveGauge(reading: GaugeReading) = edit { d ->
        d.copy(gauges = d.gauges.filterNot { it.id == reading.id } + reading)
    }

    suspend fun removeGauge(id: String) = edit { d -> d.copy(gauges = d.gauges.filterNot { it.id == id }) }

    // Backup

    fun exportBackup(): String = OdemaJson.encodeToString(FarmData.serializer(), _state.value.data)

    suspend fun importBackup(text: String): FarmData {
        val incoming = OdemaJson.decodeFromString(FarmData.serializer(), text)
        edit { incoming }
        return incoming
    }

    fun csv(parcelId: String): String? {
        val s = _state.value
        val parcel = s.parcel(parcelId) ?: return null
        val series = s.seriesFor(parcel)
        val today = LocalDate.now().toEpochDay()
        val from = series?.firstDataDay ?: (today - 365)
        return FarmAnalytics.csv(parcel, series, s.data, from, today)
    }

    // Rain history

    /**
     * Updates the recent days and downloads missing history (newest first). Stops at the
     * daily API limit and continues on the next run, so the full 1940→today record of
     * every parcel arrives within a day or two.
     */
    suspend fun sync(budget: Int = 15) {
        ensureLoaded()
        if (!syncLock.tryLock()) return
        try {
            _state.update { it.copy(syncing = true) }
            val s = _state.value
            val now = System.currentTimeMillis() / 1000
            val result = FarmSync(client).run(
                parcels = s.data.parcels,
                series = s.series,
                outlooks = s.outlooks,
                today = LocalDate.now().toEpochDay(),
                now = now,
                budget = budget,
                onProgress = { partial -> _state.update { it.copy(series = partial) } },
            )
            _state.update {
                it.copy(
                    series = result.series,
                    outlooks = result.outlooks,
                    meta = it.meta.copy(
                        lastSync = now,
                        chunksRemaining = result.chunksRemaining,
                        lastError = result.error,
                        rateLimited = result.rateLimited,
                    ),
                )
            }
            saveHistory()
        } finally {
            _state.update { it.copy(syncing = false) }
            syncLock.unlock()
        }
    }

    /** Parcels as alert places (ODEMA mode alerts on parcels instead of towns). */
    fun alertPlaces(data: FarmData = _state.value.data): List<Place> = data.parcels.mapNotNull { p ->
        val point = p.location ?: return@mapNotNull null
        Place(
            id = p.id,
            name = p.name,
            point = point,
            region = MoroccoCities.nearest(point).first.region,
            alertsEnabled = p.alertsEnabled,
            renamed = true,
        )
    }

    fun currentCampaign(): Int = Campaigns.current()

    @androidx.annotation.VisibleForTesting
    internal fun replaceStateForTests(transform: (FarmState) -> FarmState) = _state.update(transform)
}
