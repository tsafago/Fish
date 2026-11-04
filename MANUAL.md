# Fish manual

Fish 0.7.1 - unsigned experimental maintenance/Kerberos prerelease.

Known remaining defect: the whole-window perimeter can disappear after maximize/restore. Real-monitor checks for that path still fail at 100% and 150% scaling. This is not a complete border/ACS-compatibility fix.
Intermittent resize flicker was not reproduced in the bounded baseline and current-build monitor checks and remains an open hardware observation.
This manual describes the implemented application, not an assurance of full ACS compatibility.
Open Help > Full manual / Getting Started for the searchable topic index.

## Getting Started

You need the IBM i's address and permission to use its Telnet service. Windows sign-in needs configured Kerberos/NAS/EIM; password sign-in needs an IBM i account. Your administrator supplies the deployment details.

1. Run Fish.exe. Keep the complete Fish folder together if using the portable download.
2. Open Session > Profiles. Select Local IBM i, or choose New.
3. Give the profile a useful name. Check Host (new profiles start at 10.1.2.3). Leave Port at 23 unless your administrator gave you another port.
4. Fresh installs already select Local IBM i at 10.1.2.3 with Windows (Kerberos) sign-in. No password is needed; see Windows sign-in below for administrator prerequisites. For a password profile choose Password / manual, enter your username and password, and leave the recommended options checked to sign in automatically and save the password in Windows Credential Manager. Blank keeps a saved password. Turn automatic sign-in off for manual login. Device can stay empty; code page defaults to 37.
5. Choose Save & connect. A TCP connection is not the same as a successful IBM i sign-on.
6. If using manual sign-in, type in the IBM i's own sign-on fields and press Enter. The host controls their actual order. Password characters normally remain invisible. Automatic sign-in requires host support for RFC 4777 plain sign-on; a saved password or TCP connection is not proof of sign-on.
7. Work with the displayed IBM i application. Enter submits to the host. F1-F12 are host function keys; Shift+F1-F12 sends F13-F24.
8. Use the pane's X to close that connection. The final pane stays as an empty tile. Closing Fish disconnects everything immediately, without confirmation.

Plain TCP is not encrypted. Use a trusted network approved by your administrator. Fish has not yet been accepted against a real IBM i or ACS session; see Compatibility before production use.

If connection fails, check the hostname, port, VPN/network and IBM i Telnet service first. A blank Device is the recommended starting point. Do not enter your password in a visible application field.

Fresh workspaces show a modeless guided tutorial once, with Next, Back and Skip.
Short hints move beside the actual menus, profile fields and pane controls.
Use Show menu to open the real menu and follow its highlighted action. The guide
stays usable while editing a profile, but never creates a profile or
connects without your action. Apply/Done can save a profile with no connection;
host and credentials may remain blank. Help > Getting started tutorial replays it
without duplicating profiles, changing settings or interrupting sessions.
Legacy upgrades do not force the guide over existing work. Closing the app midway
does not nag on the next launch; replay remains available.

## Installation: ready-built downloads

Fish targets Windows 10/11 x64. No separate Java installation is required.

