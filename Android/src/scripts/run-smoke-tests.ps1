param(
  [string]$DeviceSerial = "",
  [ValidateSet("Debug", "Release")]
  [string]$BuildType = "Debug",
  [switch]$SkipDevice
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runRoot = Join-Path $androidRoot "build\smoke-tests\$timestamp"
$assembleTask = if ($BuildType -eq "Release") { ":app:assembleRelease" } else { ":app:assembleDebug" }
$apkPath =
  if ($BuildType -eq "Release") {
    Join-Path $androidRoot "app\build\outputs\apk\release\app-release.apk"
  }
  else {
    Join-Path $androidRoot "app\build\outputs\apk\debug\app-debug.apk"
  }

New-Item -ItemType Directory -Path $runRoot -Force | Out-Null

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
    & $Executable @Arguments 2>&1 | Tee-Object -FilePath $LogPath
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
      if (Test-Path $LogPath) {
        Write-Host "Last lines from $LogPath"
        Get-Content -Path $LogPath -Tail 80 | ForEach-Object { Write-Host $_ }
      }
      throw "Command failed: $Executable $($Arguments -join ' ')"
    }
  }
  finally {
    $ErrorActionPreference = $previousErrorActionPreference
    Pop-Location
  }
}

function Get-AdbArgs {
  if ($DeviceSerial) {
    return @("-s", $DeviceSerial)
  }

  return @()
}

function Test-AdbDeviceAvailable {
  if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Warning "adb was not found in PATH. Skipping device install and launch."
    return $false
  }

  $devices = & adb devices
  if ($LASTEXITCODE -ne 0) {
    Write-Warning "adb devices failed. Skipping device install and launch."
    return $false
  }

  if ($DeviceSerial) {
    return ($devices | Select-String "^$([regex]::Escape($DeviceSerial))\s+device$" -Quiet)
  }

  return ($devices | Select-String "^\S+\s+device$" -Quiet)
}

function Assert-NoLaunchCrash {
  param([string]$LogcatPath)

  $crashLines =
    Get-Content -Path $LogcatPath |
      Select-String "FATAL EXCEPTION|AndroidRuntime.*selfgemma\.talk|Process: selfgemma\.talk"

  if ($crashLines) {
    throw "Smoke launch detected AndroidRuntime crash. Log: $LogcatPath"
  }
}

function Assert-LaunchSucceeded {
  param([string]$LaunchLogPath)

  $launchLog = Get-Content -Path $LaunchLogPath -Raw
  if ($launchLog -notmatch "Status:\s+ok" -or $launchLog -notmatch "Activity:\s+selfgemma\.talk/\.MainActivity") {
    throw "Smoke launch did not report a successful MainActivity start. Log: $LaunchLogPath"
  }
}

function Assert-ActivityForeground {
  param([string]$ActivityPath)

  $activityDump = Get-Content -Path $ActivityPath -Raw
  if ($activityDump -notmatch "selfgemma\.talk/\.MainActivity|ResumedActivity.*selfgemma\.talk") {
    throw "Smoke launch did not find selfgemma.talk MainActivity in activity state. Log: $ActivityPath"
  }
}

function Assert-UiHierarchyCaptured {
  param([string]$UiDumpPath)

  if (-not (Test-Path $UiDumpPath)) {
    throw "Smoke UI hierarchy dump was not pulled from the device."
  }

  $uiDump = Get-Content -Path $UiDumpPath -Raw
  if ($uiDump -notmatch 'package="selfgemma\.talk"') {
    throw "Smoke UI hierarchy does not contain the app package. Dump: $UiDumpPath"
  }

  if ($uiDump -notmatch 'text="(消息|Messages)"' -or $uiDump -notmatch 'text="(设置|Settings)"') {
    throw "Smoke UI hierarchy does not show the expected main navigation labels. Dump: $UiDumpPath"
  }
}

function Wait-ForMainUiHierarchy {
  param(
    [string[]]$AdbArgs,
    [string]$UiDumpPath
  )

  $lastError = $null
  for ($attempt = 1; $attempt -le 10; $attempt++) {
    & adb @AdbArgs shell uiautomator dump /sdcard/gemmatavern-smoke-window.xml | Out-Null
    & adb @AdbArgs pull /sdcard/gemmatavern-smoke-window.xml $UiDumpPath | Out-Null

    try {
      Assert-UiHierarchyCaptured -UiDumpPath $UiDumpPath
      return
    }
    catch {
      $lastError = $_
      Start-Sleep -Seconds 2
    }
  }

  throw $lastError
}

