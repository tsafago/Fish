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

Keep the entire application-image directory together:

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

`Test` needs an unlocked, interactive Windows desktop: it opens the app,
uses the physical keyboard/mouse through `Robot`, and temporarily takes focus.
It also creates, reads and removes a uniquely named **synthetic** Windows
credential. It never connects to an IBM i. The rendered artifact is
`build\workspace-test.png`.

## Sessions and profiles

Drag a pane header to another pane: edges dock it, the center swaps it.
Drop previews show the proposed position. Drag the hairline split handles
to resize. Moving panes keeps their existing socket and terminal state.
Splitting creates a new, independent, initially offline session.

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

Remembered passwords are generic credentials in **Windows Credential Manager**,
under `Pane5250/<profile UUID>/<host>:<port>/<username>`, scoped to the current
Windows user. Uncheck Remember and save to remove a remembered credential.
Passwords are not written to profile XML, process arguments, logs or the
clipboard. JVM/PowerShell immutable strings and host screen memory are not
guaranteed to be securely erased.

Workspace state is atomically saved to `%APPDATA%\Pane5250\workspace.xml`.
The last pane layout and window size are restored **offline** next launch.
**Ctrl+Alt+S/L** additionally saves/restores a profile-specific layout.
The workspace is locked against concurrent writers. Corrupt state produces
an error and is preserved, never silently replaced. `--data-dir DIRECTORY`
selects another workspace; the demo otherwise uses a temporary workspace and
removes it on normal exit.

## Keyboard

Application shortcuts use **Ctrl+Alt**; host mappings cannot claim that namespace.

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

Phosphor, Cool and Warm palettes retain host attribute distinctions. Optional
Soft glow and Prism effects are disabled by default. The application has no
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
