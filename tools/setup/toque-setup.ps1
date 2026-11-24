# Toque setup for the Monsieur Cuisine Connect (SKMC 1200 and SKMC 1100 A1), step by step, from a
# Windows PC. Written to be driven by an AI agent (see AGENTS.md) or by hand. Every step is safe to
# re-run.
#
#   powershell -ExecutionPolicy Bypass -File tools\setup\toque-setup.ps1 -Step status
#
# Steps, in order: status, prepare, driver, backup, flash-twrp, root, flash-root, android,
# install, wifi, cleanup. Lines starting with "HUMAN:" are actions only a person can do
# (unscrewing, cables, power switch, Windows security prompts, a tap on the robot's screen).
# A step that needs one stops and says so; run it again once the person is done.
#
# The robot is recognised from its own boot backup, and the TWRP image the flash steps write
# follows from it: the guide's image on an SKMC 1200, the same TWRP rebuilt around the robot's own
# kernel on an SKMC 1100 A1 (built on the PC when a flash step needs it; 'own-twrp' builds it ahead
# of time). -Twrp guide or -Twrp own-kernel makes the choice for a robot this guide does not know.
param(
    [ValidateSet("status", "prepare", "driver", "backup", "own-twrp", "flash-twrp", "root", "flash-root", "android", "install", "wifi", "cleanup")]
    [string]$Step = "status",
    [string]$Work = "C:\Toque",
    [string]$Apk = "",
    [string]$Ip = "",
    [ValidateSet("auto", "guide", "own-kernel")]
    [string]$Twrp = "auto",
    [int]$WaitSeconds = 180
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Downloads = Join-Path $Work "downloads"
$Backup = Join-Path $Work "backup"
$StateFile = Join-Path $Work "state.json"
$Log = Join-Path $Work "setup-log.txt"

# Known-good files (SHA-256). The scatter and TWRP come from github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack.
$Known = @{
    Magisk     = @{ Url = "https://github.com/topjohnwu/Magisk/releases/download/v31.0/Magisk-v31.0.apk"; Sha = "2c8a488b9a5293e578e95ae4f07e3c57aba4feec4a52ca4dd852a2692d6dd4e8" }
    HackZip    = @{ Url = "https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack/archive/refs/heads/master.zip" }
    Twrp       = @{ Sha = "fc9ac999fc3b0a6c0cb563f39359084a6fcc211aeb42e7b754990afa4b543d3e"; Size = 11452416 }
    Scatter    = @{ Sha = "13b8260dba9e4efba874c09e35803af43cc69556ba0f1937226240b0ab318364" }
    Tools      = @{ Url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" }
    DriverCab  = @{ Name = "20896845_fdc6bb5aa9a9bac99adf85d931d6c21d1130a96e.cab"; Sha = "ce72e97c07582ad41debfaeee6f6284ecd28285df5f9caa05193653fb236561f" }
    FlashTool  = @{ Sha = "67e9eb48161087d43eca845b2dc0e9beacac3c0381bd1ce7989d48b17e2084a7" }
    FlashDA    = @{ Sha = "9cbf39f3925fe88d18bd4c4e45dd1c01741bf7a31a4f69252dfb318d229c0346" }
    Releases   = "https://api.github.com/repos/Wuki60/MrCuisineVibeConnect/releases/latest"
}
$PartitionSize = 16777216   # boot and recovery: 16 MiB each
$ByName = "/dev/block/platform/mtk-msdc.0/11120000.msdc0/by-name"

# Robots this guide has been run on, by the project name compiled into their kernel (the name
# Android also reports as its device). The guide's TWRP carries a kernel of the first one; a kernel
# built for another project may lack a driver the robot needs (SKMC 1100 A1: no touch, issue #10).
$Robots = @{
    "l706_dfbh_v_695" = @{ Name = "SKMC 1200"; Twrp = "guide" }
    "spm188a"         = @{ Name = "SKMC 1100 A1"; Twrp = "own-kernel" }
}

# ---------------------------------------------------------------- helpers

function Say([string]$text) {
    Write-Host $text
    try { Add-Content -Path $Log -Value ("{0:yyyy-MM-dd HH:mm:ss} {1}" -f (Get-Date), $text) -Encoding UTF8 } catch {}
}
function Human([string]$text) { Say ("HUMAN: " + $text) }
function Ok([string]$text) { Say ("OK: " + $text) }
function Fail([string]$text) { Say ("STOP: " + $text); exit 2 }
function NeedHuman([string]$text) { Human $text; Say "Run this step again when it is done."; exit 3 }

function Get-State {
    if (Test-Path $StateFile) {
        $o = Get-Content $StateFile -Raw | ConvertFrom-Json
        $h = @{}
        foreach ($p in $o.PSObject.Properties) { $h[$p.Name] = $p.Value }
        return $h
    }
    return @{}
}
function Set-State([string]$key, $value) {
    $s = Get-State
    $s[$key] = $value
    ($s | ConvertTo-Json -Depth 5) | Set-Content -Path $StateFile -Encoding UTF8
}
function Sha([string]$path) { (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLowerInvariant() }

function Fetch([string]$url, [string]$dest) {
    Say ("Downloading " + $url)
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $dest -Headers @{ "User-Agent" = "toque-setup" }
}

function Get-Adb {
    $candidates = @(
        (Join-Path $Work "platform-tools\adb.exe"),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    Fail "adb not found: run the 'prepare' step first."
}

function Adb {
    $adb = Get-Adb
    # Windows PowerShell 5.1: with ErrorActionPreference=Stop, a native command's stderr redirected
    # with 2>&1 becomes a terminating error, and adb prints its push/pull progress on stderr. Keep
    # going and turn the error records back into plain text lines.
    $ErrorActionPreference = "Continue"
    $out = & $adb @args 2>&1 | ForEach-Object { "$_" } | Out-String
    return $out.Trim()
}

# First "adb devices" entry as @{ Serial; State }: with -Usb the cable device (serial without
# ":port"), with $Target only serials starting with it (default: -Ip), otherwise any device.
function Get-AdbDevice([string]$Target = $Ip, [switch]$Usb) {
    $lines = (Adb devices) -split "`r?`n" | Where-Object { $_ -match "`t" }
    if ($Usb) { $lines = $lines | Where-Object { ($_ -split "`t")[0] -notmatch ":" } }
    elseif ($Target -ne "") { $lines = $lines | Where-Object { $_ -like "$Target*" } }
    $first = $lines | Select-Object -First 1
    if (-not $first) { return $null }
    $parts = $first -split "`t"
    return [pscustomobject]@{ Serial = $parts[0].Trim(); State = $parts[1].Trim() }
}

# State of the robot as adb sees it: "recovery" (TWRP), "device" (Android), "unauthorized", or "".
function Get-AdbState([string]$Target = $Ip) {
    $dev = Get-AdbDevice $Target
    if (-not $dev) { return "" }
    return $dev.State
}

function Wait-AdbState([string[]]$wanted, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $st = Get-AdbState
        if ($wanted -contains $st) { return $st }
        Start-Sleep -Seconds 3
    }
    return Get-AdbState
}

# Effective machine mode, read from Prefs: shared prefs "toque", key "mode" = demo, twin or
# hardware. A missing file or key means demo (Prefs.Mode.of). Returns "" when it cannot be read.
function Get-MachineMode {
    $xml = Adb shell "su -c 'cat /data/data/app.toque/shared_prefs/toque.xml'"
    if ($xml -match '<string name="mode">([^<]+)</string>') {
        $m = $Matches[1].Trim()
        if ($m -in "demo", "twin", "hardware") { return $m }
        return "demo"
    }
    if ($xml -match '<map' -or $xml -match 'No such file or directory') { return "demo" }
    return ""
}

function Test-BootImage([string]$path, [string]$what) {
    if (-not (Test-Path $path)) { Fail "$what missing: $path" }
    $len = (Get-Item $path).Length
    if ($len -ne $PartitionSize) { Fail ("$what has the wrong size ({0} bytes, expected {1})." -f $len, $PartitionSize) }
    $fs = [IO.File]::OpenRead($path)
    try {
        $head = New-Object byte[] 8
        [void]$fs.Read($head, 0, 8)
    } finally { $fs.Close() }
    if ([Text.Encoding]::ASCII.GetString($head) -ne "ANDROID!") { Fail "$what is not an Android boot image (no ANDROID! header): do not continue." }
}

function Copy-Bytes([byte[]]$src, [long]$offset, [long]$count) {
    $out = New-Object byte[] $count
    [Array]::Copy($src, $offset, $out, 0, $count)
    return , $out
}

function Get-BytesSha([byte[]]$bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace "-", "").ToLowerInvariant() }
    finally { $sha.Dispose() }
}

# Parses an Android boot image (header version 0: boot, recovery and the TWRP image all use it; a
# partition read back from the robot is one too, followed by whatever was there before). Returns
# its parts, or a sentence that says why the file is not one.
function Read-BootImageParts([string]$path) {
    $b = [IO.File]::ReadAllBytes($path)
    if ($b.Length -lt 4096 -or [Text.Encoding]::ASCII.GetString($b, 0, 8) -ne "ANDROID!") { return "is not an Android boot image: $path" }
    $kSize = [long][BitConverter]::ToUInt32($b, 8)
    $rSize = [long][BitConverter]::ToUInt32($b, 16)
    $page = [long][BitConverter]::ToUInt32($b, 36)
    if ($page -lt 2048 -or $page -gt 16384 -or ($page -band ($page - 1)) -ne 0) { return "has an unexpected page size ($page)." }
    $kOff = $page
    $rOff = $kOff + [long][Math]::Ceiling($kSize / $page) * $page
    if ($kSize -le 0 -or $rSize -le 0 -or ($rOff + $rSize) -gt $b.Length) { return "is truncated or its header is invalid." }
    return [pscustomobject]@{
        Bytes       = $b
        PageSize    = $page
        KernelAddr  = [BitConverter]::ToUInt32($b, 12)
        RamdiskAddr = [BitConverter]::ToUInt32($b, 20)
        SecondSize  = [BitConverter]::ToUInt32($b, 24)
        SecondAddr  = [BitConverter]::ToUInt32($b, 28)
        TagsAddr    = [BitConverter]::ToUInt32($b, 32)
        DtSize      = [BitConverter]::ToUInt32($b, 40)
        Kernel      = (Copy-Bytes $b $kOff $kSize)
        Ramdisk     = (Copy-Bytes $b $rOff $rSize)
    }
}

# The same, for a file that must be a boot image: stops otherwise.
function Read-BootImage([string]$path, [string]$what) {
    $r = Read-BootImageParts $path
    if ($r -is [string]) { Fail "$what $r" }
    return $r
}

# The id mkbootimg stores in the header: SHA-1 over each part followed by its 32-bit size.
function Get-BootImageId([byte[]]$kernel, [byte[]]$ramdisk) {
    $sha = [Security.Cryptography.SHA1]::Create()
    try {
        foreach ($part in @($kernel, $ramdisk, (New-Object byte[] 0))) {
            if ($part.Length -gt 0) { [void]$sha.TransformBlock($part, 0, $part.Length, $null, 0) }
            [void]$sha.TransformBlock([BitConverter]::GetBytes([uint32]$part.Length), 0, 4, $null, 0)
        }
        [void]$sha.TransformFinalBlock((New-Object byte[] 0), 0, 0)
        return , $sha.Hash
    } finally { $sha.Dispose() }
}

# ASCII text of the gzip stream that starts at $offset of $bytes ("" when there is none).
function Expand-GzipText([byte[]]$bytes, [int]$offset, [int]$max) {
    $buf = New-Object byte[] $max
    $n = 0
    $ms = New-Object IO.MemoryStream -ArgumentList @($bytes, $offset, ($bytes.Length - $offset), $false)
    $gz = New-Object IO.Compression.GZipStream -ArgumentList @($ms, [IO.Compression.CompressionMode]::Decompress)
    try {
        while ($n -lt $buf.Length) {
            $r = $gz.Read($buf, $n, $buf.Length - $n)
            if ($r -le 0) { break }
            $n += $r
        }
    } catch {} finally { $gz.Dispose(); $ms.Dispose() }
    if ($n -le 0) { return "" }
    return [Text.Encoding]::ASCII.GetString($buf, 0, $n)
}

# What a kernel taken from a boot image (zImage with a gzip payload) says about itself: its "Linux
# version ..." line, and the project it was built for (the name of the board's source folder, kept
# in a driver's messages: .../camera_project/<project>/...).
function Get-KernelFacts([byte[]]$kernel) {
    $version = "(version not found)"
    $project = ""
    $limit = [Math]::Min($kernel.Length - 3, 131072)
    for ($i = 0; $i -lt $limit; $i++) {
        if ($kernel[$i] -ne 0x1F -or $kernel[$i + 1] -ne 0x8B -or $kernel[$i + 2] -ne 8) { continue }
        $text = Expand-GzipText $kernel $i (32MB)
        $m = [regex]::Match($text, "Linux version [\x20-\x7e]+")
        if (-not $m.Success) { continue }
        $version = $m.Value
        $p = [regex]::Match($text, "/camera_project/([A-Za-z0-9_.-]{2,64})/")
        if ($p.Success) { $project = $p.Groups[1].Value }
        break
    }
    return [pscustomobject]@{ Version = $version; Project = $project }
}

function Get-KernelVersion([byte[]]$kernel) { return (Get-KernelFacts $kernel).Version }

# The device Android was built for, from a boot image's ramdisk (default.prop:
# ro.bootimage.build.fingerprint=brand/product/device:release/...). "" when it is not there.
function Get-RamdiskDevice([byte[]]$ramdisk) {
    if ($ramdisk.Length -lt 3 -or $ramdisk[0] -ne 0x1F -or $ramdisk[1] -ne 0x8B) { return "" }
    $text = Expand-GzipText $ramdisk 0 (32MB)
    $m = [regex]::Match($text, "ro\.bootimage\.build\.fingerprint=[^/\s]+/[^/\s]+/([A-Za-z0-9_.-]{2,64}):")
    if ($m.Success) { return $m.Groups[1].Value }
    return ""
}

# What a boot backup says about the robot. Project: the kernel's project, or failing that the
# device name. Name and Rule come from $Robots; both are "" for a robot that is not listed there.
function Get-RobotFacts([string]$bootPath) {
    $img = Read-BootImage $bootPath "boot-original.img"
    $k = Get-KernelFacts $img.Kernel
    $device = Get-RamdiskDevice $img.Ramdisk
    $project = $k.Project
    if ($project -eq "") { $project = $device }
    $known = $null
    if ($project -ne "" -and $Robots.ContainsKey($project)) { $known = $Robots[$project] }
    return [pscustomobject]@{
        Project   = $project
        Device    = $device
        Kernel    = $k.Version
        KernelSha = (Get-BytesSha $img.Kernel)
        Name      = $(if ($known) { $known.Name } else { "" })
        Rule      = $(if ($known) { $known.Twrp } else { "" })
    }
}

function Format-Robot($robot) {
    $name = $(if ($robot.Name -ne "") { $robot.Name } else { "a model this guide does not know" })
    $project = $(if ($robot.Project -ne "") { $robot.Project } else { "not found" })
    return ("{0} (project {1}; {2})" -f $name, $project, $robot.Kernel)
}

# The robot, from the boot backup that 'backup' verified.
function Get-Robot {
    $s = Get-State
    if (-not $s.ContainsKey("done-backup")) { Fail "Run 'backup' first: the robot is recognised from its own boot backup." }
    $boot = Join-Path $Backup "boot-original.img"
    Test-BootImage $boot "boot-original.img"
    if ((Sha $boot) -ne $s["boot-original-sha256"]) { Fail "boot-original.img no longer matches the checksum recorded by 'backup': do not continue." }
    return Get-RobotFacts $boot
}

# Which TWRP suits this robot: Choice is "guide" or "own-kernel", or "" with the reason in Problem.
# A known robot follows $Robots. -Twrp decides for the others (and may ask for the robot's own
# kernel on any robot); the choice made at 'flash-twrp' is remembered for the later steps.
function Resolve-TwrpChoice($robot) {
    $refused = ("The guide's TWRP does not suit this robot, " + (Format-Robot $robot) + ": its kernel was built for another board, and this robot's touch screen does not work with it (issue #10). Run the step again without -Twrp guide.")
    if ($Twrp -eq "own-kernel") { return @{ Choice = "own-kernel"; Problem = "" } }
    if ($Twrp -eq "guide") {
        if ($robot.Rule -eq "own-kernel") { return @{ Choice = ""; Problem = $refused } }
        return @{ Choice = "guide"; Problem = "" }
    }
    $s = Get-State
    $kept = $s["twrp-choice"]
    # An install begun with an earlier version of this script: its 'flash-twrp' wrote the image of
    # 'own-twrp' when that step had been run, the guide's otherwise.
    if (-not $kept -and $s.ContainsKey("done-flash-twrp")) { $kept = $(if ($s.ContainsKey("twrp-own-kernel-sha256")) { "own-kernel" } else { "guide" }) }
    if ($kept -eq "own-kernel") { return @{ Choice = "own-kernel"; Problem = "" } }
    if ($robot.Rule -ne "") { return @{ Choice = $robot.Rule; Problem = "" } }
    if ($kept -eq "guide") { return @{ Choice = "guide"; Problem = "" } }
    # A robot that is not listed, but whose kernel is the very one inside the guide's TWRP.
    $guide = Join-Path $Work "hack\recovery.img"
    if ((Test-Path $guide) -and (Sha $guide) -eq $Known.Twrp.Sha) {
        $t = Read-BootImage $guide "TWRP"
        if ((Get-BytesSha $t.Kernel) -eq $robot.KernelSha) { return @{ Choice = "guide"; Problem = "" } }
    }
    $list = ($Robots.Keys | Sort-Object | ForEach-Object { $_ + " = " + $Robots[$_].Name }) -join ", "
    return @{ Choice = ""; Problem = ("This robot is not one this guide knows: " + (Format-Robot $robot) + ". Known: " + $list + ". Check the label under the robot: a Monsieur Cuisine Smart (SKMS 1200) is different hardware, stop there. For another Monsieur Cuisine Connect, choose the TWRP yourself: run 'flash-twrp' with -Twrp own-kernel (the guide's TWRP rebuilt around this robot's own kernel: the choice for a board the guide's kernel was not built for) or with -Twrp guide (the image used on the SKMC 1200); the later steps remember it. Please report the model on the label, the project name above and how it went in an issue.") }
}

function Get-TwrpChoice($robot) {
    $c = Resolve-TwrpChoice $robot
    if ($c.Choice -eq "") { Fail $c.Problem }
    return $c.Choice
}

# The guide's TWRP, checked.
function Get-GuideTwrp {
    $twrp = Join-Path $Work "hack\recovery.img"
    if (-not (Test-Path $twrp)) { Fail "TWRP missing: run 'prepare'." }
    if ((Sha $twrp) -ne $Known.Twrp.Sha) { Fail "TWRP checksum mismatch." }
    return $twrp
}

# The TWRP image the flash steps write on this robot: the guide's, or the same TWRP with the
# robot's own kernel. That one is built here when it is not there yet, or no longer the one that
# was built from this boot backup: it only depends on two checked files, so building it again is
# always safe.
function Get-TwrpImage($robot) {
    $guide = Get-GuideTwrp
    if ((Get-TwrpChoice $robot) -eq "guide") { return $guide }
    $own = Join-Path $Work "twrp-own-kernel.img"
    $s = Get-State
    if ((Test-Path $own) -and $s["twrp-own-kernel-sha256"] -eq (Sha $own) -and $s["twrp-own-kernel-boot-sha256"] -eq $s["boot-original-sha256"]) { return $own }
    $built = Build-OwnTwrp
    if ($built -eq "") { return $guide }
    return $built
}

# Reads a 16 MiB partition of the robot ("boot" or "recovery") into $local. In Android the read
# needs root (su); in TWRP the shell is root already. True when the whole partition arrived.
function Read-RobotPartition([string]$name, [string]$local, [switch]$InTwrp) {
    if (Test-Path $local) { Remove-Item -Force $local }
    $mib = [int]($PartitionSize / 1048576)
    if ($InTwrp) {
        $tmp = "/tmp/toque-$name-check.img"
        Say (Adb shell "B=`$(ls -d /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name 2>/dev/null | head -n 1); dd if=`$B/$name of=$tmp bs=1048576 count=$mib")
        Say (Adb pull $tmp $local)
        Say (Adb shell "rm -f $tmp")
    } else {
        # /data/adb/magisk/busybox only exists after 'install' (setup-root.sh), and Android 6's
        # toolbox has no sha256sum: the partition is hashed on the PC.
        $tmp = "/data/local/tmp/toque-$name-check.img"
        Say (Adb shell "su -c 'dd if=$ByName/$name of=$tmp bs=1048576 count=$mib && chmod 644 $tmp'")
        Say (Adb pull $tmp $local)
        Say (Adb shell "su -c 'rm -f $tmp'")
    }
    return ((Test-Path $local) -and (Get-Item $local).Length -eq $PartitionSize)
}

# "" when the partition read back holds this boot image: same kernel and same ramdisk (what
# follows the image in the 16 MiB does not matter). Otherwise, what differs.
function Compare-PartitionImage([string]$dump, [string]$image) {
    $d = Read-BootImageParts $dump
    if ($d -is [string]) { return "the partition " + $d }
    $e = Read-BootImage $image (Split-Path $image -Leaf)
    $diff = @()
    if ((Get-BytesSha $d.Kernel) -ne (Get-BytesSha $e.Kernel)) { $diff += ("another kernel (" + (Get-KernelVersion $d.Kernel) + ")") }
    if ((Get-BytesSha $d.Ramdisk) -ne (Get-BytesSha $e.Ramdisk)) { $diff += "another ramdisk" }
    if ($diff.Count -eq 0) { return "" }
    return ("the partition holds " + ($diff -join " and "))
}

function Get-FlashTool {
    $exe = Get-ChildItem -Path $Work -Filter flash_tool.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $exe) { return $null }
    return $exe.FullName
}

