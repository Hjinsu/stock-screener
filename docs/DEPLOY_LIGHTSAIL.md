# AWS Lightsail 운영 가이드 (MariaDB, 모의매매 전용)

이 문서는 Lightsail Linux 인스턴스 한 대에 Java 서비스와 MariaDB를 함께 설치해 운영하는 절차입니다. 범위는 분석과 모의매매뿐이며, AWS 리소스 생성과 배포는 사용자가 지정한 서버에서 직접 진행합니다.

## 1. 구성과 경로

| 구분 | Windows 개발 PC | Lightsail Linux 운영 |
|---|---|---|
| DB | SQLite `data\screener.db` (기본) | MariaDB 10.11 `screener` DB (127.0.0.1:3306) |
| DB 설정 | 없음. 로컬 MariaDB를 쓸 때만 `SCREENER_DB_URL` 지정 | `/etc/stock-screener/screener.env`의 `SCREENER_DB_*` |
| API 키 | `scripts\set-*-key.ps1`(사용자 환경 변수) 또는 `env.local.ps1` | `/etc/stock-screener/screener.env` (root:screener, 0640) |
| 실행 | `.\run.ps1 …` | `sudo stock-screener …`(서비스 계정·환경 파일 적용 래퍼) |
| 자동 실행 | 작업 스케줄러 또는 대시보드의 앱 내 스케줄러 | systemd 타이머. 앱 내 스케줄러는 `SCREENER_INAPP_SCHEDULER=off`로 끔 |
| 앱 | `screener.jar` | `/opt/stock-screener/current` → `releases/<시각>-<커밋>` |
| 데이터·캐시·로그 | `data\` | `/var/lib/stock-screener` (서비스 계정 `screener`) |
| 백업 | — | `/var/backups/stock-screener` (root, 0700) |

> 이 PC에는 다른 프로젝트용 `DB_URL`·`DB_PASSWORD`·`TEST_DB_URL`이 설정되어 있습니다. 이 프로그램은 그 변수를 읽지 않고 **`SCREENER_DB_URL`·`SCREENER_DB_USER`·`SCREENER_DB_PASSWORD`만** 읽습니다.

## 2. Git으로 소스 옮기기

이 폴더는 아직 Git 저장소가 아닙니다. PC에서 한 번만 다음을 실행합니다.

```powershell
git init
git add .
git commit -m "stock screener"
git remote add origin <비공개 저장소 URL>
git push -u origin main
```

- `.gitignore`가 키 파일(`env.local.ps1`)과 DB·캐시·로그(`data/`), 빌드 산출물, `lib/`을 제외합니다. 커밋 전에 `git status`로 키·DB가 포함되지 않았는지 확인하세요.
- `.gitattributes`가 `.sh`·systemd 파일을 LF로 고정하므로, Windows에서 커밋해도 서버에서 실행됩니다.
- 저장소는 **비공개**로 두세요. 원본 명세와 운영 기록 문서가 함께 올라갑니다.

## 3. Lightsail 인스턴스 준비

1. **인스턴스**: Linux/Unix › OS Only › **Ubuntu 24.04 LTS**를 고릅니다(MariaDB 10.11 기본 패키지, 로컬 검증 버전과 같음). 메모리는 Java와 MariaDB를 함께 돌리므로 2GB 이상을 권장합니다.
2. **고정 IP**를 연결합니다.
3. **네트워킹 › IPv4 방화벽**: SSH(TCP 22)만 남기고 HTTP(80) 등 나머지 규칙은 삭제합니다. 가능하면 SSH 출발지를 본인 IP로 제한합니다.
4. **네트워킹 › IPv6 방화벽**: IPv4와 별도로 관리됩니다. 여기도 SSH만 남기거나 IPv6를 끕니다.
5. **3306(MariaDB)과 8765(대시보드)는 IPv4·IPv6 어디에도 추가하지 않습니다.**

## 4. 설치

```bash
sudo mkdir -p /opt/stock-screener && sudo chown ubuntu: /opt/stock-screener
git clone <비공개 저장소 URL> /opt/stock-screener/src
cd /opt/stock-screener/src
sudo bash deploy/linux/install.sh
```

`install.sh`가 하는 일은 다음과 같습니다.

- OpenJDK 17·MariaDB·Git을 설치하고 서비스 계정 `screener`(로그인 불가)를 만듭니다.
- MariaDB를 `bind-address=127.0.0.1`, `skip-name-resolve`, `local-infile=0`으로 설정합니다.
- `screener` DB와 `screener`@`127.0.0.1` 계정을 만듭니다. 권한은 이 DB에 대한 SELECT·INSERT·UPDATE·DELETE·CREATE·ALTER·INDEX·REFERENCES만 줍니다.
- DB 비밀번호를 무작위로 생성해 `/etc/stock-screener/screener.env`에만 기록합니다.
- systemd 단위와 래퍼를 설치하고, 첫 빌드(테스트 포함)와 배포를 실행합니다.
- 대시보드 서비스와 백업 타이머만 켭니다. **실행 타이머는 켜지 않습니다**(6장 참조).

### 키 입력

```bash
sudoedit /etc/stock-screener/screener.env      # KRX_API_KEY, DART_API_KEY, SEC_USER_AGENT, (선택) NAVER·ECOS·TIINGO·FMP
sudo systemctl restart stock-screener-web
sudo stock-screener check-sources               # 키 값은 출력되지 않음
sudo stock-screener db-status
```

키를 명령줄에 쓰지 마세요(셸 기록·프로세스 목록에 남습니다). `stock-screener` 래퍼는 환경 파일을 `systemd-run`으로 적용하므로 키가 인자에 나타나지 않습니다.

## 5. 대시보드 접속(SSH 터널)

PC에서 터널을 연 뒤 브라우저로 접속합니다.

```powershell
ssh -i <키.pem> -N -L 8765:127.0.0.1:8765 ubuntu@<고정 IP>
# 브라우저: http://127.0.0.1:8765
```

대시보드에는 로그인이 없고 서버의 127.0.0.1에만 열려 있습니다. 포트를 공개하거나 리버스 프록시로 노출하지 마세요.

## 6. 시작 방식 선택

### A. PC 이력 없이 새로 시작
```bash
sudo stock-screener db-status                 # 스키마 v4, 테이블 0건 확인
sudo systemctl enable --now stock-screener-kr.timer stock-screener-us.timer
```

### B. PC의 SQLite 이력을 이관(전환 절차)
**PC가 원본입니다. 서버 이관이 PASS하기 전에는 PC 실행을 퇴역 처리하지 않습니다.**

1. **PC에서 실행 정지**: 동시에 두 곳에서 같은 모의 주문·보고가 처리되지 않게 합니다.
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\register-task.ps1 -Remove   # 작업 스케줄러 해제
   # 실행 중인 .\run.ps1 serve 창 종료(앱 내 스케줄러 포함). 진행 중인 run 이 있으면 끝날 때까지 대기
   ```
   카카오 보고는 아직 구현되지 않았으므로 정지할 것이 없습니다. 구현 후에는 이 단계에서 PC의 보고 사용을 끄고 연결을 해제해야 합니다.
