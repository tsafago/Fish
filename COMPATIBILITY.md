# Compatibility and acceptance

**Status: experimental working client. Full ACS compatibility is not demonstrated.**
There was no IBM i endpoint, test account, ACS installation or real-session
capture available during implementation. Synthetic tests are valuable protocol
evidence but are not real-host acceptance.

## Fish 0.4.0 final streamlining preview

This update is a UI/windowing/packaging milestone before official release,
not new protocol acceptance. The maintained packaged runtime pin is Temurin
21.0.12.1+1. `Setup-Fish.cmd` generates a portable application and current-user
installer from complete source using pinned user-local JDK/Zig/WiX tools.

Focused source fixtures cover all registered shortcut actions, collision
rejection, actual Ctrl+Tab dispatch before Swing traversal, profile/default
host unbinding, outline-only focus, unchanged border identity/insets, version-4
migration/persistence, small-window restore, 26 full-text manual topics and
the native Windows resize/caption/maximize hit codes. They retain the existing
loopback, input-safety, glyph, palette/Flux, close and layout fixtures.

A fresh-consent bounded desktop pass exercised physical shortcut recording,
actual Ctrl+Tab cycling, native Win+Left half-screen and Win+Up quarter-screen
snapping, top-edge drag/maximize, restore, manual focus and active/inactive dark
chrome. It passed 17 checks without shared clipboard or credential access.
The first visual pass exposed default nonclient activation painting behind
an owned dialog; consuming that paint fixed the resurrected system caption.
This is not a full stress/performance rerun.

Windows 11's maximize hit target returns HTMAXBUTTON, but the OS-owned snap
flyout was not visible in this environment's captures; that flyout's display
is not claimed as accepted. Side/quarter/top native snap behavior was observed.
Real IBM i/ACS acceptance and the protocol gaps below remain unchanged.

## Fish 0.3.2 earlier quick revision

Fish rebrands the same experimental engine; unrelated protocol expansion is
paused. This revision uses a build and small noninteractive `Smoke` checks,
not the full foreground suites or a fresh resize stress benchmark. The focused
checks exercise bounded 1-8 slots, whole-target swap-only moves, close/removal
with only the final tile retained,
and immediate close/disconnect, minimal offline controls, last-preset and legacy
storage migration, native-pixel glyph caching, independent loopback sockets and
host-controlled 24x80/27x132 switching. Offscreen captures are
`build\fish-terminal-150.png` and `build\fish-empty-one.png`.
Additional focused fixtures exercise clipboard transfer using a private
synthetic clipboard (no shared OS clipboard access), numeric/protected/code-page
and host-inhibition safety, hidden-field-safe export, no paste transactions,
context command wiring, horizontal nonintersecting fish geometry (subsequently
replaced by the user-requested triangle/rhombus mark), persisted Flux
selection, deterministic animated phase differences, native glyph-cache reuse,
and preserved reverse video/non-display/blink. Captures are `build\fish-flux-a.png`,
`build\fish-flux-b.png` and `build\fish-horizontal-icon.png`. These offscreen
fixtures are not a fresh foreground animation/resize benchmark.

The prior measurements and interaction descriptions below are historical
Pane5250 0.1.1/0.2.0 evidence, not fresh Fish acceptance. Directional docking,
empty workspaces and close confirmations are superseded.
The old `UiRevisions.java` script is retained as historical source, not invoked
by the Fish build. `Test` remains an opt-in physical-desktop regression command
and was not run for this quick pass.

## Previously demonstrated (0.1.1/0.2.0)

`.\build.ps1 Test` exercises the actual selected engine and live desktop,
not a separate presentation mock. Golden fixtures and exact byte assertions
live in `tests\pane5250\Tests.java`; the live host is `demo\DemoHost`.

