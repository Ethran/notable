package com.ethran.notable

import android.app.Application
import android.util.Log
import com.onyx.android.sdk.rx.RxManager
import dagger.hilt.android.HiltAndroidApp
import org.lsposed.hiddenapibypass.HiddenApiBypass

@HiltAndroidApp
class NotableApp : Application() {

    override fun onCreate() {
        Log.i("NotableApp", "onCreate START")
        super.onCreate()
        RxManager.Builder.initAppContext(this)
        checkHiddenApiBypass()
        Log.i("NotableApp", "onCreate FINISH")
    }

    private fun checkHiddenApiBypass() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

}