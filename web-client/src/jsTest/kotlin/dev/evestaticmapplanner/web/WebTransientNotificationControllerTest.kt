package dev.evestaticmapplanner.web

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WebTransientNotificationControllerTest {
    @Test
    fun `route calculated and clear route messages complete their transient lifecycle`() = runTest {
        val views = mutableListOf<WebTransientNotificationView>()
        val notifications = WebTransientNotificationController(this, views::add)
        val planner = WebPlannerController(
            WebUniverseDataAdapter.adapt(fixtureDocument()),
            notifications::accept,
        )
        planner.setNormalStart(30_000_001)
        planner.setNormalDestination(30_000_004)
        planner.calculateNormalRoute()

        assertTrue(views.last().text.orEmpty().startsWith("Route ready"))
        assertEquals(WebTransientNotificationPhase.VISIBLE, views.last().phase)
        advanceTimeBy(INFORMATION_VISIBLE_MILLIS)
        runCurrent()
        assertEquals(WebTransientNotificationPhase.FADING, views.last().phase)
        advanceTimeBy(FADE_OUT_MILLIS)
        runCurrent()
        assertEquals(WebTransientNotificationPhase.HIDDEN, views.last().phase)

        planner.clearNormalRoute()
        assertEquals("Normal route cleared.", views.last().text)
        assertEquals(WebTransientNotificationPhase.VISIBLE, views.last().phase)
        advanceTimeBy(INFORMATION_VISIBLE_MILLIS)
        runCurrent()
        assertEquals(WebTransientNotificationPhase.FADING, views.last().phase)
        advanceTimeBy(FADE_OUT_MILLIS)
        runCurrent()
        assertEquals(WebTransientNotificationPhase.HIDDEN, views.last().phase)
    }

    @Test
    fun `second message replaces first and an old timer cannot clear it`() = runTest {
        val views = mutableListOf<WebTransientNotificationView>()
        val notifications = WebTransientNotificationController(this, views::add)
        val planner = WebPlannerController(
            WebUniverseDataAdapter.adapt(fixtureDocument()),
            notifications::accept,
        )
        planner.clearNormalRoute()
        advanceTimeBy(2_500)

        planner.clearCapitalRoute()
        assertEquals("Capital route cleared.", views.last().text)
        advanceTimeBy(500)
        runCurrent()
        assertEquals("Capital route cleared.", views.last().text)
        assertEquals(WebTransientNotificationPhase.VISIBLE, views.last().phase)

        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(WebTransientNotificationPhase.FADING, views.last().phase)
        advanceTimeBy(FADE_OUT_MILLIS)
        runCurrent()
        assertEquals(WebTransientNotificationPhase.HIDDEN, views.last().phase)
    }

    @Test
    fun `error uses the longer timeout`() = runTest {
        val views = mutableListOf<WebTransientNotificationView>()
        val notifications = WebTransientNotificationController(this, views::add)
        val planner = WebPlannerController(
            WebUniverseDataAdapter.adapt(fixtureDocument()),
            notifications::accept,
        )
        planner.calculateNormalRoute()

        assertEquals(WebTransientNotificationKind.ERROR, views.last().kind)
        assertEquals(WebTransientNotificationPhase.VISIBLE, views.last().phase)
        advanceTimeBy(INFORMATION_VISIBLE_MILLIS)
        runCurrent()
        assertEquals(WebTransientNotificationPhase.VISIBLE, views.last().phase)
        advanceTimeBy(ERROR_VISIBLE_MILLIS - INFORMATION_VISIBLE_MILLIS)
        runCurrent()
        assertEquals(WebTransientNotificationPhase.FADING, views.last().phase)
        advanceTimeBy(FADE_OUT_MILLIS)
        runCurrent()
        assertEquals(WebTransientNotificationPhase.HIDDEN, views.last().phase)
    }
}
