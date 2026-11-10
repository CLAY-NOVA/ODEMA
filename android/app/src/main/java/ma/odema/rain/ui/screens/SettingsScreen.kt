package ma.odema.rain.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.AlertSettings
import ma.odema.core.SourceId
import ma.odema.core.SourceKind
import ma.odema.rain.BuildConfig
import ma.odema.rain.Fmt
import ma.odema.rain.R
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.BackTopBar
import ma.odema.rain.ui.components.ChoiceRow
import ma.odema.rain.ui.components.ClickRow
import ma.odema.rain.ui.components.SectionHeader
import ma.odema.rain.ui.components.SwitchRow
import ma.odema.rain.ui.components.TimeRow

/** Accuracy presets: how many sources must agree, and how likely rain must be. */
enum class Preset(val agreement: Double, val severe: Double, val probability: Double) {
    SENSITIVE(0.25, 0.2, 40.0),
    BALANCED(0.5, 0.35, 60.0),
    STRICT(0.75, 0.6, 75.0);

    fun matches(s: AlertSettings) = s.requiredAgreement == agreement && s.severeAgreement == severe && s.minProbability == probability

    companion object {
        fun of(s: AlertSettings): Preset? = entries.firstOrNull { it.matches(s) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onSources: () -> Unit, onAbout: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val a = state.alertSettings
    val app = state.appSettings
    fun alerts(t: (AlertSettings) -> AlertSettings) = vm.updateAlerts(t)
    fun minutes(vararg m: Int) = m.map { it to Fmt.duration(context, it * 60L) }
    fun mmh(vararg v: Double) = v.map { it to Fmt.mmhText(context, it) }
    fun mm(vararg v: Double) = v.map { it to Fmt.mmText(context, it) }
    fun pct(vararg v: Double) = v.map { it to "${(it * 100).toInt()}%" }

    val pickKml = ma.odema.rain.ui.odema.rememberKmlPicker(vm)
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(vm.farm.exportBackup().toByteArray()) } }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()?.let(vm::importBackup)
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // Mode
            SectionHeader(stringResource(R.string.od_mode_section))
            ChoiceRow(
                stringResource(R.string.od_mode),
                listOf(false to stringResource(R.string.od_mode_general), true to stringResource(R.string.od_mode_odema)),
                app.odema,
                subtitle = stringResource(R.string.od_mode_desc),
            ) { vm.setMode(it) }
            if (app.odema) {
                ClickRow(stringResource(R.string.od_import_kml), subtitle = stringResource(R.string.od_import_kml_desc), onClick = pickKml)
                ClickRow(stringResource(R.string.od_export_backup), subtitle = stringResource(R.string.od_export_backup_desc)) {
                    exportBackup.launch("ODEMA-sauvegarde-${java.time.LocalDate.now()}.json")
                }
                ClickRow(stringResource(R.string.od_import_backup), subtitle = stringResource(R.string.od_import_backup_desc)) {
                    importBackup.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
                ma.odema.rain.ui.odema.HistoryStatus(vm)
            }

            // Alerts
            SectionHeader(stringResource(R.string.section_alerts))
            SwitchRow(stringResource(R.string.set_rain_soon), a.rainSoonEnabled, { v -> alerts { it.copy(rainSoonEnabled = v) } }, stringResource(R.string.set_rain_soon_desc))
            ChoiceRow(stringResource(R.string.set_lookahead), minutes(30, 60, 120, 180, 360, 720), a.lookaheadMinutes, enabled = a.rainSoonEnabled) { v -> alerts { it.copy(lookaheadMinutes = v) } }
            SwitchRow(stringResource(R.string.set_rain_stopping), a.rainStoppingEnabled, { v -> alerts { it.copy(rainStoppingEnabled = v) } }, stringResource(R.string.set_rain_stopping_desc))
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SwitchRow(stringResource(R.string.set_heavy), a.heavyEnabled, { v -> alerts { it.copy(heavyEnabled = v) } }, stringResource(R.string.set_heavy_desc))
            ChoiceRow(stringResource(R.string.set_heavy_hourly), mmh(5.0, 8.0, 10.0, 15.0, 20.0, 30.0), a.heavyHourly, enabled = a.heavyEnabled) { v -> alerts { it.copy(heavyHourly = v) } }
            ChoiceRow(stringResource(R.string.set_heavy_daily), mm(20.0, 30.0, 40.0, 50.0, 70.0, 100.0), a.heavyDaily, enabled = a.heavyEnabled) { v -> alerts { it.copy(heavyDaily = v) } }
            SwitchRow(stringResource(R.string.set_storm), a.stormEnabled, { v -> alerts { it.copy(stormEnabled = v) } }, stringResource(R.string.set_storm_desc))
            SwitchRow(stringResource(R.string.set_snow), a.snowEnabled, { v -> alerts { it.copy(snowEnabled = v) } }, stringResource(R.string.set_snow_desc))
            ChoiceRow(stringResource(R.string.set_snow_threshold), listOf(0.5, 1.0, 2.0, 5.0, 10.0).map { it to Fmt.cmText(context, it) }, a.snowThresholdCm, enabled = a.snowEnabled) { v -> alerts { it.copy(snowThresholdCm = v) } }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SwitchRow(stringResource(R.string.set_ahead), a.aheadEnabled, { v -> alerts { it.copy(aheadEnabled = v) } }, stringResource(R.string.set_ahead_desc))
            ChoiceRow(stringResource(R.string.set_ahead_days), listOf(1, 2, 3, 5, 7).map { it to context.resources.getQuantityString(R.plurals.days, it, it) }, a.aheadDays, enabled = a.aheadEnabled) { v -> alerts { it.copy(aheadDays = v) } }
            ChoiceRow(stringResource(R.string.set_ahead_amount), mm(5.0, 10.0, 20.0, 30.0, 50.0), a.aheadMm, enabled = a.aheadEnabled) { v -> alerts { it.copy(aheadMm = v) } }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SwitchRow(stringResource(R.string.set_daily), a.dailySummaryEnabled, { v -> alerts { it.copy(dailySummaryEnabled = v) } }, stringResource(R.string.set_daily_desc))
            TimeRow(stringResource(R.string.set_daily_time), a.dailySummaryMinute, enabled = a.dailySummaryEnabled) { v -> alerts { it.copy(dailySummaryMinute = v) } }

            // Accuracy
            SectionHeader(stringResource(R.string.section_accuracy))
            ChoiceRow(
                stringResource(R.string.set_preset),
                listOf<Pair<Preset?, String>>(
                    Preset.SENSITIVE to stringResource(R.string.preset_sensitive),
                    Preset.BALANCED to stringResource(R.string.preset_balanced),
                    Preset.STRICT to stringResource(R.string.preset_strict),
                    null to stringResource(R.string.preset_custom),
                ),
                Preset.of(a),
            ) { p -> p?.let { preset -> alerts { it.copy(requiredAgreement = preset.agreement, severeAgreement = preset.severe, minProbability = preset.probability) } } }
            ChoiceRow(stringResource(R.string.set_agreement), pct(0.01, 0.25, 0.33, 0.5, 0.66, 0.75, 1.0).map { (v, t) -> v to if (v < 0.02) stringResource(R.string.agreement_any) else t }, a.requiredAgreement, subtitle = stringResource(R.string.set_agreement_desc)) { v -> alerts { it.copy(requiredAgreement = v) } }
            ChoiceRow(stringResource(R.string.set_severe_agreement), pct(0.2, 0.25, 0.35, 0.5, 0.6, 0.75), a.severeAgreement) { v -> alerts { it.copy(severeAgreement = v) } }
            ChoiceRow(stringResource(R.string.set_min_intensity), mmh(0.1, 0.2, 0.5, 1.0, 2.5), a.minIntensity) { v -> alerts { it.copy(minIntensity = v) } }
            ChoiceRow(stringResource(R.string.set_min_probability), listOf(30.0, 40.0, 50.0, 60.0, 70.0, 75.0, 80.0, 90.0).map { it to "${it.toInt()}%" }, a.minProbability) { v -> alerts { it.copy(minProbability = v) } }
            ClickRow(
                stringResource(R.string.set_sources),
                subtitle = stringResource(R.string.set_sources_desc, a.enabledSources.size, SourceId.entries.size),
                onClick = onSources,
            )

            // Notifications
            SectionHeader(stringResource(R.string.section_notifications))
            ChoiceRow(stringResource(R.string.set_cooldown), minutes(60, 120, 180, 360, 720), a.cooldownMinutes) { v -> alerts { it.copy(cooldownMinutes = v) } }
            SwitchRow(stringResource(R.string.set_quiet), a.quietEnabled, { v -> alerts { it.copy(quietEnabled = v) } }, stringResource(R.string.set_quiet_desc))
            TimeRow(stringResource(R.string.set_quiet_from), a.quietStart, enabled = a.quietEnabled) { v -> alerts { it.copy(quietStart = v) } }
            TimeRow(stringResource(R.string.set_quiet_until), a.quietEnd, enabled = a.quietEnabled) { v -> alerts { it.copy(quietEnd = v) } }
            SwitchRow(stringResource(R.string.set_severe_quiet), a.severeIgnoresQuiet, { v -> alerts { it.copy(severeIgnoresQuiet = v) } }, stringResource(R.string.set_severe_quiet_desc), enabled = a.quietEnabled)
            ClickRow(stringResource(R.string.set_system_notifications), subtitle = stringResource(R.string.set_system_notifications_desc)) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            ClickRow(stringResource(R.string.set_test)) { vm.sendTest() }

            // Background
            SectionHeader(stringResource(R.string.section_background))
            ChoiceRow(stringResource(R.string.set_interval), minutes(15, 30, 60, 120, 180), app.checkIntervalMinutes, subtitle = stringResource(R.string.set_interval_desc)) { v -> vm.updateApp { it.copy(checkIntervalMinutes = v) } }
            SwitchRow(stringResource(R.string.set_wifi_only), app.wifiOnly, { v -> vm.updateApp { it.copy(wifiOnly = v) } })
            SwitchRow(stringResource(R.string.set_battery_low), app.pauseOnLowBattery, { v -> vm.updateApp { it.copy(pauseOnLowBattery = v) } })
            val exempt = isIgnoringBatteryOptimizations(context)
            ClickRow(
                stringResource(if (exempt) R.string.set_battery_opt_done else R.string.set_battery_opt),
                subtitle = stringResource(R.string.set_battery_opt_desc),
                enabled = !exempt,
            ) { requestBatteryExemption(context) }
            ClickRow(
                stringResource(R.string.set_check_now),
                subtitle = state.lastRefresh?.let { stringResource(R.string.set_last_check, Fmt.ago(context, it)) } ?: stringResource(R.string.places_never_checked),
            ) { vm.refresh() }

            // Appearance
            SectionHeader(stringResource(R.string.section_appearance))
            ChoiceRow(
                stringResource(R.string.set_language),
                listOf("" to stringResource(R.string.lang_system), "ar" to "العربية", "fr" to "Français", "en" to "English"),
                app.language,
            ) { v -> vm.updateApp { it.copy(language = v) } }
            ChoiceRow(
                stringResource(R.string.set_theme),
                listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light), "dark" to stringResource(R.string.theme_dark)),
                app.theme,
            ) { v -> vm.updateApp { it.copy(theme = v) } }

            // About
            SectionHeader(stringResource(R.string.section_about))
            ClickRow(stringResource(R.string.about_title), subtitle = stringResource(R.string.about_version, BuildConfig.VERSION_NAME), onClick = onAbout)
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(direct) }.onFailure {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun SourcesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val a = state.alertSettings
    Scaffold(topBar = { BackTopBar(stringResource(R.string.sources_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            listOf(
                SourceKind.BLEND to R.string.kind_blend,
                SourceKind.ENSEMBLE to R.string.kind_ensemble_long,
                SourceKind.MODEL to R.string.kind_model_long,
                SourceKind.STATION to R.string.kind_station_long,
            ).forEach { (kind, title) ->
                SectionHeader(stringResource(title))
                SourceId.entries.filter { it.kind == kind }.forEach { source ->
                    SwitchRow(
                        source.displayName,
                        source in a.enabledSources,
                        { on -> vm.updateAlerts { it.withSource(source, on) } },
                        subtitle = "${source.provider} · ×${Fmt.number(LocalContext.current, source.weight)}",
                        enabled = !(source in a.enabledSources && a.enabledSources.size == 1),
                    )
                }
            }
            Text(
                stringResource(R.string.sources_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    Scaffold(topBar = { BackTopBar(stringResource(R.string.about_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.about_how), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
            SectionHeader(stringResource(R.string.about_data))
            Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { open("https://open-meteo.com/") }) { Text("Open-Meteo") }
            TextButton(onClick = { open("https://api.met.no/") }) { Text("MET Norway") }
            TextButton(onClick = { open("https://brightsky.dev/") }) { Text("Bright Sky / DWD") }
            TextButton(onClick = { open("https://www.openstreetmap.org/copyright") }) { Text("OpenStreetMap") }
            SectionHeader(stringResource(R.string.about_official))
            Text(stringResource(R.string.about_disclaimer), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { open("https://www.marocmeteo.ma") }) { Text("marocmeteo.ma (DGM)") }
            SectionHeader(stringResource(R.string.about_reliability))
            Text(stringResource(R.string.about_reliability_text), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { open("https://dontkillmyapp.com/") }) { Text("dontkillmyapp.com") }
        }
    }
}
