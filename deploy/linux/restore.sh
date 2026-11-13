#!/usr/bin/env bash
# MariaDB 복원.
#   검증만:  sudo stock-screener-restore --verify <백업.sql.gz>   (검증용 DB 에 복원해 테이블별 건수·스키마 버전 출력, 운영 DB 무변경)
#   적용:    sudo stock-screener-restore --apply  <백업.sql.gz>   (서비스·타이머 정지 → 현재 DB 안전 백업 → 교체 → 재시작 → 상태 출력)
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "sudo 로 실행하세요" >&2; exit 1; }
MODE="${1:-}"; FILE="${2:-}"
DB="${SCREENER_DB_NAME:-screener}"
[ -f "$FILE" ] || { echo "사용: $0 --verify|--apply <백업.sql.gz>" >&2; exit 1; }
if [ -f "$FILE.sha256" ]; then sha256sum -c "$FILE.sha256" || { echo "백업 파일 SHA-256 불일치" >&2; exit 1; }; fi

load_into() { # $1 대상 DB
  mariadb -e "DROP DATABASE IF EXISTS \`$1\`; CREATE DATABASE \`$1\` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;"
  gzip -dc "$FILE" | sed -e "s/^CREATE DATABASE .*\`$DB\`.*/-- restore/" -e "s/^USE \`$DB\`;/USE \`$1\`;/" | mariadb
}
summary() { # $1 DB
  echo "스키마 버전: $(mariadb -N -e "SELECT MAX(version) FROM \`$1\`.schema_migrations")"
  for t in runs scores candidate_state tracking paper_accounts paper_signals paper_orders paper_fills paper_equity_daily; do
    printf '  %-20s %s\n' "$t" "$(mariadb -N -e "SELECT COUNT(*) FROM \`$1\`.\`$t\`" 2>/dev/null || echo '-')"
  done
  mariadb -t -e "SELECT account, currency, cash FROM \`$1\`.paper_accounts" 2>/dev/null || true
}

case "$MODE" in
  --verify)
    load_into "${DB}_restore_check"
    summary "${DB}_restore_check"
    mariadb -e "DROP DATABASE IF EXISTS \`${DB}_restore_check\`;"
    echo "검증 완료(운영 DB 변경 없음)" ;;
  --apply)
    systemctl stop stock-screener-kr.timer stock-screener-us.timer stock-screener-web.service
    systemctl stop stock-screener-run@KR.service stock-screener-run@US.service 2>/dev/null || true
    SAFE="/var/backups/stock-screener/$DB-before-restore-$(date +%Y%m%d-%H%M%S).sql.gz"
    mariadb-dump --single-transaction --quick --routines --databases "$DB" | gzip -9 > "$SAFE" && chmod 0600 "$SAFE"
    echo "현재 DB 안전 백업: $SAFE"
    load_into "$DB"
    mariadb -e "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON \`$DB\`.* TO 'screener'@'127.0.0.1';"
    summary "$DB"
    systemctl start stock-screener-web.service
    echo "복원 적용 완료. 확인 후 타이머 재개: sudo systemctl start stock-screener-kr.timer stock-screener-us.timer"
    echo "되돌리기: sudo $0 --apply $SAFE" ;;
  *) echo "사용: $0 --verify|--apply <백업.sql.gz>" >&2; exit 1 ;;
esac
