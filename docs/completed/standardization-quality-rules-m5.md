# M-5 3차. 테이블명 규칙 · 표준 용어 중복·유사어 · 컬럼명 개선 후보 — 설계 결정과 구현 기록

> [데이터 모델링 로드맵](../reference/data-modeling-roadmap.md) M-5 의 잔여 중 세 가지 진단의 기록이다. 1차(사용 현황) : [standardization-usage-m5.md](standardization-usage-m5.md), 2차(컬럼 품질 진단) : [standardization-quality-m5.md](standardization-quality-m5.md).
> 작성일 2026-11-17. **DB 변경 없음** (Flyway V0~V6 그대로, 새 마이그레이션·V7 없음). 모두 조회 전용 — 테이블·컬럼·표준 단어·용어·도메인을 바꾸지 않고, 적용 API 도 없다. AI Provider·MCP 와 무관하게 동작한다.
> 작업 지시서의 "M-5 2차 구현"이 이 기록이다 (저장소 기록의 2차는 컬럼 품질 진단이라 여기서는 3차로 부른다).
> **상태** : 이 기록의 범위는 구현·검증 완료. **M-5 전체는 부분 완료**로 남는다 — 남은 일은 [pending-work.md](../pending-work.md) [M-5] 와 9장.
> **4차 (2026-11-18)** : 진단·입력 검증 규칙 비교와 판정 함수 공통화 — 10장. 등록·수정의 허용·거절, 오류 코드, Finding·fingerprint 는 그대로다.

## 0. 완료 범위 요약

| 구분 | 완료 | 이 기록에서 하지 않은 것 |
|---|---|---|
| 공통 규칙 구조 | `StandardizationRule`(규칙·대상·심각도), `Finding`, `FindingFingerprint`, 기존 3종 컬럼 진단 연결 (2장) | 결과 저장·상태 관리 (AI-6) |
| 테이블명 진단 | 접두어 누락, 승인된 표준 단어 약어(미등록·미승인·빈 약어 구분), VIEW 제외 (3장) | 테이블 코멘트 비교(D1), DBMS 식별자·예약어(D3·D4) |
| 표준 용어 중복·유사어 | 약어+도메인 중복, 용어명 중복, 같은 약어 다른 도메인, 같은 단어 구성, 이음동의어, 금칙어 (4장) | 문자열 유사도(D7), 자동 병합·삭제·이름 변경 |
| 컬럼명 개선 후보 | 7종 출처·고정 점수, 도메인 적합성, 이름 충돌, 최대 5건 (5장) | 자동 적용·일괄 수정·용어 자동 등록 |
| 화면 | 모델 품질 진단 탭, 표준 용어 진단 탭, 개선 후보 대화상자 (7장) | 새 메뉴 |
| 검증 | H2·PostgreSQL 전체, 성능 측정, 브라우저 76/76 (8장) | DBA·MANAGER 화면, 세션 만료 알림 |

## 1. 확정 정책 (사용자 결정 D1~D10, 2026-11-17)

| ID | 항목 | 확정 내용 → 구현 |
|---|---|---|
| D1 | 테이블명 판정 | 프로젝트 접두어 + 승인된 표준 단어 약어 (`TableDesignService.generateTableName` 의 역방향). 테이블 코멘트와 생성 이름의 비교는 하지 않는다 |
| D2 | 대시보드 준수율 | 기존 값 유지 — `/summary` 의 의미·필드 그대로, 대시보드는 신규 진단을 부르지 않는다 |
| D3 | DBMS 식별자(문자·길이) | M-7 에서 통합 구현 (이번 범위 아님) |
| D4 | 예약어 | 이번 범위 아님 |
| D5 | 등록·수정 중복 검증 | 기존 동작 유지, 진단만 추가 |
| D6 | 같은 약어·다른 도메인 | 주의(WARNING) |
| D7 | 문자열 유사도 | 보류 — 단어 구성·이음동의어만 |
| D8 | 심각도 | 오류·주의·참고 3단계 고정 |
| D9 | VIEW 이름 | 진단 제외 (TB_TBL 만 읽는다) |
| D10 | 용어 진단 UI | 기존 품질 진단 화면의 탭 |

## 2. 공통 규칙 구조 (`lexorma-data` `dictionary/quality/`)

| 구성 | 내용 |
|---|---|
| `StandardizationRule` | 규칙 코드(enum 이름 — 화면·API·fingerprint 가 쓰므로 바꾸지 않는다), 대상 유형 `TargetType`(TABLE·COLUMN·TERM·WORD — WORD 는 AI-6 용, 지금 규칙 없음), 기본 심각도 `Severity`(ERROR·WARNING·INFO) |
| `Finding` | 규칙, 대상 ID(그룹은 오름차순), 현재 값, 권장 값, 근거(키 순서 고정 Map), fingerprint — 화면 문구가 들어가지 않는 판정 결과. **계산만 하고 저장하지 않는다** (AI-6 이 저장·신규/유지/해결 비교에 쓴다) |
| `FindingFingerprint` | `v1:` + SHA-256(`v1 ␟ P{프로젝트 ID} ␟ 규칙 ␟ 대상 유형 ␟ 정렬한 대상 ID ␟ 핵심 값…`). 메시지·화면 순서·현재 값을 넣지 않는다. 구성원 ID 는 정렬해서 넣는다. 구성을 바꾸면 접두어를 올린다 |
| 기존 3종 연결 | `IssueType.rule()` = 같은 이름의 규칙. 응답 값·판정·메시지는 그대로. `StandardizationQualityService.finding(projectId, issue)` 가 Finding 을 만든다 |
| `StandardNames` (4차) | 진단과 등록·생성·추천이 기준이 **완전히 같은** 이름 판정 함수 — 기대 컬럼명·컬럼명 일치, 용어·단어 이름 키, 약어·물리명 키, 단어 나누기. 판정만 하고 처리(거절·Finding·건너뛰기)는 호출하는 쪽 (10장) |

**규칙별 심각도 (구현 값을 2026-11-17 사용자가 그대로 확정, D8 의 3단계)** — 기준 : 등록 규칙을 어긴 것은 오류, 등록은 허용하지만 확인이 필요한 것은 주의, 후보는 참고. 값은 `StandardizationRule` 한 곳에만 있다.

| 심각도 | 규칙 |
|---|---|
| 오류 `ERROR` | 컬럼명 불일치 `COLUMN_NAME_MISMATCH`, 테이블 접두어 누락 `TABLE_PREFIX_MISSING`, 비표준 단어 약어 `TABLE_WORD_NOT_STANDARD`, 같은 약어 + 같은 도메인 중복 `TERM_ABBR_DUPLICATE`, 용어명 중복 `TERM_NAME_DUPLICATE` |
| 주의 `WARNING` | 표준 용어 미연결 `TERM_NOT_LINKED`, 도메인 정의 불일치 `DOMAIN_DEFINITION_MISMATCH`, 같은 약어 + 다른 도메인 `TERM_ABBR_SHARED`(D6), 금칙어 `TERM_RESTRICTED_WORD` |
| 참고 `INFO` | 같은 표준 단어 구성 `TERM_SAME_WORDS`, 이음동의어 `TERM_SYNONYM` |

