# Windows 작업 스케줄러에 장 마감 후 자동 실행을 등록한다(대시보드를 띄우지 않아도 실행됨).
#   powershell -ExecutionPolicy Bypass -File .\scripts\register-task.ps1                 # 기본 시각 등록
#   powershell -ExecutionPolicy Bypass -File .\scripts\register-task.ps1 -KrTime 18:30 -UsTime 07:00
#   powershell -ExecutionPolicy Bypass -File .\scripts\register-task.ps1 -Remove        # 등록 해제
# 시각은 이 PC의 로컬 시간(한국 PC 기준 KST)입니다.
#  - KR: 월~금 KrTime (KRX 자료가 아직 없으면 직전 영업일이 기준일로 기록됨)
#  - US: 화~토 UsTime (미국 장 마감 후, 한국 시간 아침)
# API 키는 env.local.ps1 에서 읽습니다(run.ps1 이 자동 로드). 작업은 현재 사용자 권한으로 로그온 시에만 실행됩니다.
param([string]$KrTime = '18:30', [string]$UsTime = '07:00', [switch]$Remove)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$run = Join-Path $root 'run.ps1'
$names = @('StockScreener-KR', 'StockScreener-US')
if ($Remove) {
  foreach ($n in $names) { Unregister-ScheduledTask -TaskName $n -Confirm:$false -ErrorAction SilentlyContinue }
  Write-Host '등록 해제 완료'
  exit 0
}
$argKr = "-NoProfile -ExecutionPolicy Bypass -File `"$run`" run --market KR"
$argUs = "-NoProfile -ExecutionPolicy Bypass -File `"$run`" run --market US"
$tKr = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday, Tuesday, Wednesday, Thursday, Friday -At $KrTime
$tUs = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Tuesday, Wednesday, Thursday, Friday, Saturday -At $UsTime
$set = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 3) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $names[0] -Trigger $tKr -Settings $set -Action (New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argKr -WorkingDirectory $root) -Description '한국 주식 자동 선별(장 마감 후)' -Force | Out-Null
Register-ScheduledTask -TaskName $names[1] -Trigger $tUs -Settings $set -Action (New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argUs -WorkingDirectory $root) -Description '미국 주식 자동 선별(장 마감 후)' -Force | Out-Null
Write-Host "등록 완료: $($names[0]) 평일 $KrTime, $($names[1]) 화~토 $UsTime"
Write-Host '확인: Get-ScheduledTask -TaskName StockScreener-*'
