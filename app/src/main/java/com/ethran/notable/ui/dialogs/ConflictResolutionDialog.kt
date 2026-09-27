package com.ethran.notable.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.sync.NotebookConflict
import com.ethran.notable.sync.NotebookConflictResolution
import com.ethran.notable.sync.PageConflictResolution
import com.ethran.notable.sync.SyncOrchestratorEntryPoint
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.components.ScaledDialog
import com.ethran.notable.utils.AppResult
import dagger.hilt.EntryPoints
import kotlinx.coroutines.launch

/**
 * Lets the user resolve a notebook flagged CONFLICT — a page edited on both sides, or a manifest that
 * diverged structurally — by choosing which version wins. The choices map onto
 * [com.ethran.notable.sync.SyncOrchestrator.resolvePageConflict] and
 * [com.ethran.notable.sync.SyncOrchestrator.resolveNotebookConflict]; each applies its choice and runs
 * a sync, so the CONFLICT badge clears once the last conflict is resolved.
 *
 * A structural conflict can only be resolved whole-notebook (KEEP_LOCAL / TAKE_SERVER), which also
 * settles any page conflicts in the same direction; page-only conflicts are resolved per page, each
 * shown with its page number and a local thumbnail so the choice is at least identifiable. A failed
 * resolution keeps the dialog open and surfaces the error rather than closing over it.
 */
@Composable
fun ConflictResolutionDialog(
    bookId: String,
    title: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val orchestrator = remember {
        EntryPoints.get(context.applicationContext, SyncOrchestratorEntryPoint::class.java)
            .syncOrchestrator()
    }
    val scope = rememberCoroutineScope()

    var conflict by remember { mutableStateOf<NotebookConflict?>(null) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true
        when (val result = orchestrator.notebookConflict(bookId)) {
            is AppResult.Success -> {
                conflict = result.data
                loading = false
                if (result.data.isEmpty) onClose()
            }

            is AppResult.Error -> {
                error = result.error.userMessage
                loading = false
            }
        }
    }

    fun applyPage(pageId: String, resolution: PageConflictResolution) {
        working = true
        error = null
        scope.launch {
            if (resolution == PageConflictResolution.SKIP) {
                // Skipping only hides the page for now; it is still diverged on disk and will be
                // asked again on the next sync, so just drop it from this session's list.
                val remaining = conflict?.pageConflicts?.filterNot { it.pageId == pageId }.orEmpty()
                conflict = conflict?.copy(pageConflicts = remaining)
                working = false
                if (conflict?.isEmpty == true) onClose()
            } else {
                when (val result = orchestrator.resolvePageConflict(bookId, pageId, resolution)) {
                    is AppResult.Success -> refresh()
                    is AppResult.Error -> error = result.error.userMessage
                }
                working = false
            }
        }
    }

    fun applyNotebook(resolution: NotebookConflictResolution) {
        working = true
        error = null
        scope.launch {
            when (val result = orchestrator.resolveNotebookConflict(bookId, resolution)) {
                is AppResult.Success -> onClose()
                is AppResult.Error -> {
                    error = result.error.userMessage
                    working = false
                }
            }
        }
    }

    LaunchedEffect(bookId) { refresh() }

    ScaledDialog(onDismissRequest = { if (!working) onClose() }) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .padding(24.dp)
                .widthIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Resolve sync conflict", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("\"$title\" was changed both here and on the server.", fontSize = 14.sp)

            val current = conflict
            when {
                loading -> Text("Checking the server…", fontSize = 14.sp)

                current == null -> Text(
                    "Could not read the conflict. Check your connection and try again.",
                    fontSize = 14.sp
                )

                current.structural -> {
                    Text(
                        "The notebook's structure differs — pages were added, removed, reordered, " +
                            "renamed, or its settings changed. Choose which version to keep (this " +
                            "also settles any page edits):",
                        fontSize = 14.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceButton(Modifier.weight(1f), "Keep mine", !working) {
                            applyNotebook(NotebookConflictResolution.KEEP_LOCAL)
                        }
                        ChoiceButton(Modifier.weight(1f), "Use server", !working) {
                            applyNotebook(NotebookConflictResolution.TAKE_SERVER)
                        }
                    }
                }

                else -> {
                    Text(
                        "${current.pageConflicts.size} page(s) were edited on both sides. " +
                            "The thumbnail shows your local version. Choose for each:",
                        fontSize = 14.sp
                    )
                    current.pageConflicts.forEach { page ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp, 64.dp)
                                        .border(1.dp, Color.Black, RectangleShape)
                                ) {
                                    PagePreview(modifier = Modifier.fillMaxSize(), pageId = page.pageId)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Page ${page.pageNumber}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChoiceButton(Modifier.weight(1f), "Keep mine", !working) {
                                    applyPage(page.pageId, PageConflictResolution.UPLOAD_DB)
                                }
                                ChoiceButton(Modifier.weight(1f), "Use server", !working) {
                                    applyPage(page.pageId, PageConflictResolution.REPLACE_WITH_SERVER)
                                }
                                ChoiceButton(Modifier.weight(1f), "Skip", !working) {
                                    applyPage(page.pageId, PageConflictResolution.SKIP)
                                }
                            }
                        }
                    }
                }
            }

            error?.let { Text("Failed: $it", fontSize = 13.sp, color = Color.Red) }
            if (working) Text("Applying…", fontSize = 13.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                ActionButton(text = "Close", onClick = { if (!working) onClose() })
            }
        }
    }
}

@Composable
private fun ChoiceButton(
    modifier: Modifier = Modifier,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(if (enabled) Color.LightGray else Color(0xFFE0E0E0), RectangleShape)
            .border(1.dp, Color.Black, RectangleShape)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}
