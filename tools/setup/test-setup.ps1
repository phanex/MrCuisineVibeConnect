# Tests of the setup scripts that need no robot, no cable and no download. They build small boot
# images in a temporary folder, put a fake robot in front of the scripts, and check the decisions
# the flash steps take and what the checks after a flash accept or refuse.
#
#   powershell -ExecutionPolicy Bypass -File tools\setup\test-setup.ps1
#
# Exit code 0 when every test passes. Nothing here can reach a real robot: adb and SP Flash Tool
# are replaced before any step runs.
$ErrorActionPreference = "Stop"
$TestRoot = Join-Path ([IO.Path]::GetTempPath()) ("toque-setup-test-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $TestRoot | Out-Null
$Tools = Split-Path $PSScriptRoot

. (Join-Path $PSScriptRoot "toque-setup.ps1") -Work (Join-Path $TestRoot "not-used")
. (Join-Path $Tools "adb-robot.ps1")
# Small partitions: the images built here are a few kilobytes, and the tests write many of them.
$PartitionSize = 262144

# ---------------------------------------------------------------- the script's doors to the outside, replaced

$script:Said = New-Object System.Collections.Generic.List[string]
function Say([string]$text) { $script:Said.Add($text) }
function Fail([string]$text) { throw ("STOP: " + $text) }
function NeedHuman([string]$text) { throw ("HUMAN: " + $text) }
function Start-Sleep { }

# The fake robot: what "adb devices" shows, what its shell answers (first pattern that matches),
# which files "adb pull" finds. Every adb call is kept in $AdbCalls.
$script:Robot = @{ Serial = "TESTROBOT"; State = "device"; Shell = [ordered]@{}; Files = @{} }
$script:AdbCalls = New-Object System.Collections.Generic.List[string]
function Adb {
    $script:AdbCalls.Add(($args -join " "))
    if ($args[0] -eq "devices") { return ("List of devices attached`n" + $script:Robot.Serial + "`t" + $script:Robot.State) }
    if ($args[0] -eq "pull") {
        $src = $script:Robot.Files[[string]$args[1]]
        if (-not $src) { return ("adb: error: remote object '" + $args[1] + "' does not exist") }
        if (Test-Path $src -PathType Container) { New-Item -ItemType Directory -Force -Path $args[2] | Out-Null }
        else { Copy-Item $src $args[2] -Force }
        return "1 file pulled"
    }
    if ($args[0] -eq "shell") {
        foreach ($k in $script:Robot.Shell.Keys) { if ($args[1] -like $k) { return $script:Robot.Shell[$k] } }
    }
    return ""
}

# SP Flash Tool never starts: the configuration it would have been given is kept instead.
$script:Flashed = New-Object System.Collections.Generic.List[string]
$script:FlashExit = 0
$RealInvokeFlashTool = ${function:Invoke-FlashTool}
function Invoke-FlashTool([string]$config, [string]$what) { $script:Flashed.Add($config); return $script:FlashExit }

# ---------------------------------------------------------------- small boot images

function Join-Bytes {
    $ms = New-Object IO.MemoryStream
    foreach ($a in $args) { $b = [byte[]]$a; $ms.Write($b, 0, $b.Length) }
    return , $ms.ToArray()
}
function New-Gzip([byte[]]$data) {
    $ms = New-Object IO.MemoryStream
    $gz = New-Object IO.Compression.GZipStream -ArgumentList @($ms, [IO.Compression.CompressionMode]::Compress)
    $gz.Write($data, 0, $data.Length)
    $gz.Dispose()
    return , $ms.ToArray()
}
# An ARM zImage as far as the script looks: its magic, a gzip payload with the kernel's version
# line and its project, and something after it (the device tree).
function New-Kernel([string]$project, [string]$build) {
    $text = "junk`0Linux version 3.18.19 (test@pc) (gcc version 4.8 (GCC) ) #1 SMP PREEMPT $build`0"
    if ($project -ne "") { $text += "kernel-3.18/drivers/misc/mediatek/imgsensor/src/mt6580/camera_project/$project/camera_hw/kd_camera_hw.c`0" }
    $head = New-Object byte[] 64
    [Array]::Copy([BitConverter]::GetBytes([int]0x016F2818), 0, $head, 0x24, 4)
    return , (Join-Bytes $head (New-Gzip ([Text.Encoding]::ASCII.GetBytes($text + ("x" * 3000)))) ([Text.Encoding]::ASCII.GetBytes("device-tree-of-" + $project)))
}
function New-Ramdisk([string]$device, [string]$extra = "") {
    $text = "070701 default.prop`0ro.secure=1`n"
    if ($device -ne "") { $text += "ro.bootimage.build.fingerprint=alps/full_$device/${device}:6.0/MRA58K/1663236601:user/test-keys`n" }
    return , (New-Gzip ([Text.Encoding]::ASCII.GetBytes($text + $extra)))
}
# Header version 0, 2048-byte pages, the load addresses of the real images. $size 0: no padding.
function New-BootImage([byte[]]$kernel, [byte[]]$ramdisk, [long]$size = 0, [string]$cmdline = "bootopt=64S3,32S1,32S1") {
    $page = 2048
    $kPad = [long][Math]::Ceiling($kernel.Length / $page) * $page
    $rPad = [long][Math]::Ceiling($ramdisk.Length / $page) * $page
    $len = $page + $kPad + $rPad
    if ($size -gt 0) { $len = $size }
    $img = New-Object byte[] $len
    [Array]::Copy([Text.Encoding]::ASCII.GetBytes("ANDROID!"), 0, $img, 0, 8)
    [Array]::Copy([BitConverter]::GetBytes([uint32]$kernel.Length), 0, $img, 8, 4)
    [Array]::Copy([BitConverter]::GetBytes([int]0x80008000), 0, $img, 12, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint32]$ramdisk.Length), 0, $img, 16, 4)
    [Array]::Copy([BitConverter]::GetBytes([int]0x84000000), 0, $img, 20, 4)
    [Array]::Copy([BitConverter]::GetBytes([int]0x80F00000), 0, $img, 28, 4)
    [Array]::Copy([BitConverter]::GetBytes([int]0x8E000000), 0, $img, 32, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint32]$page), 0, $img, 36, 4)
    $c = [Text.Encoding]::ASCII.GetBytes($cmdline)
    [Array]::Copy($c, 0, $img, 64, $c.Length)
    [Array]::Copy($kernel, 0, $img, $page, $kernel.Length)
    [Array]::Copy($ramdisk, 0, $img, $page + $kPad, $ramdisk.Length)
    return , $img
}
# A partition as read back from the robot: the image, then what was there before.
function New-Dump([string]$image, [string]$path) {
    $b = [IO.File]::ReadAllBytes($image)
    $d = New-Object byte[] $PartitionSize
    for ($i = $b.Length; $i -lt $d.Length; $i += 4096) { $d[$i] = 0x5A }
    [Array]::Copy($b, 0, $d, 0, $b.Length)
    [IO.File]::WriteAllBytes($path, $d)
    return $path
}

