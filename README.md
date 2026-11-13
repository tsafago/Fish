# Fish

A Windows-first, keyboard-driven desktop 5250 workspace. Java 21 / Swing,
plain TN5250 TCP, a pinned TN5250J screen/field engine, a small source-built
Windows frame/SSPI bridge, and no third-party runtime JAR dependencies.

**Working experimental implementation, not an ACS-compatible release.**
Real IBM i sign-on and ACS comparison have not been performed. Enhanced display
structured fields and WEA are explicit compatibility gaps, not silently
accepted features. Read [COMPATIBILITY.md](COMPATIBILITY.md) before connecting
to a production system.

## Fish 0.8.0 beta

[Download v0.8.0 BETA](https://github.com/tsafago/Fish/releases/tag/v0.8.0):
current-user setup, portable image, complete corresponding source/licenses and checksums.
This is an unsigned prerelease, not a stable or ACS-compatible release.

Valid discrete Settings choices take effect in their interaction, independently
of coalesced asynchronous disk writes. Typed fonts/keys/profile fields retain
validation pauses and explicit creation/password boundaries. **Saved** acknowledges
the latest durable generation, not merely a visible change. Theme changes recolor
existing controls instead of rebuilding their UI, fonts or terminal glyph grid.

The centered flat profile selector is separated from its neighbors, with explicit
**+** connection intent. Collapsing menus remain; real control-derived minimums
are enforced by Swing and Windows native resizing. Saved layouts/shortcuts uses
the referenced name-above-full-width-field hierarchy and left-aligned creation
actions. Tutorial helpers follow related owned dialogs without global always-on-top.
**Light** and **Slate**, plus **Azure vivid** and **Crimson vivid** text palettes,
retain semantic host colors and readable control states.

An app-triggered successful installed update reopens verified Fish once and
closes setup; failure, cancellation, reboot-required and launch errors remain
visible. Portable updating still installs a per-user copy, not a replacement
portable folder. **0.7.x cannot automatically discover this new-minor beta** with
its same-minor preview policy: download and upgrade manually. Stable excludes beta.

Unreadable/future workspace startup offers Exit, one Retry and explicitly
confirmed Restore factory settings. Recovery keeps an exact unique
`workspace-recovery-UUID.xml` backup before atomic replacement under the existing
exclusive lock, without clearing credentials, fonts or other user files.
Format **19** requires a pre-upgrade workspace backup to downgrade to 0.7.9 or earlier.
Synthetic fractional-grid checks are not proof that the physical 4K jump is gone;
physical monitor/native upgrade/IBM i/ACS/SSO acceptance remains unverified.

## Included 0.7.9 cursor/usability preview

[Download v0.7.9](https://github.com/tsafago/Fish/releases/tag/v0.7.9):
current-user setup, portable image, complete corresponding source/licenses and checksums.
The accepted local 0.7.7/0.7.8 settings, shortcut and simple-pane work is included.

Cursor and text share one snapped physical grid; inherited antialiasing cannot
move the cursor relative to its cell. Cursor-only dirty repaint restores the same
pixels as a complete paint without changing neighboring ink. None/Flux reuse
identical glyph coverage and metrics. **Settings > Appearance > Cursor style**
defaults to **Underline / filled Insert**. **Outline / filled Insert** and
**Filled** (both modes) are available and autosave. Filled cells retain readable,
contrasting glyphs without revealing hidden/blink-off text or changing font weight.
This mode-aware style is documented by IBM for its Linux 5250 emulator; ACS's
exact factory default remains unverified. The shortcut name field uses the same
native text rendering as other controls and restores it after Unicode fallback;
the accepted shortcut window/flow is unchanged.

**Edit > Settings** now applies valid edits automatically after a short typing
pause. **Saved** confirms the disk write; invalid fields retain their last usable
values, conflicting external edits require reload, and failed saves expose Retry.
Close keeps saved changes. Profile creation and Windows password-store changes
still require explicit actions; no settings edit connects a session.

**Panes > Saved layouts / shortcuts...** starts with this window's profile/layout,
a name, and **Create desktop shortcut...**. Confirm the destination to save the
recipe and create its link in one flow. Cancel creates neither. **Save layout only**
and the expandable **Saved layouts** library retain separate save/update/rename/
delete/open/export operations.

Panes now use only simple arrangements: one full window; two halves; three equal
rows/columns or a half beside two stacked panes (including rotated/mirrored forms);
four quadrants; then each quadrant can split **once**, up to eight panes.
Structural adds/moves rebalance sizes instead of accumulating uneven bands.
Reasonable intentional divider resizing remains available between structural moves.

With two or three panes, drag to the **window's outer edge** for whole-window
placement: a large half crosses intact with its opposite pair, or a third pane
beyond two aligned rows/columns makes **three equal strips**. Inner pane edges
arrange a half and pair. At four to eight panes, edges operate on quadrant slots:
fill an available half-slot or swap/reorient existing slots, never nest further.
Centers always swap. The shaded preview labels **Window** or **Pane** and shows
the actual resulting geometry. Too-small/invalid/outside drops do not move panes.
**Panes > Arrange pane...** offers both target areas from the keyboard;
**Undo pane arrangement** restores recent moves without reconnecting.

This build writes **format 18 / recipe 3**. Unsupported older experimental
geometry normalizes with a notice, retaining pane/profile IDs, recipe references
and display state. Close **all** Fish windows and
back up `workspace.xml` first. Fish 0.7.8 and earlier need the pre-upgrade backup
to downgrade. Published [v0.7.6](https://github.com/tsafago/Fish/releases/tag/v0.7.6)
and its files are unchanged. Run the separate final image:

```powershell
& '.\dist\Fish-0.7.9\Fish\Fish.exe'
```

### Retained 0.7.6 shortcut guidance and chrome refinement

The profile selector and + now share the flat titlebar surfaces, sizing and
interaction states, with a clear keyboard-focus underline. Selection still only
chooses the profile; pressing + adds and connects it.

**Edit > Settings > Keyboard Shortcuts** explains entry syntax with valid examples
and an **Allowed keys and actions...** searchable reference derived from the
parser and action catalog. **Help > Tutorials > Desktop profile / layout shortcuts**
walks through the existing save/export flow using its real controls and destination
chooser. Back, Next and both Skip actions remain available; the guide never
writes or opens a shortcut automatically.

The published 0.7.6 image uses format 15. This build migrates it to format 18.
Already supported shapes/reasonable proportions remain; unsupported geometry is
deliberately simplified without losing sessions' saved identities or recipes.

### Retained 0.7.5 saved-layout shortcuts and quick connections

Choose a profile in the compact titlebar selector, then press **+** (or Ctrl+N)
to add and connect it immediately. Selection alone changes no pane or connection.
Only a lone, never-used offline tile is reused; later adds create fresh independent
sessions, up to eight per window. Failures stay in the affected pane with
**Reconnect** available. Disconnect stays disconnected; there are no automatic
retry loops or transaction replays.

**Panes > Saved layouts / shortcuts...** captures this window under a new name.
Create desktop shortcut saves a separate recipe and its link after destination
confirmation; Update from this window in Saved layouts replaces the
selected recipe without breaking its existing links. Create shortcut writes an
actual Windows `.lnk` to Desktop or another chosen folder. Every shortcut launch
opens exactly one new independent, auto-connecting window, whether Fish is already
running or cold-started. Recipes capture pane/profile assignments, divider
proportions, window size and per-pane fit/zoom, never screen contents or secrets.
Renaming keeps the stable ID; deleting a recipe leaves exported files untouched
and makes those links report that it is unavailable. Referenced profiles cannot
be deleted until their named recipes are updated or removed.

Shortcuts retain the installed/portable Fish.exe path and explicit data directory,
not a username, host or password in their arguments. Installed upgrades retain
the target; moving a portable folder requires creating new shortcuts. Unicode
and spaced paths are supported by the native launcher. Cold shortcut launches
do not open the previous window collection: they retain it for the next ordinary
launch and append the shortcut window to that saved collection on Quit. Named
recipes themselves change only on an explicit Update, not ordinary pane resizing.

**Edit > Settings...** is the single settings entry; Ctrl+, opens that
same modeless dialog. Its flat index is **Appearance, Profiles, Keyboard Shortcuts,
General, Updates**. Appearance contains application theme and selected-profile
text palette/effect together, followed by font, header/footer information, outlines
and tint. Profiles and the unified keyboard editor are actual inline controls,
not launchers. Valid settings save automatically; Close keeps them. New profiles,
password-store changes and file/destructive operations remain explicit. Optional profile port/device/code page/Kerberos identity stay
under Advanced, with validation exposing the corrective field.

Settings references below mean that same Edit menu dialog. The **profile / +**
group is the last action after all menus, before the blank drag region and native window controls.
Edit also contains clipboard actions. **Inserts** groups username/password helpers,
common field/host keys and the complete host-key chooser. Panes is flat; View
keeps runtime fit/zoom; Host keeps Reset/Attention/System Request. Menus show one
primary key chord, while Keyboard Shortcuts exposes every binding. Updates renders
the manual's safe Markdown subset: no remote images, HTML execution or commands.

Launch Fish.exe again, or use **Session > New window (Ctrl+Shift+N)**, for another
normal independent taskbar/Alt+Tab window. Each window has its own layout, focus
and up to eight independent sessions; an explicit new window starts one fresh pane with the
source window's titlebar profile and connects it once. Ordinary cold startup
restores saved windows according to the existing startup preference. Profiles,
settings and shortcuts are shared live. X closes only that window's sessions.
Session > Quit saves the whole open collection for restoration; closing one X
removes it from that collection. The final window's X retains its own layout.
Only one process writes configuration; subsequent launches use a private,
authenticated loopback new-window rendezvous. No workspace cloning is needed.

Terminal typing, field navigation, mouse placement, focus and pane/window switches
restart the cursor visibly for a complete 550ms blink interval. Insert fills its
physical cell with a contrasting glyph, not bold terminal text. Default headers show only
the profile; footers show concise connection/input status, actual allocated device
when known, cursor position and screen size. Authentication details remain under
**Host > Last pane notice** rather than in the everyday footer. “Connected” is not
a claim of verified IBM i sign-on.

**Help > Tutorials** has six replayable, real-control guides: Getting started;
Appearance, colors and pane info; Non-Kerberos profile; Keyboard shortcuts; Pane
tips and tricks; Desktop profile / layout shortcuts. The desktop guide walks
through naming, saving and choosing a destination for a real Windows link, with
optional profile/add steps for a single-profile shortcut. No file is written or
launched just by opening the guide. Guides progress on actual actions, highlight production controls,
stay usable with editors open, and always offer Next/Back/Skip step/Skip tutorial.
Opening/replaying/skipping a guide never supplies credentials or starts a session;
pressing its highlighted + control is the same explicit connect action as usual.

The **application-wide** keyboard editor combines Fish and host actions, typed
exact chords, multiple bindings and **Record and add**. Main `ENTER` and
`ENTER@numpad` are independent; **both default to host Enter**, with Ctrl+Enter
retained for Field Exit. Custom keys replace their defaults; conflicting custom
owners are rejected. There are no live per-profile maps. Old explicit definitions
migrate without choosing a winning profile: incompatible keys start inactive,
and exact originals/conflicts remain recoverable in Settings > Keyboard Shortcuts > Migrated definitions
and exported `keyboard.legacy.*` data.

The editor shows valid single-key and combination examples: `F5`, `ctrl F5`
(Ctrl+F5), `ctrl shift F5` (Ctrl+Shift+F5). Use spaces, not + signs, and one
alternative per line. **Allowed keys and actions...** opens searchable parser-
and catalog-derived lists with exact tokens, defaults and direct action navigation.

The native setup checkbox now paints its actual text/check/focus path with
explicit colors, including disabled states and Windows high contrast. Never-shown
native control checks cover 100/125/150/175/200%; no live installer was run.

**Unsigned experimental prerelease**, now published at
[tsafago/Fish](https://github.com/tsafago/Fish). Profiles have keyboard-accessible
Up/Down/Delete controls. Order stays consistent across pane selectors and saved
configuration; deletion preserves panes and blocks removal of a shared login source.
**Settings > General** offers a
confirmed factory reset: save fresh first-run state, end sessions and exit.
Windows saved passwords, actual font files and other workspaces are kept.

**Settings > Updates** checks public GitHub releases asynchronously, with
no account/token/gh dependency. A quiet startup check offers Update, X or
Hide version; hiding applies only to that version. Default preview updates stay
within 0.7.x. The optional stable channel accepts stable releases within the same
major version; neither channel silently enters a newer experimental minor/major.
Download/install needs explicit consent. Size, SHA-256, GitHub origin and Windows
signature policy are checked before setup starts. Checksums are **not signing**.
The installed app updates in place; portable mode installs/opens a per-user copy
with the same workspace and leaves the portable directory untouched.
Fish 0.7.0 and earlier need a manual first upgrade.

The new compact, dark native setup wraps the same per-user Windows Installer
transaction and bundled runtime. Input notices have a physical-pixel border,
an accessible X and a 2.8-second lifetime. Dismiss does not Reset input; recovery
and **Host > Last pane notice** remain available.
Read the [manual](MANUAL.md#checking-for-updates) for update/reset details.
Configuration is now **format 18**: close ALL windows and back up before upgrading,
including from 0.7.4. Named recipes, per-window add-profile selection and per-pane
fit/zoom are new; format-14 windows, profile IDs and settings remain intact.
Old settings action bindings merge into `Settings...`; their original exact lists
remain in `keyboard.legacy.binding.*`. Existing exact host keys, profiles, fonts,
themes and update settings remain. Only the exact prior built-in information
defaults migrate to the concise defaults; custom and hidden rows stay unchanged.
Older binaries cannot read format 18; close all windows and restore the pre-upgrade
backup to downgrade, never relabel exports.

## Included Kerberos sign-in

**Fish 0.7.0** adds Windows Kerberos sign-on using RFC 4777 binary `IBMTICKET`
and the Windows **Kerberos-only** SSPI provider. A fresh workspace starts with
**Local IBM i**, **10.1.2.3:23**, Kerberos selected. New/factory workspaces now have
**connect restored panes on startup ON**, as requested; existing saved OFF choices
and older formats without that preference remain OFF.
There is no NTLM or password fallback. Existing profiles/settings are not replaced.
Windows domain credentials, the administrator-registered `krbsvr400/{server DNS}`
service/keytab, IBM i NAS/EIM and remote-sign-on policy must already be configured.
The optional **Kerberos server** field separates the service DNS identity from the
TCP IP; blank uses a fully qualified host name or forward-confirmed reverse DNS.
No realm is guessed and no ticket cache/registry changes are required.
See [Windows sign-in in the manual](MANUAL.md#windows-sign-in-kerberos).
Actual Windows-domain/IBM i SSO and ACS parity remain **unverified**.

Short, moving tutorial hints anchor to the real controls. Replay through
Help > Tutorials; upgrades do not force the guide over existing work.
New password profiles start at **10.1.2.3** with automatic sign-in and Save password checked.
Existing saved hosts and sign-in choices stay unchanged. Passwords remain in
Windows Credential Manager; plain TCP and the sign-on mechanism have the limits below.
**Use login from...** explicitly reuses another profile's login on the selected
target host using a stable reference, never a password copy.

Ctrl+C/V now copy/paste, Ctrl+Tab cycles panes, Ctrl+N/W adds/closes a pane,
Ctrl+0 and Ctrl+plus/minus fit/zoom, and Ctrl+comma opens Settings.
Escape sends Attention; Shift+Escape remains System Request. Enter during a
recoverable local input error performs **Reset only** with no host submission;
the next normal Enter resumes usual behavior. Ctrl+R remains an explicit Reset.
Explicit conflicting custom mappings are preserved/reported during migration.

[AI-CONFIG.md](AI-CONFIG.md) and [CONFIG-SCHEMA.json](CONFIG-SCHEMA.json) document
every setting, enum, color role, action and layout constraint. The shipped
`Fish-config.cmd` supports complete offline export, dry-run validation and atomic,
revision/lock-checked apply. The in-app guide now renders code, tables, lists and
emphasis rather than literal Markdown. No embedded AI or secret export.
**Settings > General > Connect restored panes on startup** reconnects restored
panes once per launch. New/factory workspaces default ON; saved preferences are
preserved. Missing logins leave actionable guidance without password prompts or
first-start alarm flashes. That preference controls ordinary restored-window startup;
explicit +, New window and saved-layout launches connect once even when it is off.
Configuration apply itself never connects or touches credentials.
This build is an **unsigned experimental prerelease**, not a production release. The native
maximize/restore perimeter defect, intermittent flicker observation and real
IBM i/ACS acceptance gates remain open.

## Interaction refinement preview

**Fish 0.5.3** is an unsigned experimental preview. Hidden/empty headers now have an invisible pane-top
swap-drag target at the close X's height, without reserving a band or intercepting
the X, Reset or terminal input below it. Manual zoom remains centered when it fits;
larger content uses the existing scrollbars and keeps the editing cursor reachable.
Active-pane outlines no longer add another line beside a shared pane divider.

That 0.5.3 preview used
Apply (save and stay) and Done (save and close). Cancel/Escape/close discard
only drafts since the last Apply. Theme Designer retains Preview and Save for
temporary previews and reusable themes, with Apply/Done for activation.
In this 0.8.0 beta, valid discrete Settings choices apply immediately and autosave separately; pane scale/layout
actions remain immediate. The configurable Flux action remains.

The logo no longer waits for the double-click timer. Normal and Flux share
cached per-channel LCD glyph coverage, changing only color; toggles do not reload
fonts or rebuild host glyphs. Default font resolution avoids the eager full-family
scan; no startup speedup is claimed without a measured cold-start comparison.
Bounded production-path checks passed on 1080p at 100% and 4K at 150%, including
hidden-header swapping and popup/dialog dismissal. The 4K logo switch changed
terminal pixels in about 91 ms. Sampled resizing kept terminal ink visible without
native background-erasure messages. The intermittent resize flicker was not
reproduced; it remains an open hardware observation, not a verified fix.
The known maximize/restore perimeter defect below is unchanged.

## Included DPI-regression preview

**Fish 0.5.2** is an unsigned experimental preview with a known maximize/restore outline defect.
Owned structural edges share one subtle divider color and a one-physical-pixel
painter. Fractional-DPI painting now follows Swing's actual buffer-copy endpoints
without expanding repaint clips, preventing missing edges and orphan menu lines.
The whole-window outline is on by default. Change it under **Settings > Appearance**;
active-pane outline and tint remain separate options there.
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
and the theme designer are together in **Settings > Appearance**.

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
Downloads are available from the public [releases page](https://github.com/tsafago/Fish/releases); older previews remain intact.
It is not a claim that the real IBM i/ACS acceptance gate has been passed.
The [full manual](MANUAL.md) begins with **Getting Started** and is also
available offline from **Help > Full manual / Getting Started**, with a
searchable full-text topic index.

## Install: no developer tools needed

Download `Fish-0.8.0-setup.exe` and its matching `SHA256SUMS.txt` from
[v0.8.0 BETA](https://github.com/tsafago/Fish/releases/tag/v0.8.0), then run the
installer for your Windows user. It creates
Start menu/desktop shortcuts without a system-wide Java installation or
administrator installation. Keep one release's installer, portable ZIP, complete
source and checksums together; earlier previews remain available. Downloads
are public and require no GitHub account.

For a portable copy, extract the matching `Fish-0.8.0-windows-x64.zip` and open
`Fish\Fish.exe`. Keep the whole folder together. Windows 10/11 **x64** is
required; ARM64 and non-Windows systems are not supported by this package.
The executable/installer are unsigned, so SmartScreen or organizational policy
may warn or block them. Checksums accompany the package; do not bypass IT policy.

**Back up before upgrading:** close ALL Fish windows normally and copy `workspace.xml`
outside the application folder before first launching 0.8.0 beta. The usual location
is `%APPDATA%\Fish`; an existing legacy `%APPDATA%\Pane5250` workspace is reused,
and custom `--data-dir` locations still apply. The next save upgrades configuration
to **version 19** if needed. Fish 0.7.9 and earlier cannot read
it. Keep a pre-upgrade backup for downgrade. XML/config exports contain no passwords or Kerberos tickets;
OS credentials are separate.

## Generate an executable and installer yourself

Extract the complete matching `Fish-source.zip`, then
**double-click `Setup-Fish.cmd`**. No Git, installed JDK, Visual Studio, WiX
installation, administrator rights or system PATH edits are needed.
The first build needs internet access and about 2 GB of free disk space.
The script downloads SHA-256-pinned portable tools into `.tools`, then produces:

| Output | Use |
|---|---|
| `dist\Fish\Fish.exe` | Run immediately with its adjacent app/runtime folders |
| `dist\Fish-0.8.0-setup.exe` | Current-user native installer with bundled runtime |
| `dist\Fish-0.8.0-windows-x64.zip` | Portable image, alongside matching source and checksums |
| `dist\Fish-source.zip` | Matching complete application/core sources |
| `dist\SHA256SUMS.txt` | Verify distributed downloads |

`Setup-Fish.cmd -Install` additionally opens the generated installer.
Existing application/source output is preserved as timestamped backups.
Close Fish before rebuilding/upgrading its directory. The script does not
silently replace failed downloads or bypass checksum mismatches.

## Run the local package

The matching package is **Fish 0.8.0 BETA**, an unsigned experimental prerelease.
To package without replacing an open image, pass
`-ImageDirectory dist\Fish-0.8.0\Fish` to Package.
This delivery is at `dist\Fish-0.8.0\Fish\Fish.exe`; `dist\Fish`
may still be an older running version.
Keep the entire application-image directory together:

```powershell
.\dist\Fish-0.8.0\Fish\Fish.exe
.\dist\Fish-0.8.0\Fish\Fish.exe --demo
```

The packaged application includes a private Java runtime; no installed Java,
administrator rights or installer is required. Windows PowerShell is needed
for Windows Credential Manager operations. The executable is not code-signed.
The demo runs a real, loopback-only synthetic TN5250 server with three
independent connections. F5 requests a 27x132 screen; F6 requests 24x80; Enter
sends edited fields to that server. It is not a simulated IBM i sign-on or
evidence of ACS parity.

Normal startup follows **Settings > General > Connect panes on startup**: ON for
new/factory workspaces; existing saved OFF choices and legacy absence remain OFF.
Fresh installs can select **Connect** for the preselected Kerberos profile.
To change authentication or DNS identity, press **Ctrl+Alt+P**, enter the IBM i hostname or
IP address, leave port **23** unless configured otherwise, and choose
**Save & connect**. Only `IBM-3477-FC` is offered. A host controls whether the
screen is 24x80 or 27x132; resizing a pane only scales its rendering.

**Plain TCP does not encrypt credentials or terminal data.** Use only on a
trusted network. Kerberos authenticates using Windows credentials but does **not**
encrypt terminal traffic or provide verified mutual authentication. TLS is out of scope.

## Advanced build commands

Windows x64, Windows PowerShell 5.1 or later, and a **JDK 21** are required.
Git is required for a development clone; initialize its pinned submodule:

```powershell
git submodule update --init --recursive
# Prefer Setup-Fish.cmd above, or prepare the pinned portable SDK:
.\scripts\bootstrap.ps1
.\build.ps1 Build
.\build.ps1 Maintenance # isolated maintenance/update/notice checks; never-shown windows
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
The branded setup is compiled from `src\native\fish-setup.c` and embeds the
matching MSI plus a verified size/hash footer; no external setup runtime is added.

`Maintenance` checks actual profile controls/selectors, first-run reset, version
and release parsing, channel/hide/error/retry/cancellation/handoff paths with
injected HTTP/consent/installer, and offscreen borders at 100/125/150/175/200%.
It never shows a window, accesses the shared clipboard, acquires credentials or
runs an installer. Installer builds also run safe self-test, payload verification
and offscreen design-rendering modes. These do not establish live setup interaction
or a successful installed upgrade; those acceptance gates remain unverified.

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

`Maintenance` is the current noninteractive regression gate. It includes
`UsabilityIterationSmoke`: acknowledged autosave/invalid/stale/failure/retry
controls and explicit profile/password boundaries. `SimplePaneSmoke` adds canonical
halves/thirds/half-and-pair/quadrants, whole-vs-local targets, live field/session
retention, exact preview/pixel rounding, repeated eight-pane moves and migration.
Together they cover
live loopback session preservation, legacy recipes and real native links in a
temporary folder. Compile `tests\native\packaged-kerberos.c`, then invoke the helper
as `helper.exe IMAGE_DIRECTORY --usability TEST_CLASSES` to run the fixture
against the actual private-runtime image; `--simple` runs the canonical-pane fixture and `--layouts`
checks its native executable's offline Unicode config CLI and restricted IPC.
No actual Desktop shortcut, clipboard, ticket, installer or IBM i is needed.

The earlier `Smoke` revision fixtures cover bounded
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
Cancel/Apply/Done buttons, all eight combinations of the three persistent settings,
and Cancel/Escape/Close dismissal without saving unapplied edits. Evidence uses the
real shared dialog builder.
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
for 1px, Enter to commit and Escape to cancel. Window close disconnects only that
window's sessions and closes immediately, without a confirmation. Other Fish
windows stay alive; the owner releases the workspace lock after the final window
closes. Save failures are reported explicitly.

The titlebar **profile selector / +** adds and connects the chosen profile, up to
**eight panes**. Header top/bottom drops stack; left/right drops place side by
side; the center swaps. One axis stays capped at two and the other at four,
without arbitrary recursive grids. The shaded result precedes a valid drop;
invalid/minimum-size targets are refused. Panes > Arrange pane is the keyboard
route, Swap panes retains direct swaps, and Undo pane arrangement restores moves.
Addition is disabled at eight. Drag hairline dividers to resize. Moves retain actual session
objects, socket connections and host state.

The **X at the far right of each pane header** immediately disconnects and
removes that pane, reflowing the remaining slots. Only the last pane is kept
as an empty offline tile. Ctrl+W does the same; there is always at least
one pane. Headers show preset names, never A1/A2 identifiers.
Offline content contains only a centered profile selector and adjacent arrow Reconnect
icon button. The collapsed selector does not paint its value as selected text.
The titlebar selection persists independently per window; restored panes retain their saved
profile assignment. Explicit adds connect once; ordinary cold startup follows the saved startup preference.
The active pane has a single subtle tinted surface across its header, terminal
and footer, with an outer physical-pixel focus outline and no header underline.
Open **Settings > Appearance** to change
the active-pane outline and tint independently.
Focus never changes the border's layout insets. OLED themes retain true-black surfaces.

Native Windows frame styles and hit testing support Win+arrows, edge/corner
snapping, top-edge maximize and the Windows 11 maximize-button snap target
without a bright native titlebar. Narrow windows collapse menus and tool labels.
The fish mark is a keyboard-focusable secret button: a single click toggles
the active preset's text effect from Flux (animated) to None, or from any other
effect to Flux (animated). Changes use the same persistent profile path as Appearance; panes using
that same preset also refresh. Keyboard Space activates it immediately, with a
visible focus indicator. Mouse release and keyboard activation switch immediately;
a double-click's second activation returns to the preceding effect, while a drag
does not activate the logo. The rest of the
title area remains available for moving/maximizing the window.

While connecting, the preset controls are replaced by a small rotating ring
until the first valid host record arrives. The footer retains connection status
and errors. X remains available to cancel a pending connection. When only one
pane remains and it is disconnected, its X is hidden; other pane-close and
disconnect behavior is unchanged.

**Settings > Keyboard Shortcuts** lists every canonical registered
application and host action, with typed multi-binding lists, exact recording,
unbinding/default controls and conflict validation. Assign **Ctrl+Tab** to Next pane and
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
it is not assigned to a host action. **Ctrl+C/V** are the clipboard defaults;
native text fields/dialogs keep ordinary editing. Explicit conflicting host
overrides migrate without being stolen, retaining the older app chord with a notice.
Copy omits non-display data. Paste respects host fields, validation, code pages
and inhibition; newlines/tabs navigate without submitting Enter. Clipboard
failures are reported in the pane status and actions return focus to the terminal.

Profiles store host, port, optional device name, username, manual/automatic
sign-on preferences, SBCS code page, palette, effect,
zoom and a saved layout. New and Duplicate are in the compact profile manager.
Duplication preserves sign-in preference flags but clears device/layout/login
reference and uses an independent credential ID. It never copies a stored password.

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
The last pane layout and window size are restored next launch; connections follow
the saved startup preference (new/factory ON, existing explicit OFF preserved).
Application appearance, global shortcuts, pane highlighting and the last used
preset are saved with version-18 state, including tutorial progress and explicit login references.
Version-1/2/3 profiles and credentials are retained. Only pre-bounded freeform layouts reflow
into bounded slots with a visible status notice; a legacy empty workspace
becomes one offline tile. Supported simple layouts retain geometry; unsupported
format-15/16 geometry simplifies with a notice and unchanged pane/profile IDs.
A legacy layout over eight panes is rejected explicitly
without overwriting its file: reduce it using the preserved Pane5250 package
or use another `--data-dir`. It is never silently truncated.
**Ctrl+Alt+S/L** additionally saves/restores a profile-specific layout.
The workspace is locked against concurrent writers. Corrupt state produces
an error and is preserved, never silently replaced. `--data-dir DIRECTORY`
selects another workspace; the demo otherwise uses a temporary workspace and
removes it on normal exit.

## Keyboard

Common application actions use conventional Ctrl chords; less common terminal/workspace
actions retain **Ctrl+Alt**. All custom app/host bindings use one validated global map.
**Alt+Space** and **Alt+F4** are also reserved for standard window operations.

| Action | Shortcut |
|---|---|
| Profiles / quick profile switch | Ctrl+Alt+P / Ctrl+O |
| Connect or disconnect / reconnect | Ctrl+Alt+R / Ctrl+Alt+Shift+R |
| Add the next bounded pane | Ctrl+N |
| Swap / close and disconnect pane (keep the last tile) | Ctrl+Alt+D / Ctrl+W |
| Next or previous pane / focus numbered pane | Ctrl+Tab or Ctrl+Shift+Tab / Ctrl+Alt+1..8 |
| Adjust nearest divider | Ctrl+Alt+[ or ] |
| Save / restore profile layout | Ctrl+Alt+S / Ctrl+Alt+L |
| Fit / zoom in or out | Ctrl+0 / Ctrl+Equals (or plus) or Ctrl+Minus |
| Copy selection (or whole screen) / paste | Ctrl+C / Ctrl+V |
| Rectangular selection | Shift+arrows, or mouse drag |
| Insert profile username / remembered password | Ctrl+Alt+U / Ctrl+Alt+Shift+P |
| Host key chooser / application menu | Ctrl+Alt+F / Ctrl+Alt+M |
| Settings | Ctrl+comma |
| Dark window menu / standard Windows window menu | Ctrl+Alt+Space / Alt+Space |
| Immediately close window and disconnect all | Alt+F4 |
| Quit | Ctrl+Q |

| Host action | Default key |
|---|---|
| F1-F12 / F13-F24 | F1-F12 / Shift+F1-F12 |
| Enter / Field Exit | Main or keypad Enter / Ctrl+Enter |
| Field Plus / Field Minus | Keypad + / - |
| Reset / Attention / System Request | Ctrl+R / Escape / Shift+Escape |
| Clear / Help | Pause or Ctrl+Backspace / Alt+F1 or Ctrl+H |
| Dup / Host print | Shift+Insert / Ctrl+Pause |
| Roll up / down | Page Up / Page Down |
| Next / previous field | Tab / Shift+Tab |
| Insert, delete, home, end of field | Insert, Delete, Home, End |

Use Settings > Keyboard Shortcuts for application-wide bindings, e.g. select Host F13 and
enter `ENTER@numpad` and `ctrl F3` on separate lines. Inserts > Send host key exposes available
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
network failures. During a recoverable local error, the first Enter resets only,
without an AID; the next normal Enter submits. Escape sends Attention, not Reset.

**Appearance** offers eleven persistent application themes: Charcoal, Graphite,
Midnight, Paper, OLED, OLED Amber, High Contrast, High Contrast Light, Lagoon,
Afterglow and Eye Saver. Eye Saver is a warm low-glare palette, not a medical claim.
They cover custom chrome, headers/footers, menus/popups,
dialogs, fields, lists, tabs, selection, disabled/hover/pressed/focus states,
dividers and text. Application themes are independent of workstation host
palettes: Phosphor, Cool, Warm, Amber, Ice, Orchid, High contrast, IBM vivid,
Mint, Rose, OLED green and OLED amber. Host
color/reverse/underline/blink/non-display distinctions remain intact.
**Settings > Appearance > Theme designer** edits all 60 semantic RGB roles: surfaces, controls and
states, chrome, menus/tooltips, header/footer/statuses, outline/tint/dividers,
cursor/terminal selection, all sixteen host slots, six gradient stops, glow
and logo colors. Preview is temporary; Cancel restores the applied theme.
Save keeps a reusable named theme; Apply activates it and stays open; Done
applies pending edits and closes. Editing an already
active named theme updates that theme on Save. Duplicate names require explicit
replacement confirmation; up to 32 custom themes are stored in the workspace.
Valid colors are not silently contrast-corrected. When a custom theme is active,
its host slots supersede the profile's built-in palette; other profile settings
and host attributes are unchanged.
**Structure > Divider** controls every owned structural border/separator,
including optional pane/window outlines. Version-5 custom themes keep their
existing divider color; old separate LINE/OUTLINE colors are linked to it with
an explicit migration notice.

**Settings > Appearance > Terminal font** discovers available regular monospaced families and accepts a
local TTF/OTF file. Automatic application checks printable fixed-cell widths and the current
profiles' code-page glyphs. Missing/unsupported files are reported explicitly;
startup uses an announced temporary Monospaced fallback without erasing the
saved choice. Font files stay local and must remain available at the saved path.
They are not bundled, uploaded or copied into releases.

**Settings > Appearance > Pane information** independently controls each header/footer
item and both rows, with no space for empty rows. Outline and tint are just below
in the same page's **Window and focus** section.
With a hidden/empty header, its independent close X overlays the upper-right
corner without reserving a band; Reset remains a separate non-overlapping overlay.
Drag the invisible top strip at X height to swap, or use the terminal context
menu, Panes menu or Alt+drag below the strip.
Workspace appearance settings apply across profiles; effects remain
profile-specific.

Optional Soft glow, column-gradient Prism and **Flux (animated)**
effects are disabled by default. Choose **Settings > Appearance > Text effect >
Flux (animated)**, or toggle the fish logo, for smoothly
flowing multicolor gradients. The effect is saved with the preset. Neutral host
text carries the full color flow; chromatic text varies within its host hue
family, reverse video retains its exact colors, and hidden data is never drawn.
Selection, cursor and host blink remain distinct. Animation uses cached native
glyphs and one shared coalescing 20fps clock; it stops for hidden, minimized,
disconnected and disposed terminals. Choose None to stop the effect.
Persisted Neon effects migrate to None with an explicit load notice; the rest
of each profile is retained.

`RefinementSmoke` additionally covers hidden-header hit targets, single divider
pixels with active outlines, centered fit/zoom/resize and cell hit coordinates
at 100/125/150/175/200%, Ubuntu Mono/Consolas shared LCD coverage and next-paint
color changes, and production Apply/Done/cancel persistence including asynchronous
profile edits. It never sends physical input, touches the OS clipboard or stores
credentials. `RefinementDesktop` is a separately consented, bounded real-monitor
baseline/confirmation driver, with an optional test-only native message probe;
it must not be run as an unattended foreground test.

Default glyphs are created at the final physical font
size, with integer-pixel cells/origins and LCD antialiasing; text is not scaled
from a nominal font or bilinearly enlarged bitmap. Effect ink is cached in tight,
background-composited row tiles at that same native physical resolution for
the static effects. None and Flux reuse exactly the same per-channel LCD glyph
masks; only the per-used-color paint changes. Neither rerasterizes or scales an
enlarged text bitmap, and a color-only switch preserves font metrics/coverage.
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
