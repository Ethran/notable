package com.ethran.notable.floatingEditor

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.exportBook
import com.ethran.notable.io.exportPageToPng
import com.ethran.notable.ui.theme.InkaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FloatingEditorActivity : ComponentActivity() {
    private lateinit var appRepository: AppRepository
    private var pageId: String? = null
    private var bookId: String? = null

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                showEditor = true
            }
        }

    private var showEditor by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent.data?.lastPathSegment
        if (data == null) {
            finish()
            return
        }

        if (data.startsWith("page-")) {
            pageId = data.removePrefix("page-")
        } else if (data.startsWith("book-")) {
            bookId = data.removePrefix("book-")
        } else {
            pageId = data
            return
        }


        appRepository = AppRepository(this)

        setContent {
            InkaTheme {
                Surface(
                    modifier = Modifier.Companion.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    val navController = rememberNavController()

                    LaunchedEffect(Unit) {
                        if (!Settings.canDrawOverlays(this@FloatingEditorActivity)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:$packageName".toUri()
                            )
                            overlayPermissionLauncher.launch(intent)
                        } else {
                            showEditor = true
                        }
                    }

                    if (showEditor) {
                        FloatingEditorContent(navController, pageId, bookId)
                    }
                }
            }
        }
    }

    @Composable
    private fun FloatingEditorContent(
        navController: NavController,
        pageId: String? = null,
        bookId: String? = null
    ) {
        if (pageId != null) {
            var page = appRepository.pageRepository.getById(pageId)
            if (page == null) {
                page = Page(
                    id = pageId,
                    notebookId = null,
                    parentFolderId = null,
                    background = GlobalAppSettings.current.defaultNativeTemplate,
                    backgroundType = BackgroundType.Native.key
                )
                appRepository.pageRepository.create(page)
            }

            FloatingEditorView(
                navController = navController,
                pageId = pageId,
                onDismissRequest = { finish() }
            )
        } else if (bookId != null) {
            FloatingEditorView(
                navController = navController,
                bookId = bookId,
                onDismissRequest = { finish() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pageId?.let { id ->
            val context = this
            lifecycleScope.launch(Dispatchers.IO) {
                exportPageToPng(context, id)
            }
        }
        bookId?.let { id ->
            lifecycleScope.launch(Dispatchers.IO) {
                exportBook(this@FloatingEditorActivity, id)
            }
        }
    }
}