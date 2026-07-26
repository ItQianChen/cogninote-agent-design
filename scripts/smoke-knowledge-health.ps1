param(
    [string]$JavaHome = '',
    [string]$ReportPath = ''
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$startedAt = [DateTimeOffset]::Now

if (-not [string]::IsNullOrWhiteSpace($JavaHome) -and (Test-Path -LiteralPath $JavaHome)) {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $repositoryRoot 'artifacts\knowledge-smoke\result.json'
}

Push-Location $repositoryRoot
try {
    & mvn -B '-Dtest=com.itqianchen.agentdesign.knowledge.KnowledgeFolderControllerTests#smokeImportSearchHealthDeleteAndRunCleanup' test
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

$completedAt = [DateTimeOffset]::Now
$result = [ordered]@{
    schemaVersion = 1
    passed = $exitCode -eq 0
    exitCode = $exitCode
    startedAt = $startedAt.ToString('o')
    completedAt = $completedAt.ToString('o')
    durationSeconds = [Math]::Round(($completedAt - $startedAt).TotalSeconds, 3)
    test = 'KnowledgeFolderControllerTests#smokeImportSearchHealthDeleteAndRunCleanup'
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$result | ConvertTo-Json | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($exitCode -ne 0) {
    throw "Knowledge health smoke failed with exit code $exitCode."
}
