@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.avbox.osc.ui

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.avbox.osc.R
import com.github.avbox.osc.player.state.PlayerUiState
import com.github.avbox.osc.ui.player.CastSheet
import com.github.avbox.osc.ui.components.HomeBackdrop
import com.github.avbox.osc.ui.components.ImagePalette
import com.github.avbox.osc.ui.components.LoadState
import com.github.avbox.osc.ui.components.LoadStateBox
import com.github.avbox.osc.ui.components.LocalTopBarGlassBackdrop
import com.github.avbox.osc.ui.components.PosterBackdrop
import com.github.avbox.osc.ui.components.VodCardMenu
import com.github.avbox.osc.ui.components.rememberVodCardMenuState
import com.github.avbox.osc.ui.theme.AppThemeState
import com.github.avbox.osc.ui.settings.UiSettingsState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.avbox.osc.ui.page.jumpToSearch

private const val EntrySlideDurationMs = 300
private const val ContentSlideRatio = 0.2f

/** 详情页内播放时，展开区域固定为 16:9 通栏（宽 / 高）。 */
private const val INLINE_PLAYER_ASPECT_RATIO = 16f / 9f

/** 详情页内播放器展开 / 收起的过渡时长。 */
private const val INLINE_PLAYER_ENTER_MS = 320
private const val INLINE_PLAYER_EXIT_MS = 220

private const val FlatScrollScrimAlpha = 0.45f