# A work folder as it is after 'prepare' and 'backup', for a robot of this kernel project and
# Android device ("same": the very kernel of the guide's TWRP).
function New-Work([string]$name, [string]$project, [string]$device) {
    $w = Join-Path $TestRoot $name
    $script:Work = $w
    $script:Downloads = Join-Path $w "downloads"
    $script:Backup = Join-Path $w "backup"
    $script:StateFile = Join-Path $w "state.json"
    $script:Log = Join-Path $w "setup-log.txt"
    New-Item -ItemType Directory -Force -Path $w, $Downloads, $Backup, (Join-Path $w "hack"), (Join-Path $w "SP_Flash_Tool") | Out-Null
    $script:Said.Clear(); $script:AdbCalls.Clear(); $script:Flashed.Clear(); $script:FlashExit = 0
    $script:Robot = @{ Serial = "TESTROBOT"; State = "device"; Shell = [ordered]@{}; Files = @{} }

    $script:TwrpKernel = New-Kernel "l706_dfbh_v_695" "Tue Apr 24 12:46:18 CST 2018"
    $script:TwrpRamdisk = New-Ramdisk "l706_dfbh_v_695" "this is TWRP"
    $guide = Join-Path $w "hack\recovery.img"
    [IO.File]::WriteAllBytes($guide, (New-BootImage $TwrpKernel $TwrpRamdisk 0 "bootopt=64S3,32S1,32S1 androidboot.selinux=permissive"))
    $Known.Twrp.Sha = Sha $guide
    Set-Content -Path (Join-Path $w "hack\l706_dfbh_v_695scatter.txt") -Value "scatter"
    Set-Content -Path (Join-Path $w "SP_Flash_Tool\flash_tool.exe") -Value "not a program"
    Set-Content -Path (Join-Path $w "SP_Flash_Tool\MTK_AllInOne_DA.bin") -Value "da"
    $magisk = Join-Path $Downloads "Magisk-v31.0.apk"
    Set-Content -Path $magisk -Value "magisk"
    $Known.Magisk.Sha = Sha $magisk

    $script:RobotKernel = $TwrpKernel
    if ($project -ne "same") { $script:RobotKernel = New-Kernel $project "Thu Sep 15 19:02:36 CST 2022" }
    $boot = Join-Path $Backup "boot-original.img"
    $rec = Join-Path $Backup "recovery-original.img"
    [IO.File]::WriteAllBytes($boot, (New-BootImage $RobotKernel (New-Ramdisk $device) $PartitionSize))
    [IO.File]::WriteAllBytes($rec, (New-BootImage $RobotKernel (New-Ramdisk $device "stock recovery") $PartitionSize))
    Set-State "boot-original-sha256" (Sha $boot)
    Set-State "recovery-original-sha256" (Sha $rec)
    Set-State "done-backup" "2026-01-01T00:00:00"
    return $w
}

# The image a configuration written for SP Flash Tool sends to a partition ("" when it is off).
function Get-ConfigRom([string]$config, [int]$index) {
    $x = [xml](Get-Content $config -Raw)
    foreach ($rom in $x.'flashtool-config'.general.'rom-list'.rom) {
        if ([int]$rom.index -eq $index) { if ($rom.enable -eq "true") { return $rom.'#text' } else { return "" } }
    }
    throw "no rom $index in $config"
}
function Assert-OnlyBootAndRecovery([string]$config, [string]$boot, [string]$recovery) {
    Assert ((Get-ConfigRom $config 8) -eq $boot) ("boot <- " + (Get-ConfigRom $config 8))
    Assert ((Get-ConfigRom $config 9) -eq $recovery) ("recovery <- " + (Get-ConfigRom $config 9))
    foreach ($i in 0, 7, 11, 17, 19, 20, 21) { Assert ((Get-ConfigRom $config $i) -eq "") "partition $i must stay off" }
    Assert ((Get-Content $config -Raw) -match "<download-only />") "Download Only"
}

# ---------------------------------------------------------------- harness