function Get-MemoryIntegrity {
    try {
        $v = Get-ItemPropertyValue -Path "HKLM:\SYSTEM\CurrentControlSet\Control\DeviceGuard\Scenarios\HypervisorEnforcedCodeIntegrity" -Name Enabled -ErrorAction Stop
        return [int]$v -eq 1
    } catch { return $false }
}

function Get-MtkPort {
    try {
        return Get-PnpDevice -PresentOnly -ErrorAction Stop | Where-Object { $_.InstanceId -match "VID_0E8D" }
    } catch { return $null }
}

function Test-MtkDriver {
    $out = pnputil /enum-drivers | Out-String
    return $out -match "cdc-acm\.inf"
}

# SP Flash Tool console configuration (flash_tool.exe -i file.xml).
function Write-FlashConfig([string]$name, [string]$commands, [string]$romList) {
    $tool = Get-FlashTool
    $da = Join-Path (Split-Path $tool) "MTK_AllInOne_DA.bin"
    $scatter = Join-Path $Work "hack\l706_dfbh_v_695scatter.txt"
    $xml = @"
<?xml version="1.0" encoding="UTF-8" ?>
<flashtool-config version="2.0">
    <general>
        <chip-name>MT6580</chip-name>
        <storage-type>EMMC</storage-type>
        <download-agent>$da</download-agent>
        <scatter>$scatter</scatter>
$romList
        <connection type="BromUSB" high-speed="true" power="AutoDetect" timeout-count="3600000" com-port="" />
    </general>
    <commands>
$commands
    </commands>
</flashtool-config>
"@
    $path = Join-Path $Work "spft-$name.xml"
    Set-Content -Path $path -Value $xml -Encoding UTF8
    return $path
}

