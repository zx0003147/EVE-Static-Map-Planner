[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SovereigntyRepo
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:StageNumber = 0
$script:StageCount = 8
$script:GradleInvocationCount = 0
$script:StartedAt = [System.Diagnostics.Stopwatch]::StartNew()
$featureApiArtifactVersion = "2.1.0"

function Write-Stage {
    param([Parameter(Mandatory = $true)][string]$Message)

    $script:StageNumber += 1
    Write-Host ""
    Write-Host ("[{0}/{1}] {2}" -f $script:StageNumber, $script:StageCount, $Message) -ForegroundColor Cyan
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $FilePath $($Arguments -join ' ')"
    }
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $output = @(& $FilePath @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $FilePath $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $wrapper = Join-Path $Repository "gradlew.bat"
    if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        throw "Gradle wrapper not found: $wrapper"
    }
    $script:GradleInvocationCount += 1
    Invoke-Native -FilePath $wrapper -Arguments (@("--no-daemon", "--console=plain", "-p", $Repository) + $Arguments)
}

function Get-GitValue {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    return (@(Invoke-NativeCapture -FilePath "git.exe" -Arguments (@("-C", $Repository) + $Arguments)) -join "`n").Trim()
}

function Assert-CleanRepository {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $status = Get-GitValue -Repository $Repository -Arguments @("status", "--porcelain=v1", "--untracked-files=all")
    if (-not [string]::IsNullOrWhiteSpace($status)) {
        throw "$Label working tree must be clean. No files were changed or reverted by acceptance.`n$status"
    }
    Invoke-Native -FilePath "git.exe" -Arguments @("-C", $Repository, "diff", "--check")
    Invoke-Native -FilePath "git.exe" -Arguments @("-C", $Repository, "diff", "--cached", "--check")
}

function Get-TrackedFiles {
    param([Parameter(Mandatory = $true)][string]$Repository)

    return @(Invoke-NativeCapture -FilePath "git.exe" -Arguments @("-C", $Repository, "ls-files"))
}

function Assert-NoForbiddenText {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Files,
        [Parameter(Mandatory = $true)][object[]]$Rules
    )

    $violations = @()
    foreach ($relativePath in $Files) {
        $absolutePath = Join-Path $Repository $relativePath
        if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
            continue
        }
        foreach ($rule in $Rules) {
            $match = Select-String -LiteralPath $absolutePath -Pattern $rule.Pattern | Select-Object -First 1
            if ($null -ne $match) {
                $violations += ("{0}:{1}: {2}" -f $relativePath, $match.LineNumber, $rule.Message)
            }
        }
    }
    if ($violations.Count -gt 0) {
        throw "$Label source-independence violations:`n$($violations -join [Environment]::NewLine)"
    }
}

