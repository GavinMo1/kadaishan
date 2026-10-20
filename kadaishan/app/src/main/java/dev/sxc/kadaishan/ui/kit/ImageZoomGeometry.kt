package dev.sxc.kadaishan.ui.kit

internal fun fittedImageSize(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): Pair<Float, Float> {
    if (viewWidth <= 0 || viewHeight <= 0) return 0f to 0f
    if (imageWidth <= 0 || imageHeight <= 0) return viewWidth to viewHeight
    val factor = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    return imageWidth * factor to imageHeight * factor
}

internal fun clampedImagePan(viewWidth: Float, viewHeight: Float, displayedWidth: Float, displayedHeight: Float, scale: Float, x: Float, y: Float): Pair<Float, Float> {
    val maxX = maxOf(0f, (displayedWidth * scale - viewWidth) / 2f)
    val maxY = maxOf(0f, (displayedHeight * scale - viewHeight) / 2f)
    // Lock a fitting axis to canonical zero; coerceIn(-0f, 0f) otherwise preserves -0f.
    val px = if (maxX == 0f) 0f else x.coerceIn(-maxX, maxX)
    val py = if (maxY == 0f) 0f else y.coerceIn(-maxY, maxY)
    return px to py
}