# Download Only of boot (and optionally recovery); every other downloadable partition explicitly off.
function Write-DownloadConfig([string]$name, [string]$bootImage, [string]$recoveryImage) {
    $roms = @("            <rom index=""0"" enable=""false""></rom>", "            <rom index=""7"" enable=""false""></rom>")
    $roms += "            <rom index=""8"" enable=""true"">$bootImage</rom>"
    if ($recoveryImage) { $roms += "            <rom index=""9"" enable=""true"">$recoveryImage</rom>" }
    else { $roms += "            <rom index=""9"" enable=""false""></rom>" }
    foreach ($i in 11, 17, 19, 20, 21) { $roms += "            <rom index=""$i"" enable=""false""></rom>" }
    $romList = "        <rom-list>`r`n" + ($roms -join "`r`n") + "`r`n        </rom-list>"
    return Write-FlashConfig $name "        <download-only />" $romList
}

function Invoke-FlashTool([string]$config, [string]$what) {
    $tool = Get-FlashTool
    if (-not $tool) { Fail "SP Flash Tool missing: run 'prepare'." }
    Human "Robot switched OFF with its rear switch, USB A-A cable in the right-hand port marked 'Android USB'. Switch the robot ON as soon as the next line appears (the tool waits up to 1 hour)."
    Say ("Running SP Flash Tool (console mode): " + $what)
    $out = Join-Path $Work ([IO.Path]::GetFileNameWithoutExtension($config) + "-out.txt")
    $p = Start-Process -FilePath $tool -ArgumentList @("-i", "`"$config`"") -WorkingDirectory (Split-Path $tool) -Wait -PassThru -NoNewWindow -RedirectStandardOutput $out
    Say ("flash_tool exit code: " + $p.ExitCode + " (output: " + $out + ")")
    return $p.ExitCode
}

# ---------------------------------------------------------------- steps

function Step-Status {
    $s = Get-State
    Say ("Work folder: " + $Work)
    Say ("Done steps: " + (($s.Keys | Where-Object { $_ -like "done-*" } | ForEach-Object { $_.Substring(5) }) -join ", "))
    Say ("Memory integrity (blocks the MediaTek driver): " + $(if (Get-MemoryIntegrity) { "ON" } else { "off" }))
    Say ("MediaTek driver installed: " + $(if (Test-MtkDriver) { "yes" } else { "no" }))
    $tool = Get-FlashTool
    Say ("SP Flash Tool: " + $(if ($tool) { $tool } else { "missing" }))
    $boot = Join-Path $Backup "boot-original.img"
    if ($s.ContainsKey("done-backup") -and (Test-Path $boot)) {
        $robot = Get-RobotFacts $boot
        Say ("Robot, from its boot backup: " + (Format-Robot $robot))
        $c = Resolve-TwrpChoice $robot
        if ($c.Choice -eq "guide") { Say "TWRP image for the flash steps: hack\recovery.img (the guide's)" }
        elseif ($c.Choice -eq "own-kernel") { Say "TWRP image for the flash steps: twrp-own-kernel.img (the guide's TWRP rebuilt around this robot's own kernel; built on the PC when a flash step needs it)" }
        else { Say ("TWRP image for the flash steps: not chosen yet. " + $c.Problem) }
    } else { Say "Robot: not known yet (it is recognised from its boot backup, step 'backup')." }
    $adb = $null
    try { $adb = Get-Adb } catch {}
    if ($adb) { Say ("adb state: " + $(Get-AdbState)) }
    $order = "prepare", "driver", "backup", "flash-twrp", "root", "flash-root", "android", "install", "wifi"
    foreach ($o in $order) { if (-not $s.ContainsKey("done-$o")) { Say ("Next step: " + $o); return } }
    Say "Everything is done. Optional: 'cleanup' (removes the MediaTek driver so memory integrity can be turned back on)."
}

function Step-Prepare {
    New-Item -ItemType Directory -Force -Path $Work, $Downloads, $Backup | Out-Null

    # Android platform tools (adb)
    if (-not (Test-Path (Join-Path $Work "platform-tools\adb.exe"))) {
        $zip = Join-Path $Downloads "platform-tools.zip"
        Fetch $Known.Tools.Url $zip
        Expand-Archive -Path $zip -DestinationPath $Work -Force
    }
    Ok ("adb: " + (Get-Adb))

    # Magisk
    $magisk = Join-Path $Downloads "Magisk-v31.0.apk"
    if (-not (Test-Path $magisk) -or (Sha $magisk) -ne $Known.Magisk.Sha) { Fetch $Known.Magisk.Url $magisk }
    if ((Sha $magisk) -ne $Known.Magisk.Sha) { Fail "Magisk-v31.0.apk checksum mismatch." }
    Ok "Magisk v31.0"

    # Scatter file and TWRP
    $hack = Join-Path $Work "hack"
    $twrp = Join-Path $hack "recovery.img"
    $scatter = Join-Path $hack "l706_dfbh_v_695scatter.txt"
    if (-not (Test-Path $twrp) -or -not (Test-Path $scatter)) {
        $zip = Join-Path $Downloads "Monsieur-Cuisine-Connect-Hack.zip"
        Fetch $Known.HackZip.Url $zip
        $tmp = Join-Path $Downloads "hack-tmp"
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        New-Item -ItemType Directory -Force -Path $hack | Out-Null
        Copy-Item (Get-ChildItem $tmp -Recurse -Filter recovery.img | Select-Object -First 1).FullName $twrp -Force
        Copy-Item (Get-ChildItem $tmp -Recurse -Filter l706_dfbh_v_695scatter.txt | Select-Object -First 1).FullName $scatter -Force
        Remove-Item -Recurse -Force $tmp
    }
    if ((Sha $twrp) -ne $Known.Twrp.Sha) { Fail "TWRP (recovery.img) checksum mismatch: do not flash it." }
    if ((Sha $scatter) -ne $Known.Scatter.Sha) { Fail "Scatter file checksum mismatch: do not continue (this guide covers the SKMC 1200 and the SKMC 1100 A1, which share this partition layout)." }
    Ok "TWRP and scatter file"

    # Toque APK (latest release)
    if ($Apk -eq "") {
        try {
            $rel = Invoke-RestMethod -Uri $Known.Releases -Headers @{ "User-Agent" = "toque-setup" }
            $asset = $rel.assets | Where-Object { $_.name -like "Toque-*.apk" -and $_.name -notlike "Toque-smart-*" } | Select-Object -First 1
            if ($asset) {
                $dest = Join-Path $Downloads $asset.name
                if (-not (Test-Path $dest)) { Fetch $asset.browser_download_url $dest }
                Ok ("Toque " + $rel.tag_name + ": " + $dest)
            } else { Say "No Toque APK in the latest release: build it (see README) or pass -Apk later." }
        } catch { Say ("GitHub releases unreachable: " + $_.Exception.Message) }
    }

    # Things that need a browser: the MediaTek driver and SP Flash Tool.
    $cab = Join-Path $Downloads $Known.DriverCab.Name
    $missing = $false
    if (-not (Test-Path $cab)) {
        $missing = $true
        Say ("MISSING: MediaTek PreLoader USB VCOM driver. In a browser, open https://www.catalog.update.microsoft.com/Search.aspx?q=MediaTek%20PreLoader%20USB%20VCOM , download the update whose file is named " + $Known.DriverCab.Name + " and save it as " + $cab)
    } elseif ((Sha $cab) -ne $Known.DriverCab.Sha) { Fail "Driver .cab checksum mismatch." }
    else { Ok "MediaTek driver package" }
    $tool = Get-FlashTool
    if (-not $tool) {
        $missing = $true
        Say ("MISSING: SP Flash Tool v5.1924 for Windows. Download it from https://spflashtool.com/download (file SP_Flash_Tool_v5.1924_Win.rar, AES password spflashtool.com; Windows 10 and Windows 11 before 23H2 need 7-Zip or WinRAR; a scripted download needs the header Referer: https://spflashtool.com/download) and extract it into " + (Join-Path $Work "SP_Flash_Tool"))
    } else {
        $da = Join-Path (Split-Path $tool) "MTK_AllInOne_DA.bin"
        if ((Sha $tool) -ne $Known.FlashTool.Sha -or -not (Test-Path $da) -or (Sha $da) -ne $Known.FlashDA.Sha) {
            Fail "SP Flash Tool files differ from v5.1924 (flash_tool.exe or MTK_AllInOne_DA.bin checksum): download the official v5.1924 again."
        }
        Ok ("SP Flash Tool v5.1924: " + $tool)
    }
    if ($missing) { Say "Download the missing files, then run 'prepare' again."; exit 3 }
    Set-State "done-prepare" (Get-Date).ToString("s")
    Ok "prepare done. Next: driver"
}

function Step-Driver {
    if (Test-MtkDriver) { Ok "MediaTek driver already installed." }
    else {
        if (Get-MemoryIntegrity) {
            NeedHuman "Windows memory integrity is ON and blocks the MediaTek driver (usb2ser.sys). Open Windows Security > Device security > Core isolation details, turn 'Memory integrity' OFF, then restart the PC. It can be turned back on at the end (step 'cleanup')."
        }
        $cab = Join-Path $Downloads $Known.DriverCab.Name
        if (-not (Test-Path $cab)) { Fail "Driver package missing: run 'prepare'." }
        $dir = Join-Path $Work "driver"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        & expand.exe -F:* $cab $dir | Out-Null
        $inf = Get-ChildItem $dir -Recurse -Filter cdc-acm.inf | Select-Object -First 1
        if (-not $inf) { Fail "cdc-acm.inf not found in the driver package." }
        Human "A Windows prompt (User Account Control) asks to allow 'PnP Utility' to make changes: click Yes."
        $p = Start-Process -FilePath pnputil.exe -ArgumentList @("/add-driver", "`"$($inf.FullName)`"", "/install") -Verb RunAs -Wait -PassThru
        Say ("pnputil exit code: " + $p.ExitCode)
        if (-not (Test-MtkDriver)) { Fail "The driver was not installed (prompt refused, or blocked by Windows)." }
        Ok "MediaTek driver installed."
    }
    Set-State "done-driver" (Get-Date).ToString("s")
    Ok "driver done. Next: backup"
}

