param(
    [string]$Jdk = (Join-Path $PSScriptRoot "..\..\jvm\windows-x64")
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Jdk = (Resolve-Path $Jdk).Path
$Output = Join-Path $RepoRoot "build\jvm-tests"
$Javac = Join-Path $Jdk "bin\javac.exe"
$Jar = Join-Path $Jdk "bin\jar.exe"

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program exited with code $LASTEXITCODE"
    }
}

Remove-Item $Output -Recurse -Force -ErrorAction SilentlyContinue
New-Item (Join-Path $Output "classes") -ItemType Directory -Force | Out-Null
New-Item (Join-Path $Output "versions") -ItemType Directory -Force | Out-Null

$MainSources = @(
    "tests\jvm\src\allcraft\jvmtest\Agent.java",
    "tests\jvm\src\allcraft\jvmtest\BaseEntity.java",
    "tests\jvm\src\allcraft\jvmtest\EvolutionContract.java"
) | ForEach-Object { Join-Path $RepoRoot $_ }
Invoke-Checked $Javac (@("-g", "-d", (Join-Path $Output "classes")) + $MainSources)

foreach ($Version in @("v1", "v2-body", "v2-structural", "v3-structural")) {
    $VersionOutput = Join-Path $Output "versions\$Version"
    New-Item $VersionOutput -ItemType Directory -Force | Out-Null
    $Sources = @(Get-ChildItem (Join-Path $RepoRoot "tests\jvm\versions\$Version") -Filter "*.java" -Recurse | Sort-Object FullName | ForEach-Object FullName)
    if ($Sources.Count -gt 0) {
        Invoke-Checked $Javac (@("-g", "-cp", (Join-Path $Output "classes"), "-d", $VersionOutput) + $Sources)
    }
}

foreach ($Version in @("v2-structural", "v3-structural")) {
    Invoke-Checked $Jar @(
        "--create", "--file", (Join-Path $Output "versions\$Version.jar"),
        "-C", (Join-Path $Output "versions\$Version"), "."
    )
}

Copy-Item (Join-Path $Output "versions\v1\*") (Join-Path $Output "classes") -Recurse -Force
Invoke-Checked $Javac @(
    "-g", "-cp", (Join-Path $Output "classes"), "-d", (Join-Path $Output "classes"),
    (Join-Path $RepoRoot "tests\jvm\src\allcraft\jvmtest\JvmRegressionMain.java")
)

$Manifest = Join-Path $Output "agent.mf"
@"
Premain-Class: allcraft.jvmtest.Agent
Can-Redefine-Classes: true
Can-Retransform-Classes: true

"@ | Set-Content $Manifest -Encoding ascii -NoNewline
Invoke-Checked $Jar @(
    "--create", "--file", (Join-Path $Output "agent.jar"), "--manifest", $Manifest,
    "-C", (Join-Path $Output "classes"), "allcraft/jvmtest/Agent.class"
)
Write-Host "Built JVM regression harness at $Output"
