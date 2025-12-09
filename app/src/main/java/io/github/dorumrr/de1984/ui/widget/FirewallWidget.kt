package io.github.dorumrr.de1984.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants

/**
 * Home screen widget for De1984 Firewall.
 * 
 * Provides quick firewall status visibility and toggle functionality from the home screen.
 */
class FirewallWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "FirewallWidget"

        /**
         * Update all widgets to show loading/starting state.
         * Called from FirewallToggleReceiver before starting the firewall.
         */
        fun setLoadingState(context: Context) {
            AppLogger.d(TAG, "━━━━━ setLoadingState() called ━━━━━")

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, FirewallWidget::class.java)
            )

            AppLogger.d(TAG, "Setting ${appWidgetIds.size} widgets to loading state")

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_firewall)

                // Set loading state UI
                views.setTextViewText(R.id.widget_status_text, context.getString(R.string.tile_label_firewall_loading))
                views.setInt(R.id.widget_container, "setBackgroundResource", R.drawable.widget_background_loading)

                // Disable click during loading (set empty pending intent)
                val emptyIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId,
                    Intent(),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_container, emptyIntent)

                appWidgetManager.updateAppWidget(appWidgetId, views)
                AppLogger.d(TAG, "✅ Widget $appWidgetId set to LOADING state (amber gradient)")
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        AppLogger.d(TAG, "━━━━━ onUpdate() called ━━━━━")
        AppLogger.d(TAG, "Widget IDs to update: ${appWidgetIds.toList()}")
        
        // Update all widgets
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, null)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        AppLogger.d(TAG, "━━━━━ onReceive() called ━━━━━")
        AppLogger.d(TAG, "Received action: ${intent?.action}")
        
        super.onReceive(context, intent)
        
        // Listen for state change broadcasts to update widget
        if (intent?.action == Constants.Firewall.ACTION_FIREWALL_STATE_CHANGED) {
            AppLogger.d(TAG, "🔔 STATE CHANGE BROADCAST RECEIVED")
            
            // Get the state from the broadcast extras
            val stateString = intent.getStringExtra(Constants.Firewall.EXTRA_FIREWALL_STATE)
            AppLogger.d(TAG, "Broadcast state string: '$stateString'")
            
            // Derive boolean state from broadcast
            val isEnabledFromBroadcast = stateString?.contains("Running") == true || 
                                          stateString?.contains("Starting") == true
            AppLogger.d(TAG, "Derived isEnabled from broadcast: $isEnabledFromBroadcast")
            
            // Update SharedPreferences to match broadcast (source of truth)
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val oldValue = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)
            prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, isEnabledFromBroadcast).apply()
            AppLogger.d(TAG, "SharedPrefs updated: $oldValue -> $isEnabledFromBroadcast")
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, FirewallWidget::class.java)
            )
            
            AppLogger.d(TAG, "Found ${appWidgetIds.size} widgets to update: ${appWidgetIds.toList()}")
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, isEnabledFromBroadcast)
            }
        }
    }

    override fun onEnabled(context: Context) {
        AppLogger.d(TAG, "Widget enabled")
    }

    override fun onDisabled(context: Context) {
        AppLogger.d(TAG, "Widget disabled")
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        forcedIsEnabled: Boolean?
    ) {
        AppLogger.d(TAG, "━━━━━ updateAppWidget() ━━━━━")
        AppLogger.d(TAG, "widgetId=$appWidgetId, forcedIsEnabled=$forcedIsEnabled")
        
        // Get current firewall state - use forced value if provided, otherwise read from prefs
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val prefsValue = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)
        val isEnabled = forcedIsEnabled ?: prefsValue
        
        AppLogger.d(TAG, "State resolution: prefs=$prefsValue, forced=$forcedIsEnabled, final=$isEnabled")
        
        val views = RemoteViews(context.packageName, R.layout.widget_firewall)
        
        // Update UI based on state
        if (isEnabled) {
            views.setTextViewText(R.id.widget_status_text, context.getString(R.string.tile_label_firewall_on))
            views.setInt(R.id.widget_container, "setBackgroundResource", R.drawable.widget_background_on)
            AppLogger.d(TAG, "UI set to ON state (purple gradient)")
        } else {
            views.setTextViewText(R.id.widget_status_text, context.getString(R.string.tile_label_firewall_off))
            views.setInt(R.id.widget_container, "setBackgroundResource", R.drawable.widget_background_off)
            AppLogger.d(TAG, "UI set to OFF state (gray gradient)")
        }
        
        // Set up click intent based on current state
        val clickIntent: Intent
        val pendingIntent: PendingIntent
        
        if (isEnabled) {
            // Firewall is ON - open app to show stop confirmation dialog
            clickIntent = Intent(context, MainActivity::class.java).apply {
                action = Constants.Firewall.ACTION_TOGGLE_FIREWALL
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            AppLogger.d(TAG, "Click intent: ACTIVITY to MainActivity with ACTION_TOGGLE_FIREWALL")
        } else {
            // Firewall is OFF - send broadcast to FirewallToggleReceiver
            clickIntent = Intent(context, io.github.dorumrr.de1984.data.receiver.FirewallToggleReceiver::class.java).apply {
                action = Constants.Firewall.ACTION_TOGGLE_FIREWALL
            }
            pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            AppLogger.d(TAG, "Click intent: BROADCAST to FirewallToggleReceiver with ACTION_TOGGLE_FIREWALL")
        }
        
        views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)
        AppLogger.d(TAG, "PendingIntent attached to widget_container")
        
        // Update the widget
        appWidgetManager.updateAppWidget(appWidgetId, views)
        AppLogger.d(TAG, "✅ Widget $appWidgetId UPDATE COMPLETE: state=${if (isEnabled) "ON" else "OFF"}")
    }
}
