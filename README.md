# Fish

Fish is a keyboard-driven 5250 terminal for Windows 10 and 11 (x64). Open up to eight IBM i sessions in one window.

**[Download Fish 0.10.4 BETA](https://github.com/tsafago/Fish/releases/tag/v0.10.4)** · [Quick guide](MANUAL.md) · [Compatibility and limitations](COMPATIBILITY.md)

## Install

Run `Fish-0.10.4-setup.exe` to install for your Windows account. No administrator access or separate Java installation is required. Installing for all users requires administrator approval.

For a portable copy, extract `Fish-0.10.4-windows-x64.zip` and open `Fish\Fish.exe`. Keep `Fish.exe` with its `app` and `runtime` folders. The release includes SHA-256 checksums. The beta is unsigned; follow your organization's software policy.

## Use Fish

Open **Edit > Settings > Profiles**, enter your host address, then choose the profile beside **+** and click **+**. Use **Help > Quick guide** for the basics or **Help > Keyboard reference** for your current shortcuts. Run `Fish.exe --demo` to try a local sample without connecting to an IBM i.

Use **Settings > Updates** to check for a new version. Back up `workspace.xml` before a downgrade. Existing desktop shortcuts still open their saved pane arrangements.

## Build and test

Start with `Setup-Fish.cmd` in the complete source download, or clone this repository with its submodule. The build uses the pinned JDK 25, OpenJFX 25 and Windows toolchain in `tools.psd1`.

```powershell
.\scripts\bootstrap.ps1
.\build.ps1 Maintenance
.\build.ps1 Check
.\build.ps1 Test
.\build.ps1 Package   -ImageDirectory dist\Fish-0.10.4\Fish
.\build.ps1 Installer -ImageDirectory dist\Fish-0.10.4\Fish
```

`Package` creates a self-contained app image. `Installer` adds setup, portable ZIP, installation scripts and checksums beside it. To preview the update window without installing anything, run `Fish-0.10.4-setup.exe --preview-update dark`.

The [configuration reference](AI-CONFIG.md) documents the offline `--config` CLI. `src\fish` contains the Java application; `src\core-adapter` integrates TN5250J and `src\native` contains the Windows bridges and setup. See [DESIGN.md](DESIGN.md) for implementation details.

GPL-2.0-or-later, with no warranty. Keep the matching source, notices and bundled runtime legal directory with redistributed builds. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Real IBM i/ACS interoperability and domain sign-in are not certified.
