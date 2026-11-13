# 한국은행 ECOS Open API 인증키를 ECOS_API_KEY 사용자 환경변수로 저장한다(선택: 통합 평가용 공식 원/달러 매매기준율).
# 발급: https://ecos.bok.or.kr → 개발자센터(Open API) → 인증키 신청.
# 사용: powershell -ExecutionPolicy Bypass -File .\scripts\set-ecos-key.ps1
$ErrorActionPreference = 'Stop'

function Read-Secret([string]$prompt) {
    $secure = Read-Host $prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr).Trim() }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$v0 = Read-Secret 'ECOS 인증키'
if ($v0 -notmatch '^[A-Za-z0-9]{16,64}$') { throw 'ECOS 인증키 형식이 올바르지 않습니다 (영문·숫자 16~64자).' }
[Environment]::SetEnvironmentVariable('ECOS_API_KEY', $v0, 'User')
Remove-Variable v0
Write-Host 'ECOS_API_KEY 사용자 환경변수를 저장했습니다. 값은 표시하지 않습니다. VS Code·터미널·대시보드(serve)를 다시 시작해야 적용됩니다.'
Write-Host '확인: .\run.ps1 check-sources'