function Step-Backup {
    $boot = Join-Path $Backup "boot-original.img"
    $rec = Join-Path $Backup "recovery-original.img"
    if (-not ((Test-Path $boot) -and (Test-Path $rec))) {
        Human "Switch the robot OFF with its rear switch (it can stay plugged into the mains). Unscrew the maintenance cover under the robot (small Torx). Plug the USB A-A cable into the RIGHT-HAND port marked 'Android USB' (never the other one: it can destroy the board) and into a USB-A port of the PC."
        $rb = @"
        <readback>
            <physical-readback is-physical-readback="false" />
            <readback-list>
                <readback-rom-item readback-index="0" readback-enable="true" readback-flag="NUTL_READ_PAGE_ONLY" start-address="0x0000000001d20000" readback-length="0x01000000" addr-flag="NUTL_ADDR_LOGICAL" part-id="8">$boot</readback-rom-item>
                <readback-rom-item readback-index="1" readback-enable="true" readback-flag="NUTL_READ_PAGE_ONLY" start-address="0x0000000002d20000" readback-length="0x01000000" addr-flag="NUTL_ADDR_LOGICAL" part-id="8">$rec</readback-rom-item>
            </readback-list>
        </readback>
"@
        $cfg = Write-FlashConfig "readback" $rb ""
        $code = Invoke-FlashTool $cfg "read back boot and recovery"
        if (-not ((Test-Path $boot) -and (Test-Path $rec))) {
            Fail ("Readback produced no files (exit code $code). Use the SP Flash Tool window instead: see AGENTS.md, 'GUI fallback: readback'. Save the files as $boot and $rec, then run 'backup' again.")
        }
    }
    Test-BootImage $boot "boot-original.img"
    Test-BootImage $rec "recovery-original.img"
    if ((Sha $boot) -eq (Sha $rec)) { Fail "boot and recovery backups are identical: the readback went wrong." }
    foreach ($f in $boot, $rec) { Set-ItemProperty -Path $f -Name IsReadOnly -Value $true }
    Set-State "boot-original-sha256" (Sha $boot)
    Set-State "recovery-original-sha256" (Sha $rec)
    Set-State "done-backup" (Get-Date).ToString("s")
    Ok "Backups verified (16 MiB Android boot images). Keep $Backup safe: it is the only way back."
    $robot = Get-RobotFacts $boot
    Set-State "robot-project" $robot.Project
    Say ("Robot: " + (Format-Robot $robot))
    $c = Resolve-TwrpChoice $robot
    if ($c.Choice -eq "guide") { Say "TWRP for this robot: the guide's image." }
    elseif ($c.Choice -eq "own-kernel") { Say "TWRP for this robot: the guide's TWRP rebuilt around the robot's own kernel (built on the PC by 'flash-twrp', nothing to do)." }
    else { Human $c.Problem }
    Human "Copy the folder $Backup to a second place (USB stick, cloud)."
    Ok "backup done. Next: flash-twrp"
}

