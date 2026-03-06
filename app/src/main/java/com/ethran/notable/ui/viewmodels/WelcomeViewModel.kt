package com.ethran.notable.ui.viewmodels

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.ethran.notable.PACKAGE_NAME
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val kvProxy: KvProxy,
) : ViewModel() {

    fun removeWelcome() {
        kvProxy.setAppSettings(
            GlobalAppSettings.current.copy(showWelcome = false)
        )
    }


}
