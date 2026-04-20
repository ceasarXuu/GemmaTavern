param(
  [string]$DeviceSerial = "ONNZ95CAEMMZSKTS"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$artifactRoot = Join-Path $androidRoot "build\roleplay-memory-acceptance"
$reportRoot = Join-Path $repoRoot "docs\reports"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runRoot = Join-Path $artifactRoot $timestamp
$reportPath = Join-Path $reportRoot "roleplay-memory-acceptance-$timestamp.md"
$scorecardXmlPath = Join-Path $androidRoot "app\build\test-results\testDebugUnitTest\TEST-selfgemma.talk.domain.roleplay.usecase.RoleplayMemoryAcceptanceReportTest.xml"
$macrobenchmarkOutputRoot = Join-Path $androidRoot "macrobenchmark\build\outputs\connected_android_test_additional_output"

New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null

function Invoke-LoggedCommand {
  param(
    [string]$Executable,
    [string[]]$Arguments,
    [string]$WorkingDirectory,
    [string]$LogPath
  )

  Push-Location $WorkingDirectory
  $previousErrorActionPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $commandOutput = & $Executable @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $commandOutput | ForEach-Object { "$_" } | Tee-Object -FilePath $LogPath
    if ($exitCode -ne 0) {
      throw "Command failed: $Executable $($Arguments -join ' ')"
    }
  }
  finally {
    $ErrorActionPreference = $previousErrorActionPreference
    Pop-Location
  }
}

function Get-ScorecardsFromXml {
  param([string]$XmlPath)

  if (-not (Test-Path $XmlPath)) {
    throw "Scorecard XML not found: $XmlPath"
  }

  $acceptanceLine =
    Get-Content -Path $XmlPath | Where-Object { $_ -match "MEMORY_ACCEPTANCE_SCORECARD=" } | Select-Object -First 1
  $performanceLine =
    Get-Content -Path $XmlPath | Where-Object { $_ -match "MEMORY_PERFORMANCE_SCORECARD=" } | Select-Object -First 1

  if (-not $acceptanceLine -or -not $performanceLine) {
    throw "Failed to extract memory scorecards from $XmlPath"
  }

  $acceptanceStart = $acceptanceLine.IndexOf("MEMORY_ACCEPTANCE_SCORECARD=")
  $performanceStart = $performanceLine.IndexOf("MEMORY_PERFORMANCE_SCORECARD=")

  return @{
    Acceptance = ($acceptanceLine.Substring($acceptanceStart + "MEMORY_ACCEPTANCE_SCORECARD=".Length) | ConvertFrom-Json)
    Performance = ($performanceLine.Substring($performanceStart + "MEMORY_PERFORMANCE_SCORECARD=".Length) | ConvertFrom-Json)
  }
}

function Get-LatestFileSince {
  param(
    [string]$Root,
    [string]$Filter,
    [datetime]$After
  )

  if (-not (Test-Path $Root)) {
    return $null
  }

  return Get-ChildItem -Path $Root -Recurse -Filter $Filter -File |
    Where-Object { $_.LastWriteTime -ge $After } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
}

function Get-BenchmarkExcerpt {
  param([string]$InstrumentationLogPath)

  if (-not $InstrumentationLogPath -or -not (Test-Path $InstrumentationLogPath)) {
    return @("instrumentation log not found")
  }

  $patterns =
    @(
      "^INSTRUMENTATION_STATUS: android\\.studio(\\.v[23])?display\\.benchmark=",
      "^INSTRUMENTATION_STATUS: time_to_initial_display_millis_median=",
      "^INSTRUMENTATION_STATUS: gfx_frame_jank_percent_median=",
      "^INSTRUMENTATION_STATUS: gfx_frame_time_50th_percentile_millis_median=",
      "^INSTRUMENTATION_STATUS: gfx_frame_time_90th_percentile_millis_median=",
      "^INSTRUMENTATION_STATUS: gfx_frame_time_95th_percentile_millis_median=",
      "^INSTRUMENTATION_STATUS: gfx_frame_time_99th_percentile_millis_median=",
      "^INSTRUMENTATION_STATUS: gfx_frame_total_count_median="
    )

  $lines =
    Get-Content -Path $InstrumentationLogPath | Where-Object {
      $line = $_
      $patterns | Where-Object { $line -match $_ }
    }

  if (-not $lines) {
    return (Get-Content -Path $InstrumentationLogPath | Select-Object -Last 20)
  }

  return $lines | Select-Object -Last 20
}