The public [v0.7.1 prerelease](https://github.com/tsafago/Fish/releases/tag/v0.7.1) includes Fish-0.7.1-setup.exe, the portable ZIP, complete matching source/licenses and SHA256SUMS.txt. No GitHub account is needed. Keep one release's assets/checksums together. The compact dark native setup installs for the current Windows user, creates Start menu/desktop shortcuts and bundles the runtime. Its default location is your local application-data Fish directory; Change selects an empty local folder or an existing Fish installation. No administrator installation is requested.

Alternatively, extract Fish-0.7.1-windows-x64.zip to a writable folder and run Fish\Fish.exe. Do not move Fish.exe out of that folder: its app and runtime directories are required.

The binaries are unsigned. Windows SmartScreen or an organization's policy may warn or block them. Verify the SHA256SUMS.txt download against the artifact if required by your administrator. Do not override your organization's security policy.

The installer does not need your IBM i credentials. Profiles are created inside Fish after installation.

## Building an executable and installer

Download Fish-source.zip and extract it to a writable folder. Open that folder and double-click Setup-Fish.cmd. Leave the console open until it reports Ready.

The script obtains checksum-pinned portable JDK 21, Zig and WiX tools inside .tools, builds Fish and generates both its portable application and current-user installer. It does not install a system JDK, modify system PATH, require Git for the complete source archive, or require administrator installation.

The first build needs internet access and approximately 2 GB of free space for tools, caches, intermediate files and packages. Windows PowerShell 5.1 and Windows' curl are used. The pinned source/build tools are listed in tools.psd1.

Default build outputs are dist\Fish\Fish.exe, dist\Fish-0.7.1-setup.exe, dist\Fish-0.7.1-windows-x64.zip and dist\Fish-source.zip. This side-by-side local delivery uses dist\Fish-0.7.1\Fish\Fish.exe. Close a running copy before rebuilding its directory.

To build and open the installer in one step, run Setup-Fish.cmd -Install. For advanced use, build.ps1 accepts Build, Maintenance, Smoke, Check, Test, Package, Installer, Run and Demo. Maintenance uses isolated data and never-shown windows for profile/update/reset checks; it does not install, authenticate or use the shared clipboard. The Test task uses the physical desktop; do not run it while someone is working.

Downloaded build tools are not application runtime dependencies. Once packaged, Fish runs with its included Java runtime and native Windows frame bridge.

For a side-by-side build while an older image is running, use
`.\build.ps1 Package -ImageDirectory dist\Fish-0.7.1\Fish`, then
`.\build.ps1 Installer -ImageDirectory dist\Fish-0.7.1\Fish`. The final directory
must be named Fish. Do not move or replace a running app's files.

## Upgrading, uninstalling and backups

Close running Fish windows before an upgrade. Install the new version for the same Windows user. The stable installer upgrade identity allows later releases to replace the application without changing the profile directory.

Before first launching the new version, copy the existing workspace.xml outside
the application directory. Normally it is in %APPDATA%\Fish, or the retained
legacy %APPDATA%\Pane5250 directory; a custom --data-dir takes precedence.
The next save writes configuration version 11. Older binaries, including 0.7.0, cannot read it:
keep the pre-upgrade backup for downgrade, not just a version-11 export.
Workspace/config backups contain safe credential references, not OS passwords
or Kerberos tickets; they are not a backup of Windows Credential Manager.

Uninstall through Windows Settings > Apps. Uninstalling the application does not intentionally delete workspace profiles or Windows Credential Manager entries. Back up workspace.xml before manual changes or a downgrade.

Older Fish/Pane5250 workspaces migrate to version 11 on load, retaining profiles, order, themes, fonts, key overrides, outline/tint and information choices. Existing version-10 Kerberos metadata is preserved; pre-10 profiles remain Password / manual with their exact auto/remember choices. The Kerberos default is seeded only for absent/reset state. Version-7 explicit outline-off choices remain off; older 5/6 data retains its documented one-time outline-on migration. Absent built-in key defaults from before version 8 change to the new conventional/IBM-style assignments. Explicit saved overrides are preserved; conflicts retain older app chords or disable only a conflicting newly introduced host default, with informational notes. An old format could not distinguish a manually selected default from an absent entry; only saved explicit entries can be retained as such. New explicit-default entries retain their provenance. Unresolved conflicts fail clearly. Pre-version-8 tutorials start skipped; later tutorial/login choices remain unchanged. Startup connection starts off on pre-9 upgrade; explicit later choices remain unchanged. Format 11 adds startup update checks on, preview-patch channel and no hidden version. Malformed state is preserved, never replaced with defaults; do not relabel a new format to downgrade.

The removed Neon effect becomes None with an explicit load notice; the rest of the profile is preserved. The removed Commands action leaves no shortcut-editor entry. Its old default Ctrl+Alt+K and any saved custom chord are reserved as no-ops until explicitly assigned to another action, preventing accidental host input.

## Checking for updates

Help > Check for updates checks the public tsafago/Fish GitHub releases over HTTPS without gh, a token or GitHub login. The lookup is asynchronous and bounded to the 30 most recent releases. It compares numeric versions, rejects drafts/malformed metadata/wrong-platform artifacts and never reinstalls or downgrades.

A nonblocking startup check is on by default. Offline/rate-limit failures stay quiet; manual checks show errors and Check again. The small startup notice offers Update, X and Hide version. X only dismisses this notice. Hide remembers that exact version; future eligible versions can notify again, and manual checks still show hidden versions.

Help > Application settings (also Appearance > More settings > Application settings) controls startup checks and channel. Preview patches includes experimental releases within the same major/minor, such as 0.7.x. Stable releases excludes previews and permits newer stable minor versions within the same major. No channel silently upgrades to a different major or a newer experimental minor. Show hidden updates again clears the exact-version ignore choice.

Update opens the version, release notes and repository link. One explicit confirmation authorizes download, saving and ending this window's sessions before setup. A download can be cancelled while Fish remains open. The handoff saves the workspace first; save/launch failure leaves Fish open with an error and retry. Setup waits for this Fish process to exit and refuses to replace an installation still in use; it never kills processes. Other Fish windows must be closed manually if they use that installation.

Only the exact release's Windows x64 installer and SHA256SUMS are downloaded. Fixed repository/CDN origins, HTTPS, size limits, published GitHub SHA-256 digests and checksum agreement are required. Windows validates any present Authenticode signature and the staged file retains its Internet-origin mark. Current builds are unsigned: hashes provide integrity, not a trusted publisher. Windows/IT policy may block setup; Fish does not bypass it. No workspace data, tickets or credentials are sent.

Installed mode updates the existing registered per-user installation. Portable mode deliberately does not replace portable files: setup installs/updates a per-user copy and opens that installed copy using the same workspace directory. The old portable directory remains unchanged. Source/java launches cannot use the packaged self-update launcher; use the downloaded installer manually. Fish 0.7.0 and earlier require a manual first upgrade.

The native setup reports the actual Windows Installer result and verifies the installed version before offering Open Fish. A required reboot is reported instead of relaunching. Failure is not reported as an update. The verified downloaded setup may remain in the OS temporary directory for recovery; it contains no user state. Live setup/upgrade/relaunch acceptance is not established by synthetic checks.

## Factory defaults

Open Application settings and choose Revert to factory defaults. Confirmation explains the removal and active-session shutdown; a single click never resets Fish. Cancel keeps everything.

Confirming atomically replaces only this active workspace's Fish settings, then exits without an old-state autosave. The next launch has the selected Kerberos 10.1.2.3:23 profile, offline until Connect, default appearance/update choices and the first-run guide. Saved profiles/layouts, custom themes, font references, key choices and hidden update version are cleared. At least one pane remains.

Windows saved passwords, font files, installation files, unknown files and other/custom workspaces are retained. Removed profile IDs are not reused, so their retained OS passwords are not automatically inherited by new profiles. Remove obsolete Fish credential targets separately through Windows Credential Manager if desired. No backup is silently made: export/copy workspace.xml first if you need recovery. A fresh workspace.xml remains even in a legacy Pane5250 directory, preventing stale fallback settings from returning.

## Workspace and panes

Fish supports one to eight independent sessions. Each connected pane has its own TCP socket, host screen and input state. Focus changes, pane moves and window resizing do not reconnect sessions or change host dimensions.

The compact + Pane control adds the next tile. Layouts are deterministic: one full pane; two side-by-side; a full-height left pane and two stacked right panes; four quadrants; then top/bottom rows of 3/2, 3/3, 4/3 and 4/4. Adding is disabled at eight.

Drag a pane header onto another pane to swap the sessions. The full target is previewed; dropping never subdivides a tile. Panes > Swap panes provides the keyboard equivalent. Drag a hairline divider, or use Decrease divider / Increase divider.

If headers are hidden, drag near the pane's top at the X overlay's height to swap. This invisible strip takes no layout space and excludes close, Reset and other controls. Selection and field input below it remain normal; Alt+drag there is an alternative swap gesture. The terminal context menu and Panes menu retain pane actions.

Pane X disconnects and removes its tile immediately; other panes reflow and stay connected. The last pane disconnects but remains as an empty tile. Closing the window is immediate and does not ask about unsent host edits.

The empty pane contains a preset selector and the arrow Connect button directly to its right. New tiles preselect the last-used preset, but never auto-connect. Existing offline tiles remember their own preset.

Session > Connect panes on startup optionally reconnects restored panes when you
open Fish. It is off by default. Each pane uses its own profile, once per launch.
Automatic sign-in needs a saved password; incomplete profiles or missing saved
passwords stay offline with a notice. Click Connect to finish signing in.
Manual sign-in profiles connect to the host's sign-on screen without password prompts.
Adding a pane, applying settings or replaying the guide never starts a connection.

## Focus and switching panes

The selected pane has an optional physical-pixel outline. View > Appearance > More settings > Window and focus controls it independently of active tint and the whole-window outline. Disabling the pane outline removes its border/insets, not just its color. Activating a different pane does not change spacing. Its header, terminal and footer share one surface; keyboard focus and the terminal cursor remain available with both outline and tint off. OLED themes retain a true-black terminal background.

Panes > Navigation > Next pane and Previous pane cycle focus. The defaults are Ctrl+Tab and Ctrl+Shift+Tab, including when the terminal or offline preset selector has focus. Edit > Keyboard > Shortcuts can change them.

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

Select a saved profile and use Up/Down, or Ctrl+Up/Ctrl+Down while the list has focus, to change its order. Pane selectors, switching and configuration exports use the same persistent order and stable IDs. Reorder applies immediately, independently of unapplied editor text.

Delete (or Delete while the list has focus) asks for confirmation. Panes using that profile disconnect and select the first remaining profile; other live sessions stay untouched. Saved layouts/last selection are repaired without removing panes. Windows saved passwords are retained, not erased. A profile still supplying another profile's login cannot be deleted: first change those dependents to Independent login or another eligible source. Pending login work must finish/cancel first. Keep at least one profile; create a replacement before deleting the last. Deleted defaults are not recreated on every launch.

A profile stores name, host, port, optional device name, username, authentication preferences, SBCS code page, host palette, text effect, zoom, host-key overrides and a saved layout. Application/custom themes, terminal font, global shortcuts, outline, tint, pane-information preferences and last-used preset belong to the workspace.

Use login from... appears when another independent profile has a username and Save password enabled. Explicitly choosing it authorizes that login for this profile's target host. It inherits username/sign-in preferences and looks up the source OS credential without copying the password. Name changes remain safe through stable IDs. Missing OS passwords prompt on Connect. Select Independent login to stop sharing; enter/save an independent password if desired. Duplicates become independent with remembering off. Chains/cycles/missing sources are rejected, and a source still referenced cannot be deleted or made ineligible. Login edits wait for pending credential/connection work and affect the next connection; saving/canceling a target never erases its source credential.

Apply stores edits without connecting and leaves the editor open. Done stores pending edits and closes without connecting. Save & connect selects the profile and connects. Cancel discards only unapplied edits after the profile editor's unsaved-edit prompt; earlier Apply actions remain saved. Inputs are unavailable while an asynchronous credential/save operation is pending. That prompt is separate from pane/window closing, which has no confirmation.

Changing connection settings disconnects affected sessions; display-only changes refresh them without reconnecting. Session > Switch profile offers a quick selector. Reconnect starts a new TCP session; unsent changes and the old host presentation are not preserved.

## Host, port and device name

Enter a hostname or IP address, not a URL. Port defaults to 23 and may be changed to 1-65535. The host must accept plain TN5250.

Only IBM-3477-FC is negotiated. Device names are optional, at most ten characters, start with a letter or IBM i national character (#, $ or @), and may otherwise contain digits and underscores. Fish normalizes them to uppercase.

Blank Device lets the IBM i allocate a workstation. An explicit name is requested through negotiated NEW-ENVIRON, not silently discarded or replaced. A collision, host configuration, disabled device creation or permission problem may prevent allocation.

Startup confirmations show an allocated/rejected device when the host supplies one. A host's rejection details are needed to diagnose remaining real-system cases; a successful local synthetic fixture does not establish that IBM i's acceptance.

## Manual sign-on and remembered credentials

Manual sign-on uses the host's own screen. Fish does not guess sign-on field positions or invent a signed-on state. Session > Sign-on helpers > Insert profile username inserts into the current field using normal validation.

Enable Remember password and save a password in the profile editor to use Windows Credential Manager. Session > Sign-on helpers > Insert remembered password is allowed only in an unlocked non-display input field with enough remaining capacity. It cannot spill into a visible field.

A blank password field in the editor preserves an existing remembered secret; it is not an indication that no password exists. Disabling Remember while staying in password mode removes the remembered entry. Changing to Kerberos does not delete the previous OS password; changing back requires explicitly enabling remembering again. Duplicating a profile does not copy its remembered password.

Credential targets retain the legacy Pane5250 prefix so existing secrets remain reachable after rebranding. Passwords are not stored in profile XML or process arguments. Clipboard export omits non-display fields.

## Automatic sign-on and security

Automatic sign-on is optional and requires a profile username. The current implemented mechanism uses RFC 4777 NEW-ENVIRON USER, an empty IBMRSEED and a negotiated plain-text IBMSUBSPW password.

Cryptographic password substitution is not implemented. TLS is out of scope. Plain TCP transmits password sign-on credentials and terminal data without encryption; use only a trusted approved network. If the host requires an unsupported authentication mechanism, Fish must not claim success.

Do not treat Connected/TCP as proof of authentication. The IBM i host decides whether sign-on succeeded. JVM/PowerShell strings and host screen memory are not guaranteed to be securely erased.

## Windows sign-in (Kerberos)

Fresh installations select Local IBM i, 10.1.2.3, port 23, Windows (Kerberos).
Nothing connects on launch: click Connect when ready. Existing installations are
not converted and a deleted default is never added back to a saved workspace.

Kerberos uses your signed-in Windows identity, not a Fish username or saved
password. In Profiles, choose Windows (Kerberos). Leave Kerberos server blank
when Host is a fully qualified DNS name, or when the IP has a reverse DNS name
that resolves back to that IP. Otherwise enter the IBM i's full DNS name in
Kerberos server. This changes only the service identity, not the TCP destination.
Fish requests `krbsvr400/{lowercase DNS name}`; Windows chooses the realm.
Fish never trusts an SPN supplied by the Telnet host or guesses a realm.

Your administrator must configure domain/KDC reachability, synchronized clocks,
the IBM i Network Authentication Service/keytab and the matching registered
krbsvr400 service principal, EIM mapping to one enabled IBM i user profile, and
remote sign-on policy permitting bypass (IBM documents QRMTSIGN *VERIFY).
Fish does not install/change any of those policies. An IP alone cannot supply
an arbitrary deployment's service identity. If DNS discovery fails, ask for the
registered IBM i DNS/service name.

Windows SSPI acquires an outbound **Kerberos** credential handle for the current
logon, then creates the complete initial GSS service token. Fish sends that
opaque binary token as RFC 4777 `IBMTICKET`, with RFC 1572 control-byte escaping
and Telnet IAC quoting, maximum 16,384 raw bytes. It requests `IBMSENDCONFREC=YES`
and reports Kerberos-specific IBM i NAS/EIM rejection codes. A host refusing
NEW-ENVIRON/ticket negotiation or explicitly refusing automatic sign-on ends the
attempt; there is no NTLM, password or screen-field injection fallback.

Ticket/DNS work stays off the UI thread. Cancel invalidates the attempt; a late
token is wiped and never connects. A 20-second UI deadline abandons a slow
attempt. Windows controls any remaining KDC call timeout; a pane cannot launch
another lookup until that call returns and its handles are released. Ticket
buffers/queued frames are wiped on completion/close; tickets are never logged,
exported or saved. Password helpers and shared-password sources are disabled
for Kerberos. No Java installation, ticket-cache extraction or registry
weakening is needed.

This is one-way initial-token sign-on, without requesting delegation or claiming
mutual authentication. Kerberos does not encrypt the subsequent plain-TCP
terminal traffic. A created ticket, I902 device startup or Connected/TCP is not
proof that the application is signed in; inspect the host result with your
administrator. Synthetic verification is not Windows-domain/IBM i acceptance.

## Host screens, fields and cursor

The host controls its presentation space: 24 rows by 80 columns or 27 rows by 132 columns. Pane geometry and zoom do not negotiate a new size.

Type only in unprotected fields. Tab and Shift+Tab move between fields; click a valid screen position to move the cursor. Numeric, signed, mandatory and other supported validations are enforced by the core. Protected positions, unsupported characters or field constraints can reject input.

Enter submits according to the host's current read/input mode. Field Exit is distinct from Enter and is not a host transaction shortcut. Newline and roll actions also have distinct host meanings.

Host colors, reverse video, underline, blink, separators and non-display attributes remain meaningful across palettes and effects. Non-display text stays hidden rather than being made visible by a theme.

## Input inhibition and Reset

The optional footer reports connection, input state, cursor and host dimensions. A contextual Reset button appears for a recoverable local input error. Without a footer it overlays a corner opposite the cursor without adding spacing or taking focus. Ctrl+R is explicit Reset. During this local error only, the first physical Enter (including keypad Enter) performs Reset alone and emits no host transaction. The next normal Enter resumes its usual meaning. Escape sends Attention.

Reset does not silently submit Enter and must not unlock an unrelated host-busy condition. If the host has inhibited input while processing, wait for the host. A disconnected network cannot be repaired by Reset; reconnect after diagnosing the connection.

Attention and System Request have separate host meanings and may be appropriate for a blocked application. Consult your IBM i administrator before interrupting work or ending a job.

## Host-function keyboard reference

Defaults: F1-F12 send F1-F12; Shift+F1-F12 send F13-F24. Enter sends Enter. Keypad Enter or Ctrl+Enter sends Field Exit. Keypad + and - send Field+ and Field-.

Escape sends Attention; Shift+Escape sends System Request; Ctrl+R sends Reset. Pause or Ctrl+Backspace sends Clear; Alt+F1 or Ctrl+H sends Help. Shift+Insert sends Dup and Ctrl+Pause sends Host Print.

The IBM Personal Communications 5250 default-key reference supports these Attention/System Request, Clear, Help, Dup, Host Print, F-key and keypad assignments: https://www.ibm.com/docs/en/personal-communications/15.0.0?topic=assignments-default-key-functions-5250-layout . This is not a claim that every regional ACS layout is identical. Fish deliberately retains normal Enter for Enter, keypad/Ctrl+Enter for Field Exit, End for end-of-field and Ctrl+End for Erase EOF. Ctrl+R avoids modifier-only Reset firing at the start of conventional Ctrl shortcuts. Unsupported IBM text-assist actions are not guessed.

Page Up/Down send roll keys. Ctrl+Page Up/Down send horizontal roll actions. Tab/Shift+Tab move fields. Arrows move the cursor. Home, End, Insert, Delete, Backspace and Ctrl+End retain the core's corresponding editing actions.

Host > Send host key offers the complete supported action list. Edit > Keyboard > Shortcuts lists those host actions and the application actions. Disabled actions remain disabled; sending an unavailable action never creates a fake host response.

## Global shortcut overrides

Edit > Keyboard > Shortcuts lists every registered Fish action, including window/menu commands, profile actions, pane focus, clipboard, appearance and all host actions. The same list is reachable from Profiles > Keyboard.

Find an action, select its row and use Record shortcut. Press the desired keys and choose Use shortcut. Unbind clears its binding; Restore default restores the selected action. Apply persists the mapping and stays open; Done persists pending changes and closes. Cancel keeps the last applied mapping.

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

Edit offers the same actions. Defaults are Ctrl+C and Ctrl+V; text fields/dialogs retain normal native editing. Ctrl+Insert copies; Ctrl+Shift+Insert and Shift+PageDown paste while their default action is unchanged and no custom binding needs that chord. The Context Menu key opens the popup when not mapped to a host action.

Copy exports the selection, or the host screen when nothing is selected. Non-display data is omitted. Copy from an offline tile is unavailable.

Paste inserts through field-aware editing, not through an unsafe raw core paste path. It respects protected/numeric fields, code-page representability, input inhibition and a 32,768-character limit. Unsupported/control/unrepresentable text is rejected before editing a prefix. A field rejection stops remaining paste.

Tabs and newlines navigate without submitting Enter. Pasting never signs on or submits a transaction automatically. Review the pasted field data before pressing Enter yourself. Clipboard failures are reported in the pane footer, or as a temporary notice if status information is hidden; other applications can temporarily lock the Windows clipboard.

## Menus and action access

The Commands window is removed. Session, Edit, Panes, View, Host and Help, the compact narrow-window menu, terminal context menu and configurable shortcuts reuse the same action handlers. Actions return focus to the intended pane and disabled actions remain disabled.

Right-click a terminal for clipboard, Reset, connection and pane actions. Quick View contains Fit, Zoom and Appearance. Appearance starts with application theme, dependent host palette and text effect; these apply immediately and are saved. More settings groups Fonts, Pane information, Window and focus, Theme designer and preset/effect shortcuts. Panes groups occasional navigation/layout actions in submenus. Host keeps Reset/Attention/System Request close at hand, with field/screen actions and the complete Send host key list one level deeper. No old Commands shortcut is silently redirected to a host key.

## Application themes, OLED and focus

View > Appearance changes the complete application theme: Charcoal, Graphite, Midnight, Paper, OLED, OLED Amber, High Contrast, High Contrast Light, Lagoon, Afterglow and Eye Saver. Lagoon is cool teal; Afterglow is a warm violet creative preset. Eye Saver is a muted warm low-glare palette, not a medical promise. Menus, dialogs, fields, disabled/hover/pressed states and window controls share the chosen tokens.

Application themes are independent of host palettes. OLED themes use true-black terminal and chrome surfaces. High-contrast choices favor explicit boundaries and strong foreground contrast; Windows' own accessibility settings may also affect system-owned menus and installers.

Appearance > More settings > Window and focus controls whole-pane tint, active-pane outline and the separate default-on whole-window outline. Apply saves and stays open; Done saves pending edits and closes; Cancel retains the most recent Apply. All enabled outlines use the shared one-physical-pixel Divider color; shared dividers/titlebar edges are never duplicated by a pane outline. Focusing a pane never changes its layout insets. Pane close is independent of every header information choice: with no header content it becomes an upper-right overlay, without reserving space or overlapping Reset.

Theme choices apply immediately; deeper editable settings use Apply/Done/Cancel. Neither reconnects sessions or alters host dimensions.

## Theme designer and saved custom themes

Open View > Appearance > More settings > Theme designer. Load a built-in preset or a saved custom theme, then enter a name and precise #RRGGBB values in the color table. Its 60 semantic roles cover every owned UI color: surfaces, text, disabled/hover/pressed/focus/selection states, chrome and inactive controls, menus/tooltips, header/footer/statuses, tint/shared Divider, cursor/terminal selection, all sixteen host slots, six effect-gradient stops, glow and the logo gradient. Structure > Divider owns all structural edges and optional outlines; legacy LINE/OUTLINE entries migrate to the existing Divider color with a notice instead of leaving conflicting controls.

Preview applies the draft to the real application without saving. Cancel, Escape or closing the designer restores the most recently applied theme. Save creates/updates a reusable custom theme; Apply also selects it and stays open. Done saves/applies pending edits and closes. Saving edits to the currently active named theme updates that active theme. Load it again to edit; choose a different name to save a copy. Names are case-insensitively unique, replacements require confirmation when values differ, and up to 32 custom themes are supported.

Malformed colors and incomplete themes are rejected with an explicit message. Contrast warnings are advisory: valid RGB values are never silently changed or blocked. Keep text, selection, cursor and host colors distinct yourself when designing a low-contrast or experimental palette.

Selecting a built-in application theme preserves the saved custom-theme library. Built-in application themes continue to use each profile's host palette. An active custom theme owns all sixteen host colors and supersedes that built-in profile palette; optional active tint uses its separate color instead of host slot 0 for the active pane's background. Disable tint to use host slot 0 exactly. Profile effects, zoom and host attributes are unchanged. Custom gradient stops blend with each host foreground; reverse video and non-display behavior are preserved. Windows-owned system menus and installer surfaces remain controlled by Windows.

## Terminal fonts and local custom fonts

Open View > Appearance > More settings > Fonts. The family list discovers actually installed fonts with equal printable-ASCII advances, prioritizing Cascadia Mono/Code, Consolas, Lucida Console, Courier New and available DejaVu/Liberation Mono faces. Automatic picks an available fixed-width face. Proportional fonts are not advertised as reliable terminal choices.

Choose a family, or browse to a local monospaced TTF/OTF file. A custom file takes precedence; clear the path to use the family again. Check / preview font shows the actual regular face. Apply validates glyph coverage and that printable glyphs fit a cell for all current profiles' SBCS code pages. Unsupported font formats, proportional faces, missing files and missing/wide required glyphs are reported rather than silently accepted. Some OpenType formats may not be supported by Java's local font parser.

The font setting is workspace-wide and persists across restart. Font files remain on your machine, at the selected path; Fish does not upload, bundle or install them. Moving/deleting a saved font causes a visible startup warning and an announced temporary Java Monospaced fallback. The saved choice is preserved so you can restore the file or choose another font.

Glyphs remain positioned at exact fixed physical-pixel cell origins. Font changes refresh glyph/effect caches without reconnecting or changing the host's dimensions; fit and manual zoom still apply. UI/titlebar fonts remain the native regular UI font.

## Pane information and zero-space rows

View > Appearance > More settings > Pane information groups header/footer visibility with their dependent items: profile name, connection state, host, requested device, status/input message, host dimensions, cursor row:column and insert indicator. Disabled rows disable their item controls while retaining the choices. Outline and tint live separately in Window and focus. These preferences belong to the workspace, not a profile. Apply saves and stays open; Done saves pending edits and closes; Cancel keeps the last Apply.

A disabled row, or a row with no actual text, reserves zero vertical space. Close/connect/add/swap actions remain available through menus, shortcuts and the terminal context menu. The invisible pane-top strip and Alt+drag swap from a terminal when no header is shown.

With no footer, a recoverable local input error shows a Reset corner overlay, not an empty band. It does not take input focus or submit Enter, and is never offered to unlock host-busy/disconnected states. Other errors remain visible in a temporary in-pane notice if status text is hidden.

## Host color palettes and text effects

The profile or Appearance dialog selects Phosphor, Cool, Warm, Amber, Ice, Orchid, High contrast, IBM vivid, Mint, Rose, OLED green or OLED amber. Each retains sixteen host color/attribute entries rather than collapsing everything into one decorative color.

Text effects are None, Soft glow, Prism and Flux (animated). None is the default, using crisp final-resolution glyphs. Effects are opt-in and not a workaround for host compatibility. Neon was removed; saved Neon values migrate to None with a load notice.

The top-left fish is an animated-text toggle for the active preset: Flux (animated) becomes None; any other effect becomes Flux (animated). A mouse release switches immediately, without a double-click timer; a second activation in a double-click restores the preceding effect. Dragging the logo does not activate it. Tab to the icon and press Space for keyboard activation. Keyboard focus has a subtle underline; mouse clicks leave no focus box. Like Appearance, it saves the preset and refreshes other panes using that same preset. None/Flux use the same regular-font metrics and per-channel LCD glyph mask, changing only color. The logo no longer performs a temporary shimmer.

During connection setup a rotating ring replaces the middle preset controls or terminal view. It is progress, not a claim that sign-on has succeeded; connection status and failures remain in the footer. The pane X can cancel this pending connection. X is hidden only when the sole remaining pane is disconnected and showing the preset selector; keyboard/menu disconnect commands remain available.

Flux uses smooth flowing multicolor gradients on neutral text; chromatic host text stays within its hue family, and reverse video retains its exact colors. A shared bounded 20fps timer is suspended for hidden, offline, minimized or disposed views. Choose None to stop effects.

Host hidden fields remain hidden. Selection, cursor, underline, blink and reverse distinctions stay meaningful. Strong effects may not suit every display or visual preference; start with None for maximum clarity.

## Fit, zoom and DPI

Fit host screen centers the entire host presentation space inside the pane. Zoom in/out stays centered on each axis that fits, from 50% to 300%. Content that exceeds an axis uses the existing scrollbars from its padded start, with the editing cursor kept reachable. Defaults are Ctrl+0, Ctrl+= (also Ctrl+plus/keypad plus) and Ctrl+- (also Ctrl+keypad minus).

## Configuration for external tools

Help > Configuration for external tools opens the formatted offline AI-CONFIG.md guide, with selectable commands and tables. CONFIG-SCHEMA.json supplies typed fields, exact enum/default/constraint values, every theme role and action, and layout grammar. Fish-config.cmd beside Fish.exe provides paths, schema, export, validate (dry run) and apply. Close Fish first; use a separate full exported snapshot, preserve its revision and unedited settings, validate, then atomically apply. Locks, stale revisions, unknown fields and invalid values are rejected without resetting the workspace. Changes take effect next launch; apply never connects/replays work or touches credentials. Normal startup stays offline unless you enabled Connect panes on startup. Passwords and font bytes are not exported. There is no embedded AI service or executable configuration hook.

Glyphs are generated at final physical pixel resolution with fixed-cell positioning; default text is not a bilinear-upscaled bitmap. Windows scaling and monitor transitions may change the fitted physical font size, but never the host's row/column count.

At very small pane sizes, the screen must be small to fit. Increase the window, use fewer panes or use manual zoom if readability is more important than seeing the entire host screen.

## Layout persistence and multiple instances

Closing Fish saves window size, pane arrangement, profile selections, last-used preset, application/custom themes, font reference, shortcuts, outline/tint, pane-information and startup connection preferences. Next launch is offline unless Connect panes on startup is enabled; saved layouts alone never imply auto-connect.

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

Transient input notices have a thin border and Dismiss X and clear after 2.8 seconds. Dismiss never resets/unlocks/submits or closes a pane. A recoverable error retains the small Reset control; Host > Last pane notice retains its explanation. Connection status remains in the footer.

Clipboard unavailable: another program may own the clipboard temporarily; retry. Protected/numeric fields or code-page rejection require correcting the input, not bypassing validation.

Snap layouts absent: confirm Windows 11/Snap settings, hover on maximize, or use Win+Z/Win+arrows. Windows 10 does not have the Windows 11 flyout. Very small display/work-area layouts may be unavailable.

The executable will not start after moving it: restore the complete Fish folder including app and runtime. A native integration load error requires the matching fish-window.dll and the included runtime.

Workspace already open: close the other instance or choose a different --data-dir. A lock file by itself is not proof of an active process; the OS-held lock is authoritative.

Build download fails: check internet/proxy policy and retry. A checksum mismatch is a hard failure; no failed archive is executed. Do not disable checksum checks. Keep your source extraction in a writable user folder.

Installer fails under organizational policy: use the portable ZIP if permitted, or consult IT. Do not elevate the build script or bypass policy to force installation.

## Compatibility, licensing and release status

Fish is an experimental TN5250J-based implementation, not a demonstrated full ACS-compatible replacement. The only terminal type is IBM-3477-FC. Only SBCS code pages in the profile list are offered; DBCS is not implemented.

Known gaps include enhanced WTD/WEA/structured-field behavior and cryptographic password substitution. Kerberos initial-token sign-on is implemented but not real-host verified. Unsupported engine behavior is reported/stops the affected session instead of inventing a successful response. Real-host acceptance requires an IBM i, valid test credentials, ACS access and representative captures that have not been supplied.

Read COMPATIBILITY.md for exact locally demonstrated behavior and the remaining acceptance checklist. The streamlining preview is not a claim of production readiness or successful real-host authentication.

Fish and its pinned TN5250J core are GPL-2.0-or-later, without warranty. LICENSE, NOTICE, the core license and complete corresponding application/core sources accompany the package. The Windows frame bridge is Fish source, not a proprietary runtime dependency.

## Index and finding help

Use the manual's Find field to search topic titles and full text. Select an index entry to jump to its section; matching text is highlighted. Ctrl+F focuses Find. Clear the query to restore every topic; Escape closes the manual.

For signing on, start with Getting Started. For navigation keys, see Global shortcut overrides. For appearance, see Application themes, Host color palettes and Fit. For install/build, see Installation and Building. For errors, see Troubleshooting and Compatibility.
