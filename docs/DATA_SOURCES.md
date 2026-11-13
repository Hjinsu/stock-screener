# 데이터 공급원 검증 기록

검증일: 2026-11-13 (KST). 방법: 공식 문서 확인과 소량 요청(`.\run.ps1 check-sources`). 아래 "소량 요청" 열은 그날 실제로 받은 응답입니다. 한도는 공식 문서에서 확인한 값만 적었습니다. 확인하지 못한 값은 "미확인"으로 표시했습니다.

## 사용하는 공급원

| 용도 | 공급원 | 키 | 공식 한도·조건 | 소량 요청 결과 | 앱 내 호출 제한·캐시 |
|---|---|---|---|---|---|
| 한국 상장 목록·일봉·거래대금·시총·상장주식수 | KRX OpenAPI `sto/stk_bydd_trd`, `sto/ksq_bydd_trd` | `KRX_API_KEY` (서비스별 이용 승인 필요) | 키당 일 10,000회. 비상업적 이용만 허용. 화면에 "한국거래소 통계정보" 출처 표시 필요. 제3자 제공 금지 (이용약관 제6·8·10·11조) | 유가 936종목, 코스닥 1,834종목 (2026-11-12) | 0.35초 간격. 지난 일자는 DB(`krx_daily`)에 영구 저장하고 최근 3일만 재확인 |
| 한국 지수 교차확인 | KRX OpenAPI `idx/kospi_dd_trd`, `idx/kosdaq_dd_trd` | 같음 | 같음 | 코스피 5342.73 (11-12) | 6시간 캐시 |
| 한국 지수 일봉 이력 | Naver `fchart.stock.naver.com` (비공식) | 없음 | 공식 문서 없음 | KOSPI 600봉 | 0.25초 간격, 3시간 캐시. KRX 종가와 비교해 일치 여부 기록 |
| 한국 종목별 외국인·기관 순매매(수량)·외국인 보유율 | Naver `m.stock.naver.com/api/stock/{code}/trend` (비공식) | 없음 | 공식 문서 없음 | 005930 60일, `bizdate`로 과거 페이지 조회 가능 | 0.25초 간격. DB(`flows`)에 누적하고 부족한 부분만 요청 |
| 한국 업종 코드 | Naver `.../integration` | 없음 | 없음 | `industryCode` 제공 | DB 90일 보관 |
| 한국 재무 | OpenDART `fnlttMultiAcnt` (최대 100개사/호출), `corpCode.xml` | `DART_API_KEY` | 약관상 한도는 홈페이지에 게시한다고 되어 있으나, 이번 검증에서는 수치를 확인하지 못함. 1인 1키, 키 공유 금지 | 삼성전자 반기 주요계정 정상 | 0.2초 간격, 20시간 캐시 |
| 한국 공시 | OpenDART `list.json` | 같음 | 같음 | 삼성전자 90일 81건 | 12시간 캐시 |
| 미국 상장 목록(ETF·테스트·재무상태 플래그) | Nasdaq Trader Symbol Directory | 없음 | 매 영업일 갱신 | 13,285건 | 12시간 캐시 |
| 미국 CIK·발행주식수·재무·공시·SIC | SEC EDGAR (`company_tickers.json`, XBRL frames, submissions) | `SEC_USER_AGENT` (앱 이름과 연락처) | 초당 최대 10회. User-Agent 선언 필수 | 티커 10,405건, frames 발행주식수 2,644개 기업 | 0.15초 간격(초당 약 6.7회), 20시간 캐시 |
| 미국 일봉·지수(^GSPC, ^NDX) | Yahoo Finance chart API (비공식) | 없음 | 공식 문서 없음 | ^GSPC 23봉 | 0.4초 간격. DB(`us_bars`)에 저장하고 이후 1개월치만 증분 수집. 분할로 과거 가격이 바뀌면 전체 재수집 |
| 미국 예비 시세(전 종목 1회) | Tiingo IEX `/iex/` | `TIINGO_API_KEY` (선택) | 무료: 시간당 50회, 일 1,000회, 월 500개 종목, 내부·개인 용도 | 정상(37,137개 티커, 별도 probe) | 실행당 1회, 6시간 캐시. 일봉 예비 공급원은 실행당 최대 40회 |
| VIX, VIX3M | Cboe `cdn.cboe.com/.../VIX_History.csv` | 없음 | 문서 미확인 | VIX 9,314일, VIX3M 4,315일 | 6시간 캐시 |
| 시총 보조(ADR·다중 클래스) | FMP `stable/quote` | `FMP_API_KEY` (선택) | 요금제 페이지 HTTP 403으로 공식 수치 확인 못함 | AAPL 시총 정상. `company-screener`는 402(유료 전용) | 일 사용량 상한 `FMP_DAILY_BUDGET`(기본 150), DB에 사용량 기록 |
| 시장심리 뉴스(MSI 뉴스·커뮤니티 영역) | NAVER API HUB 뉴스 검색 `https://naverapihub.apigw.ntruss.com/search/v1/news` | `NAVER_CLIENT_ID`·`NAVER_CLIENT_SECRET`(네이버 클라우드 콘솔에서 발급) | 문서 표기가 서로 다름: 검색 API 일 25,000회 / HUB FAQ 월 775,000건·키당 50 RPS. 한시적 무료. 기존 developers.naver.com 키 사용 불가 | '코스피' 최신 5건 정상. 같은 키로 openapi.naver.com 호출 시 401 | 0.2초 간격, 30분 캐시, 429 즉시 중단. 기준일 결과는 DB에 고정 |
| 통합 평가 환율 | 한국은행 ECOS `StatisticSearch/731Y001`(원/미국달러 매매기준율) | `ECOS_API_KEY`(선택) | 문서 미확인 | 2026-11-02~11-12 9건 정상 | 6시간 캐시. 실패 시 Yahoo KRW=X로 대체하고 출처 표시 |
| 미국 ATS 주간 거래(간접 자료) | FINRA OTC Transparency API | 없음 | Rule 6610: 주간 종료 후 "4주 이전에는 공개하지 않음"(no earlier than four weeks) | AAPL 10/19 주간 자료가 11/09에 공개됨 | 0.3초 간격, 20시간 캐시. 점수에는 반영하지 않음 |

