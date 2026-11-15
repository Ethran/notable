package com.ethran.notable.io

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.model.PageMode
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.events.DefaultAppEventBus
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.utils.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class XoppImportTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var importEngine: ImportEngine
    private val screenWidth = SCREEN_WIDTH

    @Before
    fun setUp() {
        // XoppFile scales the A4 page to SCREEN_WIDTH, read when it is constructed below. Outside
        // the app it is 0 off an Onyx device, and the device's own width on one.
        SCREEN_WIDTH = TEST_SCREEN_WIDTH
        db = TestDatabaseFactory.createInMemory(context)
        val pageRepo = PageRepository(db.pageDao())
        val bookRepo = BookRepository(db.notebookDao(), db.pageDao())
        val appEventBus = DefaultAppEventBus()
        importEngine = ImportEngine(
            context, pageRepo, bookRepo, StrokeRepository(db.strokeDao()),
            ImageRepository(db.ImageDao()), appEventBus
        ).apply { xoppFile = XoppFile(context, pageRepo, bookRepo, appEventBus) }
    }

    @After
    fun tearDown() {
        db.close()
        SCREEN_WIDTH = screenWidth
    }

    /**
     * A real notebook, with a page as long as a long lecture's, is imported whole: its pages in
     * order, each with its images and the x, y of every stroke's points. The import streams it
     * rather than holding it in memory.
     */
    @Test(timeout = 600_000)
    fun notebook_isImportedWhole_withoutBeingHeldInMemory() = runBlocking {
        val pages = fixture().use { readPages(it) }
        val file = copyFixture("Fixture.xopp")

        val heapBefore = liveHeap()
        var peakHeap = heapBefore
        val start = System.currentTimeMillis()
        val pageIds = whileSampling({ peakHeap = maxOf(peakHeap, liveHeap()) }) {
            import(file).successData()
        }
        val importGrowth = peakHeap - heapBefore

        val stored = pageIds.map { db.pageDao().getPageWithDataById(it)!! }
        val notebookSize = liveHeap() - heapBefore
        Log.i(TAG, "Imported in ${System.currentTimeMillis() - start} ms; live heap grew " +
            "${importGrowth / MIB} MiB at most, holding the notebook takes ${notebookSize / MIB} MiB")

        val book = db.notebookDao().getAll().single()
        assertEquals("Fixture", book.title)
        assertEquals(pageIds, book.pageIds)
        assertEquals(pages.size, stored.size)
        stored.zip(pages).forEach { (page, expected) ->
            assertEquals(book.id, page.page.notebookId)
            assertEquals(expected.images, page.images.size)
            assertSameStrokes(
                // The importer skips a stroke without points.
                expected = expected.strokes.filter { it.isNotEmpty() },
                imported = page.strokes.map { s ->
                    FloatArray(s.points.size * 2) { i -> s.points[i / 2].let { if (i % 2 == 0) it.x else it.y } }
                },
            )
        }
        assertTrue("The fixture should have strokes", pages.any { it.strokes.isNotEmpty() })
        assertTrue("The fixture should have an image", pages.any { it.images > 0 })
        assertTrue(
            "Import took ${importGrowth / MIB} MiB, as if it held much of the notebook " +
                "(${notebookSize / MIB} MiB)",
            importGrowth < notebookSize / 2
        )
    }

    @Test(timeout = 60_000)
    fun titleGivenInOptions_isUsed_andTheTypeStillComesFromTheFileName() = runBlocking {
        // A file: URI has no MIME type, so only the file name says this is a xopp file.
        import(firstPageOfFixture("FirstPage.xopp"), ImportOptions(bookTitle = "ManualTitle"))
            .successData()

        assertEquals("ManualTitle", db.notebookDao().getAll().single().title)
    }

    /** The title is the file name without its extension, then cleaned of what a title can't hold. */
    @Test(timeout = 60_000)
    fun titleFromTheFileName_losesItsExtensionBeforeBeingCleaned() = runBlocking {
        val long = "a".repeat(98)
        for (name in listOf("Lecture (2).XOPP", "$long.xopp")) {
            assertEquals(1, import(firstPageOfFixture(name)).successData().size)
        }

        assertEquals(listOf("Lecture 2", long), db.notebookDao().getAll().map { it.title }.sorted())
    }

    /** The same strokes, in any order, every point within the 0.01 px the database keeps. */
    private fun assertSameStrokes(expected: List<FloatArray>, imported: List<FloatArray>) {
        assertEquals(expected.size, imported.size)
        // Pairs the strokes up by their points as the database rounds them.
        val byRoundedPoints = Comparator<FloatArray> { a, b ->
            a.indices.asSequence().take(b.size)
                .map { (a[it] * 100).roundToInt().compareTo((b[it] * 100).roundToInt()) }
                .firstOrNull { it != 0 } ?: a.size.compareTo(b.size)
        }
        expected.sortedWith(byRoundedPoints).zip(imported.sortedWith(byRoundedPoints)).forEach { (e, i) ->
            assertEquals(e.size, i.size)
            e.indices.forEach {
                assertTrue("Expected ${e[it]}, imported ${i[it]}", abs(e[it] - i[it]) <= 0.01f)
            }
        }
    }

    /** Memory taken by reachable objects: the used heap right after a collection. */
    private fun liveHeap(): Long {
        val runtime = Runtime.getRuntime()
        runtime.gc()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /**
     * Runs [block] while calling [sample] each second on another thread. Not more often: a forced
     * collection can take a few hundred milliseconds on a slow emulator.
     */
    private suspend fun <T> whileSampling(sample: () -> Unit, block: suspend () -> T): T =
        coroutineScope {
            val sampler = launch(Dispatchers.Default) {
                while (isActive) {
                    sample()
                    delay(1000)
                }
            }
            try {
                block()
            } finally {
                sampler.cancelAndJoin()
                sample()
            }
        }

    private suspend fun import(file: File, options: ImportOptions = ImportOptions()) =
        importEngine.import(Uri.fromFile(file), options)

    private fun <T> AppResult<T, *>.successData(): T {
        assertTrue("Import failed: $this", this is AppResult.Success)
        return (this as AppResult.Success).data
    }

    private fun fixture(): InputStream =
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE)

    /** Copies the fixture as it is to the cache, to import it from a file. */
    private fun copyFixture(name: String): File {
        val file = File(context.cacheDir, name)
        fixture().use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /** The fixture up to the end of its first page, which has strokes and an image. */
    private fun firstPageOfFixture(name: String): File {
        val file = File(context.cacheDir, name)
        GZIPOutputStream(file.outputStream()).bufferedWriter().use { out ->
            GZIPInputStream(fixture()).bufferedReader().useLines { lines ->
                for (line in lines) {
                    out.write(line)
                    out.write("\n")
                    if (line.trim() == "</page>") break
                }
            }
            out.write("</xournal>\n")
        }
        return file
    }

    /** What a `<page>` of a xopp file holds, read independently of the importer. */
    private class XoppPage(val strokes: MutableList<FloatArray> = mutableListOf(), var images: Int = 0)

    /**
     * The pages of the gzipped xopp file in [input], each stroke's x, y pairs scaled from the A4
     * page to the test's screen width.
     */
    private fun readPages(input: InputStream): List<XoppPage> {
        val scale = TEST_SCREEN_WIDTH.toFloat() / PageMode.A4.width
        val parser = Xml.newPullParser().apply { setInput(GZIPInputStream(input), "UTF-8") }
        val pages = mutableListOf<XoppPage>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "page" -> pages += XoppPage()
                "image" -> pages.last().images++
                "stroke" -> pages.last().strokes += parser.nextText()
                    .split(' ', '\n', '\t', '\r').filter { it.isNotEmpty() }
                    .map { it.toFloat() * scale }.toFloatArray()
            }
        }
        return pages
    }

    private companion object {
        const val TAG = "XoppImportTest"
        const val FIXTURE = "test_notebook.xopp"
        const val MIB = 1024L * 1024
    }
}

private const val TEST_SCREEN_WIDTH = 1200
