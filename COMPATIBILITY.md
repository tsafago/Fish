# Compatibility and acceptance

**Status: experimental working client. Full ACS compatibility is not demonstrated.**
There was no IBM i endpoint, test account, ACS installation or real-session
capture available during implementation. Synthetic tests are valuable protocol
evidence but are not real-host acceptance.

## Fish 0.7.4 windows and cursor refinement

The single Settings action is under Edit; + Pane follows all menus. Repeated
launches and Session > New window create independent top-level frames with 1-8
sessions each, using one locked configuration owner and restricted authenticated
loopback rendezvous. Shared settings/profiles stay coherent; closing one frame
disconnects only its sessions. Quit/update/reset account for the full collection.
Format 14 adds window snapshots and actual-device footer visibility; back up with
all windows closed before upgrade. Older versions require that backup to downgrade.

Focused checks cover two four-pane loopback windows, independent screens/sockets,
shared keys/profiles and stale-draft rejection, deletion, isolated close,
all-window update failure/success/reset, persistence and offline CLI locks.
Separate JVMs cover simultaneous launch election, bad tokens, idempotent retry,
version isolation and crash/stale-metadata recovery. Cursor checks exercise real
restart timing, old/new rectangle repaint requests, accepted/rejected input,
host-output independence, hidden/disposed timer cleanup and measurable heavier
Insert paint. A bounded never-shown 100%/150% batch covers the production chrome,
default header/footer and cursor rendering.

No shared desktop, actual clipboard, tickets, real host or installer execution was
used. Offscreen checks do not establish native taskbar/Alt+Tab, physical keyboard,
mixed-monitor or assistive-technology acceptance. The known maximize-perimeter,
intermittent flicker, emulation and real SSO/ACS limits below remain unchanged.

## Fish 0.7.2 keyboard and Preferences prerelease

Adds a single exact-location, multi-binding application keyboard map/recorder,
draft-based indexed Preferences, compact mode-aware profiles with Advanced, and
actual native checkbox text/check/focus contrast painting. Both Enter locations
default to Enter. Formats 1–11 migrate keys globally; conflicting explicit
definitions are retained and inactive, not resolved by picking a profile. Format
12 requires a pre-upgrade backup for downgrade, including from 0.7.1.

Focused checks cover typed/captured location roundtrips, independent keypad Enter
overrides, multiple bindings, conflicts/unbinding/recovery, legacy defaults,
configuration rejection/persistence, real Preferences navigation/apply/cancel and
profile collapse/mode-specific fields. Native BUTTON controls remain native for
state/keyboard/accessibility; only painting is subclassed. Never-shown control
render checks exercise enabled/disabled/checked/hover/pressed states and explicit
system/high-contrast colors at 100/125/150/175/200%. This is not live installer UI,
physical keyboard/IME-layout acceptance or assistive-technology acceptance.

### Retained 0.7.1 maintenance behavior

Retains persistent profile ordering and dependency-safe deletion, atomic active-workspace
factory reset, bounded public-GitHub update checks/download integrity, native
per-user setup and dismissible bordered notices. Format 11 retains existing
version-10 Kerberos profiles; older binaries require a pre-upgrade backup.
The default remains selected Kerberos 10.1.2.3:23 and offline until Connect.
No protocol capabilities are expanded or ACS gaps closed by this update.

Focused checks use isolated workspaces, injected HTTP/security-policy/installer
providers and never-shown production UI. They exercise consent, exact ignore,
retry, cancellation, failure/handoff persistence, profile references and real
local input recovery versus notice dismissal. Offscreen borders cover
100/125/150/175/200%. Native setup self-test/payload/version/MSI checks and an
offscreen design image are not live installer or installed-upgrade acceptance.
No foreground desktop, shared clipboard, actual installer/update, real-host auth
or ticket acquisition was used for this revision.

Portable self-update installs/opens a per-user copy with the same workspace; it
does not replace the portable directory. Binaries remain unsigned and Windows
policy is not bypassed. Real setup interaction, OS reputation prompts,
installed upgrade/rollback/relaunch and network-specific proxy policy remain
unverified acceptance paths. Main-app maximize/restore perimeter loss,
intermittent flicker, WEA/enhanced WTD/structured-field gaps, cryptographic
password substitution and real Kerberos/IBM i/ACS acceptance remain open.

## Included Fish 0.7.0 Kerberos support