## 시도했지만 사용하지 않는 공급원

| 공급원 | 결과 | 조치 |
|---|---|---|
| CNN Fear & Greed | HTTP 418(봇 차단) | 차단을 우회하지 않음. MSI 교차검증에 쓰지 않음 |
| Nasdaq 스크리너 `api.nasdaq.com` | 브라우저 위장 없이는 응답 없음 | 쓰지 않음. 시총은 SEC 발행주식수 × 종가로 산출 |
| KRX `stk_isu_base_info`(종목 기본정보), `idx/drvprod_dd_trd`(VKOSPI 등 파생지수) | HTTP 401(해당 서비스 미신청) | 쓰지 않음. VKOSPI 대신 KOSPI 20일 실현변동성을 사용(대체 지표로 표시). KRX 포털에서 해당 서비스를 이용 신청하면 추가 연동 가능 |
| Cboe 일별 옵션 통계(풋/콜) | HTTP 403 | US-MSI 수급·파생 영역은 VIX/VIX3M 비율로 대체(대체 지표로 표시) |
| FMP `company-screener` | HTTP 402(유료) | 쓰지 않음 |
| Naver `investorDealTrendDay`(시장 전체 투자자 동향) | HTTP 410 | 시총 상위 K개 종목의 수급 합계로 대체(대체 지표로 표시) |

## 확보하지 못해 미평가로 남긴 항목

- 한국 연기금 등 기관 세부 수급(MAS 5점): 위 무료 공급원 중 이 값을 제공하는 곳이 없습니다.
- 미국 종목별 투자자 순매수(MAS 25점, OIS 8점): 무료 직접 자료가 없습니다. 13F는 수집하지 않으며 간접 자료라는 설명만 표시합니다. FINRA ATS는 간접 자료로 표시만 하고 점수에는 넣지 않습니다.
- 사업 경쟁력·경쟁우위(MAS 3점, OIS 10점), 가이던스·전망(OIS 8점): 정량 근거를 자동으로 판정하지 않습니다.
- 한국 영업현금흐름: 주요계정 API에 포함되지 않아 건전성 판정에서 빠집니다.
- 커뮤니티 심리: 수집하지 않습니다. 뉴스 심리는 NAVER API HUB로 산출하며(키 설정 시), 커뮤니티가 빠져 있어 '대체'로 표시하므로 MSI는 '잠정'입니다.
- 미국 ETF 자금 유출입, 신뢰할 수 있는 풋/콜 비율: 무료 공식 공급원을 찾지 못했습니다.

## 키 처리

- 모든 키는 환경 변수에서만 읽습니다. DART·FMP 키는 URL 파라미터로 전달되므로 로그와 캐시 파일 이름을 만들기 전에 마스킹합니다(`Log.redact`). KRX 키는 `AUTH_KEY` 헤더, Tiingo 키는 `Authorization` 헤더로 전달합니다.
- 캐시 파일에는 응답 본문만 저장하고 키는 저장하지 않습니다. DB에도 키를 저장하지 않습니다.
- `SEC_USER_AGENT`에 넣은 연락처는 SEC 정책에 따라 SEC에만 전송됩니다.
