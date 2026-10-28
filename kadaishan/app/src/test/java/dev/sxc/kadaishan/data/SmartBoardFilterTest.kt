package dev.sxc.kadaishan.data

import dev.sxc.kadaishan.domain.classify.classifyFields
import dev.sxc.kadaishan.model.*
import org.junit.Assert.*
import org.junit.Test

class SmartBoardFilterTest {
    @Test fun `smart and old selection share merged and split image candidate sets`() {
        val samples = listOf(
            Triple("DCIM/Camera", "Camera", "IMG_001.jpg"),
            Triple("Pictures/截屏", "截屏", "001.png"),
            Triple("Pictures/Screenshots/", "Screenshots", "002.png"),
            Triple("Download", "Download", "photo.jpg"),
        ).map { classifyFields(it.first, it.second, it.third) }
        for (sort in listOf(SortSpec.Random, SortSpec.Smart)) {
            fun matches(board: Board, merged: Boolean): List<ImageCategory> {
                val target = imageTarget(MediaQuery(board, sort, 0, VisibleScope.PARTIAL, merged))
                return samples.filter { target == null || target == it }
            }
            assertEquals(samples, matches(Board.PHOTO, true))
            assertEquals(listOf(ImageCategory.CAMERA), matches(Board.PHOTO, false))
            assertEquals(listOf(ImageCategory.SCREENSHOT, ImageCategory.SCREENSHOT), matches(Board.SCREENSHOT, false))
            assertEquals(listOf(ImageCategory.SAVED), matches(Board.SAVED, false))
        }
    }
}
