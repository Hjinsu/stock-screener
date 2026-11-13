# FMP API 키를 FMP_API_KEY 사용자 환경변수로 저장한다(선택: ADR·해외 발행사 시총 보조 확인, 일 사용 상한 FMP_DAILY_BUDGET).
# 사용: powershell -ExecutionPolicy Bypass -File .\scripts\set-fmp-key.ps1
$ErrorActionPreference = 'Stop'

function Read-Secret([string]$prompt) {
    $secure = Read-Host $prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr).Trim() }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$v0 = Read-Secret 'FMP API 키'
if ($v0 -notmatch '^[A-Za-z0-9]{16,64}$') { throw 'FMP API 키 형식이 올바르지 않습니다 (영문·숫자 16~64자).' }
[Environment]::SetEnvironmentVariable('FMP_API_KEY', $v0, 'User')
Remove-Variable v0
Write-Host 'FMP_API_KEY 사용자 환경변수를 저장했습니다. 값은 표시하지 않습니다. VS Code·터미널·대시보드(serve)를 다시 시작해야 적용됩니다.'
Write-Host '확인: .\run.ps1 check-sources'
