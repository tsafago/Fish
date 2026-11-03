# Fish

A Windows-first, keyboard-driven desktop 5250 workspace. Java 21 / Swing,
plain TN5250 TCP, a pinned TN5250J screen/field engine, a small source-built
Windows frame bridge, and no third-party runtime JAR dependencies.

**Working experimental implementation, not an ACS-compatible release.**
Real IBM i sign-on and ACS comparison have not been performed. Enhanced display
structured fields and WEA are explicit compatibility gaps, not silently
accepted features. Read [COMPATIBILITY.md](COMPATIBILITY.md) before connecting
to a production system.

## DPI-regression preview

**Fish 0.5.2** is an unsigned experimental preview with a known maximize/restore outline defect.
Owned structural edges share one subtle divider color and a one-physical-pixel
painter. Fractional-DPI painting now follows Swing's actual buffer-copy endpoints
without expanding repaint clips, preventing missing edges and orphan menu lines.
The whole-window outline is on by default. Change it under **View > Appearance > More settings > Window and
focus**; active-pane outline and tint remain separate options there.
Version-5/6 workspaces get a documented one-time outline-on migration: those
versions did not record whether off was intentional. Version-7 choices,
including off, persist unchanged. Normal migrations are informational console
messages, not red startup errors; genuinely invalid state still fails explicitly.
Pane close remains available as a zero-space overlay when its header is hidden
or has no selected information. Native maximize hover feeds the same button
state as the other caption controls.
**Remaining defect:** the outer perimeter can disappear after maximizing and
restoring. Real-monitor checks still fail for that path on both 1080p and 4K.
This batch is not a complete border fix; native/ACS acceptance is not claimed.
Flat square dialog/combo/popup perimeters replace compound bevels and shadows.
Quick menus retain frequent actions; fonts, pane information, window details
and the theme designer are behind **Appearance > More settings**.

## Included customization

**Fish 0.5.0** adds a complete named-theme designer, Lagoon/Afterglow/Eye Saver
application presets, discovered monospaced fonts and local TTF/OTF support.
Header/footer content, active outline and active tint are independently
configurable; empty information rows reserve no space. With no footer,
recoverable input errors offer an in-pane Reset overlay without reflow.

The Commands window is removed; its actions remain in themed menus,
terminal context menus and configurable shortcuts. Neon migrates explicitly
to None. The titlebar-only fish is smaller, mouse clicks no longer leave a
focus box, and keyboard focus uses a subtle underline. Its approved geometry,
Flux/None toggle, click target, 31px chrome and native monitor handling remain.
UI text uses Windows' desktop font hints without bitmap scaling.
Downloads are available from the private repository's **v0.5.2 preview release**; older releases remain intact.
It is not a claim that the real IBM i/ACS acceptance gate has been passed.
The [full manual](MANUAL.md) begins with **Getting Started** and is also
available offline from **Help > Full manual / Getting Started**, with a
searchable full-text topic index.

## Install: no developer tools needed

