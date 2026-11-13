#!/usr/bin/env bash
# 직전 릴리스로 되돌리기. 스키마 마이그레이션은 추가형(테이블·컬럼 추가)이라 이전 앱 버전이 그대로 동작한다.
# DB 내용을 되돌려야 하면 restore.sh --apply <백업 파일> 을 사용한다.
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "sudo 로 실행하세요" >&2; exit 1; }
APP=/opt/stock-screener
PREV="$(cat "$APP/PREVIOUS" 2>/dev/null || true)"
[ -n "$PREV" ] && [ -d "$PREV" ] || { echo "직전 릴리스 기록이 없습니다. ls $APP/releases 에서 골라 'ln -sfn <경로> $APP/current' 하세요" >&2; exit 1; }
CUR="$(readlink -f "$APP/current")"
ln -sfn "$PREV" "$APP/current.new" && mv -T "$APP/current.new" "$APP/current"
echo "$CUR" > "$APP/PREVIOUS"
systemctl restart stock-screener-web.service
echo "되돌림: $CUR → $PREV"