| Area | Local evidence |
|---|---|
| Stream framing | Byte-at-a-time negotiation, interleaved options, repeated requests, IAC quoting, GDS length/type/header checks, malformed/truncated input failures |
| TN5250 negotiation | IBM-3477-FC only; bidirectional BINARY/EOR; optional option refusal; TERMINAL-TYPE SEND; NEW-ENVIRON names, workstation and keyboard/code-page values |
| Named devices/startup | Exact ordered RFC 1572 SEND/IS bytes, no unsolicited IS, VAR/USERVAR wildcards, undefined unknown names, control-byte escaping/IAC quoting, collision diagnostics without renaming; exact RFC 4777 I902/8902 fixtures, unknown/malformed rejection, actual loopback named-device connections |
| Optional plain sign-on | Exact RFC 4777 USER / empty IBMRSEED / IBMSUBSPW bytes; visible refusal; no credentials negotiated in manual mode |
| Presentation | Actual 24x80 and 27x132 datastreams, host-driven transitions in both directions; pane/window resize does not change dimensions |
| Fields/input | Host-defined fields, protected/numeric validation, Field Exit, literal bracket safety, local-error Reset and preserved host inhibition |
| Host keys | Exact Enter/F1-F24/Clear/Help/roll AIDs; header-only Attention and System Request with EBCDIC option; physical desktop input and mapped keypad Field Plus |
| Read/query/save | Exact RFC 1205 Query shape/capabilities, Read MDT / Alternate embedded-null differences, Read Immediate Alternate AID/cursor, Save/Restore round trip |
| Attributes | Host color/reverse and restored blink flags; hidden-field-safe copy/export; password field/capacity guards |
| Code pages | Bidirectional engine mappings and usable UI encoders for every offered SBCS page |
| Credentials | Real Windows Credential Manager write/read/delete round trip, uniquely named synthetic fixture, Unicode secret |
| Desktop | Three independent real TCP sockets, physical keyboard focus/edit, keyboard pane navigation, socket-preserving center/edge docking, real close/reopen persistence |
| Desktop correction | Poisoned-buffer redraw regression, real continuous window/divider resize, physical pane-header docking, all eight edge/corner directions and minimum size, custom move/maximize/restore/minimize/close, native/custom window menus, keyboard move/size, focus outlines and cancelable close |
| Profiles/layout | Identity-safe duplication, all docking directions, atomic XML persistence, writer locking, explicit corrupt-state handling, reserved shortcut namespace |
| UI revisions | Whole-pane header/display/footer highlight; no header underline; far-right X close/cancel and actual socket release; immediate add/direction controls; empty workspace/add-back; searchable palette aliases, disabled states, keyboard navigation/focus and host-input isolation |
| Appearance/recovery | Four live/persistent application themes, version-1 migration, seven host palettes and readable text/focus contrast fixtures; actual themed forms/confirmations/errors/profile/key-mapping/System Request routes; visible opt-in effect pixel differences; contextual Reset clears local error with no transaction and cannot unlock host busy |

SBCS pages offered: **37, 273, 277, 278, 280, 284, 285, 297, 500, 870, 871,
1140, 1141, 1147, 1148**. Mapping round trips do not establish the correctness
of every national keyboard combination against a real host.

The desktop correction run passed **166 core/application checks plus 61
rendering/window checks** on Windows at native **150% DPI**. During real corner
and split-divider drags, 185 measured dirty-region paints had a 4.08ms median,
10.76ms p95 and 17.74ms maximum. EDT round-trip latency was 8.24ms p95 / 15.40ms
maximum, compared with the pre-correction 114.13ms p95 / 196.43ms maximum.
These are local observations, not a hardware-independent latency SLA.
Repeated menu close, maximize/restore and exposed terminal margins were clean;
the exact connection count remained three and every host screen size was
unchanged. These historical 0.1.1 numbers are retained for comparison, not
substituted for measurements of the revised 0.2.0 controls and effects.

The final revision fixtures pass **669 non-interactive protocol/model/theme/render
checks**, with real desktop,
credential-store and foreground interaction suites checked separately. The
revised interaction suite covers pane/recovery/theme/effect/palette/dialog behavior, and the rendering/window suite
passes **61 checks per effect mode**.