1. Open [Releases](https://github.com/HornadyIBM/Fish/releases) in the private [HornadyIBM/Fish repository](https://github.com/HornadyIBM/Fish) using an account with access.
2. Download `Fish-0.5.2-setup.exe` and its matching `SHA256SUMS.txt`.
3. Run it for your Windows user. It creates Start menu/desktop shortcuts without a system-wide Java installation or administrator installation.

For a portable copy, extract `Fish-0.5.2-windows-x64.zip` and open
`Fish\Fish.exe`. Keep the whole folder together. Windows 10/11 **x64** is
required; ARM64 and non-Windows systems are not supported by this package.
The executable/installer are unsigned, so SmartScreen or organizational policy
may warn or block them. Checksums accompany the package; do not bypass IT policy.

## Generate an executable and installer yourself

Extract the complete matching `Fish-source.zip`, then
**double-click `Setup-Fish.cmd`**. No Git, installed JDK, Visual Studio, WiX
installation, administrator rights or system PATH edits are needed.
The first build needs internet access and about 2 GB of free disk space.
The script downloads SHA-256-pinned portable tools into `.tools`, then produces:

| Output | Use |
|---|---|
| `dist\Fish\Fish.exe` | Run immediately with its adjacent app/runtime folders |
| `dist\Fish-0.5.2-setup.exe` | Install the preview for the current Windows user |
| `dist\Fish-0.5.2-windows-x64.zip` | Copy/extract the complete portable application |
| `dist\Fish-source.zip` | Matching complete application/core sources |
| `dist\SHA256SUMS.txt` | Verify distributed downloads |

`Setup-Fish.cmd -Install` additionally opens the generated installer.
Existing application/source output is preserved as timestamped backups.
Close Fish before rebuilding/upgrading its directory. The script does not
silently replace failed downloads or bypass checksum mismatches.

## Run the local package

The current local package is **Fish 0.5.2**.
Keep the entire application-image directory together:

```powershell
.\dist\Fish\Fish.exe
.\dist\Fish\Fish.exe --demo
```

The packaged application includes a private Java runtime; no installed Java,
administrator rights or installer is required. Windows PowerShell is needed
for Windows Credential Manager operations. The executable is not code-signed.
The demo runs a real, loopback-only synthetic TN5250 server with three
independent connections. F5 requests a 27x132 screen; F6 requests 24x80; Enter
sends edited fields to that server. It is not a simulated IBM i sign-on or
evidence of ACS parity.

Normal mode starts offline. Press **Ctrl+Alt+P**, enter the IBM i hostname or
IP address, leave port **23** unless configured otherwise, and choose
**Save & connect**. Only `IBM-3477-FC` is offered. A host controls whether the
screen is 24x80 or 27x132; resizing a pane only scales its rendering.

**Plain TCP does not encrypt credentials or terminal data.** Use only on a
trusted network. TLS and Kerberos are not implemented.

## Advanced build commands

Windows x64, Windows PowerShell 5.1 or later, and a **JDK 21** are required.
Git is required for a development clone; initialize its pinned submodule:

```powershell
git submodule update --init --recursive
# Prefer Setup-Fish.cmd above, or prepare the pinned portable SDK:
.\scripts\bootstrap.ps1
.\build.ps1 Build
.\build.ps1 Smoke     # quick Fish checks; hidden windows, no physical input
.\build.ps1 Check     # fixtures only; no foreground input or credential access
.\build.ps1 Test
.\build.ps1 Package
.\build.ps1 Installer # after Package; current-user installer, portable ZIP, checksums
```

The bootstrap downloads Temurin 21.0.12.1+1 into `.tools`, verifies its SHA-256,
and changes no system installation or PATH. Tool pins are in `tools.psd1`;
maintainers must refresh them for later security updates. `JAVA_HOME` takes precedence;
alternatively pass `-Jdk 'C:\path\to\jdk-21'` to `build.ps1`.
Native compilation uses checksum-pinned portable Zig; installer generation
uses portable WiX 3.14.1. Neither compiler nor WiX is installed system-wide.
Only the resulting small Fish-owned DLL is needed at runtime.

`Build` creates `dist\Fish.jar`. `Package` uses `jpackage` to create the
standalone directory `dist\Fish` and complete corresponding source archive
`dist\Fish-source.zip`. Move an existing `dist\Fish` aside before
packaging again; the script refuses to overwrite it.

The matching source is also under `dist\Fish\app\source`. It contains the
actual pinned upstream source and build adaptations, not just a retrieval
URL. Extract the source ZIP to an empty directory, select a JDK 21, and run
the same build commands. Git is unnecessary for that complete source archive;
offline builds additionally need the pinned JDK and Zig cache already prepared
(and WiX for installers).

```powershell
.\build.ps1 Run       # normal desktop, keeps the launching console attached
.\build.ps1 Demo      # local synthetic server and separate temporary workspace
# Or, after Build:
& "$env:JAVA_HOME\bin\java.exe" -jar .\dist\Fish.jar --demo
```

`Smoke` is the small noninteractive gate used for the Fish revision: bounded
slots, swap-only moves, immediate disconnect/close, minimal offline controls,
last-preset/legacy migration, physical-pixel text and real loopback sockets.
It also covers clipboard transfer/field safety with an isolated synthetic
clipboard (never your shared OS clipboard), deterministic animated Flux phases,
host-hidden/reverse/blink preservation, and the minimal triangle/rhombus icon geometry.
`StreamliningSmoke` adds global shortcut conflicts/dispatch, constant focus
insets, outline-only persistence, the manual index and native Windows hit tests.
It writes `build\fish-terminal-150.png` and `build\fish-empty-one.png`
without showing a window or taking focus. `CustomizationSmoke` covers complete
custom-theme save/preview/cancel/reload and role coverage, font validation/fixed
cells, zero-space information rows, local Reset overlay/host-busy exclusion,
outline off, Neon migration and removed Commands bindings. Its font fixture is
private and deleted; it never touches the shared clipboard or credentials.
`ChromeConsistencySmoke` checks exact one-device-pixel borders at 100%/150%,
complete odd-sized perimeters, shared-color migration, default-on/explicit-off
window-outline persistence, popup roots, compact menu order, native client/work
area calculations and peer recreation. Synthetic offscreen images are written
under `build\fish-051-*.png`; no real host screen or physical desktop is captured.
Its `--focus-only` mode exercises the actual Window and focus dialog's
Cancel/Apply buttons, all eight combinations of the three persistent settings,
and Cancel/Escape/Close dismissal without saving. Evidence uses the real shared
dialog builder rather than substituting a Done button.
Its `--geometry-only` mode adds 100%/125%/150%/175%/200% strict-clip and
nonzero-origin cases using Swing's actual backing-buffer endpoint rule.
`pane5250.ui.DpiDesktop` requires fresh foreground/shared-desktop consent:
it exercises production menus, combos, submenus and dialogs, partial repaints,
caption hover and native-client-coordinate pixel captures across actual monitors.
Those popup/hover/restored-window checks passed at 100%/150%; its six
maximize/restore perimeter assertions still fail. `--window-only` isolates
that remaining defect. Do not treat the offscreen smoke pass as physical
maximize/restore acceptance.
A separately consented, bounded
`StreamliningDesktop` pass verifies actual Ctrl+Tab recording, native side/quarter/top
snapping and maximize/restore. It is not the full foreground stress suite.

`Test` needs fresh consent and an unlocked, idle Windows desktop: it opens the app,
uses the physical keyboard/mouse through `Robot`, and temporarily takes focus
with an always-on-top test window. Keep other windows out of the way.
It also creates, reads and removes a uniquely named **synthetic** Windows
credential. It never connects to an IBM i. The rendered artifact is
`build\workspace-test.png`. The additional real-desktop rendering/window suite
captures `build\desktop-render-none-native.png` and
`build\desktop-render-soft-glow-native.png` and prints continuous-resize paint
and event-thread latency measurements. It verifies background clearing against
poisoned pixels, actual divider/header drags, all eight window resize directions,
window controls, keyboard move/size, focus, and unchanged session connections
and host dimensions, with effects off and Soft glow enabled. `UiRevisions.java`
is the historical 0.2.0 interaction script; its removal/confirmation/directional
split assertions are superseded and it is not part of the Fish test command.

## Sessions and profiles

The main workspace has one compact, dark 31px title/menu row, not a second
native titlebar. The gradient fish has a true square body rotated 45 degrees,
with its left sides extended to cross once and form a tail ending level with its top
and bottom, with no line joining the tail tips. It has no eye or mouth and replaces application-name
text and supplies the Windows executable/taskbar icon. Drag it or the blank title area to move;
double-click to maximize/restore. The three upper-right controls minimize,
maximize/restore and close. Drag any window edge/corner to resize.
**Alt+Space** keeps the standard Windows window menu; **Ctrl+Alt+Space**
opens the application's dark window menu (also available by right-clicking
the title area). Its Move/Size commands use arrows in 10px steps, Shift+arrows
for 1px, Enter to commit and Escape to cancel. Window close disconnects all
sessions and closes immediately, without a confirmation. Saving and releasing
the workspace lock finish in the background; save failures are reported to stderr.

