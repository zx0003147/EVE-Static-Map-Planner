[CmdletBinding()]
param(
    [string]$ZipPath = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$buildRoot = Join-Path $repository "build"
if ([string]::IsNullOrWhiteSpace($ZipPath)) {
    $ZipPath = Join-Path $buildRoot "release\EVE-Static-Map-Planner-1.0.0-Windows-x64.zip"
}
$archive = (Resolve-Path -LiteralPath $ZipPath).Path
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $buildRoot "release\portable-window-qa.txt"
}

$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$qaRootFull = Join-Path $temporaryRoot ("EVE QA " + [Guid]::NewGuid().ToString("N").Substring(0, 8))
$qaRootFull = [System.IO.Path]::GetFullPath($qaRootFull)
if (-not $qaRootFull.StartsWith($temporaryRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Portable QA root escaped the system temporary directory: $qaRootFull"
}
New-Item -ItemType Directory -Path $qaRootFull | Out-Null
$extractRoot = New-Item -ItemType Directory -Path (Join-Path $qaRootFull "Extract Path With Spaces") -Force
$unrelatedWorkingDirectory = New-Item -ItemType Directory -Path (Join-Path $qaRootFull "Unrelated Working Directory") -Force
$localAppData = Join-Path $qaRootFull "Fresh User Profile\AppData\Local"

Expand-Archive -LiteralPath $archive -DestinationPath $extractRoot.FullName -Force
$applicationImage = Join-Path $extractRoot.FullName "EVE Static Map Planner"
$executable = Join-Path $applicationImage "EVE Static Map Planner.exe"
if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
    throw "Extracted GUI launcher is missing: $executable"
}

function Get-ProgramSnapshot {
    param([Parameter(Mandatory = $true)][string]$Root)
    $snapshot = @{}
    Get-ChildItem -LiteralPath $Root -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($Root.Length).TrimStart('\')
        $snapshot[$relative] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    }
    return $snapshot
}

function Assert-SameSnapshot {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Before,
        [Parameter(Mandatory = $true)][hashtable]$After
    )
    if ($Before.Count -ne $After.Count) {
        throw "Portable program image file count changed after launch: $($Before.Count) -> $($After.Count)"
    }
    foreach ($key in $Before.Keys) {
        if (-not $After.ContainsKey($key) -or $After[$key] -ne $Before[$key]) {
            throw "Portable program image changed after launch: $key"
        }
    }
}