$script:Passed = 0
$script:Failures = @()
function Assert($condition, [string]$what) { if (-not $condition) { throw ("expected: " + $what) } }
function Assert-Stops([scriptblock]$body, [string]$pattern) {
    $message = $null
    try { & $body } catch { $message = $_.Exception.Message }
    if ($null -eq $message) { throw "expected a stop, the step went on" }
    if ($message -notmatch $pattern) { throw ("stopped with another message: " + $message) }
}
function Test([string]$name, [scriptblock]$body) {
    try { & $body; $script:Passed++; Write-Host ("ok    " + $name) }
    catch { $script:Failures += $name; Write-Host ("FAIL  " + $name + "`n      " + $_.Exception.Message) }
}
function Said([string]$pattern) { return [bool]($script:Said | Where-Object { $_ -match $pattern }) }

# ---------------------------------------------------------------- what an image says

Test "a kernel gives its version and its project" {
    $f = Get-KernelFacts (New-Kernel "spm188a" "Thu Sep 15 19:02:36 CST 2022")
    Assert ($f.Version -match "^Linux version 3\.18\.19 .* Thu Sep 15 19:02:36 CST 2022$") $f.Version
    Assert ($f.Project -eq "spm188a") $f.Project
    Assert ((Get-KernelFacts (New-Kernel "" "x")).Project -eq "") "no project"
    Assert ((Get-KernelFacts (New-Object byte[] 5000)).Version -eq "(version not found)") "not a kernel"
}
Test "a ramdisk gives the Android device" {
    Assert ((Get-RamdiskDevice (New-Ramdisk "spm188a")) -eq "spm188a") "device"
    Assert ((Get-RamdiskDevice (New-Ramdisk "")) -eq "") "no fingerprint"
    Assert ((Get-RamdiskDevice ([byte[]](1, 2, 3, 4))) -eq "") "not gzip"
}
Test "the robot is recognised from its boot backup" {
    [void](New-Work "recognise-1200" "l706_dfbh_v_695" "l706_dfbh_v_695")
    $r = Get-Robot
    Assert ($r.Name -eq "SKMC 1200" -and $r.Rule -eq "guide") (Format-Robot $r)
    [void](New-Work "recognise-1100" "spm188a" "spm188a")
    $r = Get-Robot
    Assert ($r.Name -eq "SKMC 1100 A1" -and $r.Rule -eq "own-kernel") (Format-Robot $r)
    Assert ((Format-Robot $r) -match "SKMC 1100 A1 \(project spm188a; Linux version") (Format-Robot $r)
    [void](New-Work "recognise-device" "" "spm188a")
    Assert ((Get-Robot).Name -eq "SKMC 1100 A1") "the Android device is used when the kernel names no project"
    [void](New-Work "recognise-unknown" "abc123" "abc123")
    $r = Get-Robot
    Assert ($r.Name -eq "" -and $r.Rule -eq "" -and $r.Project -eq "abc123") (Format-Robot $r)
}
Test "a backup that changed since 'backup' stops everything" {
    $w = New-Work "backup-changed" "spm188a" "spm188a"
    $boot = Join-Path $Backup "boot-original.img"
    $b = [IO.File]::ReadAllBytes($boot); $b[5000] = $b[5000] -bxor 1; [IO.File]::WriteAllBytes($boot, $b)
    Assert-Stops { Get-Robot } "no longer matches the checksum"
    Assert-Stops { Step-FlashTwrp } "no longer matches the checksum"
    Assert ($Flashed.Count -eq 0) "nothing flashed"
}

# ---------------------------------------------------------------- which TWRP