Implements RFC 4777 section 6 binary IBMTICKET using the current Windows logon's
explicit Kerberos SSPI provider (never Negotiate/NTLM/password fallback).
Fresh absent workspaces seed/select Local IBM i at 10.1.2.3:23, offline until
Connect. Existing profiles, startup choices, credentials and usability features
are retained. Kerberos service DNS identity is separate from TCP destination;
an IP uses forward-confirmed reverse DNS or an explicit configured DNS override.
No deployment-specific domain/SPN/realm has been guessed or queried.

The initial GSS token is bounded to 16,384 bytes, environment-escaped and IAC
quoted without string/base64/hex conversion. NEW-ENVIRON refusal, presentation
without ticket negotiation and Kerberos-specific startup/EIM rejections fail
explicitly. Optional absence of a startup record does not break synchronization
or assert sign-on; Connected/TCP and I902 remain authentication-unverified.
This one-hop flow does not request delegation or assert mutual authentication.
Windows handles are disposed and owned native/Java token buffers are wiped.
Cancellation/20-second UI deadlines suppress late connections; a native KDC
operation can continue until Windows returns, with at most one per pane.
NAS/keytab, registered krbsvr400 identity, EIM, Windows/domain credentials and
remote sign-on policy are external administrator prerequisites.

Synthetic native-provider fixtures check exact package/current-logon arguments,
context flags, opaque token copying, success/continue/error/unsupported completion,
size rejection and handle cleanup without acquiring user credentials.
Java fixtures cover exact RFC bytes, both escape layers, 16 KiB limits,
refused/missing negotiation, mode-specific errors, fresh seed/old-state migration,
no password-helper access, production async/socket cancellation and hidden UI.
Actual Windows-domain/IBM i SSO, identity mapping and ACS comparison remain open.
No real credentials, ticket-cache inspection, policy changes or shared-desktop
automation were used. TLS and other emulation gaps remain unchanged.
The delivered private JVM also loads its shipped native DLL and discovers the
Windows Kerberos package successfully using `tests\native\packaged-kerberos.c`;
that non-secret capability check requests no tickets and opens no windows.

## Local Fish 0.6.1 usability preview

Local-only, unsigned build; no new publication or protocol expansion.
The modeless first-run guide uses short hints beside real menus/editor/pane controls
and is replayable through Help without connecting or duplicating profiles.
New profiles default to 10.1.2.3 and checked automatic sign-in/password storage;
existing choices are retained. OS-only storage, one-hop login references and the
existing plain RFC 4777 limits remain. Session's optional startup connection
setting defaults off, attempts restored panes once and leaves missing/incomplete
credentials offline without prompt storms. Configuration apply never connects.
Offline help formats actual Markdown tables, commands, emphasis and lists without
loading embedded images or executing raw HTML.

Production-path fixtures cover Ctrl+C/V with a private test clipboard, native
dialog editing, conventional key migration/conflicts, exact zero host bytes for
the first recoverable-error Enter and Enter AID F1 on the following normal press.
Host-busy/disconnected states are not falsely recovered. Defaults use the IBM
Personal Communications 5250 reference where applicable, not an asserted universal
regional ACS map; documented Fish differences remain intentional.

Complete configuration export/validate/apply, all theme roles, login graphs,
saved layouts, font/shortcut validation, duplicate/hostile XML, exclusive locks
and stale revisions are exercised without OS credentials or live user state.
Synthetic images check actual new guide/profile constructors at 100%/150%;
no new physical-desktop or real-clipboard automation was performed.
Existing centering, glyph/Flux, hidden-header swapping and Apply/Done paths retain
their focused regression checks.

The broader historical `Check` runner currently stops at its unchanged Afterglow
secondary/disabled-text contrast assertion; that pre-existing palette/test issue
is outside this usability revision, not a passing full-suite claim.
Known maximize/restore perimeter loss, intermittent resize flicker, cryptographic
sign-on, enhanced datastream gaps and real IBM i/ACS acceptance remain open.

## Fish 0.5.3 interaction refinement preview

Unsigned experimental preview, with no protocol expansion. Hidden headers
gain a zero-space top drag target using the real swap handler. Manual zoom
centers on axes that fit and retains existing scrolling for oversize screens.
Normal and Flux share exact per-channel LCD glyph coverage; effect-only changes
retain glyph/font caches and repaint without the logo's former double-click delay.
Production settings use Apply/Done with repeated-Apply dismissal baselines.
The redundant More settings Flux item is removed; its configurable action remains.
Pane focus outlines no longer add a second shared middle-divider line.

