package io.github.senk39.droid39.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.media3.common.MediaItem
import io.github.senk39.droid39.core.Settings

/**
 * Simple Scrobbler (Simple Last.fm Scrobbler, pakiet com.adam.aslfms) – „SLS API”:
 * broadcast com.adam.aslfms.notify.playstatechanged ze stanem START=0, RESUME=1, PAUSE=2, COMPLETE=3.
 *
 * SLS 1.7.0 śledzi też sesje multimediów (dostęp do powiadomień) – wtedy broadcast nie jest potrzebny; dlatego jest
 * opcjonalny (Ustawienia → Simple Scrobbler). Wysyłany jawnie do pakietu SLS (Android 8+ nie dostarcza
 * niejawnych broadcastów do odbiorników z manifestu).
 */
class ScrobbleBroadcaster(private val context: Context, private val settings: Settings) {
    private var current: MediaItem? = null
    private var started = false

    fun transition(item: MediaItem?, playing: Boolean, auto: Boolean) {
        val prev = current
        if (prev != null && started) send(prev, if (auto) COMPLETE else PAUSE)
        current = item
        started = false
        if (item != null && playing) {
            send(item, START)
            started = true
        }
    }

    fun playing(item: MediaItem?, isPlaying: Boolean) {
        if (item == null) return
        if (current?.mediaId != item.mediaId) {
            current = item
            started = false
        }
        if (isPlaying) {
            send(item, if (started) RESUME else START)
            started = true
        } else if (started) {
            send(item, PAUSE)
        }
    }

    fun complete(item: MediaItem?) {
        if (item != null && started) send(item, COMPLETE)
        started = false
    }

    private fun send(item: MediaItem, state: Int) {
        if (!settings.scrobbleBroadcast) return
        val md = item.mediaMetadata
        val artist = md.artist?.toString().orEmpty()
        val title = md.title?.toString().orEmpty()
        if (artist.isEmpty() || title.isEmpty()) return
        val intent = Intent(ACTION).setPackage(SLS_PACKAGE)
            .putExtra("app-name", "39droid")
            .putExtra("app-package", context.packageName)
            .putExtra("state", state)
            .putExtra("artist", artist)
            .putExtra("track", title)
            .putExtra("duration", ((md.durationMs ?: 0L) / 1000L).toInt())
            .putExtra("source", "P")
        md.albumTitle?.let { intent.putExtra("album", it.toString()) }
        if (settings.sendAlbumArtist) md.albumArtist?.let { intent.putExtra("albumartist", it.toString()) }
        md.trackNumber?.let { intent.putExtra("track-number", it) }
        context.sendBroadcast(intent)
    }

    companion object {
        const val ACTION = "com.adam.aslfms.notify.playstatechanged"
        const val SLS_PACKAGE = "com.adam.aslfms"
        const val START = 0
        const val RESUME = 1
        const val PAUSE = 2
        const val COMPLETE = 3

        fun installedVersion(context: Context): String? = try {
            context.packageManager.getPackageInfo(SLS_PACKAGE, 0).versionName ?: "?"
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