The **+ Pane** icon/label control adds the next slot, up to **eight panes in two rows/four columns**.
The sequence is one full pane; two side-by-side; left full-height/right two
stacked; four quadrants; then top/bottom rows of 3/2, 3/3, 4/3 and 4/4.
There are no arbitrary recursive split directions. Addition is disabled at eight.
Drag any pane header anywhere onto another pane to **swap**; the entire target
is previewed. Ctrl+Alt+D offers the same swap by spatial position/preset.
Drag the hairline dividers to resize. Reflow and swaps retain actual session
objects, socket connections and host state.

The **X at the far right of each pane header** immediately disconnects and
removes that pane, reflowing the remaining slots. Only the last pane is kept
as an empty offline tile. Ctrl+Alt+W does the same; there is always at least
one pane. Headers show preset names, never A1/A2 identifiers.
Offline content contains only a centered preset selector and adjacent arrow Connect
icon button. The collapsed selector does not paint its value as selected text.
New panes preselect the last used preset; restored panes retain their saved
preset selection. Nothing auto-connects in normal mode.
The active pane has a single subtle tinted surface across its header, terminal
and footer, with an outer physical-pixel focus outline and no header underline.
Open **View > Appearance > More settings > Window and focus** to change
the active-pane outline and tint independently.
Focus never changes the border's layout insets. OLED themes retain true-black surfaces.