private val FlatPageScrimStops = arrayOf(
    0f to Color.Black.copy(alpha = 0.05f),
    0.45f to Color.Black.copy(alpha = 0.28f),
    0.75f to Color.Black.copy(alpha = 0.48f),
    1f to Color.Black.copy(alpha = 0.62f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activity: DetailActivity, vm: DetailViewModel) {
    val menuContext = LocalContext.current
    val pageState by vm.pageState.collectAsState()
    val full by vm.fullScreen.collectAsState()
    val rotating by vm.rotating.collectAsState()
    val playSignal by vm.playSignal.collectAsState()
    val enteringFullscreen by vm.enteringFullscreen.collectAsState()
    val exitingFullscreen by vm.exitingFullscreen.collectAsState()
    val inlinePlay by vm.inlinePlayback.collectAsState()
    val vodMenu = rememberVodCardMenuState(
        lookupCollected = vm::isVideoCollected,
        onCollectToggle = vm::toggleVideoCollect,
    )

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // 详情页内播放时保留状态栏：播放器从状态栏下方开始，展开到全屏时再回到顶部
    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val heroFadePx = with(density) { HeroScrollFadeDistance.roundToPx() }
    val heroProgress by remember(listState, heroFadePx) {
        derivedStateOf {
            heroScrollProgress(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                heroFadePx,
            )
        }
    }
    val configuration = LocalConfiguration.current
    val flatHero = flatHeroLayout(
        configuration.smallestScreenWidthDp,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    )
    LaunchedEffect(Unit) {
        // 详情页顶部始终是深色头图；滚动后还会盖上实色状态栏遮罩，统一使用浅色图标
        activity.setStatusBarIconsLight(false)
    }

    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullBox = DetailFullScreenFrame.fullBox(rotating, full, isLandscapeNow)
    val inTransition = enteringFullscreen || exitingFullscreen
    // 滚动离开头图后，用窗口层上的黑色视图压住状态栏区域，避免页面内容从透明状态栏后面透出
    val statusShadeThresholdPx = with(density) { 6.dp.roundToPx() }
    LaunchedEffect(listState, flatHero, fullBox, statusShadeThresholdPx) {
        snapshotFlow {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > statusShadeThresholdPx
        }.collect { scrolled ->
            activity.setStatusBarShadeAlpha(if (scrolled && !flatHero && !fullBox) 1f else 0f)
        }
    }
    DisposableEffect(Unit) {
        onDispose { activity.setStatusBarShadeAlpha(0f) }
    }
    var slideWidthPx by remember { mutableFloatStateOf(0f) }
    var slideHeightPx by remember { mutableFloatStateOf(0f) }

    // 内嵌播放器高度：16:9 通栏，最多铺满状态栏以下的可用高度
    val inlinePlayerHeightPx = (slideWidthPx / INLINE_PLAYER_ASPECT_RATIO)
        .coerceAtMost((slideHeightPx - statusBarTopPx).coerceAtLeast(0f))

    // 详情页内播放器的展开进度：0 = 收起（高度 0），1 = 完整 16:9 通栏。
    var inlineBandActive by remember { mutableStateOf(false) }
    val inlineBandAnim = remember { Animatable(0f) }
    LaunchedEffect(inlinePlay, full, inTransition) {
        when {
            inlinePlay -> {
                inlineBandActive = true
                // 从全屏退回来时直接按完整尺寸渲染，避免先塌成 0 高度再展开
                if (full || inTransition) inlineBandAnim.snapTo(1f)
                else inlineBandAnim.animateTo(1f, tween(INLINE_PLAYER_ENTER_MS))
            }

            full && inlineBandActive -> inlineBandAnim.snapTo(1f)

            inlineBandActive -> {
                inlineBandAnim.animateTo(0f, tween(INLINE_PLAYER_EXIT_MS))
                inlineBandActive = false
            }
        }
    }

    val container = activity.playContainer
    val scope = rememberCoroutineScope()
    var backdropPic by remember { mutableStateOf("") }
    var backdropSeed by remember { mutableStateOf<Int?>(null) }
    val pendingPic = vm.pendingPicture
    val backdropSource = backdropPic.ifEmpty { pendingPic }
    val backdropSeedArgb = backdropSeed ?: HomeBackdrop.seedOf(pendingPic)
    val darkTheme = AppThemeState.isDark(isSystemInDarkTheme()) || flatHero
    val schemeSeed = backdropSeedArgb ?: if (flatHero) AppThemeState.config.seedArgb else null
    val colorScheme = schemeSeed?.let { argb ->
        remember(argb, darkTheme) {
            AppThemeState.customScheme(argb, darkTheme, AppThemeState.config.style)
        }
    } ?: MaterialTheme.colorScheme

    LaunchedEffect(pageState) {
        if (pageState is DetailViewModel.PageState.Loading) {
            backdropPic = ""
            backdropSeed = null
        }
    }

    LaunchedEffect(pageState) {
        if (pageState is DetailViewModel.PageState.Ready) {
            withFrameNanos { }
            withFrameNanos { }
            activity.preheatWebView()
        }
    }

    LaunchedEffect(full, playSignal) {
        if (activity.playContainer == null && (full || playSignal > 0)) activity.ensurePlayContainer()
    }

    LaunchedEffect(vm) {
        vm.playbackCommands.collect { command ->
            val c = activity.playContainer ?: return@collect
            when (command) {
                is PlaybackCommand.StopForContentSwitch -> c.stopForContentSwitch()
                is PlaybackCommand.StopForSourceSwitch -> c.stopForSourceSwitch(command.tip)
                is PlaybackCommand.ClearSourceSwitchTip -> c.clearSourceSwitchTip()
                is PlaybackCommand.SetEpisodeSheetOpen -> c.setEpisodeSheetOpen(command.open)
                is PlaybackCommand.SelectQuality -> c.selectQuality(command.position)
            }
        }
    }

    LaunchedEffect(playSignal) {
        if (playSignal > 0) activity.playCurrent()
    }

    var musicWatch by remember { mutableStateOf(false) }
    var musicArmed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { musicWatch = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { musicWatch = false }
    LaunchedEffect(playSignal) {
        if (playSignal > 0) musicArmed = true
    }
    LaunchedEffect(musicWatch, musicArmed) {
        if (!musicWatch || !musicArmed) return@LaunchedEffect
        while (musicArmed) {
            snapshotFlow {
                activity.playContainer?.uiState()?.playState to UiSettingsState.config.musicAutoOpenPage
            }
                .filter { (_, enabled) -> enabled }
                .first { activity.musicPlaybackDetected() }
            if (activity.handOffToMusicPlayer()) {
                musicArmed = false
            } else {
                delay(300)
            }
        }
    }

    val slideAnim = remember { Animatable(0f) }

    fun slideProgress(): Float = if (inTransition) slideAnim.value else if (full && fullBox) 1f else 0f

    LaunchedEffect(enteringFullscreen, exitingFullscreen) {
        when {
            exitingFullscreen -> {
                slideAnim.animateTo(0f, tween(EntrySlideDurationMs, easing = FastOutSlowInEasing))
                try {
                    vm.onExitSlideFinished()
                } finally {
                    activity.settleRotationAfterExit()
                }
            }

            enteringFullscreen -> {
                activity.settleRotationAfterExit()
                // 不在竖屏内先抬高播放器：直接请求横屏，由系统旋转动画把整个窗口旋转放大，画面保持实时
                vm.onEntrySlideFinished()
            }
        }
    }

    LaunchedEffect(inTransition, rotating, container) {
        activity.setPlayerTouchBlocked(inTransition || rotating)
    }

    LaunchedEffect(full) {
        activity.applyFullscreen(full)
    }

    LaunchedEffect(Unit) {
        vm.toastEvent.collect { message ->
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        vm.finishEvent.collect {
            activity.finish()
        }
    }

    val pageBackdrop = rememberLayerBackdrop(onDraw = { drawContent() })
    MaterialTheme(colorScheme = colorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    slideWidthPx = it.width.toFloat()
                    slideHeightPx = it.height.toFloat()
                }
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (flatHero) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
                        val info = vm.vodInfo
                        if (info != null) {
                            DetailHeroPoster(
                                vm = vm,
                                title = info.name ?: "TVBox",
                                altTitle = info.en,
                                picture = info.pic,
                                year = info.year,
                                onImage = { image ->
                                    scope.launch {
                                        val seed = withContext(Dispatchers.Default) { ImagePalette.seedOf(image) }
                                        if (seed != null && backdropSeed == null) backdropSeed = seed
                                    }
                                },
                                onBackdropPic = { backdropPic = it },
                                paused = full,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(*FlatPageScrimStops)),
                        )
                    }
                    FlatScrollScrim(progress = { heroProgress })
                }
            } else {
                PosterBackdrop(
                    pic = backdropSource,
                    onImage = { image ->
                        scope.launch {
                            val seed = withContext(Dispatchers.Default) { ImagePalette.seedOf(image) }
                            if (seed != null && backdropSeed == null) backdropSeed = seed
                        }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset {
                        IntOffset((-slideProgress() * slideWidthPx * ContentSlideRatio).toInt(), 0)
                    },
            ) {
                if (!fullBox || exitingFullscreen) {
                    when (val state = pageState) {
                        is DetailViewModel.PageState.Loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ContainedLoadingIndicator(Modifier.size(64.dp))
                            }
                        }

                        is DetailViewModel.PageState.Empty -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                LoadStateBox(
                                    state = LoadState.Empty,
                                    emptyText = state.msg ?: stringResource(R.string.detail_empty_source),
                                    errorText = "",
                                    retryText = "",
                                    modifier = Modifier.weight(1f),
                                )
                                SourceSection(vm, currentSourceName = null)
                            }
                        }

                        is DetailViewModel.PageState.Ready -> {
                            CompositionLocalProvider(LocalTopBarGlassBackdrop provides pageBackdrop) {
                                DetailContent(
                                    activity = activity,
                                    vm = vm,
                                    listState = listState,
                                    onPosterPic = { backdropPic = it },
                                    onSeed = { argb -> if (argb != null) backdropSeed = argb },
                                    heroCollapse = inlineBandAnim.value,
                                    heroReservedTop = with(density) {
                                        (statusBarTopPx + inlinePlayerHeightPx).toDp()
                                    },
                                )
                            }
                        }
                    }
                }
                if (!fullBox || exitingFullscreen) {
                    DetailTopScrim(
                        modifier = Modifier.align(Alignment.TopCenter),
                        alpha = if (flatHero) 1f else 1f - heroProgress,
                    )
                }
            }

            val playerContainer = container
            if (playerContainer != null) {
                val progress = slideProgress()
                val inlineActive = inlineBandActive && slideHeightPx > 0f
                val playerBoxModifier = if (inlineActive) {
                    // 详情页展开播放：保留状态栏、从状态栏下方按 16:9 通栏展开，进入全屏时回到顶部并插值到整屏
                    val expandedPx = inlinePlayerHeightPx + (slideHeightPx - inlinePlayerHeightPx) * progress
                    val heightPx = (expandedPx * inlineBandAnim.value).coerceAtLeast(1f)
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .offset { IntOffset(0, (statusBarTopPx * (1f - progress)).toInt()) }
                        .height(with(density) { heightPx.toDp() })
                } else {
                    Modifier
                        .fillMaxSize()
                        .offset {
                            IntOffset(((1f - progress) * slideWidthPx).toInt(), 0)
                        }
                }
                Box(
                    modifier = playerBoxModifier
                        .background(Color.Black),
                ) {
                    AndroidView(
                        factory = { playerContainer },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // 展开态右下角的全屏按钮：播放加载过程中点它也能直接进横屏全屏
                    if (inlineActive && progress < 0.5f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable { vm.onFullScreenToggleRequested(true, activity.playbackFacts()) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_player_fullscreen),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            container?.uiState()?.let { PlayerCastOverlay(it) }

            if (inTransition || rotating) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                )
            }
        }

        EpisodeSheet(vm, slideFromEnd = fullBox && isLandscapeNow, showQuality = full)
        VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
    }
}

@Composable
private fun PlayerCastOverlay(state: PlayerUiState) {
    state.castSheet?.let { sheet ->
        CastSheet(sheet) { state.dismissCastSheet() }
    }
}

@Composable
private fun FlatScrollScrim(progress: () -> Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color.Black.copy(
                    alpha = FlatScrollScrimAlpha * progress().coerceIn(0f, 1f),
                ),
            ),
    )
}

