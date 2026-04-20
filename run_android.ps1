param(
  [string]$DeviceSerial = "",
  [switch]$SkipPull
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path $PSScriptRoot).Path
$androidRoot = (Resolve-Path (Join-Path $repoRoot "Android\src")).Path
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runRoot = Join-Path $androidRoot "build\run-android\$timestamp"
$packageName = "selfgemma.talk"
$launchActivity = "$packageName/.MainActivity"
$buildLogPath = Join-Path $runRoot "build.log"
$gitLogPath = Join-Path $runRoot "git-sync.log"
$devicesLogPath = Join-Path $runRoot "adb-devices.log"
$installLogPath = Join-Path $runRoot "install.log"
$launchLogPath = Join-Path $runRoot "launch.log"
$activityLogPath = Join-Path $runRoot "activity.log"
$windowLogPath = Join-Path $runRoot "window.log"
$summaryPath = Join-Path $runRoot "summary.txt"

New-Item -ItemType Directory -Path $runRoot -Force | Out-Null

function Write-Step {
  param([string]$Message)

  Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $Message)
}

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
    $commandOutput | ForEach-Object { "$_" } | Tee-Object -FilePath $LogPath | Out-Null

    if ($exitCode -ne 0) {
      throw "Command failed: $Executable $($Arguments -join ' ')"
    }

    return ,$commandOutput
  }
  finally {
    $ErrorActionPreference = $previousErrorActionPreference
    Pop-Location
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

function Assert-CommandAvailable {
  param([string]$Name)

  if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
    throw "$Name was not found in PATH."
  }
}

function Get-GitOutput {
  param([string[]]$Arguments)

  $previousErrorActionPreference = $ErrorActionPreference

  try {
    $ErrorActionPreference = "Continue"
    $output = @(& git @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
      throw "git $($Arguments -join ' ') failed. Exit code: $exitCode`n$($output -join [Environment]::NewLine)"
    }
  }
  finally {
    $ErrorActionPreference = $previousErrorActionPreference
  }

  return ,$output
}

function Sync-LatestCode {
  param([string]$LogPath)

  $statusOutput = Get-GitOutput -Arguments @("status", "--porcelain")
  if ($statusOutput.Count -gt 0) {
    throw "Working tree is dirty. Commit changes before pulling latest code, or rerun with -SkipPull."
  }

  $currentBranch = (Get-GitOutput -Arguments @("rev-parse", "--abbrev-ref", "HEAD") | Select-Object -First 1).Trim()
  $upstreamBranch = ""

  try {
    $upstreamBranch = (Get-GitOutput -Arguments @("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}") | Select-Object -First 1).Trim()
  }
  catch {
    $upstreamBranch = ""
  }

  $logLines = @(
    "branch=$currentBranch",
    "upstream=$upstreamBranch"
  )

  $logLines += Get-GitOutput -Arguments @("fetch", "--all", "--prune")
  if ($upstreamBranch) {
    $logLines += Get-GitOutput -Arguments @("pull", "--ff-only")
  }
  else {
    $logLines += "No upstream branch configured; skipped git pull."
  }

  $headCommit = (Get-GitOutput -Arguments @("rev-parse", "HEAD") | Select-Object -First 1).Trim()
  $logLines += "head=$headCommit"
  $logLines | Set-Content -Path $LogPath -Encoding UTF8
}

function Get-ConnectedDeviceSerials {
  param([string]$LogPath)

  $deviceOutput = Invoke-LoggedCommand -Executable "adb" -Arguments @("devices") -WorkingDirectory $repoRoot -LogPath $LogPath
  $serials = @()

  foreach ($line in $deviceOutput) {
    if ($line -match '^(\S+)\s+device$') {
      $serials += $Matches[1]
    }
  }

  return $serials | Select-Object -Unique
}

function Resolve-TargetDeviceSerial {
  param(
    [string]$RequestedSerial,
    [string[]]$ConnectedSerials
  )

  if ($RequestedSerial) {
    if ($ConnectedSerials -notcontains $RequestedSerial) {
      $available = if ($ConnectedSerials.Count -gt 0) { $ConnectedSerials -join ", " } else { "none" }
      throw "Requested device serial '$RequestedSerial' is not connected. Available devices: $available"
    }

    return $RequestedSerial
  }

  if ($ConnectedSerials.Count -eq 1) {
    return $ConnectedSerials[0]
  }

  if ($ConnectedSerials.Count -eq 0) {
    throw "No adb device is connected."
  }

  throw "Multiple adb devices are connected ($($ConnectedSerials -join ', ')). Rerun with -DeviceSerial."
}

function Get-AdbArgs {
  param([string]$Serial)

  return @("-s", $Serial)
}

Assert-CommandAvailable -Name "git"
Assert-CommandAvailable -Name "adb"
Assert-CommandAvailable -Name "powershell"

Push-Location $repoRoot