Test "SKMC 1200: the guide's TWRP" {
    [void](New-Work "choice-1200" "l706_dfbh_v_695" "l706_dfbh_v_695")
    $robot = Get-Robot
    Assert ((Get-TwrpChoice $robot) -eq "guide") "guide"
    Assert ((Get-TwrpImage $robot) -eq (Join-Path $Work "hack\recovery.img")) "the guide's file"
    Assert (-not (Test-Path (Join-Path $Work "twrp-own-kernel.img"))) "nothing built"
    $Twrp = "own-kernel"
    Assert ((Get-TwrpChoice $robot) -eq "own-kernel") "asked for explicitly"
}
Test "SKMC 1100 A1: its own kernel, built when needed, never the guide's" {
    [void](New-Work "choice-1100" "spm188a" "spm188a")
    $robot = Get-Robot
    Assert ((Get-TwrpChoice $robot) -eq "own-kernel") "own kernel"
    $img = Get-TwrpImage $robot
    Assert ($img -eq (Join-Path $Work "twrp-own-kernel.img") -and (Test-Path $img)) "built"
    $o = Read-BootImage $img "own"
    Assert ((Get-BytesSha $o.Kernel) -eq (Get-BytesSha $RobotKernel)) "the robot's kernel"
    Assert ((Get-BytesSha $o.Ramdisk) -eq (Get-BytesSha $TwrpRamdisk)) "TWRP's ramdisk"
    $guide = [IO.File]::ReadAllBytes((Join-Path $Work "hack\recovery.img"))
    $own = [IO.File]::ReadAllBytes($img)
    Assert ([Text.Encoding]::ASCII.GetString($own, 64, 60) -eq [Text.Encoding]::ASCII.GetString($guide, 64, 60)) "TWRP's command line"
    Assert ((Get-BytesSha $own) -ne (Get-BytesSha $guide)) "not the guide's image"
    $Twrp = "guide"
    Assert-Stops { Get-TwrpChoice $robot } "does not suit this robot"
    Assert-Stops { Get-TwrpImage $robot } "does not suit this robot"
}
Test "the built image is reused, and built again when it is not the right one any more" {
    [void](New-Work "reuse" "spm188a" "spm188a")
    $robot = Get-Robot
    $img = Get-TwrpImage $robot
    $first = Sha $img
    (Get-Item $img).LastWriteTime = (Get-Date).AddDays(-2)
    $stamp = (Get-Item $img).LastWriteTime
    [void](Get-TwrpImage $robot)
    Assert ((Get-Item $img).LastWriteTime -eq $stamp) "reused as it is"
    $b = [IO.File]::ReadAllBytes($img); $b[3000] = $b[3000] -bxor 1; [IO.File]::WriteAllBytes($img, $b)
    [void](Get-TwrpImage $robot)
    Assert ((Sha $img) -eq $first) "a damaged file is built again, identical"
    Remove-Item -Force $img
    [void](Get-TwrpImage $robot)
    Assert ((Sha $img) -eq $first) "a missing file is built again, identical"
}
Test "a state file from an earlier version (key twrp-image) cannot select the wrong image" {
    [void](New-Work "old-state" "spm188a" "spm188a")
    Set-State "twrp-image" "C:\Toque\custom\twrp-skmc1100a1.img"
    foreach ($k in "prepare", "driver", "flash-twrp", "root", "flash-root", "android", "install", "wifi") { Set-State "done-$k" "2026-01-01T00:00:00" }
    $robot = Get-Robot
    Assert ((Get-TwrpChoice $robot) -eq "own-kernel") "own kernel"
    Assert ((Split-Path (Get-TwrpImage $robot) -Leaf) -eq "twrp-own-kernel.img") "the image built for this robot"
    $script:Said.Clear()
    Step-Status
    Assert (Said "Robot, from its boot backup: SKMC 1100 A1") "status names the robot"
    Assert (Said "TWRP image for the flash steps: twrp-own-kernel.img") "status names the image"
}
Test "an unknown robot: nothing is chosen for the person" {
    [void](New-Work "unknown" "abc123" "abc123")
    $robot = Get-Robot
    Assert-Stops { Get-TwrpChoice $robot } "not one this guide knows.*-Twrp own-kernel.*-Twrp guide"
    Assert-Stops { Step-FlashTwrp } "not one this guide knows"
    Assert ($Flashed.Count -eq 0) "nothing flashed"
    $script:Said.Clear()
    Step-Status
    Assert (Said "not chosen yet") "status says so"
    $Twrp = "own-kernel"
    Step-FlashTwrp
    Assert ($Flashed.Count -eq 1) "flashed once the person chose"
    Assert-OnlyBootAndRecovery $Flashed[0] (Join-Path $Work "twrp-own-kernel.img") ""
    $Twrp = "auto"
    Assert ((Get-TwrpChoice (Get-Robot)) -eq "own-kernel") "the later steps remember the choice"
}
Test "an unknown robot that runs the very kernel of the guide's TWRP" {
    [void](New-Work "unknown-same-kernel" "same" "")
    Remove-Item -Force (Join-Path $Backup "boot-original.img")
    $k = New-Kernel "" "Tue Apr 24 12:46:18 CST 2018"
    $guide = Join-Path $Work "hack\recovery.img"
    [IO.File]::WriteAllBytes($guide, (New-BootImage $k $TwrpRamdisk))
    $Known.Twrp.Sha = Sha $guide
    $boot = Join-Path $Backup "boot-original.img"
    [IO.File]::WriteAllBytes($boot, (New-BootImage $k (New-Ramdisk "") $PartitionSize))
    Set-State "boot-original-sha256" (Sha $boot)
    $robot = Get-Robot
    Assert ($robot.Name -eq "") "unknown"
    Assert ((Get-TwrpChoice $robot) -eq "guide") "same kernel: the guide's image"
}
Test "an install begun with the earlier script goes on with the image it wrote" {
    [void](New-Work "legacy-guide" "abc123" "abc123")
    Set-State "done-flash-twrp" "2026-01-01T00:00:00"
    Assert ((Get-TwrpChoice (Get-Robot)) -eq "guide") "no own-twrp then: the guide's"
    [void](New-Work "legacy-own" "l706_dfbh_v_695" "l706_dfbh_v_695")
    Set-State "done-flash-twrp" "2026-01-01T00:00:00"
    Set-State "twrp-own-kernel-sha256" "0000"
    Assert ((Get-TwrpChoice (Get-Robot)) -eq "own-kernel") "own-twrp had been run: its image"
}
Test "own-twrp: says what the flash steps will do with the image" {
    [void](New-Work "own-1100" "spm188a" "spm188a")
    Step-OwnTwrp
    Assert (Test-Path (Join-Path $Work "twrp-own-kernel.img")) "built"
    Assert (Said "'flash-twrp' and 'flash-root' will write this image") "1100"
    [void](New-Work "own-1200" "l706_dfbh_v_695" "l706_dfbh_v_695")
    Step-OwnTwrp
    Assert (Said "keep the guide's image") "1200: built, but not selected"
    [void](New-Work "own-same" "same" "l706_dfbh_v_695")
    Step-OwnTwrp
    Assert (Said "nothing to build") "same kernel"
    Assert (-not (Test-Path (Join-Path $Work "twrp-own-kernel.img"))) "nothing written"
}

# ---------------------------------------------------------------- the flash steps

