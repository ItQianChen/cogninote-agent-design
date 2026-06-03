param(
    [string]$JdkHome = 'D:\CodeApps\Java-JDK\jdk-25.0.2',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir

Push-Location $projectRoot
try {
    # Dot-source the verifier so the refreshed Cargo/MSVC environment remains
    # available for the following Tauri build in this same PowerShell process.
    . .\scripts\verify-desktop-toolchain.ps1 -JdkHome $JdkHome

    $env:JAVA_HOME = $JdkHome
    $env:Path = "$JdkHome\bin;$env:Path"

    if (-not $SkipTests) {
        mvn test
    }

    # build-desktop-backend embeds Vue dist into the Spring Boot Jar and then
    # creates the full backend app-image directory consumed by Tauri resources.
    .\scripts\build-desktop-backend.ps1 -JdkHome $JdkHome -SkipTests

    npm --prefix cogniNote-agent-front run desktop:build

    Write-Host ''
    Write-Host 'Desktop build finished. Check cogniNote-agent-front/src-tauri/target/release/bundle/.'
} finally {
    Pop-Location
}
