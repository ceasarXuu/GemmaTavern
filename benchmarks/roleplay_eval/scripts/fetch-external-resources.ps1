param(
  [switch]$RefreshExisting
)

$ErrorActionPreference = "Stop"

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

function Invoke-GitCommand {
  param(
    [string[]]$Arguments,
    [bool]$SkipLfsSmudge = $false,
    [string]$FailureMessage
  )

  $previousGitLfsSkipSmudge = $env:GIT_LFS_SKIP_SMUDGE

  try {
    if ($SkipLfsSmudge) {
      $env:GIT_LFS_SKIP_SMUDGE = "1"
    }

    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
      throw "$FailureMessage Exit code: $LASTEXITCODE"
    }
  }
  finally {
    if ($null -eq $previousGitLfsSkipSmudge) {
      Remove-Item Env:GIT_LFS_SKIP_SMUDGE -ErrorAction SilentlyContinue
    }
    else {
      $env:GIT_LFS_SKIP_SMUDGE = $previousGitLfsSkipSmudge
    }
  }
}

function Get-RepoSizeBytes {
  param([string]$Path)

  $measure =
    Get-ChildItem -LiteralPath $Path -Recurse -Force -File |
    Measure-Object -Property Length -Sum

  if ($null -eq $measure.Sum) {
    return [int64]0
  }

  return [int64]$measure.Sum
}

function Find-FirstMatch {
  param(
    [string]$Path,
    [string[]]$Patterns
  )

  foreach ($pattern in $Patterns) {
    $match =
      Get-ChildItem -LiteralPath $Path -File -Force |
      Where-Object { $_.Name -like $pattern } |
      Select-Object -First 1
    if ($match) {
      return $match.Name
    }
  }

  return $null
}

function Get-JsonFieldValue {
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

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Resolve-Path (Join-Path $scriptDir "..")
$resourceDir = Join-Path $rootDir "resources"
$lockPath = Join-Path $resourceDir "external-resources.lock.json"
$manifestPath = Join-Path $resourceDir "external-resources.json"

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
  throw "git was not found in PATH."
}

if (-not (Test-Path -LiteralPath $manifestPath)) {
  throw "Missing resource manifest at $manifestPath"
}

$resources = Get-Content -LiteralPath $manifestPath -Encoding UTF8 -Raw | ConvertFrom-Json
$lockEntries = @()

