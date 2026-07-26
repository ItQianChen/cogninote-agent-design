$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $repositoryRoot 'cogniNote-agent-front'
$artifactRoot = Join-Path $repositoryRoot 'artifacts\browser-smoke'
$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$storageRoot = Join-Path $temporaryRoot ("cogninote-e2e-" + [Guid]::NewGuid().ToString('N'))

function Get-AvailableTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

New-Item -ItemType Directory -Force -Path $artifactRoot, $storageRoot | Out-Null
$env:COGNINOTE_E2E_BACKEND_PORT = [string](Get-AvailableTcpPort)
$env:COGNINOTE_E2E_FRONTEND_PORT = [string](Get-AvailableTcpPort)
$env:COGNINOTE_E2E_CONTROL_PORT = [string](Get-AvailableTcpPort)
$env:COGNINOTE_E2E_CONTROL_TOKEN = [Guid]::NewGuid().ToString('N')
$env:COGNINOTE_E2E_STORAGE_ROOT = $storageRoot
$env:COGNINOTE_E2E_ARTIFACT_ROOT = $artifactRoot
$env:COGNINOTE_E2E_BACKEND_JAR = Join-Path $repositoryRoot 'target\cogninote-agent-design.jar'
if (-not $env:CI -and $IsWindows) {
    $chromePath = Join-Path $env:ProgramFiles 'Google\Chrome\Application\chrome.exe'
    if (Test-Path -LiteralPath $chromePath) {
        $env:COGNINOTE_E2E_BROWSER_CHANNEL = 'chrome'
    }
}

Push-Location $repositoryRoot
try {
    & mvn -B -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to build the backend jar for browser smoke tests.'
    }

    Push-Location $frontendRoot
    try {
        $playwrightCli = Join-Path $frontendRoot 'node_modules\@playwright\test\cli.js'
        & node $playwrightCli test
        if ($LASTEXITCODE -ne 0) {
            throw 'Playwright browser smoke tests failed.'
        }
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
    $resolvedStorageRoot = [System.IO.Path]::GetFullPath($storageRoot)
    if ($resolvedStorageRoot.StartsWith($temporaryRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedStorageRoot).StartsWith('cogninote-e2e-') -and
        (Test-Path -LiteralPath $resolvedStorageRoot)) {
        Remove-Item -LiteralPath $resolvedStorageRoot -Recurse -Force
    }
}
