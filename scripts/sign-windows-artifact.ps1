param(
    [Parameter(Mandatory = $true)]
    [string]$FilePath
)

$ErrorActionPreference = 'Stop'

$resolvedFilePath = [System.IO.Path]::GetFullPath($FilePath)
if (-not (Test-Path -LiteralPath $resolvedFilePath)) {
    throw "Signing target does not exist: $resolvedFilePath"
}

$certificateBase64 = $env:WINDOWS_CERTIFICATE_PFX_BASE64
$certificatePassword = $env:WINDOWS_CERTIFICATE_PASSWORD
$requireSigning = $env:COGNINOTE_REQUIRE_WINDOWS_SIGNING -eq 'true'

$existingSignature = Get-AuthenticodeSignature -LiteralPath $resolvedFilePath
if ($existingSignature.Status -eq 'Valid') {
    Write-Host "Windows artifact is already signed: $resolvedFilePath"
    exit 0
}

if ([string]::IsNullOrWhiteSpace($certificateBase64) -or [string]::IsNullOrWhiteSpace($certificatePassword)) {
    if ($requireSigning) {
        throw 'Windows signing is required, but WINDOWS_CERTIFICATE_PFX_BASE64 or WINDOWS_CERTIFICATE_PASSWORD is missing.'
    }

    Write-Host "Windows signing secrets are not configured; leaving unsigned: $resolvedFilePath"
    exit 0
}

function Resolve-Signtool {
    $direct = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($direct) {
        return $direct.Source
    }

    $kitsRoot = "${env:ProgramFiles(x86)}\Windows Kits\10\bin"
    if (-not (Test-Path -LiteralPath $kitsRoot)) {
        throw 'signtool.exe was not found on PATH and Windows Kits directory does not exist.'
    }

    $candidate = Get-ChildItem -LiteralPath $kitsRoot -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\x64\\signtool\.exe$' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1

    if (-not $candidate) {
        throw 'signtool.exe was not found in Windows Kits x64 tool directories.'
    }

    return $candidate.FullName
}

$tempPfx = Join-Path ([System.IO.Path]::GetTempPath()) "cogninote-signing-$([System.Guid]::NewGuid()).pfx"
try {
    [System.IO.File]::WriteAllBytes($tempPfx, [Convert]::FromBase64String($certificateBase64))

    $signtool = Resolve-Signtool
    $timestampUrl = if ([string]::IsNullOrWhiteSpace($env:WINDOWS_TIMESTAMP_URL)) {
        'http://timestamp.digicert.com'
    } else {
        $env:WINDOWS_TIMESTAMP_URL
    }

    $arguments = @(
        'sign',
        '/f', $tempPfx,
        '/p', $certificatePassword,
        '/fd', 'SHA256',
        '/tr', $timestampUrl,
        '/td', 'SHA256',
        $resolvedFilePath
    )

    & $signtool @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "signtool failed with exit code $LASTEXITCODE for $resolvedFilePath"
    }

    $signature = Get-AuthenticodeSignature -LiteralPath $resolvedFilePath
    if ($signature.Status -ne 'Valid') {
        throw "Authenticode signature is not valid for ${resolvedFilePath}: $($signature.Status)"
    }

    Write-Host "Signed Windows artifact: $resolvedFilePath"
} finally {
    if (Test-Path -LiteralPath $tempPfx) {
        Remove-Item -LiteralPath $tempPfx -Force
    }
}
