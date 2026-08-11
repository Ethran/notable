package com.ethran.notable.editor

import com.ethran.notable.editor.canvas.PaneEventBus

/**
 * The panes currently on screen, for code that has no pane in hand but must reach one.
 *
 * ### Why this replaced `CanvasEventBus.active`
 *
 * That was a single mutable pointer meaning "the pane I mean", and callers meant three different
 * things by it:
 *
 * - *every* pane — the resume redraw, which with two panes refreshed only one of them
 * - the pane showing a *particular page* — a background file changing on disk, which refreshed
 *   whichever pane happened to be focused instead
 * - the pane in *focus* — the only one it actually described
 *
 * One name cannot be all three, so two of the three were quietly wrong. Worse, the pointer was
 * assigned in `PageView.init`, which made it mean "last constructed" rather than "focused" until
 * that was fixed (STATE-PLAN §2.1).
 *
 * Each accessor here names one of those intents, so the call site says which it means and can be
 * read against what it does.
 *
 * ### Why panes rather than a snapshot
 *
 * [showing] reads each pane's *current* page. Recording page ids when the registry is published
 * would go stale the moment a pane changed page — which is the same staleness this work has spent
 * its time removing.
 *
 * Global because its callers are: the activity's lifecycle callbacks, the background-file watcher,
 * and dialogs, none of which can be handed a pane. Code that *does* hold one addresses
 * `pane.events` directly and should never come here.
 */
object PaneRegistry {

    /** Emitting into this is a no-op, so callers need no null handling before an editor exists. */
    private val detached = PaneEventBus()

    @Volatile
    private var panes: List<Pane> = emptyList()

    @Volatile
    private var focusedPane: Pane? = null

    /** Called by [PaneGroup] when the pane set or the focus changes. */
    fun publish(panes: List<Pane>, focused: Pane) {
        this.panes = panes
        this.focusedPane = focused
    }

    /** Called when the editor goes away, so nothing addresses panes that are gone. */
    fun clear() {
        panes = emptyList()
        focusedPane = null
    }

    /**
     * The pane the user is looking at.
     *
     * For things that follow attention — a modal acting on "this page", a toolbar refresh. If what
     * you mean is a specific document, use [showing]; focus may have moved since.
     */
    val focused: PaneEventBus
        get() = focusedPane?.events ?: detached

    /**
     * Every pane on screen.
     *
     * For things that are true of the display rather than of a document: waking from sleep,
     * regaining window focus, re-arming after the panel was cleared.
     */
    val all: List<PaneEventBus>
        get() = panes.map { it.events }

    /**
     * The panes showing [pageId] — for changes that belong to a *document*, wherever it is on
     * screen.
     *
     * Normally one, since panes must show distinct pages, and empty when the page is not open at
     * all, which is the common case for a background file changing on disk.
     */
    fun showing(pageId: String): List<PaneEventBus> =
        panes.filter { it.pageId == pageId }.map { it.events }
}
