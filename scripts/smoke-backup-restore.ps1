param(
    [string]$JarPath = "target/cogninote-agent-design.jar",
    [string]$ReportPath = "target/backup-restore-smoke.json"
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$resolvedJar = (Resolve-Path (Join-Path $repositoryRoot $JarPath)).Path
$runId = [guid]::NewGuid().ToString()
$storageRoot = Join-Path $repositoryRoot "target/backup-restore-smoke/$runId"
New-Item -ItemType Directory -Force -Path $storageRoot | Out-Null

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()
$baseUrl = "http://127.0.0.1:$port"
$backendProcess = $null
$sentinelToken = "backuprestore$($runId.Replace('-', ''))"
$sentinelTitle = "Backup restore sentinel $sentinelToken"
$sentinelSource = Join-Path $storageRoot 'sentinel-source'
New-Item -ItemType Directory -Force -Path $sentinelSource | Out-Null
Set-Content -LiteralPath (Join-Path $sentinelSource 'sentinel.txt') -Value $sentinelToken -Encoding UTF8

function Start-SmokeBackend {
    $stdout = Join-Path $storageRoot 'backend.stdout.log'
    $stderr = Join-Path $storageRoot 'backend.stderr.log'
    $env:COGNINOTE_DATA_DIR = $storageRoot
    $env:COGNINOTE_PORT = $port.ToString()
    $process = Start-Process `
        -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') `
        -ArgumentList '-jar', $resolvedJar `
        -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
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
        throw "Smoke backend failed to start. See $stderr"
    }
    return $process
}

function Invoke-SentinelSearch {
    return (Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUrl/api/search" `
        -ContentType 'application/json' `
        -Body (@{ query = $sentinelToken; mode = 'KEYWORD'; topK = 5 } | ConvertTo-Json -Compress)).data
}

try {
    $backendProcess = Start-SmokeBackend
    $ingest = (Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUrl/api/knowledge-folders/import" `
        -ContentType 'application/json' `
        -Body (@{ folderPath = $sentinelSource; recursive = $false } | ConvertTo-Json -Compress)).data
    if ($ingest.failedCount -ne 0 -or $ingest.parsedCount -lt 1) {
        throw 'Sentinel document was not imported before backup.'
    }
    $folders = (Invoke-RestMethod -Uri "$baseUrl/api/knowledge-folders").data.folders
    $sentinelFolder = @($folders | Where-Object { $_.folderPath -eq $sentinelSource }) | Select-Object -First 1
    if ($null -eq $sentinelFolder) {
        throw 'Sentinel knowledge folder was not persisted before backup.'
    }
    if (@((Invoke-SentinelSearch).hits).Count -lt 1) {
        throw 'Sentinel document was not searchable before backup.'
    }
    $sentinelSession = (Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUrl/api/chat/sessions" `
        -ContentType 'application/json' `
        -Body (@{ title = $sentinelTitle; useKnowledgeBase = $true; mode = 'KEYWORD'; topK = 5 } |
            ConvertTo-Json -Compress)).data

    $backupEnvelope = Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUrl/api/system/backups" `
        -ContentType 'application/json' `
        -Body '{}'
    $backup = $backupEnvelope.data
    if (-not $backup.containsSecrets -or $backup.schemaVersion -lt 2) {
        throw 'Backup response did not contain the expected schema and secret policy.'
    }

    Invoke-RestMethod `
        -Method Delete `
        -Uri "$baseUrl/api/knowledge-folders/$($sentinelFolder.id)" | Out-Null
    Invoke-RestMethod `
        -Method Delete `
        -Uri "$baseUrl/api/chat/sessions/$($sentinelSession.id)" | Out-Null
    if (@((Invoke-SentinelSearch).hits).Count -ne 0) {
        throw 'Sentinel search result still exists after deleting the backed-up folder state.'
    }
    $sessionsAfterDelete = (Invoke-RestMethod -Uri "$baseUrl/api/chat/sessions").data
    if (@($sessionsAfterDelete | Where-Object { $_.id -eq $sentinelSession.id }).Count -ne 0) {
        throw 'Sentinel session still exists after deletion.'
    }

    $exportPath = Join-Path $storageRoot "data-protection/exports/$($backup.backupId).cogninote-backup"
    $importId = [guid]::NewGuid().ToString()
    $inboxPath = Join-Path $storageRoot "data-protection/restore-inbox/$importId.cogninote-backup"
    Copy-Item -LiteralPath $exportPath -Destination $inboxPath

    $preflightEnvelope = Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUrl/api/system/restores/preflight" `
        -ContentType 'application/json' `
        -Body (@{ importId = $importId } | ConvertTo-Json -Compress)
    $restore = $preflightEnvelope.data
    if ($restore.phase -ne 'PREFLIGHTED') {
        throw "Unexpected preflight phase: $($restore.phase)"
    }

    Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUrl/api/system/restores/$($restore.restoreId)/schedule" `
        -ContentType 'application/json' `
        -Body '{}' | Out-Null

    Stop-Process -Id $backendProcess.Id -Force
    Wait-Process -Id $backendProcess.Id -ErrorAction SilentlyContinue
    $backendProcess = Start-SmokeBackend

    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Milliseconds 500
        $status = (Invoke-RestMethod -Uri "$baseUrl/api/system/data-protection/status" -TimeoutSec 2).data
    } until ($status.lastStatus -in @('COMPLETED', 'ROLLED_BACK', 'REINDEX_FAILED') -or (Get-Date) -gt $deadline)
    if ($status.lastStatus -ne 'COMPLETED') {
        throw "Restore did not complete successfully: $($status.lastStatus)"
    }

    $restoredSession = (Invoke-RestMethod -Uri "$baseUrl/api/chat/sessions/$($sentinelSession.id)").data
    if ($restoredSession.title -ne $sentinelTitle) {
        throw 'SQLite sentinel session was not restored to its backed-up value.'
    }
    $restoredSearch = Invoke-SentinelSearch
    if (@($restoredSearch.hits).Count -lt 1) {
        throw 'Lucene sentinel was not rebuilt from the restored SQLite chunks.'
    }

    $report = [ordered]@{
        success = $true
        runId = $runId
        schemaVersion = $status.schemaVersion
        backupId = $backup.backupId
        restoreId = $restore.restoreId
        restoreStatus = $status.lastStatus
        containsSecrets = $backup.containsSecrets
        sqliteSentinelRestored = $true
        luceneSentinelRestored = $true
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    $resolvedReport = Join-Path $repositoryRoot $ReportPath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedReport) | Out-Null
    $report | ConvertTo-Json | Set-Content -LiteralPath $resolvedReport -Encoding UTF8
    $report | ConvertTo-Json -Compress
} finally {
    if ($null -ne $backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
    }
}
