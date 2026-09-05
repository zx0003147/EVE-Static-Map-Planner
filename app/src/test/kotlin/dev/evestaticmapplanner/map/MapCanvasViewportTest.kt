package dev.evestaticmapplanner.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MapCanvasViewportTest {
    @Test
    fun `map drawing is clipped to the canvas viewport`() = runComposeUiTest {
        setContent {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.requiredSize(100.dp)
                    .background(OUTSIDE_COLOR)
                    .testTag(ROOT_TAG),
            ) {
                MapCanvasViewport(Modifier.size(40.dp)) {
                    Canvas(Modifier.requiredSize(100.dp).offset((-30).dp, (-30).dp)) {
                        drawRect(MAP_COLOR)
                    }
                }
            }
        }

        val pixels = onNodeWithTag(ROOT_TAG).captureToImage().toPixelMap()
        assertEquals(OUTSIDE_COLOR.toArgb(), pixels[1, 1].toArgb())
        assertEquals(MAP_COLOR.toArgb(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }

    private companion object {
        const val ROOT_TAG = "map-canvas-viewport-test-root"
        val OUTSIDE_COLOR = Color(0xFFFF00FF)
        val MAP_COLOR = Color(0xFF00FFFF)
    }
}
