[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$PortableZip,
    [Parameter(Mandatory)]
    [string]$InstallerPath,
    [Parameter(Mandatory)]
    [string]$ExpectedVersion
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$portableZipPath = if ([System.IO.Path]::IsPathFullyQualified($PortableZip)) {
    [System.IO.Path]::GetFullPath($PortableZip)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $PortableZip))
}
$installerFullPath = if ([System.IO.Path]::IsPathFullyQualified($InstallerPath)) {
    [System.IO.Path]::GetFullPath($InstallerPath)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $InstallerPath))
}
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("cogninote-desktop-smoke-" + [Guid]::NewGuid().ToString('N'))
$extractRoot = Join-Path $temporaryRoot 'portable'
$installRoot = Join-Path $temporaryRoot 'installed'
$appDataRoot = Join-Path $temporaryRoot 'appdata'
$localAppDataRoot = Join-Path $temporaryRoot 'localappdata'
$storageRoot = Join-Path $temporaryRoot 'storage'
$backendProcess = $null
$oldAppData = $env:APPDATA
$oldLocalAppData = $env:LOCALAPPDATA

function Get-AvailableTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Wait-ForStatus {
    param([int]$Port)

    $deadline = [DateTimeOffset]::Now.AddSeconds(60)
    do {
        try {
            return Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/system/status" -TimeoutSec 2
        } catch {
            Start-Sleep -Milliseconds 250
        }
    } while ([DateTimeOffset]::Now -lt $deadline)
    throw "Bundled backend did not become ready on port $Port."
}

if (-not (Test-Path -LiteralPath $portableZipPath -PathType Leaf)) {
    throw "Portable zip not found: $portableZipPath"
}
if (-not (Test-Path -LiteralPath $installerFullPath -PathType Leaf)) {
    throw "Installer not found: $installerFullPath"
}

New-Item -ItemType Directory -Force -Path $extractRoot, $installRoot, $appDataRoot, $localAppDataRoot, $storageRoot | Out-Null
$env:APPDATA = $appDataRoot
$env:LOCALAPPDATA = $localAppDataRoot
try {
    Expand-Archive -LiteralPath $portableZipPath -DestinationPath $extractRoot -Force
    $portableRoot = Join-Path $extractRoot 'CogniNote'
    $desktopLauncher = Join-Path $portableRoot 'CogniNote.exe'
    $backendRoot = Join-Path $portableRoot 'backend\CogniNoteBackend'
    $backendLauncher = Join-Path $backendRoot 'CogniNoteBackend.exe'
    foreach ($requiredPath in @($desktopLauncher, $backendLauncher)) {
        if (-not (Test-Path -LiteralPath $requiredPath)) {
            throw "Portable package is missing: $requiredPath"
        }
    }

    $forbiddenFiles = Get-ChildItem -LiteralPath $portableRoot -Recurse -File -Force | Where-Object {
        $_.Name -eq '.env' -or $_.Extension -in @('.db', '.log') -or $_.FullName -match '[\\/]e2e[\\/]fixtures[\\/]'
    }
    if ($forbiddenFiles) {
        throw "Portable package contains test or user data: $($forbiddenFiles.FullName -join ', ')"
    }

    $port = Get-AvailableTcpPort
    $backendProcess = Start-Process -FilePath $backendLauncher -ArgumentList @(
        "--server.port=$port",
        '--server.address=127.0.0.1',
        "--app.storage.base-dir=$storageRoot",
        "--app.storage.database-path=$(Join-Path $storageRoot 'data\cogninote.db')",
        '--app.desktop.enabled=false'
    ) -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $temporaryRoot 'backend.stdout.log') `
        -RedirectStandardError (Join-Path $temporaryRoot 'backend.stderr.log')

    $status = Wait-ForStatus -Port $port
    if ($status.data.version -ne $ExpectedVersion) {
        throw "Backend version mismatch. Expected $ExpectedVersion, got $($status.data.version)."
    }
    $indexHtml = Invoke-WebRequest -Uri "http://127.0.0.1:$port/" -TimeoutSec 10
    if ($indexHtml.StatusCode -ne 200 -or $indexHtml.Content -notmatch '<html') {
        throw 'Bundled backend did not serve the static frontend.'
    }
    $assetPath = [regex]::Match($indexHtml.Content, '(?:src|href)="(?<path>/assets/[^\"]+)"').Groups['path'].Value
    if (-not $assetPath) {
        throw 'Bundled frontend did not reference an /assets resource.'
    }
    if ((Invoke-WebRequest -Uri "http://127.0.0.1:$port$assetPath" -TimeoutSec 10).StatusCode -ne 200) {
        throw "Bundled frontend asset could not be read: $assetPath"
    }

    Stop-Process -Id $backendProcess.Id -Force
    Wait-Process -Id $backendProcess.Id -Timeout 10 -ErrorAction SilentlyContinue
    $backendProcess = $null

    $installer = Start-Process -FilePath $installerFullPath -ArgumentList @('/S', "/D=$installRoot") -PassThru -Wait -WindowStyle Hidden
    if ($installer.ExitCode -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $installRoot 'CogniNote.exe'))) {
        throw "NSIS silent install failed with exit code $($installer.ExitCode)."
    }
    $uninstallerPath = Get-ChildItem -LiteralPath $installRoot -Filter 'uninstall*.exe' -File | Select-Object -First 1
    if (-not $uninstallerPath) {
        throw 'NSIS installation did not create an uninstaller.'
    }
    $uninstaller = Start-Process -FilePath $uninstallerPath.FullName -ArgumentList '/S' -PassThru -Wait -WindowStyle Hidden
    if ($uninstaller.ExitCode -ne 0) {
        throw "NSIS silent uninstall failed with exit code $($uninstaller.ExitCode)."
    }
} finally {
    $env:APPDATA = $oldAppData
    $env:LOCALAPPDATA = $oldLocalAppData
    if ($backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id -Force
        Wait-Process -Id $backendProcess.Id -Timeout 10 -ErrorAction SilentlyContinue
    }
    $resolvedTemporaryRoot = [System.IO.Path]::GetFullPath($temporaryRoot)
    $systemTemporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTemporaryRoot.StartsWith($systemTemporaryRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTemporaryRoot).StartsWith('cogninote-desktop-smoke-') -and
        (Test-Path -LiteralPath $resolvedTemporaryRoot)) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}

Write-Host "Windows desktop package smoke passed for version $ExpectedVersion."