용어명 중복은 **이름**이 겹친다는 진단이며 뜻까지 같다는 판정이 아니다. 같은 단어 구성·이음동의어는 유사어 **후보**일 뿐 자동 병합 대상이 아니다.

**fingerprint 핵심 값**

| 규칙 | 핵심 값 |
|---|---|
| TERM_NOT_LINKED | 없음 (컬럼 ID 만) |
| COLUMN_NAME_MISMATCH | 기대 컬럼명(대문자) — 다른 틀린 이름으로 바꿔도 같은 문제 |
| DOMAIN_DEFINITION_MISMATCH | 다른 속성(정렬) |
| TABLE_PREFIX_MISSING | 접두어 |
| TABLE_WORD_NOT_STANDARD | 위반 약어:판정 (정렬) — 승인된 다른 약어를 바꿔도 같은 문제 |
| 용어 그룹 | 묶은 키 (약어+도메인 ID, 정규화 용어명, 단어 ID 구성, `TERM`/`WORD` + 이름·구성) |
| TERM_RESTRICTED_WORD | 금칙어>표준 단어 ID (정렬) |

## 3. 테이블명 규칙 (`TableNameRules`)

| 규칙 | 기준 |
|---|---|
| `TABLE_PREFIX_MISSING` (오류) | 프로젝트 접두어가 있으면 테이블명이 그 접두어로 시작해야 한다 (대소문자 무시). 접두어는 저장할 때 끝에 `_` 를 붙여 두므로(`Project.normalizeTablePrefix`) 경계는 `_` 다 — `TB_` 에 `TBL_X`·`TBX` 는 위반. `_` 없이 저장된 예전 값도 같은 경계로 맞춘다. 접두어가 없는 프로젝트는 보지 않는다 |
| `TABLE_WORD_NOT_STANDARD` (오류) | 접두어를 뗀 나머지(접두어가 없으면 이름 전체)를 `_` 로 나눈 약어마다 `APPROVED`(승인된 단어가 하나라도 있음) / `NOT_APPROVED`(미승인 단어만) / `NOT_FOUND` / `EMPTY`(`__`, 앞뒤 `_`). 승인된 약어가 아닌 것이 있으면 위반이고 약어별 판정(`tokens`)과 근거를 낸다. 같은 약어의 단어가 여럿이면 생성 규칙("승인된 단어 우선")과 같이 하나라도 승인되어 있으면 승인 |

대상은 프로젝트의 모든 TB_TBL — 화면 등록 외에 엑셀 업로드, 원본 가져오기, 모델 교환 Import 로 만든 테이블도 포함한다(이 경로들은 등록 규칙을 거치지 않는다). VIEW 는 TB_VIEW 라 들어오지 않는다. 원본 DB 에 접속하지 않는다.

조회 : 테이블 프로젝션 1번 + 프로젝트 단어 약어·승인 여부 1번 (`DictionaryUsageRepository.findQualityTables`·`findWordAbbreviations`). 테이블마다 조회하지 않는다.

## 4. 표준 용어 중복·유사어 (`TermDuplicateRules`)

모든 규칙이 정규화한 키로 묶는 해시 그룹이다 — 용어 쌍을 모두 비교하지 않는다(O(N), 이음동의어 수에 비례). 문자열 편집거리·n-gram·임베딩·AI 는 쓰지 않는다(D7). 이름 정규화는 공백·`_` 제거 + 소문자(엑셀 업로드의 용어명 비교 `resolveUploadedTermName` 과 같은 정규화에 대소문자를 더함), 빈 이음동의어("" — 등록 때 `split(",")` 로 생길 수 있다)는 무시한다.

| 규칙 (심각도) | 기준 |
|---|---|
| `TERM_ABBR_DUPLICATE` (오류) | 영문약어(trim·대문자) + 도메인 ID 가 같은 용어가 둘 이상. 등록의 중복 기준(약어 + 도메인)을 대소문자 무시로 본다 — 수정 경로는 중복을 검사하지 않고 DB UNIQUE 도 없어 생길 수 있다 |
| `TERM_NAME_DUPLICATE` (오류) | 정규화 용어명이 같은 용어가 둘 이상. 도메인이 같은지(`sameDomain`)를 근거·문구에 남긴다 — 같은 이름이어도 뜻이 다를 수 있어 확정하지 않는다 |
| `TERM_ABBR_SHARED` (주의, D6) | 약어는 같고 도메인이 둘 이상 (그 약어의 용어 전체). 컬럼명 생성·엑셀 업로드(약어로 용어를 찾는다)에서 용어가 모호하다 |
| `TERM_SAME_WORDS` (참고) | `TB_STD_TRM_WORD` 의 단어 구성(순서 무시, 반복은 센다)이 같다. 순서가 다르면 `orderDiffers`. 구성 순서와 정규화 이름이 모두 같은 그룹은 용어명 중복과 같은 내용이라 뺀다 |
| `TERM_SYNONYM` (참고) | ① 용어명 = 다른 용어의 이음동의어(두 용어씩, 이음동의어를 가진 용어에 근거) ② 단어 W 의 이음동의어와 이름이 같은 다른 단어 V 를 W 로 바꾸면(여럿이면 ID 가 작은 W, 한 단계만) 단어 구성(순서 포함)이 같아지는 용어 — 바꾸기 전에도 같은 구성이면 이음동의어 때문이 아니라 뺀다 |
| `TERM_RESTRICTED_WORD` (주의) | 용어를 이루는 단어(단어 매핑, 매핑이 없으면 용어명을 `_`·공백으로 나눈 조각)의 이름이 다른 표준 단어의 금칙어와 **정확히** 같다. 부분 문자열은 보지 않는다(보수적). 자기 이름을 금칙어로 둔 단어(데이터 오류)는 보지 않는다. 근거 : `금칙어 → 표준 단어`, 경계 종류(`TERM_WORD`/`TERM_NAME`) |

결과는 그룹 단위(id = fingerprint, 규칙·심각도·묶은 값·구성 용어·문구·근거). 구성 용어마다 승인 여부·도메인·사용 컬럼 수·근거. 사용 컬럼 수는 **목록의 한 페이지 분량 용어만** `countColumnsByTerm` 1번으로 센다. 조회 : 프로젝트 단위 6번(용어, 용어-단어, 단어, 용어 이음동의어, 단어 이음동의어, 금칙어 — 이음동의어·금칙어는 `@ElementCollection` 을 QueryDSL `join` 으로 읽는다). 자동 병합·삭제·이름 변경 없음.

## 5. 비표준 컬럼명 개선 후보 (`ColumnNameSuggestions`)

대상은 표준 용어 미연결·컬럼명 불일치 컬럼뿐이다(그 밖에는 계산하지 않고 사유만). 컬럼 1개를 열 때만 계산한다 — 목록 전체에 미리 계산하지 않는다. 점수는 `SimilarColumnService` 와 같은 방식(출처별 고정 점수, 여러 출처면 가장 높은 것 + 출처 목록), 컬럼명 규칙은 `PhysicalNameRecommendationService.recommendColumnName`·`sameName`, 도메인 비교는 `StandardizationQualityService.compareDomain` 을 그대로 쓴다.

