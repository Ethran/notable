package com.ethran.notable.data.db

import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

class AppSettingsUpdateTest {

    private val stored = mutableListOf<AppSettings>()
    private val writer = LatestValueWriter({ GlobalAppSettings.current }) { stored += it }

    @Before
    fun reset() {
        GlobalAppSettings.update { AppSettings(version = 1) }
    }

    private suspend fun loadDefaults() =
        writer.load(load = { AppSettings(version = 1) }, publish = { GlobalAppSettings.update { _ -> it } })

    @Test
    fun concurrentFieldUpdates_noneIsLost() {
        val threads = 8
        val perThread = 100
        val start = CyclicBarrier(threads)
        List(threads) { t ->
            thread {
                start.await()
                repeat(perThread) { i ->
                    GlobalAppSettings.update { it.copy(quickNavPages = it.quickNavPages + "$t-$i") }
                }
            }
        }.forEach { it.join() }

        assertEquals(threads * perThread, GlobalAppSettings.current.quickNavPages.toSet().size)
    }

    @Test
    fun update_isVisibleBeforeAnythingIsStored() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gatedWrites = mutableListOf<AppSettings>()
        val gated = LatestValueWriter({ GlobalAppSettings.current }) {
            entered.complete(Unit)
            release.await()
            gatedWrites += it
        }
        gated.load(load = { AppSettings(version = 1) }, publish = {})

        GlobalAppSettings.update { it.copy(debugMode = true) }
        val saving = async { gated.persist() }
        entered.await()

        assertTrue(GlobalAppSettings.current.debugMode)
        assertTrue(gatedWrites.isEmpty())
        release.complete(Unit)
        saving.await()
        assertEquals(listOf(GlobalAppSettings.current), gatedWrites)
    }

    @Test
    fun persist_alwaysEndsWithTheLatestValue() = runBlocking {
        loadDefaults()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val written = mutableListOf<AppSettings>()
        val gated = LatestValueWriter({ GlobalAppSettings.current }) { value ->
            written += value
            if (written.size == 1) {
                entered.complete(Unit)
                release.await()
            }
        }
        gated.load(load = { AppSettings(version = 1) }, publish = {})
        GlobalAppSettings.update { it.copy(debugMode = true) }
        val first = async { gated.persist() }
        entered.await()
        GlobalAppSettings.update { it.copy(simpleRendering = true) }
        val second = async { gated.persist() }
        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(GlobalAppSettings.current, written.last())
        assertTrue(written.last().debugMode && written.last().simpleRendering)
    }

    @Test
    fun concurrentUpdatesFromDifferentCallers_bothPersisted() {
        runBlocking { loadDefaults() }
        val start = CyclicBarrier(2)
        listOf<(AppSettings) -> AppSettings>(
            { it.copy(debugMode = true) },
            { it.copy(simpleRendering = true) },
        ).map { transform ->
            thread {
                start.await()
                GlobalAppSettings.update(transform)
                runBlocking { writer.persist() }
            }
        }.forEach { it.join() }

        assertTrue(stored.last().debugMode)
        assertTrue(stored.last().simpleRendering)
    }

    @Test
    fun persist_doesNothingBeforeLoad() = runBlocking {
        GlobalAppSettings.update { it.copy(debugMode = true) }
        writer.persist()

        assertTrue(stored.isEmpty())
    }

    @Test
    fun load_publishesAndMarksSavedTogether_soUnchangedValueIsNotRewritten() = runBlocking {
        val loaded = AppSettings(version = 1, debugMode = true)
        writer.load(load = { loaded }, publish = { GlobalAppSettings.update { _ -> it } })

        assertEquals(loaded, GlobalAppSettings.current)
        writer.persist()
        assertTrue(stored.isEmpty())

        GlobalAppSettings.update { it.copy(simpleRendering = true) }
        writer.persist()
        assertEquals(1, stored.size)
    }

    @Test
    fun persist_completesWhenTheCallerIsCancelled() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val written = mutableListOf<AppSettings>()
        val gated = LatestValueWriter({ GlobalAppSettings.current }) { value ->
            entered.complete(Unit)
            release.await()
            written += value
        }
        gated.load(load = { AppSettings(version = 1) }, publish = {})
        GlobalAppSettings.update { it.copy(debugMode = true) }
        val job = launch { gated.persist() }
        entered.await()

        release.complete(Unit)
        job.cancelAndJoin()

        assertEquals(listOf(GlobalAppSettings.current), written)
    }

    @Test
    fun toolbarEdits_composeOnTheLatestSettings() {
        val extra = ToolbarPen.DEFAULT_PENS.first().copy(id = "extra")
        val resized = ToolbarPen.DEFAULT_PENS.first().id

        GlobalAppSettings.update { s -> s.withToolbarEdit { l, p -> l to p + extra } }
        GlobalAppSettings.update { s ->
            s.copy(toolbarPens = s.toolbarPens.map { if (it.id == resized) it.copy(size = 99f) else it })
        }
        GlobalAppSettings.update { s -> s.withToolbarEdit { l, p -> l to p.filterNot { it.id == "gone" } } }

        val pens = GlobalAppSettings.current.toolbarPens
        assertTrue(pens.any { it.id == "extra" })
        assertEquals(99f, pens.first { it.id == resized }.size, 0f)
    }

    @Test
    fun persist_retriesAfterAWriteFails() = runBlocking {
        var failNext = true
        val errors = mutableListOf<Exception>()
        val flaky = LatestValueWriter({ GlobalAppSettings.current }, errors::add) { value ->
            if (failNext) {
                failNext = false
                throw IllegalStateException("disk")
            }
            stored += value
        }
        flaky.load(load = { AppSettings(version = 1) }, publish = {})
        GlobalAppSettings.update { it.copy(debugMode = true) }

        flaky.persist()
        assertEquals(1, errors.size)
        assertTrue(stored.isEmpty())

        flaky.persist()
        assertEquals(listOf(GlobalAppSettings.current), stored)
    }

    @Test
    fun failedLoad_leavesSavingDisabledUntilALoadSucceeds() = runBlocking {
        runCatching { writer.load<AppSettings>(load = { error("no storage") }, publish = {}) }
        GlobalAppSettings.update { it.copy(debugMode = true) }
        writer.persist()
        assertTrue(stored.isEmpty())

        loadDefaults()
        GlobalAppSettings.update { it.copy(simpleRendering = true) }
        writer.persist()
        assertEquals(1, stored.size)
    }
}
