# OpenDART 인증키를 DART_API_KEY 사용자 환경변수로 저장한다.
# 발급: https://opendart.fss.or.kr → 인증키 신청/관리 (영문·숫자 40자). 1인 1키, 제3자 공유 금지(이용약관).
# 사용: powershell -ExecutionPolicy Bypass -File .\scripts\set-dart-key.ps1
$ErrorActionPreference = 'Stop'

function Read-Secret([string]$prompt) {
    $secure = Read-Host $prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr).Trim() }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$v0 = Read-Secret 'OpenDART 인증키'
if ($v0 -notmatch '^[A-Za-z0-9]{40}$') { throw 'OpenDART 인증키 형식이 올바르지 않습니다 (영문·숫자 40자).' }
[Environment]::SetEnvironmentVariable('DART_API_KEY', $v0, 'User')
Remove-Variable v0
Write-Host 'DART_API_KEY 사용자 환경변수를 저장했습니다. 값은 표시하지 않습니다. VS Code·터미널·대시보드(serve)를 다시 시작해야 적용됩니다.'
Write-Host '확인: .\run.ps1 check-sources'
