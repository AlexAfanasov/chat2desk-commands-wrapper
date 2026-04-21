$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

$gradle = Join-Path $repoRoot "gradlew.bat"
if (-not (Test-Path $gradle)) {
    throw "gradlew.bat is missing in repository root"
}

$tasksOutput = & $gradle tasks --all --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Failed to list Gradle tasks"
}

$tasksText = $tasksOutput | Out-String
if ($tasksText -notmatch "ktlintCheck") {
    throw "ktlintCheck task is missing"
}

if ($tasksText -notmatch "detekt") {
    throw "detekt task is missing"
}

& $gradle clean :wrapper:assemble :wrapper:test ktlintCheck detekt --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build/test/quality tasks failed"
}

& $gradle :wrapper:publishToMavenLocal --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Failed to publish wrapper module to Maven Local"
}

& $gradle :sample:assemble --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Consumer smoke test failed: sample module could not resolve/compile published dependency"
}

$propertiesRaw = Get-Content (Join-Path $repoRoot "gradle.properties") -Raw
$versionMatch = [regex]::Match($propertiesRaw, "(?m)^VERSION_NAME=(.+)$")
if (-not $versionMatch.Success) {
    throw "VERSION_NAME not found in gradle.properties"
}

$versionName = $versionMatch.Groups[1].Value.Trim()
if ($versionName -notmatch "^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$") {
    throw "VERSION_NAME must follow semantic format, got '$versionName'"
}

if ($env:GITHUB_REF_TYPE -eq "tag") {
    if ($env:GITHUB_REF_NAME -notmatch "^v\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$") {
        throw "Tag '$($env:GITHUB_REF_NAME)' does not match required release tag format vX.Y.Z[-suffix]"
    }
}

$readme = Get-Content (Join-Path $repoRoot "README.md") -Raw
if ($readme -notmatch "(?im)^##\s+Usage\b") {
    throw "README.md must contain a '## Usage' section"
}

Write-Host "check-handoff passed"
