package dev.evestaticmapplanner.platform.windows.minimaphud

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap

/** Applies only the extended styles owned by the Mini-map and preserves every unrelated AWT style bit. */
internal object WindowsMiniMapWindowStyles {
    private val addedBitsByHwnd = ConcurrentHashMap<Long, Int>()

    fun apply(window: Window, clickThrough: Boolean): Result<Unit> = runCatching {
        check(Platform.isWindows()) { "Click-through is only supported on Windows" }
        check(window.isDisplayable) { "Mini-map native window is not displayable" }
        val hwnd = WinDef.HWND(Native.getComponentPointer(window))
        val hwndValue = Pointer.nativeValue(hwnd.pointer)
        val current = getExtendedStyle(hwnd)
        val next = if (clickThrough) {
            val added = MiniMapExtendedStylePolicy.addedBits(current, addedBitsByHwnd[hwndValue])
            addedBitsByHwnd.putIfAbsent(hwndValue, added)
            MiniMapExtendedStylePolicy.lockedStyle(current)
        } else {
            val added = addedBitsByHwnd.remove(hwndValue) ?: 0
            MiniMapExtendedStylePolicy.interactiveStyle(current, added)
        }
        if (next != current) setExtendedStyle(hwnd, next)
    }

    fun restore(window: Window) {
        runCatching { apply(window, clickThrough = false).getOrThrow() }
    }

    private fun getExtendedStyle(hwnd: WinDef.HWND): Int {
        Native.setLastError(0)
        val style = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        val error = Native.getLastError()
        check(style != 0 || error == 0) { "GetWindowLong failed with Win32 error $error" }
        return style
    }

    private fun setExtendedStyle(hwnd: WinDef.HWND, style: Int) {
        Native.setLastError(0)
        val previous = User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, style)
        val error = Native.getLastError()
        check(previous != 0 || error == 0) { "SetWindowLong failed with Win32 error $error" }
        check(
            User32.INSTANCE.SetWindowPos(
                hwnd,
                null,
                0,
                0,
                0,
                0,
                WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or
                    WinUser.SWP_NOACTIVATE or WinUser.SWP_FRAMECHANGED,
            ),
        ) { "SetWindowPos failed with Win32 error ${Native.getLastError()}" }
    }
}

internal object MiniMapExtendedStylePolicy {
    private const val WS_EX_NOACTIVATE = 0x08000000
    private const val OWNED_LOCKED_BITS = WinUser.WS_EX_TRANSPARENT or WS_EX_NOACTIVATE

    fun addedBits(currentStyle: Int, previouslyAdded: Int?): Int =
        previouslyAdded ?: (OWNED_LOCKED_BITS and currentStyle.inv())

    fun lockedStyle(currentStyle: Int): Int = currentStyle or OWNED_LOCKED_BITS

    fun interactiveStyle(currentStyle: Int, addedBits: Int): Int = currentStyle and addedBits.inv()
}
