package ma.odema.rain.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ma.odema.core.AlertSettings
import ma.odema.rain.data.AppSettings
import ma.odema.rain.odema
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** The background check: fetch every place, alert if needed, update the widget. */
class RainCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.odema.repository.refresh(foreground = false)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

/** Morning summary at the time the user chose; re-schedules itself for the next day. */
class DailySummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = applicationContext.odema.repository
        runCatching { repository.refresh(foreground = false) }
        repository.postDailySummary()
        WorkScheduler.scheduleDaily(applicationContext, repository.state.value.alertSettings)
        return Result.success()
    }
}

object WorkScheduler {
    private const val CHECK = "rain-check"
    private const val DAILY = "daily-summary"

    fun schedule(context: Context, app: AppSettings, alerts: AlertSettings) {
        val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (app.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(app.pauseOnLowBattery)
            .build()
        // Android runs periodic work at most every 15 minutes.
        val minutes = app.checkIntervalMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<RainCheckWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(CHECK, ExistingPeriodicWorkPolicy.UPDATE, request)
        scheduleDaily(context, alerts)
    }

    fun scheduleDaily(context: Context, alerts: AlertSettings) {
        val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!alerts.dailySummaryEnabled) {
            manager.cancelUniqueWork(DAILY)
            return
        }
        val request = OneTimeWorkRequestBuilder<DailySummaryWorker>()
            .setInitialDelay(delayUntil(alerts.dailySummaryMinute).toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniqueWork(DAILY, ExistingWorkPolicy.REPLACE, request)
    }

    /** Time until the next occurrence of [minuteOfDay] in local time. */
    fun delayUntil(minuteOfDay: Int, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): Duration {
        val target = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        var next = LocalDateTime.of(LocalDate.from(now), target)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now.atZone(zone), next.atZone(zone))
    }
}
