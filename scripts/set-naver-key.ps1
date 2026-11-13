# NAVER API HUB 검색 API 인증 정보(Client ID, Client Secret)를 NAVER_CLIENT_ID, NAVER_CLIENT_SECRET 사용자 환경변수로 저장한다(선택: MSI 뉴스 심리).
# 발급: 네이버 클라우드 플랫폼 콘솔 → Application Services → NAVER API HUB → Application 등록 → API 관리 › 인증 정보.
# 호출: https://naverapihub.apigw.ntruss.com/search/v1/news (헤더 X-NCP-APIGW-API-KEY-ID / X-NCP-APIGW-API-KEY).
# 기존 developers.naver.com 의 Client ID/Secret 은 API HUB 에서 사용할 수 없다. 한시적 무료이며 유료 전환은 공지 예정.
# 사용: powershell -ExecutionPolicy Bypass -File .\scripts\set-naver-key.ps1
$ErrorActionPreference = 'Stop'

function Read-Secret([string]$prompt) {
    $secure = Read-Host $prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr).Trim() }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$v0 = Read-Secret 'NAVER API HUB Client ID'
if ($v0 -notmatch '^[A-Za-z0-9_\-]{8,64}$') { throw 'NAVER API HUB Client ID 형식이 올바르지 않습니다 (영문·숫자 8~64자).' }
$v1 = Read-Secret 'NAVER API HUB Client Secret'
if ($v1 -notmatch '^[A-Za-z0-9_\-]{6,64}$') { throw 'NAVER API HUB Client Secret 형식이 올바르지 않습니다 (영문·숫자 6~64자).' }
[Environment]::SetEnvironmentVariable('NAVER_CLIENT_ID', $v0, 'User')
[Environment]::SetEnvironmentVariable('NAVER_CLIENT_SECRET', $v1, 'User')
Remove-Variable v0,v1
Write-Host 'NAVER_CLIENT_ID, NAVER_CLIENT_SECRET 사용자 환경변수를 저장했습니다. 값은 표시하지 않습니다. VS Code·터미널·대시보드(serve)를 다시 시작해야 적용됩니다.'
Write-Host '확인: .\run.ps1 check-sources'
