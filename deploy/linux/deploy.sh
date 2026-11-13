#!/usr/bin/env bash
# 배포·업데이트: Git 최신 소스 → 빌드·테스트 → 새 릴리스 디렉터리 → current 전환 → 스키마 마이그레이션 → 서비스 재시작.
# 사용: sudo deploy/linux/deploy.sh [--no-pull] [--skip-tests]
# 실패 시 current 링크를 바꾸지 않으므로 기존 버전이 계속 동작한다. 되돌리기: sudo deploy/linux/rollback.sh
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "sudo 로 실행하세요" >&2; exit 1; }
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
APP=/opt/stock-screener
PULL=1; TESTS=""
for a in "$@"; do case "$a" in --no-pull) PULL=0 ;; --skip-tests) TESTS="--skip-tests" ;; esac; done

cd "$SRC"
if [ "$PULL" = 1 ] && [ -d .git ]; then
  sudo -u "$(stat -c %U "$SRC")" git pull --ff-only
fi
REV="$( [ -d .git ] && git rev-parse --short HEAD || echo local )"
bash "$SRC/deploy/linux/build.sh" $TESTS

REL="$APP/releases/$(date +%Y%m%d-%H%M%S)-$REV"
install -d -m 0755 "$REL/lib"
install -m 0644 screener.jar "$REL/"
install -m 0644 lib/*.jar "$REL/lib/"
echo "$REV" > "$REL/REVISION"

# 실행 중인 일괄 실행이 있으면 끝날 때까지 기다린 뒤 전환(동시 실행은 앱의 파일 잠금이 막지만 배포 중 교체를 피함)
for i in $(seq 1 120); do
  systemctl is-active --quiet stock-screener-run@KR.service stock-screener-run@US.service || break
  echo "일괄 실행 진행 중 — 대기($i)"; sleep 30
done

PREV="$(readlink -f "$APP/current" 2>/dev/null || true)"
ln -sfn "$REL" "$APP/current.new" && mv -T "$APP/current.new" "$APP/current"
[ -n "$PREV" ] && echo "$PREV" > "$APP/PREVIOUS"

# 스키마 마이그레이션(추가형 — 이전 버전 앱과 호환)
/usr/local/bin/stock-screener db-migrate || { echo "스키마 마이그레이션 실패 — rollback.sh 로 되돌리세요" >&2; exit 1; }
systemctl restart stock-screener-web.service
systemctl --no-pager --lines=0 status stock-screener-web.service || true

# 오래된 릴리스 정리(최근 5개 유지)
ls -1dt "$APP"/releases/* 2>/dev/null | tail -n +6 | xargs -r rm -rf
echo "배포 완료: $REL"
