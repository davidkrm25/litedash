package dev.davidkrm.gpsphone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/** Puerto donde escucha LiteDash en la tableta. */
const val PORT = 8765

/**
 * Lee el GPS del teléfono cada segundo y lo manda por UDP a la dirección de difusión de cada red activa (el hotspot
 * incluido): la tableta no necesita saber ninguna IP.
 */
class SenderService : Service() {
    private lateinit var manager: LocationManager
    private var socket: DatagramSocket? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = send(l)
        @Deprecated("Android 6–10") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotice()
        if (socket == null) socket = DatagramSocket().apply { broadcast = true }
        manager = getSystemService(LocationManager::class.java)
        try {
            manager.removeUpdates(listener)
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener)
            State.running = true
            State.message = "Buscando señal GPS…"
        } catch (e: SecurityException) {
            State.message = "Falta el permiso de ubicación"
            stopSelf()
        } catch (e: IllegalArgumentException) {
            State.message = "Este teléfono no tiene GPS"
            stopSelf()
        }
        return START_STICKY
    }

    private fun send(l: Location) {
        // Fuera del hilo principal: el envío por red no se puede hacer en él.
        val text = "LDGPS1;${l.latitude};${l.longitude};${if (l.hasSpeed()) l.speed else -1f};${if (l.hasBearing()) l.bearing else -1f};" +
            "${if (l.hasAccuracy()) l.accuracy else -1f};${l.altitude};${System.currentTimeMillis()}"
        val data = text.toByteArray()
        thread(name = "gps-send") {
            var sent = 0
            try {
                for (address in broadcasts()) {
                    try {
                        socket?.send(DatagramPacket(data, data.size, address, PORT))
                        sent++
                    } catch (_: Exception) {
                        // esa red no deja mandar: se sigue con las demás
                    }
                }
            } catch (_: Exception) {
            }
            State.message = if (sent > 0) "Mandando ubicación (${"%.0f".format(if (l.hasSpeed()) l.speed * 3.6f else 0f)} km/h)"
            else "Sin red a la que mandar: prende el hotspot"
        }
    }

    /** La dirección de difusión de cada red IPv4 activa (en el hotspot es la de la tableta conectada). */
    private fun broadcasts(): List<InetAddress> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.interfaceAddresses }
        .filter { it.address is Inet4Address }
        .mapNotNull { it.broadcast }

    private fun startForegroundNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel(CHANNEL, "GPS a la tableta", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder.setContentTitle("GPS a la tableta").setContentText("Mandando la ubicación de este teléfono")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, notification)
    }

    override fun onDestroy() {
        if (::manager.isInitialized) manager.removeUpdates(listener)
        socket?.close()
        socket = null
        State.running = false
        State.message = "Detenido"
        super.onDestroy()
    }

    companion object {
        const val STOP = "stop"
        private const val CHANNEL = "gps"
    }
}

/** Lo que la pantalla muestra del servicio. */
object State {
    @Volatile var running = false
    @Volatile var message = "Detenido"
}
