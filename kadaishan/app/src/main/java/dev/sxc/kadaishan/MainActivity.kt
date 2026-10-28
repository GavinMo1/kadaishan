package dev.sxc.kadaishan

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.viewModels
import dev.sxc.kadaishan.ui.boards.GestureHintViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.PendingSettlement
import dev.sxc.kadaishan.model.VisibleScope
import dev.sxc.kadaishan.ui.AppTheme
import dev.sxc.kadaishan.ui.boards.PhotoCardScreen
import dev.sxc.kadaishan.ui.boards.VideoCardScreen
import androidx.compose.foundation.layout.width
import dev.sxc.kadaishan.ui.kit.Daylight
import dev.sxc.kadaishan.ui.kit.SegTabs
import dev.sxc.kadaishan.ui.kit.TextActionButton
import dev.sxc.kadaishan.ui.kit.rememberHaptics
import dev.sxc.kadaishan.ui.kit.rememberReducedMotion
import dev.sxc.kadaishan.ui.settle.PanelStage
import dev.sxc.kadaishan.ui.settle.SettlementPanel
import dev.sxc.kadaishan.ui.settings.SettingsScreen
import dev.sxc.kadaishan.ui.trash.TrashScreen
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.font.FontWeight
import dev.sxc.kadaishan.ui.kit.PillButton
import kotlinx.coroutines.launch

/** 单 Activity 入口：edge-to-edge + 顶层组装（权限三分支 / 板块 / 设置 push）。 */
class MainActivity : ComponentActivity() {
    private val gestureHints by viewModels<GestureHintViewModel>()

    override fun onStart() {
        super.onStart()
        gestureHints.setForeground(true)
    }

    override fun onStop() {
        gestureHints.setForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
        )
        super.onCreate(savedInstanceState)
        dev.sxc.kadaishan.data.ReviewDiagnostics.initialize(applicationContext)
        val installed = packageManager.getPackageInfo(packageName, 0)
        dev.sxc.kadaishan.data.ReviewDiagnostics.record(
            "installed version=${installed.versionName} code=${installed.longVersionCode} updated=${installed.lastUpdateTime}"
        )
        val container = AppContainer.get(applicationContext)
        setContent {
            AppTheme {
                AppRoot(container, gestureHints)
            }
        }
    }
}

/** PRD §5.4：同一次提交三权限（单张系统弹窗）。 */
private val ALL_MEDIA_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
)

/** 权限判定式（PRD §5.4，G0-6 实测口径）：
 *  FULL = READ_MEDIA_IMAGES || READ_MEDIA_VIDEO 已授予；
 *  否则 PARTIAL = READ_MEDIA_VISUAL_USER_SELECTED 已授予；否则 DENIED。
 *  每次现查、不缓存（调用方在 ON_RESUME 重查）。 */
