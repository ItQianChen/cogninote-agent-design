[CmdletBinding()]
param(
    [ValidateRange(1, 100)]
    [int]$Iterations = 5
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$artifactRoot = Join-Path $repositoryRoot 'artifacts\test-repeatability'
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportRoot = Join-Path $artifactRoot "surefire-$runId"
$summaryPath = Join-Path $artifactRoot 'backend-repeatability.json'
$results = [System.Collections.Generic.List[object]]::new()
$overallStartedAt = [DateTimeOffset]::Now

New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null

function Get-SurefireSummary {
    param(
        [Parameter(Mandatory)]
        [string]$ReportDirectory
    )

    $totals = [ordered]@{
        reportCount = 0
        tests = 0
        executedTests = 0
        failures = 0
        errors = 0
        skipped = 0
    }

    Get-ChildItem -LiteralPath $ReportDirectory -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue |
        ForEach-Object {
            [xml]$report = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
            $suite = $report.testsuite
            $totals.reportCount += 1
            $totals.tests += [int]$suite.tests
            $totals.failures += [int]$suite.failures
            $totals.errors += [int]$suite.errors
            $totals.skipped += [int]$suite.skipped
        }

    $totals.executedTests = [Math]::Max(0, $totals.tests - $totals.skipped)
    return [pscustomobject]$totals
}

function Write-RepeatabilitySummary {
    $completedAt = [DateTimeOffset]::Now
    $passed = $results.Count -eq $Iterations -and -not ($results | Where-Object { -not $_.passed })
    $summary = [ordered]@{
        schemaVersion = 1
        runId = $runId
        requestedIterations = $Iterations
        completedIterations = $results.Count
        passed = $passed
        startedAt = $overallStartedAt.ToString('o')
        completedAt = $completedAt.ToString('o')
        durationSeconds = [Math]::Round(($completedAt - $overallStartedAt).TotalSeconds, 3)
        javaVersion = (& java -version 2>&1 | Select-Object -First 1) -join ''
        mavenVersion = (& mvn --version | Select-Object -First 1) -join ''
        reportDirectory = $reportRoot
        iterations = $results
    }
    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
}

Push-Location $repositoryRoot
try {
    for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
        $iterationStartedAt = [DateTimeOffset]::Now
        $surefireDirectory = Join-Path $repositoryRoot 'target\surefire-reports'
        if (Test-Path -LiteralPath $surefireDirectory) {
            Remove-Item -LiteralPath $surefireDirectory -Recurse -Force
        }

        Write-Host "[$iteration/$Iterations] Running backend test suite..."
        & mvn -B test
        $exitCode = $LASTEXITCODE
        $iterationCompletedAt = [DateTimeOffset]::Now
        $iterationReportDirectory = Join-Path $reportRoot ('iteration-{0:d3}' -f $iteration)
        New-Item -ItemType Directory -Force -Path $iterationReportDirectory | Out-Null

        if (Test-Path -LiteralPath $surefireDirectory) {
            Get-ChildItem -LiteralPath $surefireDirectory -Force |
                Copy-Item -Destination $iterationReportDirectory -Recurse -Force
        }
        $testSummary = Get-SurefireSummary -ReportDirectory $iterationReportDirectory
        $iterationPassed = $exitCode -eq 0 `
            -and $testSummary.reportCount -gt 0 `
            -and $testSummary.executedTests -gt 0 `
            -and $testSummary.failures -eq 0 `
            -and $testSummary.errors -eq 0
        $results.Add([pscustomobject][ordered]@{
                iteration = $iteration
                startedAt = $iterationStartedAt.ToString('o')
                completedAt = $iterationCompletedAt.ToString('o')
                durationSeconds = [Math]::Round(($iterationCompletedAt - $iterationStartedAt).TotalSeconds, 3)
                passed = $iterationPassed
                reportCount = $testSummary.reportCount
                tests = $testSummary.tests
                executedTests = $testSummary.executedTests
                failures = $testSummary.failures
                errors = $testSummary.errors
                skipped = $testSummary.skipped
                exitCode = $exitCode
                reportDirectory = $iterationReportDirectory
            })
        Write-RepeatabilitySummary

        if ($exitCode -ne 0) {
            throw "Backend tests failed on iteration $iteration. Reports: $iterationReportDirectory"
        }
        if ($testSummary.reportCount -le 0) {
            throw "Backend iteration $iteration produced no Surefire XML reports: $iterationReportDirectory"
        }
        if ($testSummary.executedTests -le 0) {
            throw "Backend iteration $iteration executed no tests: $iterationReportDirectory"
        }
        if ($testSummary.failures -ne 0 -or $testSummary.errors -ne 0) {
            throw "Backend iteration $iteration contains failing Surefire results: $iterationReportDirectory"
        }
    }
} finally {
    Write-RepeatabilitySummary
    Pop-Location
}

Write-Host "Backend tests passed $Iterations consecutive iteration(s). Summary: $summaryPath"
