package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class GutterProfileCutTest {

    /**
     * Seite mit [bg]-Hintergrund; jedes Panel wird mit deterministischem Schachbrett-Rauschen
     * gefüllt (hohe Luminanz-Varianz = echte Art). Solide Füllung wäre ununterscheidbar von
     * einem Gutter, daher das Muster. Die Mitteltöne (90/170) liegen bewusst zwischen Dunkel-
     * und Hell-Gutter-Schwelle, damit der Content-Trim die Art als echten Bildinhalt erkennt.
     */
    private fun texturedPage(w: Int, h: Int, panels: List<PanelRect>, bg: Int): RenderedPage {
        val px = IntArray(w * h) { bg }
        for (p in panels) {
            for (y in p.y until p.y + p.height) for (x in p.x until p.x + p.width) {
                if (x in 0 until w && y in 0 until h) {
                    val on = (x / 4 + y / 4) % 2 == 0
                    px[y * w + x] = if (on) 0xFF5A5A5A.toInt() else 0xFFAAAAAA.toInt()
                }
            }
        }
        return RenderedPage(w, h, px)
    }

    private fun matches(boxes: List<PanelRect>, target: PanelRect, tol: Int = 14): Boolean =
        boxes.any {
            kotlin.math.abs(it.x - target.x) <= tol && kotlin.math.abs(it.y - target.y) <= tol &&
                kotlin.math.abs(it.width - target.width) <= tol && kotlin.math.abs(it.height - target.height) <= tol
        }

    @Test
    fun `weiße Gutter trennen zwei nebeneinander liegende Panels`() {
        val left = PanelRect(20, 20, 440, 760)
        val right = PanelRect(540, 20, 440, 760)
        val page = texturedPage(1000, 800, listOf(left, right), bg = 0xFFFFFFFF.toInt())

        val panels = GutterProfileCut.detect(page)

        assertTrue(panels.size >= 2, "erwartet >=2 Panels, war ${panels.size}")
        assertTrue(matches(panels, left), "linkes Panel nicht gefunden: $panels")
        assertTrue(matches(panels, right), "rechtes Panel nicht gefunden: $panels")
    }

    @Test
    fun `dunkle Gutter (dunkel-auf-dunkel) trennen gestapelte Panels`() {
        val top = PanelRect(20, 20, 960, 360)
        val bottom = PanelRect(20, 440, 960, 340)
        // Schwarzer Hintergrund = dunkle Gasse; Flood würde hier nichts trennen.
        val page = texturedPage(1000, 800, listOf(top, bottom), bg = 0xFF050505.toInt())

        val panels = GutterProfileCut.detect(page)

        assertTrue(panels.size >= 2, "erwartet >=2 Panels bei dunkler Gasse, war ${panels.size}")
        assertTrue(matches(panels, top), "oberes Panel nicht gefunden: $panels")
        assertTrue(matches(panels, bottom), "unteres Panel nicht gefunden: $panels")
    }

    @Test
    fun `2x2-Grid wird rekursiv in vier Panels geschnitten`() {
        val tl = PanelRect(20, 20, 440, 360)
        val tr = PanelRect(540, 20, 440, 360)
        val bl = PanelRect(20, 440, 440, 340)
        val br = PanelRect(540, 440, 440, 340)
        val page = texturedPage(1000, 800, listOf(tl, tr, bl, br), bg = 0xFFFFFFFF.toInt())

        val panels = GutterProfileCut.detect(page)

        assertTrue(panels.size >= 4, "erwartet >=4 Panels (rekursiver Cut beider Achsen), war ${panels.size}")
        listOf(tl, tr, bl, br).forEach { assertTrue(matches(panels, it), "Panel $it nicht gefunden: $panels") }
    }

    @Test
    fun `kreuzende Sprechblase im Gutter verhindert die Trennung nicht`() {
        val left = PanelRect(20, 20, 440, 760)
        val right = PanelRect(540, 20, 440, 760)
        // Sprechblase ragt über die Gasse (x 460..540) in einem schmalen Höhenband — die
        // Kantendichte der Gutter-Spalten bleibt über die volle Höhe niedrig (tolerant).
        val bubble = PanelRect(460, 360, 80, 80)
        val page = texturedPage(1000, 800, listOf(left, right, bubble), bg = 0xFFFFFFFF.toInt())

        val panels = GutterProfileCut.detect(page)

        // Entscheidend: trotz kreuzender Blase findet der Cut weiterhin zwei nebeneinander
        // liegende Panels (die Blase darf die Trenngrenze verschieben, aber nicht aufheben).
        assertTrue(panels.size >= 2, "kreuzende Blase verhinderte die Trennung: $panels")
        assertTrue(panels.any { it.centerX < 500 }, "kein linkes Panel: $panels")
        assertTrue(panels.any { it.centerX > 500 }, "kein rechtes Panel: $panels")
    }

    @Test
    fun `leere Seite liefert keine Panels`() {
        val page = RenderedPage(400, 400, IntArray(400 * 400) { 0xFF808080.toInt() })
        assertTrue(GutterProfileCut.detect(page).isEmpty())
    }
}
