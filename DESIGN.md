---
name: Fish
description: A host-first, flat native terminal workspace.
colors:
  terminal-bg: "#101213"
  panel-bg: "#171a1c"
  divider: "#38464d"
  text: "#dde3e5"
  muted: "#b1c1c9"
  focus: "#87bbc8"
  error: "#ffa3a3"
typography:
  body:
    fontFamily: "Segoe UI"
    fontSize: "12px"
    fontWeight: 400
  terminal:
    fontFamily: "Cascadia Mono, Consolas, Monospaced"
    fontSize: "16px"
    fontWeight: 400
  status:
    fontFamily: "Consolas"
    fontSize: "11px"
    fontWeight: 400
components:
  pane-header:
    backgroundColor: "{colors.terminal-bg}"
    textColor: "{colors.muted}"
    height: "25px"
---

# Design System: Fish

## Overview

The user's pinned reference is the visual authority: near-black flat split
panes, thin dividers, compact headers and crisp monospace host text.
The terminal presentation space owns the window. Application controls remain
secondary; there are no promotional surfaces, dashboard cards or AI controls.

## Colors

`AppTheme` supplies complete Charcoal, Graphite, Midnight, Paper, OLED,
OLED Amber, High Contrast, High Contrast Light, Lagoon, Afterglow and Eye Saver token sets;
`ColorRole` inventories every semantic UI, host, effect and logo RGB role.
`ColorTheme` supplies complete named maps; `Ui` applies them to every owned UI route.
The Theme Designer previews transactionally and saves reusable workspace themes.
Custom values are exact, with advisory contrast warnings rather than forced corrections.
`FlatControls` shares flat delegates
for buttons, combos, tabs, scrollbars and split dividers. `HostPalette` defines
sixteen host colors independently, including darker foregrounds for Paper.
Never collapse host color, reverse, underline, blink or non-display distinctions
into an application accent. Twelve workstation palettes alter restrained
entries, not the terminal's attribute semantics.
An active custom theme owns all sixteen host slots; a built-in app theme leaves
the profile palette in charge. Selection/cursor, six gradient stops, glow,
active tint and header/footer/status colors have separate roles. Structure > Divider
is the single color for every owned structural edge, including optional window/pane
outlines; old LINE/OUTLINE roles link to the saved Divider on version-6 migration.

## Typography

Native controls use regular Segoe UI with Windows desktop font-rendering hints.
Terminal fonts are discovered fixed-width families or a validated local TTF/OTF.
Choices are workspace-wide; glyph coverage and cell widths are checked for the
current code pages. A missing file produces an explicit temporary fallback,
not loss of the saved choice. Fonts are not uploaded or bundled with releases.
Each glyph is positioned on a host cell; proportional text layout must never
replace the fixed grid. Fit derives font size from the actual pane and host
dimensions. Glyphs are generated at an integer physical font size with snapped
physical cell advances/baselines and LCD antialiasing, not a fractional font
transform or bilinear bitmap enlargement. Manual zoom uses the 16px base and scrolling.

## Layout

The demo's reference composition is one large left pane and two stacked right
panes. Persistent bounded slots support 1-8 sessions: 1 full, 2 side-by-side,
3 reference panes, 4 quadrants, then top/bottom 3/2, 3/3, 4/3 and 4/4.
Binary containers are an implementation detail, never arbitrary subdivisions.
Header dragging and keyboard moves only swap sessions. A divider has a
4px draggable target but paints a 1px line. Headers are 25px high with 8px
horizontal insets. Display origins retain at least 12px horizontal and 10px
vertical padding. Resizing does not resize the host's presentation space.
The main window is undecorated: one 31px dark title/menu row replaces the native
titlebar and separate menu row. The draggable title area stays empty between
menus/compact + Pane control and three 36px window controls.
The leftmost mark is a true 45-degree square with its two left sides extended
straight through the left corner to form one X and a triangular tail on the left.
The tail tips end level with the square's top and bottom, without a line joining them.
One open stroked path supplies the square and tail without overlapping strokes.
The silhouette is 3:2 while the body retains equal side lengths and exactly 45-degree rotation.
There is exactly one crossing, no eye, mouth or stretched rhombus.
A 3.4/64 stroke, uniform scale and the same geometry supply titlebar,
runtime/taskbar and multi-resolution Windows executable icons. The titlebar alone
uses a 27x20 drawing viewport inside its unchanged 48x30 focus/click target.
Its compact mode follows measured menu/action widths while reserving a drag area.
The borderless mark is a focusable button with balanced insets and keyboard
focus indication as a subtle underline, never a mouse-click box. Single clicks toggle the active preset's persistent Flux (animated)/None
effect; mouse release and keyboard Space are immediate. A double-click's second
activation returns to the preceding effect; dragging never activates.
No logo shimmer remains.
The titlebar is 31 logical pixels tall, with all controls centered in its actual
layout; native hit regions follow those bounds and scale to current client pixels.
Pane controls use transparent resting surfaces and the same type/rhythm
as menus, with modest monochrome icons, hover/pressed feedback and keyboard focus.
They must not appear as persistent filled chips against the titlebar.
The Fish-owned native bridge preserves standard overlapped resize/maximize
styles while consuming nonclient painting. Hit testing returns caption, edge
or maximize codes only for the corresponding targets, allowing OS snap/move
loops off the EDT and Windows 11 maximize hit testing. Inactive windows must
not acquire a second native caption. Narrow windows collapse menus/tool labels.
AWT refreshes native icons when changing monitors. WM_SETICON/WM_SETTEXT default
handlers can draw a classic caption directly without WM_NCPAINT. Temporarily
clearing WS_VISIBLE only around their synchronous forwarding suppresses that
draw without dropping the actual icon/title update or altering move/snap styles.
The bridge is reattached after peer recreation; normal DPI messages still reach
AWT, and hit regions are mapped from logical layout against current client size.

