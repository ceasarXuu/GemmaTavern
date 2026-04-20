param(
  [ValidateSet("full", "startup", "core", "stress")]
  [string]$Suite = "full",
  [string]$ClassFilter = "",
  [ValidateSet("auto", "gradle", "adb")]
  [string]$Runner = "auto",
  [string]$DeviceSerial = ""
)

$ErrorActionPreference = "Stop"
$PinnedBenchmarkDeviceSerial = "ONNZ95CAEMMZSKTS"
$TargetAppPackage = "selfgemma.talk"
$TargetMacrobenchmarkPackage = "selfgemma.talk.macrobenchmark"

function Get-BenchmarkClassFilter {
  param(
    [string]$SelectedSuite,
    [string]$ExplicitClassFilter
  )

  if ($ExplicitClassFilter) {
    return $ExplicitClassFilter
  }

  switch ($SelectedSuite) {
    "startup" {
      return "selfgemma.talk.macrobenchmark.StartupBenchmark"
    }
    "core" {
      return "selfgemma.talk.macrobenchmark.RoleplayFlowBenchmark"
    }
    "stress" {
      return "selfgemma.talk.macrobenchmark.RoleplayStressBenchmark"
    }
    default {
      return ""
    }
  }
}

function Get-AdbArgs {
  param([string]$Serial)

  if ($Serial) {
    return @("-s", $Serial)
  }

  return @()
}

function Resolve-BenchmarkDeviceSerial {
  param([string]$RequestedSerial)

  $expectedSerial = if ($RequestedSerial) {
    if ($RequestedSerial -ne $PinnedBenchmarkDeviceSerial) {
      throw "Benchmark runs are pinned to device serial '$PinnedBenchmarkDeviceSerial'. Refusing to target '$RequestedSerial'."
    }

    $RequestedSerial
  }
  else {
    $PinnedBenchmarkDeviceSerial
  }

  $deviceOutput = & adb devices
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to list adb devices. Exit code: $LASTEXITCODE"
  }

  $connectedDevices = @()
  foreach ($line in $deviceOutput) {
    if ($line -match '^(\S+)\s+device$') {
      $connectedDevices += $Matches[1]
    }
  }

  if ($connectedDevices -notcontains $expectedSerial) {
    $availableDevices = if ($connectedDevices.Count -gt 0) { $connectedDevices -join ", " } else { "none" }
    throw "Pinned benchmark device serial '$expectedSerial' is not connected. Available device serials: $availableDevices"
  }

  return $expectedSerial
}

function Invoke-GradleBenchmark {
  param(
    [string[]]$GradleArguments,
    [string]$Serial
  )

  if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb was not found in PATH."
  }

  $resolvedSerial = Resolve-BenchmarkDeviceSerial -RequestedSerial $Serial
  $previousAndroidSerial = $env:ANDROID_SERIAL

  try {
    $env:ANDROID_SERIAL = $resolvedSerial
    Write-Host "Running Gradle benchmark on pinned device $resolvedSerial"
    & .\gradlew.bat @GradleArguments
    return $LASTEXITCODE
  }
  finally {
    if ($null -eq $previousAndroidSerial) {
      Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    }
    else {
      $env:ANDROID_SERIAL = $previousAndroidSerial
    }
  }
}

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

function Get-ArtifactOutputRoot {
  $outputRoot = Join-Path (Resolve-Path ".\macrobenchmark\build\outputs").Path "connected_android_test_additional_output"
  New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
  return $outputRoot
}

function Get-AdbInstallStatePath {
  return Join-Path (Get-ArtifactOutputRoot) "adb-install-state.csv"
}

function Get-AdbInstallState {
  $state = @{}
  $statePath = Get-AdbInstallStatePath

  if (-not (Test-Path $statePath)) {
    return $state
  }

  foreach ($row in (Import-Csv -Path $statePath)) {
    $state[$row.Serial] = @{
      AppHash = $row.AppHash
      MacroHash = $row.MacroHash
    }
  }

  return $state
}