# The guide's TWRP (header and ramdisk, unchanged) with the robot's own kernel and device tree,
# taken from the boot backup: for a robot whose board the guide's kernel was not built for (SKMC
# 1100 A1: its kernel is from 2022, the guide's from 2018 and without its touch driver). Built and
# checked on the PC; nothing is sent to the robot. Returns the image's path, or "" when the guide's
# TWRP already carries this robot's kernel.
function Build-OwnTwrp {
    $s = Get-State
    if (-not $s.ContainsKey("done-backup")) { Fail "Run 'backup' first: the image is built from the robot's own boot backup." }
    $boot = Join-Path $Backup "boot-original.img"
    Test-BootImage $boot "boot-original.img"
    if ((Sha $boot) -ne $s["boot-original-sha256"]) { Fail "boot-original.img no longer matches the checksum recorded by 'backup': do not continue." }
    $twrp = Get-GuideTwrp

    $r = Read-BootImage $boot "boot-original.img"
    $t = Read-BootImage $twrp "TWRP"
    Say ("Robot kernel: " + (Get-KernelVersion $r.Kernel))
    Say ("TWRP kernel : " + (Get-KernelVersion $t.Kernel))
    if ((Get-BytesSha $r.Kernel) -eq (Get-BytesSha $t.Kernel)) { return "" }
    # Both images must share the same layout, or the robot's bootloader would not load the result.
    foreach ($f in "PageSize", "KernelAddr", "RamdiskAddr", "SecondAddr", "TagsAddr") {
        if ($r.$f -ne $t.$f) { Fail ("The robot's boot image and TWRP differ on {0} (0x{1:x} / 0x{2:x}): not building. Report this robot's model and image version." -f $f, $r.$f, $t.$f) }
    }
    if ($r.SecondSize -ne 0 -or $t.SecondSize -ne 0 -or $r.DtSize -ne 0 -or $t.DtSize -ne 0) { Fail "A second stage or a separate device tree is present: not handled, not building." }
    foreach ($p in $r.Kernel, $r.Ramdisk, $t.Kernel, $t.Ramdisk) {
        if ([BitConverter]::ToUInt32($p, 0) -eq 0x58881688) { Fail "MediaTek sub-header found in one of the images: not handled, not building." }
    }
    if ([BitConverter]::ToUInt32($r.Kernel, 0x24) -ne 0x016F2818) { Fail "The robot's kernel is not an ARM zImage: not building." }
    if ($t.Ramdisk[0] -ne 0x1F -or $t.Ramdisk[1] -ne 0x8B) { Fail "TWRP's ramdisk is not gzip: not building." }

    $page = $t.PageSize
    $kPad = [long][Math]::Ceiling($r.Kernel.Length / $page) * $page
    $rPad = [long][Math]::Ceiling($t.Ramdisk.Length / $page) * $page
    $size = $page + $kPad + $rPad
    if ($size -gt $PartitionSize) { Fail ("The result would be {0} bytes: larger than the partition." -f $size) }
    $img = New-Object byte[] $size
    [Array]::Copy($t.Bytes, 0, $img, 0, $page)                                           # TWRP's header: command line, load addresses
    [Array]::Copy([BitConverter]::GetBytes([uint32]$r.Kernel.Length), 0, $img, 8, 4)     # kernel_size
    [Array]::Copy([BitConverter]::GetBytes([uint32]$t.Ramdisk.Length), 0, $img, 16, 4)   # ramdisk_size
    [Array]::Clear($img, 576, 32)
    [Array]::Copy((Get-BootImageId $r.Kernel $t.Ramdisk), 0, $img, 576, 20)              # id
    [Array]::Copy($r.Kernel, 0, $img, $page, $r.Kernel.Length)                           # the robot's kernel and device tree
    [Array]::Copy($t.Ramdisk, 0, $img, $page + $kPad, $t.Ramdisk.Length)                 # TWRP itself
    $out = Join-Path $Work "twrp-own-kernel.img"
    [IO.File]::WriteAllBytes($out, $img)

    # Read the result back from disk and compare each part with its source.
    $o = Read-BootImage $out "twrp-own-kernel.img"
    if ((Get-BytesSha $o.Kernel) -ne (Get-BytesSha $r.Kernel) -or (Get-BytesSha $o.Ramdisk) -ne (Get-BytesSha $t.Ramdisk)) {
        Remove-Item -Force $out
        Fail "The image written does not read back correctly: nothing to flash."
    }
    Set-State "twrp-own-kernel-sha256" (Sha $out)
    Set-State "twrp-own-kernel-boot-sha256" $s["boot-original-sha256"]
    Ok ("TWRP with this robot's own kernel: $out ({0} bytes)." -f $size)
    return $out
}

