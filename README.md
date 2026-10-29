# Matrix Home

A Google TV home screen styled as a clean Matrix terminal: monospace text on black, a typed
prompt, inverse-video selection, no app icons or banners anywhere, and seven colours that re-light
the whole screen. Kotlin, no dependencies, views built in code (same approach as Glass and Play Home).

```bash
./run.sh
```

`run.sh` boots the `Matrix_TV` emulator (Android TV 14, 1080p) if it isn't up, builds, installs,
and makes Matrix the Home app. It finds the emulator by AVD name, so other launchers' emulators
(FrameHome_TV, Glass_TV, PlayHome_TV...) and the real TV are never touched. It also disables the
stock Google TV launcher on that emulator, as on the TV: it declares a higher HOME priority and
otherwise wins even after `set-home-activity`.

## Layout

```
MATRIX  tty1                                  67°F cloudy   9:44 PM  WED 28 OCT
────────────────────────────────────────────────────────────────────────────────
Last login: Wed Oct 28 22:03:12 on tty1
Wake up, Neo...
matrix@tv:~/favorites$ fav add youtube
added youtube to favorites
matrix@tv:~/apps$ open "play store"█ # com.android.vending

                                         ┌ ~/apps ─────────────── 3 ┐
                                         │ * cast moderator          │
                                         │ [* play store           ] │
                                         │ * youtube                 │
                                         └───────────────────────────┘
 youtube   cast moderator   play store   |   apps/   system/
────────────────────────────────────────────────────────────────────────────────
 OK  open    HOLD OK  remove from favorites    BACK  close
```

- **The top is a live shell session** (`Terminal.kt`). Selecting an entry types its command at
  the prompt (`open youtube`, `open "play store"`, `man matrix`, `wallpaper --next`, `color --next`).
  Moving edits it like a person would: backspace to what both commands share, then type the rest.
  Once typed, details show greyed after the cursor like a shell autosuggestion. OK runs it: the
  command and its output scroll up (`launching com.google.android.youtube.tv`) and a beat later it
  acts. Hold OK prints `fav add` / `fav rm` with what happened. Each visit to Home is a new session:
  `Last login:` (when you were last here), then one of the film's opening lines typed out.
- **The bottom is one line of text**: your favourites (up to 5; hold OK on one to remove it), a
  `|`, then two folders, written with a trailing `/` the way `ls -F` marks directories. It scrolls
  sideways only if five long names don't fit, so it never wraps. Entries have no glow, so the one
  inverse block is what stands out.
- **Folders open a pop-up menu** (`Menu.kt`): a small terminal window drawn up from the line,
  lined up under its folder, one entry per line like `ls -1`; it prints its lines one by one.
  The highlighted line has a triangle to its left, like a menu cursor; the other lines keep the
  space so nothing shifts (drawn as a shape: a `▶` glyph from the fallback font isn't one
  monospace cell wide).
  `apps/` lists every TV app (other home screens are left out), `*` on the favourites like the
  current branch in `git branch`; hold OK adds or removes one. `system/` has help, settings,
  name, wallpaper, color and ambient. ▲ ▼ stay inside the menu, Back closes it onto its folder;
  past 6 entries it scrolls.
- **Wallpaper preview** (`system/ > wallpaper`): while the cursor is on it the dimming lifts (to
  65% visible) so you can see the scene; each OK cross-fades to the next in 0.6 s (automatic
  changes take 1.5 s). Moving off it, or closing the menu, puts the clean dark background back.
