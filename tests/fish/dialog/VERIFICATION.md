# Fish dialog verification

From the worktree root on an unlocked Windows x64 desktop:

```powershell
.\tests\fish\verify-dialogs.ps1
```

Requires matching built `build\classes` (including manual resources) and `build\fish-native.dll`. Coordinate the copy with whoever owns the build, or pass `-ClassDirectory <private-copy>` with the matching DLL beside those classes. This runner **does not build the product**. It copies inputs into `build\dialog-ui-<id>`, compiles the three test sources there with the worktree JDK/OpenJFX, and runs two sequential, bounded child JVMs with private homes, temp directories and synthetic stores. `result.log` and `stderr.log` live under each case directory. A failed requirement or inadequate desktop sampling is nonzero. Delete a completed run directory when no longer needed.

The full Charcoal pass uses the **unmodified monitor scale**. Paper repeats desktop shortcut settings, Appearance and workspace controls with a Glass scale override confined to that JVM (default 2.0; `-PaperScale 1.0|1.5|2.0`); `-DarkOnly` runs just the native case. Neither changes Windows DPI. The workspace has two disconnected panes, a nonexistent credential helper and a refusing in-memory update transport. Demo mode prevents exporting links. The test does not run Fish startup, send desktop input, contact a server or launch setup.

## What the assertions mean

* `FRAME` compares the actual Win32 outer/client dimensions, Stage/Scene bounds, caption width and X position at the measured DPI. The shared caption must span the scene. Owned dialogs do not show Minimize. Maximize is only offered on resizable modeless windows.
* `DESKTOP` is a read of one caption pixel in the displayed desktop, sampled before show through the initial presentation. Each reading must hit the fixture PID/window's root HWND. Samples are **not frame counts**; they can miss a shorter flash or a flash elsewhere. Dark: at least eight samples, none near-white (mean RGB ≥225) and a dark final sample. Paper: a light final sample. These are not scene snapshots, assertions about every pixel, or packaged-app proof.
* `KEY` events target Fish's live JavaFX focus owner. Enter activates the focused choice, Escape cancels, caption X and window close requests must call the actual dismissal handler. This does not test global keyboard hooks, the OS input queue, physical hit-testing or DWM snap.

## Inventory

| Production path | Shown / exercised in this fixture | Remaining boundary |
|---|---|---|
| `DesktopShortcutForm` in Settings > General | Name, desktop action and inline demo restriction; no recipe on refusal | Isolated link creation and UUID reuse are exercised separately by shortcut suites, not on the user's Desktop |
| `Preferences`, `PrimaryColorBar`, embedded `ProfilesEditor`/`KeyboardEditor`, `UpdateController` | Rail pages, minimum and maximize/restore, theme/palette/effect/cursor/font controls, hue/hex validation, header/footer disclosure, action search, kiosk/reset confirmations cancelled, offline update failure, Escape | Native font picker, actual install buttons and download/restart; not every checkbox or palette combination |
| `Workspace`, `WindowChrome`, `SessionPane` | Full menubar groups, minimum-size compact menu, Fish system menu/keyboard Move → Escape, pane and clipboard menus (no clipboard access), profile pill and disconnected pane selector, real tooltip, zoom HUD, hidden-footer warning and dismiss | Physical hover, dragging/resizing/snap, shell caption affordances, connected host stream, clipboard contents |
| `Workspace` forms and `Ui` prompts | Swap, arrange, switch workstation, send-host-key form (offline, cancelled); form selectors, generic confirm both Return choices, input cancellation, message/error close, last pane notice, keyboard reference | No live switches or host keys; interactive sign-on and host-dependent errors not driven here |
| `ProfilesDialog` | Password and Kerberos states, authentication selector, optional device, minimum, edit → Escape and check persisted name | No real password storage, Windows authentication or connection |
| `ManualDialog` | Quick guide, topic search/no results/clear, Ctrl+F, minimum, Escape returns focus to the disconnected pane selector | No external link opening |
| `TutorialDialog` | All six real guides, first-run flag for Getting started, next-step skip/back, minimum, Escape cleanup | Does not complete every guide's external or host action |
| `KeyboardReference`, `KeyRecorder` | Keys/actions search and selection, minimum, callback, recorded Ctrl+F9 followed by Return, recordable Escape contract | No OS-reserved physical chords or global capture |
| `WorkspaceRecovery` | Unreadable workspace, startup error, kiosk-blocked stages: default Exit, window close, Escape, callback outcome and retry availability | No reset, deletion, kiosk privilege/credential prompt or UAC |
| Native setup/updater and OS-owned windows | **Not run by this fixture.** Installer preview belongs to the separate native suite. | Installation, elevation/secure desktop, restart, sign-in Startup folder, native pickers, Explorer and Windows system menu need dedicated authorized OS acceptance |

Related existing suites (not counted as passes here): `MaintenanceUiSmoke` covers injected offer/download/cancel/launcher states; `ZeroTenFeaturesSmoke` covers synthetic host input inhibition/reset and isolated autostart; `SettingsTutorialSmoke` covers guide progression; `DpiAndLatencySmoke` covers splitters/terminal render geometry.

## Observed on 0.10.2

* **Geometry.** On the 3840×2160, 144-DPI display both JavaFX output and render
  scales were 1.50 without an override. The 500-unit Saved layouts window was
  750 physical pixels wide; its 728-pixel client, caption and X agreed. The
  collapsed → expanded → recollapsed window heights were 480 → 725 → 482 px.
  At process-only 200% they were 640 → 946 → 628 px. The last library action
  remained accessible at minimum size.
* **Keyboard.** Escape is recorded when the shortcut capture field has focus.
  Elsewhere it cancels without applying a destructive change. Return activates
  the focused choice; in a confirmation that initially means Cancel.
* **First paint.** The desktop probe found no near-white Charcoal caption samples
  in Settings or Saved layouts. In Paper, Settings sometimes sampled dark before
  settling light. The probe does not establish that every pixel of every dialog
  matched the final theme from the first compositor frame.
