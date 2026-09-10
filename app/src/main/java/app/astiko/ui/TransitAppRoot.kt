package app.astiko.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.astiko.R
import app.astiko.TransitApp
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Stop

sealed interface TransitScreen {
    data object Home : TransitScreen

    data object CityPicker : TransitScreen

    data object StopSearch : TransitScreen

    data class VariantStops(
        val variant: LineVariant,
    ) : TransitScreen

    data class Arrivals(
        val stop: Stop,
    ) : TransitScreen

    data class Timetable(
        val target: TimetableTarget,
    ) : TransitScreen

    data object Licenses : TransitScreen
}

/**
 * A [ViewModelStoreOwner] over a plain [ViewModelStore] that carries the
 * Application in its creation extras (the screen factories resolve the DI
 * container via [ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]).
 */
private class AppViewModelStoreOwner(
    val store: ViewModelStore,
    application: Application,
) : ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory {
    override val viewModelStore: ViewModelStore get() = store

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)

    override val defaultViewModelCreationExtras: CreationExtras =
        MutableCreationExtras().apply {
            this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = application
        }
}

/**
 * One back-stack entry: the screen plus its private ViewModel store
 * (see [EntryScoped]). The store scopes the screen's ViewModels instead
 * of the activity's, and is cleared when the entry leaves composition.
 */
private class StackEntry(
    val screen: TransitScreen,
    val owner: AppViewModelStoreOwner,
)

/**
 * Runs [content] with [entry]'s private ViewModel store as the
 * [LocalViewModelStoreOwner] and clears the store when the entry's
 * composition is disposed, on pop (after the exit transition, the
 * outgoing screen renders with live data while animating out) or on
 * activity recreation (language/theme change). A reopened screen
 * therefore always gets fresh ViewModels with fresh data in the current
 * language, and its polling flows die with the screen instead of
 * running forever.
 */