2. **PC에서 일관 사본 생성**: 쓰기 로그(-wal)까지 포함됩니다. 원본은 바뀌지 않습니다.
   ```powershell
   .\run.ps1 sqlite-snapshot --out .\screener-snapshot.db    # SHA-256 출력 — 기록해 둘 것
   ```
3. **서버로 복사**:
   ```powershell
   scp -i <키.pem> .\screener-snapshot.db ubuntu@<고정 IP>:/tmp/
   ```
   ```bash
   sha256sum /tmp/screener-snapshot.db         # 2단계 값과 같은지 확인
   sudo install -o screener -g screener -m 0600 /tmp/screener-snapshot.db /var/lib/stock-screener/import-screener.db && rm /tmp/screener-snapshot.db
   ```
4. **서버에서 이관과 검증**: 대상 DB가 비어 있어야 실행됩니다.
   ```bash
   sudo stock-screener migrate-sqlite --from /var/lib/stock-screener/import-screener.db
   ```
   `/var/lib/stock-screener/migration/<시각>/report.md`에서 다음 항목을 확인합니다.
   - 백업 SHA-256
   - 테이블별 건수(원본/대상), 테이블별 내용 체크섬(실수는 비트 단위로 비교해 금액 정밀도와 기준 시각 문자열 확인)
   - 통화별 현금 잔액, 계좌별 실현손익·최신 평가액·입출금
   - 대표 신호→주문→체결 관계(원본 ID 보존)
   - 참조 무결성(고아 행 0건)

   결과가 **PASS**일 때만 대상에 `instance_role=active`, `schedule_enabled=false`(앱 내 스케줄러 끔)가 기록됩니다.
5. **서버 실행 시작**:
   ```bash
   sudo systemctl enable --now stock-screener-kr.timer stock-screener-us.timer
   systemctl list-timers 'stock-screener*'
   ```
6. **PC 퇴역 처리**: 이후 PC의 `run`·예약 실행이 종료 코드 3으로 거부됩니다.
   ```powershell
   .\run.ps1 retire-local
   ```

### 이관이 실패했을 때
- PC는 아직 퇴역 처리 전이므로 그대로 원본입니다. 필요하면 PC 작업 스케줄러를 다시 등록해 운영을 재개합니다.
- 서버의 부분 이관을 비운 뒤 원인을 고쳐 4단계부터 다시 실행합니다.
  ```bash
  sudo systemctl stop stock-screener-kr.timer stock-screener-us.timer
  sudo mariadb -e "DROP DATABASE screener; CREATE DATABASE screener CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;"
  sudo systemctl restart stock-screener-web    # 빈 스키마 재생성
  ```
- 보고서의 `problems`에 불일치 항목이 표시됩니다.

### 전환 후 되돌리기(서버 → PC)
1. 서버 타이머를 정지합니다: `sudo systemctl disable --now stock-screener-kr.timer stock-screener-us.timer`
2. 서버 운영 중 새로 쌓인 이력은 PC로 옮겨지지 않습니다. 필요하면 서버 백업을 보관합니다.
3. PC에서 `.\run.ps1 retire-local --undo`를 실행하고 작업 스케줄러를 다시 등록합니다.

