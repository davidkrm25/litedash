package dev.davidkrm.gpsphone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Una sola pantalla: un botón para empezar o parar y lo que está pasando. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var button: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            status.text = State.message
            button.text = if (State.running) "Detener" else "Empezar a mandar el GPS"
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        button = Button(this).apply { textSize = 18f; setOnClickListener { toggle() } }
        val help = TextView(this).apply {
            text = "1. Prende el hotspot de este teléfono.\n2. Conecta la tableta a ese hotspot.\n3. Toca el botón. LiteDash en la tableta recibe la ubicación solo.\n4. Activa «Mostrar sobre otras apps» para esta app: así las gasolineras de la tableta abren su ruta aquí y Android Auto la muestra."
            textSize = 14f
            setTextColor(Color.LTGRAY)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#101114"))
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad })
            addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad })
            addView(help)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun toggle() {
        if (State.running) {
            startService(Intent(this, SenderService::class.java).setAction(SenderService.STOP))
            return
        }
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(needed.toTypedArray(), 1)
            return
        }
        begin()
    }

    private fun begin() {
        // Que el sistema no la mate con la pantalla apagada (los teléfonos con ahorro agresivo lo hacen).
        val pm = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
            }
        }
        // Para abrir en segundo plano la ruta que manda la tableta, Android pide «mostrar sobre otras apps».
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
            }
        }
        val intent = Intent(this, SenderService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) begin()
        else State.message = "Sin el permiso de ubicación no puedo mandar el GPS"
    }
}
