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
OLED Amber, High Contrast and High Contrast Light token sets;
`Ui` applies them to every owned UI route. `FlatControls` shares flat delegates
for buttons, combos, tabs, scrollbars and split dividers. `HostPalette` defines
sixteen host colors independently, including darker foregrounds for Paper.
Never collapse host color, reverse, underline, blink or non-display distinctions
into an application accent. Twelve workstation palettes alter restrained
entries, not the terminal's attribute semantics.

## Typography

Native controls use Segoe UI, terminal glyphs use an installed monospace face.
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
The main window is undecorated: one 28px dark title/menu row replaces the native
titlebar and separate menu row. The draggable title area stays empty between
menus/compact + Pane and Commands controls and three 36px window controls.
The leftmost mark is a compact gradient triangle/rhombus fish, tail left and
head right. The only visible crossing is above; a small cut separates the
lower intersection. No eye, mouth or intermediate wonky polygon angles.
A 3.4/64 stroke and the same geometry supply titlebar, runtime/taskbar and
multi-resolution Windows executable icons. The mark has balanced own insets;
a click gives a brief, local color shimmer without touching session state.
The Fish-owned native bridge preserves standard overlapped resize/maximize
styles while consuming nonclient painting. Hit testing returns caption, edge
or maximize codes only for the corresponding targets, allowing OS snap/move
loops off the EDT and Windows 11 maximize hit testing. Inactive windows must
not acquire a second native caption. Narrow windows collapse menus/tool labels.

## Elevation & Depth

Flat surfaces, subtle tonal separation, no decorative shadows. A translucent
drop preview is temporary interaction feedback. Soft glow and Neon add obvious
opt-in ink halos; Prism applies a column-wise hue gradient. Effects are
rasterized into tight opaque native-resolution row tiles with separate
lit/blink-dim caches. Backgrounds and selection are
composited before caching to avoid expensive transparent-image resampling.
Overlapping row margins include neighboring ink so they cannot erase it.
Caches refresh on host/profile/theme/selection/background changes or a
fitted physical-font-size change, not every resize frame. No effect draws non-display
glyphs or replaces reverse-video backgrounds/host attributes.

Flux is an explicitly opt-in flowing text-gradient preset, not animated chrome.
Neutral text carries the rainbow; chromatic host text stays within its hue
family and reverse video keeps its original colors. Cached physical-size glyph
vectors use native rasterization with shared-per-color gradient paints; there
are no per-cell animation allocations or blur/upscale passes. One coalescing
50ms clock serves visible connected Flux terminals and stops when none are
eligible, including minimized windows. Host blink, secrecy, selection and
cursor remain independent. None is still the default and immediate stop control.

## Shapes

Rectangular panes and shared flat Swing controls. Hairline borders and the visible
focus outline provide structure without rounded containers or ornamental chrome.

## Components

Pane headers identify the profile and connection, support dragging and expose
a right-click menu. The close X occupies the far-right header position, outside
the drag target. X disconnects and removes the pane, reflowing bounded slots;
only the final pane is retained as an empty offline tile. Window
close disconnects all without confirmation. An offline tile contains only a
centered preset selector and adjacent arrow Connect icon with tooltip and
accessible name. Collapsed combo values never use selection coloring just
because the combo has focus; focus stays on its border. Newly added panes select the last
used preset; restored slots preserve their own selection. No visible A1/A2 IDs.
One subtly tinted active surface spans header, terminal and
footer; a constant-inset, physical-pixel outer focus border replaces the old terminal-only focus box
and header underline. The optional pane-background tint can be disabled; OLED
remains true black. Activating a pane never replaces its border or reshow/validates
an already-visible card. A terminal status line reports connection, inhibition,
dimensions and cursor position, with Reset visible only for the core's actual
recoverable input-error state. Enter is never remapped to recovery.
Profile editing is a compact modal list and three-tab form; labels and mnemonics
accompany controls. Owned forms, confirmations, errors and System Request
prompts share custom draggable chrome, Escape dismissal and icon-free content.
Custom window controls retain visible keyboard focus. Native Alt+Space is not
shadowed by a second popup; Ctrl+Alt+Space exposes the dark window menu.
Commands has an immediately visible entry point and Ctrl+Alt+K shortcut;
search includes aliases, disabled actions remain disabled, and modal focus
isolates host input. A single registry backs menus, palette and shortcuts. Every app/host action is
listed in the global shortcut editor, with recording, unbind/default controls,
atomic conflict rejection and version-4 persistence. Window-scoped dispatch
precedes Swing traversal/host input, so Ctrl+Tab works without taking keys in
owned dialogs. Profile-specific host bindings are preserved unless the user
explicitly reassigns that host action globally.
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