function Assert-SourceIndependence {
    param(
        [Parameter(Mandatory = $true)][string]$CoreRepository,
        [Parameter(Mandatory = $true)][string]$SovereigntyRepository
    )

    $coreFiles = Get-TrackedFiles -Repository $CoreRepository
    $coreBuildAndProductionFiles = @($coreFiles | Where-Object {
        $_ -match '(^|/)(build|settings)\.gradle(\.kts)?$' -or $_ -match '/src/main/.*\.kt$'
    })
    $coreProductionFiles = @($coreFiles | Where-Object { $_ -match '/src/main/.*\.kt$' })
    $coreScripts = @($coreFiles | Where-Object { $_ -match '(^|/)scripts/.*\.ps1$' })
    $sovereigntyModule = "sovereignty" + "-pack"
    $projectCall = "project" + '\s*\(\s*["'']:' + [regex]::Escape($sovereigntyModule) + '["'']'
    $includeCall = "include" + '\s*\(\s*["'']:' + [regex]::Escape($sovereigntyModule) + '["'']'
    Assert-NoForbiddenText -Repository $CoreRepository -Label "Core" -Files $coreBuildAndProductionFiles -Rules @(
        @{ Pattern = $projectCall; Message = "Pack ProjectDependency is forbidden" },
        @{ Pattern = $includeCall; Message = "Pack project inclusion is forbidden" }
    )
    Assert-NoForbiddenText -Repository $CoreRepository -Label "Core" -Files $coreProductionFiles -Rules @(
        @{ Pattern = 'dev\.evestaticmapplanner\.sovereignty'; Message = "production source depends on Sovereignty implementation" }
    )
    $absoluteWindowsUserPath = "C:" + "\\Users\\"
    $implicitSibling = '\.\.[\\/]' + [regex]::Escape("EVE-" + "Sovereignty-Pack")
    Assert-NoForbiddenText -Repository $CoreRepository -Label "Core scripts" -Files $coreScripts -Rules @(
        @{ Pattern = [regex]::Escape($absoluteWindowsUserPath); Message = "machine-specific Windows user path is forbidden" },
        @{ Pattern = $implicitSibling; Message = "implicit Sovereignty sibling lookup is forbidden" }
    )

    $sovFiles = Get-TrackedFiles -Repository $SovereigntyRepository
    $sovBuildAndProductionFiles = @($sovFiles | Where-Object {
        $_ -match '(^|/)(build|settings)\.gradle(\.kts)?$' -or $_ -match '/src/main/.*\.kt$'
    })
    $sovProductionFiles = @($sovFiles | Where-Object { $_ -match '/src/main/.*\.kt$' })
    $featureApiProjectCall = "project" + '\s*\(\s*["'']:feature-api["'']'
    Assert-NoForbiddenText -Repository $SovereigntyRepository -Label "Sovereignty" -Files $sovBuildAndProductionFiles -Rules @(
        @{ Pattern = $featureApiProjectCall; Message = "Feature API ProjectDependency is forbidden" },
        @{ Pattern = [regex]::Escape($absoluteWindowsUserPath); Message = "fixed Core checkout path is forbidden" }
    )
    Assert-NoForbiddenText -Repository $SovereigntyRepository -Label "Sovereignty" -Files $sovProductionFiles -Rules @(
        @{ Pattern = 'dev\.evestaticmapplanner\.(app|core|data|sde|mcp|control)(\.|\b)'; Message = "production source depends on a Core implementation package" }
    )
}