function Step-OwnTwrp {
    $robot = Get-Robot
    Say ("Robot: " + (Format-Robot $robot))
    $out = Build-OwnTwrp
    if ($out -eq "") { Ok "The guide's TWRP already carries this robot's kernel: nothing to build. Next: flash-twrp"; return }
    Set-State "done-own-twrp" (Get-Date).ToString("s")
    $c = Resolve-TwrpChoice $robot
    if ($c.Choice -eq "own-kernel") { Ok "'flash-twrp' and 'flash-root' will write this image instead of hack\recovery.img." }
    elseif ($c.Choice -eq "guide") { Say "Note: on this robot the flash steps keep the guide's image (hack\recovery.img). Run 'flash-twrp' with -Twrp own-kernel to write this one instead." }
    else { Say "Note: this robot is not one this guide knows: run 'flash-twrp' with -Twrp own-kernel to write this image." }
    Ok "own-twrp done. Next: flash-twrp"
}

function Step-FlashTwrp {
    if (-not (Get-State).ContainsKey("done-backup")) { Fail "Run 'backup' first: never flash without the backups." }
    # Which robot is this, and which TWRP suits it: decided from the backup, before anything is written.
    $robot = Get-Robot
    Say ("Robot: " + (Format-Robot $robot))
    $choice = Get-TwrpChoice $robot
    $twrp = Get-TwrpImage $robot
    Say ("TWRP image for this robot: " + $twrp)
    Set-State "twrp-choice" $choice
    $cfg = Write-DownloadConfig "flash-twrp" $twrp ""
    $code = Invoke-FlashTool $cfg ("TWRP into boot (" + (Split-Path $twrp -Leaf) + ")")
    if ($code -ne 0) { Fail ("SP Flash Tool reported an error ($code). Use the GUI: AGENTS.md, 'GUI fallback: Download Only' with boot = $twrp only.") }
    Set-State "done-flash-twrp" (Get-Date).ToString("s")
    Human "Switch the robot OFF, UNPLUG the USB cable, switch it ON: TWRP (a blue/black recovery screen) appears. Then plug the cable back in. (Switched on with the cable in, the robot stops on a battery screen.)"
    Ok "flash-twrp done ('root' checks what the boot partition now holds). Next: root (with TWRP on the robot's screen)"
}

