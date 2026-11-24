param([switch]$Unregister)

$ErrorActionPreference = 'Stop'
$taskName = 'AI-Idea-Collector-Daily'
$taskPath = '\'
if ($Unregister) {
    Write-Host "작업 해제 · $taskName"
    if (Get-ScheduledTask -TaskName $taskName -TaskPath $taskPath -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $taskName -TaskPath $taskPath -Confirm:$false
    }
    exit 0
}

Write-Warning "매일 실행은 폰에서 합니다. PC 작업을 등록하면 실행과 메일이 중복될 수 있습니다."
$script = Join-Path $PSScriptRoot 'run-scheduled.ps1'
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
Write-Host "작업 등록 또는 갱신 · $taskName · 매일 07:00"
Write-Host "놓친 실행은 가능한 즉시 실행 · 네트워크 필요 · 배터리 사용 허용"
Write-Host "실행 파일 · $script"

$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$action = New-ScheduledTaskAction -Execute $powershell -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$script`""
$trigger = New-ScheduledTaskTrigger -Daily -At ([datetime]::Today.AddHours(7))
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RunOnlyIfNetworkAvailable `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $taskName -TaskPath $taskPath -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal -Force
