# Shared by install-robot.ps1 and restore-robot.ps1: which adb to run, which robot to talk to.

# adb.exe: the one given, else the setup script's (C:\Toque), the Android SDK's, or the PATH's.
function Find-RobotAdb([string]$given) {
    if ($given -ne "") {
        if (Test-Path $given) { return $given }
        throw "adb not found: $given"
    }
    foreach ($c in @("C:\Toque\platform-tools\adb.exe", (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"))) {
        if (Test-Path $c) { return $c }
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "adb not found. Run tools\setup\toque-setup.ps1 -Step prepare (it downloads the Android platform tools), or pass -Adb <path to adb.exe>."
}

# Runs adb and returns everything it printed, as text. Windows PowerShell 5.1 turns what a native
# command writes on stderr into errors: keep going and read it as lines (adb reports progress there).
function Invoke-RobotAdb([string]$adb, [string[]]$arguments) {
    $ErrorActionPreference = "Continue"
    $out = & $adb @arguments 2>&1 | ForEach-Object { "$_" } | Out-String
    return $out.Trim()
}

# The serial of the robot to talk to. With an address: the robot on Wi-Fi (connected first).
# Without: the only device adb sees. Throws a sentence that says what is wrong otherwise.
function Select-Robot([string]$adb, [string]$ip) {
    $serial = ""
    if ($ip -ne "") {
        $serial = $ip
        if ($serial -notmatch ":\d+$") { $serial = "${serial}:5555" }
        Write-Host (Invoke-RobotAdb $adb @("connect", $serial))
    }
    $lines = @((Invoke-RobotAdb $adb @("devices")) -split "`r?`n" | Where-Object { $_ -match "`t" })
    if ($serial -ne "") { $lines = @($lines | Where-Object { ($_ -split "`t")[0].Trim() -eq $serial }) }
    if ($lines.Count -eq 0) {
        if ($serial -ne "") { throw "adb does not see the robot at $serial. Same Wi-Fi as this PC? ADB over Wi-Fi is switched on by toque-setup.ps1 -Step wifi." }
        throw "adb sees no robot. Plug the USB cable in once Android has started, or pass -Ip <address> for ADB over Wi-Fi."
    }
    if ($lines.Count -gt 1) {
        $names = ($lines | ForEach-Object { ($_ -split "`t")[0].Trim() }) -join ", "
        throw "adb sees several devices ($names): unplug the others, or pass -Ip <address of the robot>."
    }
    $parts = $lines[0] -split "`t"
    $state = $parts[1].Trim()
    if ($state -ne "device") { throw "adb sees the robot as '$state': accept the ADB prompt on its screen, or unplug and replug the cable." }
    return $parts[0].Trim()
}

# Scripts sent to the robot must have Unix line ends: its shell cannot run CRLF. This repository's
# .gitattributes keeps them so; a copy made another way may not.
function Assert-UnixLineEnds([string]$path) {
    if (-not (Test-Path $path)) { throw "File missing: $path" }
    if ([IO.File]::ReadAllBytes($path) -contains 13) { throw "$path has Windows line ends (CRLF): take the file from the repository again." }
}