Native Windows frame styles and hit testing support Win+arrows, edge/corner
snapping, top-edge maximize and the Windows 11 maximize-button snap target
without a bright native titlebar. Narrow windows collapse menus and tool labels.
The fish mark is a keyboard-focusable secret button: a single click toggles
the active preset's text effect from Flux (animated) to None, or from any other
effect to Flux (animated). Changes use the same persistent profile path as Appearance; panes using
that same preset also refresh. Keyboard Space activates it immediately, with a
visible focus indicator. Mouse clicks wait for Windows' double-click interval
so double-clicks and drags do not accidentally change effects. The rest of the
title area remains available for moving/maximizing the window.

While connecting, the preset controls are replaced by a small rotating ring
until the first valid host record arrives. The footer retains connection status
and errors. X remains available to cancel a pending connection. When only one
pane remains and it is disconnected, its X is hidden; other pane-close and
disconnect behavior is unchanged.

**Edit > Keyboard > Shortcuts** lists every registered application and host action, with
record/unbind/default controls and conflict validation. The same list is linked
from **Profiles > Keyboard**. Assign **Ctrl+Tab** to Next pane and
**Ctrl+Shift+Tab** to Previous pane to use familiar cycling. Overrides are
global within this Fish workspace, not OS-wide. Modal UI stays isolated from
host input. Menus show effective bindings; conflicting host or reserved
Windows keys are never silently stolen.

The Commands window has been removed. Session, Edit, Panes, View, Host and Help
retain the useful actions, backed by the same configurable shortcut registry.
The old Ctrl+Alt+K chord and any saved Commands override are consumed as no-ops
until explicitly assigned to another action, rather than leaking to host input.

**Right-click the terminal** for Copy selection/screen and Paste, or use the
**Edit** menu.
Without a selection, Copy exports the visible host screen; drag or Shift+arrows
selects a rectangle. The keyboard's Context Menu key opens the same popup when
it is not assigned to a host action. Existing **Ctrl+Alt+Shift+C/V** shortcuts
remain available; Ctrl+C/V and Shift+F10 are not stolen from host mappings.
Copy omits non-display data. Paste respects host fields, validation, code pages
and inhibition; newlines/tabs navigate without submitting Enter. Clipboard
failures are reported in the pane status and actions return focus to the terminal.

Profiles store host, port, optional device name, username, manual/automatic
sign-on preferences, SBCS code page, host key overrides, palette, effect,
zoom and a saved layout. New and Duplicate are in the compact profile manager.
Duplication deliberately clears the device name and remembered-password flag
to prevent workstation/credential collisions.

Manual sign-on leaves the host's own login screen in control. Insert a saved
username/password explicitly or type into its fields; no login-field positions
are guessed. Password insertion requires a non-display input field, enough
remaining capacity, and an unlocked keyboard. It cannot spill into a visible
field. Optional automatic sign-on uses RFC 4777 `USER`, empty `IBMRSEED`, and
plain-text `IBMSUBSPW` when the server agrees to NEW-ENVIRON. A connected socket
is never labeled a successful sign-on.

An explicit device name is sent exactly as the normalized profile specifies,
only in response to NEW-ENVIRON SEND, not unsolicited on DO. Automatic naming
omits DEVNAME. RFC 4777 startup confirmations report the allocated/rejected
device and known rejection reason when supplied by the host. A collision does
not silently generate a different name. NEW-ENVIRON refusal cannot silently
fall back to automatic naming when an explicit name was requested. The exact
user-reported IBM i failure still needs a real-host retry/rejection code;
local fixtures and loopback named-device connections do not establish that
host's acceptance. See [COMPATIBILITY.md](COMPATIBILITY.md).

Remembered passwords are generic credentials in **Windows Credential Manager**,
under `Pane5250/<profile UUID>/<host>:<port>/<username>`, scoped to the current
Windows user. Uncheck Remember and save to remove a remembered credential.
Passwords are not written to profile XML, process arguments, logs or the
clipboard. JVM/PowerShell immutable strings and host screen memory are not
guaranteed to be securely erased.

