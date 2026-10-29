package com.matthew.matrixhome

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import kotlin.random.Random

/**
 * The shell session at the top of Home, behaving like a real terminal: a scrollback of printed
 * lines, then a prompt (`matrix@tv:~/favorites$ `) where the command for whatever's focused is
 * typed out. Moving focus edits that command the way a person would: backspace to the part both
 * commands share, then type the rest. Once it's typed, the target's details show greyed out after
 * the cursor, like a shell's autosuggestion. [run] executes it: the command and its output scroll
 * up and a fresh prompt waits.
 */
class Terminal(private val view: TextView, private val rows: Int) {
    var hue: Hue = Hues.default
        set(value) { field = value; render() }

    /** Whose prompt it is (`matrix@tv`). Lines already printed keep the user who ran them, as in a real shell. */
    var user = "matrix"
        set(value) { field = value; render() }

    private enum class Kind { DIM, HUE, COMMAND, OUTPUT }
    private class Line(val kind: Kind, val text: String, val dir: String = "", val user: String = "")

    private val history = ArrayList<Line>()
    private var dir = "~"
    private var shown = ""   // what's on the prompt line right now
    private var target = ""  // what it's being edited toward
    private var hint = ""    // greyed suggestion after the cursor, once [shown] reaches [target]
    private var bare = false // typing a line with no prompt (the session's opening message)
    private var queued: Triple<String, String, String>? = null // a command waiting for that message to finish
    private var cursorOn = true
    private val rnd = Random(System.nanoTime())
    private val tick = Runnable { step() }

    init {
        // Lines are fitted to the view's width, which isn't known until layout.
        view.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) render() }
    }

    /** A new session: the screen clears to a single dim line (e.g. "Last login: ..."). */
    fun reset(first: String) {
        history.clear()
        history += Line(Kind.DIM, first)
        shown = ""
        target = ""
        hint = ""
        bare = false
        queued = null
        restart(0)
    }

    /** Types [text] on a line of its own (no prompt), then prints it and gives the prompt back. */
    fun say(text: String) {
        bare = true
        shown = ""
        target = text
        hint = ""
        restart(START_MS)
    }

    /** Edits the prompt toward [command], run from [cwd]; [suggestion] shows greyed after it once typed. */
    fun type(cwd: String, command: String, suggestion: String) {
        if (bare) { queued = Triple(cwd, command, suggestion); return }
        dir = cwd
        target = command
        hint = suggestion
        restart(0)
    }

    /** Runs what's on the prompt: it scrolls up with [output] under it (if any). */
    fun run(output: String?) = log(dir, target, output)

    /** Prints [command] as if it had been typed and run in [cwd], with its [output]; the prompt is left empty. */
    fun log(cwd: String, command: String, output: String?) {
        // Mid-way through the opening message: it prints in full first, as a terminal would. (Clearing
        // it instead left the typer "finishing" an empty message, which printed a blank line.)
        if (bare) {
            history += Line(Kind.HUE, target)
            bare = false
            queued = null
        }
        history += Line(Kind.COMMAND, command, cwd, user)
        if (output != null) history += Line(Kind.OUTPUT, output)
        while (history.size > rows) history.removeAt(0)
        shown = ""
        target = ""
        hint = ""
        restart(0)
    }

    fun stop() = view.removeCallbacks(tick)

    private fun restart(delay: Long) {
        view.removeCallbacks(tick)
        cursorOn = true
        render()
        view.postDelayed(tick, delay)
    }

    private fun step() {
        val delay = when {
            // Not a prefix of the new command: backspace, quickly, like a held key.
            !target.startsWith(shown) -> { shown = shown.dropLast(1); ERASE_MS }
            shown != target -> {
                shown += target[shown.length]
                target.getOrNull(shown.length)?.let { keystrokeDelay(shown.last(), it, rnd) } ?: BLINK_MS
            }
            bare -> {
                // The opening message is done: it becomes a printed line, and the prompt appears after a beat.
                history += Line(Kind.HUE, shown)
                bare = false
                shown = ""
                val (cwd, command, suggestion) = queued ?: Triple(dir, "", "")
                queued = null
                dir = cwd
                target = command
                hint = suggestion
                PAUSE_MS
            }
            else -> { cursorOn = !cursorOn; BLINK_MS } // idle: the cursor blinks; while typing it stays solid
        }
        if (shown != target) cursorOn = true
        render()
        view.postDelayed(tick, delay)
    }

    private fun render() {
        val out = SpannableStringBuilder()
        val visible = history.takeLast(rows - 1)
        repeat(rows - 1 - visible.size) { out.append('\n') } // the prompt always sits on the bottom row
        for (line in visible) out.append(fit(styled(line))).append('\n')

        val prompt = SpannableStringBuilder()
        if (bare) prompt.append(colored(shown, hue.color)) else prompt.append(promptFor(dir)).append(colored(shown, Color.WHITE))
        val cursor = prompt.length
        prompt.append(' ')
        if (cursorOn) prompt.setSpan(BackgroundColorSpan(hue.color), cursor, cursor + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!bare && shown == target && hint.isNotEmpty()) prompt.append(colored(" # $hint", HINT))
        out.append(fit(prompt))
        view.text = out
    }

    /** One terminal row: anything wider than the screen is cut with an ellipsis rather than wrapped. */
    private fun fit(line: CharSequence): CharSequence {
        val width = view.width - view.paddingLeft - view.paddingRight
        return if (width <= 0) line else TextUtils.ellipsize(line, view.paint, width.toFloat(), TextUtils.TruncateAt.END)
    }

    private fun styled(line: Line): CharSequence = when (line.kind) {
        Kind.DIM -> colored(line.text, DIM_WHITE)
        Kind.HUE -> colored(line.text, hue.color)
        Kind.OUTPUT -> colored(line.text, OUTPUT)
        Kind.COMMAND -> promptFor(line.dir, line.user).append(colored(line.text, Color.WHITE))
    }

    /** `matrix@tv:~/apps$ `: user and path in the hue, punctuation in white, the way a coloured shell prompt looks. */
    private fun promptFor(cwd: String, who: String = user) = SpannableStringBuilder()
        .append(colored("$who@tv", hue.color, bold = true))
        .append(colored(":", Color.WHITE))
        .append(colored(cwd, hue.color, bold = true))
        .append(colored("$ ", Color.WHITE))

    private fun colored(text: String, color: Int, bold: Boolean = false) = SpannableStringBuilder(text).apply {
        if (text.isEmpty()) return@apply // zero-length EXCLUSIVE spans throw
        setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private companion object {
        const val ERASE_MS = 22L   // backspace held down
        const val BLINK_MS = 530L
        const val START_MS = 400L  // a beat before the opening message starts typing
        const val PAUSE_MS = 500L  // and after it, before the prompt's first keystroke
        val OUTPUT = Color.argb(215, 255, 255, 255)
        val HINT = Color.argb(105, 255, 255, 255)
    }
}

/**
 * How long to wait before typing [next], given that [previous] was the last key typed. This is the
 * whole "feel" of the terminal: every command on the prompt is typed through it.
 */
fun keystrokeDelay(previous: Char, next: Char, random: Random): Long {
    // TODO(human): give the typing a human rhythm. For now every key is exactly 35 ms apart.
    return 35L
}
