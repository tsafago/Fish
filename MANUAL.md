# Fish manual

Fish 0.5.2 - unsigned experimental DPI-regression preview.

Known remaining defect: the whole-window perimeter can disappear after maximize/restore. Real-monitor checks for that path still fail at 100% and 150% scaling. This is not a complete border/ACS-compatibility fix.
This manual describes the implemented application, not an assurance of full ACS compatibility.
Open Help > Full manual / Getting Started for the searchable topic index.

## Getting Started

You need the IBM i's hostname or IP address, a valid IBM i username and password, and permission to use its Telnet service. Ask your IBM i administrator for these if you do not already have them.

1. Run Fish.exe. Keep the complete Fish folder together if using the portable download.
2. Open Session > Profiles. Select Local IBM i, or choose New.
3. Give the profile a useful name. Enter the Host. Leave Port at 23 unless your administrator gave you another port.
4. Leave Device empty to let the IBM i choose a workstation. Leave Automatic sign-on off for the simplest, most predictable first connection. The default code page is 37.
5. Choose Save & connect. A TCP connection is not the same as a successful IBM i sign-on.
6. On the IBM i's own sign-on screen, type your username, press Tab to reach its password field, type your password, then press Enter. The host controls the actual fields and their order. Password characters normally remain invisible.
7. Work with the displayed IBM i application. Enter submits to the host. F1-F12 are host function keys; Shift+F1-F12 sends F13-F24.
8. Use the pane's X to close that connection. The final pane stays as an empty tile. Closing Fish disconnects everything immediately, without confirmation.

Plain TCP is not encrypted. Use a trusted network approved by your administrator. Fish has not yet been accepted against a real IBM i or ACS session; see Compatibility before production use.

If connection fails, check the hostname, port, VPN/network and IBM i Telnet service first. A blank Device is the recommended starting point. Do not enter your password in a visible application field.

## Installation: ready-built downloads

Fish targets Windows 10/11 x64. No separate Java installation is required.

Download Fish-0.5.2-setup.exe and matching SHA256SUMS.txt from the private HornadyIBM/Fish repository's v0.5.2 preview release. Older releases remain separate. The installer targets the current Windows user, not all users, and creates a Start menu entry and desktop shortcut. Its default location is your local application-data Fish directory; the installer allows another location. It should not request administrator installation.

Alternatively, extract Fish-0.5.2-windows-x64.zip to a writable folder and run Fish\Fish.exe. Do not move Fish.exe out of that folder: its app and runtime directories are required.

The binaries are unsigned. Windows SmartScreen or an organization's policy may warn or block them. Verify the SHA256SUMS.txt download against the artifact if required by your administrator. Do not override your organization's security policy.

The installer does not need your IBM i credentials. Profiles are created inside Fish after installation.

## Building an executable and installer

Download Fish-source.zip and extract it to a writable folder. Open that folder and double-click Setup-Fish.cmd. Leave the console open until it reports Ready.

The script obtains checksum-pinned portable JDK 21, Zig and WiX tools inside .tools, builds Fish and generates both its portable application and current-user installer. It does not install a system JDK, modify system PATH, require Git for the complete source archive, or require administrator installation.

The first build needs internet access and approximately 2 GB of free space for tools, caches, intermediate files and packages. Windows PowerShell 5.1 and Windows' curl are used. The pinned source/build tools are listed in tools.psd1.

Outputs are dist\Fish\Fish.exe, dist\Fish-0.5.2-setup.exe, dist\Fish-0.5.2-windows-x64.zip and dist\Fish-source.zip. Prior dist\Fish directories are preserved as timestamped backups. Close a running copy before rebuilding its directory.

To build and open the installer in one step, run Setup-Fish.cmd -Install. For advanced use, Setup-Fish.ps1 accepts -Install and build.ps1 accepts Build, Smoke, Check, Test, Package, Installer, Run and Demo. The Test task uses the physical desktop; do not run it while someone is working.

Downloaded build tools are not application runtime dependencies. Once packaged, Fish runs with its included Java runtime and native Windows frame bridge.

## Upgrading, uninstalling and backups

Close running Fish windows before an upgrade. Install the new version for the same Windows user. The stable installer upgrade identity allows later releases to replace the application without changing the profile directory.

