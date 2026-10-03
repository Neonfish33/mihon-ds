package eu.kanade.tachiyomi.ui.reader.viewer.eink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.input.ReaderAction
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import okio.Buffer
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * E-ink viewer for long-strip (webtoon) pages.
 *
 * A page image is drawn fit-to-width, but instead of continuous scrolling it is paginated into
 * screen-sized pages (like a book). Break points are chosen at blank "gutters" between panels
 * (near each screen boundary) so speech bubbles and panels are not cut in half.
 */
class EinkWebtoonViewer(private val activity: ReaderActivity) : Viewer {

    private val scope = MainScope()
    private val pageView = EinkPageView(activity)
    private val frame = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(
            pageView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private var pages: List<ReaderPage> = emptyList()
    private var index = 0
    private var breakIndex = 0
    private var breaks: IntArray = intArrayOf(0) // display-pixel offsets of page starts
    private var breaksForWidth = 0

    private var decoder: BitmapRegionDecoder? = null
    private var srcWidth = 0
    private var srcHeight = 0
    private var loadJob: Job? = null

    init {
        pageView.onTapNext = { moveToNext() }
        pageView.onTapPrev = { moveToPrevious() }
        pageView.onTapMenu = { activity.toggleMenu() }
        pageView.onSized = { onViewSizeChanged() }
    }

    override fun getView(): View = frame

    override fun destroy() {
        super.destroy()
        loadJob?.cancel()
        scope.cancel()
        decoder?.recycle()
        decoder = null
    }

    override fun setChapters(chapters: ViewerChapters) {
        pages = chapters.currChapter.pages.orEmpty()
        index = chapters.currChapter.requestedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        loadCurrentPage()
    }

    override fun moveToPage(page: ReaderPage) {
        val i = pages.indexOf(page)
        if (i >= 0) {
            index = i
            loadCurrentPage()
        }
    }

    private fun displayScale(): Float {
        val vw = pageView.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        if (srcWidth <= 0) return 0f
        return vw.toFloat() / srcWidth
    }

    private fun displayHeight(): Int {
        val s = displayScale()
        if (s <= 0f) return 0
        return (srcHeight * s).toInt()
    }

    private fun currentWindow(): Pair<Int, Int> {
        val top = breaks.getOrElse(breakIndex) { 0 }
        val bottom = breaks.getOrElse(breakIndex + 1) { displayHeight() }
        return top to bottom.coerceAtLeast(top + 1)
    }

    override fun moveToNext(): Boolean {
        if (breakIndex < breaks.size - 1) {
            breakIndex++
            decodeWindow()
            return true
        }
        if (index < pages.lastIndex) {
            index++
            loadCurrentPage()
            return true
        }
        activity.loadNextChapter()
        return true
    }

    override fun moveToPrevious(): Boolean {
        if (breakIndex > 0) {
            breakIndex--
            decodeWindow()
            return true
        }
        if (index > 0) {
            index--
            loadCurrentPage(lastScreen = true)
            return true
        }
        activity.loadPreviousChapter()
        return true
    }

    private fun onViewSizeChanged() {
        if (decoder != null && pageView.width > 0 && pageView.width != breaksForWidth) {
            scope.launch {
                computeBreaks()
                decodeWindow()
            }
        } else {
            decodeWindow()
        }
    }

    private fun loadCurrentPage(lastScreen: Boolean = false) {
        val page = pages.getOrNull(index) ?: return
        activity.onPageSelected(page)

        decoder?.recycle()
        decoder = null
        srcWidth = 0
        srcHeight = 0
        breakIndex = 0
        breaks = intArrayOf(0)
        breaksForWidth = 0
        pageView.setBitmap(null)
        pageView.invalidate()

        loadJob?.cancel()
        loadJob = scope.launch {
            supervisorScope {
                page.chapter.pageLoader?.let { loader ->
                    launchIO { loader.loadPage(page) }
                }
                page.statusFlow.collectLatest { state ->
                    when (state) {
                        Page.State.Ready -> {
                            decodeDimensions(page)
                            computeBreaks()
                            if (lastScreen) breakIndex = (breaks.size - 1).coerceAtLeast(0)
                            decodeWindow()
                        }
                        is Page.State.Error -> {
                            pageView.setBitmap(null)
                            pageView.invalidate()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private suspend fun decodeDimensions(page: ReaderPage) {
        val streamFn = page.stream ?: return
        try {
            val bytes = withIOContext { streamFn().use { Buffer().readFrom(it).readByteArray() } }
            val d = withIOContext { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) }
            decoder = d
            srcWidth = d?.width ?: 0
            srcHeight = d?.height ?: 0
        } catch (e: Throwable) {
            logcat { "EinkWebtoonViewer: failed to decode dimensions: ${e.message}" }
        }
    }

    /**
     * Chooses page break rows at blank gutters near each viewport boundary so panels/bubbles are
     * not cut. Falls back to fixed boundaries when no gutter is found.
     */
    private suspend fun computeBreaks() {
        val d = decoder ?: run { breaks = intArrayOf(0); return }
        val vw = pageView.width
        val vh = pageView.height
        if (vw <= 0 || vh <= 0 || srcWidth <= 0 || srcHeight <= 0) {
            breaks = intArrayOf(0)
            return
        }

        val scale = vw.toFloat() / srcWidth
        val sample = 2
        val full = try {
            withIOContext {
                d.decodeRegion(
                    Rect(0, 0, srcWidth, srcHeight),
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        } catch (e: Throwable) {
            null
        }
        if (full == null || full.height <= 0) {
            breaks = intArrayOf(0)
            return
        }

        val dh = full.height
        val k = dh.toFloat() / srcHeight // sampled rows per source row
        val dsPerScreen = (vh / scale) * k

        // Per-row fraction of near-white pixels (speech bubbles and empty gutters are white).
        val whiteRatio = FloatArray(dh)
        val step = 3
        for (y in 0 until dh) {
            var white = 0
            var total = 0
            var x = 0
            while (x < full.width) {
                val c = full.getPixel(x, y)
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                if (r >= 210 && g >= 210 && b >= 210) white++
                total++
                x += step
            }
            whiteRatio[y] = if (total > 0) white.toFloat() / total else 0f
        }
        full.recycle()

        val result = ArrayList<Int>()
        result.add(0)
        var target = dsPerScreen
        val searchWindow = (dsPerScreen * 0.35).toInt().coerceAtLeast(4)
        val minPagePx = (vh * 0.5f).toInt()
        while (target < dh - 2) {
            val lo = (target.toInt() - searchWindow).coerceAtLeast(2)
            val hi = (target.toInt() + searchWindow).coerceAtMost(dh - 2)
            var bestY = target.toInt().coerceIn(lo, hi)
            var bestScore = Float.MAX_VALUE
            for (y in lo..hi) {
                val dist = kotlin.math.abs(y - target).toFloat() / searchWindow
                // Prefer rows with the least white (avoid cutting through a speech bubble) that are
                // also close to the screen boundary.
                val score = whiteRatio[y] + dist * 0.15f
                if (score < bestScore) {
                    bestScore = score
                    bestY = y
                }
            }
            val dispPx = ((bestY / k) * scale).toInt()
            if (dispPx - result.last() >= minPagePx) result.add(dispPx)
            target = bestY + dsPerScreen
        }

        // Merge a short trailing remainder into the previous page to avoid a mostly-empty page.
        val endPx = displayHeight()
        if (result.size >= 2 && endPx - result.last() < minPagePx) {
            result.removeAt(result.size - 1)
        }

        breaks = result.toIntArray()
        breaksForWidth = vw
        if (breakIndex >= breaks.size) breakIndex = (breaks.size - 1).coerceAtLeast(0)
    }

    private fun decodeWindow() {
        val d = decoder ?: return
        val vw = pageView.width
        val vh = pageView.height
        if (vw <= 0 || vh <= 0 || srcWidth <= 0 || srcHeight <= 0) return

        val (topDisp, bottomDisp) = currentWindow()
        val scale = vw.toFloat() / srcWidth
        val top = (topDisp / scale).toInt().coerceIn(0, srcHeight - 1)
        val bottom = (bottomDisp / scale).toInt().coerceIn(top + 1, srcHeight)

        try {
            val bmp = d.decodeRegion(Rect(0, top, srcWidth, bottom), BitmapFactory.Options())
            pageView.setBitmap(bmp)
        } catch (e: Throwable) {
            logcat { "EinkWebtoonViewer: failed to decode region: ${e.message}" }
        }
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            -> if (isUp) moveToNext() else return true

            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            -> if (isUp) moveToPrevious() else return true

            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()
            else -> return false
        }
        return true
    }

    override fun handleReaderAction(action: ReaderAction): Boolean {
        return when (action) {
            ReaderAction.NEXT,
            ReaderAction.SCROLL_DOWN,
            ReaderAction.FAST_SCROLL_DOWN,
            -> moveToNext()

            ReaderAction.PREVIOUS,
            ReaderAction.SCROLL_UP,
            ReaderAction.FAST_SCROLL_UP,
            -> moveToPrevious()

            else -> false
        }
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0 &&
            event.action == MotionEvent.ACTION_SCROLL
        ) {
            if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0f) moveToNext() else moveToPrevious()
            return true
        }
        return false
    }

    override fun handleExternalScroll(dy: Float) {
        if (dy > 0) moveToNext() else moveToPrevious()
    }

    override fun handleExternalFling(velocityY: Float) {
        if (velocityY > 0) moveToNext() else moveToPrevious()
    }
}

/**
 * Simple view that draws the current screen-sized slice of the strip.
 */
private class EinkPageView(context: Context) : View(context) {

    var onTapNext: () -> Unit = {}
    var onTapPrev: () -> Unit = {}
    var onTapMenu: () -> Unit = {}
    var onSized: () -> Unit = {}

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val w = width
                if (w <= 0) return true
                when {
                    e.x < w / 3f -> onTapPrev()
                    e.x > w * 2f / 3f -> onTapNext()
                    else -> onTapMenu()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onTapMenu()
                return true
            }
        },
    )

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onSized()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = bitmap ?: return
        if (b.width <= 0 || b.height <= 0 || width <= 0) return
        val scale = width.toFloat() / b.width
        dst.set(0f, 0f, width.toFloat(), b.height * scale)
        canvas.drawBitmap(b, null, dst, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return true
    }
}
