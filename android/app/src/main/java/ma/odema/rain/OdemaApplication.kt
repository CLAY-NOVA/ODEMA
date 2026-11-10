package ma.odema.rain

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ma.odema.core.Lang
import ma.odema.rain.data.RainRepository
import ma.odema.rain.notify.Notifier
import ma.odema.rain.work.WorkScheduler
import java.io.File
import java.util.Locale

class OdemaApplication : Application() {
    val repository: RainRepository by lazy { RainRepository(this) }
    val farm: ma.odema.rain.data.FarmRepository by lazy { ma.odema.rain.data.FarmRepository(this) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // osmdroid (map picker): identify ourselves to OpenStreetMap and keep tiles in our cache.
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = "OdemaMaroc/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        scope.launch {
            repository.ensureLoaded()
            val s = repository.state.value
            Notifier.createChannels(localized(s.appSettings.language))
            WorkScheduler.schedule(this@OdemaApplication, s.appSettings, s.alertSettings)
        }
    }
}

val Context.odema: OdemaApplication get() = applicationContext as OdemaApplication

/** Locale tag for the in-app language setting ("" = follow the phone). Moroccan variants use Western digits. */
fun languageTag(setting: String): String? = when (setting) {
    "ar" -> "ar-MA"
    "fr" -> "fr-MA"
    "en" -> "en-GB"
    else -> null
}

/** A context whose resources use the app's language, for text built outside an Activity (alerts, widget). */
fun Context.localized(language: String): Context {
    val tag = languageTag(language) ?: return this
    val locale = Locale.forLanguageTag(tag)
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    return createConfigurationContext(config)
}

val Context.locale: Locale get() = resources.configuration.locales[0]

val Context.coreLang: Lang
    get() = when (locale.language) {
        "ar" -> Lang.AR
        "fr" -> Lang.FR
        else -> Lang.EN
    }