Test "flash-twrp writes boot only, with the image of this robot" {
    [void](New-Work "flash-twrp-1100" "spm188a" "spm188a")
    Step-FlashTwrp
    Assert-OnlyBootAndRecovery $Flashed[0] (Join-Path $Work "twrp-own-kernel.img") ""
    Assert ((Get-State)["twrp-choice"] -eq "own-kernel") "choice kept"
    Assert (Said "Robot: SKMC 1100 A1") "robot named"
    [void](New-Work "flash-twrp-1200" "l706_dfbh_v_695" "l706_dfbh_v_695")
    Step-FlashTwrp
    Assert-OnlyBootAndRecovery $Flashed[0] (Join-Path $Work "hack\recovery.img") ""
    Assert ((Get-State).ContainsKey("done-flash-twrp")) "marked done"
}
Test "flash-twrp: an error of the tool is not a success" {
    [void](New-Work "flash-twrp-error" "l706_dfbh_v_695" "l706_dfbh_v_695")
    $script:FlashExit = 3
    Assert-Stops { Step-FlashTwrp } "SP Flash Tool reported an error \(3\)"
    Assert (-not (Get-State).ContainsKey("done-flash-twrp")) "not marked done"
}
Test "the output of SP Flash Tool goes to spft-<step>-out.txt" {
    $w = New-Work "tool-output" "l706_dfbh_v_695" "l706_dfbh_v_695"
    Copy-Item (Join-Path $env:SystemRoot "System32\hostname.exe") (Join-Path $w "SP_Flash_Tool\flash_tool.exe") -Force
    $cfg = Write-DownloadConfig "flash-twrp" (Join-Path $w "hack\recovery.img") ""
    [void](& $RealInvokeFlashTool $cfg "test")
    Assert (Test-Path (Join-Path $w "spft-flash-twrp-out.txt")) "spft-flash-twrp-out.txt"
    Assert (-not (Test-Path (Join-Path $w "spft-spft-flash-twrp-out.txt"))) "no doubled prefix"
}

function Set-TwrpRobot([string]$bootHolds) {
    $script:Robot.State = "recovery"
    $script:Robot.Shell["cat /proc/uptime"] = "30.00 10.00"
    $script:Robot.Shell["sh /tmp/twrp-root.sh"] = "== 1. Magisk`n== Done. Next: adb pull"
    if ($bootHolds -ne "") { $script:Robot.Files["/tmp/toque-boot-check.img"] = (New-Dump $bootHolds (Join-Path $TestRoot ("dump-" + [Guid]::NewGuid().ToString("N") + ".img"))) }
    $patched = Join-Path $TestRoot ("patched-" + [Guid]::NewGuid().ToString("N") + ".img")
    [IO.File]::WriteAllBytes($patched, (New-BootImage $RobotKernel (New-Ramdisk "magisk") $PartitionSize))
    $script:Robot.Files["/tmp/magisk/new-boot.img"] = $patched
    $script:Robot.Files["/tmp/orig"] = $TestRoot
}
Test "root: goes on when boot holds the TWRP this robot needs" {
    [void](New-Work "root-ok" "spm188a" "spm188a")
    Set-TwrpRobot (Get-TwrpImage (Get-Robot))
    Step-Root
    Assert (Said "boot partition = twrp-own-kernel.img") "checked"
    Assert ((Get-State).ContainsKey("done-root")) "done"
    Assert ((Get-State)["boot-magisk-sha256"] -eq (Sha (Join-Path $Work "boot-magisk.img"))) "checksum kept"
}
Test "root: stops before changing anything when boot holds another TWRP" {
    [void](New-Work "root-wrong" "spm188a" "spm188a")
    Set-TwrpRobot (Join-Path $Work "hack\recovery.img")
    Assert-Stops { Step-Root } "does not hold twrp-own-kernel.img.*another kernel \(Linux version 3\.18\.19 .* 2018\).*Nothing was changed"
    Assert (-not ($AdbCalls | Where-Object { $_ -match "^push " })) "nothing was sent to the robot"
    Assert (-not (Get-State).ContainsKey("done-root")) "not done"
}
Test "root: a boot that cannot be read back is a warning, TWRP being up" {
    [void](New-Work "root-unreadable" "l706_dfbh_v_695" "l706_dfbh_v_695")
    Set-TwrpRobot ""
    Step-Root
    Assert (Said "WARNING: could not read the boot partition back") "warned"
    Assert ((Get-State).ContainsKey("done-root")) "done"
}
Test "root: a TWRP that has run too long is not used" {
    [void](New-Work "root-late" "l706_dfbh_v_695" "l706_dfbh_v_695")
    Set-TwrpRobot (Join-Path $Work "hack\recovery.img")
    $script:Robot.Shell["cat /proc/uptime"] = "320.50 10.00"
    Assert-Stops { Step-Root } "HUMAN: TWRP has been running"
    Assert (-not ($AdbCalls | Where-Object { $_ -match "^push |toque-boot-check" })) "nothing was done on the robot"
}

function Set-RootDone {
    $patched = Join-Path $Work "boot-magisk.img"
    [IO.File]::WriteAllBytes($patched, (New-BootImage $RobotKernel (New-Ramdisk "magisk") $PartitionSize))
    Set-State "boot-magisk-sha256" (Sha $patched)
    Set-State "done-root" "2026-01-01T00:00:00"
    return $patched
}
Test "flash-root writes boot and recovery, nothing else" {
    [void](New-Work "flash-root-1100" "spm188a" "spm188a")
    $patched = Set-RootDone
    Step-FlashRoot
    Assert-OnlyBootAndRecovery $Flashed[0] $patched (Join-Path $Work "twrp-own-kernel.img")
    [void](New-Work "flash-root-1200" "l706_dfbh_v_695" "l706_dfbh_v_695")
    $patched = Set-RootDone
    Step-FlashRoot
    Assert-OnlyBootAndRecovery $Flashed[0] $patched (Join-Path $Work "hack\recovery.img")
}
Test "flash-root: a boot-magisk.img that changed since 'root' is not written" {
    [void](New-Work "flash-root-changed" "l706_dfbh_v_695" "l706_dfbh_v_695")
    $patched = Set-RootDone
    $b = [IO.File]::ReadAllBytes($patched); $b[9000] = $b[9000] -bxor 1; [IO.File]::WriteAllBytes($patched, $b)
    Assert-Stops { Step-FlashRoot } "no longer matches the checksum recorded by 'root'"
    Assert ($Flashed.Count -eq 0) "nothing flashed"
}

