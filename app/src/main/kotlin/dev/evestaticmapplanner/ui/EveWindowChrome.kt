package dev.evestaticmapplanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import kotlin.math.roundToInt

/**
 * Keeps the native Windows frame (resize, snap, DPI, drag and double-click behavior) while asking
 * DWM to render it with the application's dark, low-contrast chrome colors.
 */
@Composable
fun EveWindowChrome(window: Window) {
    SideEffect { NativeWindowChrome.apply(window) }
}

internal object NativeWindowChrome {
    private const val DARK_MODE_ATTRIBUTE = 20
    private const val LEGACY_DARK_MODE_ATTRIBUTE = 19
    private const val BORDER_COLOR_ATTRIBUTE = 34
    private const val CAPTION_COLOR_ATTRIBUTE = 35
    private const val TEXT_COLOR_ATTRIBUTE = 36

    private interface DwmApi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            windowHandle: Pointer,
            attribute: Int,
            attributeValue: Pointer,
            attributeSize: Int,
        ): Int
    }

    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val api: DwmApi? by lazy {
        if (!isWindows) null else runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull()
    }

    fun apply(window: Window): Boolean {
        if (!isWindows || !window.isDisplayable) return false
        val dwm = api ?: return false
        val handle = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return false
        return runCatching {
            val darkResult = setInt(dwm, handle, DARK_MODE_ATTRIBUTE, 1)
                .takeIf(::succeeded)
                ?: setInt(dwm, handle, LEGACY_DARK_MODE_ATTRIBUTE, 1)
            setInt(dwm, handle, BORDER_COLOR_ATTRIBUTE, colorRef(EveColors.Border))
            setInt(dwm, handle, CAPTION_COLOR_ATTRIBUTE, colorRef(EveColors.SecondarySurface))
            setInt(dwm, handle, TEXT_COLOR_ATTRIBUTE, colorRef(EveColors.PrimaryText))
            succeeded(darkResult)
        }.getOrDefault(false)
    }

    internal fun colorRef(color: androidx.compose.ui.graphics.Color): Int {
        val red = (color.red * 255f).roundToInt().coerceIn(0, 255)
        val green = (color.green * 255f).roundToInt().coerceIn(0, 255)
        val blue = (color.blue * 255f).roundToInt().coerceIn(0, 255)
        return red or (green shl 8) or (blue shl 16)
    }

    private fun setInt(dwm: DwmApi, handle: Pointer, attribute: Int, value: Int): Int {
        val memory = Memory(Int.SIZE_BYTES.toLong())
        memory.setInt(0, value)
        return dwm.DwmSetWindowAttribute(handle, attribute, memory, Int.SIZE_BYTES)
    }

    private fun succeeded(result: Int): Boolean = result >= 0
}
