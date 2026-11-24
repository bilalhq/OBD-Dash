package com.obddash.car

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obddash.ConnState
import com.obddash.ObdApp
import com.obddash.UiState
import com.obddash.obd.Pid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Android Auto dashboard. Uses the navigation template so the app can draw
 * the gauges itself on the car's surface; the action strip holds the buttons.
 */
class CarDashScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private val controller = (carContext.applicationContext as ObdApp).controller
    private val renderer = CarDashRenderer(carContext)

    private var surface: Surface? = null
    private var surfaceRect = Rect()
    private var visibleArea: Rect? = null
    private var density = 1f
    private var permissionOk = hasPermission()

    // Values eased toward the latest reading each frame so the needles glide.
    private var shownRpm: Float? = null
    private var shownSpeed: Float? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@CarDashScreen)
                permissionOk = hasPermission()
                if (permissionOk) {
                    controller.refreshDevices()
                    controller.connectLastIfIdle()
                }
            }
        })

        // Up to ~30 fps while the screen is visible, but frames are only drawn when the data
        // changed or a needle is still easing (see renderFrame), to keep CPU use low.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    renderFrame()
                    delay(33)
                }
            }
        }

        // Rebuild the action strip when what it shows changes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state
                    .map { Triple(it.conn, it.imperial, it.codes.stored.size + it.codes.pending.size) }
                    .distinctUntilChanged()
                    .collect { invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = controller.state.value
        val strip = ActionStrip.Builder()

        if (s.conn == ConnState.Connected) {
            val count = s.codes.stored.size + s.codes.pending.size
            strip.addAction(
                Action.Builder()
                    .setTitle(if (count > 0) "Codes ($count)" else "Codes")
                    .setOnClickListener { screenManager.push(CodesCarScreen(carContext)) }
                    .build()
            )
        } else if (s.conn != ConnState.Connecting) {
            strip.addAction(
                Action.Builder()
                    .setTitle("Connect")
                    .setOnClickListener { onConnectClicked() }
                    .build()
            )
            strip.addAction(
                Action.Builder()
                    .setTitle("Demo")
                    .setOnClickListener { controller.startDemo() }
                    .build()
            )
        }
        strip.addAction(
            Action.Builder()
                .setTitle(if (s.imperial) "mph" else "km/h")
                .setOnClickListener { controller.toggleUnits() }
                .build()
        )
        return NavigationTemplate.Builder().setActionStrip(strip.build()).build()
    }

    private fun onConnectClicked() {
        permissionOk = hasPermission()
        if (!permissionOk) {
            CarToast.makeText(carContext, "Open OBD Dash on your phone to allow Bluetooth", CarToast.LENGTH_LONG).show()
            return
        }
        controller.refreshDevices()
        if (!controller.connectLastIfIdle()) {
            CarToast.makeText(carContext, "Connect to your adapter once from the phone app first", CarToast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ surface

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        surfaceRect = Rect(0, 0, surfaceContainer.width, surfaceContainer.height)
        density = surfaceContainer.dpi / 160f
        dirty = true
        Log.i(TAG, "surface ${surfaceContainer.width}x${surfaceContainer.height} dpi=${surfaceContainer.dpi}")
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.i(TAG, "visibleArea $visibleArea")
        this.visibleArea = visibleArea
        dirty = true
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.i(TAG, "stableArea $stableArea")
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    /** Last state actually drawn; identical state + settled needles = nothing to redraw. */
    private var lastDrawn: UiState? = null
    private var dirty = true

    private fun renderFrame() {
        val sfc = surface ?: return
        if (!sfc.isValid) return
        val s = controller.state.value
        val prevRpm = shownRpm
        val prevSpeed = shownSpeed
        shownRpm = ease(shownRpm, s.values[Pid.RPM])
        shownSpeed = ease(shownSpeed, s.values[Pid.SPEED])
        val animating = shownRpm != prevRpm || shownSpeed != prevSpeed
        if (!dirty && !animating && s === lastDrawn) return
        lastDrawn = s
        dirty = false

        // Software canvas on purpose: a hardware canvas hands drawing to the RenderThread, which
        // aborts the whole process ("drawRenderNode called on a context with no surface") if the
        // host tears the surface down mid-frame. A software canvas just throws, which we catch.
        val canvas = try {
            sfc.lockCanvas(null)
        } catch (e: Exception) {
            return
        } ?: return
        try {
            val area = visibleArea?.takeIf { !it.isEmpty } ?: surfaceRect
            renderer.draw(canvas, area, density, s, shownRpm, shownSpeed, permissionMissing = !permissionOk)
        } finally {
            runCatching { sfc.unlockCanvasAndPost(canvas) }
        }
    }

    private fun ease(current: Float?, target: Float?): Float? = when {
        target == null -> null
        current == null -> target
        kotlin.math.abs(target - current) < 0.5f -> target // settle, so the redraw loop can idle
        else -> current + (target - current) * 0.25f
    }

    private companion object {
        const val TAG = "OBDDashCar"
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(carContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
