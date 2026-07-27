param(
    [string]$JarPath = "target/cogninote-agent-design.jar",
    [string]$ReportPath = "target/durable-maintenance-smoke.json"
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$resolvedJar = (Resolve-Path (Join-Path $repositoryRoot $JarPath)).Path
$runId = [guid]::NewGuid().ToString()
$storageRoot = Join-Path $repositoryRoot "target/durable-maintenance-smoke/$runId"
$sentinelSource = Join-Path $storageRoot 'sentinel-source'
$sentinelToken = "durablemaintenance$($runId.Replace('-', ''))"
$backendProcess = $null
New-Item -ItemType Directory -Force -Path $sentinelSource | Out-Null
Set-Content -LiteralPath (Join-Path $sentinelSource 'sentinel.txt') -Value $sentinelToken -Encoding UTF8

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()
$baseUrl = "http://127.0.0.1:$port"

function Start-SmokeBackend {
    param(
        [Parameter(Mandatory)]
        [bool]$DispatchEnabled,
        [Parameter(Mandatory)]
        [string]$Phase
    )

    $env:COGNINOTE_DATA_DIR = $storageRoot
    $env:COGNINOTE_PORT = $port.ToString()
    $env:COGNINOTE_DURABLE_TASK_DISPATCH_ENABLED = $DispatchEnabled.ToString().ToLowerInvariant()
    $process = Start-Process `
        -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') `
        -ArgumentList '-jar', $resolvedJar `
        -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $storageRoot "$Phase.stdout.log") `
        -RedirectStandardError (Join-Path $storageRoot "$Phase.stderr.log") `
        -PassThru

    $deadline = (Get-Date).AddSeconds(45)
    do {
        Start-Sleep -Milliseconds 500
        try {
            $ready = (Invoke-RestMethod -Uri "$baseUrl/api/system/status" -TimeoutSec 2).success
        } catch {
            $ready = $false
        }
    } until ($ready -or (Get-Date) -gt $deadline)
    if (-not $ready) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Durable maintenance backend failed to start during $Phase."
    }
    return $process
}

function Stop-SmokeBackend {
    param([System.Diagnostics.Process]$Process)
    if ($null -ne $Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $Process.Id -ErrorAction SilentlyContinue
    }
}

try {
    $backendProcess = Start-SmokeBackend -DispatchEnabled $false -Phase 'enqueue'
    $queuedRun = (Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUrl/api/knowledge-maintenance/runs/import-folder" `
        -ContentType 'application/json' `
        -Body (@{ folderPath = $sentinelSource; recursive = $false } | ConvertTo-Json -Compress)).data
    if ($queuedRun.status -ne 'QUEUED' -or $queuedRun.attempt -ne 0) {
        throw "Dispatcher-disabled task was not durably queued: $($queuedRun.status)"
    }

    Stop-SmokeBackend -Process $backendProcess
    $backendProcess = Start-SmokeBackend -DispatchEnabled $true -Phase 'resume'

    $deadline = (Get-Date).AddSeconds(90)
    do {
        Start-Sleep -Milliseconds 500
        $resumedRun = (Invoke-RestMethod -Uri "$baseUrl/api/knowledge-maintenance/runs/$($queuedRun.id)").data
    } until ($resumedRun.status -in @('COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED', 'INTERRUPTED') `
        -or (Get-Date) -gt $deadline)
    if ($resumedRun.status -ne 'COMPLETED') {
        throw "Durable maintenance task did not complete after restart: $($resumedRun.status)"
    }
    if ($resumedRun.attempt -ne 1 -or -not $resumedRun.resumable) {
        throw 'Durable maintenance attempt metadata is invalid.'
    }
    if ($resumedRun.progressTotal -lt 1 -or $resumedRun.progressCurrent -ne $resumedRun.progressTotal) {
        throw "Durable maintenance final progress is invalid: $($resumedRun.progressCurrent)/$($resumedRun.progressTotal)"
    }

    $search = (Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUrl/api/search" `
        -ContentType 'application/json' `
        -Body (@{ query = $sentinelToken; mode = 'KEYWORD'; topK = 5 } | ConvertTo-Json -Compress)).data
    if (@($search.hits).Count -lt 1) {
        throw 'Restarted maintenance task completed without a searchable sentinel document.'
    }

    $report = [ordered]@{
        schemaVersion = 1
        success = $true
        runId = $runId
        taskRunId = $queuedRun.id
        finalStatus = $resumedRun.status
        attempt = $resumedRun.attempt
        maxAttempts = $resumedRun.maxAttempts
        progressCurrent = $resumedRun.progressCurrent
        progressTotal = $resumedRun.progressTotal
        searchableSentinel = $true
        storageRoot = $storageRoot
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    $resolvedReport = Join-Path $repositoryRoot $ReportPath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedReport) | Out-Null
    $report | ConvertTo-Json | Set-Content -LiteralPath $resolvedReport -Encoding UTF8
    $report | ConvertTo-Json -Compress
} finally {
    Stop-SmokeBackend -Process $backendProcess
    Remove-Item Env:COGNINOTE_DURABLE_TASK_DISPATCH_ENABLED -ErrorAction SilentlyContinue
}
