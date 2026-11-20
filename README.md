# Fish

Fish is a keyboard-driven 5250 terminal for Windows 10 and 11 (x64). Open up to eight IBM i sessions in one window.

**[Download Fish 0.10.5 BETA](https://github.com/tsafago/Fish/releases/tag/v0.10.5)** · [Quick guide](MANUAL.md) · [Compatibility and limitations](COMPATIBILITY.md)

## Install

Run `Fish-0.10.5-setup.exe` to install for your Windows account. No administrator access or separate Java installation is required. Installing for all users requires administrator approval.

For a portable copy, extract `Fish-0.10.5-windows-x64.zip` and open `Fish\Fish.exe`. Keep `Fish.exe` with its `app` and `runtime` folders. The release includes SHA-256 checksums. The beta is unsigned; follow your organization's software policy.

Use **Windows Installed apps** for an MSI installation. For a PowerShell installation, use its matching `Uninstall-Fish.ps1`. New uninstallers keep settings, saved layouts and credentials; they do not close running programs or remove other Fish installations. Close the copy of Fish you are uninstalling first.

`Install-Fish.ps1` records its files in `.fish-script-image`. A rerun replaces a clean script installation, but refuses a mixed or unidentified folder rather than deleting personal files. Use an empty destination in that case. When the new uninstaller finds an older registered script installation without an inventory, it removes the known launchers and leaves unlisted files for manual review. These safeguards do not change previously shipped uninstallers.

If `Fish.exe` reports a missing `app\Fish.cfg`, close Fish and run setup again. You do not need to uninstall first. Setup repairs the installed image and verifies the launcher, config, app and runtime before opening Fish. It keeps its MSI source in `Fish Setup Sources` under Local AppData (Program Files for all-users installs); leave it there for future repairs. Error **1612** means Windows needs the original installation package, not that another installer is busy. The 0.10.5 release installer can restore the exact public 0.10.4 MSI offline. If setup cannot verify the installation or finds personal files a legacy uninstaller could remove, it stops rather than deleting them. Use the portable image if you need Fish immediately.

On failure, **Details** shows the step, path and Windows error. If setup recognizes an unregistered, incomplete copy in the standard per-user Fish folder, **Set aside** asks permission to rename it to a backup beside the new installation. Setup restores the old folder if the attempt fails and the destination is clear. If Windows Installer is still running or leaves files behind, setup keeps the backup and shows where it is. Setup never deletes that backup.

## Use Fish

Open **Edit > Settings > Profiles**, enter your host address, then choose the profile beside **+** and click **+**. Use **Help > Quick guide** for the basics or **Help > Keyboard reference** for your current shortcuts. Run `Fish.exe --demo` to try a local sample without connecting to an IBM i.

Use **Settings > Updates** to check for a new version. Back up `workspace.xml` before a downgrade. Existing desktop shortcuts still open their saved pane arrangements.

### Connection security

Fish uses plain TN5250 for the terminal. With password sign-in, both typed and automatic sign-in passwords can be read on the network. On an IBM i already configured for Kerberos, choose **Windows Kerberos** to send a service ticket instead of a password. Kerberos authenticates the sign-in; it does not encrypt the terminal session.

[IBM i Access Client Solutions](https://www.ibm.com/support/pages/how-configure-ibm-i-access-client-solutions-client-use-tlsssl) normally inherits the system connection setting: plain Telnet usually uses port 23; TLS usually uses port 992 and needs a TLS-enabled server and a trusted certificate. [IBM also supports TN5250E password substitution](https://www.ibm.com/docs/it/i/7.2.0?topic=security-controlling-telnet-access) on compatible hosts. It protects the automatic sign-in password, not the session; Fish does not implement that substitution. There is no client-only upgrade to an encrypted session on a plain Telnet endpoint. Use a trusted network, or a VPN that covers the route to the host. For end-to-end encryption, use a TLS-capable client with a TLS-enabled IBM i.

## Build and test

Start with `Setup-Fish.cmd` in the complete source download, or clone this repository with its submodule. The build uses the pinned JDK 25, OpenJFX 25 and Windows toolchain in `tools.psd1`.

```powershell
.\scripts\bootstrap.ps1
.\build.ps1 Maintenance
.\build.ps1 Check
.\build.ps1 Test
.\build.ps1 Package   -ImageDirectory dist\Fish-0.10.5\Fish
.\build.ps1 Installer -ImageDirectory dist\Fish-0.10.5\Fish
```

`Package` creates a self-contained app image. `Installer` adds setup, portable ZIP, installation scripts and checksums beside it. Setup and updates always use the light theme. To preview the update window without installing anything, run `Fish-0.10.5-setup.exe --preview-update`.

The [configuration reference](AI-CONFIG.md) documents the offline `--config` CLI. `src\fish` contains the Java application; `src\core-adapter` integrates TN5250J and `src\native` contains the Windows bridges and setup. See [DESIGN.md](DESIGN.md) for implementation details.

GPL-2.0-or-later, with no warranty. Keep the matching source, notices and bundled runtime legal directory with redistributed builds. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Real IBM i/ACS interoperability and domain sign-in are not certified.