function Save-AdbInstallState {
  param([hashtable]$State)

  $rows = foreach ($serial in ($State.Keys | Sort-Object)) {
    [pscustomobject]@{
      Serial = $serial
      AppHash = $State[$serial].AppHash
      MacroHash = $State[$serial].MacroHash
    }
  }

  $rows | Export-Csv -Path (Get-AdbInstallStatePath) -NoTypeInformation
}

function Test-DevicePackageInstalled {
  param(
    [string[]]$AdbArgs,
    [string]$PackageName
  )

  $packagePathOutput = & adb @AdbArgs shell pm path $PackageName 2>$null
  return ($LASTEXITCODE -eq 0) -and ($packagePathOutput | Select-String '^package:' -Quiet)
}

function Test-DevicePackageDebuggable {
  param(
    [string[]]$AdbArgs,
    [string]$PackageName
  )

  $packageDump = & adb @AdbArgs shell dumpsys package $PackageName 2>$null
  if ($LASTEXITCODE -ne 0) {
    return $false
  }

  return $packageDump | Select-String 'pkgFlags=\[.*DEBUGGABLE|flags=\[.*DEBUGGABLE' -Quiet
}

function Install-BenchmarkApksIfNeeded {
  param(
    [string]$ResolvedSerial,
    [string[]]$AdbArgs,
    [string]$AppApk,
    [string]$MacrobenchmarkApk
  )

  $state = Get-AdbInstallState
  $appHash = (Get-FileHash -Path $AppApk -Algorithm SHA256).Hash
  $macroHash = (Get-FileHash -Path $MacrobenchmarkApk -Algorithm SHA256).Hash
  $deviceState = $state[$ResolvedSerial]
  $appInstalledOnDevice = Test-DevicePackageInstalled -AdbArgs $AdbArgs -PackageName $TargetAppPackage
  $macroInstalledOnDevice = Test-DevicePackageInstalled -AdbArgs $AdbArgs -PackageName $TargetMacrobenchmarkPackage
  $appDebuggableOnDevice =
    if ($appInstalledOnDevice) {
      Test-DevicePackageDebuggable -AdbArgs $AdbArgs -PackageName $TargetAppPackage
    }
    else {
      $false
    }
  $appNeedsInstall =
    (-not $deviceState) -or
    ($deviceState.AppHash -ne $appHash) -or
    (-not $appInstalledOnDevice) -or
    $appDebuggableOnDevice
  $macroNeedsInstall = (-not $deviceState) -or ($deviceState.MacroHash -ne $macroHash) -or (-not $macroInstalledOnDevice)
  $installsPerformed = $false

  if ($appNeedsInstall) {
    if (-not $appInstalledOnDevice) {
      Write-Host "App package is missing on device $ResolvedSerial. Reinstalling benchmark app APK."
    } elseif ($appDebuggableOnDevice) {
      Write-Host "Installed target app on device $ResolvedSerial is debuggable. Reinstalling non-debuggable benchmark app APK."
    }

    Invoke-CheckedCommand -Executable "adb" -Arguments ($AdbArgs + @("install", "-r", $AppApk)) -FailureMessage "Failed to install the benchmark app APK."
    $installsPerformed = $true
  } else {
    Write-Host "Skipping app benchmark APK install because the hash matches the last installed build."
  }

  if ($macroNeedsInstall) {
    if (-not $macroInstalledOnDevice) {
      Write-Host "Macrobenchmark package is missing on device $ResolvedSerial. Reinstalling macrobenchmark APK."
    }

    Invoke-CheckedCommand -Executable "adb" -Arguments ($AdbArgs + @("install", "-r", $MacrobenchmarkApk)) -FailureMessage "Failed to install the macrobenchmark APK."
    $installsPerformed = $true
  } else {
    Write-Host "Skipping macrobenchmark APK install because the hash matches the last installed build."
  }

  $state[$ResolvedSerial] = @{
    AppHash = $appHash
    MacroHash = $macroHash
  }
  Save-AdbInstallState -State $state

  return $installsPerformed
}