The revision run at native **150% DPI** separately measured effects off
(168 paints: 6.94ms median, 22.52ms p95; EDT lag 30.10ms p95) and Neon
(152 paints: 13.25ms median, 26.54ms p95; EDT lag 31.55ms p95). Both satisfied
the actual test thresholds of paint p95 below 30ms and EDT lag p95 below 75ms,
with unchanged sockets/host dimensions. Run `Test` for current machine results;
these are observations, not universal latency guarantees. Captures are
`build\desktop-render-none-native.png`, `build\desktop-render-neon-native.png`
and `build\revision-*.png`. `Check` executes protocol/model/theme fixtures only,
without foreground desktop input or credential-store access.
Earlier stress runs exposed unnecessary oversampling and transparent-image
resampling across empty terminal regions (39-40ms paint p95). Resolution-tier,
tight opaque row caches remove that cost while retaining higher-resolution
ink for larger physical text, accurate selection/reverse backgrounds and
neighboring-row ink. No threshold was relaxed to hide the result.

**Final timing limitation:** the shared Windows machine subsequently reported
100% CPU with no Pane5250 test process left running. A later effects-off run
measured 34.08ms paint p95 / 49.55ms EDT lag p95 and failed the unchanged 30ms
paint threshold. Thus the earlier passing results above are not a repeatable
final timing guarantee under that load. The user chose to finish packaging and
report this limitation rather than run more foreground checks. Re-run `Test`
on an idle machine before treating the resize performance gate as closed.
Functional UI checks and hidden/offscreen cache regressions are distinct from
that timing limitation.

### Explicit device-name correction and remaining gate

The previous transport sent NEW-ENVIRON IS immediately on DO and again on SEND.
RFC 1572 requires the initial IS to answer SEND; the duplicate allocation attempt
is removed. Replies preserve requested variable order/types and exact DEVNAME.
RFC 4777 `IBMSENDCONFREC=YES` requests a startup result; supported startup
confirmations are consumed separately from display records. Known errors such
as 8902 (device unavailable) include the actual system/device when supplied.
Allocation is never labeled authentication. Refusal, collision and a different
allocated name cannot silently satisfy an explicit-device request.

This is an independently reproduced transport defect, **not proof of the cause
of the user's IBM i rejection**. A retry against that host and its actual startup
code (or a sanitized negotiation trace if no code is sent) is still needed.
No endpoint or host rejection details have been supplied.

The original corruption was a violated Swing opaque-component contract:
the bare terminal component did not clear its background, so old shared-buffer
pixels accumulated across panes and menus. Explicit clearing fixes the cause.
Cached fixed-cell glyph runs remove per-character layout/allocation from resize,
and cursor-only idle repaints avoid unnecessarily redrawing whole panes.

## Known implementation gaps

| Gap | Current behavior / consequence |
|---|---|
| Enhanced Write-to-Display structured fields (windows, GUI selection fields, scrollbars, grids) | Stop the connection explicitly. These are not advertised as capabilities. Real applications using them will not work. |
| WEA extended attributes and WSF beyond the basic Query | Explicit failure rather than silent skipping. Additional valid IBM i datastreams may therefore be rejected. |
| Full applicable 5250 order/FCW validation | Mature upstream behavior is inherited where not adapted; exhaustive conformance, transparent field FCWs and all signed/continued/mandatory-field combinations are not independently established. |
| Cryptographically substituted automatic sign-on | DES/SHA substitutions and server-specific password-policy variants are not implemented. Only opt-in RFC 4777 plain sign-on is offered. |
| Other startup/confirmation variants and optional IBM environment extensions | RFC 4777 display startup confirmation is now requested and parsed. Unknown results/formats fail explicitly; other IBM environment extensions are not implemented or guessed. |
| DBCS and other terminal models | Unsupported; only IBM-3477-FC and the listed SBCS pages are offered. |
| TLS / Kerberos | Not implemented; TLS is out of scope and Kerberos was optional. |
| Accessibility / DPI matrices | Native 150% DPI, real window controls and keyboard/focus routes are locally checked. Screen-reader terminal semantics, mixed-DPI monitors and other Windows scaling/keyboard layouts still need hands-on acceptance. |
| Performance | Continuous desktop resize is locally measured above; startup latency and memory have no claimed SLA. Other hardware and live-host workloads remain unverified. |

