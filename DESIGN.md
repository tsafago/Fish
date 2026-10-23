---
name: Pane5250
description: A host-first, flat native terminal workspace.
colors:
  terminal-bg: "#101213"
  panel-bg: "#171a1c"
  divider: "#383f43"
  text: "#dde3e5"
  muted: "#a5afb6"
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

`Ui.java` defines chrome colors. `TerminalView.palette` defines sixteen host
colors independently of chrome. Never collapse host color, reverse, underline,
blink or non-display distinctions into an application accent. Cool and Warm
change restrained palette entries, not the terminal's attribute semantics.

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

## Elevation & Depth

Flat surfaces, subtle tonal separation, no decorative shadows. A translucent
drop preview is temporary interaction feedback. Soft glow is an optional
single extra glyph draw, off by default; Prism is a small row-wise color tint.
Neither effect is a substitute for host attributes.

## Shapes

Rectangular panes and native Swing controls. Hairline borders and the visible
focus outline provide structure without rounded containers or ornamental chrome.

## Components

Pane headers identify the profile and connection, support dragging and expose
a right-click menu. A terminal status line reports connection, inhibition,
dimensions and cursor position. Profile editing is a compact modal list and
three-tab form; labels and mnemonics accompany controls.

## Do's and Don'ts

- Keep host function keys distinct from Ctrl+Alt application shortcuts.
- Show focused terminals and docking targets without obscuring host text.
- Keep hidden host data hidden in rendering and clipboard output.
- Do not replace a disconnected terminal with decorative demo content.
- Do not change host dimensions to make a pane fit.
- Do not enable effects by default or use them to conceal protocol limitations.
