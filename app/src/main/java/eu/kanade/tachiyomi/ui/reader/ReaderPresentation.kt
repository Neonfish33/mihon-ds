package eu.kanade.tachiyomi.ui.reader

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.WebtoonLayoutManager
import android.view.GestureDetector
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.panel.PanelPageRenderVariant
import eu.kanade.tachiyomi.ui.reader.panel.PanelPageKey
import eu.kanade.tachiyomi.ui.reader.panel.PanelReadingSettings
import eu.kanade.tachiyomi.ui.reader.panel.hasSameLogicalPage
import eu.kanade.tachiyomi.ui.reader.panel.panelPageKey
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonAdapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okio.Buffer
import mihon.core.dualscreen.DualScreenState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.presentation.core.util.collectAsState

private class TouchInterceptFrameLayout(context: android.content.Context) : FrameLayout(context) {
    var externalGestureDetector: GestureDetector? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        externalGestureDetector?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Consume all events so gesture detector gets full touch sequences
        return true
    }
}

private const val PANEL_TAP_MENU_SUPPRESSION_MS = 700L

class ReaderPresentation(
    outerContext: Context,
    display: Display,
    private val activity: ReaderActivity,
) : Presentation(outerContext, display), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val onBackPressedDispatcher: OnBackPressedDispatcher
        get() = activity.onBackPressedDispatcher

    private var container: TouchInterceptFrameLayout? = null
    private var composeView: androidx.compose.ui.platform.ComposeView? = null
    private var suppressSecondaryTapUntil = 0L

    private var localMenuVisibleState: MutableState<Boolean>? = null
    var localMenuVisible: Boolean
        get() = localMenuVisibleState?.value ?: false
        set(value) {
            localMenuVisibleState?.value = value
        }

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val backgroundColor = ContextCompat.getColor(context, R.color.reader_background_dark)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(backgroundColor))

        savedStateRegistryController.performRestore(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
        }

        setupGestureDetector()

        container = TouchInterceptFrameLayout(context).apply {
            setBackgroundColor(backgroundColor)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            externalGestureDetector = gestureDetector
        }
        setContentView(container!!)

        setupComposeOverlay()

        lifecycleScope.launch {
            DualScreenState.rotationEvents.collect {
                container?.post { setupRotation() }
            }
        }

        setupRotation()
    }

    private fun setupComposeOverlay() {
        composeView = androidx.compose.ui.platform.ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container?.addView(composeView!!)

        composeView?.setComposeContent {
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides this
            ) {
                ReaderContent()
            }
        }
    }

    @Composable
    private fun ReaderContent() {
        val state by activity.viewModel.state.collectAsState()
        val readingMode = ReadingMode.fromPreference(activity.viewModel.getMangaReadingMode(resolveDefault = true))
        val isRtl = readingMode == ReadingMode.RIGHT_TO_LEFT
        val panelReadingPreferenceEnabled by activity.readerPreferences.panelReadingPaged().collectAsState()
        val panelTransitionMillis by activity.readerPreferences.panelReadingTransitionMillis().collectAsState()
        val panelFocusEffect by activity.readerPreferences.panelReadingFocusEffect().collectAsState()
        val panelFocusStrength by activity.readerPreferences.panelReadingFocusStrength().collectAsState()
        val panelReadingState by activity.panelReadingController.state.collectAsState()
        val panelReadingEnabled = ReaderPanelReadingMode.isActive(
            panelReadingEnabled = panelReadingPreferenceEnabled,
            readingModePreference = activity.viewModel.getMangaReadingMode(resolveDefault = true),
        )

        val currentPage by activity.viewModel.state
            .map { it.currentPage }
            .distinctUntilChanged()
            .collectAsState(initial = state.currentPage)

        val currentChapterPages by activity.viewModel.state
            .map { it.currentChapter?.pages }
            .distinctUntilChanged()
            .collectAsState(initial = state.currentChapter?.pages)

        val panelRenderVariant = panelReadingState.key?.renderVariant ?: PanelPageRenderVariant.FULL
        val currentDisplayPage = currentChapterPages?.getOrNull(currentPage - 1)
        val currentDisplayPageKey = currentDisplayPage?.panelPageKey(panelRenderVariant)
        val currentDisplayInsertPageKey = currentDisplayPageKey?.copy(isInsertPage = true)
        val panelMapEnabled = panelReadingEnabled &&
            panelReadingState.activePanel != null &&
            (
                currentDisplayPageKey?.hasSameLogicalPage(panelReadingState.key) == true ||
                    currentDisplayInsertPageKey?.hasSameLogicalPage(panelReadingState.key) == true
                )

        val displayPageNum = if (panelReadingEnabled) {
            currentPage
        } else {
            getSecondaryPageNumber(currentPage)
        }
        val displayPage = currentChapterPages?.getOrNull(displayPageNum - 1)
        val activePanel = panelReadingState.activePanel.takeIf { panelMapEnabled }
        val displayRenderVariant = if (panelMapEnabled) {
            panelRenderVariant
        } else {
            PanelPageRenderVariant.FULL
        }

        val menuState = remember { mutableStateOf(false) }
        localMenuVisibleState = menuState

        if (menuState.value) {
            BackHandler {
                menuState.value = false
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (readingMode.type == ReadingMode.ViewerType.Webtoon && state.viewer is WebtoonViewer) {
                WebtoonSpannedContent(activity, state.viewer as WebtoonViewer)
            } else {
                AnimatedContent(
                    targetState = displayPage,
                    transitionSpec = {
                        val targetIdx = targetState?.index
                        val initialIdx = initialState?.index
                        if (targetIdx != null && initialIdx != null) {
                            val direction = if (readingMode.direction == ReadingMode.Direction.Vertical) {
                                if (targetIdx > initialIdx) {
                                    AnimatedContentTransitionScope.SlideDirection.Up
                                } else {
                                    AnimatedContentTransitionScope.SlideDirection.Down
                                }
                            } else if (isRtl) {
                                if (targetIdx > initialIdx) {
                                    AnimatedContentTransitionScope.SlideDirection.Right
                                } else {
                                    AnimatedContentTransitionScope.SlideDirection.Left
                                }
                            } else {
                                if (targetIdx > initialIdx) {
                                    AnimatedContentTransitionScope.SlideDirection.Left
                                } else {
                                    AnimatedContentTransitionScope.SlideDirection.Right
                                }
                            }

                            if (readingMode.direction == ReadingMode.Direction.Vertical) {
                                (slideIntoContainer(direction, animationSpec = tween(300)) + fadeIn(tween(300)))
                                    .togetherWith(slideOutOfContainer(direction, animationSpec = tween(300)) + fadeOut(tween(300)))
                            } else {
                                (slideInHorizontally(animationSpec = tween(300)) { if (direction == AnimatedContentTransitionScope.SlideDirection.Left) it else -it } + fadeIn(tween(300)))
                                    .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { if (direction == AnimatedContentTransitionScope.SlideDirection.Left) -it else it } + fadeOut(tween(300)))
                            }
                        } else {
                            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                        }
                    },
                    label = "PageTransition"
                ) { page ->
                    if (page != null) {
                        AndroidView(
                            factory = { ctx ->
                                ReaderPageImageView(ctx).apply {
                                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                }
                            },
                            update = { view ->
                                val requestedKey = view.getTag(R.id.tag_panel_requested_page_key)
                                val pageKey = page.panelPageKey(displayRenderVariant)
                                if (ReaderPresentationPageLoadGuard.shouldStartPageLoad(pageKey, requestedKey)) {
                                    view.prepareForPageLoad(pageKey)
                                    loadPageIntoView(view, page, displayRenderVariant)
                                }
                                view.applyPanelReadingDisplayConfig(
                                    panelTransitionDuration = panelTransitionMillis,
                                    panelFocusEffect = panelFocusEffect,
                                    panelFocusStrength = panelFocusStrength,
                                    panelPrimaryOverlay = true,
                                )
                                val panelKey = panelReadingState.key
                                if (panelMapEnabled && activePanel != null && panelKey != null) {
                                    view.showPanelMap(
                                        panels = panelReadingState.panels,
                                        activePanel = activePanel,
                                        showNumbers = true,
                                    ) { panelIndex ->
                                        suppressSecondaryTap()
                                        activity.panelReadingController.selectPanel(panelKey, panelIndex)
                                    }
                                } else {
                                    view.highlightPanel(null)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }

            LaunchedEffect(state.menuVisible) {
                if (state.menuVisible) {
                    menuState.value = false
                }
            }

            if (menuState.value) {
                ReaderAppBars(
                    visible = true,
                    mangaTitle = state.manga?.title,
                    chapterTitle = state.currentChapter?.chapter?.name,
                    navigateUp = { menuState.value = false },
                    onClickTopAppBar = { },
                    bookmarked = state.bookmarked,
                    onToggleBookmarked = { activity.viewModel.toggleChapterBookmark() },
                    onOpenInWebView = null,
                    onOpenInBrowser = null,
                    onShare = null,
                    viewer = null,
                    onNextChapter = { activity.loadNextChapter() },
                    enabledNext = state.viewerChapters?.nextChapter != null,
                    onPreviousChapter = { activity.loadPreviousChapter() },
                    enabledPrevious = state.viewerChapters?.prevChapter != null,
                    currentPage = displayPageNum.coerceIn(1, state.totalPages),
                    totalPages = state.totalPages,
                    onPageIndexChange = { newPageIndex ->
                        val currentReadingMode = ReadingMode.fromPreference(
                            activity.viewModel.getMangaReadingMode(resolveDefault = true),
                        )
                        val currentIsRtl = currentReadingMode == ReadingMode.RIGHT_TO_LEFT
                        val primaryPageIndex = if (panelReadingEnabled) {
                            newPageIndex
                        } else if (currentIsRtl) {
                            newPageIndex + 1
                        } else {
                            newPageIndex - 1
                        }
                        activity.moveToPageIndex(primaryPageIndex.coerceIn(0, state.totalPages - 1))
                    },
                    readingMode = readingMode,
                    onClickReadingMode = { activity.viewModel.openReadingModeSelectDialog() },
                    orientation = eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation.fromPreference(
                        activity.viewModel.getMangaOrientation(resolveDefault = false),
                    ),
                    onClickOrientation = { activity.viewModel.openOrientationModeSelectDialog() },
                    cropEnabled = false,
                    onClickCropBorder = { },
                    onClickSettings = { activity.viewModel.openSettingsDialog() },
                    companionPageEnabled = activity.isCompanionPageEnabled(),
                    onClickDualScreenMode = { activity.setCompanionPage(!activity.isCompanionPageEnabled()) },
                    panelReadingEnabled = panelReadingEnabled,
                    onClickPanelReading = if (
                        panelReadingPreferenceEnabled &&
                        ReadingMode.isPagerType(activity.viewModel.getMangaReadingMode())
                    ) {
                        {
                            activity.togglePanelReading()
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    override fun onBackPressed() {
        onBackPressedDispatcher.onBackPressed()
    }

    @Composable
    private fun WebtoonSpannedContent(
        activity: ReaderActivity,
        primaryViewer: WebtoonViewer,
    ) {
        val context = LocalContext.current
        val secondaryRecycler = remember { WebtoonRecyclerView(context) }
        val secondaryAdapter = remember { WebtoonAdapter(primaryViewer) }
        val isSyncing = remember { mutableStateOf(false) }

        DisposableEffect(primaryViewer.recycler) {
            var syncPending = false

            fun syncSecondaryPosition() {
                if (isSyncing.value) return
                isSyncing.value = true
                try {
                    val primaryWidth = primaryViewer.recycler.width.toFloat()
                    val secondaryWidth = secondaryRecycler.width.toFloat()

                    if (primaryWidth > 0 && secondaryWidth > 0) {
                        val ratio = secondaryWidth / primaryWidth
                        val primaryLayout = primaryViewer.recycler.layoutManager as? LinearLayoutManager ?: return
                        val secondaryLayout = secondaryRecycler.layoutManager as? LinearLayoutManager ?: return

                        val firstPos = primaryLayout.findFirstVisibleItemPosition()
                        val firstView = primaryLayout.findViewByPosition(firstPos)

                        if (firstView != null && firstPos != RecyclerView.NO_POSITION) {
                            val primaryOffset = firstView.top
                            val primaryHeight = primaryViewer.recycler.height

                            val targetOffset = (primaryOffset + primaryHeight) * ratio

                            val secFirstPos = secondaryLayout.findFirstVisibleItemPosition()
                            val secFirstView = secondaryLayout.findViewByPosition(secFirstPos)

                            if (secFirstPos == firstPos && secFirstView != null) {
                                val currentOffset = secFirstView.top.toFloat()
                                val diff = targetOffset - currentOffset

                                if (kotlin.math.abs(diff) > 1f) {
                                    secondaryRecycler.scrollBy(0, -diff.toInt())
                                }
                            } else {
                                secondaryLayout.scrollToPositionWithOffset(firstPos, targetOffset.toInt())
                            }
                        }
                    }
                } finally {
                    isSyncing.value = false
                }
            }

            val scrollListener = object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (!syncPending) {
                        syncPending = true
                        secondaryRecycler.post {
                            syncSecondaryPosition()
                            syncPending = false
                        }
                    }
                }
            }
            primaryViewer.recycler.addOnScrollListener(scrollListener)

            val layoutListener = object : android.view.View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: android.view.View?,
                    left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                     syncSecondaryPosition()
                }
            }
            secondaryRecycler.addOnLayoutChangeListener(layoutListener)

            val dataObserver = object : RecyclerView.AdapterDataObserver() {
                private fun syncItems() {
                    val primaryItems = primaryViewer.adapter.items
                    secondaryAdapter.setItems(primaryItems)
                    syncSecondaryPosition()
                }
                override fun onChanged() = syncItems()
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = syncItems()
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = syncItems()
                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = syncItems()
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = syncItems()
            }
            primaryViewer.adapter.registerAdapterDataObserver(dataObserver)

            onDispose {
                primaryViewer.recycler.removeOnScrollListener(scrollListener)
                secondaryRecycler.removeOnLayoutChangeListener(layoutListener)
                primaryViewer.adapter.unregisterAdapterDataObserver(dataObserver)
            }
        }

        AndroidView(
            factory = { secondaryRecycler },
            update = { recycler ->
                if (recycler.adapter == null) {
                    recycler.adapter = secondaryAdapter
                    recycler.layoutManager = WebtoonLayoutManager(context, context.resources.displayMetrics.heightPixels)
                    secondaryAdapter.setItems(primaryViewer.adapter.items)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    private fun loadPageIntoView(
        view: ReaderPageImageView,
        page: ReaderPage,
        renderVariant: PanelPageRenderVariant = PanelPageRenderVariant.FULL,
    ) {
        val expectedKey = page.panelPageKey(renderVariant)
        val stream = page.stream
        val status = page.status

        if (status is Page.State.Ready && stream == null) {
            view.recycle()
            return
        }

        if (stream != null && status is Page.State.Ready) {
            val config = ReaderPageImageView.Config(
                zoomDuration = 500,
                panelTransitionDuration = activity.readerPreferences.panelReadingTransitionMillis().get(),
                panelFocusEffect = activity.readerPreferences.panelReadingFocusEffect().get(),
                panelFocusStrength = PanelReadingSettings.normalizeFocusStrength(
                    activity.readerPreferences.panelReadingFocusStrength().get(),
                ),
                minimumScaleType = com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE,
                cropBorders = false,
                zoomStartPosition = ReaderPageImageView.ZoomStartPosition.CENTER,
                landscapeZoom = false,
            )
            lifecycleScope.launch {
                try {
                    val bufferedSource = withIOContext {
                        val source = stream().use { Buffer().readFrom(it) }
                        when (renderVariant) {
                            PanelPageRenderVariant.FULL -> source
                            PanelPageRenderVariant.SPLIT_LEFT -> ImageUtil.splitInHalf(source, ImageUtil.Side.LEFT)
                            PanelPageRenderVariant.SPLIT_RIGHT -> ImageUtil.splitInHalf(source, ImageUtil.Side.RIGHT)
                            PanelPageRenderVariant.ROTATE_90 -> ImageUtil.rotateImage(source, 90f)
                            PanelPageRenderVariant.ROTATE_NEGATIVE_90 -> ImageUtil.rotateImage(source, -90f)
                        }
                    }
                    if (!view.canApplyPageLoad(expectedKey)) return@launch
                    val isAnimated = ImageUtil.isAnimatedAndSupported(bufferedSource)
                    view.setImage(bufferedSource, isAnimated, config)
                    view.setTag(R.id.tag_panel_loaded_page_key, expectedKey)
                } catch (e: Exception) {
                    logcat { "Error loading page image: ${e.message}" }
                    if (view.canApplyPageLoad(expectedKey)) {
                        view.recycle()
                    }
                }
            }
        } else if (status is Page.State.Error) {
            view.recycle()
        } else {
            val loader = page.chapter.pageLoader
            if (loader != null && (status is Page.State.Queue || status is Page.State.LoadPage)) {
                launchIO {
                    loader.loadPage(page)
                }
            }
            lifecycleScope.launch {
                val terminalStatus = page.statusFlow.first { it is Page.State.Ready || it is Page.State.Error }
                if (!view.canApplyPageLoad(expectedKey)) return@launch
                if (terminalStatus is Page.State.Ready) {
                    loadPageIntoView(view, page, renderVariant)
                } else {
                    view.recycle()
                }
            }
        }
    }

    private fun ReaderPageImageView.prepareForPageLoad(expectedKey: PanelPageKey) {
        setTag(R.id.tag_panel_requested_page_key, expectedKey)
        setTag(R.id.tag_panel_loaded_page_key, null)
        recycle()
        highlightPanel(null)
    }

    private fun ReaderPageImageView.canApplyPageLoad(expectedKey: PanelPageKey): Boolean {
        return ReaderPresentationPageLoadGuard.canApplyPageLoad(
            expectedKey = expectedKey,
            requestedTag = getTag(R.id.tag_panel_requested_page_key),
            isAttached = isAttachedToWindow,
        )
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // Must return true for onFling to work
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleSecondaryScreenTap()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                val readingMode = ReadingMode.fromPreference(activity.viewModel.getMangaReadingMode(resolveDefault = true))
                if (readingMode.direction == ReadingMode.Direction.Vertical) {
                    activity.handleExternalScroll(distanceY)
                    return true
                }
                return false
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val readingMode = ReadingMode.fromPreference(activity.viewModel.getMangaReadingMode(resolveDefault = true))

                if (readingMode.direction == ReadingMode.Direction.Vertical) {
                    if (readingMode.type == ReadingMode.ViewerType.Webtoon) {
                         activity.handleExternalFling(velocityY)
                         return true
                    }

                    val minVelocity = 500
                    if (kotlin.math.abs(velocityY) > kotlin.math.abs(velocityX) &&
                        kotlin.math.abs(velocityY) > minVelocity) {
                        if (velocityY > 0) activity.loadPreviousPage() else activity.loadNextPage()
                        return true
                    }
                }

                val minVelocity = 500
                if (kotlin.math.abs(velocityX) > kotlin.math.abs(velocityY) &&
                    kotlin.math.abs(velocityX) > minVelocity) {
                    val isRtl = readingMode == ReadingMode.RIGHT_TO_LEFT
                    if (velocityX > 0) {
                        if (isRtl) activity.loadNextPage() else activity.loadPreviousPage()
                    } else {
                        if (isRtl) activity.loadPreviousPage() else activity.loadNextPage()
                    }
                    return true
                }
                return false
            }
        })
    }

    private fun handleSecondaryScreenTap() {
        if (SystemClock.uptimeMillis() < suppressSecondaryTapUntil) {
            suppressSecondaryTapUntil = 0L
            return
        }

        val currentLocalMenu = localMenuVisible
        val primaryMenuVisible = activity.viewModel.state.value.menuVisible

        if (currentLocalMenu) {
            localMenuVisible = false
        } else {
            localMenuVisible = true
            if (primaryMenuVisible) activity.hideMenu()
        }
    }

    private fun suppressSecondaryTap() {
        suppressSecondaryTapUntil = SystemClock.uptimeMillis() + PANEL_TAP_MENU_SUPPRESSION_MS
    }

    // R2L: companion is N-1 (right side), L2R: companion is N+1 (left side)
    private fun getSecondaryPageNumber(currentPage: Int): Int {
        val readingMode = ReadingMode.fromPreference(activity.viewModel.getMangaReadingMode(resolveDefault = true))
        return if (readingMode == ReadingMode.RIGHT_TO_LEFT) currentPage - 1 else currentPage + 1
    }

    fun setupRotation() {
        val activityRotation = activity.windowManager.defaultDisplay.rotation
        val presentationRotation = display.rotation

        var actDeg = when (activityRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val presDeg = when (presentationRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        if (activity.preferences.swapPresentationRotation().get()) {
            actDeg = (actDeg + 180) % 360
        }

        val diff = (actDeg - presDeg + 360) % 360
        val rotation = diff.toFloat()

        container?.let { dashboard ->
            if (diff == 90 || diff == 270) {
                val metrics = context.resources.displayMetrics
                val w = metrics.heightPixels
                val h = metrics.widthPixels

                dashboard.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                    gravity = Gravity.CENTER
                }
                dashboard.rotation = rotation
            } else {
                dashboard.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                dashboard.rotation = rotation
            }
        }
    }

    fun toggleMenu() {
        activity.toggleMenu()
    }

    fun showMenu() {
        activity.showMenu()
    }

    fun hideMenu() {
        activity.hideMenu()
    }

    fun hideLocalMenu() {
        localMenuVisible = false
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onStop() {
        super.onStop()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        composeView?.disposeComposition()
        composeView = null
        container = null
        viewModelStore.clear()
    }
}
