package dev.evestaticmapplanner.staticdata

import dev.evestaticmapplanner.StaticDatabaseMode
import kotlinx.coroutines.test.TestScope
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StaticDataManagerViewModelTest {
    @Test
    fun `external mode exposes read-only status and disables updater commands`() {
        val viewModel = StaticDataManagerViewModel(
            StaticDatabaseMode.EXTERNAL,
            Path("external.db"),
            123,
            service = null,
            scope = TestScope(),
            autoCheck = true,
        )

        assertEquals(StaticDatabaseMode.EXTERNAL, viewModel.state.value.mode)
        assertEquals(123, viewModel.state.value.currentBuild)
        assertFalse(viewModel.checkForUpdates())
        assertFalse(viewModel.downloadAndPrepare())
        assertFalse(viewModel.discardPending())
    }
}
