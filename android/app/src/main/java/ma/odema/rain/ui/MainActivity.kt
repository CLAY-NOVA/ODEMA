package ma.odema.rain.ui

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ma.odema.core.AlertSettings
import ma.odema.core.GeoPoint
import ma.odema.core.GeocodingResult
import ma.odema.core.MoroccoBounds
import ma.odema.core.MoroccoCities
import ma.odema.core.City
import ma.odema.core.Place
import ma.odema.rain.data.AppSettings
import ma.odema.rain.data.AppState
import ma.odema.rain.data.OverviewData
import ma.odema.rain.data.RainRepository
import ma.odema.rain.languageTag
import ma.odema.rain.localized
import ma.odema.rain.notify.Notifier
import ma.odema.rain.odema
import ma.odema.rain.work.WorkScheduler

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PLACE_ID = "place_id"
    }

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.getStringExtra(EXTRA_PLACE_ID)?.let(viewModel::openPlace)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val dark = when (state.appSettings.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            OdemaTheme(dark) { OdemaRoot(viewModel) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PLACE_ID)?.let(viewModel::openPlace)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForeground()
    }
}

private val OdemaLight = lightColorScheme(
    primary = ComposeColor(0xFF1F6FEB), onPrimary = ComposeColor.White,
    primaryContainer = ComposeColor(0xFFD8E6FF), onPrimaryContainer = ComposeColor(0xFF001B44),
    secondary = ComposeColor(0xFF2E7D5B), tertiary = ComposeColor(0xFFC1272D),
    background = ComposeColor(0xFFF8FAFF), surface = ComposeColor(0xFFF8FAFF),
    surfaceVariant = ComposeColor(0xFFE3E8F2), surfaceContainer = ComposeColor(0xFFEEF2FA),
    errorContainer = ComposeColor(0xFFFFE1DD), onErrorContainer = ComposeColor(0xFF5F1410),
)

private val OdemaDark = darkColorScheme(
    primary = ComposeColor(0xFF9EC2FF), onPrimary = ComposeColor(0xFF002F67),
    primaryContainer = ComposeColor(0xFF0F3E8C), onPrimaryContainer = ComposeColor(0xFFD8E6FF),
    secondary = ComposeColor(0xFF8FD4B2), tertiary = ComposeColor(0xFFFFB3AE),
    background = ComposeColor(0xFF10131A), surface = ComposeColor(0xFF10131A),
    surfaceVariant = ComposeColor(0xFF2B313D), surfaceContainer = ComposeColor(0xFF1A1F29),
    errorContainer = ComposeColor(0xFF5F1410), onErrorContainer = ComposeColor(0xFFFFDAD5),
)

@Composable
fun OdemaTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        android.os.Build.VERSION.SDK_INT >= 31 && dynamic -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> OdemaDark
        else -> OdemaLight
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

/** Our own blue palette reads better for weather than wallpaper colours; flip to use Material You. */
private const val dynamic = false

/** Screen state and actions; everything persistent lives in [RainRepository]. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.odema
    val repository: RainRepository = app.repository
    val state: StateFlow<AppState> = repository.state

    /** A place to open, from a notification or the widget. */
    val openPlaceRequest = MutableStateFlow<String?>(null)
    val overview = MutableStateFlow<OverviewState>(OverviewState.Idle)
    val message = MutableStateFlow<Message?>(null)

    sealed interface OverviewState {
        data object Idle : OverviewState
        data object Loading : OverviewState
        data class Ready(val data: OverviewData) : OverviewState
        data class Failed(val error: String) : OverviewState
    }

    sealed interface Message {
        data class Added(val name: String) : Message
        data class Duplicate(val name: String) : Message
        data object Outside : Message
        data object LocationFailed : Message
        data object TestSent : Message
    }

    init {
        viewModelScope.launch { repository.ensureLoaded() }
    }

    fun openPlace(id: String) { openPlaceRequest.value = id }

    fun onForeground() {
        viewModelScope.launch { repository.refresh(foreground = true) }
    }

    fun refresh(id: String? = null, force: Boolean = true) {
        viewModelScope.launch { repository.refresh(id?.let(::listOf), foreground = true, force = force) }
    }

    private fun add(place: Place, display: String) {
        viewModelScope.launch {
            message.value = when (repository.addPlace(place)) {
                RainRepository.AddResult.ADDED -> Message.Added(display)
                RainRepository.AddResult.DUPLICATE -> Message.Duplicate(display)
                RainRepository.AddResult.OUTSIDE_MOROCCO -> Message.Outside
            }
        }
    }

    fun addCity(city: City, display: String) =
        add(repository.newPlace(city.fr, city.point, city.elevation).copy(cityId = city.id, region = city.region), display)

    fun addSearchResult(r: GeocodingResult) = add(repository.newPlace(r.name, r.point, r.elevation), r.name)

    fun addPoint(name: String, point: GeoPoint) = add(repository.newPlace(name, point), name)

    fun addCurrentLocation(name: String) {
        viewModelScope.launch {
            val fix = repository.locator.current()
            if (fix == null) {
                message.value = Message.LocationFailed
                return@launch
            }
            val point = GeoPoint(fix.latitude, fix.longitude)
            if (!MoroccoBounds.contains(point)) {
                message.value = Message.Outside
                return@launch
            }
            add(repository.newPlace(name, point, followsDevice = true), name)
        }
    }

    fun nearestCity(point: GeoPoint) = MoroccoCities.nearest(point)

    suspend fun search(query: String, language: String): List<GeocodingResult> = repository.geocoder.search(query, language)

    fun remove(id: String) = viewModelScope.launch { repository.removePlace(id) }
    fun move(id: String, delta: Int) = viewModelScope.launch { repository.movePlace(id, delta) }
    fun rename(id: String, name: String) = viewModelScope.launch {
        repository.updatePlace(id) { it.copy(name = name.trim().ifEmpty { it.name }, renamed = true) }
    }
    fun setAlerts(id: String, on: Boolean) = viewModelScope.launch { repository.updatePlace(id) { it.copy(alertsEnabled = on) } }
    fun setWidgetPlace(id: String) = viewModelScope.launch { repository.updateAppSettings { it.copy(widgetPlaceId = id) } }

    fun updateAlerts(transform: (AlertSettings) -> AlertSettings) = viewModelScope.launch {
        repository.updateAlertSettings(transform)
        val s = repository.state.value
        WorkScheduler.scheduleDaily(app, s.alertSettings)
    }

    fun updateApp(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        val before = repository.state.value.appSettings
        repository.updateAppSettings(transform)
        val s = repository.state.value
        if (s.appSettings.language != before.language) {
            AppCompatDelegate.setApplicationLocales(
                languageTag(s.appSettings.language)?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList(),
            )
            Notifier.createChannels(app.localized(s.appSettings.language))
        }
        if (s.appSettings.checkIntervalMinutes != before.checkIntervalMinutes ||
            s.appSettings.wifiOnly != before.wifiOnly || s.appSettings.pauseOnLowBattery != before.pauseOnLowBattery
        ) {
            WorkScheduler.schedule(app, s.appSettings, s.alertSettings)
        }
    }

    fun loadOverview(force: Boolean = false) {
        if (overview.value is OverviewState.Loading) return
        val previous = overview.value
        overview.value = OverviewState.Loading
        viewModelScope.launch {
            overview.value = try {
                OverviewState.Ready(repository.overview(force))
            } catch (e: Exception) {
                (previous as? OverviewState.Ready) ?: OverviewState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun sendTest() {
        Notifier.postTest(app.localized(state.value.appSettings.language))
        message.value = Message.TestSent
    }
}