function Get-StartupLaunchExcerpt {
  param([string]$StartupLogPath)

  if (-not (Test-Path $StartupLogPath)) {
    return @("startup launch log not found")
  }

  $patterns = @("Status:", "LaunchState:", "Activity:", "ThisTime:", "TotalTime:", "WaitTime:", "Complete")
  $lines =
    Get-Content -Path $StartupLogPath | Where-Object {
      $line = $_
      $patterns | Where-Object { $line -match $_ }
    }

  if (-not $lines) {
    return (Get-Content -Path $StartupLogPath | Select-Object -Last 20)
  }

  return $lines
}

function Get-MeminfoExcerpt {
  param([string]$MeminfoPath)

  if (-not (Test-Path $MeminfoPath)) {
    return @("meminfo log not found")
  }

  $patterns = @("TOTAL PSS", "TOTAL", "Native Heap", "Dalvik Heap", "App Summary")
  $lines =
    Get-Content -Path $MeminfoPath | Where-Object {
      $line = $_
      $patterns | Where-Object { $line -match $_ }
    }

  if (-not $lines) {
    return (Get-Content -Path $MeminfoPath | Select-Object -First 30)
  }

  return $lines
}

function Run-BenchmarkSuite {
  param(
    [string]$Suite,
    [string]$Serial,
    [string]$LogRoot,
    [string]$ClassFilter = ""
  )

  $suiteLog = Join-Path $LogRoot "$Suite-benchmark.log"
  $startedAt = Get-Date
  $arguments =
    @(
      "-ExecutionPolicy",
      "Bypass",
      "-File",
      (Join-Path $PSScriptRoot "run-frontend-perf.ps1"),
      "-Suite",
      $Suite,
      "-Runner",
      "adb",
      "-DeviceSerial",
      $Serial
    )

  if ($ClassFilter) {
    $arguments += @("-ClassFilter", $ClassFilter)
  }

  Invoke-LoggedCommand `
    -Executable "powershell" `
    -Arguments $arguments `
    -WorkingDirectory $androidRoot `
    -LogPath $suiteLog

  $instrumentationLog =
    Get-LatestFileSince `
      -Root $macrobenchmarkOutputRoot `
      -Filter "instrumentation-output.txt" `
      -After $startedAt

  return @{
    Suite = $Suite
    WrapperLog = $suiteLog
    InstrumentationLog = $instrumentationLog.FullName
    Excerpt = Get-BenchmarkExcerpt -InstrumentationLogPath $instrumentationLog.FullName
  }
}

function Format-CodeBlock {
  param([string[]]$Lines)

  $content = if ($Lines) { $Lines -join "`n" } else { "n/a" }
  return ('```text' + [Environment]::NewLine + $content + [Environment]::NewLine + '```')
}

$unitTestLog = Join-Path $runRoot "memory-unit-tests.log"
$debugBuildLog = Join-Path $runRoot "debug-build.log"
$debugInstallLog = Join-Path $runRoot "debug-install.log"
$startupLog = Join-Path $runRoot "startup-launch.txt"
$meminfoPath = Join-Path $runRoot "meminfo.txt"
$debugApkPath = Join-Path $androidRoot "app\build\outputs\apk\debug\app-debug.apk"

$unitTestArgs =
  @(
    ":app:testDebugUnitTest",
    "--tests", "selfgemma.talk.domain.roleplay.usecase.RoleplayMemoryAcceptanceReportTest",
    "--tests", "selfgemma.talk.domain.roleplay.usecase.CompileRoleplayMemoryContextUseCaseTest",
    "--tests", "selfgemma.talk.domain.roleplay.usecase.ExtractMemoriesUseCaseTest",
    "--tests", "selfgemma.talk.domain.roleplay.usecase.SummarizeSessionUseCaseTest",
    "--tests", "selfgemma.talk.domain.roleplay.usecase.RoleplayContinuityUseCaseTest",
    "--tests", "selfgemma.talk.domain.roleplay.usecase.SendRoleplayMessageUseCaseTest",
    "--tests", "selfgemma.talk.domain.roleplay.usecase.PromptAssemblerTest",
    "--tests", "selfgemma.talk.domain.roleplay.usecase.ContextBudgetPlannerTest",
    "--tests", "selfgemma.talk.domain.roleplay.usecase.ContextOverflowRecoveryTest",
    "--console=plain"
  )

Invoke-LoggedCommand `
  -Executable ".\gradlew.bat" `
  -Arguments $unitTestArgs `
  -WorkingDirectory $androidRoot `
  -LogPath $unitTestLog

$scorecards = Get-ScorecardsFromXml -XmlPath $scorecardXmlPath

Invoke-LoggedCommand `
  -Executable ".\gradlew.bat" `
  -Arguments @(":app:assembleDebug", "--console=plain") `
  -WorkingDirectory $androidRoot `
  -LogPath $debugBuildLog

& adb -s $DeviceSerial wait-for-device | Out-Null

