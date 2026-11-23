package com.irvingguerra.litedash

import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.feature.music.RadioTile
import com.irvingguerra.litedash.feature.dashboard.TripStatKinds
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import com.irvingguerra.litedash.core.vehicle.watch
import androidx.compose.foundation.layout.height
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.irvingguerra.litedash.core.ui.CardLook
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.LiteDashTheme
import com.irvingguerra.litedash.core.ui.LocalCardLook
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.feature.music.MusicTile

/**
 * El panel fijo que se queda al lado de Mapas, Waze o la música cuando se abren en media ventana (Android 7–12, donde la
 * pantalla dividida está bloqueada): una ventana del sistema encima de las demás apps, del lado contrario a la app. Como
 * no es una ventana de actividad, tocar la app de al lado no la esconde. Se quita al volver a LiteDash o con su botón.
 */
object SidePanel {
    private var view: ComposeView? = null

    /** ¿Ya se puede dibujar sobre otras apps? (Antes de Android 6 siempre.) */
    fun allowed(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** Abre la pantalla donde se da el permiso de mostrar sobre otras apps. */
    fun askPermission(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Muestra el panel con [widthPx] de ancho, del lado izquierdo si [left]. */
    fun show(context: Context, widthPx: Int, left: Boolean): Boolean {
        val app = context.applicationContext
        if (!allowed(app)) return false
        hide(app)
        val d = (app as LiteDashApp).dependencies
        val owner = PanelOwner()
        val compose = ComposeView(app).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val look = remember(d.prefs.cardStyle, d.prefs.cardOpacity) { CardLook(d.prefs.cardStyle, d.prefs.cardOpacity) }
                // El mismo tema que la app: claro, oscuro, por sol o el del sistema.
                val night = rememberIsNight(d.prefs, needed = d.prefs.theme == ThemeMode.SUN)
                LiteDashTheme(dark = isDark(d, night), scale = d.prefs.scale, accent = d.prefs.accentColor, cards = d.prefs.cardColor) {
                    CompositionLocalProvider(LocalCardLook provides look, LocalActivityResultRegistryOwner provides NoResults) { PanelContent(d, onBack = { backToHome(app) }) }
                }
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            widthPx, WindowManager.LayoutParams.MATCH_PARENT, type,
            // Sin foco: el teclado y el botón de atrás siguen siendo de la app de al lado; los toques en el panel sí llegan.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = (if (left) Gravity.START else Gravity.END) or Gravity.TOP }
        return try {
            app.getSystemService(WindowManager::class.java).addView(compose, params)
            owner.start()
            view = compose
            true
        } catch (e: Throwable) {
            // Que se vea por qué no salió el panel (en la prueba en la tableta).
            android.util.Log.w("SidePanel", "no salió el panel", e)
            android.widget.Toast.makeText(app, "Panel: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}", android.widget.Toast.LENGTH_LONG).show()
            false
        }
    }

    fun hide(context: Context) {
        val v = view ?: return
        view = null
        runCatching { context.applicationContext.getSystemService(WindowManager::class.java).removeView(v) }
    }

    /**
     * La X: quita el panel y regresa a LiteDash a pantalla completa. Las ventanas libres de Mapas o Spotify se quedan atrás
     * (la música no se corta); LiteDash pide el modo de pantalla completa (1) para no heredar el tamaño de la ventana de al lado.
     */
    private fun backToHome(context: Context) {
        hide(context)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val options = android.app.ActivityOptions.makeBasic()
        runCatching { android.app.ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(options, 1) }
        context.startActivity(intent, options.toBundle())
    }
}

/**
 * Los medidores piden permisos con rememberLauncherForActivityResult, que falla (y cierra la app) fuera de una actividad.
 * El panel no pide nada: este registro no hace nada al lanzar.
 */
private val NoResults = object : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) = Unit
    }
}

/** El panel no es una actividad: lleva su propio ciclo de vida para que Compose funcione. */
private class PanelOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performAttach()
        saved.performRestore(null)
    }

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }
}

