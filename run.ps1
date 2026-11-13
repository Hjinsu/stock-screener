# 실행 래퍼: env.local.ps1(있으면)을 읽고 screener.jar 를 실행한다.
# 예)
#   .\run.ps1 check-sources
#   .\run.ps1 run                       # 한국·미국 전체 자동 선별
#   .\run.ps1 run --market KR --limit 40   # 검증용 소량 실행
#   .\run.ps1 demo                      # 합성 데이터 데모(data\demo.db)
#   .\run.ps1 serve                     # 대시보드 http://127.0.0.1:8765
#   .\run.ps1 serve --demo              # 데모 DB 대시보드
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$local = Join-Path $PSScriptRoot 'env.local.ps1'
if (Test-Path $local) { . $local }
if (-not (Test-Path (Join-Path $PSScriptRoot 'screener.jar'))) {
  Write-Host 'screener.jar 가 없습니다. 먼저 .\build.ps1 을 실행하세요.'
  exit 1
}
$java = 'java'
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
& $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -jar (Join-Path $PSScriptRoot 'screener.jar') @args
exit $LASTEXITCODE
