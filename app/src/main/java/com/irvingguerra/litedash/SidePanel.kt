package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
                LiteDashTheme(dark = d.prefs.theme != ThemeMode.LIGHT, scale = d.prefs.scale, accent = d.prefs.accentColor, cards = d.prefs.cardColor) {
                    CompositionLocalProvider(LocalCardLook provides look) { PanelContent(d, onBack = { backToHome(app) }) }
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
        } catch (e: Exception) {
            false
        }
    }

    fun hide(context: Context) {
        val v = view ?: return
        view = null
        runCatching { context.applicationContext.getSystemService(WindowManager::class.java).removeView(v) }
    }

    private fun backToHome(context: Context) {
        hide(context)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
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

/** Velocímetro, RPM y música, con un botón para volver al tablero. */
@androidx.compose.runtime.Composable
private fun PanelContent(d: Dependencies, onBack: () -> Unit) {
    val c = LocalColors.current
    val look = remember { GaugeLook() }
    val rpm = remember { GaugeLook() }
    Box(Modifier.fillMaxSize().background(c.background).padding(10.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1.3f)) { SpeedometerTile(d, look) }
            Box(Modifier.weight(1f)) { GaugeTile(d, com.irvingguerra.litedash.core.vehicle.Signal.RPM, rpm, card = true, onPick = {}, onWorkshop = {}) }
            Box(Modifier.weight(0.8f)) { MusicTile(d.music, 0) }
        }
        IconButton(Icons.close, onBack, Modifier.align(Alignment.TopEnd).padding(4.dp), background = c.surfaceHigh)
    }
}