private fun Context.mediaScope(): VisibleScope {
    fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    return when {
        granted(Manifest.permission.READ_MEDIA_IMAGES) ||
            granted(Manifest.permission.READ_MEDIA_VIDEO) -> VisibleScope.FULL
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> VisibleScope.PARTIAL
        else -> VisibleScope.DENIED
    }
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AppRoot(container: AppContainer, gestureHints: GestureHintViewModel) {
    val context = LocalContext.current
    var deniedGame by rememberSaveable { mutableStateOf(false) }
    var deniedSettings by rememberSaveable { mutableStateOf(false) }
    val cultivationState by container.cultivation.state.collectAsState()
    LaunchedEffect(container) { container.cultivation.refresh() }
    var scope by remember { mutableStateOf(context.mediaScope()) }

    // 权限状态不缓存：ON_RESUME 重查（PRD §5.4；含从系统设置页/权限弹窗返回）
    val lifecycle = context.findLifecycleOwner()?.lifecycle
    DisposableEffect(lifecycle) {
        if (lifecycle == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) scope = context.mediaScope()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
    }

    // 「改授全部」路径：重提三权限后仍 PARTIAL → 跳应用详情设置页
    var settingsFallback by remember { mutableStateOf(false) }

    // M1 冷启动恢复：CONFIRMING 起的暂存删除（PRD §6「进程被杀 → 重启直接 VERIFYING」）。
    // load() 全仓唯一调用点；非空即以快照 id 打开结算面板回查既成事实，不重发删除。
    var pendingRestore by remember { mutableStateOf<PendingSettlement?>(null) }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(container) {
        val pending = container.pendingSettlement.load()
        if (pending != null && pending.snapshot.isNotEmpty()) pendingRestore = pending
    }
    /** 恢复面板退出：关面板 + 清暂存（回查已完成，事实已裁决，标记可弃）。 */
    val exitRestore: () -> Unit = {
        pendingRestore = null
        coroutineScope.launch { container.pendingSettlement.clear() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val fresh = context.mediaScope()
        scope = fresh
        if (settingsFallback) {
            settingsFallback = false
            if (fresh == VisibleScope.PARTIAL) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (scope) {
                VisibleScope.DENIED -> if (deniedSettings && !deniedGame) {
                    BackHandler { deniedSettings = false }
                    SettingsScreen(container.settings, onBack = { deniedSettings = false }, cultivation = container.cultivation, onCultivation = { deniedGame = true })
                } else if (deniedGame) {
                    dev.sxc.kadaishan.ui.cultivation.CultivationScreen(container.cultivation, onBack = { deniedGame = false })
                } else DeniedGuide(
                    onGrant = {
                        settingsFallback = false
                        permissionLauncher.launch(ALL_MEDIA_PERMISSIONS)
                    },
                    onCultivation = if (cultivationState?.enabled != false) ({ deniedGame = true }) else null,
                    onSettings = { deniedSettings = true },
                )

                else -> MainContent(
                    container = container,
                    gestureHints = gestureHints,
                    scope = scope,
                    onGrantAll = {
                        settingsFallback = true
                        permissionLauncher.launch(ALL_MEDIA_PERMISSIONS)
                    },
                )
            }

            // M1 冷启动恢复：暂存非空 → 直接进结算面板 VERIFYING 回查既成事实。
            // DENIED 不渲染（无媒体权限 query 会抛 SecurityException；授权回来后 pending 仍在、再入）。
            pendingRestore?.let { pending ->
                if (scope != VisibleScope.DENIED) {
                    SettlementPanel(
                        markedIds = pending.snapshot.map { it.id },
                        totalInRound = pending.snapshot.size,
                        source = container.mediaSource,
                        deleter = container.mediaDeleter,
                        pendingStore = container.pendingSettlement,
                        onNextRound = exitRestore,
                        onDismiss = exitRestore,
                        initialPhase = PanelStage.VERIFYING,
                        initialSnapshot = pending.snapshot,
                        initialMode = pending.mode,
                        onExitRound = exitRestore,
                        onKeep = { container.reviewHistory.setKept(it, true) },
                    )
                }
            }
        }
    }
}

/** DENIED：全屏开版式引导：图形锚点（叠卡意象）+ 主张句 + 权限说明 + 蓝色药丸 CTA；spatial 入场。 */
@Composable
private fun DeniedGuide(onGrant: () -> Unit, onCultivation: (() -> Unit)?, onSettings: () -> Unit) {
    val reduced = rememberReducedMotion()
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, if (reduced) snap() else Daylight.spatial()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 32.dp)
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 24.dp.toPx()
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 图形锚点：parchment 圆底 + 叠放双卡（后卡浅灰、前卡 Action Blue）——相册整理意象，禁渐变禁阴影
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Daylight.parchment),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = 5.dp, y = (-5).dp)
                    .size(24.dp, 30.dp)
                    .clip(RoundedCornerShape(Daylight.radiusXs))
                    .background(Daylight.checkRing),
            )
            Box(
                modifier = Modifier
                    .offset(x = (-5).dp, y = 5.dp)
                    .size(24.dp, 30.dp)
                    .clip(RoundedCornerShape(Daylight.radiusXs))
                    .background(Daylight.primary),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "把相册留给真正想看的照片",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = Daylight.ink,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "需要媒体权限才能整理；所有内容只在这台手机上读取，不会上传。",
            style = MaterialTheme.typography.bodyLarge,
            color = Daylight.inkSub,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        PillButton(text = "去授权", onClick = onGrant, modifier = Modifier.fillMaxWidth())
        if (onCultivation != null) androidx.compose.material3.TextButton(onClick = onCultivation) { Text("先去修仙 · 无需相册权限") }
        androidx.compose.material3.TextButton(onClick = onSettings) { Text("设置") }
    }
}

