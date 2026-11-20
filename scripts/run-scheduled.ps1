$javaHome = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:JAVA_HOME = $javaHome
$env:JAVA_OPTS = $null
$env:JAVA_TOOL_OPTIONS = $null
$env:JDK_JAVA_OPTIONS = $null
$env:_JAVA_OPTIONS = $null
$env:CORE_OPTS = $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot) -ErrorAction Stop
$logDir = Join-Path $PWD 'out\logs'
New-Item -ItemType Directory -Path $logDir -Force -ErrorAction Stop | Out-Null
$logFile = Join-Path $logDir ((Get-Date -Format 'yyyy-MM-dd') + '.log')

& .\gradlew.bat :core:installDist -q 2>&1 | ForEach-Object {
    [IO.File]::AppendAllText($logFile, "$_`n", $utf8)
}
$status = $LASTEXITCODE
if ($status -ne 0) { exit $status }

& .\core\build\install\core\bin\core.bat --scheduled 2>&1 | ForEach-Object {
    [IO.File]::AppendAllText($logFile, "$_`n", $utf8)
}
exit $LASTEXITCODE
