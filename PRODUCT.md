# Fish

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

Windows-first native desktop application; not a website or mobile application.

## Users

IBM i users working with multiple independent interactive 5250 display sessions.

## Product Purpose

A lightweight, keyboard-first 5250 workspace. Correct host interaction matters
more than presentation. The application must be functional, not a terminal mockup.

## Operating Context

Direct plain TN5250 TCP connections to a local IBM i, normally on port 23.
Only IBM-3477-FC is offered. Host screens may be 24 by 80 or 27 by 132.
Pane resizing must never change the host's presentation space.

## Capabilities and Constraints

One to eight independent panes in bounded two-row/four-column layouts,
swap-only rearrangement, persistent workstation presets and last-used selection,
field-aware input, host keys, fixed-width rendering, OS clipboard through a
compact context menu/Edit menu, zoom and optional inexpensive visual presets.
Global application/host shortcut overrides, independent pane outline/tint,
default-off optional whole-window outline, shared one-device-pixel structural edges, zero-space
optional pane information, named all-role custom themes, creative/Eye Saver
presets, validated installed/local monospaced fonts and native Windows snapping keep
the tool usable in everyday desktop workflows. Help contains the complete,
searchable/indexed offline manual with Getting Started first. Remembered passwords belong in Windows
Credential Manager. TLS, AI features and Kerberos are not required.

The user approved a GPL-licensed application using the mature TN5250J core.
Full ACS compatibility remains an acceptance target, never an unverified claim.

## Brand Commitments

The attached reference is binding: near-black flat panes, very thin dividers,
small pane headers, crisp monospace host text, green and white host attributes,
minimal application chrome. A compact gradient outline joins a true 45-degree
square body to a triangular tail on the left. The square's two left sides
extend straight through its left corner to cross once, ending level with the
square's top and bottom, without a line joining the tail tips. There is no eye or mouth. The same
uniformly scaled exterior is the app mark and Windows icon. The top-left mark
is a secret, keyboard-accessible persistent Flux (animated)/None toggle for the active preset.
Offline content is only a centered preset selector and Connect.
Pane X disconnects and removes its tile, except the final tile stays empty;
its X hides only when that final tile is disconnected. A restrained rotating ring
replaces the center controls during connection setup without obscuring footer diagnostics.
window close exits
without confirmation. No cards, promotional text or oversized toolbars.
An opt-in Flux text preset provides flowing animated multicolor gradients
without changing the quiet chrome, default rendering or host attribute semantics.

## Evidence on Hand

One visual reference. No IBM i endpoint, credentials, ACS installation or
real-session captures. Local synthetic fixtures must be clearly identified.

## Product Principles

- Keep host state independent of pane geometry.
- Preserve protocol synchronization and report unsupported behavior.
- Make common actions accessible without taking host function keys.
- Keep credentials out of profiles and logs.
- Track demonstrated behavior separately from compatibility goals.

## Accessibility & Inclusion

Entirely keyboard navigable, predictable focus, visible focus indicators,
configurable keyboard bindings, DPI-aware rendering and readable host colors.
Windows x64 source setup is a user-runnable script that obtains portable pinned
build tools and creates both the executable and a current-user installer.