## Elevation & Depth

Flat surfaces, subtle tonal separation, no decorative shadows. A translucent
drop preview is temporary interaction feedback. Soft glow adds opt-in ink halos;
Prism applies a column-wise hue gradient. Effects are
rasterized into tight opaque native-resolution row tiles with separate
lit/blink-dim caches. Backgrounds and selection are
composited before caching to avoid expensive transparent-image resampling.
Overlapping row margins include neighboring ink so they cannot erase it.
Caches refresh on host/profile/theme/selection/background changes or a
fitted physical-font-size change, not every resize frame. No effect draws non-display
glyphs or replaces reverse-video backgrounds/host attributes.

Flux is an explicitly opt-in flowing text-gradient preset, not animated chrome.
Neutral text carries the rainbow; chromatic host text stays within its hue
family and reverse video keeps its original colors. None and Flux use identical
cached physical-size, per-channel LCD glyph masks, changing only color and never
switching between LCD/grayscale AA or changing glyph weight. The composited
surface is limited to the visible host area; no blur/upscale pass is involved.
One coalescing
50ms clock serves visible connected Flux terminals and stops when none are
eligible, including minimized windows. Host blink, secrecy, selection and
cursor remain independent. None is still the default and immediate stop control.

## Shapes

Rectangular panes and shared flat Swing controls. Hairline borders and the visible
focus underline provide structure without rounded containers or ornamental chrome.
Structural edges are exactly one physical device pixel, including at 150% DPI.
`Hairline` uses Swing's fractional-device endpoint rule and never enlarges dirty clips; no bevel,
compound perimeter, native shadow or rounded/cut corner is added.
Root panes share the corresponding dialog/menu/workspace surface color so
fractional-DPI inset gaps cannot reveal an unthemed inner strip.
The optional whole-window perimeter is on by default, independent of active
pane outline/tint, and configured only under Appearance > More settings >
Window and focus. Native move/resize/snap does not depend on a visible outline.
A rectangular native window region removes classic caption corner clipping
without losing resize/snap styles. Known remaining defect: maximize/restore
still offsets/clips the native inset-dependent drawing surface, hiding the
outer perimeter. This path failed real 100%/150% monitor checks; it is not signed off.

## Components

