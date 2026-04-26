param(
  [string]$DeviceSerial = "",
  [switch]$SkipDevice
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runRoot = Join-Path $androidRoot "build\smoke-tests\$timestamp"
$debugApkPath = Join-Path $androidRoot "app\build\outputs\apk\debug\app-debug.apk"

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
    -Arguments ($adbArgs + @("install", "-r", $debugApkPath)) `
    -WorkingDirectory $androidRoot `
    -LogPath $installLog

  & adb @adbArgs shell input keyevent 224 | Out-Null
  & adb @adbArgs shell input keyevent 82 | Out-Null

  Invoke-LoggedCommand `
    -Executable "adb" `
    -Arguments ($adbArgs + @("shell", "am", "start", "-W", "-n", "selfgemma.talk/.MainActivity")) `
    -WorkingDirectory $androidRoot `
    -LogPath $launchLog

  Start-Sleep -Seconds 3

  & adb @adbArgs shell dumpsys activity activities 2>&1 |
    Tee-Object -FilePath $activityPath |
    Out-Null

  & adb @adbArgs shell uiautomator dump /sdcard/gemmatavern-smoke-window.xml | Out-Null
  & adb @adbArgs pull /sdcard/gemmatavern-smoke-window.xml $uiDumpPath | Out-Null

  & adb @adbArgs logcat -d -v time AndroidRuntime:E ActivityTaskManager:W ActivityManager:W selfgemma.talk:E '*:S' 2>&1 |
    Tee-Object -FilePath $logcatPath |
    Out-Null

  Assert-NoLaunchCrash -LogcatPath $logcatPath
}

$compileLog = Join-Path $runRoot "compile-debug-kotlin.log"
$unitLog = Join-Path $runRoot "targeted-unit-tests.log"
$assembleLog = Join-Path $runRoot "assemble-debug.log"
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
  -Arguments @(":app:assembleDebug", "--console=plain") `
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
  "Run root: $runRoot",
  "Compile log: $compileLog",
  "Unit log: $unitLog",
  "Assemble log: $assembleLog"
) | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host "Smoke test artifacts written to $runRoot"