Focused noninteractive checks cover these production paths and Ubuntu Mono and
Consolas at 100/125/150/175/200%, with real loopback sockets and stored settings.
Freshly consented, bounded production desktop checks passed at 100% on the 1080p
monitor and 150% on one 4K monitor, including actual logo/effect selection,
font Apply/Done, submenu Escape dismissal and hidden-header mouse swapping.
The current 4K logo switch changed terminal pixels in 90.7 ms. The sampled
1080p/4K resize frames retained terminal ink; the native probe counted 20 size
messages per resize and no background-erasure messages. Current 4K EDT p95 was
18.65 ms for Flux and 10.94 ms for None. These are bounded samples, not guarantees
against the user's intermittent flicker: it was not reproduced, and no verified
hardware flicker fix is claimed. Physical 125/175/200% checks were not performed.
No workspace, credentials, clipboard or real-host images were used.
The initially interrupted confirmation exposed a shared dialog Escape handler
that also dismissed its owning dialog; it now clears the active menu path first,
and the real popup/Done sequence passed on both monitors.
The previously demonstrated maximize/restore outline defect remains open.
No full ACS/production claim is made.

## Included Fish 0.5.2 DPI-regression preview

**Known unresolved defect:** the outer perimeter still disappears after
maximize/restore on the actual 1080p/4K monitors. AWT's classic frame insets and
native drawing-surface clipping remain inconsistent on this path. This batch
is not a complete border fix. Ineffective bounds/layout workarounds were removed
rather than shipped as verified corrections.

The shared border painter now uses OpenJDK Swing's fractional buffer-copy
endpoint rule (`Region.clipRound`, including half-pixel ties). It no longer
rounds outward or enlarges inherited dirty clips. Actual quick menus, appearance
submenus, combo dropdowns and owned settings dialogs were exercised through
their production constructors and mouse paths, including partial terminal
repaints and dismissal. Their borders and content restoration passed at
100% on a 1920x1080 display and 150% on two 3840x2160 displays. The initially
restored whole-window perimeter and square corners also passed. After
maximize/restore, the six whole-window perimeter assertions still failed.
Screenshot crops were corrected against native client coordinates and
fractional component origins; no fabricated dialog wrappers were used.

Caption minimize/maximize/restore/close hover transitions passed in both
window states, including native HTMAXBUTTON handling, without changing the
WM_SETICON/WM_SETTEXT caption fix. The rectangular native frame region removes
classic cut corners. Socket identities and host dimensions stayed unchanged.
No shared clipboard, credentials, real-host screenshots or IBM i connection
were used in these freshly consented, bounded desktop checks.

The window outline is now ON by default. Schema 5/6 did not distinguish an
explicit off choice from the previous default: both migrate to on once, with
an informational console notice rather than a red startup error. Schema 7
retains subsequent explicit off choices. Pane outline/tint remain independent
under Appearance > More settings > Window and focus, with actual Apply/Cancel
and all eight persisted combinations checked. Malformed state still fails.

Close X is independent of header information and moves into a zero-space
upper-right overlay when needed; Reset stays independent and non-overlapping.
Focused loopback checks cover correct-session removal, last connected pane
disconnect, the retained offline tile, and no extra host submissions.
Offscreen strict-clip/geometry checks additionally cover 125%, 175% and 200%;
those scales were not tested on physical monitors. Theme Designer's shared
Divider role and flat control/popup surfaces remain unchanged.

This unsigned experimental preview includes no protocol expansion. Full
IBM i/ACS compatibility and production readiness remain unverified.

## Fish 0.5.0 customization preview (included)

Commands is removed, with useful actions retained in menus, terminal context
menus and shortcuts. Old Commands chords are explicitly retired until reassigned.
Neon migrates to None with a load notice, preserving the rest of the profile.
The titlebar-only drawing is now 27x20 inside the same 48x30 target; packaged
icon geometry, 31px chrome and the native monitor/DPI bridge are unchanged.
Mouse clicks leave no focus box; keyboard focus has a subtle underline.
UI text uses Windows desktop hints at the final rendering resolution.

Lagoon, Afterglow and muted warm Eye Saver are complete application presets.
The Theme Designer edits all 62 semantic UI/host/effect/logo RGB roles and saves
named complete themes. Live Preview/Cancel is reversible; custom host slots
supersede a profile's built-in palette while active. Valid custom values remain
exact, so readable contrast is the designer's responsibility.

Terminal fonts are discovered installed fixed-width faces or local TTF/OTF
files, checked against printable widths and current SBCS code-page glyphs.
Unsupported/missing fonts are explicit errors; a missing saved font uses an
announced temporary fallback without erasing the choice. OpenType support is
limited to formats accepted by Java's local font parser.

