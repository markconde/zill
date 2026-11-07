package dev.mrkcnd.zill.input

/**
 * What the soft keyboard typed, as the Mac must be told it (Sill's TextComposition.swift, simplified):
 * the keyboard edits a local copy of what went to the Mac since its caret last moved, and each change
 * becomes backspaces (for what it took back: a correction, Korean's syllable built again, a word
 * dictated and fixed) and the new text. Text still being composed (Pinyin, kana, a word not yet picked)
 * is the caller's to leave out until it is chosen.
 *
 * Pure: checked on its own (TextMirrorTest).
 */
object TextMirror {
    /** One step to the Mac. */
    sealed class Step {
        /** That many characters deleted before the caret (one Mac character each: a whole code point). */
        data class Backspaces(val count: Int) : Step()
        data class Text(val text: String) : Step()
        /** A Return or a Tab typed: a key, and the end of what can be revised. */
        data class Key(val usage: Int) : Step()
    }

    /**
     * From `sent` (what the Mac has) to `now` (what the keyboard's copy holds, composition left out):
     * backspaces back to where they differ, then the rest. Never splits a surrogate pair.
     */
    fun diff(sent: String, now: String): List<Step> {
        if (sent == now) return emptyList()
        var p = 0
        val max = minOf(sent.length, now.length)
        while (p < max && sent[p] == now[p]) p++
        if (p > 0 && p < sent.length && Character.isLowSurrogate(sent[p]) && Character.isHighSurrogate(sent[p - 1])) p--
        if (p > 0 && p < now.length && Character.isLowSurrogate(now[p]) && Character.isHighSurrogate(now[p - 1])) p--
        val steps = ArrayList<Step>()
        val back = sent.codePointCount(p, sent.length)
        if (back > 0) steps += Step.Backspaces(back)
        steps += typed(now.substring(p))
        return steps
    }

    /** Text as the Mac types it: runs of text, and a Return or a Tab as their keys. */
    fun typed(text: String): List<Step> {
        val steps = ArrayList<Step>()
        val run = StringBuilder()
        fun flush() { if (run.isNotEmpty()) { steps += Step.Text(run.toString()); run.clear() } }
        for (c in text) when (c) {
            '\n' -> { flush(); steps += Step.Key(Hid.RETURN) }
            '\t' -> { flush(); steps += Step.Key(Hid.TAB) }
            '\r' -> Unit
            else -> run.append(c)
        }
        flush()
        return steps
    }

    /** A Return or a Tab is in it: what went before is final on the Mac, so the copy starts again. */
    fun endsRevision(text: String) = text.contains('\n') || text.contains('\t')
}
