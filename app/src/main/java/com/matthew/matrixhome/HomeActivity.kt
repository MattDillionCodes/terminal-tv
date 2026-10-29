package com.matthew.matrixhome

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import java.util.concurrent.Executors

/**
 * Matrix home, styled as a clean terminal. A status line on top, then a live shell session
 * ([Terminal]): selecting an entry types its command at the prompt (`open youtube`), and OK runs
 * it, printing output before it acts. At the bottom, one line of text:
 *   youtube   cast moderator   play store   |   apps/   system/
 * Favourites first (up to five; hold OK to remove one), then two folders that open a pop-up menu
 * ([MenuView]): apps/ lists every TV app (hold OK to add or remove a favourite), system/ has help,
 * settings, name, wallpaper, color and ambient. The focused entry is in inverse video and the key
 * bar along the bottom says what the buttons do. `name` renames the prompt's user and the greeting
 * (`matthew@tv`, "Wake up, Matthew..."); the status line carries the weather ([Weather]).
 * First launch opens the help screen ([HelpView]) once; system/ > help reopens it.
 * The wallpaper sits dimmed far behind; the rain plays only on cold start and in ambient mode.
 * Layout is on the 960x540dp canvas of a 1080p TV (1dp = 2px), with 48dp side margins.
 */
class HomeActivity : Activity() {
    /** No icons or banners anywhere: an app is its name and package, printed as text. */
    private class App(val info: ActivityInfo, val label: String)

    private enum class Folder { APPS, SYSTEM }

    private lateinit var root: FrameLayout
    private lateinit var wallBack: ImageView
    private lateinit var wallFront: ImageView
    private lateinit var dimmer: View
    private lateinit var rain: RainView
    private lateinit var ui: FrameLayout
    private lateinit var weatherView: TextView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var terminal: Terminal
    private lateinit var line: LinearLayout
    private lateinit var lineScroll: HorizontalScrollView
    private lateinit var appsEntry: TermItem
    private lateinit var systemEntry: TermItem
    private lateinit var legend: TextView
    private lateinit var ambientBox: LinearLayout
    private lateinit var ambientClock: TextView
    private lateinit var ambientInfo: TextView

    /** Everything drawn in the hue registers here, so switching colours re-lights the whole screen. */
    private val tinters = ArrayList<(Hue) -> Unit>()
    private fun tint(block: (Hue) -> Unit) { tinters += block }

    private var allApps: List<App> = emptyList()
    private var hue = Hues.default
    private var sceneIndex = 0
    private var inAmbient = false
    private var help: HelpView? = null // non-null while the help screen is up
    private var menu: MenuView? = null // non-null while a pop-up menu is open
    private var menuFolder: Folder? = null
    private val menuLefts = HashMap<Folder, Int>()
    private var editingName = false // the name editor is up (Back returns to the system/ menu)
    private var swallowKeyUp = false
    private val loader = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor() // its own thread: a slow request never holds up a wallpaper
    private var weather: Weather? = null
    private var weatherAt = 0L
    private var rainAnim: ValueAnimator? = null
    private var motdIndex = -1
    private var redescribe: (() -> Unit)? = null // puts the focused entry's command back on the prompt

    private companion object {
        const val FAVORITE_SLOTS = 5
        const val CELL_GAP = 6
        const val TERMINAL_ROWS = 5
        const val LINE_BOTTOM = 64 // dp from the screen's bottom edge to the line, above the key bar
        const val LINE_H = 40
        const val MENU_W = 400
        const val MENU_ROWS = 6 // the menu fits between the line and the terminal; longer lists scroll
        const val NAME_INDEX = 2 // positions in the system/ menu, refocused after a rename or a colour change
        const val COLOR_INDEX = 4
        const val NAME_MAX = 16
        const val DEFAULT_USER = "matrix"
        const val DEFAULT_GREETING_NAME = "Neo"
        const val WEATHER_EVERY_MS = 15 * 60 * 1000L
        const val RUN_MS = 350L // output stays on screen this long before the command acts (opens an app...)
        const val ROTATE_EVERY_MS = 60 * 1000L
        const val AMBIENT_AFTER_MS = 3 * 60 * 1000L // screensaver: go ambient after this long idle on Home
        const val HOME_DIM = 0.88f // the wallpaper stays far back: on Home the text is all you read
        const val AMBIENT_DIM = 0.45f
        const val PREVIEW_DIM = 0.35f // while choosing a wallpaper: bright enough to see what changed
        const val MANUAL_FADE_MS = 600L // a change you asked for cross-fades quickly; the automatic ones take 1.5 s

        /** The first film's opening terminal, one line typed per visit to Home; %s is the user's name (or Neo). */
        val MOTD = listOf("Wake up, %s...", "The Matrix has you...", "Follow the white rabbit.", "Knock, knock, %s.")
    }

