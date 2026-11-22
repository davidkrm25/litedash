// Encender y apagar la pantalla con el auto (Ajustes → Inicio y apariencia → Encender y apagar con el auto).
package com.irvingguerra.litedash

import android.app.Application
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Lo que tarda en dormir la pantalla desde que se corta la corriente (por si fue un bache o un contacto suelto). */
internal const val CAR_SLEEP_DELAY_MS = 30_000L

/** Hay que dormir la pantalla: ya no recibe corriente y LiteDash tiene permiso de administrador para hacerlo. */
internal fun sleepDue(charging: Boolean, adminActive: Boolean) = !charging && adminActive

/** El permiso de "administrador del dispositivo" solo para bloquear la pantalla (no borra ni cambia nada). */
class CarPowerAdmin : DeviceAdminReceiver()

/**
 * La tableta se alimenta de una entrada que se corta con la llave: al llegar corriente se enciende la pantalla y se
 * abre LiteDash; al irse, tras [CAR_SLEEP_DELAY_MS] sin corriente, la pantalla se apaga (queda en reposo, no apagada
 * del todo: una app no puede apagar la tableta). Corre mientras LiteDash vive (es el inicio); apagado por defecto.
 */
object CarPower {
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE).getBoolean(PrefKeys.FOLLOW_CAR_POWER, false)

    private fun admin(context: Context) = ComponentName(context, CarPowerAdmin::class.java)

    fun adminActive(context: Context): Boolean =
        runCatching { context.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin(context)) }.getOrDefault(false)

    /** La pantalla de Android que pide el permiso de administrador. */
    fun adminIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(context))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.car_power_admin_desc))

    private fun charging(context: Context): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    /** Se llama una vez al armar la app. */
    fun start(app: Application) {
        if (started) return
        started = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!enabled(app)) return
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        main.removeCallbacks(sleep)
                        wake(app)
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        main.removeCallbacks(sleep)
                        main.postDelayed(sleep, CAR_SLEEP_DELAY_MS)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        appRef = app
    }

    private var appRef: Application? = null

    private val sleep = Runnable {
        val app = appRef ?: return@Runnable
        if (enabled(app) && sleepDue(charging(app), adminActive(app))) {
            runCatching { app.getSystemService(DevicePolicyManager::class.java).lockNow() }
        }
    }

    @Suppress("DEPRECATION")
    private fun wake(app: Application) {
        runCatching {
            val pm = app.getSystemService(PowerManager::class.java)
            pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "litedash:car-power")
                .acquire(5_000L)
        }
        if (BootLaunch.allowed(app)) BootLaunch.open(app)
    }
}
