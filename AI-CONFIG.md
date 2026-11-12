# Fish configuration for external tools

## Start here

Fish 0.7.8 (local, unpublished) exposes its complete local configuration without an AI service, network
API, special agent or MCP server. An ordinary local tool can export an XML snapshot,
edit entries, validate it and apply it while Fish is closed. **Never generate a
partial workspace or replace settings with defaults to fix a parse error.**

Read `CONFIG-SCHEMA.json` for the machine-readable **typed field catalog**. It
describes XML properties, not JSON instance data or a JSON Schema. The catalog is
generated from the same model/enums/action catalog as the application.
Help > Configuration for external tools opens this guide offline.

The portable/current-user installation includes `Fish-config.cmd` beside Fish.exe.
The guide and catalog are in `app`; matching sources include both at their root.
Use these commands from the Fish directory in Windows PowerShell:

```powershell
.\Fish-config.cmd paths
.\Fish-config.cmd schema
.\Fish-config.cmd export --file "$env:TEMP\fish-before.xml"
# Edit that NEW snapshot, leaving config.baseRevision unchanged.
.\Fish-config.cmd validate --file "$env:TEMP\fish-before.xml"
.\Fish-config.cmd apply --file "$env:TEMP\fish-before.xml"
```

`paths` returns the actual workspace and lock paths. Default is
`%APPDATA%\Fish\workspace.xml`; an existing legacy `%APPDATA%\Pane5250\workspace.xml`
is still used so older profiles and credentials remain reachable. `--data-dir`
selects another directory and must match the directory used to launch Fish.
Every command accepts `--data-dir DIRECTORY`; export/validate/apply require
`--file FILE`. Schema prints JSON or writes a **new** file using `--file`.

The batch wrapper waits for the GUI-subsystem executable and preserves its exit
status. From sources, the identical interface is:

```powershell
& '.\.tools\jdk-21.0.12.1+1\bin\java.exe' -jar .\dist\Fish.jar --config paths
# Or use: Fish.exe --config <command> ...
```

## Safe workflow and errors

1. Ask the user to close Fish normally. Do not terminate an active terminal or
   dismiss unsent host work on their behalf. Back up the existing workspace.
2. Export to a new file. Export refuses to overwrite an existing candidate.
3. Edit only requested entries. Preserve all IDs, unknown-to-you entries,
   unchanged values and the export metadata. Do not write plaintext passwords.
4. Validate the complete candidate. This is a dry run: no workspace writes,
   connections, transactions or credential operations.
5. Apply only after successful validation. Open Fish normally to use the result.
   Startup follows the saved `connectOnStartup` preference. Do not change
   that preference unless requested. Apply itself never connects.

All three commands acquire the normal exclusive `workspace.lock`. Any open Fish
window or another cooperating editor causes clean rejection, not live reload.
The lock file may remain after exit; its **OS lock**, not its existence, matters.
Never delete it to defeat a lock. A SHA-256 `config.baseRevision` also rejects stale
exports or non-cooperating file edits. Export again and reapply only the intended
changes if another version wins; never change the revision to force a write.
Application saves likewise detect external modifications.

Apply validates the whole candidate before writing, flushes a unique temporary
file in the workspace directory and atomically replaces workspace.xml. A filesystem
without atomic replacement is rejected. All writers must honor the lock; no file
format can make arbitrary direct writes to a running workspace safe.
Use a local filesystem. Font files must be local, not UNC/network paths.

Exit codes: 0 success; 2 invalid command/configuration; 3 lock, stale revision,
parse, font, filesystem or other I/O rejection. Diagnostics go to stderr; successful
export/validate/apply prints a JSON result. A failure does not substitute a clean
default workspace. XML is bounded at 2,000,000 bytes. Duplicate entries, unknown
keys, unsupported future versions and external DTD/entity declarations are rejected.
No shell commands, hooks, scripts or executable settings are supported.

