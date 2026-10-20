package dev.sxc.kadaishan.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageZoomGeometryTest {
    @Test fun `wide image fits completely without cropping`() {
        assertEquals(1000f to 500f, fittedImageSize(1000f, 1000f, 4000, 2000))
    }
    @Test fun `portrait image retains side letterboxing`() {
        assertEquals(250f to 1000f, fittedImageSize(1000f, 1000f, 1000, 4000))
    }
    @Test fun `missing dimensions use viewport without division by zero`() {
        assertEquals(1000f to 800f, fittedImageSize(1000f, 800f, 0, 0))
        assertEquals(0f to 0f, fittedImageSize(0f, 800f, 100, 100))
    }
    @Test fun `unzoomed image stays centered despite drag`() {
        assertEquals(0f to 0f, clampedImagePan(1000f, 1000f, 1000f, 500f, 1f, 300f, -400f))
    }
    @Test fun `zoomed landscape pans only where image overflows viewport`() {
        assertEquals(500f to 0f, clampedImagePan(1000f, 1000f, 1000f, 500f, 2f, 900f, -400f))
        assertEquals(-500f to 0f, clampedImagePan(1000f, 1000f, 1000f, 500f, 2f, -900f, 400f))
    }
    @Test fun `portrait at four times clamps long axis to image edge`() {
        assertEquals(0f to -1500f, clampedImagePan(1000f, 1000f, 250f, 1000f, 4f, 900f, -3000f))
    }
}
