# Pane5250

A Windows-first, keyboard-driven desktop 5250 workspace. Java 21 / Swing,
plain TN5250 TCP, a pinned TN5250J screen/field engine, and no third-party
runtime JAR dependencies.

**Working experimental implementation, not an ACS-compatible release.**
Real IBM i sign-on and ACS comparison have not been performed. Enhanced display
structured fields and WEA are explicit compatibility gaps, not silently
accepted features. Read [COMPATIBILITY.md](COMPATIBILITY.md) before connecting
to a production system.

## Run the delivered application

The current local package is **0.2.0**. Keep the entire application-image directory together:

```powershell
.\dist\Pane5250\Pane5250.exe
.\dist\Pane5250\Pane5250.exe --demo
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

## Build from source

Windows x64, Windows PowerShell 5.1 or later, and a **JDK 21** are required.
Git is required for a development clone; initialize its pinned submodule:

```powershell
git submodule update --init --recursive
# Use a maintained JDK 21 by setting JAVA_HOME, or obtain the pinned portable SDK:
.\scripts\bootstrap.ps1
.\build.ps1 Build
.\build.ps1 Check     # fixtures only; no foreground input or credential access
.\build.ps1 Test
.\build.ps1 Package
```

The bootstrap downloads Temurin 21.0.8+9 into `.tools`, verifies its SHA-256,
and changes no system installation or PATH. It is a reproducibility pin, not
a promise to track current JDK security updates. `JAVA_HOME` takes precedence;
alternatively pass `-Jdk 'C:\path\to\jdk-21'` to `build.ps1`.

`Build` creates `dist\Pane5250.jar`. `Package` uses `jpackage` to create the
standalone directory `dist\Pane5250` and complete corresponding source archive
`dist\Pane5250-source.zip`. Move an existing `dist\Pane5250` aside before
packaging again; the script refuses to overwrite it.

The matching source is also under `dist\Pane5250\app\source`. It contains the
actual pinned upstream source and build adaptations, not just a retrieval
URL. Extract the source ZIP to an empty directory, select a JDK 21, and run
the same build commands; Git/network access is unnecessary when those sources
and a JDK are already present.

```powershell
.\build.ps1 Run       # normal desktop, keeps the launching console attached
.\build.ps1 Demo      # local synthetic server and separate temporary workspace
# Or, after Build:
& "$env:JAVA_HOME\bin\java.exe" -jar .\dist\Pane5250.jar --demo
```

`Test` needs an unlocked, idle Windows desktop: it opens the app,
uses the physical keyboard/mouse through `Robot`, and temporarily takes focus
with an always-on-top test window. Keep other windows out of the way.
It also creates, reads and removes a uniquely named **synthetic** Windows
credential. It never connects to an IBM i. The rendered artifact is
`build\workspace-test.png`. The additional real-desktop rendering/window suite
captures `build\desktop-render-none-native.png` and
`build\desktop-render-neon-native.png` and prints continuous-resize paint
and event-thread latency measurements. It verifies background clearing against
poisoned pixels, actual divider/header drags, all eight window resize directions,
window controls, keyboard move/size, focus, and unchanged session connections
and host dimensions, with effects off and Neon enabled. The revision suite
exercises actual pane X/add controls, palette search/navigation and focus,
local-error Reset versus host inhibition, live themes, dialog routes, effect
differences, empty-workspace behavior and persistence. Its app-only captures
are `build\revision-*.png`.

## Sessions and profiles

The main workspace has one compact, dark 28px title/menu row, not a second
native titlebar. Drag the blank title area or application name to move it;
double-click to maximize/restore. The three upper-right controls minimize,
maximize/restore and close. Drag any window edge/corner to resize.
**Alt+Space** keeps the standard Windows window menu; **Ctrl+Alt+Space**
opens the application's dark window menu (also available by right-clicking
the title area). Its Move/Size commands use arrows in 10px steps, Shift+arrows
for 1px, Enter to commit and Escape to cancel. Closing connected sessions
still requires confirmation.

Drag a pane header to another pane: edges dock it, the center swaps it.
Drop previews show the proposed position. Drag the hairline split handles
to resize. Moving panes keeps their existing socket and terminal state.
Splitting creates a new, independent, initially offline session.
The immediately visible **+ Pane** adds to the right; its adjacent arrow
offers left/right or top/bottom splits. The **X at the far right of each pane
header** closes only that pane, with confirmation when connected/connecting.
Closing the last pane leaves an empty workspace; + Pane adds one back.
The active pane has a single subtle tinted surface across its header, terminal
and footer, with an outer focus outline and no header underline.

**Commands** or **Ctrl+Alt+K** opens searchable commands with descriptions,
shortcuts and aliases. Search `add pane`, `add window`, `new session`, `split`,
`reset`, `theme` or a host key. Up/Down selects, Enter runs and Escape returns
to the intended terminal. Disabled actions stay disabled; palette typing
never edits host fields. Menus and palette use the same action handlers.

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

Workspace state is atomically saved to `%APPDATA%\Pane5250\workspace.xml`.
The last pane layout and window size are restored **offline** next launch.
Application appearance is saved with the workspace. Existing version-1 state
migrates to Charcoal while retaining profiles and layouts; version-2 state also
supports an empty workspace.
**Ctrl+Alt+S/L** additionally saves/restores a profile-specific layout.
The workspace is locked against concurrent writers. Corrupt state produces
an error and is preserved, never silently replaced. `--data-dir DIRECTORY`
selects another workspace; the demo otherwise uses a temporary workspace and
removes it on normal exit.

## Keyboard

Application shortcuts use **Ctrl+Alt**; host mappings cannot claim that namespace.
**Alt+Space** and **Alt+F4** are also reserved for standard window operations.

| Action | Shortcut |
|---|---|
| Profiles / quick profile switch | Ctrl+Alt+P / Ctrl+Alt+O |
| Connect or disconnect / reconnect | Ctrl+Alt+R / Ctrl+Alt+Shift+R |
| Split left-right / top-bottom | Ctrl+Alt+H / Ctrl+Alt+V |
| Dock or swap / close pane | Ctrl+Alt+D / Ctrl+Alt+W |
| Next or previous pane / focus numbered pane | Ctrl+Alt+Right or Left / Ctrl+Alt+1..9 |
| Adjust nearest divider | Ctrl+Alt+[ or ] |
| Save / restore profile layout | Ctrl+Alt+S / Ctrl+Alt+L |
| Fit / zoom in or out | Ctrl+Alt+0 / Ctrl+Alt+Equals or Minus |
| Copy selection (or whole screen) / paste | Ctrl+Alt+Shift+C / Ctrl+Alt+Shift+V |
| Rectangular selection | Shift+arrows, or mouse drag |
| Insert profile username / remembered password | Ctrl+Alt+U / Ctrl+Alt+Shift+P |
| Host key chooser / application menu | Ctrl+Alt+F / Ctrl+Alt+M |
| Search commands / appearance | Ctrl+Alt+K / Ctrl+Alt+T |
| Dark window menu / standard Windows window menu | Ctrl+Alt+Space / Alt+Space |
| Close window (with connected-session confirmation) | Alt+F4 |
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
in that pane's footer. It clears the core's actual local pre-help/error state
without submitting Enter. It is not offered as an unlock for host-busy or
network failures. Enter retains its host meaning; Escape remains the default
Reset key.

**Appearance** offers four persistent application themes: Charcoal, Graphite,
Midnight and Paper. They cover custom chrome, headers/footers, menus/popups,
dialogs, fields, lists, tabs, selection, disabled/hover/pressed/focus states,
dividers and text. Application themes are independent of workstation host
palettes: Phosphor, Cool, Warm, Amber, Ice, Orchid and High contrast. Host
color/reverse/underline/blink/non-display distinctions remain intact.
Optional Soft glow, column-gradient Prism and pronounced Neon effects are
disabled by default. Effect ink is cached in tight, background-composited row
tiles at one of two resolution levels suited to the physical display size.
Selection, reverse backgrounds and blink remain accurate; resizing does not
recreate per-character glyphs/effects on every frame. The application has no
AI features, telemetry, embedded browser, dashboard or promotional UI.

## Source organization and license

`net` owns sockets/Telnet, the selective `core-adapter` owns the engine seam,
`model` owns profiles/layout/persistence, `security` owns OS credential access,
`ui` owns Swing rendering/workspace/input, and `demo` owns the synthetic host.
Core mutations, input and rendering are serialized on Swing's event thread;
socket reads/writes, credential calls and persistence run off it.

GPL-2.0-or-later, with no warranty. See [LICENSE](LICENSE), [NOTICE](NOTICE)
and the upstream source notices. Keep the matching source, notices and bundled
runtime legal directory with redistributed builds.
