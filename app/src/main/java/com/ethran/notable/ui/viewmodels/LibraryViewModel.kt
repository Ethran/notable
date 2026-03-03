package com.ethran.notable.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.ImportEngine
import com.ethran.notable.io.ImportOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val appRepository: AppRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    fun onCreateNew() {
        bookRepository.create(
            Notebook(
                parentFolderId = parentFolderId,
                defaultBackground = GlobalAppSettings.current.defaultNativeTemplate,
                defaultBackgroundType = BackgroundType.Native.key
            )
        )
    }

    fun onPdfFile(uri: Uri, copy: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            val snackText = if (copy) {
                "Importing PDF background (copy)"
            } else {
                "Setting up observer for PDF"
            }
            onStartImport()
            snackManager.runWithSnack(snackText) {
                ImportEngine(context).import(
                    uri,
                    ImportOptions(folderId = parentFolderId, linkToExternalFile = !copy)
                )
            }
            onEndImport()
        }
    }

    fun onXoppFile(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            onStartImport()
            snackManager.showSnackDuring("importing from xopp file") {
                ImportEngine(context).import(
                    uri, ImportOptions(folderId = parentFolderId)
                )
            }
            onEndImport()
        }
    }

}
