package dev.sxc.kadaishan

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.VisibleScope
import dev.sxc.kadaishan.ui.AppTheme
import dev.sxc.kadaishan.ui.boards.PhotoCardScreen
import dev.sxc.kadaishan.ui.boards.VideoCardScreen
import dev.sxc.kadaishan.ui.settings.SettingsScreen

/** 单 Activity 入口：edge-to-edge + 顶层组装（权限三分支 / 板块 / 设置 push）。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = AppContainer(applicationContext)
        setContent {
            AppTheme {
                AppRoot(container)
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

@Composable
fun AppRoot(container: AppContainer) {
    val context = LocalContext.current
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
        when (scope) {
            VisibleScope.DENIED -> DeniedGuide(
                onGrant = {
                    settingsFallback = false
                    permissionLauncher.launch(ALL_MEDIA_PERMISSIONS)
                },
            )

            else -> MainContent(
                container = container,
                scope = scope,
                onGrantAll = {
                    settingsFallback = true
                    permissionLauncher.launch(ALL_MEDIA_PERMISSIONS)
                },
            )
        }
    }
}

/** DENIED：全屏说明 + 「去授权」。 */
@Composable
private fun DeniedGuide(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "需要媒体权限才能整理相册（仅本地读取，不上传）",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onGrant) {
            Text("去授权")
        }
    }
}

/** FULL / PARTIAL 主界面：板块切换条 + 常驻 partial 横幅 + 板块内容 / 设置 push。 */
@Composable
private fun MainContent(
    container: AppContainer,
    scope: VisibleScope,
    onGrantAll: () -> Unit,
) {
    // 以 name 字符串保存，规避 enum 的 Bundle 可存性差异
    var boardName by rememberSaveable { mutableStateOf(Board.PHOTO.name) }
    val board = Board.valueOf(boardName)
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        BackHandler { showSettings = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            SettingsScreen(container.settings, onBack = { showSettings = false })
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            BoardBar(
                board = board,
                onBoardChange = { boardName = it.name },
                onSettings = { showSettings = true },
            )
            if (scope == VisibleScope.PARTIAL) {
                PartialBanner(onGrantAll = onGrantAll)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (board == Board.VIDEO) {
                    VideoCardScreen(
                        container.mediaSource,
                        container.settings,
                        container.exifReader,
                        container.mediaDeleter,
                        container.pendingSettlement,
                    )
                } else {
                    PhotoCardScreen(
                        board,
                        container.mediaSource,
                        container.settings,
                        container.exifReader,
                        container.mediaDeleter,
                        container.pendingSettlement,
                    )
                }
            }
        }
    }
}

private val BOARD_TABS = listOf(
    Board.PHOTO to "删照片",
    Board.SCREENSHOT to "删截图",
    Board.SAVED to "删已保存图片",
    Board.VIDEO to "删视频",
)

@Composable
private fun BoardBar(
    board: Board,
    onBoardChange: (Board) -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BOARD_TABS.forEach { (b, label) ->
            TextButton(onClick = { onBoardChange(b) }) {
                Text(
                    text = label,
                    color = if (b == board) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onSettings) {
            Text("设置")
        }
    }
}

/** PARTIAL：顶部常驻横幅。 */
@Composable
private fun PartialBanner(onGrantAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "仅授权了部分照片视频，整理不完整",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        TextButton(onClick = onGrantAll) {
            Text("改授全部", color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}
