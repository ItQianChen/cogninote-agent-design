param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^\d+\.\d+\.\d+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$')]
    [string]$Version,

    [switch]$SkipDocs,
    [switch]$AllowDirty,
    [switch]$WhatIf
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Resolve-RepoFile {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    return Join-Path $RepoRoot $RelativePath
}

function Read-TextFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [System.IO.File]::ReadAllText($Path, $Utf8NoBom)
}

function Write-TextFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )
    [System.IO.File]::WriteAllText($Path, $Content, $Utf8NoBom)
}

function Get-Capture {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $matches = [regex]::Matches($Text, $Pattern)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one version match for $Label, found $($matches.Count)."
    }
    return $matches[0].Groups[2].Value
}

function Set-Capture {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$NewValue,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $matches = [regex]::Matches($Text, $Pattern)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one version match for $Label, found $($matches.Count)."
    }

    $evaluator = [System.Text.RegularExpressions.MatchEvaluator]{
        param($match)
        return $match.Groups[1].Value + $NewValue + $match.Groups[3].Value
    }
    return [regex]::Replace($Text, $Pattern, $evaluator)
}

function Get-CargoPackageVersion {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    $escapedName = [regex]::Escape($PackageName)
    $pattern = "(?s)(\[\[package\]\]\s*\r?\nname\s*=\s*`"$escapedName`"\s*\r?\nversion\s*=\s*`")([^`"]+)(`")"
    $matches = [regex]::Matches($Text, $pattern)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one Cargo.lock package block for $PackageName, found $($matches.Count)."
    }
    return $matches[0].Groups[2].Value
}

function Get-PackageLockValue {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $matches = [regex]::Matches($Text, $Pattern)
    if ($matches.Count -eq 0) {
        throw "Expected at least one package-lock match for $Label."
    }
    return ($matches | ForEach-Object { $_.Groups[2].Value }) -join "`n"
}

function Update-VersionFile {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][array]$Patterns
    )

    $path = Resolve-RepoFile $RelativePath
    $oldText = Read-TextFile $path
    $newText = $oldText
    $oldVersions = @()

    foreach ($item in $Patterns) {
        $oldVersions += Get-Capture -Text $newText -Pattern $item.Pattern -Label "$RelativePath :: $($item.Label)"
        $newText = Set-Capture -Text $newText -Pattern $item.Pattern -NewValue $Version -Label "$RelativePath :: $($item.Label)"
    }

    if ($newText -ne $oldText) {
        if ($WhatIf) {
            Write-Host "Would update $RelativePath"
        } else {
            Write-TextFile -Path $path -Content $newText
            Write-Host "Updated $RelativePath"
        }
    } else {
        Write-Host "No change $RelativePath"
    }

    return $oldVersions
}

function Get-VersionFileVersions {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][array]$Patterns
    )

    $path = Resolve-RepoFile $RelativePath
    $text = Read-TextFile $path
    $versions = @()
    foreach ($item in $Patterns) {
        $versions += Get-Capture -Text $text -Pattern $item.Pattern -Label "$RelativePath :: $($item.Label)"
    }
    return $versions
}

if (-not $AllowDirty) {
    $status = & git -C $RepoRoot status --porcelain
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to read git status.'
    }
    if ($status) {
        throw "Working tree is not clean. Commit/stash changes first, or pass -AllowDirty if this is intentional."
    }
}

$packageLockPath = Resolve-RepoFile 'cogniNote-agent-front/package-lock.json'
$cargoLockPath = Resolve-RepoFile 'cogniNote-agent-front/src-tauri/Cargo.lock'
$packageLockBefore = Read-TextFile $packageLockPath
$cargoLockBefore = Read-TextFile $cargoLockPath

# These guards catch the exact class of release-bump mistake that breaks CI:
# third-party lockfile entries must remain owned by npm/Cargo, not this script.
$guardBefore = @{
    PowershellUtilsRanges = Get-PackageLockValue -Text $packageLockBefore -Pattern '("powershell-utils"\s*:\s*")([^"]+)(")' -Label 'powershell-utils dependency ranges'
    PowershellUtilsNode = Get-PackageLockValue -Text $packageLockBefore -Pattern '("node_modules/powershell-utils"\s*:\s*\{\s*\r?\n\s*"version"\s*:\s*")([^"]+)(")' -Label 'node_modules/powershell-utils version'
    WindowsThreading = Get-CargoPackageVersion -Text $cargoLockBefore -PackageName 'windows-threading'
    Vswhom = Get-CargoPackageVersion -Text $cargoLockBefore -PackageName 'vswhom'
}