| 출처 | 점수 | 기준 → 할 일 |
|---|---|---|
| `LINKED_TERM` | 100 | 컬럼명 불일치 : 연결된 용어의 약어로 → 컬럼명 변경 |
| `ABBR_EXACT` | 95 | 용어 약어 = 현재 컬럼명 → 용어만 연결 |
| `NAME_EXACT` | 85 | 용어명 = 컬럼 논리명 (정규화) → 연결(+ 이름 변경) |
| `SYNONYM` | 75 | 용어 이음동의어 = 논리명 |
| `WORD_COMPOSE` | 70 | 컬럼명을 `_` 로 나눈 약어가 모두 표준 단어 약어(약어마다 승인된 단어 우선)이고 그 단어 구성(순서 포함)의 용어가 있다 |
| `SAME_DESC_COLUMN` | 60 | 논리명이 같은 다른 컬럼이 쓰는 용어 (같은 점수 안에서는 쓰는 컬럼 수 순) |
| `UNREGISTERED` | 30 | 약어가 모두 표준 단어인데 그 구성의 용어도, 그 약어의 용어도 없다 → 새 용어 등록 검토 (용어 ID 없음, 제안 용어명·단어 근거) |

감점 : 미승인 용어 −10 (미등록 조합은 미승인 단어가 있으면 −10), 도메인 타입 불일치 −10 · 그 밖의 도메인 속성 불일치 −5, 같은 테이블의 다른 컬럼이 그 이름을 씀(대소문자 무시, 이름을 바꾸는 후보만) −20, 같은 테이블의 다른 컬럼도 그 이름을 기대함(그 컬럼에 연결된 용어 약어가 같고 아직 그 이름이 아님) −10. 순서 : 점수 → 출처 → 같은 논리명 컬럼 수 → 승인된 용어 → 용어명 → 용어 ID (같은 데이터면 같은 순서). 최대 5건, 넘으면 `truncated`.

조회 : 컬럼 1번(다른 프로젝트 컬럼·없는 컬럼은 `COLUMN_IS_NOT_DEFINED`) → 대상일 때만 프로젝트 1번 + 용어(도메인 포함)·용어 이음동의어·단어·용어-단어·같은 논리명 컬럼 수(`replace(replace(lower(COL_EXPLN),' ',''),'_','')` 비교, group by 용어 — 탭·줄바꿈은 SQL 쪽에서 빼지 않는다)·같은 테이블 컬럼 6번. 적용 API(컬럼명 변경, 용어 연결, 용어 등록, 원본 변경, 일괄 수정)는 만들지 않았다.

## 6. API (`/api/standardizationQuality`, 클래스 단위 VIEWER — 새 메뉴·권한 없음)

| API | 내용 |
|---|---|
| `GET /summary`, `GET /data` | **기존 그대로** (컬럼 3종, 대시보드가 `/summary` 를 쓴다) |
| `GET /model/summary` | `columns`(= 기존 요약과 같은 값), `totalTables`·`issueTables`·`tablePrefixMissing`·`tableWordNotStandard`·`tablePrefix`(null 이면 접두어 규칙 미적용), 심각도별 `errors`·`warnings`·`infos` |
| `GET /model/data` | 컬럼 + 테이블 진단. 그리드 규격 + `type`(규칙 코드)·`severity`·`targetType`·`schemaId`·`isApproval`, 검색 `tableName`/`columnName`, 정렬 `type`·`severity`·`targetType`·`schemaName`·`tableName`·`columnName`·`columnDesc`·`isApproval`. 행 : `ModelIssue`(테이블 대상은 컬럼 값 null, `tokens`, `fingerprint`). 기본 순서 : 테이블명 → 테이블 진단 → 컬럼 진단 |
| `GET /terms/summary` | `totalTerms`, `issueTerms`(한 그룹 이상에 속한 용어), 규칙별 그룹 수 `groups`, 심각도별 그룹 수 |
| `GET /terms/data` | 그룹 목록. `type`·`severity`, 검색 `termName`/`abbreviation`(그룹의 용어 중 하나라도), 정렬 `type`·`severity`·`groupKey`·`memberCount` |
| `GET /columns/{columnId}/suggestions` | 컬럼 정보·진단 유형·후보(최대 5)·`truncated`·`reason`(후보가 없을 때) |

잘못된 enum 값(`type`·`severity`·`targetType`)은 400. 프로젝트는 항상 로그인 프로젝트. 다섯 API 모두 기존 컨트롤러의 클래스 단위 `@PreAuthorize("hasRole('VIEWER')")` — VIEWER 이상 조회, 승인 전 사용자 거절.
**바뀌지 않은 것** : 기존 `/summary`·`/data` 의 응답 필드·판정·메시지, 대시보드 집계(`DashboardServiceImpl` 은 여전히 `/summary` 와 같은 계산만 부른다 — D2).

## 7. 화면 (`features/quality/`)

- `StandardizationQualityPage.tsx` 에 탭 두 개 : **모델 품질 진단**(기본) · **표준 용어 진단** (`?tab=terms`, 대시보드의 `?type=` 은 그대로 모델 탭 유형). 새 메뉴 없음.
- 모델 탭 : 컬럼 요약 카드(기존 5개, 값은 `/model/summary` 의 `columns`) + 테이블 카드 4개(검사 대상 테이블·접두어 표시, 문제가 있는 테이블, 접두어 누락, 비표준 단어 약어). 같은 카드를 다시 누르면 전체로. 조건 : 유형·심각도(건수 표시)·대상·스키마·승인. 열 : 테이블명·심각도·유형·대상·스키마·컬럼명·논리명·실제·기대·문제 설명·승인·이동. 이동 : [테이블 상세]·[ERD] (기존), 미연결·불일치 컬럼 행에 [개선 후보].
- 용어 탭 : 요약(검사 대상 용어, 진단된 용어, 오류·주의·참고 그룹) + 규칙별 카드 6개, 그룹 목록(관련 용어 칩 — 미승인은 주의 색), 행을 누르면 그룹 상세(용어별 약어·도메인·승인·사용 컬럼 수·근거, [표준 용어 관리] → `/standardTerm?searchColumn=commonStandardTermName&searchQuery=`).
- 개선 후보 대화상자(`qualityDialogs.tsx`) : 컬럼 정보, 후보별 순위·점수·용어(승인/미승인/미등록)·할 일·추천 컬럼명·근거(출처·단어 조합)·도메인 적합성(실제 → 기대 타입)·이름 충돌. 자동 적용 버튼 없음. [테이블 상세]·[ERD]·[컬럼 관리에서 보기]·[표준 용어 관리에서 보기].
- 그리드 최소 폭(모델 1280·용어 900px)으로 좁은 화면에서는 그리드 안에서만 가로로 넘긴다. 스타일은 기존 토큰만 (`primer.css` 의 품질 진단 절).

## 8. 검증