Only the basic Query capabilities actually intended for this implementation
are announced: both screen sizes, color, alternate MDT reads and Move Cursor;
no graphics, DBCS, enhanced GUI or row-1/column-1 capability is claimed.
Upstream warnings/unsupported diagnostics surface as connection failures.
This preserves synchronization but is intentionally stricter than ACS for some
valid host variations. It is a compatibility limitation, not completed tolerance.

Host STRPCCMD and browser/external-program execution are disabled. A host cannot
use this client to launch local commands. Password data may exist in the JVM,
helper process and host screen memory; no secure-memory-erasure guarantee is made.

## Required real IBM i / ACS comparison

Use a non-production workstation profile and an authorized test account.
Provide the IBM i hostname/address and plain-Telnet port, IBM i release,
device-name constraints, sign-on policy (manual vs permitted RFC 4777 plain),
needed code pages and representative display-file applications. Arrange access
to an ACS 5250 display session configured with matching settings. Do not put
credentials or unsanitized captures in this repository.

For each comparison record host/application versions, terminal/code page,
screen/cursor/field state, expected outbound input and both clients' behavior.
Use authorized, sanitized packet captures where needed; plain TN5250 captures
can contain passwords and business data.

1. Compare initial negotiation and Query, including reordered/refused optional
   options, device allocation/rejection, ordinary and unsuccessful sign-on.
2. Compare all applicable commands, orders, structured fields and FCWs used by
   real applications. Implement missing enhanced/extended behavior before
   declaring those applications supported.
3. Compare 24x80/27x132 transitions, output-only updates, cursor/IC/MC control,
   field boundaries, mandatory/signed/continued fields, numeric edits and Reset.
4. Compare all F1-F24, Enter, Field Exit/Plus/Minus, Attention, System Request,
   Clear, Help, rolls, message light, cancellation, and input inhibition.
5. Compare all selected code-page glyphs and reverse/underline/blink/non-display
   attributes, paste, selection and copied output.
6. Exercise multiple independent device sessions, disconnect/reconnect and host
   restarts; drag and keyboard-dock them while traffic is active.
7. Reopen profiles/layouts, verify credential opt-in/removal, test supported
   Windows DPI scales and document measured startup/input responsiveness.

Until those gates and the known implementation gaps are resolved, do not label
the client production-ready, fully TN5250-conformant or ACS-compatible.

## Sources and adaptation provenance

- [RFC 1205](https://www.rfc-editor.org/rfc/rfc1205), especially framing,
  operation flows, transparent data, alternate reads and the basic Query.
- [RFC 4777](https://www.rfc-editor.org/rfc/rfc4777), sections 3/4/7/10:
  IBM i NEW-ENVIRON, device negotiation, optional plain sign-on and startup
  confirmation codes/byte example.
- [RFC 1572](https://www.rfc-editor.org/rfc/rfc1572), ordered SEND/IS exchange,
  variable types/wildcards, undefined variables and escaping.
- RFC 854/855/856, 885, 1091 and 1572 for Telnet option semantics.
- [TN5250J](https://github.com/tn5250j/tn5250j/tree/d160492db8adec5d7cf309c6f4feefa162e6565d),
  pinned source, original licenses retained.
- [tn5250 session implementation](https://github.com/tn5250/tn5250/blob/b8448fecd2825bc8e1aa3361371844fb8f25697d/lib5250/session.c),
  cross-check of field-read formatting; not an additional binary dependency.

RFC 1205 supplements IBM publication SA21-9247-6; it is not the entire 5250
specification. That complete IBM reference and actual ACS captures were not
available here. Core adaptations are explicit, fail-on-missing-anchor
transformations in `scripts\prepare-core.ps1`, not untracked submodule edits.