An exported legacy workspace is migrated to version 17 with recoverable keyboard migration
notes. The original workspace is not migrated on disk until apply or a normal app
save. Older binaries (including 0.7.7) cannot read version 17: restore a pre-upgrade backup for a downgrade.
Do not manually relabel old/future data as version 17. Version-8/9 hosts, sign-in
choices, login references and tutorial status are retained; startup connection
is off on pre-9 upgrade and preserves the explicit version-9 value. Legacy profiles
remain PASSWORD, never silently Kerberos. No existing profile is replaced by a seed.
Version-10 Kerberos/server metadata is retained. Pre-11 update preferences default
to startup checks on, PREVIEW_PATCHES and an empty ignored version.

## Format and complete coverage

The canonical representation is Java XML properties, with a `properties` root and
one `entry key="..."` per setting. Keep its standard fixed DOCTYPE:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
```

The standard DTD is resolved internally by Java, not retrieved from the internet.
Use an XML editor/parser that preserves it without external resolution; escape XML
characters such as `&` as `&amp;`. Logical booleans are exactly `true`/`false`,
numbers use a period, enum names are case-sensitive, UUIDs are stable identity.
The catalog lists exact field types/defaults/limits and all dynamic prefixes:

| Area | Entries |
|---|---|
| Workspace | version, ordered profiles, current layout, lastProfile, width, height, appTheme, highlightActive, connectOnStartup |
| Profiles | `profile.{uuid}.` name, host, port, device, authentication, kerberosServer, username, auto, remember, loginSource, codePage, theme, effect, zoom, layout |
| Application-wide keys | `binding.{exact action title}` to newline-separated exact chords, or empty to unbind that action |
| Per-key unbinding | `binding.Unbound keys` to newline-separated exact chords disabled in terminals |
| Keyboard recovery | `keyboard.legacy.` report, shortcut.{old title}, profile.{uuid}.name and profile.{uuid}.key.{old stroke}; retained definitions, never dispatched |
| Information | `info.` header, footer, profile, connection, host, device, status, dimensions, cursor, insert |
| Fonts | font.family and font.file; exact installed fixed-width family or local TTF/OTF reference |
| Themes | appearance.selected, appearance.themes and `appearance.theme.{index}.` name plus **every** ColorRole |
| Window/focus | appearance.outline, appearance.windowOutline, independent highlightActive |
| Reserved legacy keys | appearance.retired, newline-separated valid shortcut strokes |
| Tutorial | tutorial.status: NEW, IN_PROGRESS, COMPLETED, SKIPPED |
| Updates | updates.startup, updates.channel, updates.ignoredVersion |
| Export only | config.format=1 and config.baseRevision; never copied into workspace.xml by apply |

`CONFIG-SCHEMA.json` contains the exact action titles, host actions/defaults,
enum values, all color roles (including all host colors, gradients, logo and glow),
layout grammar and constraints. Use `schema` for the running binary's authoritative
catalog rather than hardcoding a different release's definitions.

Fresh workspaces seed/select a KERBEROS profile at `10.1.2.3:23` with auto/remember
false, loginSource empty, kerberosServer empty, connectOnStartup true.
The user explicitly changed new/factory startup connections to **ON** in 0.7.3.
This is not reseeded into existing data. New starts a Kerberos draft; its first
switch to PASSWORD initializes auto/remember true, then retains user changes.
Existing saved PASSWORD false choices remain false. No password is created.
These are creation defaults, not migration replacements. Preserve existing
hosts (including blank) and explicit off choices unless asked to change them.
Host may be empty: an unconfigured profile. Device and username may be empty;
when supplied they follow IBM i naming constraints. auto=true with a blank username
is a valid unfinished profile, but cannot connect until a username is supplied.
Fixed-width fonts are checked against the configured code pages, with explicit
errors rather than silent replacement during config validation.
font.file takes precedence over font.family; clear font.file to choose a family.
Font bytes are never embedded or exported.

The comma-separated `profiles` UUID list is the persistent selector order. Reorder
IDs without renaming them; do not remove a referenced ID without repairing current/
saved layouts, lastProfile and loginSource dependents. At least one profile remains.
The in-app Up/Down/Delete controls perform those repairs and block login-source
deletion. Deletion never deletes a source's OS credential.

`updates.startup` is a boolean, default true. `updates.channel` is PREVIEW_PATCHES
(numeric same-major/minor, including previews) or STABLE (non-preview same-major).
`updates.ignoredVersion` is empty or exact numeric X.Y.Z, no leading v. It suppresses
only that startup notice, not manual checks or future versions. The endpoint is
fixed public tsafago/Fish, not configurable; no executable/download URL hook exists.
Export contains no tokens, download state or installation commands.

Format 17 retains `windows` (the additional-window count) and complete
`window.{zero-based-index}.layout/width/height/lastProfile/addProfile` entries. The primary
window still uses the original unprefixed keys. Preserve every window in a full
snapshot; each has 1-8 bounded panes. Profiles, themes, fonts, shortcuts and other
settings are shared, not copied per window. `info.footerDevice` controls actual
allocated device display, independently of optional requested-device header info.
Only the exact prior built-in information defaults migrate; custom/hidden flags
remain unchanged. Back up with all Fish windows closed before upgrading. Older
versions through 0.7.7 cannot read format 17; downgrade by restoring the backup, never relabeling.
All Settings routes now start at Edit > Settings... (Ctrl+,).

Settings UI edits apply and persist valid values automatically, with a short
coalescing pause and explicit Saved/error feedback. Invalid partial groups keep
the previous usable state. New profiles, credential-store changes, destructive
actions and exports remain explicit. This does not change the offline CLI's
whole-snapshot `validate` / atomic `apply` transaction.

Format 17 / recipe 3 permits only these canonical families:
one full pane; two halves (H/V); three equal-default rows/columns or a half and
opposite-axis pair, mirrored/rotated; four quadrants; five to eight quadrants each
split at most once into two children. Grid roots have two opposite-axis pairs;
each of their four quadrant children is a leaf or one leaf pair. Three strips use
a first leaf followed by a same-axis pair, with root ratio 1/3 and child ratio 1/2
by default. The strip root's ratio excludes both physical divider widths.

Structural adds/moves equalize affected arrangements. Reasonable intentional
ratios persist between moves; every pane's normalized width/height must remain
at least 60% of its balanced share. UI placements also require 70x70 logical
pixels. Whole-window edges arrange two/three panes; quadrant edges at four/eight
fill half-slots or swap/reorient existing slots, never nest further. Centers swap.
Unsupported format-1..16 geometry/extreme ratios normalize with a notice while
retaining every pane/profile ID, saved-layout UUID and fit/zoom state. Supported
shapes/ratios remain; current-format invalid geometry is rejected, not normalized.

`addProfile` is this window's titlebar selection for the next + action, not the
active pane's profile. Old formats initialize it from lastProfile without
changing startup. `pane.{pane-uuid}.fit/zoom` and
`window.{index}.pane.{pane-uuid}.fit/zoom` optionally preserve display scaling
(paired boolean and finite 0.5..3). Extra/missing-pane fields are rejected.

`savedLayouts` lists up to 64 unique stable UUIDs; `selectedLayout` is empty or
one listed ID (manager selection only). Each `savedLayout.{uuid}.` requires
version=3, name (1-80 printable characters, unique ignoring case), layout,
width/height, lastProfile and addProfile. Every recipe pane requires its own
`savedLayout.{uuid}.pane.{pane-uuid}.fit/zoom`. All profile IDs must exist;
profile deletion is blocked until named references are removed or updated.
Unknown/unlisted keys and unsupported recipe versions fail without writing.
No screen contents, live sessions, credentials or authenticated state are stored.

`Fish.exe --layout UUID --data-dir DIRECTORY` opens exactly one new independent,
auto-connecting window from that recipe. It does not import/execute files or
interpret names, paths, hosts or commands as recipes. Rename retains the UUID;
update changes what its shortcuts open; deleted IDs fail actionably. Actual .lnk
export is under Panes > Saved layouts / shortcuts; arguments contain only UUID
and data directory. Portable moves require new links. A cold shortcut launch
retains previous ordinary-window restoration without opening it and appends its
open windows for the next ordinary launch. Named recipes update only explicitly.

Factory reset is an explicitly confirmed in-app maintenance action, not an
executable configuration setting. It saves fresh workspace state and closes
ALL its Fish windows; next launch is format-15 first-run with startup
connections ON. It retains actual font files,
Windows credentials, exported shortcut files and other workspaces. Old recipe IDs
become unavailable; reset never deletes arbitrary shortcut destinations. Do not invoke reset to repair a failed
edit or silently discard user data.

## Examples using an exported snapshot

These are **entry edits**, not standalone import files. Retain all other exported
entries and use the real profile UUIDs already present in `profiles`.

Hide headers, retain the status footer, disable the window perimeter and choose
the default automatic monospace font:

```xml
<entry key="info.header">false</entry>
<entry key="info.footer">true</entry>
<entry key="appearance.windowOutline">false</entry>
<entry key="font.family">Automatic</entry>
<entry key="font.file"></entry>
```

For each chosen existing profile, set its `theme` to `Mint`, `effect` to `None`,
and `zoom` to `1.0` by updating its exported `profile.{uuid}.` entries. To change
every profile, iterate the UUIDs in the ordered `profiles` entry; do not infer IDs
from display names. Name changes do not change identity.

For example, after closing Fish, this Windows PowerShell bulk edit changes only
palettes/effects in a fresh export. It preserves the DOCTYPE, metadata and every
other entry. It does not enable sign-in or connect:

```powershell
$candidate = Join-Path $env:TEMP ("fish-" + [guid]::NewGuid() + ".xml")
& .\Fish-config.cmd export --file $candidate
if ($LASTEXITCODE -ne 0) { throw 'Export failed; do not continue.' }
$xml = New-Object System.Xml.XmlDocument
$xml.XmlResolver = $null
$xml.Load($candidate)
$entries = @($xml.properties.entry)
$ids = ($entries | Where-Object { $_.key -eq 'profiles' }).InnerText.Split(',')
foreach ($id in $ids) {
    ($entries | Where-Object { $_.key -eq "profile.$id.theme" }).InnerText = 'Mint'
    ($entries | Where-Object { $_.key -eq "profile.$id.effect" }).InnerText = 'None'
}
$settings = New-Object System.Xml.XmlWriterSettings
$settings.Indent = $true
$settings.Encoding = New-Object System.Text.UTF8Encoding($false)
$writer = [System.Xml.XmlWriter]::Create($candidate, $settings)
try {
    $writer.WriteStartDocument()
    $writer.WriteRaw('<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">')
    $xml.DocumentElement.WriteTo($writer)
    $writer.WriteEndDocument()
} finally { $writer.Dispose() }
& .\Fish-config.cmd validate --file $candidate
if ($LASTEXITCODE -ne 0) { throw 'Validation failed; no apply.' }
& .\Fish-config.cmd apply --file $candidate
if ($LASTEXITCODE -ne 0) { throw 'Apply failed; preserve the diagnostic.' }
```

Add the same explicit `--data-dir` to all three commands when using a nondefault
workspace. The temporary candidate contains profile/host metadata: keep it local
and remove it when finished; it contains no passwords.
The fixed trusted DOCTYPE above is deliberate: PowerShell/.NET's direct
XmlDocument.Save/WriteDocType can emit an empty internal subset or PUBLIC ID,
which Java Properties correctly rejects. Do not copy untrusted DTD text into
WriteRaw; only that exact fixed declaration belongs there.

Set a custom shortcut explicitly:

```xml
<entry key="binding.Next pane">ctrl TAB</entry>
<entry key="binding.Previous pane">ctrl shift TAB</entry>
<entry key="binding.Host F13">ENTER@numpad
ctrl F3</entry>
```

Removing a binding entry restores that release's default list (including aliases).
An explicit list equal to defaults stays explicit. Empty unbinds that action;
`binding.Unbound keys` suppresses individual chords in terminals. Lists replace
the whole action, not just its first key. Custom assignments replace default
owners; two custom owners of the same exact chord are rejected. The example
changes keypad Enter to F13 while main Enter remains Enter, and adds Ctrl+F3.

Use Java key names and ctrl/shift/alt/altGraph modifiers. `ENTER` is main,
`ENTER@numpad` (alias NUMPAD_ENTER) is keypad. The action-key location suffix can
be @standard, @numpad, @left, @right or @unknown. Modifier sides are not recorded.
Only distinctions actually reported by Windows/Java work. NumLock variants may
need separate entries; KP arrows normalize to numpad arrows. Unknown-location
events use explicit @unknown or standard fallback. OS chords remain reserved.
Unmodified typing keys cannot invoke Fish actions. Host keys are terminal-only;
dialog editing/IME are not intercepted. Ctrl+Alt is no longer a host/app namespace
boundary: the one validated global map resolves ownership.

Formats 1–11 migrate explicit app/unambiguous profile definitions globally.
Identical definitions deduplicate. Conflicting explicit definitions leave those
keys inactive in Unbound keys, with exact conflicts/originals in keyboard.legacy.*
and Settings > Keyboard Shortcuts > Migrated definitions. Archive entries are recovery
only, not live per-profile overrides; deletion of a profile keeps them. Old broad
app Enter spans both locations; old profile plain Enter affected main only.
Both Enter keys now default to Enter; Ctrl+Enter remains Field Exit. New format
13 rejects shortcut.* and profile.*.key.* instead of silently ignoring them.
Format 12 settings bindings migrate to `binding.Settings...`, preserving exact
locations and all multiline chords. Original lists remain under
`keyboard.legacy.binding.{old-title}`. Explicit old Settings unbinding and other
custom key ownership remain intact; these recovery entries never dispatch.

To add a named custom theme, increment appearance.themes, add a new contiguous
index and copy **all** color-role entries from an existing complete theme/catalog.
Give it a unique name, edit only requested colors, then select that name in
appearance.selected. Empty appearance.selected uses appTheme instead. Never
delete unrecognized color roles or convert a complete theme to a partial one.

Layouts use the catalog's prefix grammar: `L|pane-uuid|profile-uuid`, or
`H|ratio|first|second` / `V|ratio|first|second`. Preserve existing IDs and ratios
when not asked to rearrange. Use one to eight distinct pane IDs and only listed
profile IDs. Current, named and per-profile saved layouts must match the format-17
canonical families above. Never invent pane IDs by reusing a profile ID or remove a profile that a
current/saved layout still references.

## Login reuse and password boundary

Passwords are **not configuration**. Do not add password keys, base64 secrets,
environment variables, CLI arguments, logs or credentials to a repository.
This interface never reads/writes/deletes Windows Credential Manager entries.
The profile editor remains the supported way to save/erase a password; the OS
Credential Manager can remove obsolete records. Turning remember off in an
offline snapshot disables lookup/storage on Connect and insertion helpers but
does not erase an existing OS record. Changing host/port/username changes an
independent profile's credential identity: no password follows automatically.

`Use login from...` is an explicit stable UUID reference in loginSource. The source
must be another independent profile with username and remember=true. The linked
profile stores username empty, auto=false, remember=false, and inherits these
preferences and the source OS credential at use time. Its own host/port/device/
code page/display/keys/layout remain independent. This is authorization to use
that chosen login on **that target host**, not ambient reuse on all profiles.
No source means empty loginSource and independent login preferences.

Source rename is safe; identity/auth edits affect the next connection. Pending
password/connection work must finish before those edits. Missing OS credentials
prompt on Connect. Chains, cycles, self references, missing sources and deleting
or disabling a source still in use are rejected. Duplicating through the UI
produces an independent profile with remembering off, not a secret copy.

For PASSWORD mode, auto=true requests negotiated RFC 4777 **plain** automatic sign-on; connecting
requires a username and a 1-128 printable US-ASCII password supplied by the UI/OS store.
It does not inject credentials into guessed screen coordinates. Plain TN5250
TCP is unencrypted, cryptographic password substitution is not implemented, and real IBM i/ACS
acceptance remains unverified. A saved password or TCP connection is not proof of
successful IBM i sign-on. Do not enable automatic sign-in or sharing for an
unrelated host without the user's request.

### Kerberos metadata

`profile.{uuid}.authentication` is exactly `PASSWORD` or `KERBEROS`.
For KERBEROS, set auto=false, remember=false, loginSource empty. Username is ignored;
it never selects/retrieves a password. Profile code page/device/display/layout
remain independent. Selecting Kerberos in the editor does not delete an existing
OS password; config apply never touches OS credentials in either mode.

`profile.{uuid}.kerberosServer` is empty or a fully qualified DNS name, at most
253 characters, DNS labels 1-63 ASCII letters/digits/hyphens, normalized lowercase
and without the final root dot. It is **not** an IP, realm, raw SPN, command or
token. An explicit value determines `krbsvr400/{DNS}` while host/port still select
the TCP endpoint. Empty uses a fully qualified host name, or an IP's reverse DNS
only when forward lookup contains the original IP. No domain/realm is guessed.
Set the administrator-provided identity, not an invented example host name.

A safe requested bulk conversion updates these five fields together:
authentication=KERBEROS, auto=false, remember=false, loginSource empty,
kerberosServer set to the provided DNS name or left empty for discovery.
Preserve all other entries, validate the full export, then apply while closed.
Do not add IBMTICKET/password/realm/SSPI flags: such settings are not supported.
Tickets are transient binary buffers, never configuration or export content.
The catalog's freshWorkspaceProfile and profileFields defaults describe the
fresh/New Kerberos profile. Old password-profile choices are never replaced by
these defaults during migration.

Windows logon credentials, an IBM i krbsvr400 SPN/keytab, NAS/EIM and remote-sign-on
policy must already be configured. Fish does not fall back to NTLM/password,
extract the ticket cache, change registry/policies or authenticate during config
commands. See the manual for diagnostics and the unverified real-host gate.

## Tutorial, runtime and limits

Fresh workspaces start NEW and show the modeless guide once. Opening records
IN_PROGRESS; user Done/Skip records COMPLETED/SKIPPED. Interrupting the app midway
does not force the guide over the next session. Legacy upgrades use SKIPPED.
Help > Tutorials replays any of five real-control guides without modifying
profiles/sockets or resetting saved introduction status. Next/Back/Skip step/Skip
tutorial remain available; Show control only navigates to real controls.
Set NEW only when the user explicitly wants the first-run behavior again.

`connectOnStartup=true` is the new/factory default. Existing explicit OFF choices
and formats before this setting remain OFF. Settings > General edits it, or set
that exact boolean in an exported snapshot when requested. A normal
launch then attempts each restored pane once, using its own selected profile.
Manual sign-in profiles connect to the host sign-on screen; PASSWORD automatic sign-in
requires a saved OS password. Incomplete profiles, remembering disabled or missing
saved passwords remain offline with a pane notice, not password/profile dialogs.
Credential lookup failures and connection errors stay visible in the affected pane.
KERBEROS panes use Windows sign-in on ordinary startup only when this preference is
on; changing authentication does not silently enable it. Fresh/factory state
defaults on. Keep the user's saved choice unchanged in bulk edits. Explicit +,
New window, Restore profile layout and named-layout launches connect their intended
panes once even when ordinary startup is off. Selection alone does not connect.
Disconnect and failures never create automatic retry loops; Reconnect is explicit.
Demo stays isolated to its loopback host and cannot export normal layout shortcuts.

Apply never launches Fish or connects, sends Enter/AIDs or replays terminal work.
A restart is required to use bulk edits. Host screen content, live socket/input
state, passwords, Windows snap placement and unsaved editor previews are not
exportable configuration. Native maximize/restore perimeter loss, intermittent
resize flicker and real IBM i/ACS acceptance remain separate open gates.
