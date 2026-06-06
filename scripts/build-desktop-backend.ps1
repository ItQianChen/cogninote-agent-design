param(
    [string]$JdkHome = '',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$DefaultWindowsJdkHome = 'D:\CodeApps\Java-JDK\jdk-25.0.2'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$resolvedRoot = [System.IO.Path]::GetFullPath($projectRoot)

function Test-Jdk25Home {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $false
    }

    $candidateJava = Join-Path $Path 'bin\java.exe'
    $candidateJpackage = Join-Path $Path 'bin\jpackage.exe'
    if (-not (Test-Path -LiteralPath $candidateJava) -or -not (Test-Path -LiteralPath $candidateJpackage)) {
        return $false
    }

    $versionLine = cmd /c "`"$candidateJava`" -version 2>&1" | Select-Object -First 1
    return $versionLine -match 'version "25(\.|")'
}

function Resolve-JdkHome {
    param([string]$RequestedJdkHome)

    if (-not [string]::IsNullOrWhiteSpace($RequestedJdkHome)) {
        return $RequestedJdkHome
    }

    # GitHub Actions exposes setup-java through JAVA_HOME. On local machines,
    # JAVA_HOME may still point to an old JDK, so only trust it when it is JDK 25.
    if (Test-Jdk25Home $env:JAVA_HOME) {
        return $env:JAVA_HOME
    }

    if (Test-Jdk25Home $DefaultWindowsJdkHome) {
        return $DefaultWindowsJdkHome
    }

    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        return $env:JAVA_HOME
    }

    return $DefaultWindowsJdkHome
}

$JdkHome = Resolve-JdkHome $JdkHome
$javaExe = Join-Path $JdkHome 'bin\java.exe'
$jpackageExe = Join-Path $JdkHome 'bin\jpackage.exe'
$jarName = 'cogninote-agent-design.jar'
$jarPath = Join-Path $projectRoot "target\$jarName"
$desktopBackendDir = Join-Path $projectRoot 'target\desktop\backend'
$backendImageDir = Join-Path $desktopBackendDir 'CogniNoteBackend'
$jpackageInputDir = Join-Path $projectRoot 'target\desktop\jpackage-input'
$frontendDir = Join-Path $projectRoot 'cogniNote-agent-front'

function Assert-InProject {
    param([string]$Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (-not $fullPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to operate outside project directory: $fullPath"
    }
}

function Invoke-Native {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList = @()
    )

    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $FilePath $($ArgumentList -join ' ')"
    }
}

function Stop-ProjectFrontendDevServer {
    $frontendFullPath = [System.IO.Path]::GetFullPath($frontendDir).ToLowerInvariant()
    $projectNpmMarker = 'cogninote-agent-front'

    Get-CimInstance Win32_Process -Filter "name = 'node.exe'" |
        Where-Object {
            $commandLine = $_.CommandLine
            if (-not $commandLine) {
                return $false
            }

            $normalized = $commandLine.ToLowerInvariant()
            $isProjectVite = $normalized.Contains($frontendFullPath) -and $normalized.Contains('vite')
            $isProjectNpmDev = $normalized.Contains($projectNpmMarker) -and $normalized.Contains('run dev')
            return $isProjectVite -or $isProjectNpmDev
        } |
        ForEach-Object {
            # npm ci removes and recreates node_modules. A running Vite dev server
            # keeps Rolldown's native .node binding loaded on Windows, which turns
            # dependency refresh into EPERM unlink failures.
            Write-Host "Stopping frontend dev server process PID $($_.ProcessId) before npm ci."
            Stop-Process -Id $_.ProcessId -Force
        }
}

if (-not (Test-Path -LiteralPath $javaExe)) {
    throw "JDK 25 java.exe not found: $javaExe"
}

if (-not (Test-Path -LiteralPath $jpackageExe)) {
    throw "JDK 25 jpackage.exe not found: $jpackageExe"
}

Push-Location $projectRoot
try {
    $env:JAVA_HOME = $JdkHome
    $env:Path = "$JdkHome\bin;$env:Path"

    Stop-ProjectFrontendDevServer

    if ($SkipTests) {
        Invoke-Native -FilePath 'mvn' -ArgumentList @('-Pwith-frontend', 'package', '-DskipTests')
    } else {
        Invoke-Native -FilePath 'mvn' -ArgumentList @('-Pwith-frontend', 'package')
    }

    if (-not (Test-Path -LiteralPath $jarPath)) {
        throw "Backend Jar was not generated: $jarPath"
    }

    if (Test-Path -LiteralPath $backendImageDir) {
        Assert-InProject $backendImageDir
        Remove-Item -LiteralPath $backendImageDir -Recurse -Force
    }

    if (Test-Path -LiteralPath $jpackageInputDir) {
        Assert-InProject $jpackageInputDir
        Remove-Item -LiteralPath $jpackageInputDir -Recurse -Force
    }

    New-Item -ItemType Directory -Force -Path $desktopBackendDir | Out-Null
    New-Item -ItemType Directory -Force -Path $jpackageInputDir | Out-Null
    Copy-Item -LiteralPath $jarPath -Destination (Join-Path $jpackageInputDir $jarName) -Force

    # SQLite JDBC triggers a native-access warning on JDK 25. The desktop backend
    # uses a bundled runtime, so pass the flag once at package time instead of
    # asking users to understand a low-level JVM warning.
    $jpackageArgs = @(
        '--type', 'app-image',
        '--name', 'CogniNoteBackend',
        '--input', $jpackageInputDir,
        '--main-jar', $jarName,
        '--dest', $desktopBackendDir,
        '--java-options', '--enable-native-access=ALL-UNNAMED'
    )
    Invoke-Native -FilePath $jpackageExe -ArgumentList $jpackageArgs

    $backendExe = Join-Path $backendImageDir 'CogniNoteBackend.exe'
    if (-not (Test-Path -LiteralPath $backendExe)) {
        throw "jpackage did not generate backend launcher: $backendExe"
    }

    Get-ChildItem -LiteralPath $backendImageDir -Recurse -Force | ForEach-Object {
        if ($_.Attributes -band [System.IO.FileAttributes]::ReadOnly) {
            $_.Attributes = $_.Attributes -band (-bnot [System.IO.FileAttributes]::ReadOnly)
        }
    }

    Write-Host "Backend app-image generated: $backendImageDir"
} finally {
    Pop-Location
}