function Prepare-BenchmarkDevice {
  param([string[]]$AdbArgs)

  Invoke-CheckedCommand -Executable "adb" -Arguments ($AdbArgs + @("shell", "svc", "power", "stayon", "true")) -FailureMessage "Failed to keep the benchmark device awake."
  Invoke-CheckedCommand -Executable "adb" -Arguments ($AdbArgs + @("shell", "input", "keyevent", "224")) -FailureMessage "Failed to wake the benchmark device."
  Invoke-CheckedCommand -Executable "adb" -Arguments ($AdbArgs + @("shell", "input", "keyevent", "82")) -FailureMessage "Failed to unlock the benchmark device."
}

function Assert-ValidBenchmarkMetrics {
  param([string]$InstrumentationLogPath)

  $invalidMetricLines = Get-Content -Path $InstrumentationLogPath | Select-String 'gfx_frame_total_count_median=0\.0|gfx_frame_time_50th_percentile_millis_median=4950\.0'
  if ($invalidMetricLines) {
    throw "Benchmark run completed, but invalid frame samples were detected. Wake and unlock the device, then rerun. Log: $InstrumentationLogPath"
  }
}

function Assert-ValidInstrumentationResult {
  param([string]$InstrumentationLogPath)

  $crashLines = Get-Content -Path $InstrumentationLogPath | Select-String 'INSTRUMENTATION_RESULT: shortMsg=Process crashed'
  if ($crashLines) {
    throw "Benchmark instrumentation crashed on device. Log: $InstrumentationLogPath"
  }
}

function Assert-BenchmarkTestsPassed {
  param([string]$InstrumentationLogPath)

  $failureLines =
    Get-Content -Path $InstrumentationLogPath | Select-String 'FAILURES!!!|There was \d+ failure|There were \d+ failures|^Error in '
  if ($failureLines) {
    throw "Benchmark instrumentation reported test failures. Log: $InstrumentationLogPath"
  }
}

function Invoke-InstrumentationAttempt {
  param(
    [string[]]$AdbArgs,
    [string]$BenchmarkClass,
    [string]$ArtifactRoot
  )

  $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $attemptRoot = Join-Path $ArtifactRoot $timestamp
  New-Item -ItemType Directory -Path $attemptRoot -Force | Out-Null

  $instrumentationArgs = @("shell", "am", "instrument", "-w")
  if ($BenchmarkClass) {
    $instrumentationArgs += @("-e", "class", $BenchmarkClass)
  }
  $instrumentationArgs += "selfgemma.talk.macrobenchmark/androidx.test.runner.AndroidJUnitRunner"

  $instrumentationLogPath = Join-Path $attemptRoot "instrumentation-output.txt"
  $pulledArtifactRoot = Join-Path $attemptRoot "device-media"
  New-Item -ItemType Directory -Path $pulledArtifactRoot -Force | Out-Null

  try {
    Prepare-BenchmarkDevice -AdbArgs $AdbArgs

    & "adb" @AdbArgs @instrumentationArgs 2>&1 | Tee-Object -FilePath $instrumentationLogPath | Out-Host
    $instrumentationExitCode = $LASTEXITCODE
    if ($instrumentationExitCode -ne 0) {
      throw "adb instrumentation failed. Exit code: $instrumentationExitCode"
    }

    Assert-ValidInstrumentationResult -InstrumentationLogPath $instrumentationLogPath
    Assert-BenchmarkTestsPassed -InstrumentationLogPath $instrumentationLogPath

    & "adb" @AdbArgs pull "/sdcard/Android/media/selfgemma.talk.macrobenchmark/." $pulledArtifactRoot
    if ($LASTEXITCODE -ne 0) {
      Write-Warning "Failed to pull macrobenchmark media artifacts from the device. Continuing with the instrumentation log only."
    }
  }
  finally {
    & "adb" @AdbArgs shell svc power stayon false | Out-Null
  }

  return $instrumentationLogPath
}

