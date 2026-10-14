package net.mrphan.calibre.reader.epub.curl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlMathTest {

    @Test
    fun `dragX = 0 thi trang phang hoan toan`() {
        val geometry = PageCurlMath.compute(dragX = 0f, pageWidth = 1000f, pageHeight = 1600f)
        assertEquals(1000f, geometry.foldX, 0.01f)
        assertEquals(0f, geometry.curlAngle, 0.01f)
    }

    @Test
    fun `dragX = pageWidth thi da lat het`() {
        val geometry = PageCurlMath.compute(dragX = 1000f, pageWidth = 1000f, pageHeight = 1600f)
        assertEquals(0f, geometry.foldX, 0.01f)
        assertEquals(Math.PI.toFloat(), geometry.curlAngle, 0.01f)
    }

    @Test
    fun `radius luon duong va nam trong khoang cho phep`() {
        for (fraction in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
            val geometry = PageCurlMath.compute(dragX = 1000f * fraction, pageWidth = 1000f, pageHeight = 1600f)
            assertTrue(geometry.radius > 0f)
            assertTrue(geometry.radius <= 1000f * 0.16f)
        }
    }

    @Test
    fun `touchYFraction = 0,5 (mac dinh) thi duong gap thang dung - khong nghieng`() {
        val geometry = PageCurlMath.compute(dragX = 500f, touchYFraction = 0.5f, pageWidth = 1000f, pageHeight = 1600f)
        assertEquals(geometry.foldTopX, geometry.foldBottomX, 0.01f)
        assertEquals(geometry.foldX, geometry.foldTopX, 0.01f)
    }

    @Test
    fun `keo tu gan mep tren thi mep tren cuon nhieu hon (foldTopX nho hon foldBottomX)`() {
        val geometry = PageCurlMath.compute(dragX = 500f, touchYFraction = 0.0f, pageWidth = 1000f, pageHeight = 1600f)
        assertTrue(geometry.foldTopX < geometry.foldBottomX)
    }

    @Test
    fun `keo tu gan mep duoi thi mep duoi cuon nhieu hon (foldBottomX nho hon foldTopX)`() {
        val geometry = PageCurlMath.compute(dragX = 500f, touchYFraction = 1.0f, pageWidth = 1000f, pageHeight = 1600f)
        assertTrue(geometry.foldBottomX < geometry.foldTopX)
    }

    @Test
    fun `dragX = 0 hoac = pageWidth thi khong nghieng du touchYFraction the nao (bulge ve 0)`() {
        val atStart = PageCurlMath.compute(dragX = 0f, touchYFraction = 0f, pageWidth = 1000f, pageHeight = 1600f)
        val atEnd = PageCurlMath.compute(dragX = 1000f, touchYFraction = 1f, pageWidth = 1000f, pageHeight = 1600f)
        assertEquals(atStart.foldTopX, atStart.foldBottomX, 0.01f)
        assertEquals(atEnd.foldTopX, atEnd.foldBottomX, 0.01f)
    }

    @Test
    fun `tiltStrengthOf = 0 khi ngon tay dung giua canh, tang dan ve 1 khi ra sat mep`() {
        assertEquals(0f, PageCurlMath.tiltStrengthOf(0.5f), 0.001f)
        assertEquals(1f, PageCurlMath.tiltStrengthOf(0f), 0.001f)
        assertEquals(1f, PageCurlMath.tiltStrengthOf(1f), 0.001f)
        assertEquals(0.6f, PageCurlMath.tiltStrengthOf(0.8f), 0.001f)
        // Doi xung 2 phia cua giua canh.
        assertEquals(PageCurlMath.tiltStrengthOf(0.2f), PageCurlMath.tiltStrengthOf(0.8f), 0.001f)
    }

    @Test
    fun `rowCountFor tra ve 1 khi keo gan nhu thang dung - re nhu ban cu, khong them dong nao`() {
        assertEquals(1, PageCurlMath.rowCountFor(0.5f))
    }

    @Test
    fun `rowCountFor tang dan khi ngon tay ra gan mep tren hoac duoi`() {
        assertTrue(PageCurlMath.rowCountFor(0.1f) > 1)
        assertTrue(PageCurlMath.rowCountFor(0f) >= PageCurlMath.rowCountFor(0.3f))
    }

    @Test
    fun `rowLocalDragFraction = 1 cho MOI dong khi ngon tay dung giua canh (khong doi hanh vi cu)`() {
        for (rowY in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(1f, PageCurlMath.rowLocalDragFraction(rowY, touchYFraction = 0.5f), 0.001f)
        }
    }

    @Test
    fun `rowLocalDragFraction = 1 dung tai dong trung ngon tay, nho dan khi cang xa`() {
        val atTouch = PageCurlMath.rowLocalDragFraction(rowYFraction = 0f, touchYFraction = 0f)
        val near = PageCurlMath.rowLocalDragFraction(rowYFraction = 0.2f, touchYFraction = 0f)
        val far = PageCurlMath.rowLocalDragFraction(rowYFraction = 1f, touchYFraction = 0f)
        assertEquals(1f, atTouch, 0.001f)
        assertTrue(far < near)
        assertTrue(near < atTouch || near == atTouch)
        assertTrue(far in 0f..1f)
    }
}
