package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.editor.Pane
import com.ethran.notable.editor.PaneGroup
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.components.QuickNavContent
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.viewmodels.QuickNavViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("PanePagePicker")

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PanePagePickerEntryPoint {
    fun thumbnailBackfillQueue(): ThumbnailBackfillQueue
    fun snackDispatcher(): SnackDispatcher

    // Taken from Hilt rather than threaded down from EditorView: EditorViewModel keeps its
    // AppRepository private, and widening that to reach one composable is the wrong trade.
    fun appRepository(): AppRepository
}

/** Which pane a selection loads into. */
private enum class PickerTarget { ThisPane, OtherPane }

/**
 * The in-editor page picker, opened from the page counter.
 *
 * Reuses [QuickNavContent] rather than duplicating a picker, but hosts it *inside* the editor,
 * where the panes are in scope. The app-level `QuickNav` sits above the NavHost so it can be
 * reached from the library; it therefore cannot see [PaneGroup] and addresses the editor only
 * through `CanvasEventBus.active`. That is enough to reach the focused pane and not enough to aim
 * at the other one.
 *
 * Two things follow from hosting it here:
 *
 * - **Selection is a state change, not a navigation.** Every path out of this picker emits
 *   `changePage` on the target pane's bus. The app-level favourites row navigates to the editor
 *   route instead, which rebuilds the editor and takes both panes with it.
 * - **The target pane is explicit.** Defaults to the pane the counter belongs to — the active one —
 *   with an opt-in to fill the other, which is the "a previous note *beside* this one" workflow.
 *
 * Data is loaded for the *target* pane, so the breadcrumb and the scrubber describe the pane being
 * aimed at rather than the one in focus.
 */
@Composable
fun PanePagePicker(
    paneGroup: PaneGroup,
    onClose: () -> Unit,
    goToFolder: (String?) -> Unit,
) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPoints.get(context.applicationContext, PanePagePickerEntryPoint::class.java)
    }
    val appRepository = remember(entryPoint) { entryPoint.appRepository() }

    var target by remember { mutableStateOf(PickerTarget.ThisPane) }

    val activePane = paneGroup.active
    val otherPane: Pane? = paneGroup.other(activePane)

    // Falls back to the active pane when there is only one, so the single-pane case needs no
    // special handling anywhere below.
    val targetPane = if (target == PickerTarget.OtherPane && otherPane != null) otherPane
    else activePane
    val nonTargetPane = paneGroup.other(targetPane)

    val viewModel: QuickNavViewModel = viewModel(
        key = "pane-page-picker",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = QuickNavViewModel(
                appRepository = appRepository,
                thumbnailBackfillQueue = entryPoint.thumbnailBackfillQueue(),
                snackDispatcher = entryPoint.snackDispatcher(),
                // Resolved at emit time, not captured: the factory runs once, but the target moves
                // when the user flips the selector or focus changes while the picker is open.
                targetBus = { resolveTargetBus(paneGroup, target) },
            ) as T
        }
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Reload when the aimed-at pane changes, or when its page does.
    val targetPageId = targetPane.pageId
    val exclusion = QuickNavViewModel.PaneExclusion(
        notebookId = nonTargetPane?.notebookId,
        pageId = nonTargetPane?.pageId,
    )
    LaunchedEffect(targetPageId, exclusion) {
        log.d("Picker aimed at page $targetPageId, excluding $exclusion")
        viewModel.loadPageData(targetPageId, exclusion)
    }

    QuickNavContent(
        appRepository = appRepository,
        uiState = uiState,
        onClose = onClose,
        // The breadcrumb genuinely leaves the editor for the library — that is what tapping a
        // folder asks for. Close first so the picker is not left floating over the destination.
        // Making it merely close would be a control that silently does nothing.
        onNavigateBreadcrumb = { folderId ->
            onClose()
            goToFolder(folderId)
        },
        onToggleFavorite = viewModel::toggleFavorite,
        onGenerateBookPreviews = viewModel::generateThumbnailsForCurrentBook,
        onScrubStart = viewModel::onScrubStart,
        onScrubPreview = viewModel::onScrubPreview,
        onScrubEnd = { index ->
            viewModel.onScrubEnd(index)
            onClose()
        },
        // The "return to source" affordance belongs to the app-level QuickNav's jump tracking,
        // which is navigation state this picker does not participate in. Hidden rather than
        // wired to nothing.
        onReturnClick = {},
        showReturn = false,
        goToPage = { pageId ->
            viewModel.onPageSelected(pageId)
            onClose()
        },
        header = if (otherPane != null) {
            {
                PaneTargetSelector(
                    selected = target,
                    onSelect = { target = it },
                )
            }
        } else null,
    )
}

/** The bus for the pane [target] names, resolved against the current focus. */
private fun resolveTargetBus(paneGroup: PaneGroup, target: PickerTarget) =
    when (target) {
        PickerTarget.ThisPane -> paneGroup.active
        PickerTarget.OtherPane -> paneGroup.other(paneGroup.active) ?: paneGroup.active
    }.events

@Composable
private fun PaneTargetSelector(
    selected: PickerTarget,
    onSelect: (PickerTarget) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.page_picker_target),
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(end = 10.dp)
        )
        TargetChip(
            label = stringResource(R.string.page_picker_this_pane),
            isSelected = selected == PickerTarget.ThisPane,
            onSelect = { onSelect(PickerTarget.ThisPane) },
        )
        Spacer(Modifier.width(6.dp))
        TargetChip(
            label = stringResource(R.string.page_picker_other_pane),
            isSelected = selected == PickerTarget.OtherPane,
            onSelect = { onSelect(PickerTarget.OtherPane) },
        )
    }
}

/**
 * Selection is shown by inverting fill rather than by colour: on e-ink there is no colour to spend,
 * and a border alone reads as a button rather than as the chosen one of two.
 */
@Composable
private fun TargetChip(
    label: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Text(
        text = label,
        color = if (isSelected) Color.White else Color.Black,
        modifier = Modifier
            .border(1.dp, Color.Black, RectangleShape)
            .background(if (isSelected) Color.Black else Color.White)
            .noRippleClickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 2.dp)
    )
}
