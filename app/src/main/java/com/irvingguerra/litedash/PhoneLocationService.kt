package com.irvingguerra.litedash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * Recibe el GPS de la app del teléfono (por el hotspot) aunque KalEl Auto no esté al frente y se lo da a TODA la tableta
 * como ubicación simulada (Maps, Waze, Open Headunit). Necesita que KalEl Auto sea la «app de ubicación simulada».
 */
class PhoneLocationService : Service() {
    @Volatile private var socket: DatagramSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (socket == null) listen()
        return START_STICKY
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.phone_gps_channel), NotificationManager.IMPORTANCE_MIN))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(getString(R.string.phone_gps_notification)).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(ID, notification)
    }

    private fun listen() {
        val feed = PhoneLocationFeed(getSystemService(LocationManager::class.java))
        thread(name = "phone-location") {
            val s = try {
                DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PHONE_GPS_PORT)) }
            } catch (e: Exception) {
                state = "puerto: ${e.message}"
                return@thread
            }
            socket = s
            running = true
            val buffer = ByteArray(256)
            while (!s.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    s.receive(packet)
                    parsePhoneFix(String(packet.data, 0, packet.length))?.let {
                        lastReceivedAt = android.os.SystemClock.elapsedRealtime()
                        feed.push(it)
                        lastPushAt = feed.lastPushAt
                        state = feed.state
                    }
                } catch (e: Exception) {
                    if (s.isClosed) break
                }
            }
            running = false
            feed.stop()
        }
    }

    override fun onDestroy() {
        socket?.close()
        socket = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "phone_location"
        private const val ID = 4521

        /** Reloj de arranque del último punto que se le dio a la tableta, y cómo va (null = sin intentar; "" = bien; otro = por qué no). */
        @Volatile var lastPushAt = 0L
        @Volatile var state: String? = null

        /** El servicio ya escucha el puerto, y cuándo llegó el último punto del teléfono (reloj de arranque; 0 = ninguno). */
        @Volatile var running = false
        @Volatile var lastReceivedAt = 0L

        fun start(context: Context) {
            val intent = Intent(context, PhoneLocationService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (_: Exception) {
                // Sin permiso para arrancar en segundo plano: se vuelve a intentar al abrir la app.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhoneLocationService::class.java))
        }
    }
}
