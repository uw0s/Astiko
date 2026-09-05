package app.astiko.ui

import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The search screen's state machine: a blank query stays idle without
 * fetching, retyping collapses to one search after the pause, an error
 * surfaces with retry, and clearing returns to the prompt. The debounce
 * runs on virtual time, so no real waiting in the tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopSearchViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stop(
        id: String,
        name: String,
    ) = Stop(provider = Provider.CITYBUS, id = id, name = name, lat = 0.0, lon = 0.0)

    private fun viewModel(repo: FakeTransitRepository = FakeTransitRepository()) =
        StopSearchViewModel(
            repository = repo,
            favoritesRepository = FakeFavoritesRepository(),
        )

    @Test
    fun blankQueryStaysIdleWithoutFetching() =
        runTest {
            val repo = FakeTransitRepository()
            val vm = viewModel(repo)

            vm.setQuery("   ")
            advanceUntilIdle()

            assertEquals(StopSearchViewModel.SearchState.Idle, vm.state.value)
            assertEquals(0, repo.stopCatalogCalls)
        }

    @Test
    fun querySearchesAfterDebounce() =
        runTest {
            val repo = FakeTransitRepository().apply { stopCatalog = listOf(stop("1", "ΑΓΟΡΑ")) }
            val vm = viewModel(repo)

            vm.setQuery("αγορά")
            advanceUntilIdle()

            assertEquals(1, repo.stopCatalogCalls)
            assertEquals(
                StopSearchViewModel.SearchState.Ready(listOf(stop("1", "ΑΓΟΡΑ"))),
                vm.state.value,
            )
        }

    @Test
    fun fastRetypeFetchesCatalogOnce() =
        runTest {
            val repo = FakeTransitRepository().apply { stopCatalog = listOf(stop("1", "ΑΓΟΡΑ")) }
            val vm = viewModel(repo)

            vm.setQuery("α")
            advanceTimeBy(100)
            runCurrent()
            vm.setQuery("αγ")
            advanceTimeBy(100)
            runCurrent()
            vm.setQuery("αγορ")
            advanceUntilIdle()

            // The pause resets with every keystroke: the burst collapses
            // to one catalog fetch, not three.
            assertEquals(1, repo.stopCatalogCalls)
        }

    @Test
    fun errorSurfacesAndRetryResearches() =
        runTest {
            val repo =
                FakeTransitRepository().apply {
                    stopCatalogError = IOException("boom")
                }
            val vm = viewModel(repo)

            vm.setQuery("αγορ")
            advanceUntilIdle()
            val error = vm.state.value as StopSearchViewModel.SearchState.Error
            assertEquals("boom", error.message)

            // The failure passes. Retry re-searches the same query.
            repo.stopCatalogError = null
            repo.stopCatalog = listOf(stop("1", "ΑΓΟΡΑ"))
            vm.retry()
            advanceUntilIdle()

            assertEquals(2, repo.stopCatalogCalls)
            assertEquals(
                StopSearchViewModel.SearchState.Ready(listOf(stop("1", "ΑΓΟΡΑ"))),
                vm.state.value,
            )
        }

    @Test
    fun clearingQueryReturnsToIdle() =
        runTest {
            val repo = FakeTransitRepository().apply { stopCatalog = listOf(stop("1", "ΑΓΟΡΑ")) }
            val vm = viewModel(repo)

            vm.setQuery("αγορά")
            advanceUntilIdle()
            assertTrue(vm.state.value is StopSearchViewModel.SearchState.Ready)

            vm.setQuery("")
            advanceUntilIdle()
            assertEquals(StopSearchViewModel.SearchState.Idle, vm.state.value)
        }

    @Test
    fun resetClearsQueryStateAndScroll() =
        runTest {
            val repo = FakeTransitRepository().apply { stopCatalog = listOf(stop("1", "ΑΓΟΡΑ")) }
            val vm = viewModel(repo)

            vm.setQuery("αγορά")
            advanceUntilIdle()
            vm.listState.requestScrollToItem(37)
            assertTrue(vm.state.value is StopSearchViewModel.SearchState.Ready)

            // Next open of the search entry: previous session is discarded.
            vm.reset()
            advanceUntilIdle()

            assertEquals("", vm.query.value)
            assertEquals(StopSearchViewModel.SearchState.Idle, vm.state.value)
            assertEquals(0, vm.listState.firstVisibleItemIndex)
            // The reset query is idle, so nothing was re-searched.
            assertEquals(1, repo.stopCatalogCalls)
        }
}