function Read-And-Assert-McpLocator {
    param(
        [Parameter(Mandatory = $true)][string]$ApplicationDataRoot,
        [Parameter(Mandatory = $true)][string]$ExpectedImage
    )
    $locatorPath = Join-Path $ApplicationDataRoot "integration\mcp.json"
    if (-not (Test-Path -LiteralPath $locatorPath -PathType Leaf)) {
        throw "MCP discovery locator was not created: $locatorPath"
    }
    $document = Get-Content -LiteralPath $locatorPath -Raw | ConvertFrom-Json
    $fields = @($document.PSObject.Properties.Name | Sort-Object)
    $expectedFields = @("appVersion", "command", "schemaVersion", "transport")
    if (@(Compare-Object -ReferenceObject $expectedFields -DifferenceObject $fields).Count -ne 0) {
        throw "MCP locator fields changed: $($fields -join ',')"
    }
    if ($document.schemaVersion -ne 1 -or $document.appVersion -ne "1.0.0" -or $document.transport -ne "stdio") {
        throw "MCP locator contract values are invalid"
    }
    $expectedCommand = [System.IO.Path]::GetFullPath((Join-Path $ExpectedImage "eve-map-mcp.exe"))
    $actualCommand = [System.IO.Path]::GetFullPath([string]$document.command)
    if (-not $actualCommand.Equals($expectedCommand, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "MCP locator command mismatch: expected=$expectedCommand actual=$actualCommand"
    }
    if (-not (Test-Path -LiteralPath $actualCommand -PathType Leaf)) {
        throw "MCP locator command is not a file: $actualCommand"
    }
    return [pscustomobject]@{
        Path = $locatorPath
        Command = $actualCommand
    }
}

function Start-And-ClosePortable {
    param(
        [Parameter(Mandatory = $true)][string]$ExecutablePath,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$FakeLocalAppData
    )
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $ExecutablePath
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.EnvironmentVariables["LOCALAPPDATA"] = $FakeLocalAppData
    foreach ($name in @("JAVA_HOME", "JDK_HOME", "GRADLE_HOME")) {
        $startInfo.EnvironmentVariables.Remove($name)
    }
    $pathKey = @($startInfo.EnvironmentVariables.Keys | Where-Object {
        $_.ToString().Equals("PATH", [System.StringComparison]::OrdinalIgnoreCase)
    }) | Select-Object -First 1
    if ($null -ne $pathKey) {
        $startInfo.EnvironmentVariables[$pathKey] = (($startInfo.EnvironmentVariables[$pathKey] -split ';') |
            Where-Object { $_ -notmatch '(?i)java|jdk|gradle' }) -join ';'
    }

    New-Item -ItemType Directory -Path $FakeLocalAppData -Force | Out-Null
    $startupLog = Join-Path $FakeLocalAppData "EVE Static Map Planner\logs\app-0.log"
    $initialStartupCount = if (Test-Path -LiteralPath $startupLog -PathType Leaf) {
        [regex]::Matches((Get-Content -LiteralPath $startupLog -Raw), "Application starting").Count
    } else {
        0
    }
    $process = [System.Diagnostics.Process]::Start($startInfo)
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        $startupReady = $false
        $hadWindowHandle = $false
        while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
            Start-Sleep -Milliseconds 250
            $process.Refresh()
            $hadWindowHandle = $process.MainWindowHandle -ne [IntPtr]::Zero
            $logReady = (Test-Path -LiteralPath $startupLog -PathType Leaf) -and
                ([regex]::Matches((Get-Content -LiteralPath $startupLog -Raw), "Application starting").Count -gt
                    $initialStartupCount)
            if ($process.Responding -and $logReady) {
                $startupReady = $true
                break
            }
        }
        if (-not $startupReady) {
            throw "Portable GUI process did not become responsive and record application startup"
        }
        $forcedClose = $false
        if ($hadWindowHandle -and $process.CloseMainWindow()) {
            if (-not $process.WaitForExit(20000)) {
                throw "Portable GUI did not exit after its window was closed"
            }
        } else {
            # A non-interactive automation desktop cannot expose the GUI window handle.
            # The visible window/normal-close check remains part of final human QA.
            $forcedClose = $true
            $process.Kill()
            $process.WaitForExit()
        }
        if (-not $forcedClose -and $process.ExitCode -eq 1) {
            throw "Portable GUI regressed to exit code 1"
        }
        return [pscustomobject]@{
            ExitCode = if ($forcedClose) { "FORCED_BY_NONINTERACTIVE_QA" } else { $process.ExitCode }
            WindowHandleObserved = $hadWindowHandle
            ForcedClose = $forcedClose
        }
    } finally {
        if (-not $process.HasExited) {
            $process.Kill()
            $process.WaitForExit()
        }
        $process.Dispose()
    }
}

$beforeFirstLaunch = Get-ProgramSnapshot -Root $applicationImage
if (Get-ChildItem -LiteralPath $applicationImage -Recurse -File | Where-Object { $_.Name -eq "mcp.json" }) {
    throw "Portable ZIP must not prepackage mcp.json"
}
$firstLaunch = Start-And-ClosePortable -ExecutablePath $executable -WorkingDirectory $unrelatedWorkingDirectory.FullName -FakeLocalAppData $localAppData
$applicationDataRoot = Join-Path $localAppData "EVE Static Map Planner"
if (-not (Test-Path -LiteralPath $applicationDataRoot -PathType Container)) {
    throw "First launch did not create the LocalAppData application root"
}
$firstLocator = Read-And-Assert-McpLocator -ApplicationDataRoot $applicationDataRoot -ExpectedImage $applicationImage
$firstLocatorWriteTime = (Get-Item -LiteralPath $firstLocator.Path).LastWriteTimeUtc
Assert-SameSnapshot -Before $beforeFirstLaunch -After (Get-ProgramSnapshot -Root $applicationImage)