Invoke-LoggedCommand `
  -Executable "adb" `
  -Arguments @("-s", $DeviceSerial, "install", "-r", $debugApkPath) `
  -WorkingDirectory $androidRoot `
  -LogPath $debugInstallLog

& adb -s $DeviceSerial shell am start -W -n selfgemma.talk/.MainActivity 2>&1 | Tee-Object -FilePath $startupLog
if ($LASTEXITCODE -ne 0) {
  throw "Failed to launch debug app on device $DeviceSerial"
}

& adb -s $DeviceSerial shell dumpsys meminfo selfgemma.talk 2>&1 | Tee-Object -FilePath $meminfoPath | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw "Failed to capture meminfo from device $DeviceSerial"
}

$startupSuite = Run-BenchmarkSuite -Suite "startup" -Serial $DeviceSerial -LogRoot $runRoot -ClassFilter "selfgemma.talk.macrobenchmark.StartupBenchmark#warmStartup"
$coreSuite = Run-BenchmarkSuite -Suite "core" -Serial $DeviceSerial -LogRoot $runRoot
$stressSuite = Run-BenchmarkSuite -Suite "stress" -Serial $DeviceSerial -LogRoot $runRoot

$acceptance = $scorecards.Acceptance
$performance = $scorecards.Performance
$startupLaunchExcerpt = Get-StartupLaunchExcerpt -StartupLogPath $startupLog
$meminfoExcerpt = Get-MeminfoExcerpt -MeminfoPath $meminfoPath
$tokenSampleBlock = Format-CodeBlock -Lines @([string]($acceptance.tokenSamples -join ", "))
$startupLaunchBlock = Format-CodeBlock -Lines $startupLaunchExcerpt
$meminfoBlock = Format-CodeBlock -Lines $meminfoExcerpt
$startupBlock = Format-CodeBlock -Lines $startupSuite.Excerpt
$coreBlock = Format-CodeBlock -Lines $coreSuite.Excerpt
$stressBlock = Format-CodeBlock -Lines $stressSuite.Excerpt

$report = @"
# Roleplay Memory Acceptance Report ($timestamp)

## Memory Scorecard

- semantic memory precision: $($acceptance.semanticMemoryPrecision)
- fallback recall precision: $($acceptance.fallbackRecallPrecision)
- episodic compaction recall rate: $($acceptance.episodicCompactionRecallRate)
- open-thread recall rate: $($acceptance.openThreadRecallRate)
- continuity pass rate: $($acceptance.continuityPassRate)
- branch pollution rate: $($acceptance.branchPollutionRate)
- drift repair activation rate: $($acceptance.driftRepairActivationRate)
- prompt budget compliance rate: $($acceptance.promptBudgetComplianceRate)
- injected memory token median: $($acceptance.injectedMemoryTokenMedian)
- injected memory token max: $($acceptance.injectedMemoryTokenMax)

Token samples:
$tokenSampleBlock

## Host Microbench

- compile memory context: median=$($performance.compileMemoryContext.medianMs) ms, p95=$($performance.compileMemoryContext.p95Ms) ms, max=$($performance.compileMemoryContext.maxMs) ms
- extract memories: median=$($performance.extractMemories.medianMs) ms, p95=$($performance.extractMemories.p95Ms) ms, max=$($performance.extractMemories.maxMs) ms
- summarize session: median=$($performance.summarizeSession.medianMs) ms, p95=$($performance.summarizeSession.p95Ms) ms, max=$($performance.summarizeSession.maxMs) ms
- continuity rebuild: median=$($performance.continuityRebuild.medianMs) ms, p95=$($performance.continuityRebuild.p95Ms) ms, max=$($performance.continuityRebuild.maxMs) ms

## Device Meminfo

$meminfoBlock

## Startup Verification

Cold launch via ``adb shell am start -W``:

 - launch log: $startupLog

$startupLaunchBlock

Warm startup macrobenchmark:

 - wrapper log: $($startupSuite.WrapperLog)
 - instrumentation log: $($startupSuite.InstrumentationLog)

$startupBlock

## Core Benchmark

 - wrapper log: $($coreSuite.WrapperLog)
 - instrumentation log: $($coreSuite.InstrumentationLog)

$coreBlock

## Stress Benchmark

 - wrapper log: $($stressSuite.WrapperLog)
 - instrumentation log: $($stressSuite.InstrumentationLog)

$stressBlock

## Artifacts

 - unit test log: $unitTestLog
 - debug build log: $debugBuildLog
 - debug install log: $debugInstallLog
 - app launch log: $startupLog
 - meminfo log: $meminfoPath
 - scorecard xml: $scorecardXmlPath
"@

Set-Content -Path $reportPath -Value $report -Encoding UTF8
Write-Host "Acceptance report written to $reportPath"
