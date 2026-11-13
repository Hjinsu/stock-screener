# Tiingo API 토큰을 TIINGO_API_KEY 사용자 환경변수로 저장한다(선택: 미국 전 종목 최근가로 예비 시총 계산, 일봉 예비 공급원).
# 발급: https://www.tiingo.com 가입(무료) → 계정 메뉴 API → Token. 무료 요금제: 시간당 50회·일 1,000회·월 500종목, 본인 내부 용도만.
# 사용: powershell -ExecutionPolicy Bypass -File .\scripts\set-tiingo-key.ps1
$ErrorActionPreference = 'Stop'

function Read-Secret([string]$prompt) {
    $secure = Read-Host $prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr).Trim() }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$v0 = Read-Secret 'Tiingo API Token'
if ($v0 -notmatch '^[A-Za-z0-9]{20,64}$') { throw 'Tiingo API Token 형식이 올바르지 않습니다 (영문·숫자 20~64자).' }
[Environment]::SetEnvironmentVariable('TIINGO_API_KEY', $v0, 'User')
Remove-Variable v0
Write-Host 'TIINGO_API_KEY 사용자 환경변수를 저장했습니다. 값은 표시하지 않습니다. VS Code·터미널·대시보드(serve)를 다시 시작해야 적용됩니다.'
Write-Host '확인: .\run.ps1 check-sources'