Uninstall through Windows Settings > Apps. Uninstalling the application does not intentionally delete workspace profiles or Windows Credential Manager entries. Back up workspace.xml before manual changes or a downgrade.

Older Fish/Pane5250 workspaces are migrated on load; do not run two versions against the same directory simultaneously. Version-7 state retains profiles, custom themes, font references, shortcuts, active-pane outline/tint and pane-information choices. Whole-window outline is now on by default. Version-5/6 state did not distinguish an explicit off choice from the previous default: both migrate to on once. Turn it off again under Window and focus if desired; version-7 off choices persist unchanged. Expected migrations are informational console messages, never red startup errors. Malformed files and genuine font, credential, connection and filesystem failures remain explicit. Older builds do not understand version-7 state; use a backup or a separate data directory for a downgrade.

The removed Neon effect becomes None with an explicit load notice; the rest of the profile is preserved. The removed Commands action leaves no shortcut-editor entry. Its old default Ctrl+Alt+K and any saved custom chord are reserved as no-ops until explicitly assigned to another action, preventing accidental host input.

## Workspace and panes

Fish supports one to eight independent sessions. Each connected pane has its own TCP socket, host screen and input state. Focus changes, pane moves and window resizing do not reconnect sessions or change host dimensions.

The compact + Pane control adds the next tile. Layouts are deterministic: one full pane; two side-by-side; a full-height left pane and two stacked right panes; four quadrants; then top/bottom rows of 3/2, 3/3, 4/3 and 4/4. Adding is disabled at eight.

Drag a pane header onto another pane to swap the sessions. The full target is previewed; dropping never subdivides a tile. Panes > Swap panes provides the keyboard equivalent. Drag a hairline divider, or use Decrease divider / Increase divider.

If headers are hidden, Alt+drag on a terminal swaps panes without moving the host cursor. The terminal context menu and Panes menu retain pane actions.

Pane X disconnects and removes its tile immediately; other panes reflow and stay connected. The last pane disconnects but remains as an empty tile. Closing the window is immediate and does not ask about unsent host edits.

The empty pane contains a preset selector and the arrow Connect button directly to its right. New tiles preselect the last-used preset, but never auto-connect. Existing offline tiles remember their own preset.

## Focus and switching panes

The selected pane has an optional physical-pixel outline. View > Appearance > More settings > Window and focus controls it independently of active tint and the whole-window outline. Disabling the pane outline removes its border/insets, not just its color. Activating a different pane does not change spacing. Its header, terminal and footer share one surface; keyboard focus and the terminal cursor remain available with both outline and tint off. OLED themes retain a true-black terminal background.

Panes > Navigation > Next pane and Previous pane cycle focus. The defaults are Ctrl+Alt+Right and Ctrl+Alt+Left. To use Ctrl+Tab, open Edit > Keyboard > Shortcuts, find Next pane, select it, choose Record shortcut and press Ctrl+Tab. For reverse cycling, assign Ctrl+Shift+Tab to Previous pane.

Ctrl+Alt+1 through Ctrl+Alt+8 focus the corresponding layout position. The Shortcuts list can change these too. These are panes inside Fish, not separate document tabs.

## Windows positioning and resizing

Drag the blank title area to move the window using native Windows move handling. Drag to a screen edge/corner for the Windows snap arrangements available on your system. Win+Left/Right, Win+Up/Down and Windows 11 Win+Z remain operating-system commands.

On Windows 11, hover over the maximize button to request Snap Layouts. Available layouts depend on screen size, scaling and Windows settings. The titlebar is custom, but native resize/maximize styles and hit testing are retained.

Drag any window edge or corner to resize. Double-click the blank title area to maximize or restore. Upper-right buttons minimize, maximize/restore and close. A compact application-menu button replaces the menu labels in narrow windows.

Alt+Space opens the Windows system menu. The default Ctrl+Alt+Space opens Fish's themed window menu. Its keyboard Move and Size commands use arrow keys, Shift+arrows for single-unit adjustments, Enter to finish and Escape to cancel.

The whole-window outline is on by default and is not required for resizing.
View > Appearance > More settings > Window and focus can disable the complete square one-physical-pixel perimeter.
The restored-window perimeter is verified before maximizing, but it can disappear after maximize/restore; this is a known unresolved native geometry/surface-inset defect.
All owned structural borders, menu separators and the titlebar/content divider use Theme Designer's Structure > Divider color.
Dialogs and popups have the same flat edge, without native shadows or rounded corners. Windows-owned system menus and installers remain OS-controlled.

