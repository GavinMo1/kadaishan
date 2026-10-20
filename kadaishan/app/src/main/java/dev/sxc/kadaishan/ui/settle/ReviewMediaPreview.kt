package dev.sxc.kadaishan.ui.settle

import android.content.ContextWrapper
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.model.MediaItem
import dev.sxc.kadaishan.ui.kit.Daylight
import dev.sxc.kadaishan.ui.kit.PillButton
import dev.sxc.kadaishan.ui.kit.Spinner
import dev.sxc.kadaishan.ui.kit.TextActionButton
import dev.sxc.kadaishan.ui.kit.ZoomableMediaImage
import kotlinx.coroutines.CancellationException

/** An overlay inside the review Dialog. Selection edits only the caller's draft. */
@Composable
internal fun ReviewMediaPreview(
    id: Long,
    source: MediaSource,
    imageLoader: ImageLoader,
    selected: Boolean,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var item by remember(id) { mutableStateOf<MediaItem?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var failure by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(source, id) {
        try {
            item = source.queryByIds(listOf(id), MediaStore.MATCH_EXCLUDE).firstOrNull { it.id == id }
            if (item == null) failure = "这项媒体已不可用，请返回复核"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failure = "暂时无法读取，请返回复核后重试"
        } finally { loading = false }
    }
    BackHandler(onBack = onClose)
    Column(
        modifier.background(Daylight.canvas)
            // Intercept the otherwise empty surface so taps cannot reach the underlying grid/footer.
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextActionButton("‹ 返回复核", onClick = onClose)
            Text(
                item?.displayName.orEmpty(),
                Modifier.weight(1f).padding(horizontal = 12.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Daylight.inkSub, style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Daylight.parchment),
            contentAlignment = Alignment.Center,
        ) {
            val media = item
            when {
                loading -> Spinner()
                failure != null -> Text(failure!!, Modifier.padding(24.dp), color = Daylight.inkSub)
                media?.mimeType?.startsWith("video/") == true -> ReviewVideoPreview(media)
                media?.mimeType?.startsWith("image/") == true -> ZoomableMediaImage(media, imageLoader)
                else -> Text("暂不支持预览这项媒体", color = Daylight.inkSub)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                if (selected) "已选为待删除" else "本次保留",
                color = if (selected) Daylight.danger else Daylight.inkSub,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            PillButton(
                text = if (selected) "保留这项" else "选为待删除",
                onClick = onToggle, modifier = Modifier.fillMaxWidth(),
                container = if (selected) Daylight.primary else Daylight.danger,
            )
            Spacer(Modifier.height(8.dp))
            Text("返回复核后统一确认", color = Daylight.inkSub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A short-lived, muted player; never borrows or advances the card-stream player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ReviewVideoPreview(item: MediaItem) {
    val context = LocalContext.current
    var failed by remember(item.uri) { mutableStateOf(false) }
    val lifecycleOwner = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<LifecycleOwner>().firstOrNull()
    }
    val player = remember(item.uri) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
                player.pause()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(listener)
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    if (failed) Text("无法播放这段视频，请返回复核", color = Daylight.inkSub)
    else AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                contentDescription = item.displayName
                controllerShowTimeoutMs = 0
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { it.player = player },
        onRelease = { it.player = null },
    )
}