## 7. 운영 명령

| 작업 | 명령 |
|---|---|
| 배포·업데이트 | `cd /opt/stock-screener/src && sudo bash deploy/linux/deploy.sh` (git pull → 빌드·테스트 → 새 릴리스 → 스키마 마이그레이션 → 대시보드 재시작. 실패 시 기존 버전 유지) |
| 되돌리기 | `sudo bash deploy/linux/rollback.sh` (직전 릴리스. 스키마는 추가형이라 이전 앱과 호환) |
| 재시작 | `sudo systemctl restart stock-screener-web` |
| 수동 실행 | `sudo systemctl start stock-screener-run@KR.service` / `@US` |
| 상태·로그 | `systemctl list-timers 'stock-screener*'`, `journalctl -u stock-screener-run@KR -n 100`, `journalctl -u stock-screener-web -f` |
| DB 상태 | `sudo stock-screener db-status` |
| CSV | `sudo stock-screener export-csv --out /var/lib/stock-screener/export/$(date +%F)` |
| 백업(매일 03:30 KST 자동) | `sudo stock-screener-backup` (덤프 → 검증용 DB에 복원 → CHECKSUM TABLE 대조 → 14일 보관) |
| 복원 검증 | `sudo stock-screener-restore --verify /var/backups/stock-screener/<파일>.sql.gz` |
| 복원 적용 | `sudo stock-screener-restore --apply <파일>.sql.gz` (서비스·타이머 정지 → 현재 DB 안전 백업 → 교체 → 대시보드 재시작. 타이머는 확인 후 수동 재개) |

### 자동 실행 일정
- **KR**: 월~금 18:30 KST. KRX 자료가 아직 없으면 직전 영업일을 기준일로 처리하고, 같은 기준일 재실행은 중복 저장되지 않습니다.
- **US**: 화~토 07:00 KST. Yahoo가 당일 종목 일봉을 미확정으로 주면 확정된 직전 세션을 처리합니다. 실측상 한 세션 늦게 처리되는 경우가 있습니다.
- **휴장일**: 앱이 완료 거래일을 자료로 확인하므로 같은 기준일을 다시 실행할 뿐입니다(중복 없음).
- **재부팅 복구**: `Persistent=true`라서 꺼져 있던 동안 놓친 실행을 부팅 후 1회 수행합니다. 중단된 RUNNING 기록은 다음 실행에서 ABORTED로 정리되고, 파일 잠금은 프로세스 종료와 함께 풀립니다.

## 8. 서버 검증 체크리스트(사용자 실행)

설치 후 다음을 직접 확인하세요. 개발 PC에는 Linux 환경이 없어 아래 항목은 검증하지 못했습니다.

1. `sudo stock-screener db-status`: 스키마 4/4, 역할 표시
2. `sudo ss -ltnp | grep -E '3306|8765'`: 둘 다 `127.0.0.1`에만 열려 있어야 합니다(`0.0.0.0`·`[::]`이면 안 됨)
3. 외부 PC에서 `Test-NetConnection <고정 IP> -Port 3306`과 `-Port 8765`가 모두 실패해야 합니다.
4. `sudo systemctl start stock-screener-run@KR.service` 실행 후 `journalctl`에서 "상태: OK|PARTIAL" 확인
5. `sudo stock-screener-backup` 실행 후 "백업·복원 검증 통과" 확인. 이어서 `--verify`로 복원 검증
6. `sudo reboot` 후 확인할 것:
   - `systemctl is-active stock-screener-web mariadb`
   - `systemctl list-timers 'stock-screener*'`(다음 실행 시각 표시)
   - SSH 터널로 대시보드 접속
7. 첫 거래일 저녁·아침에 타이머가 실제로 실행되었는지 확인합니다. 대시보드의 실행 내역과 `journalctl`을 봅니다.

## 9. 보안 요약

- **DB**: 루프백 전용, 전용 계정(해당 DB 권한만), 원격 root 없음(unix_socket). 방화벽에 3306을 공개하지 않습니다.
- **키·비밀번호**: `/etc/stock-screener/screener.env`(0640, root:screener)에만 둡니다. 저장소·로그·DB·CSV에는 기록하지 않습니다. 로그에서는 마스킹됩니다.
- **서비스 계정**: 로그인할 수 없고, systemd 보호 옵션(ProtectSystem=strict, NoNewPrivileges 등)을 적용했습니다. 쓰기 경로는 `/var/lib/stock-screener`로 제한합니다.
- **대시보드**: 127.0.0.1에만 열리며 SSH 터널로만 접근합니다.
- **백업 파일**: root 0600. 서버 밖으로 옮길 때는 암호화하세요.
- **카카오 토큰**: 9항 구현 시 같은 환경 파일이나 `/var/lib/stock-screener` 아래 0600 파일에 보관하고, 서버에서 따로 설정합니다(PC 토큰을 복사하지 않음).
