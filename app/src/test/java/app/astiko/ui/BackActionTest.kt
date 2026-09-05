package app.astiko.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decision table for a back press at the app root, by precedence: drill-in
 * stack (the city picker is a stack entry, so it pops like any other
 * screen), then tab position, then the double-back exit gate on the first
 * tab. The handler only executes the result. The reset side effects live
 * next to the execution.
 */
class BackActionTest {
    private val window = BACK_EXIT_WINDOW_MS

    // Fixed "current time" so every test's relative presses are deterministic.
    private val now = 10_000L

    private fun decide(
        stackSize: Int = 1,
        tab: Int = 0,
        lastBackAt: Long = 0L,
        now: Long = this.now,
    ) = backAction(stackSize, tab, lastBackAt, now, window)

    // --- Precedence -------------------------------------------------------

    @Test
    fun drillInStack_popsBeforeTabsAreTouched() {
        assertEquals(BackAction.Pop, decide(stackSize = 2, tab = 2))
        // Even on the first tab, an open drill-in screen pops first.
        assertEquals(BackAction.Pop, decide(stackSize = 2, tab = 0))
    }

    @Test
    fun rootWithStackSizeOne_neverPops() {
        assertEquals(BackAction.StepTabLeft, decide(stackSize = 1, tab = 1))
    }

    // --- Tab stepping ------------------------------------------------------

    @Test
    fun infoTab_stepsToOneLeft() {
        assertEquals(BackAction.StepTabLeft, decide(tab = 2))
    }

    @Test
    fun linesTab_stepsToOneLeft() {
        assertEquals(BackAction.StepTabLeft, decide(tab = 1))
    }

    // --- Double-back exit gate on the first tab -----------------------------

    @Test
    fun firstTabNoPriorPress_showsToast() {
        assertEquals(BackAction.ShowExitToast, decide(tab = 0, lastBackAt = 0L))
    }

    @Test
    fun firstTabPressWithinWindow_finishes() {
        assertEquals(BackAction.Finish, decide(tab = 0, lastBackAt = now - 1_000))
    }

    @Test
    fun firstTabImmediateRepeat_finishes() {
        assertEquals(BackAction.Finish, decide(tab = 0, lastBackAt = now))
    }

    @Test
    fun firstTabPressJustInsideWindow_finishes() {
        assertEquals(BackAction.Finish, decide(tab = 0, lastBackAt = now - window + 1))
    }

    @Test
    fun firstTabPressExactlyAtWindowBoundary_showsToastAgain() {
        // Strictly less than the window. Exactly at it is a fresh press.
        assertEquals(BackAction.ShowExitToast, decide(tab = 0, lastBackAt = now - window))
    }

    @Test
    fun firstTabStalePress_showsToastAgain() {
        assertEquals(BackAction.ShowExitToast, decide(tab = 0, lastBackAt = now - window - 1))
    }

    @Test
    fun customWindow_isHonored() {
        assertEquals(
            BackAction.ShowExitToast,
            backAction(
                stackSize = 1,
                tab = 0,
                lastBackAt = now - 1_500,
                now = now,
                backExitWindowMs = 1_000,
            ),
        )
    }
}