| 검증 항목 | 결과 |
|---|---|
| H2 전체 (`./gradlew build`) | 926건, 실패 0, 건너뜀 41 |
| PostgreSQL 18 전체 (빈 격리 DB) | 926건, 실패 0, 건너뜀 29 |
| 브라우저 (헤드리스 Chrome) | 76/76 통과 — 한국어·영어, 라이트·다크, 1440·375px, ADMIN·VIEWER·USER |
| DB 마이그레이션 | 없음 (V0~V6 그대로, V7 만들지 않음) |
| 미검증 | DBA·MANAGER 계정 화면, 세션 만료·프로젝트 접근 해제 알림 (8.5) |

```bash
./gradlew build                                                        # H2 : 926 tests, 실패 0, 건너뜀 41 (npm ci + typecheck + vite build 포함)
# PostgreSQL 18 : 빈 격리 DB(lexorma_m5r_*)를 만들어 실행하고 지웠다 (개발 DB 는 건드리지 않았다)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/lexorma_m5r_full SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
LEXORMA_TEST_POSTGRES_URL=jdbc:postgresql://localhost:15439/lexorma_m5r_full ./gradlew test -PfrontendPrebuilt=true --rerun
LEXORMA_PERF=true ./gradlew :lexorma-app:test -PfrontendPrebuilt=true --tests 'com.lexorma.StandardizationQualityPerformanceTest'   # 성능 (H2·PostgreSQL)
```

### 8.1 자동 테스트

| 테스트 | 내용 |
|---|---|
| `TableNameRulesTest` 4 | 같은 약어 여러 단어의 승인 판정, 접두어(대소문자·`_` 경계·예전 저장 값·미설정), 미등록·미승인·빈 약어, 접두어 없는 테이블은 이름 전체, fingerprint 안정성·구분 |
| `TermDuplicateRulesTest` 7 | 정상(자기 자신 중복 없음), 약어+도메인 중복·같은 약어 다른 도메인, 용어명 정규화(공백·`_`·대소문자), 단어 구성(순서·용어명 중복과의 중복 제외), 용어·단어 이음동의어, 금칙어(단어 경계·공백 경계·부분 문자열·자기 이름 제외), fingerprint |
| `ColumnNameSuggestionsTest` 7 | 대상 아님, 후보 0·여러 건, 출처 우선순위, 불일치 컬럼의 이름 변경·재연결, 미승인·도메인·이름 충돌·기대 이름 감점과 고정 순서, 미등록 조합(약어가 이미 있는 용어면 제외), 최대 5건 |
| `StandardizationQualityServiceTest` +1 | 기존 3종 ↔ 규칙 연결, 컬럼 fingerprint 핵심 값 |
| `StandardizationQualityRulesIntegrationTest` 6 | 모델 진단(요약·목록·조건·정렬·페이징, 기존 요약과 같은 값, 접두어 미설정, 격리), 용어 진단(6규칙 그룹·사용 컬럼 수·검색·정렬·조회 전용·격리), 개선 후보(대상·순서·충돌·후보 없음·미등록·조회 전용·다른 프로젝트 컬럼 거부), 권한(VIEWER 허용·NOT_APPROVE 거절), **LEXORMA 예제를 새로 마이그레이션한 DB 에서**(H2·PostgreSQL) 판정 |
| `SmokeTest` +1 | HTTP 바인딩 : 모델 요약 `columns` = `/summary`, 요약 = 목록 건수, 잘못된 `targetType`·`severity`·`type` 400, 후보 응답 계약, 없는 컬럼 오류. `gridSortAndSearch` 에 모델·용어 그리드 등록 |
| 회귀 | 기존 `StandardizationQualityIntegrationTest`·`SmokeTest.standardizationQuality`(예제 컬럼 진단 0건)·`DashboardStatsIntegrationTest`·`ModelMappingQualityIntegrationTest`(`compareDomain`)·`ModuleBoundaryTest`·`MessagesCoverageTest`·`UiActionAuthorizationIntegrationTest` 통과 |

H2 전체 `./gradlew build` : 926 건, 실패 0, 건너뜀 41. PostgreSQL 18 전체 : 926 건, 실패 0, 건너뜀 29 (8.4).

### 8.2 LEXORMA 예제

새로 마이그레이션한 DB 에서 (테이블 82 · 컬럼 915 · 용어 347, 접두어 `TB_`) : 테이블명 규칙 0건, 용어 진단 오류 0 · 주의 0 · **참고 1** — `뷰_참조_일련번호`(VIEW_RFRNC_SN, TB_VIEW_RFRNC 의 키) ↔ `참조_뷰_일련번호`(RFRNC_VIEW_SN, 참조되는 VIEW 의 ID) 가 같은 단어(뷰·참조·일련번호)를 다른 순서로 쓴다. 뜻이 다른 두 용어라 실제 중복이 아니고 참고(후보)로 나오는 것이 맞다 — **예제를 고치지 않았다**(V7 없음). 기존 컬럼 진단 0건은 그대로다.

같은 테스트 DB 를 쓰는 다른 테스트가 프로젝트 0 에 테이블·용어를 더하므로 예제 판정은 공유 DB 가 아니라 새 DB 에서 한다 (처음에 공유 DB 에서 단언했다가 전체 빌드에서 실행 순서에 따라 28건이 나와 바꿨다).

### 8.3 성능 (`StandardizationQualityPerformanceTest`, LEXORMA_PERF=true)

> 아래 값은 **이 테스트 환경(개발 PC·로컬 Docker PostgreSQL·최악 조건 데이터)에서 잰 결과**다. 운영 환경의 응답 시간을 보장하는 수치가 아니다.

요약 (중앙값) : PostgreSQL — 컬럼 10만 모델 요약 0.66초, 컬럼 10만 기존 컬럼 진단(`/data`) 0.55초, 용어 1만 요약 0.05초, 개선 후보 1건 0.04초 이하. H2 — 모델 요약 0.29초, 기존 컬럼 진단 0.21초, 용어 요약 0.04초. 측정 범위에서 SQL 문 수는 데이터 건수와 무관했고(모델 4 · 용어 요약 6 · 용어 목록 7 · 후보 8), 컬럼 10만 모델 요약의 요청당 할당은 약 600~740 MB 였다.

환경 : macOS · Apple M3 Max(개발 PC), 테스트 JVM 힙 1 GB, 워밍업 1회 + 7회 중앙값. 데이터는 JPA `saveAll`(테이블 100개씩 트랜잭션)로 만들었고 **결과가 가장 많은 조건**이다 — 모든 컬럼에 도메인 불일치, 5개 중 1개 미연결, 모든 테이블에 비표준 단어 약어(이름 끝 순번), 10개 중 1개 접두어 누락. 할당은 요청 스레드의 할당 바이트(가비지 포함, 상주 메모리가 아니다).

