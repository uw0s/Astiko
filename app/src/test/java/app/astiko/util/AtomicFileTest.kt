package app.astiko.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The temp-file-plus-rename write the offline cache and the OSETh GTFS
 * catalog share. The contract that matters is the failure one: a write
 * that dies must leave the previous file as it was, and a leftover temp
 * file must never sit next to a real one.
 */
class AtomicFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writes the target and leaves no temp file behind`() {
        val dir = tmp.newFolder()
        val target = File(dir, "entry.json")

        writeAtomically(target, "one".toByteArray())
        assertEquals("one", target.readText())

        // A second write replaces it, the rename is the only visible step.
        writeAtomically(target, "two".toByteArray())
        assertEquals("two", target.readText())
        val written =
            dir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .sorted()
        assertEquals(listOf("entry.json"), written)
    }

    @Test
    fun `a failing write leaves the previous content untouched`() {
        val dir = tmp.newFolder()
        val target = File(dir, "entry.json")
        writeAtomically(target, "keep".toByteArray())

        // A directory where the temp file goes makes the write fail.
        assertTrue(File(dir, "entry.json.tmp").mkdirs())

        runCatching { writeAtomically(target, "clobber".toByteArray()) }
            .onSuccess { throw AssertionError("expected the write to fail") }
            .onFailure { assertTrue(it is IOException) }

        assertEquals("keep", target.readText())
    }

    @Test
    fun `writes into a directory that does not exist yet`() {
        val dir = File(tmp.newFolder(), "nested/deeper")
        val target = File(dir, "entry.json")

        writeAtomically(target, "created".toByteArray())

        assertEquals("created", target.readText())
    }
}
