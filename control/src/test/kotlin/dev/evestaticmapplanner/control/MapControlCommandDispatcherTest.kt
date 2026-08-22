package dev.evestaticmapplanner.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapControlCommandDispatcherTest {
    @Test
    fun `commands execute FIFO with no late result overtaking clear`() = runTest {
        val dispatcher = MapControlCommandDispatcher(this)
        val events = mutableListOf<String>()
        val add = async { dispatcher.dispatch { events += "add"; "added" } }
        val clear = async { dispatcher.dispatch { events += "clear"; "cleared" } }
        val secondAdd = async { dispatcher.dispatch { events += "add-2"; "added-2" } }

        advanceUntilIdle()

        assertEquals(listOf("add", "clear", "add-2"), events)
        assertEquals("added", assertIs<DispatchOutcome.Completed<String>>(add.await()).value)
        assertEquals("cleared", assertIs<DispatchOutcome.Completed<String>>(clear.await()).value)
        assertEquals("added-2", assertIs<DispatchOutcome.Completed<String>>(secondAdd.await()).value)
        dispatcher.close()
    }

    @Test
    fun `clear cannot overtake an in-flight calculation and commit`() = runTest {
        val dispatcher = MapControlCommandDispatcher(this)
        val calculationStarted = CompletableDeferred<Unit>()
        val releaseCalculation = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val show = async {
            dispatcher.dispatch {
                calculationStarted.complete(Unit)
                releaseCalculation.await()
                events += "show-commit"
            }
        }
        calculationStarted.await()
        val clear = async {
            dispatcher.dispatch { events += "clear-commit" }
        }
        testScheduler.runCurrent()
        assertTrue(events.isEmpty())

        releaseCalculation.complete(Unit)
        advanceUntilIdle()

        show.await()
        clear.await()
        assertEquals(listOf("show-commit", "clear-commit"), events)
        dispatcher.close()
    }

    @Test
    fun `bounded queue rejects overflow before consumer starts`() = runTest {
        val consumerScheduler = TestCoroutineScheduler()
        val dispatcher = MapControlCommandDispatcher(
            CoroutineScope(StandardTestDispatcher(consumerScheduler)),
            capacity = 1,
        )
        val first = async(UnconfinedTestDispatcher(testScheduler)) { dispatcher.dispatch { 1 } }
        val second = async(UnconfinedTestDispatcher(testScheduler)) { dispatcher.dispatch { 2 } }

        assertTrue(second.isCompleted)
        assertIs<DispatchOutcome.QueueFull>(second.await())
        consumerScheduler.advanceUntilIdle()
        assertEquals(1, assertIs<DispatchOutcome.Completed<Int>>(first.await()).value)
        dispatcher.close()
    }

    @Test
    fun `cancelled queued command is skipped before start`() = runTest {
        val consumerScheduler = TestCoroutineScheduler()
        val dispatcher = MapControlCommandDispatcher(CoroutineScope(StandardTestDispatcher(consumerScheduler)))
        val events = mutableListOf<String>()
        val cancelled = async(UnconfinedTestDispatcher(testScheduler)) {
            dispatcher.dispatch { events += "must-not-run" }
        }
        cancelled.cancel()
        consumerScheduler.advanceUntilIdle()

        assertTrue(events.isEmpty())
        dispatcher.close()
    }
}
