# 환경 변수 예시 — 이 파일을 env.local.ps1 로 복사한 뒤 값을 채우세요.
#   Copy-Item env.example.ps1 env.local.ps1
# run.ps1 이 env.local.ps1 을 자동으로 읽습니다. env.local.ps1 은 .gitignore 에 포함되어 저장소에 올라가지 않습니다.
# (시스템 전체에 설정하려면: [Environment]::SetEnvironmentVariable('KRX_API_KEY','값','User') )

# ── 필수(한국) ─────────────────────────────────────────────
# KRX 정보데이터시스템 OpenAPI 인증키 (https://openapi.krx.co.kr)
#   회원가입 → 마이페이지 'API 인증키 신청' → 서비스별 '이용 신청' 후 승인 필요:
#   주식 > 유가증권 일별매매정보, 코스닥 일별매매정보 / 지수 > KOSPI 시리즈 일별시세정보, KOSDAQ 시리즈 일별시세정보
$env:KRX_API_KEY = ''
# OpenDART 인증키 (https://opendart.fss.or.kr → 인증키 신청/관리). 이용 한도는 OpenDART 홈페이지 게시 기준.
$env:DART_API_KEY = ''

# ── 필수(미국) ─────────────────────────────────────────────
# SEC EDGAR 는 키가 없지만 User-Agent 에 '앱 이름 + 연락처 이메일'을 요구합니다(SEC 공정 접근 정책).
#   예: 'MyScreener/1.0 your-email@example.com'  (본인 연락처로 바꾸세요)
$env:SEC_USER_AGENT = ''

# ── 선택 ──────────────────────────────────────────────────
# Tiingo (https://www.tiingo.com → Account → API Token). 전 종목 최근가 1회 호출로 미국 예비 시총 계산 속도 향상, 일봉 예비 공급원.
$env:TIINGO_API_KEY = ''
# Financial Modeling Prep (https://site.financialmodelingprep.com → 무료 키). ADR·다중 클래스 종목 시총 보조(일 사용 상한 FMP_DAILY_BUDGET).
$env:FMP_API_KEY = ''
# NAVER API HUB 검색(뉴스) — MSI '뉴스·커뮤니티' 영역(25%). 네이버 클라우드 플랫폼 콘솔 → NAVER API HUB → Application → 인증 정보.
#   기존 developers.naver.com 키는 API HUB 에서 쓸 수 없습니다. 없으면 뉴스 심리 미평가.
$env:NAVER_CLIENT_ID = ''
$env:NAVER_CLIENT_SECRET = ''
# 한국은행 ECOS — 통합 평가용 공식 원/달러 매매기준율. 없으면 Yahoo KRW=X(비공식)로 대체.
$env:ECOS_API_KEY = ''
# 권장: 이 파일 대신 scripts\set-*-key.ps1 로 입력(화면에 표시되지 않음). 토스증권 등 실계좌 키는 이 프로젝트에 필요 없습니다.
# FRED 키는 현재 버전에서 사용하지 않습니다(VIX는 Cboe 공식 CSV 사용).
# $env:FRED_API_KEY = ''

# ── DB(선택) ─────────────────────────────────────────────
# 비워 두면 SQLite(data\screener.db). 로컬 MariaDB 로 개발할 때만 지정(운영 서버는 /etc/stock-screener/screener.env).
# 다른 프로젝트의 DB_URL/DB_PASSWORD 와 섞이지 않도록 SCREENER_ 접두사 변수만 읽습니다.
# $env:SCREENER_DB_URL = 'jdbc:mariadb://127.0.0.1:3306/screener'
# $env:SCREENER_DB_USER = 'screener'
# $env:SCREENER_DB_PASSWORD = ''

# ── 동작 설정(선택) ────────────────────────────────────────
# $env:SCREENER_HOME = 'D:\screener-data'   # DB·캐시·로그 위치(기본: .\data)
# $env:SCREENER_PORT = '8765'               # 대시보드 포트(127.0.0.1 에만 바인딩)
# $env:KR_HISTORY_DAYS = '140'              # KRX 일봉 확보 영업일 수(첫 실행 시 일자당 2회 호출)
# $env:KR_OIS_TOP = '300'                   # 역발상 한국 유니버스(KOSPI+KOSDAQ 시총 상위)
# $env:US_OIS_TOP = '500'                   # 역발상 미국 유니버스(시총 상위)
# $env:KR_FLOW_AGG_TOP = '100'              # KR-MSI 수급 집계 대상 종목 수
# $env:FMP_DAILY_BUDGET = '150'             # FMP 일 사용 상한(무료 250회 중)
