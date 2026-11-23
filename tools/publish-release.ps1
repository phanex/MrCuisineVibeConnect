# Publishes a Toque release on GitHub from the PC (the same thing .github/workflows/release.yml does
# when GitHub Actions is available): tag vX.Y.Z, release notes from the commits, the APK attached.
# Robots then find it in Settings > Updates, and toque-setup.ps1 downloads it.
#
#   powershell -ExecutionPolicy Bypass -File tools\publish-release.ps1 -Apk Toque-0.8.0.apk -SmartApk Toque-smart-0.8.0.apk [-RepoInfo]
#
# -Apk: the Connect APK (gradlew assembleRelease), attached first: older Toque versions take the first APK.
# -SmartApk: the Smart APK (gradlew assembleRelease -PtoqueSmart=true), attached as Toque-smart-X.Y.Z.apk.
#
# Token: $env:GH_TOKEN, else $env:GITHUB_TOKEN, else "gh auth token", else the github.com
# credential already stored for git (Git Credential Manager). It is never printed.
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$SmartApk = "",
    [string]$Repo = "Wuki60/MrCuisineVibeConnect",
    [switch]$RepoInfo,
    # Pre-release: on GitHub but not offered to robots (they read /releases/latest) until -Promote.
    [switch]$Prerelease,
    [switch]$Promote
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root

function Get-Token {
    if ($env:GH_TOKEN) { return $env:GH_TOKEN }
    if ($env:GITHUB_TOKEN) { return $env:GITHUB_TOKEN }
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if ($gh) {
        $t = (& gh auth token 2>$null | Out-String).Trim()
        if ($t) { return $t }
    }
    $cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
    $line = $cred | Where-Object { $_ -like "password=*" } | Select-Object -First 1
    if ($line) { return $line.Substring(9) }
    throw "No GitHub token found (GH_TOKEN, gh, or git credentials)."
}

$version = ([regex]::Match((Get-Content "app\build.gradle.kts" -Raw), 'versionName\s*=\s*"([^"]+)"')).Groups[1].Value
if (-not $version) { throw "versionName not found in app\build.gradle.kts" }
$tag = "v$version"
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
if ($SmartApk -and -not (Test-Path $SmartApk)) { throw "Smart APK not found: $SmartApk" }
$token = Get-Token
$h = @{ Authorization = "Bearer $token"; Accept = "application/vnd.github+json"; "User-Agent" = "toque-publish"; "X-GitHub-Api-Version" = "2022-11-28" }
$api = "https://api.github.com/repos/$Repo"

$release = $null
try { $release = Invoke-RestMethod -Uri "$api/releases/tags/$tag" -Headers $h } catch {}
if (-not $release) {
    $prev = $null
    try { $prev = (Invoke-RestMethod -Uri "$api/releases/latest" -Headers $h).tag_name } catch {}
    git fetch --tags --quiet 2>$null | Out-Null
    $range = "HEAD~10..HEAD"
    if ($prev) {
        git rev-parse -q --verify "refs/tags/$prev" 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $range = "$prev..HEAD" }
    }
    $notes = (git log --no-merges --pretty="- %s" $range | Out-String).Trim()
    $sha = (git rev-parse HEAD).Trim()
    $body = @{ tag_name = $tag; target_commitish = $sha; name = "Toque $version"; body = $notes; prerelease = [bool]$Prerelease } | ConvertTo-Json
    $release = Invoke-RestMethod -Method Post -Uri "$api/releases" -Headers $h -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType "application/json; charset=utf-8"
    Write-Host "Release $tag created."
} else {
    Write-Host "Release $tag already exists."
}
if ($Promote -and $release.prerelease) {
    $patch = @{ prerelease = $false; make_latest = "true" } | ConvertTo-Json
    $release = Invoke-RestMethod -Method Patch -Uri "$api/releases/$($release.id)" -Headers $h -Body ([Text.Encoding]::UTF8.GetBytes($patch)) -ContentType "application/json; charset=utf-8"
    Write-Host "Release $tag is now the latest version: robots will be offered it."
}

$name = "Toque-$version.apk"
if (-not ($release.assets | Where-Object { $_.name -eq $name })) {
    $upload = "https://uploads.github.com/repos/$Repo/releases/$($release.id)/assets?name=$name"
    Invoke-RestMethod -Method Post -Uri $upload -Headers $h -InFile $Apk -ContentType "application/vnd.android.package-archive" | Out-Null
    Write-Host "$name attached."
} else {
    Write-Host "$name already attached."
}
if ($SmartApk) {
    $smartName = "Toque-smart-$version.apk"
    $release = Invoke-RestMethod -Uri "$api/releases/$($release.id)" -Headers $h
    if (-not ($release.assets | Where-Object { $_.name -eq $smartName })) {
        $upload = "https://uploads.github.com/repos/$Repo/releases/$($release.id)/assets?name=$smartName"
        Invoke-RestMethod -Method Post -Uri $upload -Headers $h -InFile $SmartApk -ContentType "application/vnd.android.package-archive" | Out-Null
        Write-Host "$smartName attached."
    } else {
        Write-Host "$smartName already attached."
    }
}

if ($RepoInfo) {
    $info = @{
        description = "Toque: a modern replacement interface for the Lidl Monsieur Cuisine Connect (SKMC 1200, SKMC 1100) and Smart (SKMS 1200, in test), with the official recipes, guided cooking and an AI-agent-friendly install guide."
        homepage = "https://github.com/$Repo#readme"
    } | ConvertTo-Json
    Invoke-RestMethod -Method Patch -Uri $api -Headers $h -Body ([Text.Encoding]::UTF8.GetBytes($info)) -ContentType "application/json; charset=utf-8" | Out-Null
    $topics = @{ names = @("monsieur-cuisine", "monsieur-cuisine-connect", "monsieur-cuisine-smart", "lidl", "silvercrest", "android", "kotlin", "magisk", "root", "recipes", "ai-agent") } | ConvertTo-Json
    Invoke-RestMethod -Method Put -Uri "$api/topics" -Headers $h -Body ([Text.Encoding]::UTF8.GetBytes($topics)) -ContentType "application/json; charset=utf-8" | Out-Null
    Write-Host "Repository description and topics updated."
}
Write-Host ("https://github.com/$Repo/releases/tag/$tag")
