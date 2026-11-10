package io.embodit.klotskibox.ui.board

import io.embodit.klotskibox.domain.Layout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * acceptance.mustPass M9 (design spatialLayout.counterPlate): every plate line, in both languages and at its widest
 * (3-digit step counts), measures <= 944 px at 80 px. JVM stand-in for Android's Paint: the AWT serif bold face at the
 * same size (CJK falls back to the system CJK serif); the app also logs OVERFLOW plate/text at runtime and shrinks.
 */
class PlateTextTest {
    private fun strings(dir: String): Map<String, String> {
        val f = listOf("src/main/res/$dir/strings.xml", "app/src/main/res/$dir/strings.xml").map { File(it) }.first { it.exists() }
        return Regex("<string name=\"(\\w+)\">(.*?)</string>").findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** java.awt through reflection (the unit-test compile classpath is android.jar, the JVM running it has AWT). */
    private fun width(s: String): Double {
        System.setProperty("java.awt.headless", "true")
        val fontC = Class.forName("java.awt.Font")
        val frcC = Class.forName("java.awt.font.FontRenderContext")
        val atC = Class.forName("java.awt.geom.AffineTransform")
        val font = fontC.getConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance("Serif", 1, 80)
        val frc = frcC.getConstructor(atC, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).newInstance(null, true, true)
        val r = fontC.getMethod("getStringBounds", String::class.java, frcC).invoke(font, s, frc)
        return r.javaClass.getMethod("getWidth").invoke(r) as Double
    }

    @Test fun everyPlateLineFitsInBothLanguages() {
        for (dir in listOf("values", "values-zh")) {
            val s = strings(dir)
            val lines = listOf(
                s["plate_moves"]!!.replace("%1\$d", "999").replace("%2\$d", "103"),
                s["plate_solved"]!!.replace("%1\$d", "999"),
                s["plate_pinch"]!!, s["plate_restored"]!!,
            )
            for (l in lines) {
                val w = width(l)
                println("$dir \"$l\" ${w.toInt()} px")
                assertTrue("$dir \"$l\" $w px > ${Layout.PLATE_TEXT_PX}", w <= Layout.PLATE_TEXT_PX)
            }
        }
    }
}