$targets = @(
    @{
        RelativePath = 'pom.xml'
        Patterns = @(
            @{ Label = 'Maven project version'; Pattern = '(?s)(<groupId>com\.itqianchen</groupId>\s*<artifactId>cogninote-agent-design</artifactId>\s*<version>)([^<]+)(</version>)' }
        )
    },
    @{
        RelativePath = 'cogniNote-agent-front/package.json'
        Patterns = @(
            @{ Label = 'frontend package version'; Pattern = '(?m)^(\s*"version"\s*:\s*")([^"]+)(",\s*)$' }
        )
    },
    @{
        RelativePath = 'cogniNote-agent-front/package-lock.json'
        Patterns = @(
            @{ Label = 'package-lock root version'; Pattern = '(?s)(\A\{\s*\r?\n\s*"name"\s*:\s*"cogninote-agent-front",\s*\r?\n\s*"version"\s*:\s*")([^"]+)(")' },
            @{ Label = 'package-lock package root version'; Pattern = '(?s)("packages"\s*:\s*\{\s*\r?\n\s*""\s*:\s*\{\s*\r?\n\s*"name"\s*:\s*"cogninote-agent-front",\s*\r?\n\s*"version"\s*:\s*")([^"]+)(")' }
        )
    },
    @{
        RelativePath = 'cogniNote-agent-front/src-tauri/Cargo.toml'
        Patterns = @(
            @{ Label = 'Cargo package version'; Pattern = '(?s)(\[package\]\s*\r?\nname\s*=\s*"cogninote-agent"\s*\r?\nversion\s*=\s*")([^"]+)(")' }
        )
    },
    @{
        RelativePath = 'cogniNote-agent-front/src-tauri/Cargo.lock'
        Patterns = @(
            @{ Label = 'Cargo.lock app package version'; Pattern = '(?s)(\[\[package\]\]\s*\r?\nname\s*=\s*"cogninote-agent"\s*\r?\nversion\s*=\s*")([^"]+)(")' }
        )
    },
    @{
        RelativePath = 'cogniNote-agent-front/src-tauri/tauri.conf.json'
        Patterns = @(
            @{ Label = 'Windows Tauri bundle version'; Pattern = '(?m)^(\s*"version"\s*:\s*")([^"]+)(",?\s*)$' }
        )
    },
    @{
        RelativePath = 'cogniNote-agent-front/src-tauri/tauri.macos.conf.json'
        Patterns = @(
            @{ Label = 'macOS Tauri bundle version'; Pattern = '(?m)^(\s*"version"\s*:\s*")([^"]+)(",?\s*)$' }
        )
    },
    @{
        RelativePath = '.github/workflows/desktop-windows.yml'
        Patterns = @(
            @{ Label = 'Windows workflow desktop version'; Pattern = '(?m)^(\s*COGNINOTE_DESKTOP_VERSION:\s*)([0-9A-Za-z.-]+)(\s*)$' }
        )
    },
    @{
        RelativePath = '.github/workflows/desktop-macos.yml'
        Patterns = @(
            @{ Label = 'macOS workflow desktop version'; Pattern = '(?m)^(\s*COGNINOTE_DESKTOP_VERSION:\s*)([0-9A-Za-z.-]+)(\s*)$' }
        )
    }
)

$oldVersions = @()
foreach ($target in $targets) {
    $oldVersions += Get-VersionFileVersions -RelativePath $target.RelativePath -Patterns $target.Patterns
}

$uniqueOldVersions = @($oldVersions | Sort-Object -Unique)
if ($uniqueOldVersions.Count -ne 1) {
    throw "Project version fields are inconsistent before update: $($uniqueOldVersions -join ', '). Fix them before bumping."
}
$oldVersion = $uniqueOldVersions[0]

foreach ($target in $targets) {
    [void](Update-VersionFile -RelativePath $target.RelativePath -Patterns $target.Patterns)
}

if (-not $SkipDocs) {
    $docFiles = @('README.md', 'docs/desktop-build-guide.md')
    foreach ($relativePath in $docFiles) {
        $path = Resolve-RepoFile $relativePath
        $oldText = Read-TextFile $path
        $newText = $oldText.Replace($oldVersion, $Version)
        if ($newText -ne $oldText) {
            if ($WhatIf) {
                Write-Host "Would update $relativePath"
            } else {
                Write-TextFile -Path $path -Content $newText
                Write-Host "Updated $relativePath"
            }
        } else {
            Write-Host "No doc change $relativePath"
        }
    }
}

$packageLockAfter = if ($WhatIf) { $packageLockBefore } else { Read-TextFile $packageLockPath }
$cargoLockAfter = if ($WhatIf) { $cargoLockBefore } else { Read-TextFile $cargoLockPath }
$guardAfter = @{
    PowershellUtilsRanges = Get-PackageLockValue -Text $packageLockAfter -Pattern '("powershell-utils"\s*:\s*")([^"]+)(")' -Label 'powershell-utils dependency ranges'
    PowershellUtilsNode = Get-PackageLockValue -Text $packageLockAfter -Pattern '("node_modules/powershell-utils"\s*:\s*\{\s*\r?\n\s*"version"\s*:\s*")([^"]+)(")' -Label 'node_modules/powershell-utils version'
    WindowsThreading = Get-CargoPackageVersion -Text $cargoLockAfter -PackageName 'windows-threading'
    Vswhom = Get-CargoPackageVersion -Text $cargoLockAfter -PackageName 'vswhom'
}

foreach ($key in $guardBefore.Keys) {
    if ($guardBefore[$key] -ne $guardAfter[$key]) {
        throw "Dependency guard failed: $key changed from '$($guardBefore[$key])' to '$($guardAfter[$key])'."
    }
}

Write-Host "Release version update complete: $oldVersion -> $Version"
Write-Host 'Suggested checks: git diff --check; mvn test; npm --prefix cogniNote-agent-front run build'