function Set-AndroidRobot([string]$bootHolds, [string]$recoveryHolds) {
    $script:Robot.State = "device"
    $script:Robot.Shell["magisk -v"] = "31.0:MAGISK:R"
    $script:Robot.Shell["pm list packages*"] = "package:com.topjohnwu.magisk"
    $script:Robot.Shell["su -c 'id -u'"] = "0"
    if ($bootHolds -ne "") { $script:Robot.Files["/data/local/tmp/toque-boot-check.img"] = $bootHolds }
    if ($recoveryHolds -ne "") { $script:Robot.Files["/data/local/tmp/toque-recovery-check.img"] = (New-Dump $recoveryHolds (Join-Path $TestRoot ("dump-" + [Guid]::NewGuid().ToString("N") + ".img"))) }
}
Test "android: boot and recovery are read back and accepted" {
    [void](New-Work "android-ok" "spm188a" "spm188a")
    $patched = Set-RootDone
    Set-AndroidRobot $patched (Get-TwrpImage (Get-Robot))
    Step-Android
    Assert (Said "boot partition = boot-magisk.img") "boot checked"
    Assert (Said "recovery partition = twrp-own-kernel.img") "recovery checked"
    Assert ((Get-State).ContainsKey("done-android")) "done"
    Assert (-not (Test-Path (Join-Path $Work "boot-check.img")) -and -not (Test-Path (Join-Path $Work "recovery-check.img"))) "nothing left behind"
}
Test "android: a boot that is not the patched one stops the install" {
    [void](New-Work "android-boot" "spm188a" "spm188a")
    [void](Set-RootDone)
    Set-AndroidRobot (Join-Path $Backup "boot-original.img") (Get-TwrpImage (Get-Robot))
    Assert-Stops { Step-Android } "boot partition differs from boot-magisk.img.*run 'flash-root' again"
    Assert (-not (Get-State).ContainsKey("done-android")) "not done"
}
Test "android: a recovery that is not TWRP stops the install" {
    [void](New-Work "android-recovery" "spm188a" "spm188a")
    $patched = Set-RootDone
    Set-AndroidRobot $patched (Join-Path $Backup "recovery-original.img")
    Assert-Stops { Step-Android } "recovery partition does not hold twrp-own-kernel.img.*another ramdisk"
    Assert (-not (Get-State).ContainsKey("done-android")) "not done"
}
Test "android: a partition that cannot be read back is not a success" {
    [void](New-Work "android-unreadable" "l706_dfbh_v_695" "l706_dfbh_v_695")
    $patched = Set-RootDone
    Set-AndroidRobot "" ""
    Assert-Stops { Step-Android } "Could not read the boot partition back"
    Set-AndroidRobot $patched ""
    Assert-Stops { Step-Android } "Could not read the recovery partition back"
    Assert (-not (Get-State).ContainsKey("done-android")) "not done"
}
Test "android: without a boot-magisk.img from 'root', boot cannot be compared and the step says so" {
    [void](New-Work "android-manual" "l706_dfbh_v_695" "l706_dfbh_v_695")
    Set-AndroidRobot "" (Join-Path $Work "hack\recovery.img")
    Step-Android
    Assert (Said "WARNING: no boot-magisk.img from the 'root' step") "said"
    Assert (Said "recovery partition = recovery.img") "recovery still checked"
    Assert ((Get-State).ContainsKey("done-android")) "done"
}
Test "a partition read back: same image, other kernel, other ramdisk, not an image" {
    $w = New-Work "compare" "spm188a" "spm188a"
    $own = Get-TwrpImage (Get-Robot)
    $guide = Join-Path $w "hack\recovery.img"
    $d = Join-Path $w "d.img"
    Assert ((Compare-PartitionImage (New-Dump $own $d) $own) -eq "") "same"
    Assert ((Compare-PartitionImage (New-Dump $guide $d) $own) -match "^the partition holds another kernel \(Linux version .* 2018\)$") "kernel only"
    Assert ((Compare-PartitionImage (New-Dump (Join-Path $Backup "boot-original.img") $d) $own) -match "^the partition holds another ramdisk$") "ramdisk only"
    [IO.File]::WriteAllBytes($d, (New-Object byte[] $PartitionSize))
    Assert ((Compare-PartitionImage $d $own) -match "^the partition is not an Android boot image") "empty partition"
}

# ---------------------------------------------------------------- backup and status

Test "backup: names the robot and the TWRP it will get" {
    [void](New-Work "backup-1100" "spm188a" "spm188a")
    Step-Backup
    Assert (Said "Robot: SKMC 1100 A1 \(project spm188a") "robot"
    Assert (Said "rebuilt around the robot's own kernel") "its TWRP"
    Assert ((Get-State)["robot-project"] -eq "spm188a") "project kept"
    [void](New-Work "backup-unknown" "abc123" "abc123")
    Step-Backup
    Assert (Said "HUMAN: This robot is not one this guide knows") "the person is told"
    Assert ((Get-State).ContainsKey("done-backup")) "the backup itself is done"
}