| 데이터 | 요청 | H2 중앙 ms | PostgreSQL 중앙 ms | SQL 문 | 할당 MB (H2 / PG) | 결과 |
|---|---|---|---|---|---|---|
| 컬럼 1만 (테이블 200) | 기존 `/summary` | 24.8 | 118.2 | 2 | 35 / 48 | 문제 컬럼 10,000 |
| | `/model/summary` | 31.1 | 130.9 | 4 | 60 / 74 | 진단 11,220건 |
| | `/model/data` 1쪽 | 29.1 | 126.0 | 4 | 60 / 74 | |
| | `/terms/summary` (용어 1천) | 6.1 | 12.1 | 6 | 10 / 11 | 그룹 20 |
| | 개선 후보 1건 | 3.1 | 12.4 | 8 | 2 / 3 | |
| 컬럼 10만 (테이블 2,000) | 기존 `/summary` | 214.4 | 5,588.6 ※ | 2 | 341 / 476 | 문제 컬럼 100,000 |
| | 기존 `/data` 1쪽 | 205.6 | 545.6 | 2 | 339 / 474 | |
| | `/model/summary` | 287.8 | 656.1 | 4 | 603 / 739 | 진단 112,200건 |
| | `/model/data` 1쪽 | 281.0 | 639.9 | 4 | 601 / 737 | |
| | 개선 후보 1건 | 1.5 | 28.4 | 8 | 2 / 3 | |
| 용어 1만 (단어 1천) | `/terms/summary` | 37.1 | 52.0 | 6 | 89 / 97 | 그룹 257 (단어 구성 47·이음동의어 10·금칙어 200) |
| | `/terms/data` 1쪽 | 35.5 | 60.1 | 7 | 89 / 97 | |
| | 개선 후보 1건 | 9.8 | 39.5 | 8 | 17 / 26 | |

- SQL 문 수는 데이터 양과 무관하다 (모델 4 · 용어 요약 6 · 용어 목록 7 · 후보 8). N+1 없음.
- 모델 진단은 기존 컬럼 진단보다 SQL 2문(테이블·단어 약어)과 판정·병합만 더한다 : 시간은 기존 대비 H2 1.25~1.35배, PostgreSQL 1.1~1.2배(10만은 기존 `/data` 와 비교), 할당은 1.5~1.8배다.
- ※ PostgreSQL 10만의 기존 `/summary` 는 시드 직후 첫 측정이라 다른 요청보다 10배 느렸다(평균 4.9초, 같은 계산인 `/data` 는 0.55초). 코드는 바꾸지 않은 엔드포인트이며 원인(시드 직후 DB 상태 등)은 확인하지 않았다.
- 컬럼 10만에서 요청당 할당 600~740 MB(가비지)는 큰 편이다 — 수십만 컬럼이면 2차 기록의 제약처럼 진단 결과 저장(AI-6)이나 SQL 로 거를 수 있는 유형 분리가 필요하다. 대시보드는 신규 진단을 부르지 않는다.

### 8.4 PostgreSQL

전체 테스트(빈 격리 DB `lexorma_m5r_full`, `--rerun`) : **926건, 실패 0, 건너뜀 29** (예제 판정의 PostgreSQL 변형 포함). 신규·회귀 클래스 조합(SmokeTest 69·StandardizationQuality 2·Rules 5·DashboardStats 5·ModelMappingQuality 1)은 빈 DB 에서 통과. 처음 한 번 SmokeTest 가 첫 로그인 요청에서 `HTTP/1.1 header parser received no bytes`(서버가 응답 없이 연결을 끊음)로 실패했고, 새 DB 로 SmokeTest 단독·같은 조합을 다시 돌렸을 때는 재현되지 않았다 — 원인은 확인하지 않았다.

### 8.5 브라우저 (헤드리스 Chrome 157 을 CDP 로 직접 조작, 의존성 추가 없음)

대상 : 빌드한 `lexorma.jar` 를 `h2-local`(임시 데이터 폴더)·포트 19940 으로 따로 띄운 인스턴스. 예제에 시험 데이터(접두어 없는 테이블 `MBR_HIST`, 미승인 단어 `HIST`, 미연결 컬럼 `USER_ID`·`ZZZ_QQ`, 불일치 컬럼, 약어·이름이 겹치는 용어 2건, VIEWER·USER 계정)를 넣었다. 4가지 조합 — 한국어·라이트·1440px·ADMIN / 영어·다크·1440px·ADMIN / 한국어·다크·375px·VIEWER / 영어·라이트·375px·USER — 에서 **76/76 통과** : 탭·요약 카드·그리드, 테이블 진단 행, 접두어 누락 카드 필터(1건), 개선 후보 대화상자(후보 3건 · USER_ID · 도메인 적합성 · 이름 충돌, 적용 버튼 없음), 표준 용어 관리 이동(검색어 유지), 후보 없음 사유, 용어 탭(API 와 같은 4그룹), 그룹 상세(관련 용어 2), 빈 결과 안내, 가로 넘침 없음(본문·대화상자), 한국어 화면에 영어 문구 없음 / 영어 화면에 한국어 화면 문구 없음(데이터 값 제외), 콘솔 오류·API 4xx/5xx 없음. 화면 캡처로 라이트·다크·375px 배치를 확인했다.

**브라우저 미검증** : 세션 만료·프로젝트 접근 해제 알림, DBA·MANAGER 계정, Chrome 이외 브라우저, 키보드만으로 조작, PostgreSQL 을 붙인 화면, 대시보드 → `?type=` 진입(코드 경로는 기존 그대로).

## 9. 제약 · 후속

상태 관리는 [pending-work.md](../pending-work.md) 가 한다 (아래는 근거와 분류). 재현되지 않은 현상은 확정 결함이 아니다.

### 9.1 M-5 후속 (pending-work [M-5])

- **등록 시점 검증과 진단 규칙의 공통화 (D5)** : 판정 함수 공통화는 4차로 끝났다 (10장). 표준 용어 중복은 5차(11장)에서 약어 대소문자 무시 + 도메인 ID 로 통일했다. 다른 진단 규칙으로 등록·수정을 막을지는 정책 결정이 필요하다.
- **문자열 유사도 (D7 보류)** : 넣는다면 같은 도메인·n-gram 역색인 같은 블로킹으로 O(N²) 를 피한다.
- **브라우저 미검증** : DBA·MANAGER 계정 화면, 세션 만료 알림 (8.5).

### 9.2 M-7 로 이관 (pending-work [M-7])

- DBMS별 식별자 문자·길이 검사 (D3). **후속 반영 (2026-11-17, M-7 보강 1차)** : 규칙 5개(`IDENTIFIER_*`·`CONSTRAINT_NAME_*`, `TargetType.IDENTIFIER`)와 `/api/standardizationQuality/identifiers/*`·[DBMS 식별자 진단] 탭으로 구현했다. 이 문서의 기존 규칙·`/summary`·`/model/*`·대시보드 수치는 그대로이고 식별자 결과는 따로 집계한다 — [logical-physical-mapping-m7.md 12장](logical-physical-mapping-m7.md).
- DBMS별 예약어 검사 — **구현하기로 확정한 것이 아니라** 정책 검토 대상이다 (D4). M-7 보강 1차에서도 제외했다 (P13).

### 9.3 AI-6 연계 (pending-work [AI-6])

`StandardizationRule`·`Finding`·`FindingFingerprint` 는 계산만 한다. 결과 재사용, fingerprint 로 결과 식별, 결과 저장, 신규/유지/해결 상태, 프로젝트별 규칙 활성화(U-16)는 AI-6. `lexorma-ai` 는 지금 이 진단을 호출하지 않는다 (ai → data 방향이라 호출해도 모듈 경계 위반은 아니다).

### 9.4 성능·안정성 (pending-work [M-5])

