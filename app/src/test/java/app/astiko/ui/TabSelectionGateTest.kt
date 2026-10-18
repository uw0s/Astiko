package app.astiko.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tab animates only when it becomes selected from another tab. The
 * first composition and a tap on the active tab stay still.
 */
class TabSelectionGateTest {
    @Test
    fun firstCompositionOfSelectedTabDoesNotPlay() {
        assertFalse(TabSelectionGate(selected = true).shouldPlay(true))
    }

    @Test
    fun firstCompositionOfUnselectedTabDoesNotPlay() {
        assertFalse(TabSelectionGate(selected = false).shouldPlay(false))
    }

    @Test
    fun switchingToTheTabPlays() {
        val gate = TabSelectionGate(selected = false)
        assertTrue(gate.shouldPlay(true))
    }

    @Test
    fun tappingTheActiveTabDoesNotPlayAgain() {
        val gate = TabSelectionGate(selected = false)
        assertTrue(gate.shouldPlay(true))
        assertFalse(gate.shouldPlay(true))
    }

    @Test
    fun switchingAwayDoesNotPlay() {
        val gate = TabSelectionGate(selected = false)
        assertTrue(gate.shouldPlay(true))
        assertFalse(gate.shouldPlay(false))
    }

    @Test
    fun switchingBackPlaysAgain() {
        val gate = TabSelectionGate(selected = false)
        assertTrue(gate.shouldPlay(true))
        assertFalse(gate.shouldPlay(false))
        assertTrue(gate.shouldPlay(true))
    }
}