# ---------------------------------------------------------------- install

Test "the Toque APK is chosen by its version, not by the date of the file" {
    [void](New-Work "apk" "l706_dfbh_v_695" "l706_dfbh_v_695")
    Assert ((Get-ToqueApk) -eq "") "none yet"
    $n = 0
    foreach ($v in "0.10.0", "0.7.1", "0.9.2") {
        $f = Join-Path $Downloads "Toque-$v.apk"
        Set-Content -Path $f -Value $v
        (Get-Item $f).LastWriteTime = (Get-Date).AddDays($n - 10)
        $n++
    }
    Set-Content -Path (Join-Path $Downloads "Toque-debug.apk") -Value "x"
    Assert ((Split-Path (Get-ToqueApk) -Leaf) -eq "Toque-0.10.0.apk") (Get-ToqueApk)
}

Test "the Smart's APK is never the one installed on a Connect" {
    [void](New-Work "apk-smart" "l706_dfbh_v_695" "l706_dfbh_v_695")
    $f = Join-Path $Downloads "Toque-smart-0.8.0.apk"
    Set-Content -Path $f -Value "smart"
    Assert ((Get-ToqueApk) -eq "") "a Smart APK alone is not taken"
    Set-Content -Path (Join-Path $Downloads "Toque-0.8.0.apk") -Value "connect"
    (Get-Item $f).LastWriteTime = (Get-Date).AddDays(1)
    Assert ((Split-Path (Get-ToqueApk) -Leaf) -eq "Toque-0.8.0.apk") (Get-ToqueApk)
}

