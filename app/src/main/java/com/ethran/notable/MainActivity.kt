package com.ethran.notable

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.StrokeMigrationHelper
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.ui.AppEventUiBridge
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.components.NotableApp
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.utils.hasFilePermission
import com.onyx.android.sdk.api.device.epd.EpdController
import dagger.hilt.android.AndroidEntryPoint
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


private const val TAG = "MainActivity"
const val APP_SETTINGS_KEY = "APP_SETTINGS"
const val PACKAGE_NAME = "com.ethran.notable"

// TODO: Check if migrating to LocalConfiguration in Compose is good idea
var SCREEN_WIDTH = EpdController.getEpdHeight().toInt()
var SCREEN_HEIGHT = EpdController.getEpdWidth().toInt()

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Delay the init till we have the permissions required
    @Inject
    lateinit var kvProxy: dagger.Lazy<KvProxy>

    @Inject
    lateinit var strokeMigrationHelper: dagger.Lazy<StrokeMigrationHelper>

    @Inject
    lateinit var editorSettingCacheManager: dagger.Lazy<EditorSettingCacheManager>

    @Inject
    lateinit var appRepositoryLazy: dagger.Lazy<AppRepository>

    @Inject
    lateinit var exportEngineLazy: dagger.Lazy<ExportEngine>

    @Inject
    lateinit var pageDataManager: dagger.Lazy<PageDataManager>

    @Inject
    lateinit var syncScheduler: dagger.Lazy<SyncScheduler>

    @Inject
    lateinit var snackDispatcher: SnackDispatcher

    @Inject
    lateinit var appEventUiBridge: AppEventUiBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableFullScreen()
        ShipBook.start(
            this.application, BuildConfig.SHIPBOOK_APP_ID, BuildConfig.SHIPBOOK_APP_KEY
        )

        Log.i(TAG, "Notable started")

        SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
        SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels

        val snackState = SnackState()

        setContent {
            var isInitialized by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                if (hasFilePermission(this@MainActivity)) {
                    withContext(Dispatchers.IO) {
                        // Init app settings, also do migration
                        val savedSettings =
                            kvProxy.get().get(APP_SETTINGS_KEY, AppSettings.serializer())
                                ?: AppSettings(version = 1)

                        GlobalAppSettings.update(savedSettings)
                        strokeMigrationHelper.get().reencodeStrokePointsToSB1()
                        pageDataManager.get()
                            .registerComponentCallbacks(this@MainActivity.applicationContext)
                        editorSettingCacheManager.get().init()
                    }
                    restorePeriodicSyncSchedule()
                    // Trigger initial sync on app startup (fails silently if offline)
                    triggerInitialSync()
                }
                isInitialized = true
            }

            InkaTheme {
                CompositionLocalProvider(LocalSnackContext provides snackState) {
                    if (isInitialized) {
                        NotableApp(
                            // Call .get() here so they are only instantiated AFTER the permission check runs
                            exportEngine = exportEngineLazy.get(),
                            snackState = snackState,
                            snackDispatcher = snackDispatcher,
                            appRepository = appRepositoryLazy.get()
                        )
                    } else {
                        ShowInitMessage()
                    }
                }
            }
        }
    }


    private fun triggerInitialSync() {
        lifecycleScope.launch {
            try {
                val settings = kvProxy.get().getSyncSettings()
                if (settings.syncEnabled) {
                    Log.i(TAG, "Triggering one-time sync on app startup via WorkManager")
                    syncScheduler.get().triggerImmediateSync()
                }
            } catch (e: Exception) {
                Log.i(TAG, "Initial sync setup failed: ${e.message}")
            }
        }
    }

    private fun restorePeriodicSyncSchedule() {
        lifecycleScope.launch {
            try {
                val settings = kvProxy.get().getSyncSettings()
                syncScheduler.get().reconcilePeriodicSync(settings)
            } catch (e: Exception) {
                Log.i(TAG, "Periodic sync reconcile failed: ${e.message}")
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        // redraw after device sleep
        this.lifecycleScope.launch {
            CanvasEventBus.reinitSignal.emit(Unit)
        }
    }

    override fun onPause() {
        super.onPause()
        this.lifecycleScope.launch {
            Log.d("QuickSettings", "App is paused - maybe quick settings opened?")
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        Log.d(TAG, "OnWindowFocusChanged: $hasFocus")
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableFullScreen()
        }
        lifecycleScope.launch {
            CanvasEventBus.onFocusChange.emit(hasFocus)
        }
    }

    override fun onResume() {
        super.onResume()
        enableFullScreen()
        lifecycleScope.launch {
            CanvasEventBus.onFocusChange.emit(true)
        }
    }


    // when the screen orientation is changed, set new screen width restart is not necessary,
    // as we need first to update page dimensions which is done in EditorView()
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.i(TAG, "Switched to Landscape")
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.i(TAG, "Switched to Portrait")
        }
        SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
        SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels
    }

    private fun enableFullScreen() {
        // Clearer intent broadcasting syntax
        val optimizeIntent = Intent("com.onyx.app.optimize.setting").apply {
            putExtra("optimize_fullScreen", true)
            putExtra(
                "optimize_pkgName", packageName
            ) // Use Context.packageName instead of hardcoding
        }
        sendBroadcast(optimizeIntent)

        // Modern, backwards-compatible AndroidX way to handle fullscreen / insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
@Preview(showBackground = true)
fun ShowInitMessage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Initializing...",
            color = Color.Black,
            fontSize = 30.sp
        )
    }
}