Pane headers identify the profile and connection, support dragging and expose
a right-click menu. The close X occupies the far-right header position, outside
the drag target, or a zero-space upper-right overlay when information is hidden.
It is independent of the selected header items and does not overlap Reset.
An invisible, zero-layout-space pane-top drag target at X height supplies the same
swap action when a header is hidden; controls remain excluded and normal terminal
selection starts below it.
X disconnects and removes the pane, reflowing bounded slots;
only the final pane is retained as an empty offline tile. Window
close disconnects all without confirmation. An offline tile contains only a
centered preset selector and adjacent arrow Connect icon with tooltip and
accessible name. Collapsed combo values never use selection coloring just
because the combo has focus; focus stays on its border. Newly added panes select the last
used preset; restored slots preserve their own selection. No visible A1/A2 IDs.
One subtly tinted active surface spans header, terminal and
footer; a constant-inset, physical-pixel outer focus border replaces the old terminal-only focus box
and header underline. The optional pane-background tint and active outline can be disabled independently;
outline off means zero border insets/paint, not a transparent spacer. Shared pane
dividers, titlebar edges and an enabled window perimeter own their boundaries:
the pane outline never adds a parallel second divider. OLED
remains true black. Activating a pane never replaces its border or reshow/validates
an already-visible card. A terminal status line reports connection, inhibition,
dimensions and cursor position, with Reset visible only for the core's actual
recoverable input-error state. Header/footer visibility and all existing text
items are workspace preferences. Empty/disabled rows reserve no space.
Without a footer, Reset is a keyboard-reachable, non-focus-stealing corner
overlay opposite the editing cursor. Hidden status errors use temporary notices.
Host-busy/disconnected states never receive a false unlock. Alt+drag/context
menus and keyboard actions remain available when headers are hidden.
Enter is never remapped to recovery.
Settings is one visible action beside + Pane and Ctrl+Comma, with persistent,
direct category navigation: Appearance, Profiles, Keyboard Shortcuts, General,
Updates. The modeless dialog shows actual editable controls, not launcher cards.
Appearance orders workspace theme, selected-profile palette/effect, workspace
font, pane information and window/focus controls. Its selected-profile scope is
explicit and never silently switches a pane or changes every saved profile.
One shared profile list/form is embedded in Settings and reused by the operational
Profiles dialog; only genuinely optional host/device/code-page/Kerberos identity
fields live under Advanced. Username/password controls appear only in the proper
mode. Labels and mnemonics accompany controls. Owned forms, confirmations, errors and System Request
prompts share custom draggable chrome, Escape dismissal and icon-free content.
Editable settings have Cancel/Apply/Done in reading/tab order: Apply stays open
and updates the baseline, Done accepts pending changes and closes, dismissal
discards only drafts since Apply. Immediate selectors do not gain transaction
buttons or tautological save helper text. Profile credential work stays asynchronous.
Custom window controls retain visible keyboard focus. Native Alt+Space is not
shadowed by a second popup; Ctrl+Alt+Space exposes the dark window menu.
The Commands window is removed. A single registry backs menus, terminal context
menus and shortcuts; disabled actions remain disabled and modal focus isolates
host input. Former Commands chords are retired no-ops until explicitly reassigned.
Every app/host action is
listed in the global shortcut editor, with recording, unbind/default controls,
atomic conflict rejection and format-13 exact, multiline persistence. Window-scoped dispatch
precedes Swing traversal/host input, so Ctrl+Tab works without taking keys in
owned dialogs. Older per-profile definitions migrate globally with exact
conflict archives; retired Settings routes migrate to the canonical action,
preserving original lists. Menu labels show one primary available chord; the
inline editor shows all bindings, exact-location recording and recoverable conflicts.
Edit contains clipboard operations; Inserts groups sign-on helpers, field/screen
host inserts and the complete chooser. Pane actions have no one-child cascades.
Help > Tutorials contains five event-driven, modeless real-control guides.
Highlights follow their production controls/windows without intercepting input.
Next, Back and both skip controls remain available; opening/replaying a guide
never connects, creates a profile or saves credentials.
Updates embeds the shared safe offline Markdown renderer, with explicit safe
links and no automatic image/resource/code loading.

This task-based topology follows recognition-over-recall and conventional direct
settings navigation rather than progressive disclosure of everyday controls:
https://www.nngroup.com/articles/ten-usability-heuristics/ and
https://learn.microsoft.com/en-us/windows/apps/design/app-settings/guidelines-for-app-settings .
Shared native-font antialiasing applies to control labels, menus and buttons;
mnemonic underlines appear on Alt/menu activation rather than persistently.
The offline full manual uses a full-text topic index; Getting Started is first.
The compact terminal clipboard popup and Edit menu share those same guarded
Copy/Paste actions, preserve the selection on right-click, report clipboard
errors, and return focus to the terminal without taking host Ctrl+C/V or F-keys.

## Painting and resize

Every terminal paint explicitly clears its clipped background. An opaque bare
Swing component cannot rely on a UI delegate to do this. Regular-weight glyph
runs and their fixed physical-cell positions are cached until host/input state
or fitted physical font size changes; repainting never scales cached default
text. Idle cursor blinking repaints its
small region unless host blinking text requires a full terminal redraw.

## Do's and Don'ts

- Keep host function keys distinct from Ctrl+Alt application shortcuts.
- Show focused terminals and docking targets without obscuring host text.
- Keep hidden host data hidden in rendering and clipboard output.
- Do not replace a disconnected terminal with decorative demo content.
- Do not change host dimensions to make a pane fit.
- Do not enable effects by default or use them to conceal protocol limitations.