function Assert-CanonicalJar {
    param([Parameter(Mandatory = $true)][string]$JarPath)

    $jar = Get-Item -LiteralPath $JarPath -ErrorAction Stop
    if ($jar.Length -le 0) {
        throw "Canonical Pack JAR is empty: $JarPath"
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $duplicateEntries = @($archive.Entries | Group-Object -Property FullName | Where-Object { $_.Count -gt 1 })
        if ($duplicateEntries.Count -gt 0) {
            throw "Canonical Pack JAR has duplicate entries: $($duplicateEntries.Name -join ', ')"
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Get-TestSummary {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string[]]$ResultDirectories
    )

    $files = @()
    foreach ($directory in $ResultDirectories) {
        if (Test-Path -LiteralPath $directory -PathType Container) {
            $files += @(Get-ChildItem -LiteralPath $directory -Filter "TEST-*.xml" -File -Recurse)
        }
    }
    if ($files.Count -eq 0) {
        throw "No JUnit XML results found for $Label"
    }
    $tests = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    foreach ($file in $files) {
        [xml]$document = Get-Content -Raw -LiteralPath $file.FullName
        $tests += [int]$document.testsuite.tests
        $failures += [int]$document.testsuite.failures
        $errors += [int]$document.testsuite.errors
        $skipped += [int]$document.testsuite.skipped
    }
    if (($failures + $errors) -ne 0) {
        throw "$Label JUnit results contain failures=$failures errors=$errors"
    }
    return [pscustomobject]@{
        Label = $Label
        Tests = $tests
        Skipped = $skipped
        Suites = $files.Count
    }
}

try {
    Write-Stage "Verify repository inputs, clean baselines, and source independence"
    $coreRepo = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
    $sovereigntyRepoPath = (Resolve-Path -LiteralPath $SovereigntyRepo).Path
    if ($coreRepo -eq $sovereigntyRepoPath) {
        throw "Core and Sovereignty repository paths must be different"
    }
    foreach ($repository in @($coreRepo, $sovereigntyRepoPath)) {
        if (-not (Test-Path -LiteralPath (Join-Path $repository ".git") -PathType Container)) {
            throw "Not a Git repository: $repository"
        }
    }
    $coreBranch = Get-GitValue -Repository $coreRepo -Arguments @("branch", "--show-current")
    $sovBranch = Get-GitValue -Repository $sovereigntyRepoPath -Arguments @("branch", "--show-current")
    if ($coreBranch -ne "main" -or $sovBranch -ne "main") {
        throw "Acceptance requires both repositories on main (Core=$coreBranch, Sovereignty=$sovBranch)"
    }
    $coreHead = Get-GitValue -Repository $coreRepo -Arguments @("rev-parse", "HEAD")
    $sovHead = Get-GitValue -Repository $sovereigntyRepoPath -Arguments @("rev-parse", "HEAD")
    Assert-CleanRepository -Repository $coreRepo -Label "Core"
    Assert-CleanRepository -Repository $sovereigntyRepoPath -Label "Sovereignty"
    if (-not [string]::IsNullOrWhiteSpace($env:ORG_GRADLE_PROJECT_sovereigntyPackJar)) {
        throw "ORG_GRADLE_PROJECT_sovereigntyPackJar must be unset so the normal Core build proves no-Pack independence"
    }
    Assert-SourceIndependence -CoreRepository $coreRepo -SovereigntyRepository $sovereigntyRepoPath
    Write-Host "Core:        $coreBranch $coreHead"
    Write-Host "Sovereignty: $sovBranch $sovHead"

    Write-Stage "Publish and verify Feature API $featureApiArtifactVersion in runtime compatibility family 2"
    Invoke-Gradle -Repository $coreRepo -Arguments @(
        ":feature-api:clean",
        ":feature-api:verifyFeatureApiPublication",
        ":feature-api:test",
        "--tests", "dev.evestaticmapplanner.feature.api.FeaturePackCompatibilityTest"
    )
    $featureApiRepository = Join-Path $coreRepo "feature-api\build\test-maven-repository"
    $featureApiVersionDirectory = Join-Path $featureApiRepository "dev\evestaticmapplanner\feature-api\$featureApiArtifactVersion"
    foreach ($artifact in @(
        "feature-api-$featureApiArtifactVersion.jar",
        "feature-api-$featureApiArtifactVersion-sources.jar",
        "feature-api-$featureApiArtifactVersion.pom",
        "feature-api-$featureApiArtifactVersion.module"
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $featureApiVersionDirectory $artifact) -PathType Leaf)) {
            throw "Feature API publication is missing $artifact under $featureApiVersionDirectory"
        }
    }
    $featureApiTests = Get-TestSummary -Label "Feature API contract" -ResultDirectories @(
        (Join-Path $coreRepo "feature-api\build\test-results\test")
    )

    Write-Stage "Build and test Sovereignty independently from Feature API Maven coordinates"
    Invoke-Gradle -Repository $sovereigntyRepoPath -Arguments @(
        "-PfeatureApiRepository=$featureApiRepository",
        "-PfeatureApiArtifactVersion=$featureApiArtifactVersion",
        "clean",
        "check",
        "packageExternalFeaturePack"
    )
    $sovTests = Get-TestSummary -Label "Sovereignty" -ResultDirectories @(
        (Join-Path $sovereigntyRepoPath "build\test-results\test")
    )
    Assert-CleanRepository -Repository $sovereigntyRepoPath -Label "Sovereignty after standalone build"

    Write-Stage "Verify canonical Sovereignty Pack artifact"
    $packJar = (Resolve-Path -LiteralPath (Join-Path $sovereigntyRepoPath "build\external-feature-pack\sovereignty.pack\pack.jar")).Path
    Assert-CanonicalJar -JarPath $packJar
    Write-Host "Canonical artifact: $packJar"

    Write-Stage "Clean-build and test Core without a Sovereignty path or artifact"
    Invoke-Gradle -Repository $coreRepo -Arguments @("clean", "build")
    $coreTestDirectories = @(
        "app", "control", "control-transport", "core", "data", "feature-api", "mcp", "sde"
    ) | ForEach-Object { Join-Path $coreRepo ("{0}\build\test-results\test" -f $_) }
    $coreTestDirectories += Join-Path $coreRepo "feature-api\src\test\fixtures\coordinate-consumer\build\test-results\test"
    $coreTests = Get-TestSummary -Label "Core normal build" -ResultDirectories $coreTestDirectories

    Write-Stage "Run focused no-Pack, compatibility, Host, presentation, and Preferences regressions"
    $focusedTests = @(
        "dev.evestaticmapplanner.featurepack.LocalFeaturePackHostTest",
        "dev.evestaticmapplanner.featurepack.ProductionFeaturePackRuntimeTest",
        "dev.evestaticmapplanner.featurepack.FeaturePackManagerTest",
        "dev.evestaticmapplanner.featurepack.FeatureOverlayHostTest",
        "dev.evestaticmapplanner.featurepack.SystemInfoHostTest",
        "dev.evestaticmapplanner.map.FeatureOverlayPresentationTest",
        "dev.evestaticmapplanner.map.FeatureOverlayPresentationCacheTest",
        "dev.evestaticmapplanner.map.FeatureOverlayEmblemCandidateTest",
        "dev.evestaticmapplanner.map.FeatureOverlayEmblemsTest",
        "dev.evestaticmapplanner.map.FeatureOverlayLegendTest",
        "dev.evestaticmapplanner.preferences.OverlayVisibilityTest",
        "dev.evestaticmapplanner.preferences.PreferencesStoreTest"
    )
    $focusedArguments = @(
        ":app:test"
    )
    foreach ($testClass in $focusedTests) {
        $focusedArguments += @("--tests", $testClass)
    }
    Invoke-Gradle -Repository $coreRepo -Arguments $focusedArguments
    $focusedTestSummary = Get-TestSummary -Label "Core focused regressions" -ResultDirectories @(
        (Join-Path $coreRepo "app\build\test-results\test")
    )

    Write-Stage "Run explicit external Sovereignty Pack integration from fresh cached fixture data"
    Invoke-Gradle -Repository $coreRepo -Arguments @(
        ":app:test",
        "-PsovereigntyPackJar=$packJar",
        "--tests", "dev.evestaticmapplanner.featurepack.SovereigntyPackIntegrationTest"
    )
    $integrationTests = Get-TestSummary -Label "Cross-repository integration" -ResultDirectories @(
        (Join-Path $coreRepo "app\build\test-results\test")
    )

    Write-Stage "Verify the Core-owned MCP catalog remains exactly 22 tools"
    Invoke-Gradle -Repository $coreRepo -Arguments @(
        ":mcp:test",
        "--tests", "dev.evestaticmapplanner.mcp.McpToolCatalogTest"
    )
    $mcpTests = Get-TestSummary -Label "MCP catalog" -ResultDirectories @(
        (Join-Path $coreRepo "mcp\build\test-results\test")
    )

    Assert-SourceIndependence -CoreRepository $coreRepo -SovereigntyRepository $sovereigntyRepoPath
    Assert-CleanRepository -Repository $coreRepo -Label "Core after acceptance"
    Assert-CleanRepository -Repository $sovereigntyRepoPath -Label "Sovereignty after acceptance"
    if ((Get-GitValue -Repository $coreRepo -Arguments @("rev-parse", "HEAD")) -ne $coreHead) {
        throw "Core HEAD changed during acceptance"
    }
    if ((Get-GitValue -Repository $sovereigntyRepoPath -Arguments @("rev-parse", "HEAD")) -ne $sovHead) {
        throw "Sovereignty HEAD changed during acceptance"
    }

    $script:StartedAt.Stop()
    Write-Host ""
    Write-Host "PASS - repeatable cross-repository Feature Pack acceptance" -ForegroundColor Green
    Write-Host "Feature API: dev.evestaticmapplanner:feature-api:$featureApiArtifactVersion (runtime compatibility family 2)"
    Write-Host "Pack: sovereignty.pack 0.2.1; required API 2; publisher/name verified"
    Write-Host "Host integration: API 2; ClassLoader 1; ServiceLoader entrypoint 1; Overlay/System Info registered and unregistered"
    Write-Host "No-Pack: no ClassLoader, storage, Public ESI, or worker"
    Write-Host "MCP catalog: exactly 22 tools"
    Write-Host ("Tests: Feature API {0}, Sovereignty {1}, Core build {2}, focused {3}, integration {4}, MCP {5}" -f `
        $featureApiTests.Tests, $sovTests.Tests, $coreTests.Tests, $focusedTestSummary.Tests, $integrationTests.Tests, $mcpTests.Tests)
    Write-Host ("Gradle invocations: {0}; duration: {1:c}" -f $script:GradleInvocationCount, $script:StartedAt.Elapsed)
    exit 0
}
catch {
    $script:StartedAt.Stop()
    [Console]::Error.WriteLine("")
    [Console]::Error.WriteLine("FAIL - cross-repository Feature Pack acceptance")
    [Console]::Error.WriteLine($_.Exception.Message)
    [Console]::Error.WriteLine(("Duration before failure: {0:c}" -f $script:StartedAt.Elapsed))
    exit 1
}
