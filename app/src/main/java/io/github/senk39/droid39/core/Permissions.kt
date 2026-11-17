package io.github.senk39.droid39.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Permissions {
    /** Android 17 (API 37) – uprawnienie do sieci lokalnej (NAS w LAN); na starszych systemach zawsze przyznane. */
    const val LOCAL_NETWORK = Manifest.permission.ACCESS_LOCAL_NETWORK

    fun localNetworkRequired(): Boolean = Build.VERSION.SDK_INT >= 37

    fun localNetworkGranted(context: Context): Boolean =
        !localNetworkRequired() || context.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    const val LOCAL_NETWORK_HINT =
        "Android 17 wymaga zgody na dostęp do sieci lokalnej: Ustawienia → Aplikacje → 39droid → Uprawnienia → Urządzenia w pobliżu."
}
