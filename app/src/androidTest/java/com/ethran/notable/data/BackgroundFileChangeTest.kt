package com.ethran.notable.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.PageLeases
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.testing.PageDataTestHarness
import com.ethran.notable.testing.waitForCondition
import com.ethran.notable.ui.SnackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A page's background follows its file: once the file is there, and after the file changes. */
@RunWith(AndroidJUnit4::class)
class BackgroundFileChangeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var data: PageDataTestHarness
    private val pageDataManager get() = data.pageDataManager
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = File(app.cacheDir, "background-file-change-test")
    private val screen = SCREEN_WIDTH to SCREEN_HEIGHT
    private val pageIds = mutableListOf<String>()

    @Before
    fun setUp() {
        // Off an Onyx device the screen size is unknown, and PDF pages are rendered to its width.
        SCREEN_WIDTH = 1200
        SCREEN_HEIGHT = 1600
        dir.deleteRecursively()
        dir.mkdirs()
        data = PageDataTestHarness(app)
    }

    @After
    fun tearDown() {
        viewScope.cancel()
        // A background cached arms its file's watch in the background, reading the page record.
        runBlocking { delay(500) }
        pageIds.forEach { data.backgroundFileWatcher.unwatch(it) }
        data.close()
        dir.deleteRecursively()
        SCREEN_WIDTH = screen.first
        SCREEN_HEIGHT = screen.second
    }

    @Test(timeout = 30000)
    fun backgroundWhoseFileIsMissing_isShownOnceTheFileIsThere() = runBlocking {
        val image = File(dir, "background.png")
        val pageId = createPage(notebookId = null, image, BackgroundType.Image)

        val view = PageView(
            context = app,
            coroutineScope = viewScope,
            pageDataManager = pageDataManager,
            pageLeases = PageLeases(pageDataManager),
            appRepository = data.appRepository,
            initialPageId = pageId,
            viewWidth = 400,
            viewHeight = 600,
            snackManager = SnackState(),
            onPageShown = {},
        )
        assertTrue(waitForCondition(5000) { pageDataManager.validatePageDataLoaded(pageId) })
        assertNull(view.getOrLoadBackground(pageId, image.path, -1, 1f))

        image.outputStream().use { solid(Color.BLUE).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shown = view.getOrLoadBackground(pageId, image.path, -1, 1f)
        view.disposeOldPage()
        assertNotNull("background drawn once its file is there", shown)
        assertEquals(Color.BLUE, shown!!.center())
    }

    @Test(timeout = 30000)
    fun changedBackgroundFile_isShownOnEveryPageUsingIt() = runBlocking {
        val pdf = File(dir, "background.pdf")
        writePdf(pdf, Color.RED)
        // Only pages of a notebook following a PDF are watched; the other page uses the same PDF
        // page without being watched, and is the one on screen.
        val watched = createPage(followingNotebook(pdf), pdf, BackgroundType.Pdf(0))
        val alsoUsing = createPage(notebookId = null, pdf, BackgroundType.Pdf(0))
        for (pageId in listOf(watched, alsoUsing)) {
            pageDataManager.requestPageLoadJoin(pageId)
            assertEquals(Color.RED, pageDataManager.getBackground(pageId).bitmap?.center())
        }
        pageDataManager.hold(alsoUsing)
        val repainted = AtomicBoolean(false)
        val repaints = viewScope.launch(start = CoroutineStart.UNDISPATCHED) {
            CanvasEventBus.forceUpdate.collect { if (it == null) repainted.set(true) }
        }

        // Rewritten until the watcher, armed in the background, reports the change.
        assertTrue("change noticed", waitForCondition(10000) {
            writePdf(pdf, Color.BLUE)
            waitForCondition(500) { pageDataManager.getBackground(watched).bitmap == null }
        })
        assertTrue("page on screen repainted", waitForCondition(2000) { repainted.get() })
        repaints.cancel()
        pageDataManager.release(alsoUsing)

        for (pageId in listOf(watched, alsoUsing)) {
            pageDataManager.requestPageLoadJoin(pageId)
            assertEquals(
                "background of $pageId",
                Color.BLUE,
                pageDataManager.getBackground(pageId).bitmap?.center(),
            )
        }
    }

    @Test(timeout = 30000)
    fun backgroundFileThatFailsToRender_isShownOnceFixed() = runBlocking {
        val pdf = File(dir, "background.pdf")
        pdf.writeText("not a pdf")
        val pageId = createPage(followingNotebook(pdf), pdf, BackgroundType.Pdf(0))
        pageDataManager.requestPageLoadJoin(pageId)
        assertNull(pageDataManager.getBackground(pageId).bitmap)

        // Rewritten until the watcher, armed in the background, reports the change.
        assertTrue("fixed file shown", waitForCondition(10000) {
            writePdf(pdf, Color.BLUE)
            waitForCondition(500) { shows(pageId, Color.BLUE) }
        })
    }

    @Test(timeout = 30000)
    fun backgroundFileReplacedByMovingTheOldOneAway_isStillFollowed() = runBlocking {
        val pdf = File(dir, "background.pdf")
        writePdf(pdf, Color.RED)
        val pageId = createPage(followingNotebook(pdf), pdf, BackgroundType.Pdf(0))
        assertTrue(shows(pageId, Color.RED))

        // As an editor that saves by moving the old file aside and writing a new one.
        assertTrue("replacement shown", waitForCondition(10000) {
            pdf.renameTo(File(dir, "background.pdf.old"))
            writePdf(pdf, Color.BLUE)
            waitForCondition(1000) { shows(pageId, Color.BLUE) }
        })
        // The new file is watched, not the old one moved away.
        assertTrue("later change shown", waitForCondition(10000) {
            writePdf(pdf, Color.GREEN)
            waitForCondition(500) { shows(pageId, Color.GREEN) }
        })
    }

    /** Creates a notebook following [pdf]: the backgrounds of its pages are watched. */
    private suspend fun followingNotebook(pdf: File): String {
        val notebook = Notebook(
            defaultBackground = pdf.path,
            defaultBackgroundType = BackgroundType.AutoPdf.key,
        )
        data.db.notebookDao().create(notebook)
        return notebook.id
    }

    /** Whether [pageId], loaded, shows a background of [color]. */
    private fun shows(pageId: String, color: Int): Boolean = runBlocking {
        pageDataManager.requestPageLoadJoin(pageId)
        pageDataManager.getBackground(pageId).bitmap?.center() == color
    }

    private suspend fun createPage(notebookId: String?, file: File, type: BackgroundType): String {
        val page = Page(notebookId = notebookId, background = file.path, backgroundType = type.key)
        data.db.pageDao().create(page)
        pageIds += page.id
        return page.id
    }

    private fun solid(color: Int): Bitmap = createBitmap(100, 100).apply { eraseColor(color) }

    private fun writePdf(file: File, color: Int) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(200, 280, 1).create())
            page.canvas.drawColor(color)
            document.finishPage(page)
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    // Exact for a solid image; a rendered PDF page may be a shade off, so it is rounded to the
    // primary colour it is closest to.
    private fun Bitmap.center(): Int {
        val pixel = getPixel(width / 2, height / 2)
        fun channel(value: Int) = if (value > 127) 255 else 0
        return Color.rgb(channel(Color.red(pixel)), channel(Color.green(pixel)), channel(Color.blue(pixel)))
    }
}
