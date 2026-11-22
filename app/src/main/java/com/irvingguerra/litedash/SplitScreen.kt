package com.irvingguerra.litedash

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Pantalla dividida real del sistema: la app de mapa o de música en una mitad y el auto
 * (3D o en carretera) en la otra. El inicio no puede ser una mitad, así que el auto vive en
 * [SplitActivity], una actividad aparte en su propia tarea; el inicio no se toca y, al
 * regresar, se ve como estaba. Necesita Android 12L o más nuevo y la pantalla acostada.
 */
object SplitScreen {
    private const val EXTRA_APP = "app"
    private const val EXTRA_OPEN = "open"
    /** Del lado que debe quedar el auto antes de abrir la app (true = izquierda). */
    private const val EXTRA_LEFT = "left"

    /** Cada cuánto el auto revisa si ya quedó en su lado, y hasta cuándo esperar. */
    private const val CHECK_MS = 100L
    private const val GIVE_UP_MS = 3_000L

    /** Si esta pantalla deja abrir dos apps lado a lado desde una app (Android 12L+). */
    // El ajuste del sistema no tiene constante pública: se busca por nombre (una vez por consulta).
    @SuppressLint("DiscouragedApi")
    fun supported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return false
        val r = Resources.getSystem()
        val id = r.getIdentifier("config_supportsMultiWindow", "bool", "android")
        return id == 0 || r.getBoolean(id)
    }

    /** Las apps que se abren divididas: las de mapa y de música (las conocidas, la elegida o por su categoría). */
    private fun isMapOrMusic(context: Context, d: Dependencies, pkg: String): Boolean {
        if (pkg in NAVIGATION_PACKAGES || pkg in MUSIC_PACKAGES || pkg == d.music.preferred) return true
        if (d.prefs.mapApp?.substringBefore('/') == pkg) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val category = try {
            context.packageManager.getApplicationInfo(pkg, 0).category
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return category == ApplicationInfo.CATEGORY_MAPS || category == ApplicationInfo.CATEGORY_AUDIO
    }

    /** Abre [intent] dividida si toca; false = que se abra normal. */
    fun open(context: Context, intent: Intent, pkg: String): Boolean {
        val d = (context.applicationContext as LiteDashApp).dependencies
        if (d.prefs.freeformMaps && !supported() && openFreeform(context, d, intent, pkg)) return true
        // Android solo divide la pantalla así desde la 12L: antes, la app se abre normal.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2 || !d.prefs.splitScreen || !supported()) return false
        if (context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return false
        if (!isMapOrMusic(context, d, pkg)) return false
        start(context, intent, d.prefs.splitCarLeft)
        return true
    }

    /**
     * Android solo arma la división desde la ventana que está al frente, y lo que se abre
     * "al lado" queda del otro lado de quien lo abre. Por eso todo sale de ventanas visibles:
     * - Auto a la izquierda desde el inicio: el auto (completo) abre la app a su lado.
     * - Auto a la derecha: una ventana de paso ([SplitStartActivity]) abre el auto a su lado
     *   (a la derecha) y el auto, ya en su lugar, abre la app a su lado: donde estaba la de paso.
     */
    @RequiresApi(Build.VERSION_CODES.S_V2)
    internal fun start(context: Context, app: Intent, carLeft: Boolean) {
        val target = if (carLeft) SplitActivity::class.java else SplitStartActivity::class.java
        val intent = Intent(context, target)
            .putExtra(EXTRA_APP, app)
            .putExtra(EXTRA_OPEN, true)
            .putExtra(EXTRA_LEFT, carLeft)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch(context, intent)
    }

    /**
     * Sin pantalla dividida (Android 7–12 o fabricante que la bloquea): la app de mapa o música en una ventana libre
     * de [Preferences.freeformWidth] % de la pantalla, del lado contrario al auto. Solo se pide el tamaño: si el sistema
     * no tiene las ventanas libres activas, la app se abre normal (a pantalla completa).
     */
    private fun openFreeform(context: Context, d: Dependencies, intent: Intent, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return false
        if (!isMapOrMusic(context, d, pkg)) return false
        // Sin el permiso de mostrar sobre otras apps el panel no sale: se pide primero (y Mapas no se abre encima de esa pantalla).
        if (!d.prefs.freeformDock && !SidePanel.allowed(context)) {
            SidePanel.askPermission(context)
            return true
        }
        val screen = context.resources.displayMetrics
        val width = screen.widthPixels * d.prefs.freeformWidth.coerceIn(30, 70) / 100
        val rect = if (d.prefs.splitCarLeft) Rect(screen.widthPixels - width, 0, screen.widthPixels, screen.heightPixels) else Rect(0, 0, width, screen.heightPixels)
        return try {
            val options = ActivityOptions.makeBasic()
            // Método oculto: con la división real (4 = mitad secundaria) el sistema acomoda LiteDash en la otra mitad y las
            // dos quedan fijas; con la libre (5) es una ventana flotante del tamaño pedido. Si no existe, se sigue sin él.
            val dock = d.prefs.freeformDock
            if (!dock) options.setLaunchBounds(rect)
            runCatching { ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(options, if (dock) WINDOWING_MODE_SPLIT_SECONDARY else WINDOWING_MODE_FREEFORM) }
                .onFailure { Log.w(TAG, "sin modo de ventana: ${it.message}") }
            if (dock) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            context.startActivity(intent, options.toBundle())
            // Con la ventana libre, el panel fijo del otro lado (si ya hay permiso de mostrar sobre otras apps).
            if (!dock) {
                if (SidePanel.allowed(context)) {
                    // Después de que Mapas ya está al frente: si no, el panel nace antes y queda tapado.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { SidePanel.show(context, screen.widthPixels - width, left = d.prefs.splitCarLeft) }, 800L,
                    )
                } else SidePanel.askPermission(context)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "ventana libre: ${e.message}", e)
            false
        }
    }

    /** Abre [intent]; si Android no deja, queda anotado en el registro y regresa false. */
    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no se pudo abrir ${intent.component ?: intent.`package`}", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Android no dejó abrir ${intent.component ?: intent.`package`}", e)
        false
    }

    private const val TAG = "SplitScreen"
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val WINDOWING_MODE_SPLIT_SECONDARY = 4

    internal fun appOf(intent: Intent): Intent? = IntentCompat.getParcelableExtra(intent, EXTRA_APP, Intent::class.java)

    /**
     * El auto abre la app a su lado, pero hasta que ya quedó del lado que le toca (en un
     * celular o pantalla lenta la división tarda en acomodarse). Si no llega en [GIVE_UP_MS],
     * la abre igual: mejor del otro lado que no abrirla.
     */
    @RequiresApi(Build.VERSION_CODES.S_V2)
    internal fun openBeside(activity: SplitActivity, intent: Intent) {
        val app = appOf(intent) ?: return
        if (!intent.getBooleanExtra(EXTRA_OPEN, false)) return
        // Ya se armó: que no se vuelva a abrir al girar la pantalla o regresar.
        intent.removeExtra(EXTRA_OPEN)
        val left = intent.getBooleanExtra(EXTRA_LEFT, true)
        val view = activity.window.decorView
        val since = SystemClock.elapsedRealtime()
        fun tryOpen() {
            // Una ventana vieja (se recreó al girar) ya no abre nada.
            if (activity.isFinishing || activity.isDestroyed) return
            if (!activity.onSide(left) && SystemClock.elapsedRealtime() - since < GIVE_UP_MS) {
                view.postDelayed(::tryOpen, CHECK_MS)
                return
            }
            launch(activity, Intent(app).addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        view.post(::tryOpen)
    }

    /** La ventana de paso abre el auto a su lado, que debe quedar a la [EXTRA_LEFT]. */
    @RequiresApi(Build.VERSION_CODES.S_V2)
    internal fun moveCar(activity: ComponentActivity, intent: Intent) {
        val app = appOf(intent) ?: return activity.finish()
        val board = Intent(activity, SplitActivity::class.java)
            .putExtra(EXTRA_APP, app)
            .putExtra(EXTRA_OPEN, true)
            .putExtra(EXTRA_LEFT, intent.getBooleanExtra(EXTRA_LEFT, false))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        launch(activity, board)
    }

    /**
     * Cambia el auto de lado sin cerrar nada (cerrar y volver a abrir desde atrás lo bloquea
     * Android en los equipos de verdad): el auto abre la ventana de paso en su mismo lugar, esa
     * ventana manda el auto al otro lado y el auto abre la app donde quedó la de paso.
     */
    @RequiresApi(Build.VERSION_CODES.S_V2)
    internal fun swap(activity: SplitActivity, app: Intent?) {
        app ?: return
        val prefs = (activity.application as LiteDashApp).dependencies.prefs
        val left = !prefs.splitCarLeft
        val step = Intent(activity, SplitStartActivity::class.java)
            .putExtra(EXTRA_APP, app)
            .putExtra(EXTRA_LEFT, left)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        // El lado guardado cambia solo si de verdad se movió (si no, quedaría al revés de lo que se ve).
        if (launch(activity, step)) prefs.splitCarLeft = left
    }
}

/** ¿La ventana ya está del lado [left] de la pantalla? (Completa cuenta como izquierda.) */
@RequiresApi(Build.VERSION_CODES.S_V2)
private fun ComponentActivity.onSide(left: Boolean): Boolean {
    val at = IntArray(2)
    window.decorView.getLocationOnScreen(at)
    return onSide(left, at[0], window.decorView.width, resources.displayMetrics.widthPixels, isInMultiWindowMode)
}

/**
 * Una ventana que empieza en [x] y mide [width] de una pantalla de [screen], ¿está del lado [left]? A la izquierda
 * cuenta completa (recién abierta desde el inicio) o ya en la mitad izquierda; a la derecha, la que empieza pasado el cuarto.
 */
internal fun onSide(left: Boolean, x: Int, width: Int, screen: Int, multiWindow: Boolean): Boolean {
    val total = screen.coerceAtLeast(1)
    val onLeft = x < total / 4
    if (!left) return !onLeft
    return onLeft && (!multiWindow || width < total * 3 / 4)
}

/** El auto solo, para la mitad de la pantalla dividida, con cambiar de lado y salir. */
@RequiresApi(Build.VERSION_CODES.S_V2)
class SplitActivity : ComponentActivity() {
    private val dependencies get() = (application as LiteDashApp).dependencies
    private val revision = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiteDashContent(dependencies, revision.intValue) { SplitBoard(dependencies, onSwap = { SplitScreen.swap(this, SplitScreen.appOf(intent)) }, onExit = { finishAndRemoveTask() }) }
        }
        if (savedInstanceState == null) openAppIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openAppIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        revision.intValue++
    }

    // Ya con el auto al frente (y en su lado), se arma la división desde su ventana.
    private fun openAppIfNeeded() = SplitScreen.openBeside(this, intent)

    override fun attachBaseContext(base: Context) = super.attachBaseContext(contextWithLanguage(base))

    // Con un idioma bajado, sus recursos no los pone al día Android ([TranslatedResources.sync]).
    override fun onConfigurationChanged(newConfig: Configuration) {
        syncTranslated(resources, newConfig)
        super.onConfigurationChanged(newConfig)
    }
}