- **컬럼 전체 메모리 로딩** : 컬럼·테이블 진단은 2차와 같이 프로젝트 전체를 읽어 메모리에서 판정·페이징한다. 수십만 컬럼이면 결과 저장(AI-6)이나 SQL 로 거를 수 있는 유형 분리를 검토한다.
- **요청당 할당** : 컬럼 10만에서 600~740 MB (8.3) — 무엇이 차지하는지 분석하지 않았다.
- **PostgreSQL 첫 측정 이상값** : 컬럼 10만 기존 `/summary` 의 시드 직후 첫 측정 5.6초 (8.3) — 원인 미확인.
- **PostgreSQL 첫 로그인 연결 끊김** : SmokeTest 1회 (8.4) — 재실행 2회에서 재현되지 않았다.

### 9.5 알려진 동작 제약 (설계 결정, 후속 과제 아님)

- **이음동의어** : 한 단계만 바꾼다(체인·순환은 보지 않는다). 용어 이음동의어는 용어명과만 비교한다.
- **개선 후보** : 같은 논리명 비교는 SQL 에서 공백·`_` 만 뺀다. 같은 테이블의 다른 컬럼들끼리 후보가 겹치는지는 그 컬럼들의 "기대 이름"(연결된 용어 약어)만 본다 — 다른 컬럼의 후보를 모두 계산하지 않는다.

## 10. 4차 — 진단 규칙 ↔ 등록·수정 검증 비교와 판정 함수 공통화 (2026-11-18)

목표 : 품질 진단과 등록·수정이 **같은 뜻으로 계산하는 부분만** 한 함수로 모은다. 진단에서 오류로 보이는 것을 등록에서 새로 막지 않는다 (D5 유지). DB·마이그레이션·API·화면 변경 없음 (V0~V7 그대로, 다음 V8).

### 10.1 비교표

판정 : ① 기준이 완전히 같아 공통화 ② 비슷하지만 정책이 달라 공통화하지 않음 ③ 판정의 일부만 공통 함수, 처리는 경로마다 다름.

| 규칙 | 품질 진단 | 등록 | 수정 | 엑셀 업로드 | 판정 |
|---|---|---|---|---|---|
| 용어 중복 (약어 + 도메인) | `TERM_ABBR_DUPLICATE` 오류 : 약어 trim·대문자 + **도메인 ID** | 약어 + **도메인명** 정확 일치(DB 비교, 대소문자 구분) → 400 `DUPLICATE_TERM_DATA`. 화면의 중복 확인 API 도 같다 | 검사 없음 | 용어명 + 약어 + 도메인 ID 정확 일치면 건너뜀 (오류 아님) | ② |
| 용어명 중복 | `TERM_NAME_DUPLICATE` 오류 : 공백·`_` 제거 + 소문자 | 검사 없음 | 검사 없음 | 위 키에 포함 | ② (정규화의 `compact` 만 ③ — 엑셀의 용어명 결정과 같은 함수) |
| 용어 명칭 형식 | 규칙 없음 | `validateWordCount` (`_` 구분, 공백·빈 단어 없음, 약어와 단어 수 같음) → 400 `TERM_WORD_COUNT_MISMATCH` | 이름·약어가 바뀔 때만 같은 검사 | 맞지 않으면 약어로 용어명을 새로 만든다 | 진단 대상 아님 — 변경 없음 |
| 용어의 단어·약어 | `TERM_SAME_WORDS`·`TERM_SYNONYM` (단어 매핑 기준, 참고) | 약어마다 단어(약어 정확 일치) + 같은 순번 단어명 정확 일치 → 400 `NOT_FOUND_WORD_DATA` | 단어 매핑 갱신 없음 | 같은 약어의 단어 중 고름 | ② |
| 단어 중복·형식 | 규칙 없음 (`TargetType.WORD` 는 AI-6 용) | 단어명 정확 일치 → 400 `SAVED_DATA_EXISTS`, `_` → 400 `WORD_UNDERSCORE_NOT_ALLOWED` | 이름·약어는 수정 대상이 아님 | 정확 일치 건너뜀, `_` 는 업로드 취소 | 진단 대상 아님 — 변경 없음 |
| 금칙어 | `TERM_RESTRICTED_WORD` 주의 (단어 경계에서 정확히 같음) | 검사 없음 | 검사 없음 | 없음 | 단어 경계(`splitWords`)만 ① — 테이블명 생성과 같다 |
| 도메인명 중복 ([DICT-2]) | 별도 진단 API (`StandardizationRule` 아님), 대소문자 무시 | 대소문자 무시 + 프로젝트 행 배타 잠금 → 400 `DUPLICATE_STANDARD_DOMAIN_NAME` | 이름은 수정 대상이 아님 | 대소문자 무시 건너뜀 | 이미 공통 (`lower()`·`StandardDomainService.nameKey`) — **그대로**, 새 정규화 없음 |
| 도메인명 조회 | — | 대소문자 무시, 2건 이상 409 `AMBIGUOUS_STANDARD_DOMAIN` | 같음 | `nameKey` 캐시, 같음 | 그대로 |
| 테이블명 | `TABLE_PREFIX_MISSING`·`TABLE_WORD_NOT_STANDARD` 오류 (물리명 → 약어, 대소문자 무시) | 한글명 → 접두어 + 승인된 단어 약어로 **생성**, 못 만들면 400 `TABLE_WORD_NOT_STANDARD` | 같은 생성 | 엑셀·원본 가져오기·모델 교환은 규칙을 거치지 않는다 | ③ 단어 나누기(`splitWords`)·약어 키(`physicalNameKey`)만 공통. 접두어 정규화는 `Project.normalizeTablePrefix`(형식 오류 400) ↔ 진단(예전 값도 예외 없이 비교)이 달라 각자, "승인된 단어 우선"은 결과가 달라(단어 선택 ↔ 약어 판정) 각자 |
| 컬럼명 | `COLUMN_NAME_MISMATCH` 오류 : 기대 = 용어 약어 trim(비면 판정 안 함), 앞뒤 공백·대소문자 무시 | 검사가 아니라 컬럼명 = 용어 약어로 **생성** (화면 일괄 저장·엑셀) | — | — | ① 진단·개선 후보·물리명 추천·논리↔물리 정합성 비교가 같은 함수 |
| 도메인 정의 | `DOMAIN_DEFINITION_MISMATCH` 주의 (`compareDomain`) | 컬럼 저장은 검사 없음 | — | — | 이미 공통 (진단·개선 후보·정합성 비교) — 변경 없음 |

### 10.2 공통 판정 함수 `dictionary/quality/StandardNames` (순수 함수, Spring 의존 없음)

