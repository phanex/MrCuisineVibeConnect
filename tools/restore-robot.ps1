# Gives control back to the stock Monsieur Cuisine app and removes Toque.
# Usage: powershell -ExecutionPolicy Bypass -File tools\restore-robot.ps1 [-Ip 192.168.1.x] [-Uninstall] [-Adb path\adb.exe]
param([string]$Ip = "", [switch]$Uninstall, [string]$Adb = "")
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "adb-robot.ps1")
function Stop-Restore([string]$text) { Write-Host $text; exit 1 }

$script = Join-Path $PSScriptRoot "robot\restore.sh"
try {
    Assert-UnixLineEnds $script
    $adbExe = Find-RobotAdb $Adb
    $robot = Select-Robot $adbExe $Ip
} catch { Stop-Restore $_.Exception.Message }
[void](Invoke-RobotAdb $adbExe @("-s", $robot, "push", $script, "/data/local/tmp/toque-restore.sh"))
$out = Invoke-RobotAdb $adbExe @("-s", $robot, "shell", "su -c 'sh /data/local/tmp/toque-restore.sh'")
Write-Host $out
if ($out -notmatch "Stock app restored") { Stop-Restore "The stock app was not restored (no root access, or the script did not run): see the lines above. Nothing else was done." }
if ($Uninstall) {
    # The boot script that keeps Toque allowed in Magisk and opens it first (written by setup-root.sh).
    [void](Invoke-RobotAdb $adbExe @("-s", $robot, "shell", "su -c 'rm -f /data/adb/service.d/toque-su.sh'"))
    Write-Host (Invoke-RobotAdb $adbExe @("-s", $robot, "uninstall", "app.toque"))
}
[void](Invoke-RobotAdb $adbExe @("-s", $robot, "reboot"))
Write-Host "Robot restarted on the stock app."
