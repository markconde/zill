package dev.mrkcnd.zill.input

import android.view.KeyEvent
import dev.mrkcnd.zill.protocol.InputEvent

/** The modifier bits the Mac reads (KeyChords.swift's KeyModifiers: CGEventFlags' positions). */
object Mods {
    const val CAPS = 1L shl 16
    const val SHIFT = 1L shl 17
    const val CONTROL = 1L shl 18
    const val OPTION = 1L shl 19
    const val COMMAND = 1L shl 20

    /** Each modifier's own key, in KeyModifiers.keys' order. */
    val KEYS = listOf(CONTROL to 0xE0, SHIFT to 0xE1, OPTION to 0xE2, COMMAND to 0xE3)

    fun fromMeta(meta: Int): Long {
        var m = 0L
        if (meta and KeyEvent.META_SHIFT_ON != 0) m = m or SHIFT
        if (meta and KeyEvent.META_CTRL_ON != 0) m = m or CONTROL
        if (meta and KeyEvent.META_ALT_ON != 0) m = m or OPTION
        if (meta and KeyEvent.META_META_ON != 0) m = m or COMMAND
        return m
    }
}

object Hid {
    const val RETURN = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val DELETE_FORWARD = 0x4C
    const val V = 0x19

    /** A key as a keyboard sends it with [mods]: each modifier's key down, the key down and up, the modifiers up (KeyChord.press). */
    fun press(usage: Int, mods: Long): List<InputEvent> = down(usage, mods) + up(usage, mods)

    fun down(usage: Int, mods: Long): List<InputEvent> {
        var held = 0L
        val out = ArrayList<InputEvent>()
        for ((flag, key) in Mods.KEYS) if (mods and flag != 0L) {
            held = held or flag
            out += InputEvent.Key(key, true, held)
        }
        out += InputEvent.Key(usage, true, mods)
        return out
    }

    fun up(usage: Int, mods: Long): List<InputEvent> {
        val out = ArrayList<InputEvent>()
        out += InputEvent.Key(usage, false, mods)
        var held = mods
        for ((flag, key) in Mods.KEYS.reversed()) if (mods and flag != 0L) {
            held = held and flag.inv()
            out += InputEvent.Key(key, false, held)
        }
        return out
    }

    /** A US-layout character to its key (for shortcuts typed with ⌘, ⌃ or ⌥ latched): usage and whether shift makes it. */
    fun forChar(c: Char): Pair<Int, Boolean>? {
        return when (c) {
            in 'a'..'z' -> (0x04 + (c - 'a')) to false
            in 'A'..'Z' -> (0x04 + (c - 'A')) to true
            in '1'..'9' -> (0x1E + (c - '1')) to false
            '0' -> 0x27 to false
            ' ' -> SPACE to false
            '\n' -> RETURN to false
            '\t' -> TAB to false
            '-' -> 0x2D to false; '_' -> 0x2D to true
            '=' -> 0x2E to false; '+' -> 0x2E to true
            '[' -> 0x2F to false; '{' -> 0x2F to true
            ']' -> 0x30 to false; '}' -> 0x30 to true
            '\\' -> 0x31 to false; '|' -> 0x31 to true
            ';' -> 0x33 to false; ':' -> 0x33 to true
            '\'' -> 0x34 to false; '"' -> 0x34 to true
            '`' -> 0x35 to false; '~' -> 0x35 to true
            ',' -> 0x36 to false; '<' -> 0x36 to true
            '.' -> 0x37 to false; '>' -> 0x37 to true
            '/' -> 0x38 to false; '?' -> 0x38 to true
            '!' -> 0x1E to true; '@' -> 0x1F to true; '#' -> 0x20 to true; '$' -> 0x21 to true
            '%' -> 0x22 to true; '^' -> 0x23 to true; '&' -> 0x24 to true; '*' -> 0x25 to true
            '(' -> 0x26 to true; ')' -> 0x27 to true
            else -> null
        }
    }

    /** A hardware keyboard's key to its USB HID usage (keyboard page), or null. */
    fun forKeyCode(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + (code - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + (code - KeyEvent.KEYCODE_1)
        KeyEvent.KEYCODE_0 -> 0x27
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE_FORWARD
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_SPACE -> SPACE
        KeyEvent.KEYCODE_MINUS -> 0x2D
        KeyEvent.KEYCODE_EQUALS -> 0x2E
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
        KeyEvent.KEYCODE_BACKSLASH -> 0x31
        KeyEvent.KEYCODE_SEMICOLON -> 0x33
        KeyEvent.KEYCODE_APOSTROPHE -> 0x34
        KeyEvent.KEYCODE_GRAVE -> 0x35
        KeyEvent.KEYCODE_COMMA -> 0x36
        KeyEvent.KEYCODE_PERIOD -> 0x37
        KeyEvent.KEYCODE_SLASH -> 0x38
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x39
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x3A + (code - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_INSERT -> 0x49
        KeyEvent.KEYCODE_MOVE_HOME -> 0x4A
        KeyEvent.KEYCODE_PAGE_UP -> 0x4B
        KeyEvent.KEYCODE_MOVE_END -> 0x4D
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xE0
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xE1
        KeyEvent.KEYCODE_ALT_LEFT -> 0xE2
        KeyEvent.KEYCODE_META_LEFT -> 0xE3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xE4
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xE5
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xE6
        KeyEvent.KEYCODE_META_RIGHT -> 0xE7
        else -> null
    }
}