    /**
     * Kept out of apps/, as in Glass: Settings has its own entry under system/, and the TCL system
     * screens aren't apps you'd open from here. Unlike Glass, `com.tcl.tv` (live TV / HDMI inputs)
     * stays listed: Glass reaches inputs from its Control Center, Matrix has only apps/ > tv.
     */
    private val hiddenApps = setOf(
        "com.android.tv.settings", // Settings
        "com.tcl.suspension",      // Quick panel
        "com.tcl.dashboard",       // TCL HOME
    )

    /** Favourites until you change them (the same as Glass's dock); any free slots fill from apps/. */
    private val favoriteDefaults = listOf(
        "com.google.android.youtube.tv",
        "com.google.android.youtube.tvunplugged",
        "com.nuvio.tv",
        "tv.twitch.android.app",
        "com.wbd.stream",
    )

    private val prefs by lazy { getSharedPreferences("matrix", MODE_PRIVATE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hue = Hues.byId(prefs.getString("hue", null))
        sceneIndex = prefs.getInt("scene", -1)

        root = FrameLayout(this)
        wallBack = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        wallFront = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        dimmer = View(this).apply { setBackgroundColor(Color.BLACK); alpha = HOME_DIM }
        rain = RainView(this)
        ui = FrameLayout(this)
        for (v in listOf(wallBack, wallFront, dimmer, rain, ui)) root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        buildStatus()
        buildTerminal()
        buildLine()
        buildKeyBar()
        buildAmbient()
        setContentView(root)

        tinters.forEach { it(hue) }
        rain.hue = hue
        terminal.user = loginFor(savedName())
        loadApps()
        intro()
    }

    // ---- Layout ------------------------------------------------------------------------------

    private fun text(sizeSp: Float, face: Typeface = mono, color: Int? = null) = TextView(this).apply {
        typeface = face
        textSize = sizeSp
        color?.let { setTextColor(it) }
    }

    /** A 1dp rule in the hue at low strength: the only "chrome" on screen. */
    private fun hairline() = View(this).also { v -> tint { v.setBackgroundColor(it.dim) } }

    /** Top: `MATRIX  tty1 ........ 64°F clear   9:28 PM  WED 28 OCT`, then a rule. */
    private fun buildStatus() {
        val brand = text(12f, monoBold).apply { text = "MATRIX"; letterSpacing = 0.3f }
        weatherView = text(12f, color = DIM_WHITE).apply { setPadding(0, 0, dp(18), 0) } // empty until the first reading
        clock = text(12f, color = Color.WHITE)
        date = text(12f).apply { setPadding(dp(14), 0, 0, 0) }
        tint { brand.setTextColor(it.color); date.setTextColor(it.color) }
        ui.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(brand)
            addView(text(12f, color = DIM_WHITE).apply { text = "tty1"; setPadding(dp(14), 0, 0, 0) })
            addView(View(this@HomeActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(weatherView)
            addView(clock)
            addView(date)
        }, FrameLayout.LayoutParams(MATCH_PARENT, dp(24), Gravity.TOP).apply { setMargins(dp(48), dp(18), dp(48), 0) })
        ui.addView(hairline(), FrameLayout.LayoutParams(MATCH_PARENT, dp(1), Gravity.TOP)
            .apply { setMargins(dp(48), dp(46), dp(48), 0) })
    }

    /** The shell session: a fixed block of [TERMINAL_ROWS] rows, so nothing below it ever moves. */
    private fun buildTerminal() {
        val view = text(18f, color = Color.WHITE).apply {
            setLines(TERMINAL_ROWS)
            setLineSpacing(dpf(3), 1f)
        }
        // Glows stay at or under 10dp (20px): past HWUI's 25px blur limit a text shadow renders as a dark box.
        tint { view.setShadowLayer(dpf(4), 0f, 0f, withAlpha(it.color, 90)) }
        terminal = Terminal(view, TERMINAL_ROWS)
        tint { terminal.hue = it }
        ui.addView(view, FrameLayout.LayoutParams(dp(864), WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { setMargins(dp(48), dp(58), 0, 0) })
    }

    /**
     * The one line of text. It scrolls sideways only if five long favourite names don't fit, so it
     * never wraps onto a second line.
     */
    private fun buildLine() {
        line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), 0, dp(48), 0)
        }
        lineScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(line, FrameLayout.LayoutParams(WRAP_CONTENT, dp(LINE_H)))
        }
        ui.addView(lineScroll, FrameLayout.LayoutParams(MATCH_PARENT, dp(LINE_H), Gravity.BOTTOM)
            .apply { bottomMargin = dp(LINE_BOTTOM) })
    }

    /** Bottom, nano style: a rule, then each key in inverse video with what it does. */
    private fun buildKeyBar() {
        ui.addView(hairline(), FrameLayout.LayoutParams(MATCH_PARENT, dp(1), Gravity.BOTTOM)
            .apply { setMargins(dp(48), 0, dp(48), dp(44)) })
        legend = text(11f, color = Color.argb(200, 255, 255, 255))
        ui.addView(legend, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(dp(48), 0, 0, dp(18)) })
    }

    /** Ambient: a big clock with the weather and date under it, bottom left over the full-screen rain. */
    private fun buildAmbient() {
        ambientClock = text(64f, color = Color.WHITE)
        ambientInfo = text(16f)
        tint {
            ambientClock.setShadowLayer(dpf(10), 0f, 0f, withAlpha(it.color, 240))
            ambientInfo.setTextColor(it.color)
        }
        ambientBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0f
            // Explicit WRAP_CONTENT: the default (MATCH_PARENT) shrinks the clock to the info line's
            // width while keeping a one-line height, which cut "PM" off.
            addView(ambientClock, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(ambientInfo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(4) })
        }
        root.addView(ambientBox, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(dp(48), 0, 0, dp(36)) })
    }

    // ---- The line -----------------------------------------------------------------------------

    /** Rebuilds the line (after apps change, a pin, or a new colour), then focuses [refocus]'s pick. */
    private fun fillLine(refocus: () -> View? = { firstEntry() }) {
        // Removing the focused entry hands focus to the next one along, which would type its command
        // for a moment; hold focus off the line until it's rebuilt and the right entry is picked.
        ui.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        line.removeAllViews()

        val favs = favorites()
        favs.forEach { app -> line.addView(favoriteEntry(app), LinearLayout.LayoutParams(WRAP_CONTENT, dp(LINE_H)).apply { marginEnd = dp(CELL_GAP) }) }
        if (favs.isEmpty()) line.addView(text(14f, color = DIM_WHITE).apply { text = "no favorites yet" })
        line.addView(text(16f).also { bar -> bar.text = "|"; tint { bar.setTextColor(it.dim) } },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(14), 0, dp(14), 0) })

        appsEntry = folder("apps/", { "${allApps.size} apps · OK to browse" }) { openMenu(Folder.APPS) }
        systemEntry = folder("system/", { "help settings name wallpaper color ambient" }) { openMenu(Folder.SYSTEM) }

        root.post {
            if (menu != null) return@post // the open menu keeps focus; the line stays blocked behind it
            ui.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            (refocus() ?: firstEntry())?.requestFocus()
        }
    }

    private fun favoriteEntry(app: App) = TermItem(this, hue, app.label.lowercase(), 16f).apply {
        maxWidth = dp(190) // ~16 characters; longer names end in an ellipsis
        val keys = listOf("OK" to "open", "HOLD OK" to "remove from favorites", "◀ ▶" to "move")
        setOnClickListener { execute("launching ${app.info.packageName}") { launch(app.info) } }
        setOnLongClickListener { togglePin(app, fromMenu = false, entry = this); true }
        onFocus { f -> setFocusedLook(f); if (f) describe("~/favorites", "open ${arg(app.label)}", { app.info.packageName }, keys) }
    }

    /** A folder at the end of the line (`apps/`, `system/`): focusing it types `ls`, OK opens its menu. */
    private fun folder(name: String, hint: () -> String, open: () -> Unit) = TermItem(this, hue, name, 16f).apply {
        val keys = listOf("OK" to "open menu", "◀ ▶" to "move")
        setOnClickListener { terminal.run(null); open() }
        onFocus { f -> setFocusedLook(f); if (f) describe("~", "ls $name", hint, keys) }
        line.addView(this, LinearLayout.LayoutParams(WRAP_CONTENT, dp(LINE_H)).apply { marginEnd = dp(CELL_GAP) })
    }

    private fun firstEntry(): View? = line.getChildAt(0)?.takeIf { it.isFocusable } ?: appsEntry

    /** A name as a shell argument: lowercase, quoted when it has a space (`open "play store"`). */
    private fun arg(label: String) = label.lowercase().let { if (' ' in it) "\"$it\"" else it }

    private fun sceneName(index: Int) = Math.floorMod(index, Scenes.all.size).let {
        "${Scenes.label(Scenes.all[it]).lowercase()} (${it + 1}/${Scenes.all.size})"
    }

    /** Types the focused entry's command on the prompt and spells out what the buttons will do. */
    private fun describe(dir: String, command: String, hint: () -> String, keys: List<Pair<String, String>>) {
        redescribe = { describe(dir, command, hint, keys) }
        terminal.type(dir, command, hint())
        setLegend(keys)
    }

    /** The key bar: each key in inverse video, then what it does. */
    private fun setLegend(keys: List<Pair<String, String>>) {
        val text = SpannableStringBuilder()
        keys.forEach { (key, action) ->
            if (text.isNotEmpty()) text.append("    ")
            val start = text.length
            text.append(" $key ")
            text.setSpan(BackgroundColorSpan(hue.color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(Color.BLACK), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(" ").append(action)
        }
        legend.text = text
    }

    /**
     * OK on an entry: the command runs in the terminal ([output] printed under it), then a beat later
     * [then] does the real thing and the focused entry's command is typed again on the fresh prompt.
     */
    private fun execute(output: String?, then: (() -> Unit)?) {
        terminal.run(output)
        root.postDelayed({
            then?.invoke()
            if (!isFinishing) redescribe?.invoke()
        }, RUN_MS)
    }

    // ---- Pop-up menus -------------------------------------------------------------------------

    /**
     * Opens [folder]'s menu above its entry on the line, focusing entry [focus]. While it's open the
     * line can't take focus, so ▲ ▼ stay inside the menu; Back closes it.
     */
    private fun openMenu(folder: Folder, focus: Int = 0, animate: Boolean = true) {
        val entries = if (folder == Folder.APPS) appMenuEntries() else systemMenuEntries()
        val (title, count) = if (folder == Folder.APPS) "~/apps" to "${allApps.size}" else "~/system" to "${entries.size}"
        showMenu(folder, title, count, entries, focus, animate)
    }

    /** Puts a [MenuView] with [entries] above [folder]'s entry (the name editor uses this too). */
    private fun showMenu(folder: Folder, title: String, count: String, entries: List<View>, focus: Int, animate: Boolean) {
        closeMenu(restoreFocus = false, animate = false)
        val opener = if (folder == Folder.APPS) appsEntry else systemEntry
        val view = MenuView(this, hue, title, count, entries, MENU_ROWS)
        ui.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // A freshly rebuilt line (reopening after a pin or colour change) isn't laid out yet, so its
        // entries have no position: reuse where this menu opened last time instead of jumping left.
        val left = if (opener.isLaidOut) menuLeft(opener).also { menuLefts[folder] = it } else menuLefts[folder] ?: dp(48)
        root.addView(view, FrameLayout.LayoutParams(dp(MENU_W), WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(LINE_BOTTOM + LINE_H + 8)
            leftMargin = left
        })
        menu = view
        menuFolder = folder
        if (animate) view.reveal()
        view.post { (entries.getOrNull(focus) ?: entries.firstOrNull())?.requestFocus() }
    }

    /** Lines the menu's text up under its entry's text (the pointer triangles hang to its left), kept on screen. */
    private fun menuLeft(opener: View): Int {
        val at = IntArray(2).also { opener.getLocationInWindow(it) }[0] - IntArray(2).also { root.getLocationInWindow(it) }[0]
        return (at - dp(1) - dp(TermItem.POINTER_SPACE_DP)).coerceIn(dp(48), (root.width - dp(48) - dp(MENU_W)).coerceAtLeast(dp(48)))
    }

    private fun closeMenu(restoreFocus: Boolean = true, animate: Boolean = true) {
        val v = menu ?: return
        val opener = if (menuFolder == Folder.APPS) appsEntry else systemEntry
        if (editingName) getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
        menu = null
        menuFolder = null
        editingName = false
        previewWallpaper(false) // never leave the wallpaper bright behind a closed menu
        if (animate) v.animate().alpha(0f).setDuration(120).withEndAction { root.removeView(v) }.start() else root.removeView(v)
        if (restoreFocus) {
            ui.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            opener.requestFocus()
        }
    }

    /** apps/: every app, one per line; `*` marks the favourites, like the current branch in `git branch`. */
    private fun appMenuEntries(): List<View> {
        val favs = favorites().map { it.info.packageName }.toSet()
        return allApps.map { app ->
            val fav = app.info.packageName in favs
            TermItem(this, hue, (if (fav) "* " else "  ") + app.label.lowercase(), 14f, pointer = true).apply {
                val keys = listOf("OK" to "open", "HOLD OK" to if (fav) "remove from favorites" else "add to favorites",
                    "BACK" to "close")
                setOnClickListener { execute("launching ${app.info.packageName}") { closeMenu(); launch(app.info) } }
                setOnLongClickListener { togglePin(app, fromMenu = true, entry = this); true }
                onFocus { f -> setFocusedLook(f); if (f) describe("~/apps", "open ${arg(app.label)}", { app.info.packageName }, keys) }
            }
        }
    }

    /** system/: the same commands as before, now in a menu so the line stays one line. */
    private fun systemMenuEntries(): List<View> {
        fun item(label: String, command: String, hint: () -> String, key: Pair<String, String>,
                 focused: (Boolean) -> Unit = {}, run: () -> Unit) =
            TermItem(this, hue, label, 14f, pointer = true).apply {
                setOnClickListener { run() }
                onFocus { f ->
                    setFocusedLook(f)
                    focused(f)
                    if (f) describe("~/system", command, hint, listOf(key, "BACK" to "close"))
                }
            }
        return listOf(
            item("help", "man matrix", { "how to use this screen and the remote" }, "OK" to "show") {
                execute(null) { closeMenu(); showHelp() }
            },
            item("settings", "open settings", { "android settings" }, "OK" to "open") {
                execute("launching com.android.tv.settings") { closeMenu(); runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            },
            item("name", "whoami", { "${loginFor(savedName())} · OK to change" }, "OK" to "change name") {
                terminal.run(loginFor(savedName())) // whoami prints who you are, then the editor opens
                openNameEditor()
            },
            // While the cursor is on `wallpaper` the dimming lifts, so each change is actually visible.
            item("wallpaper", "wallpaper --next", { "now: ${sceneName(sceneIndex)}" }, "OK" to "next wallpaper",
                focused = { previewWallpaper(it) }) {
                execute("wallpaper: ${sceneName(sceneIndex + 1)}") { nextScene(MANUAL_FADE_MS) }
            },
            item("color", "color --next", { "now: ${hue.id} · next: ${nextHue().id}" }, "OK" to "next colour") {
                applyHue(nextHue())
            },
            item("ambient", "ambient", { "full-screen code and a clock · any button comes back" }, "OK" to "start") {
                execute("starting ambient · press any button to return") { closeMenu(); enterAmbient() }
            },
        )
    }

    // ---- Name ---------------------------------------------------------------------------------

    private fun savedName() = prefs.getString("name", null)

    /** The prompt's user: a name as a Unix login (`Matthew D` -> `matthew-d`), or `matrix` when there's none. */
    private fun loginFor(name: String?) =
        name.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { DEFAULT_USER }

    /** Who the greeting talks to: the saved name as typed (first letter capitalised), or Neo. */
    private fun greetingName() = savedName()?.replaceFirstChar { it.uppercase() } ?: DEFAULT_GREETING_NAME

    /**
     * system/ > name: a one-line editor in the menu's place. As you type, the terminal types the
     * rename it's about to run (`usermod -l matthew matrix`, the real Linux command) and the line
     * under the field previews the prompt. Done (or Enter) saves; Back goes back to the menu.
     */
    private fun openNameEditor() {
        val old = loginFor(savedName())
        val preview = text(12f, color = DIM_WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        val field = EditText(this).apply {
            typeface = mono
            textSize = 14f
            setTextColor(Color.WHITE)
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(InputFilter.LengthFilter(NAME_MAX))
            highlightColor = withAlpha(hue.color, 110)
            textCursorDrawable = GradientDrawable().apply { setColor(hue.color); setSize(dp(9), dp(18)) } // a block, like the prompt's
            setText(savedName().orEmpty())
            setSelection(text.length)
        }
        fun refresh() {
            val login = loginFor(field.text.toString())
            preview.text = if (field.text.isBlank()) "empty: back to $DEFAULT_USER@tv and $DEFAULT_GREETING_NAME" else "shows as $login@tv"
            terminal.type("~/system", "usermod -l $login $old", "done to save")
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        field.setOnEditorActionListener { _, action, event ->
            val done = action == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (done) saveName(field.text.toString())
            done
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(text(14f, color = DIM_WHITE).apply { text = "new name: " })
            addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        showMenu(Folder.SYSTEM, "~/system/name", "", listOf(row, preview), focus = 0, animate = false)
        editingName = true
        redescribe = null
        setLegend(listOf("OK" to "keyboard", "DONE" to "save", "BACK" to "cancel"))
        refresh()
        field.post {
            field.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Saves the name (empty resets it), prints the rename and its result, and goes back to the menu. */
    private fun saveName(raw: String) {
        val old = loginFor(savedName())
        val name = raw.trim().take(NAME_MAX).ifEmpty { null }
        prefs.edit().apply { if (name == null) remove("name") else putString("name", name) }.apply()
        val login = loginFor(name)
        terminal.log("~/system", "usermod -l $login $old", if (name == null) "welcome back, $DEFAULT_GREETING_NAME" else "welcome, ${greetingName()}")
        terminal.user = login // from here on the prompt is theirs; the line above keeps the old one
        openMenu(Folder.SYSTEM, focus = NAME_INDEX, animate = false)
    }

    // ---- Favorites ----------------------------------------------------------------------------

    /** Saved favourites in order; until you change them, the defaults that are installed, then the first apps. */
    private fun favorites(): List<App> {
        val pkgs = prefs.getString("favorites", null)?.split(',')?.filter { it.isNotBlank() }
            ?: (favoriteDefaults + allApps.map { it.info.packageName }).distinct()
        return pkgs.mapNotNull { p -> allApps.find { it.info.packageName == p } }.take(FAVORITE_SLOTS)
    }

    /** Hold OK: printed in the terminal as `fav add` / `fav rm`, with what happened. */
    private fun togglePin(app: App, fromMenu: Boolean, entry: View) {
        val pins = favorites().map { it.info.packageName }.toMutableList()
        val pkg = app.info.packageName
        val name = arg(app.label)
        val dir = if (fromMenu) "~/apps" else "~/favorites"
        when {
            pkg in pins -> { pins.remove(pkg); terminal.log(dir, "fav rm $name", "removed $name from favorites") }
            pins.size >= FAVORITE_SLOTS -> {
                terminal.log(dir, "fav add $name", "fav: favorites are full ($FAVORITE_SLOTS/$FAVORITE_SLOTS) · hold OK on one to remove it")
                root.postDelayed({ redescribe?.invoke() }, RUN_MS)
                return
            }
            else -> { pins.add(pkg); terminal.log(dir, "fav add $name", "added $name to favorites") }
        }
        prefs.edit().putString("favorites", pins.joinToString(",")).apply()
        if (fromMenu) {
            // Same menu, same line, with the `*` updated.
            fillLine()
            openMenu(Folder.APPS, focus = allApps.indexOf(app), animate = false)
        } else {
            val index = line.indexOfChild(entry)
            fillLine { line.getChildAt(index.coerceAtMost(pins.size - 1))?.takeIf { pins.isNotEmpty() && it.isFocusable } }
        }
    }

    // ---- Colour, scenes, rain -----------------------------------------------------------------

    private fun nextHue() = Hues.all[(Hues.all.indexOf(hue) + 1) % Hues.all.size]

    private fun applyHue(new: Hue) {
        terminal.run("signal set to ${new.label.lowercase()}")
        hue = new
        prefs.edit().putString("hue", new.id).apply()
        tinters.forEach { it(new) }
        rain.hue = new
        showScene(fadeMs = 900)
        // The menu's entries were drawn in the old colour: redraw it in place, still on `color`, so
        // pressing OK again keeps stepping through them.
        fillLine()
        openMenu(Folder.SYSTEM, focus = COLOR_INDEX, animate = false)
    }

    private val rotation = Runnable { nextScene() }

    /** Lifts the dimming so the wallpaper itself can be seen (system/ > wallpaper), or puts it back. */
    private fun previewWallpaper(on: Boolean) {
        if (inAmbient) return // ambient has its own dimming
        dimmer.animate().alpha(if (on) PREVIEW_DIM else HOME_DIM).setDuration(350).start()
    }

    /** The next of the six scenes, in the current colour. Also runs every time Home comes back. */
    private fun nextScene(fadeMs: Long = 1500) {
        sceneIndex = (sceneIndex + 1) % Scenes.all.size
        prefs.edit().putInt("scene", sceneIndex).apply()
        showScene(fadeMs)
        root.removeCallbacks(rotation)
        root.postDelayed(rotation, ROTATE_EVERY_MS)
    }

    /** Decodes off the main thread, then cross-fades: the new art fades in on top, then becomes the back layer. */
    private fun showScene(fadeMs: Long = 1500) {
        val h = hue
        val scene = Scenes.all[sceneIndex.coerceAtLeast(0)]
        loader.execute {
            val bmp = Scenes.load(this, h, scene) ?: return@execute
            root.post {
                if (isDestroyed) return@post
                wallFront.animate().cancel()
                wallFront.setImageBitmap(bmp)
                wallFront.alpha = 0f
                wallFront.animate().alpha(1f).setDuration(fadeMs).withEndAction {
                    wallBack.setImageBitmap(bmp)
                    wallFront.alpha = 0f
                    wallFront.setImageDrawable(null)
                }.start()
            }
        }
    }

    /** Animates the rain's strength; at 0 it stops drawing, so Home costs nothing while it's still. */
    private fun moveRain(to: Float, ms: Long) {
        // One at a time: leaving ambient before its 1.2s fade-in finished used to leave that fade-in
        // running, and it pulled the rain back up to full over Home.
        rainAnim?.cancel()
        rainAnim = ValueAnimator.ofFloat(rain.intensity, to).apply {
            duration = ms
            interpolator = ease
            addUpdateListener { rain.intensity = it.animatedValue as Float }
            start()
        }
    }

    /** Cold start: "jacking in". Full-screen rain first, then it drains away and the terminal is there. */
    private fun intro() {
        ui.alpha = 0f
        rain.intensity = 1f
        root.postDelayed({
            ui.animate().alpha(1f).setDuration(600).start()
            moveRain(0f, 900)
            // First launch ever: once the terminal has surfaced, explain it.
            if (!prefs.getBoolean("help_seen", false)) root.postDelayed({ showHelp() }, 900)
        }, 1400)
    }

    // ---- Help ---------------------------------------------------------------------------------

    private fun showHelp() {
        if (help != null || inAmbient) return
        help = HelpView(this, hue).also { v ->
            v.alpha = 0f
            root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            v.animate().alpha(1f).setDuration(250).start()
        }
    }

    /** Any button closes it (see [dispatchKeyEvent]); after the first time it never opens by itself again. */
    private fun closeHelp() {
        val v = help ?: return
        help = null
        prefs.edit().putBoolean("help_seen", true).apply()
        v.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(v) }.start()
    }

    // ---- Ambient (screensaver) ----------------------------------------------------------------

    private val idleAmbient = Runnable { enterAmbient() }

    private fun restartIdle() {
        root.removeCallbacks(idleAmbient)
        root.postDelayed(idleAmbient, AMBIENT_AFTER_MS)
    }

    private fun enterAmbient() {
        if (inAmbient || help != null) return
        closeMenu()
        inAmbient = true
        updateClock()
        ui.animate().alpha(0f).setDuration(600).start()
        dimmer.animate().alpha(AMBIENT_DIM).setDuration(900).start()
        ambientBox.animate().alpha(1f).setDuration(900).start()
        moveRain(1f, 1200)
    }

    private fun exitAmbient() {
        if (!inAmbient) return
        inAmbient = false
        ui.animate().alpha(1f).setDuration(300).start()
        dimmer.animate().alpha(HOME_DIM).setDuration(300).start()
        ambientBox.animate().alpha(0f).setDuration(200).start()
        moveRain(0f, 400)
    }

    // ---- Session and clock --------------------------------------------------------------------

    /**
     * Each visit to Home is a new session, the way a terminal greets you: when you were last here,
     * then the next of the film's opening lines typed out, then the prompt with the focused entry.
     */
    private fun startSession() {
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_login", now)
        prefs.edit().putLong("last_login", now).apply()
        terminal.reset("Last login: ${DateFormat.format("EEE MMM d HH:mm:ss", last)} on tty1")
        motdIndex = (motdIndex + 1) % MOTD.size
        terminal.say(MOTD[motdIndex].format(greetingName()))
        redescribe?.invoke() // queued behind the opening line; on a cold start focus arrives a moment later
    }

    private fun updateClock() {
        val now = Date()
        clock.text = DateFormat.getTimeFormat(this).format(now)
        date.text = DateFormat.format("EEE d MMM", now).toString().uppercase()
        ambientClock.text = clock.text
        ambientInfo.text = listOfNotNull(weather?.toString(), date.text.toString()).joinToString("  ·  ")
    }

    private val weatherTick = Runnable { refreshWeather() }

    /** Every 15 min while Home is on screen (and on coming back, if the last reading is older). Offline keeps the last one. */
    private fun refreshWeather() {
        root.removeCallbacks(weatherTick)
        root.postDelayed(weatherTick, WEATHER_EVERY_MS)
        if (weather != null && SystemClock.elapsedRealtime() - weatherAt < WEATHER_EVERY_MS) return
        net.execute {
            val reading = runCatching { Weather.fetch() }.getOrNull() ?: return@execute
            root.post {
                if (isDestroyed) return@post
                weather = reading
                weatherAt = SystemClock.elapsedRealtime()
                weatherView.text = reading.toString()
                updateClock()
            }
        }
    }

    // ---- Apps ---------------------------------------------------------------------------------

    private fun loadApps() {
        // Other home screens (Glass, Play Home...) are left out: opening a launcher from a launcher only confuses.
        val launchers = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        allApps = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .filter { it.activityInfo.packageName !in launchers && it.activityInfo.packageName !in hiddenApps }
            .map { ri -> App(ri.activityInfo, ri.loadLabel(packageManager).toString()) }
            .sortedBy { it.label.lowercase() }
        closeMenu(restoreFocus = false, animate = false) // its list may be out of date
        fillLine()
    }

    private fun launch(ai: ActivityInfo) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setClassName(ai.packageName, ai.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
    }

    // ---- Lifecycle and keys -------------------------------------------------------------------

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateClock()
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadApps()
    }

    override fun onStart() {
        super.onStart()
        updateClock()
        registerReceiver(clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        rain.running = true
        nextScene() // a new scene every time you come back to Home
        startSession()
        refreshWeather()
    }

    override fun onResume() {
        super.onResume()
        restartIdle()
    }

    override fun onPause() {
        root.removeCallbacks(idleAmbient) // only while Home is on screen, never over another app
        super.onPause()
    }

    override fun onStop() {
        rain.running = false
        terminal.stop()
        root.removeCallbacks(rotation)
        root.removeCallbacks(weatherTick)
        unregisterReceiver(clockReceiver)
        unregisterReceiver(packageReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        net.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) restartIdle()
        // In ambient any button just brings the home screen back; that press (down + up) does nothing else.
        if (inAmbient) {
            if (event.action == KeyEvent.ACTION_DOWN) { exitAmbient(); swallowKeyUp = true }
            return true
        }
        // The same for the help screen: any button closes it and does nothing else, so a first press
        // can't open an app hiding behind it.
        if (help != null) {
            if (event.action == KeyEvent.ACTION_DOWN) { closeHelp(); swallowKeyUp = true }
            return true
        }
        if (swallowKeyUp) {
            if (event.action == KeyEvent.ACTION_UP) swallowKeyUp = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Home while already home: close whatever's open and return to the first favourite. */
    private fun goHome() {
        exitAmbient()
        closeHelp()
        closeMenu(restoreFocus = false, animate = false)
        ui.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        lineScroll.scrollTo(0, 0)
        firstEntry()?.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        goHome()
    }

    /**
     * Back cancels the name editor (back to the system/ menu), closes an open menu (back onto its
     * folder), and otherwise returns to the first favourite. With the keyboard up, Back closes that first.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            when {
                editingName -> openMenu(Folder.SYSTEM, focus = NAME_INDEX, animate = false)
                menu != null -> closeMenu()
                else -> goHome()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
