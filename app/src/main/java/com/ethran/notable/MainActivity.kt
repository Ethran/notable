package com.ethran.notable


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.LocalSnackContext
import com.ethran.notable.classes.PageDataManager
import com.ethran.notable.classes.SnackBar
import com.ethran.notable.classes.SnackState
import com.ethran.notable.datastore.EditorSettingCacheManager
import com.ethran.notable.db.KvProxy
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.views.Router
import com.onyx.android.sdk.api.device.epd.EpdController
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.launch


var SCREEN_WIDTH = EpdController.getEpdHeight().toInt()
var SCREEN_HEIGHT = EpdController.getEpdWidth().toInt()

var TAG = "MainActivity"
const val APP_SETTINGS_KEY = "APP_SETTINGS"
const val PACKAGE_NAME = "com.ethran.notable"


@ExperimentalAnimationApi
@ExperimentalComposeUiApi
@ExperimentalFoundationApi
class MainActivity : ComponentActivity() {
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
        if (hasRequiredPermissions()) {
            GlobalAppSettings.update(
                KvProxy(this).get(APP_SETTINGS_KEY, AppSettings.serializer())
                    ?: AppSettings(version = 1)
            )
            // Used to load up app settings, latter used in
            // class EditorState
            EditorSettingCacheManager.init(applicationContext)
        }

        //EpdDeviceManager.enterAnimationUpdate(true);

        val intentData = intent.data?.lastPathSegment
        setContent {
            InkaTheme {
                CompositionLocalProvider(LocalSnackContext provides snackState) {
                    Box(
                        Modifier
                            .background(Color.White)
                    ) {
                        Router()
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.Black)
                    )
                    SnackBar(state = snackState)
                }
            }
        }
    }


    override fun onRestart() {
        super.onRestart()
        // redraw after device sleep
        this.lifecycleScope.launch {
            DrawCanvas.restartAfterConfChange.emit(Unit)
        }
    }

    override fun onPause() {
        super.onPause()
        this.lifecycleScope.launch {
            Log.d("QuickSettings", "App is paused - maybe quick settings opened?")

            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        Log.d(TAG, "OnWindowFocusChanged: $hasFocus")
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableFullScreen()
        }
        lifecycleScope.launch {
            DrawCanvas.onFocusChange.emit(hasFocus)
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
//        this.lifecycleScope.launch {
//            DrawCanvas.restartAfterConfChange.emit(Unit)
//        }
    }

    private fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            )
                return false
        } else if (!Environment.isExternalStorageManager())
            return false
        return true
    }
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001
                )
            }
        } else if (!Environment.isExternalStorageManager()) {
            requestManageAllFilesPermission()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestManageAllFilesPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.fromParts("package", packageName, null)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
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