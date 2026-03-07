package com.ethran.notable.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val kvProxy: KvProxy,
) : ViewModel() {

    suspend fun removeWelcome() {
        kvProxy.setAppSettings(
            GlobalAppSettings.current.copy(showWelcome = false)
        )
    }


}
