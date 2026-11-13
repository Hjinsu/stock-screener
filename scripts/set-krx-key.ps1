# KRX 정보데이터시스템 OpenAPI 인증키를 KRX_API_KEY 사용자 환경변수로 저장한다. 키는 화면에 표시하지 않고 저장소에도 넣지 않는다.
# 발급: https://openapi.krx.co.kr → API 인증키 신청 → 서비스별 이용 신청·승인(유가증권/코스닥 일별매매정보, KOSPI/KOSDAQ 시리즈 일별시세정보).
# 인증키는 계정당 하나이며 서비스 승인은 같은 키에 추가된다. 키 유효기간 1년, 12개월 미사용 시 삭제될 수 있다(이용약관).
# 사용: powershell -ExecutionPolicy Bypass -File .\scripts\set-krx-key.ps1
$ErrorActionPreference = 'Stop'

function Read-Secret([string]$prompt) {
    $secure = Read-Host $prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr).Trim() }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$v0 = Read-Secret 'KRX API 인증키'
if ($v0 -notmatch '^[A-Za-z0-9_\-]{16,128}$') { throw 'KRX API 인증키 형식이 올바르지 않습니다 (영문·숫자·_- 16~128자).' }
[Environment]::SetEnvironmentVariable('KRX_API_KEY', $v0, 'User')
Remove-Variable v0
Write-Host 'KRX_API_KEY 사용자 환경변수를 저장했습니다. 값은 표시하지 않습니다. VS Code·터미널·대시보드(serve)를 다시 시작해야 적용됩니다.'
Write-Host '확인: .\run.ps1 check-sources'