New workspace state is atomically saved to `%APPDATA%\Fish\workspace.xml`.
If `%APPDATA%\Pane5250\workspace.xml` already exists, Fish uses that same
legacy directory and lock instead, preserving existing profiles and passwords.
The legacy `Pane5250/` credential target above is intentionally unchanged.
The last pane layout and window size are restored **offline** next launch.
Application appearance, global shortcuts, pane highlighting and the last used
preset are saved with version-7 state.
Version-1/2/3 profiles and credentials are retained. Old freeform layouts reflow
into bounded slots with a visible status notice; a legacy empty workspace
becomes one offline tile. A legacy layout over eight panes is rejected explicitly
without overwriting its file: reduce it using the preserved Pane5250 package
or use another `--data-dir`. It is never silently truncated.
**Ctrl+Alt+S/L** additionally saves/restores a profile-specific layout.
The workspace is locked against concurrent writers. Corrupt state produces
an error and is preserved, never silently replaced. `--data-dir DIRECTORY`
selects another workspace; the demo otherwise uses a temporary workspace and
removes it on normal exit.

## Keyboard

Application shortcuts default to **Ctrl+Alt**; global overrides may use other
safe keys such as Ctrl+Tab. Profile-specific host mappings cannot claim Ctrl+Alt.
**Alt+Space** and **Alt+F4** are also reserved for standard window operations.

| Action | Shortcut |
|---|---|
| Profiles / quick profile switch | Ctrl+Alt+P / Ctrl+Alt+O |
| Connect or disconnect / reconnect | Ctrl+Alt+R / Ctrl+Alt+Shift+R |
| Add the next bounded pane | Ctrl+Alt+H |
| Swap / close and disconnect pane (keep the last tile) | Ctrl+Alt+D / Ctrl+Alt+W |
| Next or previous pane / focus numbered pane | Ctrl+Alt+Right or Left / Ctrl+Alt+1..8 |
| Adjust nearest divider | Ctrl+Alt+[ or ] |
| Save / restore profile layout | Ctrl+Alt+S / Ctrl+Alt+L |
| Fit / zoom in or out | Ctrl+Alt+0 / Ctrl+Alt+Equals or Minus |
| Copy selection (or whole screen) / paste | Ctrl+Alt+Shift+C / Ctrl+Alt+Shift+V |
| Rectangular selection | Shift+arrows, or mouse drag |
| Insert profile username / remembered password | Ctrl+Alt+U / Ctrl+Alt+Shift+P |
| Host key chooser / application menu | Ctrl+Alt+F / Ctrl+Alt+M |
| Appearance | Ctrl+Alt+T |
| Dark window menu / standard Windows window menu | Ctrl+Alt+Space / Alt+Space |
| Immediately close window and disconnect all | Alt+F4 |
| Quit | Ctrl+Alt+Q |

| Host action | Default key |
|---|---|
| F1-F12 / F13-F24 | F1-F12 / Shift+F1-F12 |
| Enter / Field Exit | Enter / keypad Enter or Ctrl+Enter |
| Field Plus / Field Minus | Keypad + / - |
| Reset / Attention / System Request | Escape / Pause / Shift+Escape |
| Clear / Help | Ctrl+Backspace / Ctrl+H |
| Roll up / down | Page Up / Page Down |
| Next / previous field | Tab / Shift+Tab |
| Insert, delete, home, end of field | Insert, Delete, Home, End |

Use Profiles > Keyboard for per-profile overrides, e.g.
`ctrl ENTER = FIELD_EXIT` or `shift F1 = PF13`. The Host menu exposes available
host actions even on keyboards without Pause or a keypad. System Request
prompts for an optional option; an empty option requests its menu.

Paste is field-aware and bounded to 32768 characters. Unrepresentable characters
are rejected before editing; input errors or host inhibition stop further input.
Newlines and tabs navigate rather than sending Enter. Non-display text is
masked in copy/export. Fit and zoom never alter host screen dimensions.

For a recoverable terminal input error, a contextual **Reset** button appears
in that pane's footer, or as a corner overlay when the footer is hidden.
It clears the core's actual local pre-help/error state
without submitting Enter. It is not offered as an unlock for host-busy or
network failures. Enter retains its host meaning; Escape remains the default
Reset key.

