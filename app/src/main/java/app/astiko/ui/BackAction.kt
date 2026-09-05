package app.astiko.ui

internal const val BACK_EXIT_WINDOW_MS = 2_000L

/** What a back press at the root should do, decided purely so it's unit-testable. */
internal sealed interface BackAction {
    /** Pop the drill-in stack (city picker -> line -> stops -> arrivals...). */
    data object Pop : BackAction

    /** Step one tab toward the first (Settings -> Lines -> Stops). */
    data object StepTabLeft : BackAction

    /** First press on the first tab. Show the exit-hint toast. */
    data object ShowExitToast : BackAction

    /** Second press on the first tab within the window. Exit the app. */
    data object Finish : BackAction
}

/**
 * Decide the effect of a back press, in precedence order. The drill-in
 * stack pops first (the city picker is a stack entry, so it pops like
 * any other screen). Then the tab steps one left, and only on the first
 * tab does the double-back exit gate apply. A press older than
 * [backExitWindowMs] does not count toward the exit, and any navigation
 * resets it.
 */
internal fun backAction(
    stackSize: Int,
    tab: Int,
    lastBackAt: Long,
    now: Long,
    backExitWindowMs: Long = BACK_EXIT_WINDOW_MS,
): BackAction =
    when {
        stackSize > 1 -> BackAction.Pop
        tab != 0 -> BackAction.StepTabLeft
        now - lastBackAt < backExitWindowMs -> BackAction.Finish
        else -> BackAction.ShowExitToast
    }
