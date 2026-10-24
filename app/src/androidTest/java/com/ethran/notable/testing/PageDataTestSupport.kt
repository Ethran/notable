package com.ethran.notable.testing

import android.content.Context
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.BackgroundFileWatcher
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.PageSaver
import com.ethran.notable.data.PageViewportState
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.data.db.NotebookSyncStateRepository
import com.ethran.notable.data.db.PageDao
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.PageWithData
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokeDao
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.events.DefaultAppEventBus
import com.ethran.notable.editor.utils.Pen
import java.util.UUID

/** Real repositories over [db]; the page and stroke repositories use [pageDao] and [strokeDao]. */
internal fun createAppRepositoryForTest(
    context: Context,
    db: AppDatabase,
    pageDao: PageDao = db.pageDao(),
    strokeDao: StrokeDao = db.strokeDao(),
): AppRepository {
    val kvRepository = KvRepository(db.kvDao(), context)
    return AppRepository(
        bookRepository = BookRepository(db.notebookDao(), db.pageDao()),
        pageRepository = PageRepository(pageDao),
        strokeRepository = StrokeRepository(strokeDao),
        imageRepository = ImageRepository(db.ImageDao()),
        folderRepository = FolderRepository(db.folderDao()),
        notebookSyncStateRepository = NotebookSyncStateRepository(db.notebookSyncStateDao()),
        pageSyncStateRepository = PageSyncStateRepository(db.pageSyncStateDao()),
        kvProxy = KvProxy(kvRepository, CryptoHelper()),
        db = db,
    )
}

/**
 * The page data layer as the app wires it — page cache, saver and repositories — over an in-memory
 * database.
 *
 * [pageSaver] is created on first use. It starts saving on its own a second after an edit, so a
 * test that hands edits around itself ([PageDataManager.takeUnsaved]) must not touch it.
 *
 * [beforePageRead], [afterPageRead] and [beforeStrokeWrite] let a test act at a chosen point of a
 * load or a save.
 */
internal class PageDataTestHarness(context: Context) {
    val db: AppDatabase = TestDatabaseFactory.createInMemory(context)

    /** Runs when a page load is about to read the page from the database. */
    @Volatile
    var beforePageRead: suspend (pageId: String) -> Unit = {}

    /** Runs when a page load has read the page from the database, before it uses the rows. */
    @Volatile
    var afterPageRead: suspend (pageId: String) -> Unit = {}

    /** Runs before strokes are written to the database. */
    @Volatile
    var beforeStrokeWrite: suspend () -> Unit = {}

    private val pageDao = object : PageDao by db.pageDao() {
        override suspend fun getPageWithDataById(pageId: String): PageWithData? {
            beforePageRead(pageId)
            return db.pageDao().getPageWithDataById(pageId).also { afterPageRead(pageId) }
        }
    }
    private val strokeDao = object : StrokeDao by db.strokeDao() {
        override suspend fun upsert(strokes: List<Stroke>) {
            beforeStrokeWrite()
            db.strokeDao().upsert(strokes)
        }
    }
    val appRepository = createAppRepositoryForTest(context, db, pageDao, strokeDao)
    val appEventBus = DefaultAppEventBus()
    val pageDataManager = PageDataManager(
        appRepository = appRepository,
        appEventBus = appEventBus,
        backgroundFileWatcher = BackgroundFileWatcher(appEventBus),
        viewport = PageViewportState(),
    )
    val pageSaver by lazy { PageSaver(pageDataManager, appRepository, db, appEventBus) }

    /** The page's strokes as stored, without the pressure normalization a load applies. */
    suspend fun storedStrokes(pageId: String): List<Stroke> =
        db.pageDao().getPageWithDataById(pageId)?.strokes.orEmpty()

    suspend fun storedImages(pageId: String): List<Image> =
        db.pageDao().getPageWithDataById(pageId)?.images.orEmpty()

    fun close() = db.close()
}

/** What identifies a stroke's stored state: its id, page and geometry. */
internal data class StrokeRow(
    val id: String, val pageId: String,
    val top: Float, val bottom: Float, val left: Float, val right: Float,
    val pointCount: Int,
)

internal fun Stroke.row() = StrokeRow(id, pageId, top, bottom, left, right, points.size)

internal fun List<Stroke>.rows(): Set<StrokeRow> = mapTo(HashSet()) { it.row() }

internal data class ImageRow(
    val id: String, val pageId: String, val x: Int, val y: Int, val width: Int, val height: Int,
)

internal fun List<Image>.imageRows(): Set<ImageRow> =
    mapTo(HashSet()) { ImageRow(it.id, it.pageId, it.x, it.y, it.width, it.height) }

/** A small stroke at ([x], [y]), with pressure already normalized as in-memory strokes are. */
internal fun testStroke(pageId: String, x: Float = 100f, y: Float = 100f): Stroke {
    val points = (0 until 5).map { i ->
        StrokePoint(x = x + i * 10f, y = y + i * 2f, pressure = 0.5f)
    }
    return Stroke(
        id = UUID.randomUUID().toString(),
        size = 5f,
        pen = Pen.BALLPEN,
        maxPressure = 1,
        top = points.minOf { it.y },
        bottom = points.maxOf { it.y },
        left = points.minOf { it.x },
        right = points.maxOf { it.x },
        points = points,
        pageId = pageId,
    )
}

/** This stroke moved down by [dy], under the same id, as a selection move or page cut does. */
internal fun Stroke.movedDown(dy: Float): Stroke = copy(
    points = points.map { it.copy(y = it.y + dy) },
    top = top + dy,
    bottom = bottom + dy,
)

internal fun testImage(pageId: String, x: Int = 50, y: Int = 50): Image =
    Image(x = x, y = y, width = 200, height = 100, uri = "file:///test.png", pageId = pageId)
