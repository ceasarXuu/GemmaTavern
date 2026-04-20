param(
  [string]$CatalogPath = ".\benchmarks\roleplay_eval\scenarios\public\catalog.json",
  [string[]]$SuiteIds,
  [string]$DeviceSerial,
  [string]$ModelName,
  [string]$AdbPath = "adb",
  [int]$TimeoutMinutes = 45,
  [string]$JudgeConfigPath = ".\benchmarks\roleplay_eval\config\judges.local.json",
  [switch]$SkipJudge,
  [switch]$ContinueOnFailure
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-CheckedCommand {
  param(
    [string]$Executable,
    [string[]]$Arguments,
    [string]$FailureMessage
  )

  & $Executable @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "$FailureMessage Exit code: $LASTEXITCODE"
  }
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$catalogAbsolutePath = Resolve-Path $CatalogPath
$catalog = Get-Content -LiteralPath $catalogAbsolutePath -Encoding UTF8 -Raw | ConvertFrom-Json
$selectedSuites =
  @($catalog.suites | Where-Object {
    if ($null -eq $SuiteIds -or $SuiteIds.Count -eq 0) {
      return $true
    }
    return $SuiteIds -contains [string]$_.suiteId
  })

if ($selectedSuites.Count -eq 0) {
  throw "No suites matched the requested selection."
}

$runScriptPath = Join-Path $PSScriptRoot "run-device-roleplay-eval.ps1"
$analysisScriptPath = Join-Path $PSScriptRoot "analyze-roleplay-eval-reports.py"
$judgeScriptPath = Join-Path $PSScriptRoot "judge-roleplay-eval.py"
$reportScriptPath = Join-Path $PSScriptRoot "render-roleplay-benchmark-report.py"
$reportsRoot = Join-Path $repoRoot "benchmarks\roleplay_eval\reports"
$htmlReportPath = Join-Path $reportsRoot "roleplay-benchmark-report.html"
$suiteResults = New-Object System.Collections.Generic.List[object]
$analysisError = $null
$judgeError = $null
$reportError = $null
$judgeConfigResolvedPath = $null

$shouldSkipBuild = $false

if (-not $SkipJudge -and -not [string]::IsNullOrWhiteSpace($JudgeConfigPath)) {
  $judgeConfigCandidate =
    if ([System.IO.Path]::IsPathRooted($JudgeConfigPath)) {
      $JudgeConfigPath
    } else {
      Join-Path $repoRoot ($JudgeConfigPath -replace "/", "\")
    }
  if (Test-Path -LiteralPath $judgeConfigCandidate) {
    $judgeConfigResolvedPath = [string](Resolve-Path $judgeConfigCandidate)
  } else {
    Write-Host "Judge config not found. Skipping LLM judge: $judgeConfigCandidate"
  }
}

foreach ($suite in $selectedSuites) {
  $manifestAbsolutePath = Join-Path $repoRoot ([string]$suite.manifestPath -replace "/", "\")
  $arguments = @(
    "-ExecutionPolicy", "Bypass",
    "-File", $runScriptPath,
    "-ManifestPath", $manifestAbsolutePath,
    "-AdbPath", $AdbPath,
    "-TimeoutMinutes", [string]$TimeoutMinutes
  )

  if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $arguments += @("-DeviceSerial", $DeviceSerial)
  }
  if (-not [string]::IsNullOrWhiteSpace($ModelName)) {
    $arguments += @("-ModelName", $ModelName)
  }
  if ($shouldSkipBuild) {
    $arguments += "-SkipBuild"
  }

  try {
    Write-Host "Running suite $($suite.suiteId) using $manifestAbsolutePath"
    Invoke-CheckedCommand -Executable "powershell" -Arguments $arguments -FailureMessage "Suite $($suite.suiteId) failed."
    $suiteResults.Add(
      [pscustomobject]@{
        suiteId = [string]$suite.suiteId
        manifestPath = [string]$suite.manifestPath
        status = "SUCCEEDED"
      }
    ) | Out-Null
    $shouldSkipBuild = $true
  }
  catch {
    Write-Warning $_.Exception.Message
    $suiteResults.Add(
      [pscustomobject]@{
        suiteId = [string]$suite.suiteId
        manifestPath = [string]$suite.manifestPath
        status = "FAILED"
        error = $_.Exception.Message
      }
    ) | Out-Null
    if (-not $ContinueOnFailure) {
      break
    }
    $shouldSkipBuild = $true
  }
}

try {
  Invoke-CheckedCommand -Executable "python" -Arguments @($analysisScriptPath, "--reports-root", $reportsRoot) -FailureMessage "Failed to analyze pulled reports."
}
catch {
  $analysisError = $_.Exception.Message
  Write-Warning $analysisError
}

if ([string]::IsNullOrWhiteSpace($analysisError) -and -not [string]::IsNullOrWhiteSpace($judgeConfigResolvedPath)) {
  try {
    Invoke-CheckedCommand -Executable "python" -Arguments @(
      $judgeScriptPath,
      "--reports-root", $reportsRoot,
      "--config", $judgeConfigResolvedPath
    ) -FailureMessage "Failed to run LLM judge over pulled reports."
  }
  catch {
    $judgeError = $_.Exception.Message
    Write-Warning $judgeError
  }
}

$batchSummaryPath = Join-Path $reportsRoot "batch-summary.json"
$batchSummary = [pscustomobject]@{
  generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  catalogPath = [string]$catalogAbsolutePath
  suiteResults = $suiteResults
  analysisError = $analysisError
  judgeConfigPath = $judgeConfigResolvedPath
  judgeError = $judgeError
  htmlReportPath = $htmlReportPath
  reportError = $reportError
}
$batchSummary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $batchSummaryPath -Encoding UTF8

if ([string]::IsNullOrWhiteSpace($analysisError)) {
  try {
    Invoke-CheckedCommand -Executable "python" -Arguments @(
      $reportScriptPath,
      "--reports-root", $reportsRoot,
      "--catalog", $catalogAbsolutePath,
      "--batch-summary", $batchSummaryPath,
      "--output", $htmlReportPath
    ) -FailureMessage "Failed to render static roleplay benchmark report."
  }
  catch {
    $reportError = $_.Exception.Message
    Write-Warning $reportError
    $batchSummary = [pscustomobject]@{
      generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
      catalogPath = [string]$catalogAbsolutePath
      suiteResults = $suiteResults
      analysisError = $analysisError
      judgeConfigPath = $judgeConfigResolvedPath
      judgeError = $judgeError
      htmlReportPath = $htmlReportPath
      reportError = $reportError
    }
    $batchSummary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $batchSummaryPath -Encoding UTF8
  }
}

$failedSuites = @($suiteResults | Where-Object { $_.status -eq "FAILED" })
$batchErrors = @()
if (-not [string]::IsNullOrWhiteSpace($analysisError)) {
  $batchErrors += "Report analysis failed."
}
if (-not [string]::IsNullOrWhiteSpace($judgeError)) {
  $batchErrors += "LLM judge failed."
}
if (-not [string]::IsNullOrWhiteSpace($reportError)) {
  $batchErrors += "Static report generation failed."
}
if ($failedSuites.Count -gt 0) {
  $batchErrors += "Batch completed with $($failedSuites.Count) failed suite(s)."
}

if ($batchErrors.Count -gt 0) {
  throw ("{0} See {1}" -f ($batchErrors -join " "), $batchSummaryPath)
}

Write-Host "Batch completed successfully. Summary: $batchSummaryPath"
