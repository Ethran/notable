package com.ethran.notable.classes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.History
import com.ethran.notable.utils.Mode
import com.ethran.notable.utils.Pen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DrawCanvasTest {

    private lateinit var mockContext: Context
    private lateinit var mockCoroutineScope: CoroutineScope
    private lateinit var mockEditorState: EditorState
    private lateinit var mockPageView: PageView
    private lateinit var mockHistory: History
    private lateinit var mockSurfaceHolder: SurfaceHolder
    private lateinit var mockBitmap: Bitmap
    private lateinit var mockCanvas: Canvas

    private lateinit var drawCanvas: DrawCanvas
    private lateinit var spyDrawCanvas: DrawCanvas

    @Before
    fun setUp() {
        mockContext = mock()
        mockCoroutineScope = CoroutineScope(Dispatchers.Unconfined) // Use Unconfined for testing

        // Mock EditorState and its properties
        mockEditorState = mock()
        whenever(mockEditorState.pen).thenReturn(Pen.BALLPEN) // Simulate a pen
        whenever(mockEditorState.mode).thenReturn(Mode.Draw)
        // Mock penSettings for the current pen
        val mockPenSettings = mock<EditorState.PenSettingsState>()
        whenever(mockPenSettings.strokeSize).thenReturn(2f)
        whenever(mockPenSettings.color).thenReturn(android.graphics.Color.BLACK)
        val penSettingsMap = hashMapOf(Pen.BALLPEN.penName to mockPenSettings)
        whenever(mockEditorState.penSettings).thenReturn(penSettingsMap)


        // Mock PageView and its properties
        mockPageView = mock()
        mockBitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        mockCanvas = spy(Canvas(mockBitmap)) // Spy on Canvas to verify drawRect
        whenever(mockPageView.windowedBitmap).thenReturn(mockBitmap)
        whenever(mockPageView.windowedCanvas).thenReturn(mockCanvas)
        whenever(mockPageView.id).thenReturn("test_page_id")
        whenever(mockPageView.viewWidth).thenReturn(800)
        whenever(mockPageView.viewHeight).thenReturn(600)
        whenever(mockPageView.scroll).thenReturn(0)
        whenever(mockPageView.zoomLevel).thenReturn(MutableStateFlow(1f))


        mockHistory = mock()
        mockSurfaceHolder = mock()

        // Instantiate DrawCanvas - use a real instance and spy on specific methods if needed
        // For drawRandomRectangle, we are testing its internal logic, so a real instance is better.
        // However, drawCanvasToView is a method on DrawCanvas itself, so we need to spy on DrawCanvas.
        drawCanvas = DrawCanvas(
            context = mockContext,
            coroutineScope = mockCoroutineScope,
            state = mockEditorState,
            page = mockPageView,
            history = mockHistory
        )
        // Holder needs to be available for the constructor, but init() sets up the callback.
        // We are not testing surface lifecycle here.
        whenever(mockSurfaceHolder.surfaceFrame).thenReturn(Rect(0, 0, 800, 600))
        whenever(mockSurfaceHolder.lockCanvas()).thenReturn(mockCanvas) // Return our mock canvas
        drawCanvas.holder.addCallback(mock()) // Add a dummy callback to avoid NPE if not testing surface interaction


        spyDrawCanvas = spy(drawCanvas)
        // We need to ensure drawCanvasToView is not actually executing its real implementation
        // as it might involve more complex UI interactions not needed for this unit test.
        doReturn(Unit).whenever(spyDrawCanvas).drawCanvasToView()
    }

    @Test
    fun `drawRandomRectangle should draw a rectangle and refresh canvas`() {
        // Call the method under test using the spy
        // Note: drawRandomRectangle is private, to test it directly we would need to make it internal or use reflection.
        // For now, let's assume we can call it if it were public/internal.
        // If it remains private, this test strategy needs to change (e.g. testing via the public method that calls it).

        // Since drawRandomRectangle is private, we can't call it directly.
        // We will trigger it through the pen change collector logic that was modified.
        // To do this, we'll simulate the pen change.

        // The collector is in registerObservers, which is called by init.
        // And init is called when surface is created.
        // Let's call the private method directly for simplicity in this unit test,
        // acknowledging this might require making it internal for real-world testability.
        // Or, we refactor the test to trigger the public API that calls it.

        // For the purpose of this exercise, let's assume we've made drawRandomRectangle() internal or public for testing.
        // If not, the alternative is to test via the pen change flow.

        // Let's use the spy to call the *real* drawRandomRectangle method,
        // but allow mocking of drawCanvasToView.
        val realDrawCanvas = DrawCanvas(
            context = mockContext,
            coroutineScope = mockCoroutineScope,
            state = mockEditorState,
            page = mockPageView,
            history = mockHistory
        )
        // We need to spy on the real instance to verify calls on its methods
        val spyRealDrawCanvas = spy(realDrawCanvas)
        doReturn(Unit).whenever(spyRealDrawCanvas).drawCanvasToView() // Mock its own method

        // Simulate holder being ready for drawCanvasToView
        val mockRealCanvasForLock = mock<Canvas>()
        whenever(mockSurfaceHolder.lockCanvas()).thenReturn(mockRealCanvasForLock)
        spyRealDrawCanvas.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Set up a bid to avoid NPE in drawCanvasToView if it was not mocked
                whenever(spyRealDrawCanvas.holder.lockCanvas()).thenReturn(Canvas(Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)))
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        // Manually call surfaceCreated to ensure holder is set up if drawCanvasToView was real
        spyRealDrawCanvas.holder.callbacks?.firstOrNull()?.surfaceCreated(spyRealDrawCanvas.holder)


        // Call the method using reflection if it's private, or change visibility
        // For now, let's assume it's accessible for test.
        // If it's private, the following line would not compile.
        // We will call the method on spyRealDrawCanvas
        // Accessing private method via reflection for testing
        val method = DrawCanvas::class.java.getDeclaredMethod("drawRandomRectangle")
        method.isAccessible = true
        method.invoke(spyRealDrawCanvas)

        // Verify that drawRect was called on the mockCanvas (which is page.windowedCanvas)
        verify(mockCanvas).drawRect(
            ArgumentMatchers.any(Rect::class.java),
            ArgumentMatchers.any(Paint::class.java)
        )

        // Verify that drawCanvasToView was called on the spy
        verify(spyRealDrawCanvas).drawCanvasToView()
    }
}
