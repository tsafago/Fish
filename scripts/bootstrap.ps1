$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\prepare-tools.ps1" -Kind Jdk
Write-Host 'Portable JDK ready. Double-click Setup-Fish.cmd to build the executable and user-wide installer.'
