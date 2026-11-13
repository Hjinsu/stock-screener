#!/usr/bin/env bash
# Lightsail Ubuntu 24.04 최초 설치(관리자 1회 실행). Java 17·MariaDB·Git 설치, 서비스 계정·디렉터리,
# MariaDB 루프백 전용 설정과 전용 DB 계정, 서버 환경 파일, systemd 서비스·타이머를 구성한다.
# 사용: sudo deploy/linux/install.sh   (저장소를 /opt/stock-screener/src 에 clone 한 뒤 그 안에서 실행)
# API 키·DB 비밀번호는 이 스크립트가 저장소에 쓰지 않는다. DB 비밀번호는 무작위 생성해 /etc/stock-screener/screener.env(0640)에만 기록한다.
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "sudo 로 실행하세요" >&2; exit 1; }
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
APP=/opt/stock-screener
DATA=/var/lib/stock-screener
ETC=/etc/stock-screener
BK=/var/backups/stock-screener

echo "== 1) 패키지"
apt-get update -y
DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jdk-headless mariadb-server mariadb-client git curl ca-certificates

echo "== 2) 서비스 계정·디렉터리"
id screener >/dev/null 2>&1 || useradd --system --home-dir "$DATA" --shell /usr/sbin/nologin screener
install -d -o root -g root -m 0755 "$APP" "$APP/releases"
install -d -o screener -g screener -m 0750 "$DATA"
install -d -o root -g screener -m 0750 "$ETC"
install -d -o root -g root -m 0700 "$BK"

echo "== 3) MariaDB: 루프백 전용, 원격 접속·LOCAL INFILE 차단"
cat > /etc/mysql/mariadb.conf.d/60-stock-screener.cnf <<'CNF'
[mysqld]
bind-address = 127.0.0.1
skip-name-resolve
local-infile = 0
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci
CNF
systemctl enable --now mariadb
systemctl restart mariadb

echo "== 4) 전용 DB·계정(screener@127.0.0.1, screener DB 에만 권한)"
if [ ! -f "$ETC/screener.env" ]; then
  DBPW="$(openssl rand -base64 30 | tr -d '/+=' | cut -c1-32)"
  mariadb -u root <<SQL
CREATE DATABASE IF NOT EXISTS screener CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER IF NOT EXISTS 'screener'@'127.0.0.1' IDENTIFIED BY '${DBPW}';
ALTER USER 'screener'@'127.0.0.1' IDENTIFIED BY '${DBPW}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON screener.* TO 'screener'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
  umask 027
  sed "s|^SCREENER_DB_PASSWORD=.*|SCREENER_DB_PASSWORD=${DBPW}|" "$SRC/deploy/linux/screener.env.example" > "$ETC/screener.env"
  chown root:screener "$ETC/screener.env"
  chmod 0640 "$ETC/screener.env"
  unset DBPW
  echo "   $ETC/screener.env 생성(DB 비밀번호 기록). API 키는 'sudoedit $ETC/screener.env' 로 직접 입력하세요."
else
  echo "   $ETC/screener.env 가 이미 있어 DB 계정·환경 파일을 바꾸지 않음"
fi

echo "== 5) systemd 서비스·타이머"
install -m 0644 "$SRC"/deploy/linux/systemd/*.service "$SRC"/deploy/linux/systemd/*.timer /etc/systemd/system/
install -m 0755 "$SRC/deploy/linux/backup.sh" /usr/local/sbin/stock-screener-backup
install -m 0755 "$SRC/deploy/linux/restore.sh" /usr/local/sbin/stock-screener-restore
install -m 0755 "$SRC/deploy/linux/stock-screener.sh" /usr/local/bin/stock-screener
systemctl daemon-reload

echo "== 6) 첫 빌드·배포"
bash "$SRC/deploy/linux/deploy.sh"

# 실행 타이머(KR/US)는 켜지 않는다: PC 이력 이관(migrate-sqlite)은 빈 DB 에서만 진행되므로 이관·전환을 마친 뒤 켠다.
systemctl enable --now stock-screener-web.service stock-screener-backup.timer
echo
echo "설치 완료. 다음 단계:"
echo "  1) sudoedit $ETC/screener.env  — KRX_API_KEY, DART_API_KEY, SEC_USER_AGENT 등 입력 후 'sudo systemctl restart stock-screener-web'"
echo "  2) sudo stock-screener check-sources   /   sudo stock-screener db-status"
echo "  3) Lightsail 네트워킹: IPv4·IPv6 방화벽에 SSH(22)만 허용 — 3306·8765 는 열지 않음"
echo "  4) PC에서 대시보드: ssh -N -L 8765:127.0.0.1:8765 ubuntu@<서버 IP>  →  http://127.0.0.1:8765"
echo "  5) PC 이력이 있으면 docs/DEPLOY_LIGHTSAIL.md '전환 절차'로 이관·검증 후, 없으면 바로:"
echo "     sudo systemctl enable --now stock-screener-kr.timer stock-screener-us.timer"
