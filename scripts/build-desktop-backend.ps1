param(
    [string]$JdkHome = 'D:\CodeApps\Java-JDK\jdk-25.0.2',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$resolvedRoot = [System.IO.Path]::GetFullPath($projectRoot)
$javaExe = Join-Path $JdkHome 'bin\java.exe'
$jpackageExe = Join-Path $JdkHome 'bin\jpackage.exe'
$jarName = 'cogninote-agent-design-0.0.1-SNAPSHOT.jar'
$jarPath = Join-Path $projectRoot "target\$jarName"
$desktopBackendDir = Join-Path $projectRoot 'target\desktop\backend'
$backendImageDir = Join-Path $desktopBackendDir 'CogniNoteBackend'
$jpackageInputDir = Join-Path $projectRoot 'target\desktop\jpackage-input'

function Assert-InProject {
    param([string]$Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (-not $fullPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to operate outside project directory: $fullPath"
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

    if ($SkipTests) {
        mvn -Pwith-frontend package -DskipTests
    } else {
        mvn -Pwith-frontend package
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
    & $jpackageExe @jpackageArgs

    $backendExe = Join-Path $backendImageDir 'CogniNoteBackend.exe'
    if (-not (Test-Path -LiteralPath $backendExe)) {
        throw "jpackage did not generate backend launcher: $backendExe"
    }

    Write-Host "Backend app-image generated: $backendImageDir"
} finally {
    Pop-Location
}