**Appearance** offers eleven persistent application themes: Charcoal, Graphite,
Midnight, Paper, OLED, OLED Amber, High Contrast, High Contrast Light, Lagoon,
Afterglow and Eye Saver. Eye Saver is a warm low-glare palette, not a medical claim.
They cover custom chrome, headers/footers, menus/popups,
dialogs, fields, lists, tabs, selection, disabled/hover/pressed/focus states,
dividers and text. Application themes are independent of workstation host
palettes: Phosphor, Cool, Warm, Amber, Ice, Orchid, High contrast, IBM vivid,
Mint, Rose, OLED green and OLED amber. Host
color/reverse/underline/blink/non-display distinctions remain intact.
**View > Appearance > More settings > Theme designer** edits all 60 semantic RGB roles: surfaces, controls and
states, chrome, menus/tooltips, header/footer/statuses, outline/tint/dividers,
cursor/terminal selection, all sixteen host slots, six gradient stops, glow
and logo colors. Preview is temporary; Cancel restores the applied theme.
Save keeps a reusable named theme; Save & apply activates it. Editing an already
active named theme updates that theme on Save. Duplicate names require explicit
replacement confirmation; up to 32 custom themes are stored in the workspace.
Valid colors are not silently contrast-corrected. When a custom theme is active,
its host slots supersede the profile's built-in palette; other profile settings
and host attributes are unchanged.
**Structure > Divider** controls every owned structural border/separator,
including optional pane/window outlines. Version-5 custom themes keep their
existing divider color; old separate LINE/OUTLINE colors are linked to it with
an explicit migration notice.

**Appearance > More settings > Fonts** discovers available regular monospaced families and accepts a
local TTF/OTF file. Apply checks printable fixed-cell widths and the current
profiles' code-page glyphs. Missing/unsupported files are reported explicitly;
startup uses an announced temporary Monospaced fallback without erasing the
saved choice. Font files stay local and must remain available at the saved path.
They are not bundled, uploaded or copied into releases.

**Appearance > More settings > Pane information** independently controls each header/footer item and
both rows, with no space for empty rows. Outline and tint live in **Window and focus**.
With a hidden/empty header, its independent close X overlays the upper-right
corner without reserving a band; Reset remains a separate non-overlapping overlay.
Use the terminal context menu, Panes menu or Alt+drag to swap.
Workspace appearance settings apply across profiles; effects remain
profile-specific.

Optional Soft glow, column-gradient Prism and **Flux (animated)**
effects are disabled by default. Choose **View > Appearance > Text effect >
Flux (animated)**, or **Appearance > More settings > Flux animated text**, for smoothly
flowing multicolor gradients. The effect is saved with the preset. Neutral host
text carries the full color flow; chromatic text varies within its host hue
family, reverse video retains its exact colors, and hidden data is never drawn.
Selection, cursor and host blink remain distinct. Animation uses cached native
glyphs and one shared coalescing 20fps clock; it stops for hidden, minimized,
disconnected and disposed terminals. Choose None to stop the effect.
Persisted Neon effects migrate to None with an explicit load notice; the rest
of each profile is retained.

Default glyphs are created at the final physical font
size, with integer-pixel cells/origins and LCD antialiasing; text is not scaled
from a nominal font or bilinearly enlarged bitmap. Effect ink is cached in tight,
background-composited row tiles at that same native physical resolution for
the static effects. Flux reuses the native glyphs with per-used-color gradient
paints, never rerasterizing an enlarged text bitmap.
Selection, reverse backgrounds and blink remain accurate; resizing does not
recreate glyphs/effects while the fitted physical font size is unchanged. The application has no
AI features, telemetry, embedded browser, dashboard or promotional UI.

## Source organization and license

`net` owns sockets/Telnet, the selective `core-adapter` owns the engine seam,
`model` owns profiles/layout/persistence, `security` owns OS credential access,
`ui` owns Swing rendering/workspace/input, `src\native` owns the Windows
frame bridge, and `demo` owns the synthetic host.
Core mutations, input and rendering are serialized on Swing's event thread;
socket reads/writes, credential calls and persistence run off it.

GPL-2.0-or-later, with no warranty. See [LICENSE](LICENSE), [NOTICE](NOTICE)
and the upstream source notices. Keep the matching source, notices and bundled
runtime legal directory with redistributed builds.
