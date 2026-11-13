#!/usr/bin/env bash
# MariaDB 일일 백업 + 복원 검증. root(unix_socket 인증)로 실행(systemd 타이머 stock-screener-backup).
# 1) mariadb-dump --single-transaction 으로 일관 백업(gzip, SHA-256)  2) 검증용 DB 에 복원  3) CHECKSUM TABLE 로 원본과 대조
# 4) 14일 초과 백업 삭제. 백업 중 쓰기가 있으면 대조가 어긋날 수 있어 실행 시각(03:30 KST)을 일괄 실행과 겹치지 않게 둔다.
set -euo pipefail
DB="${SCREENER_DB_NAME:-screener}"
BK=/var/backups/stock-screener
KEEP_DAYS="${KEEP_DAYS:-14}"
CHECK_DB="${DB}_restore_check"
install -d -m 0700 "$BK"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$BK/$DB-$TS.sql.gz"

mariadb-dump --single-transaction --quick --routines --triggers --default-character-set=utf8mb4 --databases "$DB" | gzip -9 > "$OUT"
sha256sum "$OUT" > "$OUT.sha256"
chmod 0600 "$OUT" "$OUT.sha256"

# 복원 검증: --databases 덤프의 CREATE DATABASE/USE 를 검증용 DB 로 바꿔 넣는다
mariadb -e "DROP DATABASE IF EXISTS \`$CHECK_DB\`; CREATE DATABASE \`$CHECK_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;"
gzip -dc "$OUT" | sed -e "s/^CREATE DATABASE .*\`$DB\`.*/-- restore-check/" -e "s/^USE \`$DB\`;/USE \`$CHECK_DB\`;/" | mariadb
TABLES="$(mariadb -N -e "SELECT GROUP_CONCAT(CONCAT('\`$DB\`.\`', table_name, '\`, \`$CHECK_DB\`.\`', table_name, '\`') ORDER BY table_name) FROM information_schema.tables WHERE table_schema='$DB'")"
BAD=0
while IFS=$'\t' read -r name val; do
  t="${name#*.}"; d="${name%%.*}"
  if [ "$d" = "$DB" ]; then declare "SRC_$t=$val"; else ref="SRC_$t"; [ "${!ref:-}" = "$val" ] || { echo "불일치: $t (${!ref:-?} vs $val)"; BAD=1; }; fi
done < <(mariadb -N -e "CHECKSUM TABLE $TABLES EXTENDED")
mariadb -e "DROP DATABASE IF EXISTS \`$CHECK_DB\`;"

find "$BK" -name "$DB-*.sql.gz*" -mtime +"$KEEP_DAYS" -delete
if [ "$BAD" = 0 ]; then
  echo "백업·복원 검증 통과: $OUT ($(du -h "$OUT" | cut -f1))"
else
  echo "백업은 저장했으나 복원 대조 불일치 — 백업 중 쓰기 발생 가능. 수동 확인 필요: $OUT" >&2
  exit 2
fi
