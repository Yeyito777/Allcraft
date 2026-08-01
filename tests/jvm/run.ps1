param(
    [string]$Jdk = (Join-Path $PSScriptRoot "..\..\jvm\windows-x64"),
    [string]$Scenario = "all"
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Jdk = (Resolve-Path $Jdk).Path
$Output = Join-Path $RepoRoot "build\jvm-tests"
$Java = Join-Path $Jdk "bin\java.exe"
$Jcmd = Join-Path $Jdk "bin\jcmd.exe"

& (Join-Path $PSScriptRoot "build.ps1") -Jdk $Jdk
if ($LASTEXITCODE -ne 0) { throw "JVM test build failed" }

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program exited with code $LASTEXITCODE"
    }
}

function Invoke-Scenario([string]$Name) {
    Invoke-Checked $Java @(
        "-Xms128m", "-Xmx1g",
        "-javaagent:$Output\agent.jar",
        "-XX:+AllowEnhancedClassRedefinition",
        "-XX:CompileThreshold=100",
        "-Dallcraft.jvmtest.versions=$Output\versions",
        "-Dallcraft.jvmtest.jfr=$Output\$Name.jfr",
        "-cp", (Join-Path $Output "classes"),
        "allcraft.jvmtest.JvmRegressionMain", $Name
    )
}

function Invoke-JfrAttach {
    $Log = Join-Path $Output "jfr-attach.log"
    $Recording = Join-Path $Output "jfr-attach.jfr"
    Remove-Item $Log, $Recording -Force -ErrorAction SilentlyContinue
    $Arguments = @(
        "-Xms128m", "-Xmx1g",
        "-javaagent:$Output\agent.jar",
        "-XX:+AllowEnhancedClassRedefinition",
        "-XX:CompileThreshold=100",
        "-Dallcraft.jvmtest.versions=$Output\versions",
        "-cp", (Join-Path $Output "classes"),
        "allcraft.jvmtest.JvmRegressionMain", "jfr-wait"
    )
    $Process = Start-Process $Java -ArgumentList $Arguments -RedirectStandardOutput $Log -RedirectStandardError "$Log.err" -PassThru
    try {
        $Ready = $null
        $Deadline = [DateTime]::UtcNow.AddSeconds(30)
        while ([DateTime]::UtcNow -lt $Deadline) {
            if ($Process.HasExited) {
                throw "JFR attach target exited early with code $($Process.ExitCode)"
            }
            if (Test-Path $Log) {
                $Ready = Select-String -Path $Log -Pattern '^READY pid=(\d+)$' | Select-Object -Last 1
                if ($Ready) { break }
            }
            Start-Sleep -Milliseconds 100
        }
        if (-not $Ready) { throw "Timed out waiting for JFR attach target" }
        $TargetPid = $Ready.Matches[0].Groups[1].Value
        Invoke-Checked $Jcmd @($TargetPid, "JFR.start", "duration=10s", "filename=$Recording", "settings=profile")
        if (-not $Process.WaitForExit(90000)) { throw "JFR attach target timed out" }
        Get-Content $Log
        if (-not (Select-String -Path $Log -Pattern '^PASS jfr-wait ' -Quiet)) {
            Get-Content "$Log.err" -ErrorAction SilentlyContinue
            throw "JFR attach target did not complete successfully"
        }
        if (-not (Test-Path $Recording) -or (Get-Item $Recording).Length -eq 0) {
            throw "JFR attach recording is empty"
        }
    } finally {
        if (-not $Process.HasExited) { Stop-Process $Process -Force }
    }
}

if ($Scenario -eq "all") {
    foreach ($Name in @("configuration", "c2-old-method", "structural", "multi-structural", "repeat", "jfr", "jfr-structural")) {
        Invoke-Scenario $Name
    }
    Invoke-JfrAttach
} elseif ($Scenario -eq "jfr-attach") {
    Invoke-JfrAttach
} else {
    Invoke-Scenario $Scenario
}
