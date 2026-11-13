# SEC EDGAR 요청에 필요한 User-Agent 를 SEC_USER_AGENT 사용자 환경변수로 저장한다(필수: 미국 상장·재무·공시).
# SEC 공정 접근 정책: 요청 헤더에 '앱 이름 + 연락처 이메일'을 밝혀야 하며 초당 10회 이하로 호출한다.
# 이 값은 SEC(sec.gov, data.sec.gov)에만 전송되고 로그·DB에는 마스킹된다.
# 사용: powershell -ExecutionPolicy Bypass -File .\scripts\set-sec-useragent.ps1
$ErrorActionPreference = 'Stop'

$app = (Read-Host '앱 이름(예: MyScreener/1.0)').Trim()
$mail = (Read-Host '연락처 이메일').Trim()
if ($app -notmatch '^[A-Za-z0-9._/\-]{2,40}$') { throw '앱 이름은 영문·숫자·._/- 2~40자로 입력하세요.' }
if ($mail -notmatch '^[^@\s]+@[^@\s]+\.[^@\s]+$') { throw '이메일 형식이 올바르지 않습니다.' }
[Environment]::SetEnvironmentVariable('SEC_USER_AGENT', "$app $mail", 'User')
Remove-Variable app, mail
Write-Host 'SEC_USER_AGENT 사용자 환경변수를 저장했습니다. VS Code·터미널·대시보드(serve)를 다시 시작해야 적용됩니다.'
