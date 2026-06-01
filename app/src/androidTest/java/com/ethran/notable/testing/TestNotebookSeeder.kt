package com.ethran.notable.testing

import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import java.util.UUID
import kotlin.random.Random

data class SeededNotebook(
    val notebookId: String,
    val pageIds: List<String>,
)

/**
 * Simple, deterministic data seeder for tests.
 *
 * Goal: In tests, work with data-rich pages (strokes) instead of blank pages.
 *
 * Note: This is not a "pretty handwriting" generator – it's enough that the pages have
 * non-empty strokes and sensible bounding boxes so that PageDataManager/Repositories
 * behave as they do in real-world scenarios.
 */
object TestNotebookSeeder {

    suspend fun seedNotebook(
        db: AppDatabase,
        pageCount: Int = 3,
        strokesPerPage: Int = 25,
        randomSeed: Int = 42,
        notebookTitle: String = "Seeded notebook",
    ): SeededNotebook {
        require(pageCount >= 1)
        require(strokesPerPage >= 1)

        val notebookId = UUID.randomUUID().toString()
        val notebook = Notebook(id = notebookId, title = notebookTitle)
        db.notebookDao().create(notebook)

        val pageIds = (0 until pageCount).map { UUID.randomUUID().toString() }

        pageIds.forEachIndexed { index, pageId ->
            val page = Page(
                id = pageId,
                notebookId = notebookId,
                background = "blank",
                backgroundType = "native",
            )
            db.pageDao().create(page)

            val strokes = generateStrokes(
                pageId = pageId,
                count = strokesPerPage,
                seed = randomSeed + index * 1000,
            )
            db.strokeDao().create(strokes)
        }

        db.notebookDao().setPageIds(notebookId, pageIds)
        db.notebookDao().setOpenPageId(notebookId, pageIds.first())

        return SeededNotebook(
            notebookId = notebookId,
            pageIds = pageIds,
        )
    }

    private fun generateStrokes(pageId: String, count: Int, seed: Int): List<Stroke> {
        val rnd = Random(seed)
        return (0 until count).map { i ->
            val baseX = rnd.nextInt(from = 50, until = 900).toFloat()
            val baseY = (50 + i * 20 + rnd.nextInt(from = 0, until = 10)).toFloat()

            val points = (0 until 12).map { p ->
                val dx = p * rnd.nextInt(from = 8, until = 18)
                val dy = rnd.nextInt(from = -6, until = 7)
                StrokePoint(
                    x = baseX + dx,
                    y = baseY + dy,
                    pressure = 1200f,
                    tiltX = 0,
                    tiltY = 0,
                    dt = null,
                )
            }

            val xs = points.map { it.x }
            val ys = points.map { it.y }

            Stroke(
                id = UUID.randomUUID().toString(),
                size = 5f,
                pen = Pen.BALLPEN,
                color = 0xFF000000.toInt(),
                maxPressure = 4096,
                top = ys.minOrNull() ?: 0f,
                bottom = ys.maxOrNull() ?: 0f,
                left = xs.minOrNull() ?: 0f,
                right = xs.maxOrNull() ?: 0f,
                points = points,
                pageId = pageId,
            )
        }
    }
}