function Invoke-AdbBenchmark {
  param(
    [string]$BenchmarkClass,
    [string]$Serial
  )

  if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb was not found in PATH."
  }

  $resolvedSerial = Resolve-BenchmarkDeviceSerial -RequestedSerial $Serial
  Write-Host "Running adb benchmark on pinned device $resolvedSerial"
  $adbArgs = Get-AdbArgs -Serial $resolvedSerial
  $appApk = (Resolve-Path ".\app\build\outputs\apk\benchmark\app-benchmark.apk").Path
  $macrobenchmarkApk = (Resolve-Path ".\macrobenchmark\build\outputs\apk\benchmark\macrobenchmark-benchmark.apk").Path

  Invoke-CheckedCommand -Executable "adb" -Arguments ($adbArgs + @("wait-for-device")) -FailureMessage "Failed to connect to the benchmark device."
  $installsPerformed = Install-BenchmarkApksIfNeeded -ResolvedSerial $resolvedSerial -AdbArgs $adbArgs -AppApk $appApk -MacrobenchmarkApk $macrobenchmarkApk

  $sessionTimestamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $artifactRoot = Join-Path (Get-ArtifactOutputRoot) "adb-$sessionTimestamp"
  New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null

  $attemptCount = 2
  $lastInstrumentationLogPath = ""
  for ($attempt = 1; $attempt -le $attemptCount; $attempt++) {
    $lastInstrumentationLogPath = Invoke-InstrumentationAttempt -AdbArgs $adbArgs -BenchmarkClass $BenchmarkClass -ArtifactRoot $artifactRoot

    try {
      Assert-ValidBenchmarkMetrics -InstrumentationLogPath $lastInstrumentationLogPath
      Write-Host "Artifacts copied to $artifactRoot from device $resolvedSerial"
      return
    }
    catch {
      if ($attempt -lt $attemptCount) {
        if ($installsPerformed) {
          Write-Warning "Invalid frame samples were detected immediately after APK install. Retrying once without reinstall."
        }
        else {
          Write-Warning "Invalid frame samples were detected. Retrying the benchmark once on the same installed build."
        }
        continue
      }

      throw
    }
  }
}

function Use-AdbByDefaultForAutoRunner {
  param([string]$Serial)

  $resolvedSerial = Resolve-BenchmarkDeviceSerial -RequestedSerial $Serial
  return $resolvedSerial -eq $PinnedBenchmarkDeviceSerial
}

Push-Location (Resolve-Path (Join-Path $PSScriptRoot ".."))

try {
  $benchmarkClass = Get-BenchmarkClassFilter -SelectedSuite $Suite -ExplicitClassFilter $ClassFilter

  Invoke-CheckedCommand -Executable ".\gradlew.bat" -Arguments @(':app:assembleBenchmark', ':macrobenchmark:assembleBenchmark') -FailureMessage "Failed to assemble benchmark APKs."

  $gradleArgs = @(':macrobenchmark:connectedBenchmarkAndroidTest')
  if ($benchmarkClass) {
    $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.class=$benchmarkClass"
  }

  if ($Runner -eq "gradle") {
    $gradleExitCode = Invoke-GradleBenchmark -GradleArguments $gradleArgs -Serial $DeviceSerial
    if ($gradleExitCode -ne 0) {
      throw "Gradle benchmark execution failed. Exit code: $gradleExitCode"
    }

    exit 0
  }

  if ($Runner -eq "auto") {
    if (Use-AdbByDefaultForAutoRunner -Serial $DeviceSerial) {
      Write-Warning "Auto runner is using adb by default on $PinnedBenchmarkDeviceSerial to avoid interactive install prompts from Gradle on this device."
    }
    else {
      $gradleExitCode = Invoke-GradleBenchmark -GradleArguments $gradleArgs -Serial $DeviceSerial
      if ($gradleExitCode -eq 0) {
        exit 0
      }

      Write-Warning "Gradle benchmark execution failed. Falling back to adb instrumentation."
    }
  }

  Invoke-AdbBenchmark -BenchmarkClass $benchmarkClass -Serial $DeviceSerial
  exit 0
}
finally {
  Pop-Location
}