Outline and tint are independent persistent settings. Header/footer and each
existing information item can be hidden, with zero space for empty rows.
Recoverable local input errors get Reset in the footer or a non-reflowing corner
overlay; host-busy/disconnected states never get a false unlock.

Focused source/private-runtime checks cover theme save/load and all-role
coverage, custom font/fixed cells, hidden rows, overlay Reset without submission,
outline off, Neon/shortcut migrations and retained geometry/native hit tests.
Representative offscreen images are inspected as one bounded batch.
No shared-desktop/clipboard automation or new physical mixed-DPI monitor test
is part of this pass. Earlier monitor evidence below is historical; real
IBM i/ACS acceptance and protocol gaps remain unchanged.

## Fish 0.4.3 UI sizing preview (included)

The titlebar drawing is modestly smaller (32x24 instead of 38x28), with unchanged
geometry, gradient, 48x30 click target, accessibility and Flux/None behavior.
Native/taskbar icon sizes and the 31-pixel titlebar are unchanged.

Offline preset rows use actual available width below their preferred size and
grow naturally for longer names. This removes the centered GridBag row's abrupt
preferred-to-minimum collapse and the fixed 225-pixel selector cap. Titlebar
compaction uses measured controls instead of a fixed 700-pixel threshold.
Shared borders report the same insets through both Swing measurement APIs.
Menus, command/profile lists and dialog renderers retain normal truncation when
their actual allocated area cannot fit the text; ellipses are not suppressed.

Focused source/packaged checks exercise narrow and wide layouts, longer profile
names, measured toolbar allocation, genuine overflow and unchanged logo hit
testing at 100%/150% rendering. No foreground desktop automation or new physical
monitor run is part of this pass. Protocol/real IBM i/ACS acceptance remains open.
The private v0.4.3 preview includes the previously local revisions below.

## Fish 0.4.2 logo/interaction revision (included in 0.4.3)

The user's final geometry extends the true square's two left sides straight
through its left corner, crossing once and ending level with its top and bottom,
with no line joining the tail tips.
The silhouette remains 3:2, drawn with uniform scaling in every icon.
The logo toggles the actual persisted Flux (animated)/None preset effect, not
static Prism. The previous mixed-DPI correction remains unchanged.
Pane/Commands now rest directly on the titlebar surface rather than filled chips.

A separate connecting card displays a small rotating ring until the transport's
first valid host record. It retains explicit footer diagnostics and cancel via X.
Only the sole disconnected tile hides X; multiple tiles and busy/connected solo
tiles keep it. The ring clock stops on hidden/minimized/offline/disposed surfaces.

Focused source and packaged-JVM fixtures cover straight extensions/crossing/proportions,
deterministic Flux animation through the logo path, no-effect restoration,
persistence, toolbar styling, and actual loopback connection success, held
handshake, failure, cancellation and close-button/card transitions. The spinner's
rendered phases and hidden lifecycle are checked without foreground desktop input.
This pass does not repeat the prior physical mixed-DPI run or expand protocol acceptance.
First delivered locally; included in the 0.4.3 preview with matching source.

## Fish 0.4.1 monitor/chrome revision (included in 0.4.3)

First delivered locally. A focused regression reproduced the
classic-caption corruption simply by refreshing the icons of a visible Fish
window. OpenJDK `WWindowPeer.updateGC()` refreshes icons on monitor changes;
`AwtFrame::DoUpdateIcon()` sends WM_SETICON. Its Windows default handler can
paint a classic caption directly, bypassing WM_NCPAINT/WM_NCACTIVATE.
Fish now inhibits only that synchronous default icon/title redraw while
preserving the actual icon/title update, visibility, styles and AWT DPI handling.
Peer recreation receives a fresh native attachment, and logical hit regions
are scaled against the current physical client rectangle rather than frozen
at the previous monitor's size.

The titlebar is three logical pixels taller (31). The shared icon is the exact
geometric union of a 45-degree square and a right-isosceles triangle whose legs
equal the square's side length and whose right-angle tip is the square center.
Only the exterior is stroked, without aspect-ratio distortion. The focusable
logo toggles the active preset through the real persistent Prism/None path;
double-clicks and drags do not toggle.

