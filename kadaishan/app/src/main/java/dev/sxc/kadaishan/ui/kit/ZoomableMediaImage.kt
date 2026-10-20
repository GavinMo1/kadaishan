package dev.sxc.kadaishan.ui.kit

import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.sxc.kadaishan.model.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Shared fit/pinch/double-tap surface, without board margins, progress or navigation. */
@Composable
fun ZoomableMediaImage(
    item: MediaItem,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    onZoomChange: (Boolean) -> Unit = {},
) {
    var failed by remember(item.uri) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    val zoomSpec = if (reduced) snap<Float>() else Daylight.spatial<Float>()
    val scale = remember(item.uri) { Animatable(1f) }
    val panX = remember(item.uri) { Animatable(0f) }
    val panY = remember(item.uri) { Animatable(0f) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 双击动画 Job：捏合起手即取消（手势接管变换，避免两源打架）
    var tapAnimJob by remember(item.uri) { mutableStateOf<Job?>(null) }

    // ContentScale.Fit 下影像的实际显示尺寸（信箱式留白不计入平移边界）
    fun displayedSize(): Pair<Float, Float> {
        return fittedImageSize(viewSize.width.toFloat(), viewSize.height.toFloat(), item.width, item.height)
    }

    fun clampPan(s: Float, x: Float, y: Float): Pair<Float, Float> {
        val (dw, dh) = displayedSize()
        return clampedImagePan(viewSize.width.toFloat(), viewSize.height.toFloat(), dw, dh, s, x, y)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            // 捏合缩放 + 放大态平移：≥2 指即入缩放（消费位移）；单指仅在 >1x 时接管平移，
            // 1x 下全程不消费 → 外层滑卡手势不受影响。
            // 受限手势作用域不能调挂起 snapTo：本地变量做权威计算，异步推值进 Animatable 渲染
            //（同 scope 顺序执行保序；逐帧直读本地变量无延迟）。
            .pointerInput(item.uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    tapAnimJob?.cancel()
                    var curS = scale.value
                    var curX = panX.value
                    var curY = panY.value
                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val cx0 = viewSize.width / 2f
                            val cy0 = viewSize.height / 2f
                            val oldS = curS
                            curS = (curS * zoom).coerceIn(1f, 4f)
                            val factor = if (oldS > 0f) curS / oldS else 1f
                            // 焦点不动：centroid 在缩放前后映射到同一屏幕点，再叠加双指整体平移
                            curX = centroid.x - cx0 - (centroid.x - cx0 - curX) * factor + pan.x
                            curY = centroid.y - cy0 - (centroid.y - cy0 - curY) * factor + pan.y
                            val (px, py) = clampPan(curS, curX, curY)
                            curX = px
                            curY = py
                            val s2 = curS
                            val x2 = curX
                            val y2 = curY
                            scope.launch {
                                scale.snapTo(s2)
                                panX.snapTo(x2)
                                panY.snapTo(y2)
                            }
                            pressed.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                            onZoomChange(curS > 1.01f)
                        } else if (pressed.size == 1 && curS > 1f) {
                            val change = pressed.first()
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                val (px, py) = clampPan(curS, curX + delta.x, curY + delta.y)
                                curX = px
                                curY = py
                                val x2 = curX
                                val y2 = curY
                                scope.launch {
                                    panX.snapTo(x2)
                                    panY.snapTo(y2)
                                }
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    onZoomChange(curS > 1.01f)
                }
            }
            // 双击 2.5x 放大 / 还原（不消费事件，外层单击切换 chrome 语义不变——
            // 双击序列 = 两次 onTap 相互抵消 + 一次 onDoubleTap，chrome 终态不变）
            .pointerInput(item.uri) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        haptics.tap()
                        if (scale.value > 1.01f) {
                            onZoomChange(false)
                            tapAnimJob = scope.launch {
                                launch { scale.animateTo(1f, zoomSpec) }
                                launch { panX.animateTo(0f, zoomSpec) }
                                panY.animateTo(0f, zoomSpec)
                            }
                        } else {
                            val target = 2.5f
                            val cx0 = viewSize.width / 2f
                            val cy0 = viewSize.height / 2f
                            val factor = target / scale.value
                            // 放大后以点击点为焦点
                            val nx = pos.x - cx0 - (pos.x - cx0 - panX.value) * factor
                            val ny = pos.y - cy0 - (pos.y - cy0 - panY.value) * factor
                            val (px, py) = clampPan(target, nx, ny)
                            onZoomChange(true)
                            tapAnimJob = scope.launch {
                                launch { scale.animateTo(target, zoomSpec) }
                                launch { panX.animateTo(px, zoomSpec) }
                                panY.animateTo(py, zoomSpec)
                            }
                        }
                    },
                )
            },
    ) {
        AsyncImage(
            // crossfade(200) + parchment 占位（占位挂在 ImageRequest 上：
            // AsyncImage.placeholder 只收 Painter，request.placeholder 会在 Loading 态透出），
            // 卡面 radiusMd 裁圆；contentDescription = displayName（无障碍朗读文件名）
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.uri)
                .placeholder(ColorDrawable(Daylight.parchment.toArgb()))
                .crossfade(200)
                .build(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            imageLoader = imageLoader,
            onError = { failed = true },
            onSuccess = { failed = false },
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    // 惰性读取动画状态：捏合/平移逐帧不重组
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = panX.value
                    translationY = panY.value
                },
        )
        if (failed) androidx.compose.material3.Text(
            "无法读取这张图片",
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
            color = Daylight.ink,
        )
    }
}
