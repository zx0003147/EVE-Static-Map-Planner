package dev.evestaticmapplanner.shortcut

sealed interface GlobalShortcut

data class KeyboardShortcut(
    val key: ShortcutKey,
    val modifiers: Set<ShortcutModifier> = emptySet(),
) : GlobalShortcut

enum class ShortcutModifier(val token: String, val displayName: String) {
    CTRL("CTRL", "Ctrl"),
    ALT("ALT", "Alt"),
    SHIFT("SHIFT", "Shift"),
    META("META", "Meta"),
}

enum class ShortcutKey(val token: String, val displayName: String = token) {
    A("A"), B("B"), C("C"), D("D"), E("E"), F("F"), G("G"), H("H"), I("I"), J("J"),
    K("K"), L("L"), M("M"), N("N"), O("O"), P("P"), Q("Q"), R("R"), S("S"), T("T"),
    U("U"), V("V"), W("W"), X("X"), Y("Y"), Z("Z"),
    DIGIT_0("0"), DIGIT_1("1"), DIGIT_2("2"), DIGIT_3("3"), DIGIT_4("4"),
    DIGIT_5("5"), DIGIT_6("6"), DIGIT_7("7"), DIGIT_8("8"), DIGIT_9("9"),
    F1("F1"), F2("F2"), F3("F3"), F4("F4"), F5("F5"), F6("F6"),
    F7("F7"), F8("F8"), F9("F9"), F10("F10"), F11("F11"), F12("F12"),
    F13("F13"), F14("F14"), F15("F15"), F16("F16"), F17("F17"), F18("F18"),
    F19("F19"), F20("F20"), F21("F21"), F22("F22"), F23("F23"), F24("F24"),
    SPACE("SPACE", "Space"),
    TAB("TAB", "Tab"),
    ENTER("ENTER", "Enter"),
    BACKSPACE("BACKSPACE", "Backspace"),
    DELETE("DELETE", "Delete"),
    INSERT("INSERT", "Insert"),
    ARROW_UP("ARROW_UP", "Up"),
    ARROW_DOWN("ARROW_DOWN", "Down"),
    ARROW_LEFT("ARROW_LEFT", "Left"),
    ARROW_RIGHT("ARROW_RIGHT", "Right"),
    HOME("HOME", "Home"),
    END("END", "End"),
    PAGE_UP("PAGE_UP", "Page Up"),
    PAGE_DOWN("PAGE_DOWN", "Page Down"),
}

object KeyboardShortcutCodec {
    private const val PREFIX = "kbd:v1:"
    private val modifiersByToken = ShortcutModifier.entries.associateBy(ShortcutModifier::token)
    private val keysByToken = ShortcutKey.entries.associateBy(ShortcutKey::token)

    fun encode(shortcut: KeyboardShortcut): String = buildList {
        ShortcutModifier.entries.filterTo(this) { it in shortcut.modifiers }
        add(shortcut.key)
    }.joinToString(
        separator = "+",
        prefix = PREFIX,
    ) { value ->
        when (value) {
            is ShortcutModifier -> value.token
            is ShortcutKey -> value.token
            else -> error("Unsupported shortcut token")
        }
    }

    fun decode(value: String?): KeyboardShortcut? {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (!raw.startsWith(PREFIX)) return null
        val tokens = raw.removePrefix(PREFIX).split('+')
        if (tokens.isEmpty() || tokens.any(String::isBlank)) return null
        val key = keysByToken[tokens.last()] ?: return null
        val modifiers = tokens.dropLast(1).map { modifiersByToken[it] ?: return null }.toSet()
        return KeyboardShortcut(key, modifiers)
    }
}

fun KeyboardShortcut.displayLabel(): String = buildList {
    ShortcutModifier.entries.filterTo(this) { it in modifiers }
    add(key)
}.joinToString(" + ") { value ->
    when (value) {
        is ShortcutModifier -> value.displayName
        is ShortcutKey -> value.displayName
        else -> error("Unsupported shortcut display value")
    }
}
