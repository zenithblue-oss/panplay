package dev.zenithblue.panvklauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegFileTest {
    private val v5 = "Windows Registry Editor Version 5.00\r\n\r\n[HKEY_CURRENT_USER\\Software]"

    @Test fun acceptsKnownHeaders() {
        assertTrue(ContainerManager.isRegFile(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + v5.toByteArray(Charsets.UTF_16LE).copyOf(78)))
        assertTrue(ContainerManager.isRegFile(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + v5.toByteArray()))
        assertTrue(ContainerManager.isRegFile(v5.toByteArray()))
        assertTrue(ContainerManager.isRegFile("REGEDIT4\r\n".toByteArray()))
    }

    @Test fun rejectsOthers() {
        assertFalse(ContainerManager.isRegFile("MZ\u0090\u0000".toByteArray()))
        assertFalse(ContainerManager.isRegFile(ByteArray(0)))
        assertFalse(ContainerManager.isRegFile(v5.toByteArray(Charsets.UTF_16LE))) // UTF-16 without BOM
    }
}