# A fake adb.exe for install-robot.ps1 and restore-robot.ps1, which start adb themselves: a .cmd
# that answers from robot.json and writes each call to calls.txt.
$FakeAdbDir = Join-Path $TestRoot "fake-adb"
New-Item -ItemType Directory -Force -Path $FakeAdbDir | Out-Null
Set-Content -Path (Join-Path $FakeAdbDir "fake-adb.ps1") -Encoding ASCII -Value @'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$cfg = Get-Content (Join-Path $dir "robot.json") -Raw | ConvertFrom-Json
Add-Content -Path (Join-Path $dir "calls.txt") -Value ($args -join " ")
$a = @($args)
if ($a.Count -gt 2 -and $a[0] -eq "-s") { $a = @($a[2..($a.Count - 1)]) }
$rest = ""
if ($a.Count -gt 1) { $rest = ($a[1..($a.Count - 1)] -join " ") }
switch ($a[0]) {
    "devices" { "List of devices attached"; foreach ($d in $cfg.devices) { $d } }
    "connect" { "connected to " + $a[1] }
    "install" { $cfg.install }
    "pull" { if ($cfg.pull) { Copy-Item $cfg.pull $a[2] -Force; "1 file pulled" } else { "adb: error: remote object does not exist" } }
    "push" { "1 file pushed" }
    "uninstall" { "Success" }
    "shell" {
        if ($rest -like "*id -u*") { $cfg.id }
        elseif ($rest -like "pm path*") { "package:/data/app/app.toque-1/base.apk" }
        elseif ($rest -like "*toque-setup-root.sh*") { $cfg.setup }
        elseif ($rest -like "*toque-restore.sh*") { $cfg.restore }
    }
}
'@
$FakeAdb = Join-Path $FakeAdbDir "adb.cmd"
Set-Content -Path $FakeAdb -Encoding ASCII -Value ('@powershell -NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $FakeAdbDir "fake-adb.ps1") + '" %*')
$Apk = ""
function Set-FakeRobot($changes) {
    $cfg = @{
        devices = @("TESTROBOT`tdevice"); id = "0"; install = "Success"; pull = $script:TestApk
        setup = "Toque allowed in Magisk`nBoot script installed: Toque stays allowed after a restart and opens first"; restore = "Stock app restored. Restart the robot."
    }
    foreach ($k in $changes.Keys) { $cfg[$k] = $changes[$k] }
    ($cfg | ConvertTo-Json) | Set-Content -Path (Join-Path $FakeAdbDir "robot.json") -Encoding ASCII
    Set-Content -Path (Join-Path $FakeAdbDir "calls.txt") -Value ""
}
function Get-FakeCalls { return @(Get-Content (Join-Path $FakeAdbDir "calls.txt") | Where-Object { $_ -ne "" }) }
$script:TestApk = Join-Path $TestRoot "Toque-9.9.9.apk"
Set-Content -Path $script:TestApk -Value "the apk that is sent"
$OtherApk = Join-Path $TestRoot "other.apk"
Set-Content -Path $OtherApk -Value "another apk"
function Invoke-Install($extra) {
    $p = @{ Apk = $script:TestApk; Adb = $FakeAdb }
    foreach ($k in $extra.Keys) { $p[$k] = $extra[$k] }
    $global:LASTEXITCODE = 0
    $out = & (Join-Path $Tools "install-robot.ps1") @p 6>&1 | Out-String
    return @{ Code = $LASTEXITCODE; Out = $out }
}

Test "install-robot: installs, checks the file on the robot, sets root up" {
    Set-FakeRobot @{}
    $r = Invoke-Install @{}
    Assert ($r.Code -eq 0) ("exit code " + $r.Code + " : " + $r.Out)
    Assert ($r.Out -match "Toque on the robot = the file that was sent") $r.Out
    $calls = Get-FakeCalls
    Assert ($calls | Where-Object { $_ -match "^-s TESTROBOT install -r " }) "install on the chosen robot"
    foreach ($f in "toque-restore.sh", "toque-setup-root.sh", "toque-su.sh") { Assert ($calls | Where-Object { $_ -match ("^-s TESTROBOT push .* /data/local/tmp/" + $f + "$") }) "pushed $f" }
    Assert ($calls | Where-Object { $_ -match "am start -n app.toque/.ui.MainActivity" }) "Toque started"
}
Test "install-robot: a refused install is a failure, and nothing else is done" {
    Set-FakeRobot @{ install = "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]" }
    $r = Invoke-Install @{}
    Assert ($r.Code -eq 1) ("exit code " + $r.Code)
    Assert ($r.Out -match "already runs a newer Toque") $r.Out
    Assert (-not (Get-FakeCalls | Where-Object { $_ -match " push | am start |toque-setup-root" })) "no push, no setup, no start"
}
Test "install-robot: another file on the robot than the one sent is a failure" {
    Set-FakeRobot @{ pull = $OtherApk }
    $r = Invoke-Install @{}
    Assert ($r.Code -eq 1 -and $r.Out -match "is not the file that was sent") $r.Out
    Assert (-not (Get-FakeCalls | Where-Object { $_ -match "toque-setup-root" })) "root setup not run"
}
Test "install-robot: several devices, no root, an unfinished root setup" {
    Set-FakeRobot @{ devices = @("TESTROBOT`tdevice", "192.168.0.20:5555`tdevice") }
    $r = Invoke-Install @{}
    Assert ($r.Code -eq 1 -and $r.Out -match "several devices \(TESTROBOT, 192\.168\.0\.20:5555\)") $r.Out
    $r = Invoke-Install @{ Ip = "192.168.0.20" }
    Assert ($r.Code -eq 0) ("with -Ip: " + $r.Out)
    Assert (Get-FakeCalls | Where-Object { $_ -match "^-s 192\.168\.0\.20:5555 install -r " }) "the robot on Wi-Fi was used"
    Set-FakeRobot @{ id = "" }
    $r = Invoke-Install @{}
    Assert ($r.Code -eq 1 -and $r.Out -match "No root access") $r.Out
    Assert (-not (Get-FakeCalls | Where-Object { $_ -match " install " })) "nothing installed without root"
    Set-FakeRobot @{ setup = "Toque is not installed" }
    $r = Invoke-Install @{}
    Assert ($r.Code -eq 1 -and $r.Out -match "root setup did not finish") $r.Out
    Set-FakeRobot @{ devices = @("TESTROBOT`tunauthorized") }
    $r = Invoke-Install @{}
    Assert ($r.Code -eq 1 -and $r.Out -match "sees the robot as 'unauthorized'") $r.Out
}
Test "install (setup script): done only when Toque really is on the robot" {
    [void](New-Work "install-step" "l706_dfbh_v_695" "l706_dfbh_v_695")
    function Get-Adb { return $FakeAdb }
    $script:Robot.Shell["dumpsys package app.toque*"] = "    versionName=9.9.9"
    $script:Robot.Shell["su -c 'cat /data/data/app.toque/shared_prefs/toque.xml'"] = '<map><string name="mode">hardware</string></map>'
    Copy-Item $script:TestApk (Join-Path $Downloads "Toque-9.9.9.apk")
    Set-FakeRobot @{ install = "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"; pull = (Join-Path $Downloads "Toque-9.9.9.apk") }
    Assert-Stops { Step-Install 6>$null } "Toque was not installed"
    Assert (-not (Get-State).ContainsKey("done-install")) "not marked done"
    Set-FakeRobot @{ pull = (Join-Path $Downloads "Toque-9.9.9.apk") }
    Step-Install 6>$null
    Assert ((Get-State).ContainsKey("done-install")) "marked done"
    Assert (Said "Toque installed: versionName=9.9.9") "version shown"
}
Test "restore-robot: the stock app back, on the chosen robot" {
    Set-FakeRobot @{}
    $global:LASTEXITCODE = 0
    $out = & (Join-Path $Tools "restore-robot.ps1") -Adb $FakeAdb -Uninstall 6>&1 | Out-String
    Assert ($LASTEXITCODE -eq 0 -and $out -match "Robot restarted on the stock app") $out
    $calls = Get-FakeCalls
    Assert ($calls | Where-Object { $_ -match "^-s TESTROBOT uninstall app.toque$" }) "uninstalled"
    Assert ($calls | Where-Object { $_ -match "^-s TESTROBOT reboot$" }) "restarted"
    Set-FakeRobot @{ restore = "" }
    $global:LASTEXITCODE = 0
    $out = & (Join-Path $Tools "restore-robot.ps1") -Adb $FakeAdb 6>&1 | Out-String
    Assert ($LASTEXITCODE -eq 1 -and $out -match "was not restored") $out
    Assert (-not (Get-FakeCalls | Where-Object { $_ -match " reboot$" })) "no restart when nothing was restored"
}
Test "scripts for the robot keep Unix line ends" {
    foreach ($f in "restore.sh", "setup-root.sh", "toque-su.sh", "twrp-root.sh") { Assert-UnixLineEnds (Join-Path $Tools "robot\$f") }
    $bad = Join-Path $TestRoot "crlf.sh"
    [IO.File]::WriteAllBytes($bad, [byte[]](35, 33, 13, 10))
    Assert-Stops { Assert-UnixLineEnds $bad } "Windows line ends"
}

# ---------------------------------------------------------------- end

Remove-Item -Recurse -Force $TestRoot -ErrorAction SilentlyContinue
Write-Host ""
Write-Host ("{0} passed, {1} failed" -f $script:Passed, $script:Failures.Count)
if ($script:Failures.Count -gt 0) { exit 1 }
exit 0
