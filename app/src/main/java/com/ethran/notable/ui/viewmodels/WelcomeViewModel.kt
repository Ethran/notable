package com.ethran.notable.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    // Lazy: injecting KvProxy directly would force Room's AppDatabase to be built as soon
    // as this ViewModel is constructed, i.e. as soon as the welcome screen appears - which
    // is exactly the screen shown when storage permission (and therefore the db directory)
    // isn't available yet. Deferring until removeWelcome() actually runs (only reachable
    // once permission is granted) avoids that crash.
    private val kvProxy: Lazy<KvProxy>,
) : ViewModel() {

    suspend fun removeWelcome() {
        kvProxy.get().setAppSettings(
            GlobalAppSettings.current.copy(showWelcome = false)
        )
    }


}
