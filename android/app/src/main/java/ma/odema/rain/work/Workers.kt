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

/** Keeps ODEMA's rain history current and backfills it to 1940 (stops at the API's daily limit). */
class FarmSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val farm = applicationContext.odema.farm
        farm.ensureLoaded()
        if (farm.state.value.data.parcels.isNotEmpty()) farm.sync()
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

/** Coworkers' new breakdowns, when the app is closed (the live listener only runs while it is open). */
class TeamCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext.odema
        app.ops.ensureLoaded()
        app.cloud.checkInBackground()
        Result.success()
    } catch (e: Exception) {
        Result.success()
    }
}

object WorkScheduler {
    private const val CHECK = "rain-check"
    private const val DAILY = "daily-summary"
    private const val FARM = "odema-history"
    private const val FARM_NOW = "odema-history-now"
    private const val TEAM = "odema-team"

    /** Twice a day: recent rain + more history until 1940 is reached. */
    fun scheduleFarm(context: Context) {
        val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val request = PeriodicWorkRequestBuilder<FarmSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(FARM, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Every 30 minutes while the team sync is configured. */
    fun scheduleTeam(context: Context, on: Boolean) {
        val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!on) { manager.cancelUniqueWork(TEAM); return }
        val request = PeriodicWorkRequestBuilder<TeamCheckWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(TEAM, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Right after parcels change (import, new location). */
    fun syncFarmNow(context: Context) {
        val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val request = OneTimeWorkRequestBuilder<FarmSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniqueWork(FARM_NOW, ExistingWorkPolicy.REPLACE, request)
    }

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
        scheduleFarm(context)
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
