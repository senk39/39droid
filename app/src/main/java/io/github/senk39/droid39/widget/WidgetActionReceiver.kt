package io.github.senk39.droid39.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import io.github.senk39.droid39.App

/**
 * „Poprzedni” / „następny” z widżetu. MediaButtonReceiver z Media3 przyjmuje tylko „odtwórz” (inne przyciski przy
 * nieuruchomionej usłudze groziłyby błędem startu usługi w tle), więc te dwa idą przez kontroler odtwarzacza
 * aplikacji – jak przyciski na ekranie odtwarzacza.
 */
@UnstableApi
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val player = App.graph.player
        val pending = goAsync()                                   // połączenie z usługą trwa chwilę
        when (intent.action) {
            ACTION_NEXT -> player.skipWhenReady(forward = true)
            ACTION_PREVIOUS -> player.skipWhenReady(forward = false)
        }
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 5000)
    }

    companion object {
        const val ACTION_NEXT = "io.github.senk39.droid39.widget.NEXT"
        const val ACTION_PREVIOUS = "io.github.senk39.droid39.widget.PREVIOUS"
    }
}
