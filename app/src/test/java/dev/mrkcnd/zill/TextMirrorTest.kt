package dev.mrkcnd.zill

import dev.mrkcnd.zill.input.Hid
import dev.mrkcnd.zill.input.TextMirror
import dev.mrkcnd.zill.input.TextMirror.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class TextMirrorTest {
    @Test fun typingOnSendsOnlyTheNewText() {
        assertEquals(listOf(Step.Text("é")), TextMirror.diff("caf", "café"))
        assertEquals(emptyList<Step>(), TextMirror.diff("same", "same"))
    }

    @Test fun aCorrectionBacksUpThenTypes() {
        // An autocorrect: "teh " becomes "the ".
        assertEquals(listOf(Step.Backspaces(3), Step.Text("he ")), TextMirror.diff("teh ", "the "))
        // A plain backspace.
        assertEquals(listOf(Step.Backspaces(1)), TextMirror.diff("abc", "ab"))
    }

    @Test fun koreanSyllablesBuiltAgain() {
        // ㅎ, then 하, then 한: each one replaces the last.
        assertEquals(listOf(Step.Text("ㅎ")), TextMirror.diff("", "ㅎ"))
        assertEquals(listOf(Step.Backspaces(1), Step.Text("하")), TextMirror.diff("ㅎ", "하"))
        assertEquals(listOf(Step.Backspaces(1), Step.Text("한")), TextMirror.diff("하", "한"))
    }

    @Test fun anEmojiIsOneCharacterOnTheMac() {
        // 😀 and 😁 share their high surrogate: the backspace still takes the whole one.
        assertEquals(listOf(Step.Backspaces(1), Step.Text("😁")), TextMirror.diff("a😀", "a😁"))
        assertEquals(listOf(Step.Backspaces(1)), TextMirror.diff("a😀", "a"))
    }

    @Test fun returnAndTabAreKeys() {
        assertEquals(listOf(Step.Text("hi"), Step.Key(Hid.RETURN), Step.Text("you"), Step.Key(Hid.TAB)), TextMirror.typed("hi\nyou\t"))
        assertEquals(true, TextMirror.endsRevision("ok\n"))
        assertEquals(false, TextMirror.endsRevision("ok"))
    }
}
