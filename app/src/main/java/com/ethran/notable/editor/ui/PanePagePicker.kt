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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
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

/**
 * Which pane a selection loads into.
 *
 * [OtherPane] means an existing second pane; [NewPane] means one that does not exist yet, so
 * choosing a page creates the split. They are distinct because the destination differs — a bus
 * emit versus creating the pane — not merely the label.
 */
private enum class PickerTarget { ThisPane, OtherPane, NewPane }

/**
 * The in-editor page picker, opened from the page counter.
 *
 * Reuses [QuickNavContent] rather than duplicating a picker, but hosts it *inside* the editor,
 * where the panes are in scope. The app-level `QuickNav` sits above the NavHost so it can be
 * reached from the library; it therefore cannot see [PaneGroup] and addresses the editor only
 * through `PaneRegistry.focused`. That is enough to reach the focused pane and not enough to aim
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
    /** Opened from the split control: default to aiming at a pane that does not exist yet. */
    forNewPane: Boolean = false,
    onCreatePane: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPoints.get(context.applicationContext, PanePagePickerEntryPoint::class.java)
    }
    val appRepository = remember(entryPoint) { entryPoint.appRepository() }
    val scope = rememberCoroutineScope()

    val activePane = paneGroup.active
    val otherPane: Pane? = paneGroup.other(activePane)

    // The second option offered: an existing pane if there is one, otherwise creating it.
    val secondOption = if (otherPane != null) PickerTarget.OtherPane else PickerTarget.NewPane

    var target by remember {
        mutableStateOf(if (forNewPane) PickerTarget.NewPane else PickerTarget.ThisPane)
    }

    // Null for NewPane — there is no pane to aim at yet, which is what suppresses the scrubber.
    val targetPane: Pane? = when (target) {
        PickerTarget.ThisPane -> activePane
        PickerTarget.OtherPane -> otherPane
        PickerTarget.NewPane -> null
    }

    // What the target may not open. For a new pane that is the active pane's notebook, since the
    // new one must be a different notebook; for an existing target it is the pane opposite it.
    val nonTargetPane = targetPane?.let { paneGroup.other(it) } ?: activePane

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

    // Re-point the target each composition. The ViewModel is scoped to the nav entry and survives
    // the sheet closing, while `target` above is discarded with it — so the provider passed to the
    // factory (which runs once) would keep reading a dead session's selector after a reopen.
    SideEffect {
        viewModel.targetBus = { resolveTargetBus(paneGroup, target) }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Reload when the aimed-at pane changes, or when its page does. For a new pane there is no
    // target page, so the active pane's supplies the breadcrumb context.
    val contextPageId = targetPane?.pageId ?: activePane.pageId
    // Only the page id: the ViewModel resolves its notebook from the record, because
    // `Pane.notebookId` is null until that pane's own load finishes.
    val excludePageId = nonTargetPane?.pageId
    LaunchedEffect(contextPageId, excludePageId) {
        log.d("Picker aimed at $target (page $contextPageId), excluding page $excludePageId")
        viewModel.loadPageData(contextPageId, excludePageId)
    }


    // Every selection path — favourite page, notebook, scrubber — funnels through here, so a new
    // pane is created rather than an existing one repointed regardless of which control was used.
    fun selectPage(pageId: String) {
        if (target == PickerTarget.NewPane) onCreatePane(pageId)
        else viewModel.onPageSelected(pageId)
        onClose()
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
        // The scrubber walks the *target pane's own* notebook. A pane that does not exist yet has
        // no notebook, and the only one in scope — the active pane's — is precisely the notebook
        // the new pane may not open, so every position on it would be an illegal choice.
        showScrubber = targetPane != null,
        goToPage = { pageId -> selectPage(pageId) },
        // A created document opens in the target pane just as a chosen one does, so splitting
        // straight into a fresh page is one action rather than create-then-find.
        onCreateQuickPage = {
            scope.launch {
                viewModel.createQuickPage(uiState.folderId)?.let { selectPage(it) }
                    ?: log.e("Could not create a quick page")
            }
        },
        onCreateNotebook = {
            scope.launch {
                viewModel.createNotebook(uiState.folderId)?.let { selectPage(it) }
                    ?: log.e("Could not create a notebook")
            }
        },
        notebooks = uiState.notebooks,
        // Opening a notebook lands on the page it was last left at, so returning to a document
        // resumes where you were rather than at page one.
        onSelectNotebook = { notebook ->
            val pageId = notebook.openPageId?.takeIf { it in notebook.pageIds }
                ?: notebook.pageIds.firstOrNull()
            if (pageId == null) log.w("Notebook ${notebook.title} has no pages to open")
            else selectPage(pageId)
        },
        header = {
            PaneTargetSelector(
                selected = target,
                secondOption = secondOption,
                onSelect = { target = it },
            )
        },
    )
}

/**
 * The bus for the pane [target] names, resolved against the current focus.
 *
 * [PickerTarget.NewPane] has no bus — a selection there creates a pane instead of emitting to one —
 * so it falls back to the active pane's rather than returning null for a case that cannot occur.
 */
private fun resolveTargetBus(paneGroup: PaneGroup, target: PickerTarget) =
    when (target) {
        PickerTarget.ThisPane, PickerTarget.NewPane -> paneGroup.active
        PickerTarget.OtherPane -> paneGroup.other(paneGroup.active) ?: paneGroup.active
    }.events

@Composable
private fun PaneTargetSelector(
    selected: PickerTarget,
    secondOption: PickerTarget,
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
            label = stringResource(
                if (secondOption == PickerTarget.NewPane) R.string.page_picker_new_pane
                else R.string.page_picker_other_pane
            ),
            isSelected = selected == secondOption,
            onSelect = { onSelect(secondOption) },
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
