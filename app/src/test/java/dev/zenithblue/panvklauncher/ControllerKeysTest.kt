package dev.zenithblue.panvklauncher

import com.winlator.xserver.Keyboard
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerKeysTest {
    private val keysyms = Keyboard.createKeyboard(null).keysyms

    /** Every keyboard dropdown entry has an X keycode with a keysym the injected key event carries; nothing is missing. */
    @Test fun everyKeyboardOptionIsInjectable() {
        val codes = ControllerKeys.KEYBOARD.map { n ->
            val k = ControllerKeys.key(n)
            assertNotNull("no keycode: $n", k)
            val i = ((k!!.id.toInt() and 0xff) - Keyboard.MIN_KEYCODE) * Keyboard.KEYSYMS_PER_KEYCODE
            assertTrue("keycode out of keysym table: $n", i >= 0 && i + 1 < keysyms.size)
            assertNotEquals("no keysym: $n", 0, keysyms[i])
            k
        }
        assertEquals("two options share a keycode", codes.size, codes.toSet().size)
        val all = XKeycode.values().filter { it != XKeycode.KEY_NONE && it != XKeycode.KEY_MAX }.toSet()
        assertEquals("keycodes missing from the dropdown", all, codes.toSet())
        assertEquals(116, ControllerKeys.KEYBOARD.size)
        assertEquals(listOf(0xFFCA, 0xFFD5), listOf("F13", "F24").map { keysyms[(ControllerKeys.key(it)!!.id - 8) * 2] })
    }

    @Test fun optionsPerType() {
        val mouse = ControllerKeys.options("mouse").flatMap { it.second }
        assertEquals(13, mouse.size)
        assertTrue(mouse.all { ControllerKeys.mouse(it) != null || it in ControllerKeys.MOUSE_AXES })
        val pad = ControllerKeys.options("gamepad").flatMap { it.second }
        assertEquals(26, pad.size); assertEquals("gamepad", pad[0])
        assertEquals("Same (A)", ControllerKeys.describe("gamepad", "A"))
        assertEquals("Left stick up", ControllerKeys.describe("pad:LS_UP"))
        assertTrue(ControllerKeys.options("keyboard").flatMap { it.second } == ControllerKeys.KEYBOARD)
        // Legacy names and aliases still resolve.
        listOf("w", "Escape", "esc", "Shift_L", "Prior", "pagedown", "Return", "enter").forEach { assertNotNull(it, ControllerKeys.key(it)) }
        listOf("space", "mouse:wheelleft", "pad:B", "gamepad", "none").forEach {
            assertEquals(it, ControllerKeys.options(ControllerKeys.type(it)).flatMap { g -> g.second }.firstOrNull { o -> o == it } ?: "none")
        }
    }
}
