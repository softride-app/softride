package ai.softride.app

import ai.softride.app.engine.Screen
import ai.softride.app.ui.Day
import ai.softride.app.ui.DriverScreen
import ai.softride.app.ui.LocalPalette
import ai.softride.app.ui.LocalScale
import ai.softride.app.ui.Night
import ai.softride.app.ui.ReportScreen
import ai.softride.app.ui.Scale
import ai.softride.app.ui.SettingsScreen
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.collectAsState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = arrayOf(
            "android.car.permission.CAR_SPEED", Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECORD_AUDIO, "pleos.car.permission.FUSED_LOCATION",
        )
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        startCueService(intent.getBooleanExtra(CueService.EXTRA_REPLAY, false))

        val engine = (application as SoftRideApp).engine
        setContent {
            // collectAsState, not collectAsStateWithLifecycle: lifecycle-runtime-compose 2.9 looks up
            // compose-ui 1.6's LocalLifecycleOwner by reflection, which R8 renames → release crash.
            val ui by engine.ui.collectAsState()
            val settings by engine.settings.state.collectAsState()
            var showSettings by rememberSaveable { mutableStateOf(false) }
            val density = LocalDensity.current.density
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // layout px (Pleos app area 1680×1210 beside the driving view) → this window
                val k = minOf(constraints.maxWidth / 1680f, constraints.maxHeight / 1210f)
                CompositionLocalProvider(
                    LocalPalette provides if (isSystemInDarkTheme()) Night else Day,
                    LocalScale provides Scale(k, density),
                ) {
                    val report = ui.report
                    when {
                        showSettings -> SettingsScreen(settings, locked = !ui.parked, store = engine.settings) { showSettings = false }
                        ui.screen == Screen.REPORT && report != null -> ReportScreen(report, { startCueService(true) }, { showSettings = true })
                        else -> DriverScreen(ui, { startCueService(true) }, { showSettings = true })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(CueService.EXTRA_REPLAY, false)) startCueService(true)
        if (intent.getBooleanExtra(CueService.EXTRA_NAVI_PROBE, false)) startCueService(false, naviProbe = true)
    }

    private fun startCueService(replay: Boolean, naviProbe: Boolean = false) {
        startForegroundService(Intent(this, CueService::class.java)
            .putExtra(CueService.EXTRA_REPLAY, replay).putExtra(CueService.EXTRA_NAVI_PROBE, naviProbe))
    }
}