/**
 * Ventana de paso, oscura y de un instante (opaca: desde una transparente Android a veces no
 * divide): manda el auto a su lado. Es noHistory: se va sola cuando la app la tapa.
 */
@RequiresApi(Build.VERSION_CODES.S_V2)
class SplitStartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) window.decorView.post { SplitScreen.moveCar(this, intent) }
    }
}

@Composable
private fun SplitBoard(d: Dependencies, onSwap: () -> Unit, onExit: () -> Unit) {
    val c = LocalColors.current
    val car = remember(d.prefs.carModel) { d.cars.pkg(d.prefs.carModel) }
    Box(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
        val modifier = Modifier.fillMaxSize().padding(16.dp)
        if (d.prefs.splitRoad) RoadView(d, car, onClick = {}, modifier = modifier) else HomeCar(d, car, onClick = {}, modifier = modifier)
        Row(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(Icons.swap, onSwap, background = c.surfaceHigh)
            IconButton(Icons.expand, onExit, background = c.surfaceHigh)
        }
    }
}

/** Ajustes → Apps → Pantalla dividida. */
@Composable
fun SplitSettings(d: Dependencies) {
    val c = LocalColors.current
    val supported = remember { SplitScreen.supported() }
    // Sin pantalla dividida en este Android: se dice por qué y, desde Android 7, se ofrece la ventana libre.
    if (!supported) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SwitchRow(stringResource(R.string.freeform_title), d.prefs.freeformMaps, { d.prefs.freeformMaps = it }, stringResource(R.string.freeform_help))
            if (d.prefs.freeformMaps) {
                Segmented(
                    listOf(true to stringResource(R.string.freeform_dock), false to stringResource(R.string.freeform_float)),
                    d.prefs.freeformDock,
                    { d.prefs.freeformDock = it },
                )
                Label(stringResource(R.string.freeform_width), Typography.label, c.textMuted)
                Segmented(listOf(40 to "40 %", 50 to "50 %", 60 to "60 %"), d.prefs.freeformWidth, { d.prefs.freeformWidth = it })
                Label(stringResource(R.string.split_side), Typography.label, c.textMuted)
                Segmented(
                    listOf(true to stringResource(R.string.split_left), false to stringResource(R.string.split_right)),
                    d.prefs.splitCarLeft,
                    { d.prefs.splitCarLeft = it },
                )
            }
        }
        Label(stringResource(R.string.split_title), Typography.subtitle)
        // Dos razones distintas: Android viejo, o un Android nuevo cuya pantalla no deja dividir.
        val reason = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) stringResource(R.string.split_unsupported, Build.VERSION.RELEASE)
        else stringResource(R.string.split_unsupported_screen)
        Label(reason, Typography.small, c.textMuted)
        return
    }
    SwitchRow(stringResource(R.string.split_title), d.prefs.splitScreen, { d.prefs.splitScreen = it }, stringResource(R.string.split_help))
    if (d.prefs.splitScreen) {
        Label(stringResource(R.string.split_view), Typography.label, c.textMuted)
        Segmented(
            listOf(false to stringResource(R.string.tile_car), true to stringResource(R.string.tile_road)),
            d.prefs.splitRoad,
            { d.prefs.splitRoad = it },
        )
        Label(stringResource(R.string.split_side), Typography.label, c.textMuted)
        Segmented(
            listOf(true to stringResource(R.string.split_left), false to stringResource(R.string.split_right)),
            d.prefs.splitCarLeft,
            { d.prefs.splitCarLeft = it },
        )
        Label(stringResource(R.string.split_swap_help), Typography.small, c.textMuted)
    }
}