| 함수 | 기준 | 호출하는 곳 (처리는 각자) |
|---|---|---|
| `expectedColumnName(약어)` | 앞뒤 공백 제거, 비면 null | 컬럼명 불일치 진단(`StandardizationQualityService.diagnose` → Finding), 개선 후보(`ColumnNameSuggestions` → 할 일·충돌), 물리명 추천(`PhysicalNameRecommendationService.recommendColumnName` → 추천명 + 사유 문구) |
| `sameColumnName(현재, 기대)` | 앞뒤 공백 제거 + `equalsIgnoreCase`, 기대 null 이면 false | 위 셋 + 정합성 비교(`ModelMappingQualityService` 는 `PhysicalNameRecommendationService.sameName` 을 거쳐 같은 함수) |
| `compact(이름)` · `termNameKey(이름)` | 공백·`_` 제거 / + 소문자(ROOT) | 용어 진단(`TermDuplicateRules`), 개선 후보·같은 논리명 컬럼 수(`ColumnNameSuggestionService`), 엑셀 용어명 결정(`StandardTermService.resolveUploadedTermName`·`pickWord` 은 `compact`) |
| `physicalNameKey(이름)` | 앞뒤 공백 제거 + 대문자(ROOT) | 용어 약어 그룹(`TermDuplicateRules`), 테이블명 약어 판정(`TableNameRules`), 개선 후보의 약어 비교 |
| `splitWords(이름)` | `_`·공백 경계, 빈 조각 제외 | 테이블명 생성(`TableDesignServiceImpl`), 금칙어 진단의 용어명 경계(`TermDuplicateRules`) |

- 바뀐 것은 계산 위치뿐이다 : 이전 `TermDuplicateRules.normalizeName`·`normalizeAbbreviation`, 각 클래스 안의 `trim().toUpperCase(Locale.ROOT)`·`replaceAll("[_\\s]", "")`·`split("[_\\s]+")` 를 위 함수로 바꿨다. `PhysicalNameRecommendationService.recommendColumnName`·`sameName` 은 그대로 남아 같은 함수에 맡긴다 (사유 문구는 그쪽에 있다).
- 진단 쪽(`dictionary/quality`)이 `mapping` 서비스를 부르던 의존(`ColumnNameSuggestions` → `PhysicalNameRecommendationService`)이 없어졌다 — 이제 `mapping` 이 `dictionary/quality` 를 쓴다. 모두 `lexorma-data` 안이라 Gradle 모듈 의존은 바뀌지 않았다 (`lexorma-ai`·MCP 의존 추가 없음).
- 새 조회·요청당 계산은 없다 (순수 함수 바꿔 끼우기, 같은 정규식). 대량 컬럼 메모리 로딩은 9.4 그대로.

### 10.3 공통화하지 않은 것과 이유

- **용어 중복 기준** : 진단은 대소문자 무시·도메인 ID, 등록은 정확 일치·도메인명, 수정은 검사 없음, 엑셀은 건너뜀 — 허용·거절 범위가 달라 한 함수로 합치면 기존 허용 요청이 400 이 되거나 진단이 줄어든다.
- **단어명 중복** : 등록은 정확 일치(`Member`·`member` 공존 가능), 진단에는 단어 중복 규칙이 없다.
- **도메인명** : [DICT-2] 의 `lower()`(DB)·`toLowerCase(ROOT)`(엑셀) 비교를 그대로 둔다 — 용어 이름 키(공백·`_` 제거)와 기준이 다르고, 특수 유니코드 차이(pending [M-5] ⑩)는 새 정규화로 풀지 않는다.
- **테이블 접두어 정규화·승인 단어 선택** : 10.1 테이블명 행.

### 10.4 검증

| 항목 | 결과 |
|---|---|
| 결과 동일성 (`StandardizationFindingGoldenTest`) | 공통화 **전** 코드로 만든 기대값 `lexorma-data/src/test/resources/quality-golden/findings-v1.txt` (99행 : 컬럼 3종 × PostgreSQL·Oracle, 테이블명 2종 × 접두어 4가지, 용어 6종, 개선 후보 8건 — 규칙·대상·현재/권장 값·근거·문구·fingerprint·후보 전체) 와 공통화 후 결과가 같다. 판정을 일부러 바꾸는 작업이 아니면 기대값을 고치지 않는다 |
| 공통 함수 단위 (`StandardNamesTest` 7건) | 정상·경계·null·빈 값·대소문자, 물리명 추천과 같은 결과, 공통화하지 않은 정책(도메인명 키·단어 수 규칙)이 그대로인지 |
| 등록·수정 정책 (`StandardRulePolicyIntegrationTest` 4건 — 5차에서 용어 3건은 정책이 바뀌어 `StandardTermDuplicateIntegrationTest` 로 옮기고 단어 1건만 남겼다) | 용어 등록 중복 = 약어 + 도메인명 정확 일치(400), 대소문자만 다른 약어·도메인명은 등록되고 진단이 오류 그룹으로 묶는다, 용어 수정은 중복 미검사, 단어명 정확 일치(400)·`_`(400), 프로젝트 격리(진단 그룹·fingerprint) |
| 기존 테스트 | M-5 1~3차·[DICT-1]·[DICT-2] 테스트 기대값을 고치지 않았다 |
| 전체 | H2 1,016건 실패 0 (건너뜀 45), PostgreSQL 18.6 빈 격리 DB 1,016건 실패 0 (건너뜀 30) |
| 실제 HTTP · 브라우저 | 등록·수정 오류 코드(400/409)·한국어·영어 메시지, 진단 API. 헤드리스 Chrome 16조합(모델·용어 탭 × 한국어·영어 × 라이트·다크 × 1440·375px)과 용어 그룹 상세 — 가로 스크롤·콘솔 오류 없음. 미검증 : DBA·MANAGER·세션 만료, UI 의 오류 알림 표시 |

### 10.5 이번에 확인한 현행 동작 (바꾸지 않음 — 후속 결정) → 5차에서 해결 (11장)

- **용어 등록 중복 검사와 도메인 조회의 대소문자 차이** : 중복 검사는 도메인명을 정확히 비교하고([DICT-2] 이전 그대로) 도메인 조회는 대소문자를 무시한다([DICT-2] 후속). 그래서 `MBR_NO` + `CODE` 용어가 있어도 `MBR_NO` + `code` 로 등록하면 중복 검사를 지나 같은 CODE 도메인에 연결된 두 번째 용어가 생긴다. 진단은 `TERM_ABBR_DUPLICATE`(오류)로 알린다. 등록 거절 범위가 넓어지는 변경이라 정책 결정 전에는 고치지 않았다 (`StandardRulePolicyIntegrationTest` 가 현행을 고정 — 정책을 바꾸면 그 테스트를 함께 고친다).

## 11. 5차 — 표준 용어 중복 정책 통일 (2026-11-18, 사용자 확정 정책)

10.5 의 틈(등록 중복 검사는 도메인**명** 정확 일치, 도메인 조회는 대소문자 무시 → `MBR_NO`+`code` 가 같은 도메인의 중복 용어를 만든다)을 닫는다. DB·마이그레이션 변경 없음 (V0~V7 그대로, 다음 V8), 예전 중복 데이터는 진단만 한다.

### 11.1 확정 정책과 구현

