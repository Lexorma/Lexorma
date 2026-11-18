# M-5 3차. 테이블명 규칙 · 표준 용어 중복·유사어 · 컬럼명 개선 후보 — 설계 결정과 구현 기록

> [데이터 모델링 로드맵](../reference/data-modeling-roadmap.md) M-5 의 잔여 중 세 가지 진단의 기록이다. 1차(사용 현황) : [standardization-usage-m5.md](standardization-usage-m5.md), 2차(컬럼 품질 진단) : [standardization-quality-m5.md](standardization-quality-m5.md).
> 작성일 2026-11-17. **DB 변경 없음** (Flyway V0~V6 그대로, 새 마이그레이션·V7 없음). 모두 조회 전용 — 테이블·컬럼·표준 단어·용어·도메인을 바꾸지 않고, 적용 API 도 없다. AI Provider·MCP 와 무관하게 동작한다.
> 작업 지시서의 "M-5 2차 구현"이 이 기록이다 (저장소 기록의 2차는 컬럼 품질 진단이라 여기서는 3차로 부른다).
> **상태** : 이 기록의 범위는 구현·검증 완료. **M-5 전체는 부분 완료**로 남는다 — 남은 일은 [pending-work.md](../pending-work.md) [M-5] 와 9장.

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

- **등록 시점 검증과 진단 규칙의 공통화 (D5)** : 등록·수정은 그대로다 — 용어 등록의 중복은 대소문자를 구분하고, 수정은 중복을 검사하지 않는다. 진단과 같은 기준으로 막으려면 정책 결정이 필요하다.
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
