# Fish

A Windows-first, keyboard-driven desktop 5250 workspace. Java 21 / Swing,
plain TN5250 TCP, a pinned TN5250J screen/field engine, and no third-party
runtime JAR dependencies.

**Working experimental implementation, not an ACS-compatible release.**
Real IBM i sign-on and ACS comparison have not been performed. Enhanced display
structured fields and WEA are explicit compatibility gaps, not silently
accepted features. Read [COMPATIBILITY.md](COMPATIBILITY.md) before connecting
to a production system.

## Run the delivered application

The current local package is **Fish 0.3.0**. Keep the entire application-image directory together:

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

## Build from source

Windows x64, Windows PowerShell 5.1 or later, and a **JDK 21** are required.
Git is required for a development clone; initialize its pinned submodule:

```powershell
git submodule update --init --recursive
# Use a maintained JDK 21 by setting JAVA_HOME, or obtain the pinned portable SDK:
.\scripts\bootstrap.ps1
.\build.ps1 Build
.\build.ps1 Smoke     # quick Fish checks; hidden windows, no physical input
.\build.ps1 Check     # fixtures only; no foreground input or credential access
.\build.ps1 Test
.\build.ps1 Package
```

The bootstrap downloads Temurin 21.0.8+9 into `.tools`, verifies its SHA-256,
and changes no system installation or PATH. It is a reproducibility pin, not
a promise to track current JDK security updates. `JAVA_HOME` takes precedence;
alternatively pass `-Jdk 'C:\path\to\jdk-21'` to `build.ps1`.

`Build` creates `dist\Fish.jar`. `Package` uses `jpackage` to create the
standalone directory `dist\Fish` and complete corresponding source archive
`dist\Fish-source.zip`. Move an existing `dist\Fish` aside before
packaging again; the script refuses to overwrite it.

The matching source is also under `dist\Fish\app\source`. It contains the
actual pinned upstream source and build adaptations, not just a retrieval
URL. Extract the source ZIP to an empty directory, select a JDK 21, and run
the same build commands; Git/network access is unnecessary when those sources
and a JDK are already present.

```powershell
.\build.ps1 Run       # normal desktop, keeps the launching console attached
.\build.ps1 Demo      # local synthetic server and separate temporary workspace
# Or, after Build:
& "$env:JAVA_HOME\bin\java.exe" -jar .\dist\Fish.jar --demo
```

`Smoke` is the small noninteractive gate used for the Fish revision: bounded
slots, swap-only moves, immediate disconnect/close, minimal offline controls,
last-preset/legacy migration, physical-pixel text and real loopback sockets.
It writes `build\fish-terminal-150.png` and `build\fish-offline-eight.png`
without showing a window or taking focus. No full/foreground suite was run
for this quick revision.

`Test` needs fresh consent and an unlocked, idle Windows desktop: it opens the app,
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
and host dimensions, with effects off and Neon enabled. `UiRevisions.java`
is the historical 0.2.0 interaction script; its removal/confirmation/directional
split assertions are superseded and it is not part of the Fish test command.

## Sessions and profiles

The main workspace has one compact, dark 28px title/menu row, not a second
native titlebar. The angled gradient wireframe fish replaces application-name
text and supplies the Windows executable/taskbar icon. Drag it or the blank title area to move;
double-click to maximize/restore. The three upper-right controls minimize,
maximize/restore and close. Drag any window edge/corner to resize.
**Alt+Space** keeps the standard Windows window menu; **Ctrl+Alt+Space**
opens the application's dark window menu (also available by right-clicking
the title area). Its Move/Size commands use arrows in 10px steps, Shift+arrows
for 1px, Enter to commit and Escape to cancel. Window close disconnects all
sessions and closes immediately, without a confirmation. Saving and releasing
the workspace lock finish in the background; save failures are reported to stderr.

**+ Pane** adds the next slot, up to **eight panes in two rows/four columns**.
The sequence is one full pane; two side-by-side; left full-height/right two
stacked; four quadrants; then top/bottom rows of 3/2, 3/3, 4/3 and 4/4.
There are no arbitrary recursive split directions. Addition is disabled at eight.
Drag any pane header anywhere onto another pane to **swap**; the entire target
is previewed. Ctrl+Alt+D offers the same swap by spatial position/preset.
Drag the hairline dividers to resize. Reflow and swaps retain actual session
objects, socket connections and host state.

The **X at the far right of each pane header** immediately disconnects and
clears only that session, retaining its tile. Ctrl+Alt+W does the same; there
is always at least one pane. Headers show preset names, never A1/A2 identifiers.
Offline content contains only a centered preset selector and Connect button.
New panes preselect the last used preset; restored panes retain their saved
preset selection. Nothing auto-connects in normal mode.
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

New workspace state is atomically saved to `%APPDATA%\Fish\workspace.xml`.
If `%APPDATA%\Pane5250\workspace.xml` already exists, Fish uses that same
legacy directory and lock instead, preserving existing profiles and passwords.
The legacy `Pane5250/` credential target above is intentionally unchanged.
The last pane layout and window size are restored **offline** next launch.
Application appearance and the last used preset are saved with version-3 state.
Version-1/2 profiles and credentials are retained. Old freeform layouts reflow
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

Application shortcuts use **Ctrl+Alt**; host mappings cannot claim that namespace.
**Alt+Space** and **Alt+F4** are also reserved for standard window operations.

| Action | Shortcut |
|---|---|
| Profiles / quick profile switch | Ctrl+Alt+P / Ctrl+Alt+O |
| Connect or disconnect / reconnect | Ctrl+Alt+R / Ctrl+Alt+Shift+R |
| Add the next bounded pane (both shortcuts) | Ctrl+Alt+H / Ctrl+Alt+V |
| Swap / disconnect and clear pane | Ctrl+Alt+D / Ctrl+Alt+W |
| Next or previous pane / focus numbered pane | Ctrl+Alt+Right or Left / Ctrl+Alt+1..8 |
| Adjust nearest divider | Ctrl+Alt+[ or ] |
| Save / restore profile layout | Ctrl+Alt+S / Ctrl+Alt+L |
| Fit / zoom in or out | Ctrl+Alt+0 / Ctrl+Alt+Equals or Minus |
| Copy selection (or whole screen) / paste | Ctrl+Alt+Shift+C / Ctrl+Alt+Shift+V |
| Rectangular selection | Shift+arrows, or mouse drag |
| Insert profile username / remembered password | Ctrl+Alt+U / Ctrl+Alt+Shift+P |
| Host key chooser / application menu | Ctrl+Alt+F / Ctrl+Alt+M |
| Search commands / appearance | Ctrl+Alt+K / Ctrl+Alt+T |
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
disabled by default. Default glyphs are created at the final physical font
size, with integer-pixel cells/origins and LCD antialiasing; text is not scaled
from a nominal font or bilinearly enlarged bitmap. Effect ink is cached in tight,
background-composited row tiles at that same native physical resolution.
Selection, reverse backgrounds and blink remain accurate; resizing does not
recreate glyphs/effects while the fitted physical font size is unchanged. The application has no
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