function Step-Root {
    # The TWRP that 'flash-twrp' was to write, found first: TWRP restarts by itself after a few
    # minutes, so nothing slow may happen once it is up.
    $twrp = Get-TwrpImage (Get-Robot)
    $st = Wait-AdbState @("recovery") $WaitSeconds
    if ($st -ne "recovery") { NeedHuman "adb does not see TWRP (state: '$st'). The robot must show TWRP and the cable must be plugged in AFTER it started." }
    # This TWRP restarts by itself about 6 min 40 s after it started (watchdog): the step takes
    # about 20 s, so start it on a fresh TWRP.
    $uptime = 0
    [void][double]::TryParse(((Adb shell "cat /proc/uptime") -split " ")[0], [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$uptime)
    if ($uptime -gt 300) {
        NeedHuman ("TWRP has been running for {0:N0} s and restarts by itself at about 400 s. Restart it now (off, unplug, on, plug in once TWRP shows; swipe 'Allow Modifications' if asked), then run 'root' again right away." -f $uptime)
    }
    # Is the TWRP on screen the one this robot needs? A TWRP built for another board starts too,
    # but without touch: the step would fail further down, on a /system that cannot be unlocked.
    $dump = Join-Path $Work "boot-check.img"
    if (Read-RobotPartition "boot" $dump -InTwrp) {
        $diff = Compare-PartitionImage $dump $twrp
        Remove-Item -Force $dump
        if ($diff -ne "") { Fail ("The boot partition does not hold " + (Split-Path $twrp -Leaf) + ", the TWRP this robot needs: " + $diff + ". Nothing was changed. Run 'flash-twrp' again (or the GUI fallback with boot = $twrp), then 'root'.") }
        Ok ("boot partition = " + (Split-Path $twrp -Leaf))
    } else {
        if (Test-Path $dump) { Remove-Item -Force $dump }
        Say "WARNING: could not read the boot partition back to check it; TWRP is running, so the step goes on."
    }
    $boot = Join-Path $Backup "boot-original.img"
    Test-BootImage $boot "boot-original.img"
    $magisk = Join-Path $Downloads "Magisk-v31.0.apk"
    if ((Sha $magisk) -ne $Known.Magisk.Sha) { Fail "Magisk-v31.0.apk checksum mismatch: run 'prepare'." }
    $key = Join-Path $env:USERPROFILE ".android\adbkey.pub"
    Say (Adb shell "rm -rf /tmp/magisk /tmp/orig /tmp/twrp-root.sh")
    Say (Adb push $magisk /tmp/magisk.apk)
    Say (Adb push $boot /tmp/boot.img)
    if (Test-Path $key) { Say (Adb push $key /tmp/adbkey.pub) }
    $script = Join-Path $Repo "tools\robot\twrp-root.sh"
    Say (Adb push $script /tmp/twrp-root.sh)
    $out = Adb shell "sh /tmp/twrp-root.sh"
    Say $out
    if ($out -notmatch "== Done") { Fail "twrp-root.sh did not finish: nothing was flashed. Read the output above." }
    $patched = Join-Path $Work "boot-magisk.img"
    if (Test-Path $patched) { Remove-Item -Force $patched }
    Say (Adb pull /tmp/magisk/new-boot.img $patched)
    Test-BootImage $patched "boot-magisk.img"
    $orig = Join-Path $Work "system-originals"
    New-Item -ItemType Directory -Force -Path $orig | Out-Null
    Say (Adb pull /tmp/orig $orig)
    Set-State "boot-magisk-sha256" (Sha $patched)
    Set-State "done-root" (Get-Date).ToString("s")
    Ok "Magisk boot image ready: $patched. Next: flash-root"
}

function Step-FlashRoot {
    if (-not (Get-State).ContainsKey("done-root")) { Fail "Run 'root' first." }
    $patched = Join-Path $Work "boot-magisk.img"
    Test-BootImage $patched "boot-magisk.img"
    if ((Sha $patched) -ne (Get-State)["boot-magisk-sha256"]) { Fail "boot-magisk.img no longer matches the checksum recorded by 'root': run 'root' again." }
    $robot = Get-Robot
    Say ("Robot: " + (Format-Robot $robot))
    $twrp = Get-TwrpImage $robot
    Say ("TWRP image for this robot: " + $twrp)
    $cfg = Write-DownloadConfig "flash-root" $patched $twrp
    $code = Invoke-FlashTool $cfg ("Magisk boot into boot, TWRP into recovery (" + (Split-Path $twrp -Leaf) + ")")
    if ($code -ne 0) { Fail ("SP Flash Tool reported an error ($code). Use the GUI: AGENTS.md, 'GUI fallback: Download Only' with boot = $patched and recovery = $twrp.") }
    Set-State "done-flash-root" (Get-Date).ToString("s")
    Human "Switch the robot OFF, UNPLUG the USB cable, switch it ON: the stock Monsieur Cuisine app starts. Then plug the cable back in."
    Ok "flash-root done ('android' checks what boot and recovery now hold). Next: android"
}

function Step-Android {
    $st = Wait-AdbState @("device", "unauthorized") $WaitSeconds
    if ($st -eq "unauthorized") { NeedHuman "The robot asks to allow USB debugging: tick 'Always allow' and tap OK on its screen." }
    if ($st -ne "device") { NeedHuman "adb does not see Android (state: '$st'). The stock app must be on screen and the cable plugged in after start-up." }
    $magiskV = Adb shell "magisk -v"
    if ($magiskV -notmatch "MAGISK") { Fail "Magisk is not running: the boot partition is not the patched one. Check the flash-root step." }
    Ok ("Magisk " + $magiskV)
    $pkgs = Adb shell "pm list packages com.topjohnwu.magisk"
    if ($pkgs -notmatch "com.topjohnwu.magisk") {
        Say (Adb install -r (Join-Path $Downloads "Magisk-v31.0.apk"))
    }
    Human "Watch the robot's screen: Magisk asks for superuser access for 'Shell' in a few seconds. Tap GRANT (Autoriser) within 10 seconds."
    Start-Sleep -Seconds 8
    $id = Adb shell "su -c 'id -u'"
    if ($id -ne "0") {
        NeedHuman "Root was not granted to 'Shell'. If the prompt was missed, Magisk now refuses for good: open the Magisk app > Superuser, switch 'Shell' on. Then run 'android' again."
    }
    # What 'flash-root' wrote is read back from the robot and compared on the PC. A difference stops
    # here, before Toque is installed: a boot that is not the patched one, or a recovery that is not
    # TWRP, would only show up later, when it is needed.
    $expected = (Get-State)["boot-magisk-sha256"]
    $patched = Join-Path $Work "boot-magisk.img"
    if (-not $expected -and (Test-Path $patched)) { $expected = Sha $patched }
    if (-not $expected) { Say "WARNING: no boot-magisk.img from the 'root' step on this PC: the boot partition cannot be compared with it." }
    else {
        $local = Join-Path $Work "boot-check.img"
        if (-not (Read-RobotPartition "boot" $local)) {
            if (Test-Path $local) { Remove-Item -Force $local }
            Fail "Could not read the boot partition back to check it. Run 'android' again (root must be granted to 'Shell')."
        }
        $got = Sha $local
        Remove-Item -Force $local
        if ($got -ne $expected) { Fail ("The boot partition differs from boot-magisk.img ($got). Do not go on: run 'flash-root' again, then 'android'. (Never use 'Direct install' in the Magisk app: it rewrites boot.)") }
        Ok "boot partition = boot-magisk.img"
    }
    $twrp = Get-TwrpImage (Get-Robot)
    $local = Join-Path $Work "recovery-check.img"
    if (-not (Read-RobotPartition "recovery" $local)) {
        if (Test-Path $local) { Remove-Item -Force $local }
        Fail "Could not read the recovery partition back to check it. Run 'android' again."
    }
    $diff = Compare-PartitionImage $local $twrp
    Remove-Item -Force $local
    if ($diff -ne "") { Fail ("The recovery partition does not hold " + (Split-Path $twrp -Leaf) + ": " + $diff + ". Run 'flash-root' again (or the GUI fallback with recovery = $twrp), then 'android'.") }
    Ok ("recovery partition = " + (Split-Path $twrp -Leaf))
    Set-State "done-android" (Get-Date).ToString("s")
    Ok "Root works (uid 0). Next: install"
}

# The Toque APK to install when -Apk is not given: the highest version among downloads\Toque-X.Y.Z.apk
# (by its number, not by the date of the file: an older release downloaded later must not win).
# The Smart's APK, Toque-smart-X.Y.Z.apk, never matches: it is not for a Connect.
function Get-ToqueApk {
    $best = $null
    $bestVersion = $null
    foreach ($f in @(Get-ChildItem $Downloads -Filter "Toque-*.apk" -ErrorAction SilentlyContinue)) {
        $m = [regex]::Match($f.Name, "^Toque-(\d+(\.\d+){1,3})\.apk$")
        if (-not $m.Success) { continue }
        $v = [version]$m.Groups[1].Value
        if ($null -eq $bestVersion -or $v -gt $bestVersion) { $best = $f; $bestVersion = $v }
    }
    if ($best) { return $best.FullName }
    return ""
}

function Step-Install {
    $apkPath = $Apk
    if ($apkPath -eq "") { $apkPath = Get-ToqueApk }
    if ($apkPath -eq "" -or -not (Test-Path $apkPath)) { Fail "No Toque APK: run 'prepare' (downloads the latest release) or pass -Apk <file>." }
    Say ("Toque APK: " + $apkPath)
    $adb = Get-Adb
    $installArgs = @{ Apk = $apkPath; Adb = $adb }
    if ($Ip -ne "") { $installArgs["Ip"] = $Ip }
    $global:LASTEXITCODE = 0
    & (Join-Path $Repo "tools\install-robot.ps1") @installArgs
    if ($LASTEXITCODE -ne 0) { Fail "Toque was not installed (see the lines above): the step is not marked as done." }
    $ver = Adb shell "dumpsys package app.toque | grep versionName"
    if ($ver -notmatch "versionName") { Fail "Toque is not installed." }
    $mode = Get-MachineMode
    if ($mode -ne "" -and $mode -ne "hardware") {
        NeedHuman ("Toque is in '" + $mode + "' mode, so the motor is not driven. On the robot, EMPTY bowl, lid closed, hand near the main switch: Toque > Settings > Advanced > Machine > Real machine > Apply (in demo mode, a dot marks 'Advanced' in the menu on the left of Settings), then run the install step again so the mode can be verified. Success: the badge reads Ready/« Base prête » with no Demo or Twin marker AND the journal must show the real link, e.g. 'liaison ouverte : Base moteur (/dev/ttyMT...)'; the simulated-base line means the mode was not applied.")
    }
    if ($mode -eq "hardware") { Ok "Toque machine mode: hardware (real machine)." }
    elseif ($mode -eq "") { Say "WARNING: Toque machine mode could not be verified (su or prefs unreadable). On the robot, Settings > Advanced > Machine > Real machine must be applied before the motor can run." }
    Set-State "done-install" (Get-Date).ToString("s")
    Ok ("Toque installed: " + $ver.Trim())
    Ok "install done. Next: wifi (optional)"
}

function Step-Wifi {
    # -Ip is the Wi-Fi address ("10.0.0.5" or "10.0.0.5:5555"). Before the switch the robot is on
    # the cable, with a serial; already on Wi-Fi it is the TCP device only, and the cable is not
    # needed, so fall back to any device. setprop/tcpip are harmless over the TCP serial.
    $usb = Get-AdbDevice -Usb
    if (-not $usb -or $usb.State -ne "device") { $usb = Get-AdbDevice }
    if (-not $usb) { NeedHuman "Connect the robot with the USB cable (Android started), then run 'wifi' again." }
    if ($usb.State -ne "device") { NeedHuman ("adb sees the robot as '" + $usb.State + "': accept the ADB prompt on the robot (or unplug/replug) and run 'wifi' again.") }
    $robotIp = $Ip -replace ':5555$', ''
    if ($robotIp -eq "") {
        $addr = Adb -s $usb.Serial shell "ip -f inet addr show wlan0"
        $m = [regex]::Match($addr, "inet (\d+\.\d+\.\d+\.\d+)")
        if (-not $m.Success) {
            NeedHuman "The robot is not on Wi-Fi. In Toque, tap the clock at the top right 5 times quickly to open the Android settings, join the home Wi-Fi, come back with the 'Back to Toque' button."
        }
        $robotIp = $m.Groups[1].Value
    }
    Say (Adb -s $usb.Serial shell "su -c 'setprop persist.adb.tcp.port 5555'")
    Say (Adb -s $usb.Serial tcpip 5555)
    Start-Sleep -Seconds 5
    Say (Adb connect "${robotIp}:5555")
    $check = Adb -s "${robotIp}:5555" shell "su -c 'id -u'"
    if ($check -ne "0") { Fail "Wi-Fi adb does not answer as root yet: unplug and replug the cable, then run 'wifi' again." }
    Set-State "robot-ip" $robotIp
    Set-State "done-wifi" (Get-Date).ToString("s")
    Ok ("ADB over Wi-Fi: ${robotIp}:5555 (kept after restarts). Home network only.")
    Human "The cable is no longer needed: screw the maintenance cover back on."
}

function Step-Cleanup {
    $out = pnputil /enum-drivers | Out-String
    $blocks = $out -split "(\r?\n){2,}"
    $published = @()
    foreach ($b in $blocks) {
        # cdc-acm.inf is a generic name: only remove the MediaTek one.
        if ($b -match "cdc-acm\.inf" -and $b -match "MediaTek") {
            $m = [regex]::Match($b, "(oem\d+\.inf)")
            if ($m.Success) { $published += $m.Groups[1].Value }
        }
    }
    foreach ($oem in $published) {
        Human "A Windows prompt (User Account Control) asks to allow 'PnP Utility': click Yes."
        Start-Process -FilePath pnputil.exe -ArgumentList @("/delete-driver", $oem, "/uninstall", "/force") -Verb RunAs -Wait | Out-Null
        Say ("Removed driver " + $oem)
    }
    Human "You can turn Windows memory integrity back on: Windows Security > Device security > Core isolation details > Memory integrity ON, then restart."
    Set-State "done-cleanup" (Get-Date).ToString("s")
}

# Dot-sourcing (". toque-setup.ps1") only loads the functions, for tests.
if ($MyInvocation.InvocationName -eq ".") { return }
New-Item -ItemType Directory -Force -Path $Work | Out-Null
switch ($Step) {
    "status" { Step-Status }
    "prepare" { Step-Prepare }
    "driver" { Step-Driver }
    "backup" { Step-Backup }
    "own-twrp" { Step-OwnTwrp }
    "flash-twrp" { Step-FlashTwrp }
    "root" { Step-Root }
    "flash-root" { Step-FlashRoot }
    "android" { Step-Android }
    "install" { Step-Install }
    "wifi" { Step-Wifi }
    "cleanup" { Step-Cleanup }
}
