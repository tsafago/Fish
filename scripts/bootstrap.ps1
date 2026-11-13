$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\prepare-tools.ps1" -Kind Jdk
& "$PSScriptRoot\prepare-tools.ps1" -Kind JavafxSdk
& "$PSScriptRoot\prepare-tools.ps1" -Kind JavafxJmods
Write-Host 'Portable JDK 25 and OpenJFX 25 ready. Double-click Setup-Fish.cmd to build the executable and user-wide installer.'
