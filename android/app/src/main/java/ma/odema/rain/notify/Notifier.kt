package ma.odema.rain.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ma.odema.core.AlertEvent
import ma.odema.core.AlertKind
import ma.odema.core.ForecastReport
import ma.odema.core.Place
import ma.odema.rain.Fmt
import ma.odema.rain.R
import ma.odema.rain.Weather
import ma.odema.rain.coreLang
import ma.odema.rain.ui.MainActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Local notifications. No server and no push: the phone alerts itself. */
object Notifier {
    const val CH_RAIN = "rain_soon"
    const val CH_STOP = "rain_stopping"
    const val CH_SEVERE = "severe"
    const val CH_SNOW = "snow"
    const val CH_AHEAD = "rain_ahead"
    const val CH_DAILY = "daily"
    private const val DAILY_ID = 7_000

    /** Creating again with a new name just renames the channel (used when the language changes). */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            Triple(CH_SEVERE, R.string.channel_severe, NotificationManager.IMPORTANCE_HIGH),
            Triple(CH_RAIN, R.string.channel_rain_soon, NotificationManager.IMPORTANCE_HIGH),
            Triple(CH_SNOW, R.string.channel_snow, NotificationManager.IMPORTANCE_HIGH),
            Triple(CH_STOP, R.string.channel_rain_stopping, NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CH_AHEAD, R.string.channel_ahead, NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CH_DAILY, R.string.channel_daily, NotificationManager.IMPORTANCE_LOW),
        ).forEach { (id, name, importance) ->
            manager.createNotificationChannel(NotificationChannel(id, context.getString(name), importance).apply {
                enableVibration(importance >= NotificationManager.IMPORTANCE_DEFAULT)
            })
        }
    }

    fun channelFor(kind: AlertKind): String = when (kind) {
        AlertKind.RAIN_SOON -> CH_RAIN
        AlertKind.RAIN_STOPPING -> CH_STOP
        AlertKind.HEAVY_RAIN, AlertKind.STORM -> CH_SEVERE
        AlertKind.SNOW -> CH_SNOW
        AlertKind.RAIN_AHEAD -> CH_AHEAD
    }

    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, channel: String, title: String, body: String, placeId: String?, color: Int = 0xFF1F6FEB.toInt()) {
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_rain)
            .setColor(color)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent(context, placeId, id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(if (channel == CH_SEVERE || channel == CH_RAIN) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openIntent(context: Context, placeId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { placeId?.let { putExtra(MainActivity.EXTRA_PLACE_ID, it) } }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** [context] must already be in the app's language (see `localized`). */
    fun postAlert(context: Context, event: AlertEvent, place: Place) {
        val (title, body) = AlertText.of(context, event, place.displayName(context.coreLang))
        // One notification per place and kind: a newer alert replaces the older one.
        val id = (place.id + event.kind.name).hashCode()
        val color = if (event.kind.severe) 0xFFD93025.toInt() else 0xFF1F6FEB.toInt()
        post(context, id, channelFor(event.kind), title, body, place.id, color)
    }

    /** One alert for several parcels: "⚠️ Heavy rain in Nord, Sud, Est". Uses the earliest event's details. */
    fun postAlertGroup(context: Context, group: List<Pair<AlertEvent, Place>>) {
        val sorted = group.sortedBy { it.first.start ?: Long.MAX_VALUE }
        val names = sorted.map { it.second.displayName(context.coreLang) }
        val label = if (names.size <= 4) names.joinToString(", ") else names.take(3).joinToString(", ") + " +${names.size - 3}"
        val first = sorted.first().first
        val (title, body) = AlertText.of(context, first, label)
        val kind = first.kind
        val color = if (kind.severe) 0xFFD93025.toInt() else 0xFF1F6FEB.toInt()
        post(context, ("odema-" + kind.name).hashCode(), channelFor(kind), title, body, null, color)
    }

    fun postDailySummary(context: Context, places: List<Place>, reports: Map<String, ForecastReport>) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val lines = places.mapNotNull { place ->
            val day = reports[place.id]?.daily?.firstOrNull {
                Instant.ofEpochSecond(it.date + 3600).atZone(zone).toLocalDate() == today
            } ?: return@mapNotNull null
            context.getString(
                R.string.notif_daily_line,
                place.displayName(context.coreLang),
                Weather.emoji(day.code),
                Fmt.mm(context, day.precipitationSum ?: 0.0),
                (day.probabilityMax ?: 0.0).toInt(),
                Fmt.temp(context, day.tempMin),
                Fmt.temp(context, day.tempMax),
            )
        }
        if (lines.isEmpty()) return
        post(context, DAILY_ID, CH_DAILY, context.getString(R.string.notif_daily_title), lines.joinToString("\n"), null)
    }

    fun postTest(context: Context) {
        post(context, 9_999, CH_RAIN, context.getString(R.string.notif_test_title), context.getString(R.string.notif_test_body), null)
    }
}

/** Turns an [AlertEvent] into a localized title and body. */
object AlertText {
    fun of(context: Context, e: AlertEvent, placeName: String, now: Long = System.currentTimeMillis() / 1000): Pair<String, String> {
        val start = e.start?.let { maxOf(it, now) }
        val at = start?.let { Fmt.time(it) } ?: "–"
        val agree = context.getString(R.string.notif_agree_part, e.agreeing, e.available)
        fun mm(v: Double?) = Fmt.mm(context, v ?: 0.0)
        return when (e.kind) {
            AlertKind.RAIN_SOON -> {
                val parts = mutableListOf<String>()
                if (!e.rainingNow && start != null) {
                    parts += context.getString(R.string.notif_rain_soon_body, at, Fmt.relative(context, start - now))
                }
                e.amount?.let { parts += context.getString(R.string.notif_intensity_part, Fmt.intensity(context, it), mm(it)) }
                parts += agree
                context.getString(if (e.rainingNow) R.string.notif_rain_now_title else R.string.notif_rain_soon_title, placeName) to
                    parts.joinToString(" · ")
            }
            AlertKind.RAIN_STOPPING ->
                context.getString(R.string.notif_stopping_title, placeName) to
                    context.getString(R.string.notif_stopping_body, at) + " · " + agree
            AlertKind.HEAVY_RAIN ->
                context.getString(R.string.notif_heavy_title, placeName) to
                    context.getString(R.string.notif_heavy_body, mm(e.amount), at, mm(e.amount2)) + " · " + agree
            AlertKind.STORM ->
                context.getString(R.string.notif_storm_title, placeName) to
                    context.getString(R.string.notif_storm_body, at) + " · " + agree
            AlertKind.SNOW ->
                context.getString(R.string.notif_snow_title, placeName) to
                    context.getString(R.string.notif_snow_body, mm(e.amount), at) + " · " + agree
            AlertKind.RAIN_AHEAD -> {
                val n = e.amount2?.toInt() ?: 3
                val days = context.resources.getQuantityString(R.plurals.days, n, n)
                context.getString(R.string.notif_ahead_title, placeName) to
                    context.getString(R.string.notif_ahead_body, mm(e.amount), days) + " · " + agree
            }
        }
    }
}