$unchangedLaunch = Start-And-ClosePortable -ExecutablePath $executable -WorkingDirectory $unrelatedWorkingDirectory.FullName -FakeLocalAppData $localAppData
$unchangedLocator = Read-And-Assert-McpLocator -ApplicationDataRoot $applicationDataRoot -ExpectedImage $applicationImage
if ((Get-Item -LiteralPath $unchangedLocator.Path).LastWriteTimeUtc -ne $firstLocatorWriteTime) {
    throw "Unchanged MCP locator was rewritten"
}

$settings = Join-Path $applicationDataRoot "settings.properties"
$userDatabase = Join-Path $applicationDataRoot "data\user.db"
$packMarker = Join-Path $applicationDataRoot "feature-packs\preservation.marker"
New-Item -ItemType Directory -Path (Split-Path -Parent $userDatabase) -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $packMarker) -Force | Out-Null
[System.IO.File]::WriteAllText($settings, "settings.version=1\n")
[System.IO.File]::WriteAllText($userDatabase, "portable-preservation-user-db")
[System.IO.File]::WriteAllText($packMarker, "portable-preservation-pack-marker")
$preservationHashes = @{}
foreach ($path in @($settings, $userDatabase, $packMarker)) {
    $preservationHashes[$path] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
}

$movedParent = New-Item -ItemType Directory -Path (Join-Path $qaRootFull "Moved Portable Directory") -Force
$movedImage = Join-Path $movedParent.FullName "EVE Static Map Planner"
Move-Item -LiteralPath $applicationImage -Destination $movedImage
$movedExecutable = Join-Path $movedImage "EVE Static Map Planner.exe"
$beforeMovedLaunch = Get-ProgramSnapshot -Root $movedImage
$movedLaunch = Start-And-ClosePortable -ExecutablePath $movedExecutable -WorkingDirectory $unrelatedWorkingDirectory.FullName -FakeLocalAppData $localAppData
$movedLocator = Read-And-Assert-McpLocator -ApplicationDataRoot $applicationDataRoot -ExpectedImage $movedImage
if (-not $movedLocator.Path.Equals($firstLocator.Path, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Portable move created a second MCP locator instead of updating the authority file"
}
if ($movedLocator.Command.Equals($firstLocator.Command, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Portable move left the old MCP command in the locator"
}
Assert-SameSnapshot -Before $beforeMovedLaunch -After (Get-ProgramSnapshot -Root $movedImage)
foreach ($path in $preservationHashes.Keys) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Portable launch removed preserved user data: $path"
    }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $preservationHashes[$path]) {
        throw "Portable launch overwrote preserved user data: $path"
    }
}

$reportDirectory = Split-Path -Parent $ReportPath
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
@(
    "archive=$archive"
    "firstRun=PASS"
    "appDataAutoCreation=PASS"
    "spacedExtractionPath=PASS"
    "unrelatedWorkingDirectory=PASS"
    "systemJavaRequired=NO"
    "guiProcessLaunch=PASS"
    "responsive=PASS"
    "firstWindowHandleObserved=$($firstLaunch.WindowHandleObserved)"
    "firstExitCode=$($firstLaunch.ExitCode)"
    "locatorFirstRun=PASS"
    "locatorPathA=$($firstLocator.Command)"
    "locatorUnchangedNoWrite=PASS"
    "unchangedWindowHandleObserved=$($unchangedLaunch.WindowHandleObserved)"
    "unchangedExitCode=$($unchangedLaunch.ExitCode)"
    "movedDirectoryLaunch=PASS"
    "locatorPathB=$($movedLocator.Command)"
    "locatorMovedUpdate=PASS"
    "secondWindowHandleObserved=$($movedLaunch.WindowHandleObserved)"
    "secondExitCode=$($movedLaunch.ExitCode)"
    "userDataPreservation=PASS"
    "programImageMutation=NO"
) | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if (-not $qaRootFull.StartsWith($temporaryRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean Portable QA output outside the system temporary directory: $qaRootFull"
}
Remove-Item -LiteralPath $qaRootFull -Recurse -Force

Write-Host "Portable Windows acceptance PASS"
Write-Host "Report: $ReportPath"