| 항목 | 정책 | 구현 |
|---|---|---|
| 중복 키 | 같은 프로젝트 + 영문약어 **대소문자 무시** + 실제 **도메인 ID** | `StandardTermRepository.findDuplicates` (`lower(TRM_ENG_ABBR_NM) = lower(:abbr)` + `STD_DMN_SN`), 엑셀은 `StandardTermService.duplicateKey`(Java `toLowerCase(ROOT)`). 앞뒤 공백·`_`·특수문자·유니코드 정규화 없음 (2.2) |
| 승인 상태 | 기존 범위 그대로 | 승인·미승인 모두 포함 (용어 상태는 `APRV_YN` 두 값뿐 — 반려·비활성 상태 없음, 삭제는 실제 삭제) |
| 등록 | 중복이면 400 `DUPLICATE_TERM_DATA` (한국어·영어 메시지 그대로) | 오류 순서는 예전 그대로 : 단어 수 → **중복** → 단어(`NOT_FOUND_WORD_DATA`) → 도메인(없음 400 / 모호 409). 도메인명을 대소문자 무시로 찾아 **정확히 1건**일 때만 그 ID 로 중복을 본다 — 0건이면 중복 없음(뒤에서 400), 2건 이상이면 중복을 판단하지 않고 도메인 단계에서 409 (2.3) |
| 중복 확인 API (`GET /api/getStandardTerms/duplicate`, 화면의 등록 전 확인) | 등록과 같은 판정 | 도메인명 → ID(대소문자 무시) + 약어 대소문자 무시. 도메인이 없으면 `duplicated:false`, **모호하면 409** (예전에는 도메인명 정확 일치 목록 — 계약 변화) |
| 수정 | 기존 계약 분석 후 안전한 범위 (8.2) | **중복 키가 바뀌는 수정만** 검사 (자기 자신 제외, 승인 여부 무관) → 겹치면 400. 키가 그대로인 수정(설명 등, 약어 대소문자만 바꾸기, 이미 중복인 예전 데이터의 수정)은 예전처럼 검사하지 않는다 |
| 엑셀 | 기존 중복 처리 계약 유지 | 같은 키의 행은 DB 에 있든 파일 안에서 앞에 나왔든 **건너뛴다** (오류 아님 — 예전 키 "용어명 + 약어 + 도메인 ID 정확 일치"를 새 키로). 단어·도메인 오류(400/409)와 업로드 전체 롤백 그대로 |
| 동시성 | 중복 생성 방지 | 등록과 키를 바꾸는 수정은 검사 전에 프로젝트 행 배타 잠금(`ProjectRepository.findByIdForUpdate`, 도메인 등록·엑셀 업로드와 같은 잠금). 엑셀은 도메인 시트 단계에서 이미 같은 잠금을 잡은 한 트랜잭션 |
| 기존 중복 데이터 | 진단만, 자동 정리 금지 | 기존 `TERM_ABBR_DUPLICATE`(오류) 재사용 — 이미 약어 대소문자 무시 + 도메인 ID 로 묶는다. 판정·Finding·fingerprint 변화 없음 (골든 99행 그대로). 그룹 구성원 응답에 `domainId`·`registeredAt`·`modifiedAt` 추가 (등록·수정 일시는 사용 컬럼 수처럼 한 페이지 분량만) |

### 11.2 수정 API 분석 (8장)

| 항목 | 현행 (바꾸지 않음) |
|---|---|
| API 로 바꿀 수 있는 필드 | 제정차수·용어명·설명·영문약어·행정표준코드명·소관기관·이음동의어(null 이면 유지, [DICT-1])·도메인. 단어 매핑은 갱신하지 않는다 |
| 화면 | 수정 버튼은 **미승인 용어만**, 용어명·영문약어·도메인은 읽기 전용으로 보낸다 → 화면 수정은 중복 키를 바꾸지 않아 5차 검사에 걸리지 않는다 |
| 승인 | 수정하면 승인 여부를 수정자 권한으로 다시 정한다(관리자·승인 권한이면 승인). API 로는 승인된 용어도 수정된다 — 둘 다 기존 동작 그대로, 이번에 바꾸지 않았다 |
| 단어 수 규칙 | 용어명·약어가 바뀔 때만 (기존) |

### 11.3 진단과 등록 기준의 차이 (남김)

진단 `TERM_ABBR_DUPLICATE` 는 약어 **앞뒤 공백도 무시**한다(`StandardNames.physicalNameKey`). 등록 키는 확정 정책대로 공백을 다루지 않으므로 `MBR_NO` 와 `" MBR_NO"` 는 등록에서는 다른 키, 진단에서는 같은 그룹이다 — 진단이 더 넓다(등록이 막는 것은 모두 진단도 잡는다). 화면·엑셀 등록은 앞뒤 공백 약어를 단어로 찾지 못해 거절하므로 이런 값은 수정 API 로만 생길 수 있다. 진단 기준을 줄이면 골든 결과가 바뀌어 이번에는 그대로 두었다.

### 11.4 검증

| 항목 | 결과 |
|---|---|
| `StandardTermDuplicateIntegrationTest` (신규 8건, H2·PostgreSQL) | 등록 표(정확·소문자·혼합 약어, 도메인명 대소문자 → 400 / 다른 도메인 ID·다른 프로젝트 → 허용), 오류 순서·모호한 도메인 409, 수정(자기 제외·키 그대로·키 변경 충돌 400·미승인 용어와 충돌·예전 중복 데이터의 비핵심 수정), 진단(예전 중복 묶음·다른 도메인 ID·다른 프로젝트 제외·승인 상태 혼합·진단 전후 데이터 불변·새 구성원 필드), 엑셀(DB 예전 중복·파일 안 중복·도메인명 대소문자·다른 도메인 ID), 동시 등록(같은 키 3건 → 1건, 다른 도메인·프로젝트 → 모두 성공), 등록 ↔ 키 변경 수정, 엑셀 ↔ 등록 — 각 3회 |
| 바뀐 기존 테스트 | `StandardTermServiceTest`(중복 목 → 도메인 ID 기준, 프로젝트 잠금 목), `StandardRulePolicyIntegrationTest`(4차의 용어 현행 고정 3건 → 이 테스트로 이동), `DataPortalTemplateTest`(아래 11.5 — 건너뛰는 두 행을 명시) |
| 결과 동일성 | `StandardizationFindingGoldenTest` 99행 그대로 (용어 중복 규칙 판정 변화 없음) |

### 11.5 정책 충돌 (구현은 확정 키로, 결정 대기)

- **데이터 사전 약어를 쓰지 않는 프로젝트의 엑셀 업로드** : 영문약어 칸에 단어 영문명으로 만든 이름이 들어간다. 공공데이터 공통표준 템플릿(1,695 용어)에서 뜻이 다른 두 쌍이 같은 영문명·같은 도메인(`명V100`)이 된다 — `그룹_명`/`단체_명` → `GROUP_NAME`, `문서_명`/`서류_명` → `DOCUMENT_NAME`. 예전 엑셀 키는 용어명까지 봐서 모두 등록했고("생성된 영문명이 우연히 같은 다른 용어는 등록한다"), 새 키로는 뒤의 행(`단체_명`·`서류_명`)을 건너뛴다. 화면 등록도 같은 키로 거절하므로 경로 간 일관성은 맞다. 약어를 쓰는 프로젝트는 영향 없음.
- **DB 직접 쓰기** : UNIQUE 제약이 없어(예전 중복 가능, V8 금지) 앱을 거치지 않는 쓰기·JDBC 일괄 적재는 막지 못한다.