try {
  Write-Step "Starting run_android"
  Write-Step "Repo root: $repoRoot"
  Write-Step "Android root: $androidRoot"
  Write-Step "Run logs: $runRoot"

  if ($SkipPull) {
    Write-Step "Skipping git sync because -SkipPull was provided"
    $headCommit = (Get-GitOutput -Arguments @("rev-parse", "HEAD") | Select-Object -First 1).Trim()
    @(
      "Skipped git sync because -SkipPull was provided.",
      "head=$headCommit"
    ) | Set-Content -Path $gitLogPath -Encoding UTF8
    Write-Step "Using current HEAD: $headCommit"
  }
  else {
    Write-Step "Syncing latest code from upstream"
    Sync-LatestCode -LogPath $gitLogPath
    $headCommit = (Get-GitOutput -Arguments @("rev-parse", "--short", "HEAD") | Select-Object -First 1).Trim()
    Write-Step "Git sync complete at HEAD $headCommit"
  }

  Write-Step "Detecting connected adb devices"
  $connectedSerials = Get-ConnectedDeviceSerials -LogPath $devicesLogPath
  $resolvedSerial = Resolve-TargetDeviceSerial -RequestedSerial $DeviceSerial -ConnectedSerials $connectedSerials
  $adbArgs = Get-AdbArgs -Serial $resolvedSerial
  $apkPath = Join-Path $androidRoot "app\build\outputs\apk\debug\app-debug.apk"
  Write-Step "Using device: $resolvedSerial"

  Write-Step "Waiting for device to become ready"
  Invoke-CheckedCommand -Executable "adb" -Arguments ($adbArgs + @("wait-for-device")) -FailureMessage "Failed to connect to device $resolvedSerial."

  Write-Step "Stopping Gradle daemons"
  Invoke-LoggedCommand `
    -Executable ".\gradlew.bat" `
    -Arguments @("--stop") `
    -WorkingDirectory $androidRoot `
    -LogPath (Join-Path $runRoot "gradle-stop.log") | Out-Null

  Write-Step "Building debug APK"
  Invoke-LoggedCommand `
    -Executable ".\gradlew.bat" `
    -Arguments @(":app:assembleDebug", "--no-daemon", "--console=plain") `
    -WorkingDirectory $androidRoot `
    -LogPath $buildLogPath | Out-Null

  if (-not (Test-Path $apkPath)) {
    throw "Debug APK was not produced at $apkPath"
  }

  Write-Step "Installing APK to device"
  Invoke-LoggedCommand `
    -Executable "adb" `
    -Arguments ($adbArgs + @("install", "-r", $apkPath)) `
    -WorkingDirectory $repoRoot `
    -LogPath $installLogPath | Out-Null

  Write-Step "Clearing logcat and force-stopping the app"
  Invoke-CheckedCommand -Executable "adb" -Arguments ($adbArgs + @("logcat", "-c")) -FailureMessage "Failed to clear logcat on device $resolvedSerial."
  Invoke-CheckedCommand -Executable "adb" -Arguments ($adbArgs + @("shell", "am", "force-stop", $packageName)) -FailureMessage "Failed to stop $packageName on device $resolvedSerial."

  Write-Step "Launching $launchActivity"
  Invoke-LoggedCommand `
    -Executable "adb" `
    -Arguments ($adbArgs + @("shell", "am", "start", "-W", "-n", $launchActivity)) `
    -WorkingDirectory $repoRoot `
    -LogPath $launchLogPath | Out-Null

  Write-Step "Verifying foreground activity and focused window"
  $activityOutput = Invoke-LoggedCommand `
    -Executable "adb" `
    -Arguments ($adbArgs + @("shell", "dumpsys", "activity", "activities")) `
    -WorkingDirectory $repoRoot `
    -LogPath $activityLogPath

  $windowOutput = Invoke-LoggedCommand `
    -Executable "adb" `
    -Arguments ($adbArgs + @("shell", "dumpsys", "window", "windows")) `
    -WorkingDirectory $repoRoot `
    -LogPath $windowLogPath

  $activityVerified = ($activityOutput | Select-String -Pattern 'topResumedActivity.*selfgemma\.talk/\.MainActivity|mResumedActivity.*selfgemma\.talk/\.MainActivity' -Quiet)
  $windowVerified = ($windowOutput | Select-String -Pattern 'mCurrentFocus.*selfgemma\.talk/selfgemma\.talk\.MainActivity|mFocusedWindow.*selfgemma\.talk/selfgemma\.talk\.MainActivity|imeLayeringTarget.*selfgemma\.talk/selfgemma\.talk\.MainActivity|imeInputTarget.*selfgemma\.talk/selfgemma\.talk\.MainActivity|imeControlTarget.*selfgemma\.talk/selfgemma\.talk\.MainActivity|mResumeActivity:ActivityRecord\{.*selfgemma\.talk/\.MainActivity' -Quiet)

  if (-not $activityVerified) {
    throw "Launch verification failed. $launchActivity was not observed in $activityLogPath"
  }

  if (-not $windowVerified) {
    throw "Window verification failed. $packageName was not observed in $windowLogPath"
  }

  $summaryLines = @(
    "device=$resolvedSerial",
    "package=$packageName",
    "apk=$apkPath",
    "logs=$runRoot",
    "build_log=$buildLogPath",
    "install_log=$installLogPath",
    "launch_log=$launchLogPath",
    "activity_log=$activityLogPath",
    "window_log=$windowLogPath"
  )
  $summaryLines | Set-Content -Path $summaryPath -Encoding UTF8

  Write-Step "run_android completed successfully"
  Write-Host "Device: $resolvedSerial"
  Write-Host "Logs: $runRoot"
}
catch {
  Write-Step "run_android failed"
  Write-Host "Logs: $runRoot"
  throw
}
finally {
  Pop-Location
}
