param(
  [Parameter(Mandatory = $true)]
  [string]$ManifestPath,
  [string]$ModelName,
  [string]$DeviceSerial,
  [string]$AppId = "selfgemma.talk",
  [string]$AdbPath = "adb",
  [int]$TimeoutMinutes = 45,
  [int]$StartupTimeoutSeconds = 30,
  [string]$RunId,
  [switch]$SkipBuild
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

function Invoke-CheckedCommandCapture {
  param(
    [string]$Executable,
    [string[]]$Arguments,
    [string]$FailureMessage
  )

  $output = & $Executable @Arguments 2>&1
  if ($LASTEXITCODE -ne 0) {
    $renderedOutput = ($output | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($renderedOutput)) {
      throw "$FailureMessage Exit code: $LASTEXITCODE"
    }
    throw "$FailureMessage Exit code: $LASTEXITCODE`n$renderedOutput"
  }

  return $output
}

function Get-JsonPropertyValue {
  param(
    [object]$Object,
    [string]$PropertyName
  )

  if ($null -eq $Object) {
    return $null
  }

  $property = $Object.PSObject.Properties[$PropertyName]
  if ($null -eq $property) {
    return $null
  }

  return $property.Value
}

function Resolve-DeviceSerial {
  param(
    [string]$Executable,
    [string]$RequestedSerial
  )

  if (-not [string]::IsNullOrWhiteSpace($RequestedSerial)) {
    return $RequestedSerial.Trim()
  }

  $devicesOutput = Invoke-CheckedCommandCapture -Executable $Executable -Arguments @("devices") -FailureMessage "Failed to list adb devices."
  $deviceLines =
    $devicesOutput |
    Where-Object { $_ -match "^\S+\s+device$" } |
    ForEach-Object { ($_ -split "\s+")[0] }

  if ($deviceLines.Count -eq 0) {
    throw "No connected Android device in 'device' state was found."
  }

  if ($deviceLines.Count -gt 1) {
    throw "Multiple Android devices are connected. Re-run with -DeviceSerial."
  }

  return [string]$deviceLines[0]
}

function New-ManifestCopy {
  param(
    [string]$SourcePath,
    [string]$TargetPath,
    [string]$ModelNameOverride,
    [string]$RunIdValue
  )

  $manifestObject = Get-Content -LiteralPath $SourcePath -Encoding UTF8 -Raw | ConvertFrom-Json

  if (-not [string]::IsNullOrWhiteSpace($ModelNameOverride)) {
    if ($null -eq (Get-JsonPropertyValue -Object $manifestObject -PropertyName "modelName")) {
      Add-Member -InputObject $manifestObject -NotePropertyName "modelName" -NotePropertyValue $ModelNameOverride
    }
    else {
      $manifestObject.modelName = $ModelNameOverride
    }
  }

  $existingRunLabel = [string](Get-JsonPropertyValue -Object $manifestObject -PropertyName "runLabel")
  $finalRunLabel =
    if ([string]::IsNullOrWhiteSpace($existingRunLabel)) {
      "device-run/$RunIdValue"
    }
    else {
      "$existingRunLabel [$RunIdValue]"
    }

  if ($null -eq (Get-JsonPropertyValue -Object $manifestObject -PropertyName "runLabel")) {
    Add-Member -InputObject $manifestObject -NotePropertyName "runLabel" -NotePropertyValue $finalRunLabel
  }
  else {
    $manifestObject.runLabel = $finalRunLabel
  }

  $manifestObject |
    ConvertTo-Json -Depth 16 |
    Set-Content -LiteralPath $TargetPath -Encoding UTF8
}

function Invoke-Adb {
  param(
    [string]$Executable,
    [string]$Serial,
    [string[]]$Arguments,
    [string]$FailureMessage
  )

  $allArguments = @("-s", $Serial) + $Arguments
  Invoke-CheckedCommand -Executable $Executable -Arguments $allArguments -FailureMessage $FailureMessage
}

function Invoke-AdbCapture {
  param(
    [string]$Executable,
    [string]$Serial,
    [string[]]$Arguments,
    [string]$FailureMessage
  )

  $allArguments = @("-s", $Serial) + $Arguments
  return Invoke-CheckedCommandCapture -Executable $Executable -Arguments $allArguments -FailureMessage $FailureMessage
}

function Ensure-LocalRunDir {
  param(
    [string]$ReportsRoot,
    [string]$RunIdValue
  )

  $runDir = Join-Path $ReportsRoot $RunIdValue
  if (Test-Path -LiteralPath $runDir) {
    $backupRoot = Join-Path $ReportsRoot "_backup"
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
    $timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
    Move-Item -LiteralPath $runDir -Destination (Join-Path $backupRoot "$RunIdValue-$timestamp") -Force
  }

  New-Item -ItemType Directory -Path $runDir -Force | Out-Null
  return $runDir
}

function Try-PullDeviceRunArtifacts {
  param(
    [string]$Executable,
    [string]$Serial,
    [string]$DeviceRunDir,
    [string]$ReportsRoot
  )

  try {
    Invoke-Adb -Executable $Executable -Serial $Serial -Arguments @("pull", $DeviceRunDir, $ReportsRoot) -FailureMessage "Failed to pull device run artifacts."
    return $true
  }
  catch {
    Write-Warning "Unable to pull partial device run artifacts from $DeviceRunDir."
    return $false
  }
}

function Write-DeviceLogcatSnapshot {
  param(
    [string]$Executable,
    [string]$Serial,
    [string]$DestinationPath
  )

  $logLines =
    Invoke-CheckedCommandCapture `
      -Executable $Executable `
      -Arguments @("-s", $Serial, "logcat", "-d", "-v", "time") `
      -FailureMessage "Failed to capture device logcat."

  $filterPattern = "RoleplayEval|roleplay_eval|selfgemma\\.talk|AndroidRuntime|FATAL EXCEPTION|ModelManager|manifestPath|run-status"
  $allLines = @($logLines | ForEach-Object { [string]$_ })
  $filteredLines = @($allLines | Where-Object { $_ -match $filterPattern })
  if ($filteredLines.Count -eq 0) {
    $filteredLines = @($allLines | Select-Object -Last 200)
  }

  Set-Content -LiteralPath $DestinationPath -Value $filteredLines -Encoding UTF8
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceRoot = Resolve-Path (Join-Path $scriptDir "..")
$repoRoot = Resolve-Path (Join-Path $workspaceRoot "..\..")
$androidRoot = Join-Path $repoRoot "Android\src"
$reportsRoot = Join-Path $workspaceRoot "reports"
$generatedManifestRoot = Join-Path $workspaceRoot "cache\generated-manifests"

if (-not (Get-Command $AdbPath -ErrorAction SilentlyContinue)) {
  throw "adb was not found in PATH."
}

$resolvedManifest = Resolve-Path $ManifestPath
$resolvedSerial = Resolve-DeviceSerial -Executable $AdbPath -RequestedSerial $DeviceSerial
$runIdValue =
  if ([string]::IsNullOrWhiteSpace($RunId)) {
    "rp-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
  }
  else {
    $RunId.Trim()
  }

New-Item -ItemType Directory -Path $reportsRoot -Force | Out-Null
New-Item -ItemType Directory -Path $generatedManifestRoot -Force | Out-Null

$generatedManifestPath = Join-Path $generatedManifestRoot "$runIdValue.json"
New-ManifestCopy -SourcePath $resolvedManifest -TargetPath $generatedManifestPath -ModelNameOverride $ModelName -RunIdValue $runIdValue

if (-not $SkipBuild) {
  if (-not (Test-Path -LiteralPath $androidRoot)) {
    throw "Android project directory was not found at $androidRoot"
  }

  Write-Host "Installing benchmark build onto device $resolvedSerial"
  Push-Location $androidRoot
  try {
    Invoke-CheckedCommand -Executable ".\gradlew.bat" -Arguments @(":app:installBenchmark", "--no-daemon") -FailureMessage "Failed to install benchmark build."
  }
  finally {
    Pop-Location
  }
}

$deviceRoot = "/sdcard/Android/media/$AppId/roleplay_eval"
$deviceInputDir = "$deviceRoot/input"
$deviceManifestPath = "$deviceInputDir/$runIdValue.json"
$deviceRunDir = "$deviceRoot/runs/$runIdValue"
$deviceStatusPath = "$deviceRunDir/run-status.json"
$deviceSummaryPath = "$deviceRunDir/run-summary.json"
$deviceActivity = "$AppId/selfgemma.talk.performance.roleplayeval.RoleplayEvalActivity"
$localRunDir = Join-Path $reportsRoot $runIdValue
$originalStayOnSetting = $null
$stayonWasApplied = $false

Invoke-Adb -Executable $AdbPath -Serial $resolvedSerial -Arguments @("shell", "mkdir", "-p", $deviceInputDir) -FailureMessage "Failed to create device input directory."
Invoke-Adb -Executable $AdbPath -Serial $resolvedSerial -Arguments @("push", $generatedManifestPath, $deviceManifestPath) -FailureMessage "Failed to push manifest to device."
try {
  $originalStayOnSetting =
    ((
      Invoke-AdbCapture `
        -Executable $AdbPath `
        -Serial $resolvedSerial `
        -Arguments @("shell", "settings", "get", "global", "stay_on_while_plugged_in") `
        -FailureMessage "Failed to query stay_on_while_plugged_in."
    ) | Out-String).Trim()
  if ([string]::IsNullOrWhiteSpace($originalStayOnSetting) -or $originalStayOnSetting -eq "null") {
    $originalStayOnSetting = "0"
  }

  Invoke-Adb -Executable $AdbPath -Serial $resolvedSerial -Arguments @("shell", "svc", "power", "stayon", "usb") -FailureMessage "Failed to enable USB stay-awake."
  $stayonWasApplied = $true
}
catch {
  Write-Warning "Unable to enable temporary USB stay-awake on the device. Continuing."
}
try {
  Invoke-Adb -Executable $AdbPath -Serial $resolvedSerial -Arguments @("shell", "input", "keyevent", "KEYCODE_WAKEUP") -FailureMessage "Failed to wake device."
  Invoke-Adb -Executable $AdbPath -Serial $resolvedSerial -Arguments @("shell", "wm", "dismiss-keyguard") -FailureMessage "Failed to dismiss keyguard."
}
catch {
  Write-Warning "Unable to wake or dismiss keyguard on the device before launch. Continuing."
}
try {
  try {
    Invoke-Adb -Executable $AdbPath -Serial $resolvedSerial -Arguments @("shell", "am", "force-stop", $AppId) -FailureMessage "Failed to force-stop the app before benchmark launch."
    Start-Sleep -Seconds 1
  }
  catch {
    Write-Warning "Unable to force-stop the app before benchmark launch. Continuing."
  }

  try {
    Invoke-Adb -Executable $AdbPath -Serial $resolvedSerial -Arguments @("logcat", "-c") -FailureMessage "Failed to clear device logcat."
  }
  catch {
    Write-Warning "Unable to clear device logcat before run start. Continuing with existing buffers."
  }

  $startOutput =
    Invoke-AdbCapture `
      -Executable $AdbPath `
      -Serial $resolvedSerial `
      -Arguments @(
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        $deviceActivity,
        "--es",
        "manifestPath",
        $deviceManifestPath,
        "--es",
        "runId",
        $runIdValue
      ) `
      -FailureMessage "Failed to launch RoleplayEvalActivity."

  $startOutputText = ($startOutput | Out-String).Trim()
  if ($startOutputText -match "Error:" -or $startOutputText -match "Exception") {
    throw "Failed to launch RoleplayEvalActivity.`n$startOutputText"
  }

  Write-Host "Started roleplay eval runId=$runIdValue on device $resolvedSerial"

  $startedAtUtc = (Get-Date).ToUniversalTime()
  $startupDeadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
  $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
  $lastPrintedSignature = ""
  $lastStatus = $null
  $observedAnyStatus = $false

  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2

    $statusRaw =
      Invoke-AdbCapture `
        -Executable $AdbPath `
        -Serial $resolvedSerial `
        -Arguments @("shell", "if [ -f '$deviceStatusPath' ]; then cat '$deviceStatusPath'; fi") `
        -FailureMessage "Failed to query device run status."

    $statusText = ($statusRaw | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($statusText)) {
      if (-not $observedAnyStatus -and (Get-Date) -ge $startupDeadline) {
        $localRunDir = Ensure-LocalRunDir -ReportsRoot $reportsRoot -RunIdValue $runIdValue
        $startupLogPath = Join-Path $localRunDir "device-startup-logcat.txt"
        Write-DeviceLogcatSnapshot -Executable $AdbPath -Serial $resolvedSerial -DestinationPath $startupLogPath
        $pulledArtifacts = Try-PullDeviceRunArtifacts -Executable $AdbPath -Serial $resolvedSerial -DeviceRunDir $deviceRunDir -ReportsRoot $reportsRoot
        $artifactSuffix =
          if ($pulledArtifacts) {
            " Partial device artifacts were pulled to $localRunDir."
          }
          else {
            ""
          }
        throw "No run-status.json was observed within $StartupTimeoutSeconds second(s). Startup logcat: $startupLogPath$artifactSuffix"
      }
      continue
    }

    $observedAnyStatus = $true

    try {
      $status = $statusText | ConvertFrom-Json
    }
    catch {
      Write-Warning "Skipping unparsable status payload: $statusText"
      continue
    }

    $lastStatus = $status
    $signature = "{0}|{1}|{2}|{3}|{4}|{5}" -f `
      [string](Get-JsonPropertyValue -Object $status -PropertyName "state"), `
      [string](Get-JsonPropertyValue -Object $status -PropertyName "phase"), `
      [string](Get-JsonPropertyValue -Object $status -PropertyName "completedCases"), `
      [string](Get-JsonPropertyValue -Object $status -PropertyName "totalCases"), `
      [string](Get-JsonPropertyValue -Object $status -PropertyName "currentCaseId"), `
      [string](Get-JsonPropertyValue -Object $status -PropertyName "resolvedModelName")

    if ($signature -ne $lastPrintedSignature) {
      $phase = [string](Get-JsonPropertyValue -Object $status -PropertyName "phase")
      $state = [string](Get-JsonPropertyValue -Object $status -PropertyName "state")
      $completedCases = [string](Get-JsonPropertyValue -Object $status -PropertyName "completedCases")
      $totalCases = [string](Get-JsonPropertyValue -Object $status -PropertyName "totalCases")
      $currentCaseId = [string](Get-JsonPropertyValue -Object $status -PropertyName "currentCaseId")
      $resolvedModelName = [string](Get-JsonPropertyValue -Object $status -PropertyName "resolvedModelName")
      $errorMessage = [string](Get-JsonPropertyValue -Object $status -PropertyName "errorMessage")

      Write-Host ("[{0}] state={1} phase={2} cases={3}/{4} currentCase={5} model={6}" -f (Get-Date).ToString("s"), $state, $phase, $completedCases, $totalCases, $currentCaseId, $resolvedModelName)
      if (-not [string]::IsNullOrWhiteSpace($errorMessage)) {
        Write-Warning $errorMessage
      }
      $lastPrintedSignature = $signature
    }

    $state = [string](Get-JsonPropertyValue -Object $status -PropertyName "state")
    if ($state -eq "COMPLETED" -or $state -eq "FAILED") {
      break
    }
  }

  if ((Get-Date) -ge $deadline -and ($null -eq $lastStatus -or [string](Get-JsonPropertyValue -Object $lastStatus -PropertyName "state") -notin @("COMPLETED", "FAILED"))) {
    $localRunDir = Ensure-LocalRunDir -ReportsRoot $reportsRoot -RunIdValue $runIdValue
    $timeoutLogPath = Join-Path $localRunDir "device-timeout-logcat.txt"
    Write-DeviceLogcatSnapshot -Executable $AdbPath -Serial $resolvedSerial -DestinationPath $timeoutLogPath
    $pulledArtifacts = Try-PullDeviceRunArtifacts -Executable $AdbPath -Serial $resolvedSerial -DeviceRunDir $deviceRunDir -ReportsRoot $reportsRoot
    $artifactSuffix =
      if ($pulledArtifacts) {
        " Partial device artifacts were pulled to $localRunDir."
      }
      else {
        ""
      }
    throw "Timed out waiting for device run $runIdValue after $TimeoutMinutes minute(s). Timeout logcat: $timeoutLogPath$artifactSuffix"
  }

  if (Test-Path -LiteralPath $localRunDir) {
    $localRunDir = Ensure-LocalRunDir -ReportsRoot $reportsRoot -RunIdValue $runIdValue
  }

  Invoke-Adb -Executable $AdbPath -Serial $resolvedSerial -Arguments @("pull", $deviceRunDir, $reportsRoot) -FailureMessage "Failed to pull run artifacts from device."

  $hostMetadata = [pscustomobject]@{
    runId = $runIdValue
    appId = $AppId
    deviceSerial = $resolvedSerial
    manifestSourcePath = [string]$resolvedManifest
    generatedManifestPath = $generatedManifestPath
    deviceManifestPath = $deviceManifestPath
    deviceRunDir = $deviceRunDir
    deviceSummaryPath = $deviceSummaryPath
    timeoutMinutes = $TimeoutMinutes
    skipBuild = [bool]$SkipBuild
    startedAtUtc = $startedAtUtc.ToString("o")
    finishedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  }
  $hostMetadata |
    ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $localRunDir "host-run.json") -Encoding UTF8

  $statusPath = Join-Path $localRunDir "run-status.json"
  $summaryPath = Join-Path $localRunDir "run-summary.json"
  $errorPath = Join-Path $localRunDir "run-error.json"

  if (-not (Test-Path -LiteralPath $statusPath)) {
    throw "Pulled run artifacts did not contain run-status.json at $statusPath"
  }

  $finalStatus = Get-Content -LiteralPath $statusPath -Encoding UTF8 -Raw | ConvertFrom-Json
  $finalState = [string](Get-JsonPropertyValue -Object $finalStatus -PropertyName "state")
  Write-Host "Artifacts pulled to $localRunDir"

  if ($finalState -eq "FAILED") {
    $errorMessage = [string](Get-JsonPropertyValue -Object $finalStatus -PropertyName "errorMessage")
    if (Test-Path -LiteralPath $errorPath) {
      Write-Warning "Run error details: $errorPath"
    }
    throw "Device run $runIdValue failed. $errorMessage"
  }

  if (-not (Test-Path -LiteralPath $summaryPath)) {
    throw "Pulled run artifacts did not contain run-summary.json at $summaryPath"
  }

  $summary = Get-Content -LiteralPath $summaryPath -Encoding UTF8 -Raw | ConvertFrom-Json
  $caseResults = @($summary.caseResults)
  $failedCases = @($caseResults | Where-Object { -not $_.passed })

  Write-Host ("Completed runId={0} suite={1} cases={2} failedCases={3}" -f $runIdValue, [string]$summary.suiteId, $caseResults.Count, $failedCases.Count)

  if ($failedCases.Count -gt 0) {
    foreach ($failedCase in $failedCases) {
      $failedAssertions =
        @(
          $failedCase.assertionResults |
            Where-Object { -not $_.passed } |
            Select-Object -First 3
        )
      $assertionSummary =
        if ($failedAssertions.Count -eq 0) {
          "no failed assertions recorded"
        }
        else {
          ($failedAssertions | ForEach-Object { "$($_.scope)/$($_.label)" }) -join ", "
        }
      Write-Warning ("Case failed: {0} ({1}) -> {2}" -f [string]$failedCase.caseId, [string]$failedCase.description, $assertionSummary)
    }

    throw "Roleplay eval completed with failed assertions. See $summaryPath"
  }
}
finally {
  if ($stayonWasApplied) {
    try {
      if ($null -ne $originalStayOnSetting) {
        Invoke-Adb -Executable $AdbPath -Serial $resolvedSerial -Arguments @("shell", "settings", "put", "global", "stay_on_while_plugged_in", $originalStayOnSetting) -FailureMessage "Failed to restore stay_on_while_plugged_in."
      }
      else {
        Invoke-Adb -Executable $AdbPath -Serial $resolvedSerial -Arguments @("shell", "svc", "power", "stayon", "false") -FailureMessage "Failed to disable USB stay-awake."
      }
    }
    catch {
      Write-Warning "Unable to restore the device stay-awake setting after the run."
    }
  }

  try {
    Invoke-Adb -Executable $AdbPath -Serial $resolvedSerial -Arguments @("shell", "am", "force-stop", $AppId) -FailureMessage "Failed to force-stop the app after benchmark execution."
  }
  catch {
    Write-Warning "Unable to force-stop the app after benchmark execution."
  }
}
