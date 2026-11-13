#!/usr/bin/env bash
# 서버 CLI 래퍼: 서비스 계정(screener)·서버 환경 파일로 앱 명령을 실행한다.
# 키를 명령줄 인자로 펼치지 않으므로 프로세스 목록·셸 기록에 남지 않는다.
# 예) sudo stock-screener check-sources | db-status | run --market KR --limit 40 | export-csv
set -euo pipefail
[ "$(id -u)" -eq 0 ] || exec sudo "$0" "$@"
exec systemd-run --quiet --pty --wait --collect \
  -p User=screener -p Group=screener \
  -p EnvironmentFile=/etc/stock-screener/screener.env \
  -p WorkingDirectory=/var/lib/stock-screener \
  /usr/bin/java -Dfile.encoding=UTF-8 -jar /opt/stock-screener/current/screener.jar "$@"