Focused source/packaged checks cover geometry, cache invalidation, actual Prism
pixel changes and exact None restoration, saved profile state, logo/native hit
separation and peer recreation, alongside the retained loopback/field-safety
fixtures. A fresh-consent bounded physical run used three monitors at 100%,
150% and 150%, including icon/title refresh, cross-monitor movement,
maximize/restore, minimize/restore, owned-dialog activation and physical logo
single/double/drag/keyboard actions. These are local Windows observations, not
proof for every GPU, monitor arrangement, docking/replug or DPI configuration.
No IBM i, shared clipboard or credentials were accessed. Protocol expansion and
real IBM i/ACS acceptance remain unchanged.

Lifecycle source references:
- OpenJDK: `src/java.desktop/windows/classes/sun/awt/windows/WWindowPeer.java`
  (`updateGC`) and native `awt_Frame.cpp` (`DoUpdateIcon`).
- Chromium: `ui/views/win/hwnd_message_handler.cc`, documentation of default
  WM_SETICON/WM_SETTEXT direct nonclient painting. Referenced behavior only;
  no Chromium runtime dependency is introduced.

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
| Cryptographically substituted password sign-on | DES/SHA password substitutions and server-specific password-policy variants are not implemented. PASSWORD mode offers RFC 4777 plain sign-on; Kerberos is a separate implemented mode. Existing preferences are unchanged. |
| Other startup/confirmation variants and optional IBM environment extensions | RFC 4777 display startup confirmation is now requested and parsed. Unknown results/formats fail explicitly; other IBM environment extensions are not implemented or guessed. |
| DBCS and other terminal models | Unsupported; only IBM-3477-FC and the listed SBCS pages are offered. |
| Kerberos real-host acceptance | Implemented initial-token SSPI/IBMTICKET flow with synthetic fixtures; actual Windows-domain/IBM i NAS/EIM sign-on is not verified. |
| TLS | Not implemented; remains out of scope. Kerberos does not encrypt terminal traffic. |
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
- [RFC 4777](https://www.rfc-editor.org/rfc/rfc4777), sections 3/4/6/7/10:
  IBM i NEW-ENVIRON, device negotiation, Kerberos GSS IBMTICKET, plain sign-on and startup
  confirmation codes/byte example.
- [IBM i Kerberos service principals](https://www.ibm.com/docs/en/i/7.5.0?topic=service-adding-i-principals-kerberos-server):
  registered service class `krbsvr400`. The RFC's SSPI prose sample transposes
  its spelling; Fish uses IBM's named service/configuration example.
- [IBM i NAS/EIM verification](https://www.ibm.com/docs/en/i/7.5.0?topic=i-verifying-network-authentication-service-eim-configuration):
  EIM identity mapping and QRMTSIGN *VERIFY prerequisite.
- Microsoft [AcquireCredentialsHandle (Kerberos)](https://learn.microsoft.com/en-us/windows/win32/secauthn/acquirecredentialshandle--kerberos)
  and [InitializeSecurityContext (Kerberos)](https://learn.microsoft.com/en-us/windows/win32/secauthn/initializesecuritycontext--kerberos):
  explicit package/current-logon handles, opaque token, status and resource semantics.
  Fish requests neither optional delegation nor mutual authentication for the
  one-way TN5250 initial-token flow.
- [RFC 1572](https://www.rfc-editor.org/rfc/rfc1572), ordered SEND/IS exchange,
  variable types/wildcards, undefined variables and escaping.
- RFC 854/855/856, 885, 1091 and 1572 for Telnet option semantics.
- [TN5250J](https://github.com/tn5250j/tn5250j/tree/d160492db8adec5d7cf309c6f4feefa162e6565d),
  pinned source, original licenses retained.
- [tn5250 session implementation](https://github.com/tn5250/tn5250/blob/b8448fecd2825bc8e1aa3361371844fb8f25697d/lib5250/session.c),
  cross-check of field-read formatting; not an additional binary dependency.
- [LGPL tn5250 Telnet transport](https://github.com/tn5250/tn5250/blob/b8448fecd2825bc8e1aa3361371844fb8f25697d/lib5250/telnetstr.c),
  inspected with the pinned TN5250J core for licensed prior art. Neither provides
  a Kerberos token producer; the SSPI bridge/binary ticket path is Fish's own
  implementation of the cited protocol/API contracts, not copied ticket code.

RFC 1205 supplements IBM publication SA21-9247-6; it is not the entire 5250
specification. That complete IBM reference and actual ACS captures were not
available here. Core adaptations are explicit, fail-on-missing-anchor
transformations in `scripts\prepare-core.ps1`, not untracked submodule edits.