function Invoke-DeviceSmoke {
  $adbArgs = Get-AdbArgs
  $installLog = Join-Path $runRoot "device-install.log"
  $launchLog = Join-Path $runRoot "device-launch.log"
  $logcatPath = Join-Path $runRoot "device-logcat.log"
  $activityPath = Join-Path $runRoot "device-activity.log"
  $uiDumpPath = Join-Path $runRoot "device-window.xml"

  & adb @adbArgs logcat -c | Out-Null

  Invoke-LoggedCommand `
    -Executable "adb" `
    -Arguments ($adbArgs + @("install", "-r", $apkPath)) `
    -WorkingDirectory $androidRoot `
    -LogPath $installLog

  & adb @adbArgs shell input keyevent 224 | Out-Null
  & adb @adbArgs shell input keyevent 82 | Out-Null

  Invoke-LoggedCommand `
    -Executable "adb" `
    -Arguments ($adbArgs + @("shell", "am", "start", "-W", "-n", "selfgemma.talk/.MainActivity")) `
    -WorkingDirectory $androidRoot `
    -LogPath $launchLog

  Assert-LaunchSucceeded -LaunchLogPath $launchLog

  Start-Sleep -Seconds 3

  & adb @adbArgs shell dumpsys activity activities 2>&1 |
    Tee-Object -FilePath $activityPath |
    Out-Null
  Assert-ActivityForeground -ActivityPath $activityPath

  Wait-ForMainUiHierarchy -AdbArgs $adbArgs -UiDumpPath $uiDumpPath

  & adb @adbArgs logcat -d -v time AndroidRuntime:E ActivityTaskManager:W ActivityManager:W selfgemma.talk:E '*:S' 2>&1 |
    Tee-Object -FilePath $logcatPath |
    Out-Null

  Assert-NoLaunchCrash -LogcatPath $logcatPath
}

$compileLog = Join-Path $runRoot "compile-debug-kotlin.log"
$unitLog = Join-Path $runRoot "targeted-unit-tests.log"
$assembleLog = Join-Path $runRoot "assemble-$($BuildType.ToLowerInvariant()).log"
$summaryPath = Join-Path $runRoot "summary.txt"

Invoke-LoggedCommand `
  -Executable ".\gradlew.bat" `
  -Arguments @(":app:compileDebugKotlin", "--console=plain") `
  -WorkingDirectory $androidRoot `
  -LogPath $compileLog

$targetedTests = @(
  ":app:testDebugUnitTest",
  "--tests", "selfgemma.talk.feature.roleplay.navigation.RoleplayRoutesTest",
  "--tests", "selfgemma.talk.feature.roleplay.chat.RoleplayChatViewModelTest",
  "--tests", "selfgemma.talk.feature.roleplay.sessions.SessionsViewModelTest",
  "--tests", "selfgemma.talk.domain.roleplay.usecase.RunRoleplayTurnUseCaseTest",
  "--tests", "selfgemma.talk.domain.roleplay.usecase.CompileRoleplayMemoryContextUseCaseTest",
  "--tests", "selfgemma.talk.domain.roleplay.usecase.ExtractMemoriesUseCaseTest",
  "--tests", "selfgemma.talk.data.roleplay.mapper.RoleplayMappersTest",
  "--tests", "com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModelTest",
  "--console=plain"
)

Invoke-LoggedCommand `
  -Executable ".\gradlew.bat" `
  -Arguments $targetedTests `
  -WorkingDirectory $androidRoot `
  -LogPath $unitLog

Invoke-LoggedCommand `
  -Executable ".\gradlew.bat" `
  -Arguments @($assembleTask, "--console=plain") `
  -WorkingDirectory $androidRoot `
  -LogPath $assembleLog

if ($SkipDevice) {
  Write-Warning "Skipping device smoke because -SkipDevice was set."
}
elseif (Test-AdbDeviceAvailable) {
  Invoke-DeviceSmoke
}
else {
  Write-Warning "No ready adb device found. Host smoke passed; device smoke was skipped."
}

@(
  "GemmaTavern smoke test completed.",
  "Build type: $BuildType",
  "Run root: $runRoot",
  "Compile log: $compileLog",
  "Unit log: $unitLog",
  "Assemble log: $assembleLog"
) | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host "Smoke test artifacts written to $runRoot"
