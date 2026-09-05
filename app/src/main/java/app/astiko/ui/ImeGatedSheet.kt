package app.astiko.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.delay

/**
 * Opens a ModalBottomSheet only after the keyboard has fully collapsed.
 *
 * [request] drops the field's focus, and the sheet is composed once the
 * IME insets reach 0 (per-frame snapshot state on the screen's window).
 * Composing the dialog earlier raced the IME hide: the dialog's window
 * focus change cancelled the hide, re-showed the keyboard, and hid it
 * again (the double collapse), and the sheet slid up while the keyboard
 * still covered the bottom of the screen.
 *
 * Two guards: a stuck IME signal opens the sheet after
 * [IME_COLLAPSE_TIMEOUT_MS] anyway, and back during the wait cancels the
 * request so it cannot open over a screen already sliding away.
 */
class ImeGatedSheet<T>(
    private val shownState: MutableState<T?>,
    private val pendingState: MutableState<T?>,
    private val clearFieldFocus: () -> Unit,
) {
    /** Non-null when the sheet should be composed. */
    val shown: T?
        get() = shownState.value

    /** Drop the field's focus and open the sheet once the keyboard is
     *  gone, or after [IME_COLLAPSE_TIMEOUT_MS] whichever comes first. */
    fun request(value: T) {
        clearFieldFocus()
        pendingState.value = value
    }

    /** Dismiss the sheet and drop a pending request. */
    fun dismiss() {
        pendingState.value = null
        shownState.value = null
    }

    /** True while a request is waiting for the keyboard. */
    fun isPending(): Boolean = pendingState.value != null
}

/** Remembers an [ImeGatedSheet] for this screen and keyframes its open on
 *  the screen's IME insets. The sheet dialog has its own insets, so the
 *  gate must never read them inside the sheet. */
@Composable
fun <T> rememberImeGatedSheet(): ImeGatedSheet<T> {
    val focusManager = LocalFocusManager.current
    val shown = remember { mutableStateOf<T?>(null) }
    val pending = remember { mutableStateOf<T?>(null) }
    val density = LocalDensity.current
    val imeGone = WindowInsets.Companion.ime.getBottom(density) == 0

    LaunchedEffect(imeGone, pending.value != null) {
        if (pending.value == null) return@LaunchedEffect
        // The key below restarts this effect at the last frame of the
        // collapse. The timeout covers a stuck signal.
        if (!imeGone) delay(IME_COLLAPSE_TIMEOUT_MS)
        // Re-read: a newer request may have replaced the pending value.
        val req = pending.value ?: return@LaunchedEffect
        pending.value = null
        shown.value = req
    }

    val gate =
        remember {
            ImeGatedSheet<T>(
                shownState = shown,
                pendingState = pending,
                clearFieldFocus = { focusManager.clearFocus() },
            )
        }

    // Back cancels a pending request like it dismisses a shown sheet. A
    // pop mid-wait would otherwise leave the request alive through the
    // exit animation and open the sheet over the outgoing screen.
    BackHandler(enabled = gate.isPending()) { gate.dismiss() }

    return gate
}

/** IME hide animations run ~250 ms. This only fires when the insets
 *  signal never arrives. A late sheet beats no sheet. */
private const val IME_COLLAPSE_TIMEOUT_MS = 600L
