param(
    [string]$JdkHome = 'D:\CodeApps\Java-JDK\jdk-25.0.2'
)

$ErrorActionPreference = 'Stop'

function Add-PathIfExists {
    param([string]$Path)

    if ((Test-Path -LiteralPath $Path) -and ($env:Path -notlike "*$Path*")) {
        $env:Path = "$Path;$env:Path"
    }
}

function Find-VsDevCmd {
    $vswhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path -LiteralPath $vswhere)) {
        return $null
    }

    $installPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $installPath) {
        return $null
    }

    $candidate = Join-Path $installPath 'Common7\Tools\VsDevCmd.bat'
    if (Test-Path -LiteralPath $candidate) {
        return $candidate
    }
    return $null
}

function Import-VsDevEnvironment {
    $vsDevCmd = Find-VsDevCmd
    if (-not $vsDevCmd) {
        return
    }

    # VsDevCmd is a batch file, so PowerShell cannot source it directly. Capture
    # the environment after cmd.exe loads it, then mirror those variables here.
    cmd /c "`"$vsDevCmd`" -arch=x64 -host_arch=x64 >nul && set" | ForEach-Object {
        $name, $value = $_ -split '=', 2
        if ($name -and $value) {
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

function Require-Command {
    param(
        [string]$Name,
        [string]$Hint
    )

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name not found. $Hint"
    }
}

function Require-File {
    param(
        [string]$Path,
        [string]$Hint
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Path does not exist. $Hint"
    }
}

$javaExe = Join-Path $JdkHome 'bin\java.exe'
$jpackageExe = Join-Path $JdkHome 'bin\jpackage.exe'
$cargoBin = Join-Path $env:USERPROFILE '.cargo\bin'

Add-PathIfExists $cargoBin
Import-VsDevEnvironment

# Desktop delivery depends on jpackage from JDK 25. Use the explicit JDK path
# instead of trusting a shell PATH that may still contain JDK 8/17.
Require-File $javaExe 'Check the JDK 25 install path or pass -JdkHome.'
Require-File $jpackageExe 'Check that the path points to a full JDK, not a JRE.'

Require-Command mvn 'Install Maven 3.9+ and add it to PATH.'
Require-Command node 'Install Node.js 20.19.6 or a compatible version.'
Require-Command npm 'Install npm 10.8.2 or a compatible version.'
Require-Command cargo 'Install Rust stable with rustup.'
Require-Command rustc 'Install Rust stable with rustup.'
Require-Command cl 'Install Visual Studio Build Tools and select Desktop development with C++.'
Require-Command link 'Install Visual Studio Build Tools and select Desktop development with C++.'

Write-Host 'Desktop toolchain check passed.'
Write-Host "JAVA_HOME = $JdkHome"
& $javaExe -version
Write-Host "jpackage = $(& $jpackageExe --version)"
Write-Host "node = $(node --version)"
Write-Host "npm = $(npm --version)"
Write-Host "rustc = $(rustc --version)"
Write-Host "cargo = $(cargo --version)"
Write-Host "cl = $((Get-Command cl).Source)"
Write-Host "link = $((Get-Command link).Source)"

Write-Host ''
Write-Host 'Note: Windows Tauri packaging also needs MSVC Build Tools. The installer is configured to download the WebView2 bootstrapper.'
