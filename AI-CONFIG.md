# Fish configuration for external tools

## Start here

Fish 0.6.1 exposes its complete local configuration without an AI service, network
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
   Startup stays offline unless the user enabled `connectOnStartup`. Do not change
   that preference unless requested. Apply itself never connects.

All three commands acquire the normal exclusive `workspace.lock`. A running Fish
instance or another cooperating editor causes clean rejection, not live reload.
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

An exported legacy workspace is migrated to version 9 with informational migration
notes. The original workspace is not migrated on disk until apply or a normal app
save. Older binaries cannot read version 9: restore a backup for a downgrade.
Do not manually relabel old/future data as version 9. Version-8 hosts, sign-in
choices, login references and tutorial status are retained; startup connection
is off on upgrade.

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
| Profiles | `profile.{uuid}.` name, host, port, device, username, auto, remember, loginSource, codePage, theme, effect, zoom, layout |
| Per-profile keys | `profile.{uuid}.key.{Swing stroke}` to a supported host mnemonic or empty to disable |
| Global shortcuts | `shortcut.{exact action title}` to a Swing pressed stroke or empty to unbind |
| Information | `info.` header, footer, profile, connection, host, device, status, dimensions, cursor, insert |
| Fonts | font.family and font.file; exact installed fixed-width family or local TTF/OTF reference |
| Themes | appearance.selected, appearance.themes and `appearance.theme.{index}.` name plus **every** ColorRole |
| Window/focus | appearance.outline, appearance.windowOutline, independent highlightActive |
| Reserved legacy keys | appearance.retired, newline-separated valid shortcut strokes |
| Tutorial | tutorial.status: NEW, IN_PROGRESS, COMPLETED, SKIPPED |
| Export only | config.format=1 and config.baseRevision; never copied into workspace.xml by apply |

`CONFIG-SCHEMA.json` contains the exact action titles, host actions/defaults,
enum values, all color roles (including all host colors, gradients, logo and glow),
layout grammar and constraints. Use `schema` for the running binary's authoritative
catalog rather than hardcoding a different release's definitions.

New profiles default to the user-requested host `10.1.2.3` with auto/remember true.
These are creation defaults, not migration replacements. Preserve existing
hosts (including blank) and explicit off choices unless asked to change them.
Host may be empty: an unconfigured profile. Device and username may be empty;
when supplied they follow IBM i naming constraints. auto=true with a blank username
is a valid unfinished profile, but cannot connect until a username is supplied.
Fixed-width fonts are checked against the configured code pages, with explicit
errors rather than silent replacement during config validation.
font.file takes precedence over font.family; clear font.file to choose a family.
Font bytes are never embedded or exported.

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
<entry key="shortcut.Next pane">ctrl TAB</entry>
<entry key="shortcut.Previous pane">ctrl shift TAB</entry>
```

Removing a shortcut entry restores that release's default. An explicit entry equal
to the default remains explicit across later migrations. Empty means unbound.
Conflicting primary bindings fail clearly; they are never silently dropped.
Application aliases (Ctrl+Insert copy, Ctrl+Shift+Insert/Shift+PageDown paste,
Ctrl+plus/keypad zoom) are available only when their action retains its default
and no custom host/application/reserved binding needs that chord.
Ctrl+Alt is reserved for application shortcuts. F1-F24 remain host functions.
Ctrl+C/V are default clipboard actions; legacy explicit conflicting host mappings
are preserved with a migration notice and the old app chord.

To add a named custom theme, increment appearance.themes, add a new contiguous
index and copy **all** color-role entries from an existing complete theme/catalog.
Give it a unique name, edit only requested colors, then select that name in
appearance.selected. Empty appearance.selected uses appTheme instead. Never
delete unrecognized color roles or convert a complete theme to a partial one.

Layouts use the catalog's prefix grammar: `L|pane-uuid|profile-uuid`, or
`H|ratio|first|second` / `V|ratio|first|second`. Preserve existing IDs and ratios
when not asked to rearrange. Use one to eight distinct pane IDs and only listed
profile IDs. Current and per-profile saved layouts must match Fish's bounded
shapes. Never invent pane IDs by reusing a profile ID or remove a profile that a
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

auto=true requests negotiated RFC 4777 **plain** automatic sign-on; connecting
requires a username and a 1-128 printable US-ASCII password supplied by the UI/OS store.
It does not inject credentials into guessed screen coordinates. Plain TN5250
TCP is unencrypted, cryptographic sign-on is not implemented, and real IBM i/ACS
acceptance remains unverified. A saved password or TCP connection is not proof of
successful IBM i sign-on. Do not enable automatic sign-in or sharing for an
unrelated host without the user's request.

## Tutorial, runtime and limits

Fresh workspaces start NEW and show the modeless guide once. Opening records
IN_PROGRESS; user Done/Skip records COMPLETED/SKIPPED. Interrupting the app midway
does not force the guide over the next session. Legacy upgrades use SKIPPED.
Help can replay it without modifying profiles/sockets or resetting the status.
Set NEW only when the user explicitly wants the first-run behavior again.

`connectOnStartup=false` is the fresh/upgrade default. The Session menu can enable
it, or set that exact boolean in an exported snapshot when requested. A normal
launch then attempts each restored pane once, using its own selected profile.
Manual sign-in profiles connect to the host sign-on screen; automatic sign-in
requires a saved OS password. Incomplete profiles, remembering disabled or missing
saved passwords remain offline with a pane notice, not password/profile dialogs.
Credential lookup failures and connection errors stay visible in the affected pane.
This never reconnects current work, connects newly added panes or repeats failed
attempts until the next launch/user Connect. Demo stays isolated to its loopback host.

Apply never launches Fish or connects, sends Enter/AIDs or replays terminal work.
A restart is required to use bulk edits. Host screen content, live socket/input
state, passwords, Windows snap placement and unsaved editor previews are not
exportable configuration. Native maximize/restore perimeter loss, intermittent
resize flicker and real IBM i/ACS acceptance remain separate open gates.
