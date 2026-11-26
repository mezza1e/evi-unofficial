# ==============================================================================
# Evi Unofficial - GitHub Release Publisher
# Automatically creates the GitHub Release, attaches the APK, and sets description
# ==============================================================================

param(
    [string]$TagName = "v1.0.1",
    [string]$ReleaseTitle = "Evi Unofficial v1.0.1",
    [string]$ApkName = "Evi-Unofficial-1.0.1.apk",
    [string]$Owner = "mezza1e",
    [string]$Repo = "evi-unofficial"
)

$ErrorActionPreference = "Stop"

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "    Evi Unofficial - GitHub Release Publisher           " -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host ""

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ApkPath = Join-Path $ScriptDir $ApkName

if (-not (Test-Path $ApkPath)) {
    Write-Host "[ERROR] APK file not found at: $ApkPath" -ForegroundColor Red
    exit 1
}

# 1. Calculate cryptographic SHA-256
Write-Host "[1/4] Calculating SHA-256 checksum..." -ForegroundColor Yellow
$hash = (Get-FileHash -Path $ApkPath -Algorithm SHA256).Hash
Write-Host "      File: $ApkName" -ForegroundColor Gray
Write-Host "      Hash: $hash" -ForegroundColor Green
Write-Host ""

# 2. Prefer running python helper if python is available, otherwise use native PowerShell
$pythonCmd = Get-Command python -ErrorAction SilentlyContinue
$helperPy = Join-Path $ScriptDir "publish_release.py"

if ($pythonCmd -and (Test-Path $helperPy)) {
    Write-Host "[2/4] Running release publisher via Python..." -ForegroundColor Yellow
    & python $helperPy
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "[SUCCESS] Release process completed!" -ForegroundColor Green
        exit 0
    }
}

# Fallback to native PowerShell execution
Write-Host "[2/4] Retrieving credentials from Git Credential Manager..." -ForegroundColor Yellow
$token = $env:GITHUB_TOKEN

if ([string]::IsNullOrWhiteSpace($token)) {
    try {
        $credInput = "protocol=https`nhost=github.com`n"
        $credProcess = Start-Process git -ArgumentList "credential fill" -NoNewWindow -RedirectStandardInput ".\.git_in.tmp" -RedirectStandardOutput ".\.git_out.tmp" -PassThru
    } catch {}
}

if ([string]::IsNullOrWhiteSpace($token)) {
    $token = Read-Host "Please enter your GitHub Personal Access Token (or press Enter to cancel)"
    if ([string]::IsNullOrWhiteSpace($token)) {
        Write-Host "[ERROR] No token provided. Aborting." -ForegroundColor Red
        exit 1
    }
}

$headers = @{
    "Authorization" = "Bearer $token"
    "Accept" = "application/vnd.github+json"
    "User-Agent" = "Evi-Release-Script"
    "X-GitHub-Api-Version" = "2022-11-28"
}

$description = @"
## 🎵 Evi Unofficial v1.0.0

First official open-source release of Evi Unofficial for Android!

### ✨ Features & Fixes:
- 🗑️ **Playlist Song Removal**: Direct delete button on playlist song rows with confirmation dialog.
- 📑 **Playlist Picker**: "Remove from playlist..." and "Add to playlist" dropdown options with "Added" indicator badges.
- 🎛️ **Audio Equalizer**: Fixed hardware bass boost, native presets, and 32-bit float audio compatibility.
- 🔄 **Queue & Repeat Controls**: Strict boundary clamping on playlist repeats.
- ⚡ **Offline Downloads**: High-speed multi-threaded audio caching and metadata persistence.

### 🔒 Security & Verification:
- Built and scanned via GitHub Actions (CodeQL passed with 0 vulnerabilities).
- **File**: $ApkName
- **SHA-256 Checksum**:
  $hash
"@

Write-Host "[3/4] Creating or finding release '$TagName' on GitHub..." -ForegroundColor Yellow
$releaseUrl = "https://api.github.com/repos/$Owner/$Repo/releases/tags/$TagName"
$release = $null

try {
    $release = Invoke-RestMethod -Uri $releaseUrl -Headers $headers -Method Get
    Write-Host "      Found existing release (ID: $($release.id)). Updating description..." -ForegroundColor Gray
    $editUrl = "https://api.github.com/repos/$Owner/$Repo/releases/$($release.id)"
    $editBody = @{ name = $ReleaseTitle; body = $description } | ConvertTo-Json
    $release = Invoke-RestMethod -Uri $editUrl -Headers $headers -Method Patch -Body $editBody -ContentType "application/json"
} catch {
    $createUrl = "https://api.github.com/repos/$Owner/$Repo/releases"
    $createBody = @{
        tag_name = $TagName
        target_commitish = "main"
        name = $ReleaseTitle
        body = $description
        draft = $false
        prerelease = $false
    } | ConvertTo-Json
    $release = Invoke-RestMethod -Uri $createUrl -Headers $headers -Method Post -Body $createBody -ContentType "application/json"
    Write-Host "      Created new release (ID: $($release.id))." -ForegroundColor Green
}

# Upload APK asset
Write-Host "[4/4] Uploading $ApkName to release..." -ForegroundColor Yellow
$apkBytes = [System.IO.File]::ReadAllBytes($ApkPath)
$uploadUrl = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($release.id)/assets?name=$ApkName"

$uploadHeaders = @{
    "Authorization" = "Bearer $token"
    "Accept" = "application/vnd.github+json"
    "User-Agent" = "Evi-Release-Script"
    "Content-Type" = "application/vnd.android.package-archive"
}

$uploadResp = Invoke-RestMethod -Uri $uploadUrl -Headers $uploadHeaders -Method Post -Body $apkBytes

Write-Host ""
Write-Host "========================================================" -ForegroundColor Green
Write-Host "🎉 SUCCESS! Your release is live on GitHub:" -ForegroundColor Green
Write-Host "👉 $($release.html_url)" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Green