@Composable
private fun EntryScoped(
    entry: StackEntry,
    content: @Composable () -> Unit,
) {
    DisposableEffect(entry) {
        onDispose { entry.owner.store.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides entry.owner, content = content)
}

/**
 * Runs [content] with a fresh private ViewModel store as the
 * [LocalViewModelStoreOwner], cleared when [content] leaves composition.
 * For components that open and close without a back-stack entry (the
 * direction sheet): same rationale as [EntryScoped], a reopened sheet must
 * fetch fresh data in the current language, not reuse an activity-scoped
 * ViewModel from a previous visit.
 */
@Composable
fun ScopedViewModelStore(content: @Composable () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val store = remember { ViewModelStore() }
    val owner = remember { AppViewModelStoreOwner(store, application) }
    DisposableEffect(store) {
        onDispose { store.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

private enum class NavDirection { PUSH, POP }

/**
 * Root of the app. Bottom navigation plus drill-in screens (line,
 * direction, stops, arrivals). The top bar title opens the city picker
 * screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitAppRoot() {
    val cityViewModel: CityViewModel = viewModel(factory = CityViewModel.factory())
    val city by cityViewModel.city.collectAsState()
    val mode by cityViewModel.mode.collectAsState()
    val decided by cityViewModel.decided.collectAsState()

    if (!decided) {
        // First launch: a choice (city or location) before anything else.
        OnboardingScreen(
            onPickCity = { cityViewModel.selectCity(it) },
            onUseLocation = { cityViewModel.selectAuto() },
        )
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val appContext = LocalContext.current.applicationContext as Application

    // Search session: the two search ViewModels (stop search, lines
    // search) live here, per city, above the entry stores. Covered
    // screens cannot kill them, so a search survives an arrivals or
    // drill-in round-trip with its results and scroll intact and no
    // re-search on return. Neither VM polls or tracks GPS, so a parked
    // session does no background work. The store clears on city switch
    // or activity recreation, same lifetime as the city's Home store.
    val searchStore = remember(city.name) { ViewModelStore() }
    val searchOwner = remember(city.name) { AppViewModelStoreOwner(searchStore, appContext) }
    DisposableEffect(searchStore) {
        onDispose { searchStore.clear() }
    }

    // Each stack entry owns a private ViewModelStore. A screen's ViewModels
    // live exactly as long as its entry and are cleared when the entry
    // leaves composition (pop, or the activity recreating on a
    // language/theme change). Activity-scoped ViewModels would survive
    // pops, serving stale data on reopen, leaving old-language data on
    // screen after a switch, and keeping the arrivals/vehicles polls
    // running forever in the background.
    val stack =
        remember {
            mutableStateListOf(
                StackEntry(
                    TransitScreen.Home,
                    AppViewModelStoreOwner(ViewModelStore(), appContext),
                ),
            )
        }
    val current = stack.last()

    // Direction of the last stack mutation. AnimatedContent reads it when
    // the target changes, so a push slides the new screen in from the
    // right and a pop slides the previous one back in from the left.
    var navDirection by remember { mutableStateOf(NavDirection.PUSH) }

    // Timestamp of the last back press on the first tab. Two presses within
    // BACK_EXIT_WINDOW_MS exit the app (with a toast on the first). Any
    // other navigation resets it, so a stale press can never count toward
    // the exit.
    var lastBackAt by remember { mutableLongStateOf(0L) }

    @SuppressLint("RestrictedApi")
    fun push(screen: TransitScreen) {
        navDirection = NavDirection.PUSH
        stack.add(StackEntry(screen, AppViewModelStoreOwner(ViewModelStore(), appContext)))
        lastBackAt = 0L
        // A popped stop search ends its session here, not at pop: the
        // outgoing screen stays composed during the exit animation, and
        // resetting at pop would visibly wipe the results while it is
        // still sliding away. The next open gets a fresh search.
        // ViewModelStore.get is restricted to the lifecycle group. The
        // store is root-owned and reset-on-next-open needs the instance,
        // so the suppression is deliberate.
        if (screen is TransitScreen.StopSearch) {
            (searchStore.get("stop-search-${city.name}") as? StopSearchViewModel)
                ?.reset()
        }
    }

    fun pop() {
        // Guarded: the outgoing screen stays composed and pointer-active
        // during the exit transition, so a fast double-activation (double
        // tap on a city row or a back arrow) can call pop() twice. The
        // second call on a size-1 stack would empty it and crash the next
        // `stack.last()` read ("No such element"). The system-back path
        // is already guarded by [backAction]. This guards the direct
        // call sites (onBack/onSelectCity/onSelectAuto).
        if (stack.size <= 1) return
        stack.removeAt(stack.lastIndex)
        navDirection = NavDirection.POP
        lastBackAt = 0L
    }

    // Always enabled: back steps one tab toward the first (Settings, Lines,
    // Stops), and only back on the first tab exits. The system default
    // (finish) would quit the app straight from the Lines/Settings tabs,
    // which users read as a bug. The decision itself is pure
    // ([backAction]) and unit-tested. This handler only executes it. Nested
    // sheets (city chooser, direction sheet) register their own handlers
    // deeper in the tree, so they still dismiss before this runs.
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val exitToast = stringResource(R.string.back_to_exit)
    // Operator announcement page for the current city, null when the
    // operator publishes none. The language comes from the effective
    // configuration, so the in-app override (applied in
    // attachBaseContext) is already in it.
    val announcementsUrl =
        city.announcementsUrl(LocalConfiguration.current.locales[0].language)
    BackHandler {
        val now = SystemClock.uptimeMillis()
        when (backAction(stack.size, tab, lastBackAt, now)) {
            BackAction.Pop -> {
                pop()
            }

            BackAction.StepTabLeft -> {
                tab -= 1
                lastBackAt = 0L
            }

            BackAction.ShowExitToast -> {
                lastBackAt = now
                Toast.makeText(context, exitToast, Toast.LENGTH_SHORT).show()
            }

            BackAction.Finish -> {
                (context as? Activity)?.finish()
            }
        }
    }

    // Both screens stay composed during the transition. On push/pop of a
    // map screen two MapLibre MapViews overlap for ~320 ms. The incoming
    // screen's own Scaffold/top bar slides over the outgoing one (standard
    // Android push/pop feel). The entry's ViewModel store is cleared only
    // when its composition is disposed (transition finished), so the
    // outgoing screen renders with live data during its exit animation.
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            if (navDirection == NavDirection.PUSH) {
                (slideInHorizontally(tween(320)) { it } + fadeIn(tween(320)))
                    .togetherWith(
                        slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(320)),
                    )
            } else {
                (slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(320)))
                    .togetherWith(slideOutHorizontally(tween(320)) { it } + fadeOut(tween(320)))
            }
        },
        label = "screen-transition",
    ) { entry ->
        when (val screen = entry.screen) {
            // The Home tab's ViewModels (stops/lines, keyed by city) live in a
            // store scoped to the current city: a city switch clears the
            // previous city's ViewModels, so their GPS tracking and 15 s
            // polling loops stop instead of accumulating for every visited
            // city (activity-scoped stores were never cleared). Same rationale
            // as EntryScoped. The city is the Home screen's identity.
            is TransitScreen.Home -> {
                key(city.name) {
                    ScopedViewModelStore {
                        Scaffold(
                            topBar = {
                                Column {
                                    TopAppBar(
                                        title = {
                                            Row(
                                                modifier =
                                                    Modifier.clickable {
                                                        push(
                                                            TransitScreen.CityPicker,
                                                        )
                                                    },
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(city.displayName())
                                                Icon(
                                                    Icons.Filled.ArrowDropDown,
                                                    contentDescription =
                                                        stringResource(
                                                            R.string.change_city,
                                                        ),
                                                )
                                            }
                                        },
                                        actions = {
                                            // Stop search belongs to the Στάσεις tab
                                            // (the Γραμμές tab has its own line search
                                            // field). Providers without name search
                                            // (OASA today) don't show the icon.
                                            if (tab == 0 &&
                                                (appContext as TransitApp)
                                                    .container
                                                    .repository(city.provider)
                                                    .supportsStopSearch
                                            ) {
                                                IconButton(
                                                    onClick = {
                                                        push(TransitScreen.StopSearch)
                                                    },
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Search,
                                                        contentDescription =
                                                            stringResource(
                                                                R.string.search_stop,
                                                            ),
                                                    )
                                                }
                                            }
                                            announcementsUrl?.let { url ->
                                                IconButton(
                                                    onClick = { uriHandler.openUri(url) },
                                                ) {
                                                    Icon(
                                                        ImageVector.vectorResource(
                                                            R.drawable.ic_newspaper,
                                                        ),
                                                        contentDescription =
                                                            stringResource(
                                                                R.string.provider_announcements,
                                                            ),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                    OfflineBanner(city.provider)
                                }
                            },
                            bottomBar = {
                                NavigationBar {
                                    MainTab.entries.forEachIndexed { index, tabDef ->
                                        NavigationBarItem(
                                            selected = tab == index,
                                            // Any navigation resets the double-back
                                            // timer (same rule as push/pop/back-step).
                                            // A stale first press must never count
                                            // toward an exit after the user moved.
                                            onClick = {
                                                tab = index
                                                lastBackAt = 0L
                                            },
                                            icon = { tabDef.icon() },
                                            label = { Text(stringResource(tabDef.labelRes)) },
                                        )
                                    }
                                }
                            },
                        ) { padding ->
                            // Soft crossfade between the three tabs (no directional
                            // slide. Tabs are siblings, not a drill-down).
                            Crossfade(
                                targetState = tab,
                                animationSpec = tween(220),
                                label = "tab-crossfade",
                            ) { t ->
                                when (t) {
                                    0 -> {
                                        StopsScreen(
                                            city = city,
                                            onStopClick = { push(TransitScreen.Arrivals(it)) },
                                            modifier = Modifier.padding(padding),
                                        )
                                    }

                                    1 -> {
                                        LinesScreen(
                                            city = city,
                                            viewModel =
                                                viewModel(
                                                    viewModelStoreOwner = searchOwner,
                                                    key = "lines-${city.name}",
                                                    factory = LinesViewModel.factory(city.provider),
                                                ),
                                            onVariantClick = {
                                                push(
                                                    TransitScreen.VariantStops(it),
                                                )
                                            },
                                            modifier = Modifier.padding(padding),
                                        )
                                    }

                                    2 -> {
                                        SettingsScreen(
                                            city = city,
                                            onOpenLicenses = { push(TransitScreen.Licenses) },
                                            modifier = Modifier.padding(padding),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is TransitScreen.CityPicker -> {
                EntryScoped(entry) {
                    CityPickerScreen(
                        city = city,
                        mode = mode,
                        onSelectCity = {
                            cityViewModel.selectCity(it)
                            pop()
                        },
                        onSelectAuto = {
                            cityViewModel.selectAuto()
                            pop()
                        },
                        onBack = { pop() },
                    )
                }
            }

            is TransitScreen.StopSearch -> {
                EntryScoped(entry) {
                    StopSearchScreen(
                        city = city,
                        viewModel =
                            viewModel(
                                viewModelStoreOwner = searchOwner,
                                key = "stop-search-${city.name}",
                                factory = StopSearchViewModel.factory(city),
                            ),
                        onBack = { pop() },
                        onStopClick = { push(TransitScreen.Arrivals(it)) },
                    )
                }
            }

            is TransitScreen.VariantStops -> {
                EntryScoped(entry) {
                    VariantStopsScreen(
                        variant = screen.variant,
                        onBack = { pop() },
                        onStopClick = { push(TransitScreen.Arrivals(it)) },
                        onTimetableClick = { v ->
                            // The direction switcher may have flipped the shown
                            // direction. The timetable follows it.
                            push(TransitScreen.Timetable(TimetableTarget.LineVariantTarget(v)))
                        },
                    )
                }
            }

            is TransitScreen.Arrivals -> {
                EntryScoped(entry) {
                    ArrivalsScreen(
                        stop = screen.stop,
                        onBack = { pop() },
                        onVariantClick = { push(TransitScreen.VariantStops(it)) },
                        onTimetableClick = {
                            push(TransitScreen.Timetable(TimetableTarget.StopTarget(screen.stop)))
                        },
                    )
                }
            }

            is TransitScreen.Timetable -> {
                EntryScoped(entry) {
                    TimetableScreen(
                        target = screen.target,
                        onBack = { pop() },
                    )
                }
            }

            is TransitScreen.Licenses -> {
                EntryScoped(entry) {
                    LicensesScreen(onBack = { pop() })
                }
            }
        }
    }
}
