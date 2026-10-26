---
name: Pane5250
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

# Design System: Pane5250

## Overview

The user's pinned reference is the visual authority: near-black flat split
panes, thin dividers, compact headers and crisp monospace host text.
The terminal presentation space owns the window. Application controls remain
secondary; there are no promotional surfaces, dashboard cards or AI controls.

## Colors

`AppTheme` supplies complete Charcoal, Graphite, Midnight and Paper token sets;
`Ui` applies them to every owned UI route. `FlatControls` shares flat delegates
for buttons, combos, tabs, scrollbars and split dividers. `HostPalette` defines
sixteen host colors independently, including darker foregrounds for Paper.
Never collapse host color, reverse, underline, blink or non-display distinctions
into an application accent. Seven workstation palettes alter restrained
entries, not the terminal's attribute semantics.

## Typography

Native controls use Segoe UI, terminal glyphs use an installed monospace face.
Each glyph is positioned on a host cell; proportional text layout must never
replace the fixed grid. Fit derives font size from the actual pane and host
dimensions. Manual zoom uses the 16px base and scrolling.

## Layout

The demo's reference composition is one large left pane and two stacked right
panes. The real workspace is a persistent binary split tree. A divider has a
4px draggable target but paints a 1px line. Headers are 25px high with 8px
horizontal insets. Display origins retain at least 12px horizontal and 10px
vertical padding. Resizing does not resize the host's presentation space.
The main window is undecorated: one 28px dark title/menu row replaces the native
titlebar and separate menu row. The draggable title area stays empty between
menus/compact + Pane and Commands controls and three 36px window controls.
A transparent 5px edge target provides
eight-direction resizing without overlaying the terminal interior.

## Elevation & Depth

Flat surfaces, subtle tonal separation, no decorative shadows. A translucent
drop preview is temporary interaction feedback. Soft glow and Neon add obvious
opt-in ink halos; Prism applies a column-wise hue gradient. Effects are
rasterized into tight opaque row tiles at 1x for small physical text and 2x for
larger text, with separate lit/blink-dim caches. Backgrounds and selection are
composited before caching to avoid expensive transparent-image resampling.
Overlapping row margins include neighboring ink so they cannot erase it.
Caches refresh on host/profile/theme/selection/background changes or a
resolution-tier crossing, not every resize frame. No effect draws non-display
glyphs or replaces reverse-video backgrounds/host attributes.

## Shapes

Rectangular panes and shared flat Swing controls. Hairline borders and the visible
focus outline provide structure without rounded containers or ornamental chrome.

## Components

Pane headers identify the profile and connection, support dragging and expose
a right-click menu. The close X occupies the far-right header position, outside
the drag target. One subtly tinted active surface spans header, terminal and
footer; a single outer focus border replaces the old terminal-only focus box
and header underline. A terminal status line reports connection, inhibition,
dimensions and cursor position, with Reset visible only for the core's actual
recoverable input-error state. Enter is never remapped to recovery.
Profile editing is a compact modal list and three-tab form; labels and mnemonics
accompany controls. Owned forms, confirmations, errors and System Request
prompts share custom draggable chrome, Escape dismissal and icon-free content.
Custom window controls retain visible keyboard focus. Native Alt+Space is not
shadowed by a second popup; Ctrl+Alt+Space exposes the dark window menu.
Commands has an immediately visible entry point and Ctrl+Alt+K shortcut;
search includes aliases, disabled actions remain disabled, and modal focus
isolates host input. A single registry backs menus, palette and shortcuts.

## Painting and resize

Every terminal paint explicitly clears its clipped background. An opaque bare
Swing component cannot rely on a UI delegate to do this. Regular-weight glyph
runs and their fixed cell positions are cached until host/input state changes;
resizing changes only the display transform. Idle cursor blinking repaints its
small region unless host blinking text requires a full terminal redraw.

## Do's and Don'ts

- Keep host function keys distinct from Ctrl+Alt application shortcuts.
- Show focused terminals and docking targets without obscuring host text.
- Keep hidden host data hidden in rendering and clipboard output.
- Do not replace a disconnected terminal with decorative demo content.
- Do not change host dimensions to make a pane fit.
- Do not enable effects by default or use them to conceal protocol limitations.