/** FULL / PARTIAL 主界面：常驻 partial 横幅（三分支之外）+ 板块切换条 / 回收站 / 设置 push。 */
@Composable
private fun MainContent(
    container: AppContainer,
    gestureHints: GestureHintViewModel,
    scope: VisibleScope,
    onGrantAll: () -> Unit,
) {
    // 以 name 字符串保存，规避 enum 的 Bundle 可存性差异
    var boardName by rememberSaveable { mutableStateOf(Board.PHOTO.name) }
    val board = Board.valueOf(boardName)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var showCultivation by rememberSaveable { mutableStateOf(false) }
    val imageBoardsMerged by container.settings.imageBoardsMerged.collectAsState(initial = true)
    // 统一图片板块（PRD §3.1）：分板遗留的截图/已保存选中态归位「照片」
    LaunchedEffect(imageBoardsMerged, boardName) {
        if (imageBoardsMerged && board != Board.PHOTO && board != Board.VIDEO) boardName = Board.PHOTO.name
    }

    // 系统栏随页面动态配色（P0-2）：视频板黑底沉浸 → 浅色图标；其余页面 → 深色图标
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(board, showSettings, showTrash, showCultivation) {
        val onVideoBoard = board == Board.VIDEO && !showSettings && !showTrash && !showCultivation
        activity?.enableEdgeToEdge(
            statusBarStyle = if (onVideoBoard) {
                SystemBarStyle.dark(android.graphics.Color.BLACK)
            } else {
                SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE)
            },
            navigationBarStyle = if (onVideoBoard) {
                SystemBarStyle.dark(android.graphics.Color.BLACK)
            } else {
                SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE)
            },
        )
    }

    val haptics = rememberHaptics()
    val reducedMotion = rememberReducedMotion()
    // 页面键：子页（设置/回收站）与板块页三分支；板块内切换不触发转场
    val page = when {
        showCultivation -> "cultivation"
        showTrash -> "trash"
        showSettings -> "settings"
        else -> "board"
    }

    // iOS 推入式导航（P0-3）：子页自右滑入 + 底页左移 1/4 parallax，返回镜像；
    // 系统侧滑返回手势触发 BackHandler → 同一弹出转场（与 iOS 左缘拖拽返回同路径）
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            when {
                reducedMotion -> fadeIn(snap()) togetherWith fadeOut(snap())
                initialState == "board" && targetState != "board" ||
                    initialState != "board" && targetState != "board" ->
                    (slideInHorizontally(Daylight.drawer()) { it } + fadeIn(Daylight.settle())) togetherWith
                        (slideOutHorizontally(Daylight.drawer()) { -it / 4 } + fadeOut(Daylight.settle()))
                else ->
                    (slideInHorizontally(Daylight.drawer()) { -it / 4 } + fadeIn(Daylight.settle())) togetherWith
                        (slideOutHorizontally(Daylight.drawer()) { it } + fadeOut(Daylight.settle()))
            }
        },
        label = "page-nav",
    ) { target ->
        when (target) {
            "cultivation" -> dev.sxc.kadaishan.ui.cultivation.CultivationScreen(container.cultivation, onBack = { showCultivation = false })
            "trash" -> {
                BackHandler { showTrash = false }
                TrashScreen(container.mediaDeleter, onBack = { showTrash = false })
            }
            "settings" -> {
                BackHandler { showSettings = false }
                SettingsScreen(container.settings, onBack = { showSettings = false }, cultivation = container.cultivation, onCultivation = { showCultivation = true })
            }
            else -> Box(modifier = Modifier.fillMaxSize()) {
                // 内容全幅（查看器式）：子页/板块自管 insets——视频板黑色满屏沉浸，图片板白画布自留顶部余量。
                // chrome 悬浮于内容之上，不占布局位：显隐不引起内容重排。
                // 查看器式收纳：单击切换 chrome（图片板）；干活（手势/翻页）自动收起
                var chromeVisible by rememberSaveable { mutableStateOf(true) }
                val onToggleChrome: () -> Unit = { chromeVisible = !chromeVisible }
                val onInteract: () -> Unit = { chromeVisible = false }
                if (board == Board.VIDEO) {
                    VideoCardScreen(
                        container.mediaSource,
                        container.settings,
                        container.exifReader,
                        container.mediaDeleter,
                        container.pendingSettlement,
                        container.reviewHistory,
                        gestureHints = gestureHints,
                        hintActive = page == "board",
                        onOpenTrash = { showTrash = true },
                        onToggleChrome = onToggleChrome,
                        onInteract = onInteract,
                        cultivationRounds = container.cultivationRounds,
                    )
                } else {
                    PhotoCardScreen(
                        board,
                        container.mediaSource,
                        container.settings,
                        container.exifReader,
                        container.mediaDeleter,
                        container.pendingSettlement,
                        container.reviewHistory,
                        gestureHints = gestureHints,
                        hintActive = page == "board",
                        onOpenTrash = { showTrash = true },
                        onToggleChrome = onToggleChrome,
                        onInteract = onInteract,
                        cultivationRounds = container.cultivationRounds,
                    )
                }

                // 悬浮 chrome 层：PARTIAL 横幅常驻（PRD §5.4）+ 板块栏 + 收纳把手
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                ) {
                    if (scope == VisibleScope.PARTIAL) {
                        PartialBanner(
                            onGrantAll = onGrantAll,
                            modifier = Modifier.statusBarsPadding(),
                        )
                    }
                    AnimatedVisibility(
                        visible = chromeVisible,
                        enter = slideInVertically(Daylight.settle()) { -it } + fadeIn(Daylight.settle()),
                        exit = slideOutVertically(Daylight.settle()) { -it } + fadeOut(Daylight.settle()),
                    ) {
                        FloatingBoardBar(
                            board = board,
                            tabs = boardTabs(imageBoardsMerged),
                            onBoardChange = {
                                haptics.tick()
                                boardName = it.name
                            },
                            onSettings = { showSettings = true },
                            onTrash = { showTrash = true },
                            modifier = Modifier.statusBarsPadding(),
                        )
                    }
                    // 收纳把手：chrome 收起时才出现（半透圆片，图片白画布 / 视频黑底均可辨）；
                    // 视频板点按 = 暂停（PRD），把手是其唯一的 chrome 唤回通道
                    AnimatedVisibility(visible = !chromeVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(top = 6.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp, 48.dp)
                                    .clickable {
                                        haptics.tick()
                                        chromeVisible = true
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp, 5.dp)
                                        .clip(RoundedCornerShape(Daylight.pillRadius))
                                        .background(Daylight.chipTranslucent),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 板块标签（PRD §3.1）：统一模式 = 照片/视频 两板；分开模式 = 图片三板 + 视频。 */
private fun boardTabs(merged: Boolean): List<Pair<Board, String>> =
    if (merged) {
        listOf(Board.PHOTO to "照片", Board.VIDEO to "视频")
    } else {
        listOf(Board.PHOTO to "照片", Board.SCREENSHOT to "截图", Board.SAVED to "已保存", Board.VIDEO to "视频")
    }

/** 悬浮板块栏（半透白一行：分段页签承板名 + 回收站/设置文字键）——内容不为其让位。 */
@Composable
private fun FloatingBoardBar(
    board: Board,
    tabs: List<Pair<Board, String>>,
    onBoardChange: (Board) -> Unit,
    onSettings: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(Daylight.radiusLg))
            .background(Daylight.chromeSurface)
            .border(1.dp, Daylight.hairline, RoundedCornerShape(Daylight.radiusLg))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegTabs(
            labels = tabs.map { it.second },
            selectedIndex = tabs.indexOfFirst { it.first == board }.coerceAtLeast(0),
            onSelect = { onBoardChange(tabs[it].first) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(1.dp, 24.dp)
                .background(Daylight.hairline),
        )
        ChromeTextButton(text = "回收站", onClick = onTrash)
        ChromeTextButton(text = "设置", onClick = onSettings)
    }
}

/** 悬浮栏文字键：kit TextActionButton（Action Blue 14sp、48dp 触达、按压渐隐、内置 tap 回执）。 */
@Composable
private fun ChromeTextButton(text: String, onClick: () -> Unit) =
    TextActionButton(text = text, onClick = onClick)

/** PARTIAL：顶部常驻横幅（PRD §5.4）：浅红容器药丸 + 行内改授动作，悬浮于内容之上。 */
@Composable
private fun PartialBanner(onGrantAll: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(Daylight.radiusMd))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 14.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "仅授权了部分照片视频，整理不完整",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        TextActionButton(
            text = "改授全部",
            onClick = onGrantAll,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
