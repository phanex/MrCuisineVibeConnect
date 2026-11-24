# Installs Toque on the Monsieur Cuisine Connect (rooted, ADB enabled), and checks that it is there.
# Usage: powershell -ExecutionPolicy Bypass -File tools\install-robot.ps1 [-Apk path.apk] [-Ip 192.168.1.x] [-Adb path\adb.exe]
# Exit code 0 only when the APK now on the robot is the file given and the root setup finished.
param(
    [string]$Apk = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$Ip = "",
    [string]$Adb = ""
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "adb-robot.ps1")
function Stop-Install([string]$text) { Write-Host $text; exit 1 }

if (-not (Test-Path $Apk)) { Stop-Install "APK not found: $Apk (Build > Build APK in Android Studio, or download it from GitHub Releases)" }
$scripts = @{
    "restore.sh"    = "/data/local/tmp/toque-restore.sh"
    "setup-root.sh" = "/data/local/tmp/toque-setup-root.sh"
    "toque-su.sh"   = "/data/local/tmp/toque-su.sh"
}
try {
    foreach ($name in $scripts.Keys) { Assert-UnixLineEnds (Join-Path $PSScriptRoot "robot\$name") }
    $adbExe = Find-RobotAdb $Adb
    $robot = Select-Robot $adbExe $Ip
} catch { Stop-Install $_.Exception.Message }

$root = Invoke-RobotAdb $adbExe @("-s", $robot, "shell", "su -c 'id -u'")
if ($root -ne "0") { Stop-Install "No root access on the robot ($robot): install Magisk first (see docs\INSTALLATION.md). If Magisk asked on the robot's screen, tap Grant and run this again." }

Write-Host "Installing Toque on $robot : $Apk"
$out = Invoke-RobotAdb $adbExe @("-s", $robot, "install", "-r", $Apk)
Write-Host $out
if ($out -notmatch "(?m)^\s*Success\s*$") {
    if ($out -match "INSTALL_FAILED_VERSION_DOWNGRADE") { Stop-Install "The robot already runs a newer Toque than this file: nothing was changed. Use a newer APK (toque-setup.ps1 -Step prepare downloads the latest release) or pass -Apk <file>." }
    Stop-Install "Toque was not installed: nothing else was changed on the robot."
}

# The APK now on the robot must be the file that was sent.
$remote = @((Invoke-RobotAdb $adbExe @("-s", $robot, "shell", "pm path app.toque")) -split "`r?`n" | Where-Object { $_ -match "^package:" })
if ($remote.Count -eq 0) { Stop-Install "Toque is not on the robot after the install." }
$copy = Join-Path ([IO.Path]::GetTempPath()) ("toque-installed-" + [Guid]::NewGuid().ToString("N") + ".apk")
try {
    [void](Invoke-RobotAdb $adbExe @("-s", $robot, "pull", ($remote[0] -replace "^package:", "").Trim(), $copy))
    if (-not (Test-Path $copy)) { Stop-Install "Could not read the installed APK back from the robot to check it." }
    if ((Get-FileHash -Algorithm SHA256 -Path $copy).Hash -ne (Get-FileHash -Algorithm SHA256 -Path $Apk).Hash) { Stop-Install "The APK on the robot is not the file that was sent: run this again." }
} finally { if (Test-Path $copy) { Remove-Item -Force $copy } }
Write-Host "Toque on the robot = the file that was sent."

foreach ($name in $scripts.Keys) { [void](Invoke-RobotAdb $adbExe @("-s", $robot, "push", (Join-Path $PSScriptRoot "robot\$name"), $scripts[$name])) }
$out = Invoke-RobotAdb $adbExe @("-s", $robot, "shell", "su -c 'sh /data/local/tmp/toque-setup-root.sh'")
Write-Host $out
if ($out -notmatch "Boot script installed") { Stop-Install "The root setup did not finish (see the lines above): Toque is installed, but run this again before using it." }
[void](Invoke-RobotAdb $adbExe @("-s", $robot, "shell", "am start -n app.toque/.ui.MainActivity"))
Write-Host ""
Write-Host "Toque is running. On the robot:"
Write-Host "  1. Settings > Advanced > Machine > Real machine > Apply (first test: EMPTY bowl)."
Write-Host "  2. Toque now opens by itself when the robot starts (the robot has no Home button)."
Write-Host "  3. Settings > Lidl recipes > Sync."
Write-Host "To undo everything: tools\restore-robot.ps1"