Window changes scale the terminal rendering; only the host may switch between 24x80 and 27x132. Very small snapped windows cannot make a dense 132-column screen as readable as a larger window.

## Saved profiles

Session > Profiles manages saved workstations. New creates an offline preset; Duplicate copies settings but clears the requested device name and remembered-password flag to avoid collisions.

A profile stores name, host, port, optional device name, username, authentication preferences, SBCS code page, host palette, text effect, zoom, host-key overrides and a saved layout. Application/custom themes, terminal font, global shortcuts, outline, tint, pane-information preferences and last-used preset belong to the workspace.

Save stores edits without connecting. Save & connect selects the profile and connects. Cancel discards unsaved edits after the profile editor's unsaved-edit prompt. That prompt is separate from pane/window closing, which has no confirmation.

Changing connection settings disconnects affected sessions; display-only changes refresh them without reconnecting. Session > Switch profile offers a quick selector. Reconnect starts a new TCP session; unsent changes and the old host presentation are not preserved.

## Host, port and device name

Enter a hostname or IP address, not a URL. Port defaults to 23 and may be changed to 1-65535. The host must accept plain TN5250.

Only IBM-3477-FC is negotiated. Device names are optional, at most ten characters, start with a letter or IBM i national character (#, $ or @), and may otherwise contain digits and underscores. Fish normalizes them to uppercase.

Blank Device lets the IBM i allocate a workstation. An explicit name is requested through negotiated NEW-ENVIRON, not silently discarded or replaced. A collision, host configuration, disabled device creation or permission problem may prevent allocation.

Startup confirmations show an allocated/rejected device when the host supplies one. A host's rejection details are needed to diagnose remaining real-system cases; a successful local synthetic fixture does not establish that IBM i's acceptance.

## Manual sign-on and remembered credentials

Manual sign-on uses the host's own screen. Fish does not guess sign-on field positions or invent a signed-on state. Session > Sign-on helpers > Insert profile username inserts into the current field using normal validation.

Enable Remember password and save a password in the profile editor to use Windows Credential Manager. Session > Sign-on helpers > Insert remembered password is allowed only in an unlocked non-display input field with enough remaining capacity. It cannot spill into a visible field.

A blank password field in the editor preserves an existing remembered secret; it is not an indication that no password exists. Disabling Remember and saving removes the remembered entry. Duplicating a profile does not copy its remembered password.

Credential targets retain the legacy Pane5250 prefix so existing secrets remain reachable after rebranding. Passwords are not stored in profile XML or process arguments. Clipboard export omits non-display fields.

## Automatic sign-on and security

Automatic sign-on is optional and requires a profile username. The current implemented mechanism uses RFC 4777 NEW-ENVIRON USER, an empty IBMRSEED and a negotiated plain-text IBMSUBSPW password.

Cryptographic password substitution is not implemented. TLS and Kerberos are not implemented. Plain TCP transmits credentials and terminal data without encryption; use only a trusted approved network. If the host requires an unsupported authentication mechanism, Fish must not claim success.

Do not treat Connected/TCP as proof of authentication. The IBM i host decides whether sign-on succeeded. JVM/PowerShell strings and host screen memory are not guaranteed to be securely erased.

## Host screens, fields and cursor

The host controls its presentation space: 24 rows by 80 columns or 27 rows by 132 columns. Pane geometry and zoom do not negotiate a new size.

Type only in unprotected fields. Tab and Shift+Tab move between fields; click a valid screen position to move the cursor. Numeric, signed, mandatory and other supported validations are enforced by the core. Protected positions, unsupported characters or field constraints can reject input.

Enter submits according to the host's current read/input mode. Field Exit is distinct from Enter and is not a host transaction shortcut. Newline and roll actions also have distinct host meanings.

Host colors, reverse video, underline, blink, separators and non-display attributes remain meaningful across palettes and effects. Non-display text stays hidden rather than being made visible by a theme.

## Input inhibition and Reset

The optional footer reports connection, input state, cursor and host dimensions. A contextual Reset button appears for the core's recoverable local input error, such as rejected field input. When the footer is absent, Reset overlays a corner opposite the editing cursor without adding spacing or taking focus. It is keyboard reachable and disappears after recovery. Escape sends Reset by default.

Reset does not silently submit Enter and must not unlock an unrelated host-busy condition. If the host has inhibited input while processing, wait for the host. A disconnected network cannot be repaired by Reset; reconnect after diagnosing the connection.

Attention and System Request have separate host meanings and may be appropriate for a blocked application. Consult your IBM i administrator before interrupting work or ending a job.

## Host-function keyboard reference

Defaults: F1-F12 send F1-F12; Shift+F1-F12 send F13-F24. Enter sends Enter. Keypad Enter or Ctrl+Enter sends Field Exit. Keypad + and - send Field+ and Field-.

Escape sends Reset; Pause sends Attention; Shift+Escape sends System Request; Ctrl+Backspace sends Clear; Ctrl+H sends Help.

Page Up/Down send roll keys. Ctrl+Page Up/Down send horizontal roll actions. Tab/Shift+Tab move fields. Arrows move the cursor. Home, End, Insert, Delete, Backspace and Ctrl+End retain the core's corresponding editing actions.

Host > Send host key offers the complete supported action list. Edit > Keyboard > Shortcuts lists those host actions and the application actions. Disabled actions remain disabled; sending an unavailable action never creates a fake host response.

## Global shortcut overrides

Edit > Keyboard > Shortcuts lists every registered Fish action, including window/menu commands, profile actions, pane focus, clipboard, appearance and all host actions. The same list is reachable from Profiles > Keyboard.

Find an action, select its row and use Record shortcut. Press the desired keys and choose Use shortcut. Unbind clears its binding; Restore default restores the selected action. Save shortcuts persists the entire change; Cancel leaves existing bindings intact.

Overrides apply throughout this Fish workspace, across all profiles and panes. They are not system-wide hotkeys. Ctrl+Tab is supported even when focus is on a terminal or offline selector because app dispatch precedes Swing focus traversal.

Conflicts are rejected with an actionable explanation. Windows-key combinations, Alt+Space and Alt+F4 remain owned by Windows. Unmodified typing letters/numbers are not application shortcuts. A global host-action override replaces that action's original profile/default host bindings; it does not leave a second hidden binding active.

If a new app binding conflicts with a host action, first unbind/reassign that host action in the same list, or change the profile-specific override that caused the collision. Fish never silently steals a mapped host key. Modal dialogs and the manual keep their own text/navigation keys.

For manual entry, Swing stroke syntax is accepted, such as ctrl TAB, ctrl shift TAB or ctrl alt K. The displayed menu accelerators update to the effective bindings.

## Profile-specific host keyboard mappings

Profiles > Keyboard retains per-profile host overrides in KEY = ACTION form, one per line. Examples are ctrl ENTER = FIELD_EXIT and shift F1 = PF13. Host action names come from the complete Host > Send host key list.

These are host mappings, not application commands. Ctrl+Alt remains reserved for app defaults in the profile parser; use the global Shortcuts list for application bindings or global host reassignment. Alt+Space and Alt+F4 are always window operations.

Profile-specific keys still apply unless their host action has been explicitly reassigned globally. Conflicting profile changes are rejected before saving. Global shortcuts are saved separately, so switching workstation profiles does not unexpectedly change pane navigation.

## Selection, copy and paste

Drag across the terminal to select a rectangular region. Shift+arrows extends keyboard selection. Right-click preserves the selection and opens Copy selection / screen and Paste into fields.

Edit offers the same actions. Defaults are Ctrl+Alt+Shift+C and Ctrl+Alt+Shift+V. The Context Menu key opens the popup when it is not mapped to a host action. Ctrl+C/V and Shift+F10 are not newly claimed by clipboard support.

Copy exports the selection, or the host screen when nothing is selected. Non-display data is omitted. Copy from an offline tile is unavailable.

Paste inserts through field-aware editing, not through an unsafe raw core paste path. It respects protected/numeric fields, code-page representability, input inhibition and a 32,768-character limit. Unsupported/control/unrepresentable text is rejected before editing a prefix. A field rejection stops remaining paste.

Tabs and newlines navigate without submitting Enter. Pasting never signs on or submits a transaction automatically. Review the pasted field data before pressing Enter yourself. Clipboard failures are reported in the pane footer, or as a temporary notice if status information is hidden; other applications can temporarily lock the Windows clipboard.

## Menus and action access

The Commands window is removed. Session, Edit, Panes, View, Host and Help, the compact narrow-window menu, terminal context menu and configurable shortcuts reuse the same action handlers. Actions return focus to the intended pane and disabled actions remain disabled.

Right-click a terminal for clipboard, Reset, connection and pane actions. Quick View contains Fit, Zoom and Appearance. Appearance starts with application theme, dependent host palette and text effect; these apply immediately and are saved. More settings groups Fonts, Pane information, Window and focus, Theme designer and preset/effect shortcuts. Panes groups occasional navigation/layout actions in submenus. Host keeps Reset/Attention/System Request close at hand, with field/screen actions and the complete Send host key list one level deeper. No old Commands shortcut is silently redirected to a host key.

## Application themes, OLED and focus

View > Appearance changes the complete application theme: Charcoal, Graphite, Midnight, Paper, OLED, OLED Amber, High Contrast, High Contrast Light, Lagoon, Afterglow and Eye Saver. Lagoon is cool teal; Afterglow is a warm violet creative preset. Eye Saver is a muted warm low-glare palette, not a medical promise. Menus, dialogs, fields, disabled/hover/pressed states and window controls share the chosen tokens.

Application themes are independent of host palettes. OLED themes use true-black terminal and chrome surfaces. High-contrast choices favor explicit boundaries and strong foreground contrast; Windows' own accessibility settings may also affect system-owned menus and installers.

Appearance > More settings > Window and focus controls whole-pane tint, active-pane outline and the separate default-on whole-window outline. Choose Apply to save, or Cancel to leave them unchanged. All enabled outlines use the shared one-physical-pixel Divider color; focusing a pane never changes its layout insets. Pane close is independent of every header information choice: with no header content it becomes an upper-right overlay, without reserving space or overlapping Reset.

Theme choices apply immediately; deeper settings use Apply/Cancel. Neither reconnects sessions or alters host dimensions.

## Theme designer and saved custom themes

Open View > Appearance > More settings > Theme designer. Load a built-in preset or a saved custom theme, then enter a name and precise #RRGGBB values in the color table. Its 60 semantic roles cover every owned UI color: surfaces, text, disabled/hover/pressed/focus/selection states, chrome and inactive controls, menus/tooltips, header/footer/statuses, tint/shared Divider, cursor/terminal selection, all sixteen host slots, six effect-gradient stops, glow and the logo gradient. Structure > Divider owns all structural edges and optional outlines; legacy LINE/OUTLINE entries migrate to the existing Divider color with a notice instead of leaving conflicting controls.

Preview applies the draft to the real application without saving. Cancel, Escape or closing the designer restores the applied theme. Save creates/updates a reusable custom theme; Save & apply also selects it. Saving edits to the currently active named theme updates that active theme. Load it again to edit; choose a different name to save a copy. Names are case-insensitively unique, replacements require confirmation when values differ, and up to 32 custom themes are supported.

Malformed colors and incomplete themes are rejected with an explicit message. Contrast warnings are advisory: valid RGB values are never silently changed or blocked. Keep text, selection, cursor and host colors distinct yourself when designing a low-contrast or experimental palette.

Selecting a built-in application theme preserves the saved custom-theme library. Built-in application themes continue to use each profile's host palette. An active custom theme owns all sixteen host colors and supersedes that built-in profile palette; optional active tint uses its separate color instead of host slot 0 for the active pane's background. Disable tint to use host slot 0 exactly. Profile effects, zoom and host attributes are unchanged. Custom gradient stops blend with each host foreground; reverse video and non-display behavior are preserved. Windows-owned system menus and installer surfaces remain controlled by Windows.

## Terminal fonts and local custom fonts

Open View > Appearance > More settings > Fonts. The family list discovers actually installed fonts with equal printable-ASCII advances, prioritizing Cascadia Mono/Code, Consolas, Lucida Console, Courier New and available DejaVu/Liberation Mono faces. Automatic picks an available fixed-width face. Proportional fonts are not advertised as reliable terminal choices.

Choose a family, or browse to a local monospaced TTF/OTF file. A custom file takes precedence; clear the path to use the family again. Check / preview font shows the actual regular face. Apply validates glyph coverage and that printable glyphs fit a cell for all current profiles' SBCS code pages. Unsupported font formats, proportional faces, missing files and missing/wide required glyphs are reported rather than silently accepted. Some OpenType formats may not be supported by Java's local font parser.

The font setting is workspace-wide and persists across restart. Font files remain on your machine, at the selected path; Fish does not upload, bundle or install them. Moving/deleting a saved font causes a visible startup warning and an announced temporary Java Monospaced fallback. The saved choice is preserved so you can restore the file or choose another font.

Glyphs remain positioned at exact fixed physical-pixel cell origins. Font changes refresh glyph/effect caches without reconnecting or changing the host's dimensions; fit and manual zoom still apply. UI/titlebar fonts remain the native regular UI font.

## Pane information and zero-space rows

View > Appearance > More settings > Pane information groups header/footer visibility with their dependent items: profile name, connection state, host, requested device, status/input message, host dimensions, cursor row:column and insert indicator. Disabled rows disable their item controls while retaining the choices. Outline and tint live separately in Window and focus. These preferences belong to the workspace, not a profile. Apply saves; Cancel leaves existing choices unchanged.

A disabled row, or a row with no actual text, reserves zero vertical space. Close/connect/add/swap actions remain available through menus, shortcuts and the terminal context menu. Alt+drag swaps from the terminal when no header is shown.

With no footer, a recoverable local input error shows a Reset corner overlay, not an empty band. It does not take input focus or submit Enter, and is never offered to unlock host-busy/disconnected states. Other errors remain visible in a temporary in-pane notice if status text is hidden.

## Host color palettes and text effects

The profile or Appearance dialog selects Phosphor, Cool, Warm, Amber, Ice, Orchid, High contrast, IBM vivid, Mint, Rose, OLED green or OLED amber. Each retains sixteen host color/attribute entries rather than collapsing everything into one decorative color.

Text effects are None, Soft glow, Prism and Flux (animated). None is the default, using crisp final-resolution glyphs. Effects are opt-in and not a workaround for host compatibility. Neon was removed; saved Neon values migrate to None with a load notice.

The top-left fish is a secret animated-text toggle for the active preset: Flux (animated) becomes None; any other effect becomes Flux (animated). A single mouse click waits for the system double-click interval; double-clicks and drags do not toggle it. Tab to the icon and press Space for immediate keyboard activation. Keyboard focus has a subtle underline; mouse clicks leave no focus box. Like Appearance, it saves the preset and refreshes other panes using that same preset. The logo no longer performs a temporary shimmer.

During connection setup a rotating ring replaces the middle preset controls or terminal view. It is progress, not a claim that sign-on has succeeded; connection status and failures remain in the footer. The pane X can cancel this pending connection. X is hidden only when the sole remaining pane is disconnected and showing the preset selector; keyboard/menu disconnect commands remain available.

Flux uses smooth flowing multicolor gradients on neutral text; chromatic host text stays within its hue family, and reverse video retains its exact colors. A shared bounded 20fps timer is suspended for hidden, offline, minimized or disposed views. Choose None to stop effects.

Host hidden fields remain hidden. Selection, cursor, underline, blink and reverse distinctions stay meaningful. Strong effects may not suit every display or visual preference; start with None for maximum clarity.

## Fit, zoom and DPI

Fit host screen centers the entire host presentation space inside the pane. Zoom in/out switches to manual zoom, from 50% to 300%, with scrolling when needed. The default shortcuts are Ctrl+Alt+0, Ctrl+Alt+= and Ctrl+Alt+-.

Glyphs are generated at final physical pixel resolution with fixed-cell positioning; default text is not a bilinear-upscaled bitmap. Windows scaling and monitor transitions may change the fitted physical font size, but never the host's row/column count.

At very small pane sizes, the screen must be small to fit. Increase the window, use fewer panes or use manual zoom if readability is more important than seeing the entire host screen.

## Layout persistence and multiple instances

Closing Fish saves window size, pane arrangement, profile selections, last-used preset, application/custom themes, font reference, shortcuts, outline/tint and pane-information preferences. Next launch is offline; saved layouts do not imply an auto-connect.

Panes > Layout > Save layout to profile and Restore profile layout preserve a profile-specific arrangement. The same submenu holds divider adjustment. Restoring/replacing a layout can close sessions removed from it. Old arbitrary layouts are normalized into bounded slots with a notice. Layouts over eight panes are rejected rather than silently truncated.

The workspace is locked against concurrent writers. Another independent instance needs another directory: Fish.exe --data-dir "C:\Users\you\Fish-workspace-two". Do not point two copies at the same workspace file.

New state is normally %APPDATA%\Fish\workspace.xml. If legacy %APPDATA%\Pane5250\workspace.xml exists, Fish keeps using that directory and its lock so profiles and legacy credential targets survive the rename.

Corrupt or unsupported state is preserved and produces an error, not silently replaced. Back up the entire workspace directory before manual edits. Passwords are stored separately in Windows Credential Manager and need separate handling.

## Demo and diagnostics

Fish.exe --demo starts a loopback-only synthetic host and a separate temporary workspace. It demonstrates independent sockets, field input, host keys and screen-size switching, not IBM i authentication.

In the demo, F5 requests 27x132 and F6 requests 24x80. Enter submits fields to the synthetic server. The footer/banner identifies the session as local synthetic data.

Fish.exe --help prints command-line options and exits. Advanced builders can run build.ps1 Smoke for focused hidden-window checks without physical input or shared clipboard access. Full foreground tests require an idle desktop and fresh consent.

When reporting a problem, include Fish version, Windows version, display scale, the action/profile involved and the exact footer message. Remove passwords, personal host data and credentials before sharing traces or screenshots.

## Troubleshooting

No connection: check Host/Port, network/VPN/firewall, IBM i Telnet service and permissions. A refused/timed-out connection is different from a sign-on rejection.

Named device fails: retry with Device blank to isolate device allocation. Check the requested name and host-side device policy; retain the exact reported allocation rejection for your administrator. Fish must not silently substitute another name.

Input appears stuck: inspect the footer. Use Reset only for a recoverable local error, wait for host busy, or diagnose a disconnected network. Repeated Enter is not a reset strategy.

Clipboard unavailable: another program may own the clipboard temporarily; retry. Protected/numeric fields or code-page rejection require correcting the input, not bypassing validation.

Snap layouts absent: confirm Windows 11/Snap settings, hover on maximize, or use Win+Z/Win+arrows. Windows 10 does not have the Windows 11 flyout. Very small display/work-area layouts may be unavailable.

The executable will not start after moving it: restore the complete Fish folder including app and runtime. A native integration load error requires the matching fish-window.dll and the included runtime.

Workspace already open: close the other instance or choose a different --data-dir. A lock file by itself is not proof of an active process; the OS-held lock is authoritative.

Build download fails: check internet/proxy policy and retry. A checksum mismatch is a hard failure; no failed archive is executed. Do not disable checksum checks. Keep your source extraction in a writable user folder.

Installer fails under organizational policy: use the portable ZIP if permitted, or consult IT. Do not elevate the build script or bypass policy to force installation.

## Compatibility, licensing and release status

Fish is an experimental TN5250J-based implementation, not a demonstrated full ACS-compatible replacement. The only terminal type is IBM-3477-FC. Only SBCS code pages in the profile list are offered; DBCS is not implemented.

Known gaps include enhanced WTD/WEA/structured-field behavior and cryptographic automatic sign-on. Unsupported engine behavior is reported/stops the affected session instead of inventing a successful response. Real-host acceptance requires an IBM i, valid test credentials, ACS access and representative captures that have not been supplied.

Read COMPATIBILITY.md for exact locally demonstrated behavior and the remaining acceptance checklist. The streamlining preview is not a claim of production readiness or successful real-host authentication.

Fish and its pinned TN5250J core are GPL-2.0-or-later, without warranty. LICENSE, NOTICE, the core license and complete corresponding application/core sources accompany the package. The Windows frame bridge is Fish source, not a proprietary runtime dependency.

## Index and finding help

Use the manual's Find field to search topic titles and full text. Select an index entry to jump to its section; matching text is highlighted. Ctrl+F focuses Find. Clear the query to restore every topic; Escape closes the manual.

For signing on, start with Getting Started. For navigation keys, see Global shortcut overrides. For appearance, see Application themes, Host color palettes and Fit. For install/build, see Installation and Building. For errors, see Troubleshooting and Compatibility.