foreach ($resource in $resources) {
  $storageSubdir =
    if ([string]::IsNullOrWhiteSpace($resource.storageSubdir)) {
      "external"
    }
    else {
      [string]$resource.storageSubdir
    }
  $storageRoot = Join-Path $rootDir $storageSubdir
  $targetPath = Join-Path $storageRoot $resource.localDirName
  $skipLfsSmudge = [bool]$resource.skipLfsSmudge
  $fetchMode =
    if ([string]::IsNullOrWhiteSpace($resource.fetchMode)) {
      "git_clone"
    }
    else {
      [string]$resource.fetchMode
    }
  $repoExists = Test-Path -LiteralPath (Join-Path $targetPath ".git")

  New-Item -ItemType Directory -Path $storageRoot -Force | Out-Null

  if ($fetchMode -eq "hf_api_metadata") {
    New-Item -ItemType Directory -Path $targetPath -Force | Out-Null
    $apiUrl = [string]$resource.apiUrl
    if ([string]::IsNullOrWhiteSpace($apiUrl)) {
      throw "Missing apiUrl for $($resource.name)."
    }

    Write-Host "Fetching metadata for $($resource.name) from $apiUrl"
    $apiResponse = Invoke-WebRequest -UseBasicParsing $apiUrl
    $apiContent = $apiResponse.Content
    $apiPath = Join-Path $targetPath "model_api.json"
    $apiContent | Set-Content -LiteralPath $apiPath -Encoding UTF8
    $apiModel = $apiContent | ConvertFrom-Json

    $readmeSaved = $false
    $configSaved = $false
    $siblings = @(Get-JsonFieldValue -Object $apiModel -PropertyName "siblings")
    if ($siblings) {
      if ($siblings.rfilename -contains "README.md") {
        try {
          Invoke-WebRequest -UseBasicParsing "$($resource.repoUrl)/resolve/main/README.md" -OutFile (Join-Path $targetPath "README.md")
          $readmeSaved = $true
        }
        catch {
          Write-Warning "Failed to fetch README.md for $($resource.name): $($_.Exception.Message)"
        }
      }

      if ($siblings.rfilename -contains "config.json") {
        try {
          Invoke-WebRequest -UseBasicParsing "$($resource.repoUrl)/resolve/main/config.json" -OutFile (Join-Path $targetPath "config.json")
          $configSaved = $true
        }
        catch {
          Write-Warning "Failed to fetch config.json for $($resource.name): $($_.Exception.Message)"
        }
      }
    }

    $lockEntries += [pscustomobject]@{
      id = $resource.id
      name = $resource.name
      repoUrl = $resource.repoUrl
      localPath = $targetPath
      storageSubdir = $storageSubdir
      fetchMode = $fetchMode
      commit = Get-JsonFieldValue -Object $apiModel -PropertyName "sha"
      branch = "metadata"
      readme = if ($readmeSaved) { "README.md" } else { $null }
      license = $null
      skipLfsSmudge = $false
      sizeBytes = Get-RepoSizeBytes -Path $targetPath
      preparedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
      purpose = $resource.purpose
      gated = [bool](Get-JsonFieldValue -Object $apiModel -PropertyName "gated")
      usedStorage = Get-JsonFieldValue -Object $apiModel -PropertyName "usedStorage"
      siblings = @($siblings | ForEach-Object { $_.rfilename })
      configSaved = $configSaved
    }

    continue
  }

  if (-not $repoExists) {
    Write-Host "Cloning $($resource.name) into $targetPath"
    $cloneArguments =
      if ($skipLfsSmudge) {
        @("-c", "http.version=HTTP/1.1", "clone", "--depth", "1", "--single-branch", "--no-tags", "--filter=blob:none", $resource.repoUrl, $targetPath)
      }
      else {
        @("clone", "--depth", "1", "--single-branch", "--no-tags", $resource.repoUrl, $targetPath)
      }
    Invoke-GitCommand -Arguments $cloneArguments -SkipLfsSmudge:$skipLfsSmudge -FailureMessage "Failed to clone $($resource.name)."
  }
  elseif ($RefreshExisting) {
    Write-Host "Refreshing $($resource.name) in $targetPath"
    Invoke-GitCommand -Arguments @("-C", $targetPath, "pull", "--ff-only", "--depth", "1") -SkipLfsSmudge:$skipLfsSmudge -FailureMessage "Failed to refresh $($resource.name)."
  }
  else {
    Write-Host "Keeping existing checkout for $($resource.name)"
  }

  $commit = (& git -C $targetPath rev-parse HEAD).Trim()
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to resolve HEAD for $($resource.name)."
  }

  $branch = (& git -C $targetPath branch --show-current).Trim()
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to resolve branch for $($resource.name)."
  }

  $remoteUrl = (& git -C $targetPath remote get-url origin).Trim()
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to resolve origin URL for $($resource.name)."
  }

  $readme = Find-FirstMatch -Path $targetPath -Patterns @("README*", "readme*")
  $license = Find-FirstMatch -Path $targetPath -Patterns @("LICENSE*", "License*", "license*")

  $lockEntries += [pscustomobject]@{
    id = $resource.id
    name = $resource.name
    repoUrl = $remoteUrl
    localPath = $targetPath
    storageSubdir = $storageSubdir
    fetchMode = $fetchMode
    commit = $commit
    branch = $branch
    readme = $readme
    license = $license
    skipLfsSmudge = $skipLfsSmudge
    sizeBytes = Get-RepoSizeBytes -Path $targetPath
    preparedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    purpose = $resource.purpose
  }
}

$lockEntries |
  ConvertTo-Json -Depth 4 |
  Set-Content -LiteralPath $lockPath -Encoding UTF8

Write-Host "External benchmark resources prepared. Lock file: $lockPath"
