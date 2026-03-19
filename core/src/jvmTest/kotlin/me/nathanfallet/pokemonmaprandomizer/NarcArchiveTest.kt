package me.nathanfallet.pokemonmaprandomizer

import dev.kotlinds.NarcArchive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NarcArchiveTest {
    @Test
    fun `unpack scr_seq narc returns non-empty file list`() {
        val bytes = javaClass.classLoader.getResourceAsStream("scr_seq.narc")!!.readBytes()
        val files = NarcArchive.unpack(bytes)
        assertTrue(files.isNotEmpty(), "Expected at least one file in scr_seq.narc")
    }

    @Test
    fun `unpack pack unpack scr_seq narc preserves files`() {
        val bytes = javaClass.classLoader.getResourceAsStream("scr_seq.narc")!!.readBytes()
        val files = NarcArchive.unpack(bytes)
        val repacked = NarcArchive.pack(files)
        val unpacked2 = NarcArchive.unpack(repacked)

        assertEquals(files.size, unpacked2.size, "File count changed after repack")
        for (i in files.indices) {
            assertTrue(files[i].contentEquals(unpacked2[i]), "File $i differs after repack")
        }
    }
}
