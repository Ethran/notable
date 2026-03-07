package com.ethran.notable


import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
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
import com.ethran.notable.ui.LocalSnackContext
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


var SCREEN_WIDTH = EpdController.getEpdHeight().toInt()
var SCREEN_HEIGHT = EpdController.getEpdWidth().toInt()

var TAG = "MainActivity"
const val APP_SETTINGS_KEY = "APP_SETTINGS"
const val PACKAGE_NAME = "com.ethran.notable"


@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var kvProxy: KvProxy

    @Inject
    lateinit var strokeMigrationHelper: StrokeMigrationHelper
    @Inject
    lateinit var editorSettingCacheManager: EditorSettingCacheManager

    @Inject
    lateinit var appRepository: AppRepository


    @Inject
    lateinit var exportEngine: ExportEngine

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
        snackState.registerGlobalSnackObserver()
        snackState.registerCancelGlobalSnackObserver()
        PageDataManager.registerComponentCallbacks(this)
        //EpdDeviceManager.enterAnimationUpdate(true);
//        val intentData = intent.data?.lastPathSegment

        setContent {
            var isInitialized by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                if (hasFilePermission(this@MainActivity)) {
                    withContext(Dispatchers.IO) {
                        // Init app settings, also do migration
                        val savedSettings = kvProxy.get(APP_SETTINGS_KEY, AppSettings.serializer())
                            ?: AppSettings(version = 1)

                        GlobalAppSettings.update(savedSettings)

                        // Used to load up app settings, latter used in
                        // class EditorState
                        editorSettingCacheManager.init()
                        strokeMigrationHelper.reencodeStrokePointsToSB1()
                    }
                }
                isInitialized = true
            }

            InkaTheme {
                CompositionLocalProvider(LocalSnackContext provides snackState) {
                    if (isInitialized) {
                        NotableApp(
                            exportEngine = exportEngine,
                            editorSettingCacheManager = editorSettingCacheManager,
                            snackState = snackState,
                            appRepository = appRepository
                        )
                    } else
                        ShowInitMessage()
                }
            }
        }
    }


    override fun onRestart() {
        super.onRestart()
        // redraw after device sleep
        this.lifecycleScope.launch {
            CanvasEventBus.restartAfterConfChange.emit(Unit)
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
        // Not necessary, done in CanvasEventBus.surfaceChanged()
//        this.lifecycleScope.launch {
//            CanvasEventBus.restartAfterConfChange.emit(Unit)
//        }
    }


    // written by GPT, but it works
    // needs to be checked if it is ok approach.
    private fun enableFullScreen() {
        // Turn on onyx optimization, no idea what it does.
        // https://github.com/onyx-intl/OnyxAndroidDemo/blob/3290434f0edba751ec907d777fe95208378ae752/app/OnyxAndroidDemo/src/main/java/com/android/onyx/demo/AppOptimizeActivity.java#L4
        Intent().apply {
            action = "com.onyx.app.optimize.setting"
            putExtra("optimize_fullScreen", true)
            putExtra("optimize_pkgName", "com.ethran.notable")
            sendBroadcast(this)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11 and above
            // 'setDecorFitsSystemWindows(Boolean): Unit' is deprecated. Deprecated in Java
//            window.setDecorFitsSystemWindows(false)
            WindowCompat.setDecorFitsSystemWindows(window, false)
//            if (window.insetsController != null) {
//                window.insetsController!!.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
//                window.insetsController!!.systemBarsBehavior =
//                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
//            }
            // Safely access the WindowInsetsController
            val controller = window.decorView.windowInsetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                Log.e(TAG, "WindowInsetsController is null")
            }
        } else {
            // For Android 10 and below
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
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