- **Your name** (`system/ > name`): `whoami` prints the current user, then a one-line editor opens
  in the menu's place with the TV keyboard. As you type, the terminal types the rename it will run
  (`usermod -l matthew matrix`) and a line under the field previews the prompt. Done (Enter on a
  keyboard) saves: the prompt becomes `matthew@tv` (a Unix-style login: lowercase, other characters
  become `-`) and the greeting uses the name as typed ("Wake up, Matthew...", "Knock, knock,
  Matthew."). Saving it empty goes back to `matrix@tv` and Neo; Back cancels. Lines already in the
  scrollback keep the user who ran them, as in a real shell.
- **Weather** (`Weather.kt`): `67°F cloudy` in the status line and under the ambient clock, from
  Open-Meteo (free, no key) for Los Angeles, the same place Glass uses. Words, not icons. Refreshed
  every 15 min while Home is on screen, on its own thread; offline keeps the last reading.
- **Help screen** (`Help.kt`): a `man matrix` page explaining the remote, the line and the menus.
  It opens by itself once, on the very first launch (pref `help_seen`), and from `system/ > help`
  any time after. Any button closes it, and that press does nothing else.
- **Selection is inverse video**: a solid block of the colour with black text, changing instantly
  like a cursor.
- **The key bar** (nano style) always says what the buttons do for the focused entry. Home (or
  Back on the line) returns to the first favourite.
- **Colour** is one entry, `system/ > color`: OK steps to the next of the seven (`color --next`,
  "signal set to red pill"), and focus stays on it so repeated OKs cycle. Switching re-lights
  everything (accents, rules, rain) and swaps the wallpaper set. Accent colours were measured from
  each set's wallpapers (brightest 0.25-1% of pixels), not picked.
- **Wallpapers.** 6 scenes (cascade, depth, tunnel, floor, scan, wet glass) x 7 hues, bundled in
  `app/src/main/assets/wallpapers/<hue>/<scene>.jpg` (originals in `~/Downloads/Matrix Wallpapers`),
  dimmed to 12% on Home so only the text reads. A new scene each time you come back Home and every
  60 s. All are already 1920x1080 sRGB, so they decode straight into GPU (HARDWARE) bitmaps.
- **Cold start** "jacks in": full-screen rain, which drains away to leave the terminal.
- **Ambient.** After 3 min idle on Home (or `ambient`): full-screen rain over the wallpaper, a
  big clock, and the weather and date under it. Any button comes back, and that press does nothing else.

On the emulator your Mac keyboard is the remote: arrows move, Enter is OK (hold it for HOLD OK),
Esc is Back. That needs `hw.keyboard=yes` in the AVD's `config.ini`; `avdmanager` creates TV
devices with `no`, and then key presses in the emulator window never reach the TV.

## The rain (`Rain.kt`, `Glyphs.kt`)

Done the way the films do it (per Rezmason's frame-by-frame study of the originals): glyphs sit
still in a fixed grid and what falls is light. Each column has two raindrops, a near-white tracer
with a fading tail, sweeping through stationary symbols while random cells change glyph. Glyphs are
Simon Whiteley's recipe: mirrored half-width katakana, numerals and a few symbols.

Things that are not obvious:
- Glyphs are pre-rendered once into `ALPHA_8` atlases. Alpha-only bitmaps draw in the paint's
  colour, so a colour change is just a different `paint.color`, with no re-rendering or filters.
- It steps at ~30 fps (`postInvalidateDelayed(33)`); the film's rain moves cell by cell anyway.
  At intensity 0 (all of Home) it stops drawing, so Home only redraws for the cursor blink.
  Its fades go through one animator (`moveRain`), and each new one cancels the last: leaving
  ambient before its 1.2 s fade-in ended used to leave that fade-in running, putting the rain
  back at full over Home.
- Text glows (`setShadowLayer`) stay at 10dp = 20px or less. Above HWUI's 25px blur limit the
  shadow renders as a dark box behind the text.
- Katakana come from the system's Noto CJK font; if a build lacks them (`Paint.hasGlyph`), the
  rain falls back to numerals and symbols only.

## On the TV

The TCL QM5K (Android TV 12, API 31) is for testing only: Matrix is installed there as an app you
open, never as its Home.

```bash
./install-tv.sh   # build the release, install and open Matrix; the TV's Home app is left as it was
```

Measured on the TV (PowerVR, 1080p UI): median frame 6 ms, 90th percentile 24 ms, 3.6% janky
(the cold-start rain and wallpaper decodes), 47 MB PSS. Noto CJK is on the TV, so the rain uses
real katakana. Weather loads over Wi-Fi.

Things that are not obvious:
- Installing Matrix the first time cleared the TV's saved Home choice (it resolved to Android's
  "choose a home app" picker). With the Google launcher disabled, Home would have shown that
  picker; `install-tv.sh` notes the TV's Home before installing and puts that same one back.
- apps/ hides the same TCL system screens as Glass (Quick panel, TCL HOME) but keeps `tv`
  (`com.tcl.tv`, live TV / HDMI inputs): Glass switches inputs from its Control Center, Matrix
  has no other way there.

Matrix has no remote-button remapper of its own; Glass's `KeyRemapService` (TCL TV button ->
ambient, i -> Control Center) sends those buttons to Glass, not here.
