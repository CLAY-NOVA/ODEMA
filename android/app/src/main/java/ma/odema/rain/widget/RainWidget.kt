package ma.odema.rain.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.launch
import ma.odema.rain.Fmt
import ma.odema.rain.R
import ma.odema.rain.StatusText
import ma.odema.rain.coreLang
import ma.odema.rain.localized
import ma.odema.rain.odema
import ma.odema.rain.ui.MainActivity

/** Home-screen widget: next rain for your main place. Updated after every check. */
class RainWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        context.odema.scope.launch {
            try {
                context.odema.repository.ensureLoaded()
                RainWidget.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}

object RainWidget {
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, RainWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val state = context.odema.repository.state.value
        val ctx = context.localized(state.appSettings.language)
        val views = RemoteViews(context.packageName, R.layout.widget_rain)
        val place = state.widgetPlace
        if (place == null) {
            views.setTextViewText(R.id.widget_place, ctx.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_status, ctx.getString(R.string.widget_empty))
            views.setTextViewText(R.id.widget_updated, "")
            views.setTextViewText(R.id.widget_temp, "")
        } else {
            val report = state.reports[place.id]
            val status = StatusText.of(ctx, report, state.alertSettings, place.id in state.refreshing)
            views.setTextViewText(R.id.widget_icon, status.emoji)
            views.setTextViewText(R.id.widget_place, place.displayName(ctx.coreLang))
            views.setTextViewText(R.id.widget_status, status.text + (status.badge?.let { " · $it" } ?: ""))
            views.setTextViewText(R.id.widget_updated, report?.let { ctx.getString(R.string.widget_updated, Fmt.time(it.fetchedAt)) } ?: "")
            views.setTextViewText(R.id.widget_temp, report?.current?.temperature?.let { Fmt.temp(ctx, it) } ?: "")
        }
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).apply { place?.let { putExtra(MainActivity.EXTRA_PLACE_ID, it.id) } },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        manager.updateAppWidget(ids, views)
    }
}