/** Velocímetro, RPM, tiempo de manejo, clima, autonomía y música (o radio, a elegir), con un botón para volver al tablero. */
@androidx.compose.runtime.Composable
private fun PanelContent(d: Dependencies, onBack: () -> Unit) {
    val c = LocalColors.current
    // Lo que suena manda al empezar: si es la radio, arranca en Radio.
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps = com.irvingguerra.litedash.feature.apps.rememberApps(d.apps).orEmpty()
    fun appOf(pkg: String?) = pkg?.let { p -> apps.firstOrNull { it.pkg == p } }
    // Los accesos del panel: la música abre su app (Spotify u otra) y el tiempo de manejo, la de mapas; cada una en su ventana de al lado.
    val musicApp = appOf(d.music.preferred) ?: d.apps.firstOf(apps, *MUSIC_PACKAGES)
    val radioApp = appOf(d.music.radioApp) ?: musicApp
    fun open(app: com.irvingguerra.litedash.feature.apps.App?) { app?.let { d.apps.open(context, it) } }
    var radio by remember { mutableStateOf(d.music.radio.song?.playing == true && d.music.song?.playing != true) }
    Box(Modifier.fillMaxSize().background(c.background).padding(10.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(0.8f)) { SpeedRpmStrip(d) }
            Row(Modifier.weight(0.65f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val coolant = d.bus.watch(com.irvingguerra.litedash.core.vehicle.Signal.COOLANT)
                val volts = d.bus.watch(com.irvingguerra.litedash.core.vehicle.Signal.VOLTAGE)
                val trip = rememberTripStats(d.trip, d.prefs.units.miles)
                val leg = d.trip.current()
                val ms = leg.movingMs / 1000
                Box(Modifier.weight(1f)) { MiniStat(stringResource(R.string.panel_engine), coolant?.let { String.format(java.util.Locale.ROOT, "%.0f°", it) } ?: "--") }
                Box(Modifier.weight(1f)) { MiniStat(stringResource(R.string.panel_battery), volts?.let { String.format(java.util.Locale.ROOT, "%.1f V", it) } ?: "--") }
                Box(Modifier.weight(1.15f)) { MiniStat(stringResource(R.string.dashito_trip_now), trip.distance + " " + trip.unit, String.format(java.util.Locale.ROOT, "%d:%02d h", ms / 3600, ms / 60 % 60)) }
            }
            Box(Modifier.weight(0.75f)) { FuelTile(d) {} }
            Column(Modifier.weight(2.6f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Segmented(listOf(false to stringResource(R.string.music), true to stringResource(R.string.tile_radio)), radio, { radio = it })
                Box(Modifier.weight(1f)) {
                    if (radio) RadioTile(d.music, 0, onIdle = {}, onOpen = { open(radioApp) }) else MusicTile(d.music, 0, onOpen = { open(musicApp) })
                }
            }
        }
        IconButton(Icons.close, onBack, Modifier.align(Alignment.TopEnd).padding(4.dp), background = c.surfaceHigh)
    }
}

/** Velocidad grande a la izquierda y las RPM a la derecha (número y barra): una franja en vez de dos medidores. */
@androidx.compose.runtime.Composable
private fun SpeedRpmStrip(d: Dependencies) {
    val c = LocalColors.current
    // Como el velocímetro: con un adaptador ya guardado se reconecta solo; sin OBD, el GPS publica la velocidad.
    val obdOn = rememberObd(d)?.state == com.irvingguerra.litedash.feature.obd.ObdState.ON
    rememberGpsPermission(d)
    if (!obdOn) KeepTripRunning(d.trip)
    val miles = d.prefs.units.miles
    val kmh = d.bus.watch(com.irvingguerra.litedash.core.vehicle.Signal.SPEED)
    val rpm = d.bus.watch(com.irvingguerra.litedash.core.vehicle.Signal.RPM)
    com.irvingguerra.litedash.core.ui.Card(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 54.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Bottom) {
                    com.irvingguerra.litedash.core.ui.Label(speedText(kmh, miles), com.irvingguerra.litedash.core.ui.Typography.clock.copy(fontSize = 52.sp), c.text, 1)
                    com.irvingguerra.litedash.core.ui.Label(speedUnit(miles), com.irvingguerra.litedash.core.ui.Typography.small, c.textMuted, 1, Modifier.padding(start = 6.dp, bottom = 8.dp))
                }
                com.irvingguerra.litedash.core.ui.Label(
                    (rpm?.let { String.format(java.util.Locale.ROOT, "%.0f", it) } ?: "--") + " rpm",
                    com.irvingguerra.litedash.core.ui.Typography.body, c.textMuted, 1, Modifier.padding(bottom = 8.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(8.dp).background(c.surfaceHigh, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth(((rpm ?: 0f) / RPM_TOP).coerceIn(0f, 1f)).fillMaxSize().background(if ((rpm ?: 0f) > RPM_RED) c.danger else c.accent, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
            }
        }
    }
}

private const val RPM_TOP = 8000f
private const val RPM_RED = 6500f

/** Mosaico chico: nombre arriba, valor al centro y, si hay, un renglón abajo. */
@androidx.compose.runtime.Composable
private fun MiniStat(name: String, value: String, sub: String? = null) {
    val c = LocalColors.current
    com.irvingguerra.litedash.core.ui.Card(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            com.irvingguerra.litedash.core.ui.Label(name, com.irvingguerra.litedash.core.ui.Typography.small, c.textMuted, 1)
            com.irvingguerra.litedash.core.ui.Label(value, com.irvingguerra.litedash.core.ui.Typography.title.copy(fontSize = 20.sp), c.text, 1)
            if (sub != null) com.irvingguerra.litedash.core.ui.Label(sub, com.irvingguerra.litedash.core.ui.Typography.small, c.textMuted, 1)
        }
    }
}
