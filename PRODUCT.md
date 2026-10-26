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
compact context menu/Edit menu/command search, zoom and
optional inexpensive visual presets. Remembered passwords belong in Windows
Credential Manager. TLS, AI features and Kerberos are not required.

The user approved a GPL-licensed application using the mature TN5250J core.
Full ACS compatibility remains an acceptance target, never an unverified claim.

## Brand Commitments

The attached reference is binding: near-black flat panes, very thin dividers,
small pane headers, crisp monospace host text, green and white host attributes,
minimal application chrome. A horizontal, noncrossing gradient outline fish,
tail left and head right, is the app mark
and Windows icon. Offline content is only a centered preset selector and Connect.
Pane X disconnects and removes its tile, except the final tile stays empty;
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
