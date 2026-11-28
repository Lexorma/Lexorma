# AI-6 규칙 기반 메타데이터 품질 진단 — 1차 현황 분석 · 상세 설계 (2026-11-25)

**상태 (2026-11-27) : 완료 — 1차(분석·설계, 1~32장) → 2차(실행·결과 저장, 33~45장) → 2차 최종 보완(46장) → 3차(47장, 화면·완전성 규칙·대시보드) → FINAL(48장, 메모리·저장 구조 비교·회귀) → FINAL 최종 보완(49장, 저장 구조 D·fingerprint v4·실행 이력 누적 삭제 측정) → 최종 승인(50장, AI6-CLOSE-01~10). 로컬 구현·검증 완료, 원격 CI 는 사용자 커밋·푸시 뒤. 후속 : AI-7·AI-8·AI-9·[PRJ-DEL].** 아래 앞 장들의 '결정 전'·'권장'·'다음은' 표현은 그때의 기록이다.

- **근거 표기** : **[코드]** = 저장소 코드를 읽어 확인 (경로), **[문서]** = 기존 기록에서 확인, **[제안]** = 이 문서의 설계 제안 (확정 아님), **[미확인]** = 확인하지 못함.
- 기준 : HEAD `c8bd92b` (DB-1 통합 커밋, `dev`). 작업 트리는 인덱스의 `AD` 8개(다른 세션 7개 + 옛 경로 `docs/ongoing/postgresql-only-migration.md`) 외에 변경 없음.
- **확정 / 권장 / 미확정 구분** : '확정' 은 이미 사용자가 정한 정책(2장)만이다. 3장 이후의 '권장' 은 모두 결정 전 제안이며, 29장에서 결정을 요청한다.

## 1. 작업 목적

M-5 의 표준화 품질 진단(규칙 `StandardizationRule` · 판정 결과 `Finding` · `FindingFingerprint`)을 재사용해 **결정적·규칙 기반** 진단을 실행 단위로 돌리고, 결과를 저장하고, 같은 문제를 fingerprint 로 식별해 신규·유지·해결(·예외)을 관리하며, 프로젝트별 규칙 사용 여부(U-16)를 둔다. 결과는 대시보드·후속 AI 개선 권고(AI-7)·배치(AI-8)·보고서(AI-9)가 쓰는 공통 데이터가 된다. AI Provider 없이 동작해야 한다.

## 2. 기존 확정 정책 (다시 결정하지 않는다)

| 출처 | 내용 |
|---|---|
| DB-1 (PG-D01~D51) | 관리 DB PostgreSQL 전용 (최소 15 · 권장 18), 단일 JAR, Docker, Spring Session JDBC, H2·Testcontainers 없음, 테스트는 `LEXORMA_TEST_POSTGRES_*` + `TestDatabaseGuard` |
| PG-D31 | 실제 사용자 0명 — 하위 호환·이전 기능 설계 안 함 (개발 DB·다른 세션은 보호) |
| M-5 D1~D10 (2026-11-17) | 테이블명 판정 = 접두어 + 승인된 단어 약어(D1), 대시보드 준수율 의미 유지(D2), 등록·수정 중복 검증은 기존 그대로·진단만(D5), 같은 약어·다른 도메인 = WARNING(D6), 문자열 유사도 보류(D7), **심각도 3단계 ERROR·WARNING·INFO 고정, 규칙별 값 확정(D8)**, VIEW 진단 제외(D9), 용어 진단은 기존 화면의 탭(D10) |
| M-5 규칙 코드 | enum 이름 = 화면·API·fingerprint 가 쓰는 코드 — 바꾸지 않는다 [코드 `StandardizationRule` 주석] |
| U-15 (AI-5 결정) | 비동기 작업 = 서버 안 스레드 + DB 실행 상태, 프로젝트당 진행 중 하나, 재시작 시 INTERRUPTED·자동 재개 없음. '8단계 배치는 그때 다시 본다' |
| DEC-14 (2026-11-16) | 모델 버전·변경 이력·평가 기준 데이터는 보존, 운영 데이터는 보관 정책·참조 무결성을 갖출 때만 정리 (보관 기간은 후속) |
| 로드맵 원칙 | AI 의 SQL·DDL·DML 직접 실행 금지, 관리 데이터 자동 수정 금지, 승인 정책 유지, 배치·검사는 Lexorma 자체 DB 만 읽음(원본 DB 접속 없음) |
| DB-1 커밋 | 사용자가 `c8bd92b` 로 통합 커밋, 원격 GitHub Actions 전체 Job 성공 — **사용자 확인에 따른 완료** (에이전트는 원격 로그를 보지 못함 — `gh` 없음) |

## 3. 기존 로드맵 [문서]

| 작업 | 로드맵 정의 (요약) | 상태 |
|---|---|---|
| **AI-6** (6단계) | 결정 가능한 문제를 AI 없이 찾고 7단계 후보를 줄인다. 범위 : 규칙 목록화와 구현(원본 COMMENT 누락, 업무 설명 누락·지나치게 짧음, 표준 단어 미등록 약어, 명명 규칙 위반, 표준 용어 미매핑 컬럼, 동일 컬럼명의 타입·길이 불일치, 표준 도메인 ↔ 컬럼 타입·길이 불일치, FK 컬럼 타입 불일치, 승인된 설명과 다른 표준 용어 사용) · 실행 단위(프로젝트 / 테이블) · 결과 항목(대상·규칙·심각도·현재 값·권고·근거) · **fingerprint(대상 + 규칙 + 핵심 값) + 상태(신규 / 유지 / 해결 / 예외 처리)**. 기존 검증 로직 재사용, 규칙 중복 금지, **수동·야간 검사가 같은 규칙 엔진**. **완료 조건 : 규칙별 결과 저장, 재실행 시 신규/유지/해결 구분, 예외 처리한 항목이 다시 신규로 나타나지 않음.** U-16 | 부분 완료 (규칙 판정만) |
| AI-7 (7단계) | LLM 이 필요한 후보만 6단계 결과·변경분에서 골라 의미 검사·개선안, 승인/수정/거절 → 승인분만 데이터 사전 반영, 반복 추론 방지(fingerprint + 대상 변경), LLM 실패 시 규칙 결과 보존 | 미착수 |
| AI-8 (8단계) | 수동·정기 실행(프로젝트/테이블), 증분 우선, 실행 이력, 재시작 처리, 다중 인스턴스 중복 실행 방지(U-17), 배치 설정 = 프로젝트 관리자 이상, 결과 조회 = 멤버 | 미착수 |
| AI-9 (9단계) | 근거 있는 보고서·항목별 승인/수정/거절/보류 이력·이전 보고서 비교·엑셀, 보관(U-06) | 미착수 |

**로드맵과 이 설계의 차이 (범위 변경 아님, 결정 요청)** :
- 로드맵 6단계의 '실행 단위 프로젝트/테이블'·'수동 실행' 중 **수동 실행과 실행 이력의 일부는 8단계와 겹친다**. AI-6 이 결과 저장·상태를 하려면 최소한의 실행 단위(수동 전체 실행)가 필요하다 → AI-6 = 수동 전체 실행 + 실행 기록, AI-8 = 정기 실행·증분·테이블 단위·재시도·스케줄 (AI6-D07). 기능을 옮기는 것이 아니라 경계를 명시하는 것이다.
- 로드맵 6단계 규칙 목록 중 **M-5 에 없는 것**(원본 COMMENT 누락, 업무 설명 누락·짧음, 동일 컬럼명 타입 불일치, FK 컬럼 타입 불일치, 승인된 설명과 다른 표준 용어)은 새 규칙이다. 한 번에 넣지 않고 단계로 나눌 것을 권장한다 (8장, AI6-D03).
- '예외 처리' 상태는 로드맵 완료 조건이므로 **빼지 않는다** — 권한·사유·만료 규칙만 결정한다 (AI6-D12).

## 4. 모듈 구조 · 의존성 [코드]

- 방향 : `common ← core ← (system, operation, data, source) ← app`, `data ← ai ← app` (`lexorma-ai/build.gradle` `api project(':lexorma-data')`). 중간 4개 모듈은 서로 모르고, **data → ai 금지** (`ModuleBoundaryTest` — data 의 금지 접두어 system·operation·source·ai).
- 현재 위치 :

| 기능 | 위치 |
|---|---|
| 규칙 정의·판정 (`StandardizationRule`, `Finding`, `FindingFingerprint`, `StandardNames`, `TableNameRules`, `TermDuplicateRules`, `ColumnNameSuggestions`) | `lexorma-data` `dictionary/quality/` |
| 진단 서비스 (`StandardizationQualityService`, `TermQualityService`, `IdentifierQualityService`, `ColumnNameSuggestionService`) — 모두 `@Transactional(readOnly = true)` | `lexorma-data` `dictionary/service/` |
| 진단 조회 SQL (QueryDSL DTO 투영, 엔티티 없음) | `dictionary/repository/DictionaryUsageRepository`, `table/repository/DdlIdentifierQueryRepository` |
| 식별자 규칙 | `lexorma-data` `table/ddl/DbIdentifierRules`·`DbIdentifierCheck` |
| API | `dictionary/controller/StandardizationQualityRestController` (`/api/standardizationQuality`, 클래스 VIEWER) |
| 화면 | `lexorma-web/src/features/quality/` (`/standardizationQuality`, 메뉴 `standardization-quality` VIEWER, 그룹 `data-quality`) |
| 그 밖의 진단 (별도 체계) | 물리 구조 진단 `physical/diagnostic/ModelStructureDiagnosticService` (FK·인덱스 8 종), 매핑 정합성 `mapping/service/ModelMappingQualityService`·`ModelStructureQualityJudge` (MATCH/MISMATCH/NOT_COMPARABLE/UNMAPPED), 원본 비교 `sourcemapping/compare` — 모두 `lexorma-data`, 요청 때 계산, 저장 없음 |
| AI Tool | `lexorma-ai` `tool/MetadataToolset` (13개, 품질 Tool 없음), MCP `mcp/service/McpServerService` (11 + 2) |
| 권한·트랜잭션 | 클래스·핸들러 `@PreAuthorize`, 프로젝트 = `AccountDto.getProjectId()`, `ExecutionContextVerifier`, `ProjectWriteGuard` (core) |

**배치 권장 [제안]** : 진단 엔진·결과 저장·실행·규칙 설정은 **`lexorma-data`** (새 패키지, 예 `data/quality/…` 또는 기존 `dictionary/quality` 옆) 에 둔다. 이유 : 규칙·조회가 모두 data 에 있고, AI Provider 와 무관하게 실행돼야 하며(로드맵 원칙), ai → data 방향 그대로 AI-7 이 결과를 읽을 수 있다. `lexorma-ai` 배치는 AI Provider 설정과 진단 실행을 엮을 위험·data 의 판정을 ai 로 옮겨야 하는 비용이 있어 권장하지 않는다. **새 Gradle 모듈** (예 `lexorma-quality`) 은 data 에 의존해야 하고 다시 ai 가 그 모듈을 읽어야 해 얻는 것(경계 명확)보다 빌드·경계 시험 추가 비용이 크다 — 지금은 권장하지 않는다 (AI6-D02).

## 5. M-5 기존 구현 [코드]

| 구성 | 파일 (`lexorma-data/src/main/java/com/lexorma/data/…`) | 입력 → 출력 | 재사용 | 변경 필요 |
|---|---|---|---|---|
| `StandardizationRule` enum | `dictionary/quality/StandardizationRule.java` | 규칙 코드 · `TargetType {TABLE, COLUMN, TERM, WORD, IDENTIFIER}` · `Severity {ERROR, WARNING, INFO}` | 그대로 (규칙 레지스트리) | 새 규칙 추가 시 값 추가 (코드 변경 = 배포) |
| `Finding` record | `dictionary/quality/Finding.java` | `(rule, List<Long> targetIds, actualValue, expectedValue, Map<String,String> evidence, fingerprint)` | 그대로 (저장 단위) | 없음 — 식별자 진단도 `Finding` 을 만들도록 맞추는 것만 필요 |
| `FindingFingerprint` | `dictionary/quality/FindingFingerprint.java` | `of(projectId, rule, targetIds, keys)` → `v1:` + SHA-256 | 그대로 (12장) | 용어 그룹 구성 결정에 따라 (AI6-D11) |
| `TableNameRules` | `dictionary/quality/TableNameRules.java` | 테이블 행·단어 약어 상태 → `TableIssue(table, finding, tokens)` | 그대로 | 없음 |
| `TermDuplicateRules` | `dictionary/quality/TermDuplicateRules.java` | 용어·단어·이음동의어·금칙어 → `TermGroup(finding, groupKey, termIds, notes)` (해시 그룹, O(N)) | 그대로 | 없음 |
| 컬럼 3종 판정 | `dictionary/service/StandardizationQualityService` (`issueTypes`·`finding(projectId, issue)`·`compareDomain`) | 컬럼 행 → `Issue` + `Finding` | 판정 함수 재사용 | 판정을 '프로젝트 전체 Finding 목록' 으로 꺼내는 메서드 분리 (내부 리팩터링) |
| 식별자 판정 | `dictionary/service/IdentifierQualityService` + `table/ddl/DbIdentifierCheck` | 프로젝트 모델 → `DbIdentifierCheck.Finding` (별도 클래스) + fingerprint 직접 계산 | 판정 재사용 | `quality.Finding` 으로 바꾸는 변환 추가 |
| 조회 | `DictionaryUsageRepository.findQualityColumns/Tables/WordAbbreviations/Terms/TermWords/Words/TermSynonyms/WordSynonyms/RestrictedWords`, `DdlIdentifierQueryRepository.model` | QueryDSL DTO 투영 (모델 4문 · 용어 6~7문 · 식별자 6문), 데이터 양과 무관한 SQL 수 | 그대로 | 없음 (대량은 16장) |
| API | `StandardizationQualityRestController` — `/summary`·`/data`(옛 컬럼 3종)·`/model/*`·`/terms/*`·`/domains/duplicates`·`/identifiers/*`·`/columns/{id}/suggestions`, VIEWER | 요청마다 전체 계산 후 Java 에서 걸러·정렬·페이징 | 유지 (실시간 '현재 상태' 보기) | 저장 결과 API 는 별도 경로 (24장) |
| 화면 | `features/quality/StandardizationQualityPage.tsx` (탭 model·terms·identifiers) | 위 API | 확장 대상 (19장) | 탭·상태 열 추가 |
| 테스트 | `StandardizationFindingGoldenTest`(`quality-golden/findings-v1.txt` 99줄 — 식별자 제외), `TableNameRulesTest`, `TermDuplicateRulesTest`, `DbIdentifierRulesTest`/`CheckTest`, `StandardizationQuality*IntegrationTest`, `SmokeTest.standardizationQuality*`(예제 0건), `StandardizationQualityPerformanceTest`(opt-in) | | 그대로 | 저장·상태 테스트 추가 |

**복제하지 않는다** : 저장 진단은 위 판정 함수가 만든 `Finding` 을 그대로 받아 저장한다. 같은 규칙을 SQL·Java 두 벌로 쓰지 않는다.

## 6. 현재 품질 진단 규칙 전체 목록 [코드]

### 6.1 `StandardizationRule` (16개, 심각도 = 확정 값 D8)

| 규칙 ID | 규칙명 | 대상 | 심각도 | 검사 조건 | 구현 | AI-6 재사용 |
|---|---|---|---|---|---|---|
| `TERM_NOT_LINKED` | 표준 용어 미연결 | COLUMN | WARNING | 컬럼의 용어 없음 (이 경우 다른 컬럼 규칙 건너뜀) | 완료 | 예 |
| `COLUMN_NAME_MISMATCH` | 컬럼명 불일치 | COLUMN | ERROR | 용어 약어(trim, 비어 있지 않음) ≠ 컬럼명 (trim, 대소문자 무시) | 완료 | 예 |
| `DOMAIN_DEFINITION_MISMATCH` | 도메인 정의 불일치 | COLUMN | WARNING | 프로젝트 DBMS 로 바꾼 타입·길이·정밀도·소수 자릿수 중 도메인이 정한 것이 다름 | 완료 | 예 |
| `TABLE_PREFIX_MISSING` | 테이블 접두어 누락 | TABLE | ERROR | 접두어가 있는데 테이블명이 그것으로 시작하지 않음 (VIEW 제외) | 완료 | 예 |
| `TABLE_WORD_NOT_STANDARD` | 비표준 단어 약어 | TABLE | ERROR | 접두어 뺀 `_` 조각 중 승인된 단어 약어가 아닌 것(미등록·미승인·빈 조각) | 완료 | 예 |
| `TERM_ABBR_DUPLICATE` | 약어·도메인 중복 | TERM(그룹) | ERROR | 약어 키 + 도메인 ID 같은 용어 2개 이상 | 완료 | 예 |
| `TERM_NAME_DUPLICATE` | 용어명 중복 | TERM(그룹) | ERROR | 정규화 용어명(공백·`_`·대소문자 무시) 같은 용어 2개 이상 | 완료 | 예 |
| `TERM_ABBR_SHARED` | 같은 약어 다른 도메인 | TERM(그룹) | WARNING | 같은 약어 키에 도메인 2종 이상 | 완료 | 예 |
| `TERM_SAME_WORDS` | 같은 단어 구성 | TERM(그룹) | INFO | 단어 ID 다중집합 같음 (순서·이름 모두 같으면 제외) | 완료 | 예 |
| `TERM_SYNONYM` | 이음동의어 유사 | TERM(쌍·그룹) | INFO | 용어명 = 다른 용어의 이음동의어, 또는 단어 이음동의어 치환 뒤 구성 같음 | 완료 | 예 |
| `TERM_RESTRICTED_WORD` | 금칙어 사용 | TERM | WARNING | 용어 단어 조각 = 다른 단어의 금칙어 | 완료 | 예 |
| `IDENTIFIER_LENGTH_EXCEEDED` | 식별자 길이 초과 | IDENTIFIER | ERROR | 스키마·테이블·컬럼 이름 > DBMS 기준 (PG 63B·Oracle 30B·MySQL/MariaDB 64자) | 완료 (M-7) | 예 |
| `IDENTIFIER_INVALID_CHARACTER` | DBMS 허용 문자 위반 | IDENTIFIER | ERROR | 따옴표 없는 식별자 문법 위반 | 완료 | 예 |
| `IDENTIFIER_NOT_RECOMMENDED` | 권장 형식 아님 | IDENTIFIER | WARNING | DBMS 는 허용, Lexorma 권장 `^[A-Za-z_][A-Za-z0-9_$]*$` 아님 | 완료 | 예 |
| `CONSTRAINT_NAME_TRUNCATED` | 제약조건명 잘림 | IDENTIFIER | WARNING | 생성되는 `PK_/UK_/FK_` 이름이 길이 기준 초과 | 완료 | 예 |
| `CONSTRAINT_NAME_COLLISION` | 제약조건명 충돌 | IDENTIFIER | ERROR | 잘린 최종 이름이 같은 이름 공간에서 겹침 | 완료 | 예 |

`TargetType.WORD` 는 정의만 있고 규칙이 없다 (M-5 가 AI-6 용으로 남김). 표준 도메인 중복(`/domains/duplicates`, DICT-2)은 **규칙이 아니다** (enum·심각도·fingerprint 없음).

### 6.2 다른 진단 체계 (`StandardizationRule` 밖)

| 체계 | 코드 | 심각도 | 대상 | 비고 |
|---|---|---|---|---|
| 물리 구조 진단 (DEC-09·12) | `FK_TARGET_NOT_FOUND`·`FK_TARGET_AMBIGUOUS`·`FK_TARGET_NO_PK`·`FK_TARGET_COMPOSITE_PK`·`FK_RELATION_NOT_KEY` / `INDEX_SAME_DEFINITION`·`INDEX_SAME_AS_PK`·`INDEX_PREFIX_OF_OTHER` | 자체 `ERROR`·`WARNING` | 컬럼·관계·인덱스 | fingerprint 없음, id = `type:key` |
| 매핑 정합성 (M-7) | 상태 `MATCH`/`MISMATCH`/`NOT_COMPARABLE`/`UNMAPPED` × 유형 12 (MAPPING·TERM·NAME·DATA_TYPE·LENGTH·REQUIRED·IDENTIFIER·INHERITANCE·RELATIONSHIP·ALTERNATE_KEY·PHYSICAL_FK·PHYSICAL_UNIQUE) + 세부 코드 40여 개 | 없음 (상태) | 논리 모델 단위 | '문제' 가 아니라 비교 결과 — 심각도 없음 |
| 원본 비교 (M-4d) | `SAME`·`ADDED_IN_SOURCE`·`ADDED_IN_MODEL`·`MODIFIED`·`RENAME_CANDIDATE`·`CONFLICT`·`UNKNOWN` | 없음 | 원본 테이블 단위 | 마지막 수집 기준 |
| 대시보드 완전성 수치 | `tablesWithoutComment`·`columnsWithoutComment`·`tablesWithoutBizDescription`·`columnsWithoutBizDescription` | 없음 | 테이블·컬럼 | 규칙이 아니라 집계 SQL (`DashboardRepositoryImpl`) |

### 6.3 대상별 진단 존재 여부

| 대상 | 현재 진단 |
|---|---|
| 표준 도메인 | 중복 이름 보고(규칙 아님), 컬럼 쪽 `DOMAIN_DEFINITION_MISMATCH` 만 |
| 표준 단어 | 없음 (`TargetType.WORD` 비어 있음) — 테이블 약어 판정의 기준으로만 쓰임 |
| 표준 용어 | 6 규칙 (그룹) |
| 물리 스키마 | 식별자 규칙 (기본 스키마 제외) |
| 물리 테이블 | 2 규칙 + 식별자 + 물리 구조(인덱스) |
| 물리 컬럼 | 3 규칙 + 식별자 + 물리 구조(FK 대상) |
| 논리 모델·엔터티·속성·식별자·관계 | **규칙 진단 없음** — 등록 검증 + 매핑 정합성 비교에서만 |
| 논리·물리 매핑 | 매핑 정합성 (상태 기반) |
| 원본 DB 매핑 | 원본 비교 (상태 기반) |

## 7. 현재 진단의 한계 · 기존 기능 재사용 범위

- **저장 없음** : 모든 진단이 요청 때 전체를 다시 계산한다. 이력·추이·신규/해결 구분·예외 처리가 불가능하다.
- **메모리** : 컬럼·테이블 진단은 프로젝트 전체를 메모리에 올린 뒤 Java 로 걸러·정렬·페이징한다. 컬럼 10만 기준 요청당 할당 600~740MB(가비지), PostgreSQL 0.66초 [문서 M-5 8.3] — 요청마다 반복.
- **규칙 사용 여부 없음** (U-16 미구현), 심각도는 enum 고정.
- **체계가 셋** (M-5 규칙 · 물리 구조 · 매핑/원본 비교) — 결과 형식·코드 체계가 다르다.
- **식별자 진단은 `quality.Finding` 을 만들지 않는다** (fingerprint 만).
- 재사용 : 판정 함수·`Finding`·fingerprint·조회 SQL·권한 체계·AI-5 실행기 패턴·`ProjectWriteGuard`·프로젝트 삭제 정리 구조·그리드 페이징 규칙(`PageableUtil`, `list-query-policy.md`)·화면 탭 구조.

## 8. AI-6 신규 기능 후보

### 8.1 분류별 판단

| 분류 | 후보 | 판단 [제안] |
|---|---|---|
| 표준화 | M-5 16 규칙 | **1단계 포함** (그대로 재사용) |
| 메타데이터 완전성 | 테이블 코멘트 누락, 컬럼 설명 누락, 테이블·컬럼 업무 설명 누락 (대시보드가 이미 세는 4종) | **새 규칙 후보 — 1단계 포함 권장** : 판정이 단순하고(빈 값), 대시보드 수치와 같은 기준으로 맞출 수 있다. 심각도는 새로 정해야 한다 (AI6-D04) |
| 메타데이터 완전성 | 업무 설명 '지나치게 짧음' | 보류 권장 — 기준 길이가 업무 정책이고 오탐이 많다 |
| 물리 모델 | 물리 구조 진단 8종 (FK·인덱스) | **2단계 권장** — 이미 판정이 있으나 `Finding`·fingerprint 가 없다. 어댑터(코드 → 규칙 코드)와 fingerprint 정의가 필요하다 |
| 물리 모델 | FK 컬럼 타입 불일치, 같은 컬럼명 다른 타입·길이 | 새 판정 — 2단계 이후 |
| 논리 모델 | 엔터티·속성 설명 누락 등 | 후속 (지금 논리 규칙 체계 없음) |
| 논리·물리 정합성 | 매핑 정합성 결과를 Finding 으로 | **AI-6 밖 권장** — 상태 비교(심각도 없음)라 '문제' 로 옮기면 의미가 바뀐다. 필요하면 MISMATCH 만 별도 결정 |
| 원본 DB 정합성 | 원본 COMMENT 누락·차이 | 후속 — 원본은 수집 시점 데이터라 해결 판정이 수집과 엮인다 |

### 8.2 진단과 업무 유효성 검증의 구분

- 업무 검증(등록·수정 거절)은 **그대로** 둔다. 진단에서 문제가 있다고 조회·수정·승인을 막지 않는다 (M-5 D5 와 같다).
- 이미 저장할 수 없는 상태(예 : 같은 테이블 같은 컬럼명 — `UX_COL_01`)를 규칙으로 다시 만들지 않는다. 다만 **예전 데이터가 남을 수 있는 규칙**(DB 제약이 없는 용어·도메인 중복 — M-5 5차 '기존 중복은 보고만')은 진단으로 유지한다.

## 9. 규칙 기반 진단과 AI 의 구분

- **AI-6 (결정적)** : 같은 데이터·같은 규칙 설정 → 같은 결과. AI Provider 없이 실행. 심각도는 규칙이 정한다. 결과 = 규칙 ID + 실제 메타데이터 근거(`evidence`). AI 는 결과·상태를 바꾸지 않는다.
- **AI-7 (확률적)** : 저장된 Finding 을 입력으로 설명·개선 방향·요약·우선순위 제안, 의미 기반 새 후보. 결과는 별도 저장(제안), 승인 시에만 데이터 사전 반영.
- 경계 데이터 : AI-6 이 저장하는 `evidence`·현재 값·기대 값·대상 이름 스냅샷이 AI-7 의 근거가 된다 (21장).

## 10. 프로젝트별 규칙 관리 (U-16) [제안]

### 10.1 저장 방식 비교

| 항목 | A. 전체 규칙 설정 행 저장 | B. 기본값(코드) + 프로젝트 변경값만 저장 |
|---|---|---|
| 데이터 중복 | 프로젝트 × 규칙 수 행 | 바꾼 것만 |
| 새 규칙 추가 | 모든 프로젝트에 행 추가 마이그레이션 필요 | 코드의 기본값이 바로 적용 |
| 조회 | 단순 (행 그대로) | 기본값 + 변경값 합치기 (메모리에서 간단) |
| 프로젝트 생성 | 행 생성 필요 (생성 경로 수정) | 없음 |
| 변경 추적 | 행마다 감사 칼럼 | 같음 (변경 행에만) |
| 동시성 | 행 단위 | 행 단위 (UNIQUE 프로젝트+규칙) |
| 유지보수 | 규칙 제거 시 행 정리 필요 | 코드에 없는 규칙의 행은 무시 |

**권장 B** : `TB_…_RULE_STNG` (프로젝트, 규칙 코드, 사용 여부, 감사 칼럼), UNIQUE(프로젝트, 규칙). 기본값은 enum(모든 규칙 사용) — 새 규칙의 기본 상태는 규칙 정의에 둔다 (예외적으로 INFO 규칙을 기본 꺼짐으로 둘지는 AI6-D05).

### 10.2 정책 후보

| 항목 | 권장 |
|---|---|
| 기본 상태 | 모든 규칙 사용 (기존 화면과 같은 결과) |
| 기존·새 프로젝트 | 같은 기본값 (행 없음) |
| 새 규칙 | 규칙 정의의 기본값 (사용) |
| 끈 규칙의 기존 Finding | **해결로 바꾸지 않는다.** 상태는 그대로 두고 목록·집계에서 '규칙 꺼짐' 으로 숨긴다 (다음 실행은 그 규칙을 돌리지 않으므로 '미발견' 판정에서 제외) |
| 다시 켬 | 다음 실행에서 정상 판정 (그대로 있으면 유지, 없어졌으면 해결) |
| 통계 | 꺼진 규칙 제외 (대시보드·요약) — 별도 표시 '꺼진 규칙 n개' |
| 변경 권한 | 프로젝트 관리자 또는 ADMIN (`ProjectAuthority.canManage` — AI·MCP 프로젝트 설정과 같다) |
| 변경 이력 | 감사 칼럼(누가·언제)만, 별도 이력 테이블 없음 — 필요하면 후속 |
| 프로젝트별 심각도 변경 | **도입하지 않음** (D8 확정 값, 같은 Finding 의 의미가 프로젝트마다 달라짐) |

## 11. 진단 실행 구조 [제안]

### 11.1 실행 범위

| 범위 | 필요성 | 조회 범위 | 비용 | 생명주기 판정 | 권장 |
|---|---|---|---|---|---|
| 프로젝트 전체 (켠 규칙 전부) | 높음 — 기준 상태 | 전체 | 컬럼 10만 ≈ 1초 판정 + 저장 | **완전** — 미발견 = 해결 | **AI-6 지원** |
| 규칙 계열 (표준화 모델 / 용어 / 식별자 / 완전성) | 중간 | 계열 데이터 | 작음 | 계열 안에서 완전 | AI-6 선택 (내부 단위는 계열로 설계, API 는 전체만 먼저) |
| 논리·물리·개별 모델 | 낮음 (지금 규칙이 프로젝트 단위) | — | — | 범위 정의 필요 | 후속 |
| 개별 규칙 | 낮음 | 규칙 데이터 | 작음 | 규칙 안 완전 | 후속 |
| 테이블 단위·변경분 증분 | 중간 (로드맵 8단계) | 영향 대상 | 작음 | 간접 영향 판단 어려움 (17장) | **AI-8** |

### 11.2 실행 방식

| 항목 | A. 동기 HTTP | B. 앱 내부 비동기 | C. B + DB 실행 상태 |
|---|---|---|---|
| HTTP 시간 | 대량에서 길어짐 | 즉시 응답 | 즉시 응답 |
| 상태 조회 | 불필요 | 메모리만 (재시작 유실) | DB |
| 재시작 | 요청 실패 | 유실 | `INTERRUPTED` 표시 (AI-5 와 같다) |
| 중복 실행 방지 | 요청 직렬화 필요 | 메모리 | DB (부분 UNIQUE 또는 프로젝트 행 잠금) |
| 취소 | 불가 | 가능 | 가능 |
| 구현 | 작음 | 중간 | 중간 (AI-5·원본 수집 패턴 재사용) |

**권장 C** : U-15 에서 정한 AI-5 방식(서버 안 전용 실행기 — 빈 아님, 프로젝트당 진행 중 하나, 재시작 시 INTERRUPTED, 자동 재개 없음)을 그대로 쓴다. 판정 자체는 컬럼 10만에서 1초 안팎이라 A 도 가능하지만, 결과 저장(수만~수십만 행)·AI-8 배치가 같은 경로를 써야 하므로 C 로 시작한다. 새 외부 큐·Redis·Spring Batch 없음.

### 11.3 실행 이력 (실행 단위 테이블)

후보 항목 : 실행 ID · 프로젝트 · 실행 유형(MANUAL — AI-8 에서 SCHEDULED) · 범위(PROJECT, 계열 목록) · **적용 규칙 집합 (실행 시점의 켠 규칙 코드 목록 + 엔진 버전·fingerprint 버전)** · 요청자 · 상태 · 시작·종료 · 대상 수(계열별) · 발견 수 · 신규 · 유지 · 해결 · 재발 · 실패 코드·내용.

**최소 상태 집합 권장** : `PENDING` · `RUNNING` · `COMPLETED` · `FAILED` · `INTERRUPTED` (재시작) — `CANCELLED` 는 사용자 취소를 AI-6 에 넣을 때만 (판정이 짧아 취소 필요성이 낮다 — AI6-D08). AI-5 의 `PARTIAL_FAILED` 는 넣지 않는다 : 부분 결과는 확정하지 않는다 (14장).

## 12. 진단 결과 저장 구조 [제안]

| 항목 | A. 실행별 전체 결과 | B. 현재 상태만 | C. 현재 Finding + 실행 기록 분리 |
|---|---|---|---|
| 저장 공간 | 실행 수 × Finding 수 (컬럼 10만 프로젝트에 실행마다 수만 행) | 최소 | 현재 + 실행 집계 + (선택) 상태 변경 이력 |
| 조회 성능 | 최신 실행 기준 조회 — 인덱스 필요 | 가장 좋음 | 좋음 |
| 추적 | 완전 | 최초·마지막 발견만 | 최초·마지막·해결·재발 + 실행별 집계 |
| 신규·유지·해결 | 두 실행 비교 | 갱신 시 판정 | 갱신 시 판정 + 실행에 집계 |
| 재발 | 비교로 | 해결 시각 + 재발견 | 같음 + 재발 횟수 |
| 실패 처리 | 실패 실행 결과 무시 | 확정 전 반영 금지 필요 | 같음 (14장) |
| 증가량 | 큼 (보관 정책 필수) | 작음 | 작음 ~ 중간 |

**권장 C** : 현재 Finding 테이블(fingerprint 당 한 행, 상태 갱신) + 실행 테이블(집계) + **상태 변경 이력은 선택** (AI6-D14). 실행별 전체 스냅샷은 기본으로 두지 않는다.

### 12.1 Finding 저장 항목

| 항목 | 필요 | 출처 |
|---|---|---|
| 프로젝트 | 필수 | 실행 |
| 규칙 코드 · 대상 유형 | 필수 | `Finding.rule` |
| 심각도 | 저장 권장 (그 시점 값 — 규칙 값이 바뀌어도 이력 해석 가능) | 규칙 |
| fingerprint | 필수, UNIQUE(프로젝트, fingerprint) | `Finding.fingerprint` |
| 대상 ID 목록 | 필수 (그룹은 여러 개) — 대표 ID(첫 ID) 칼럼 + 전체 목록 | `targetIds` |
| 대상 이름 스냅샷 (스키마·테이블·컬럼·용어명) | 권장 — 대상이 지워져도 표시, 목록 검색 | 조회 행 |
| 현재 값 · 기대 값 · 근거 | 필수 (근거 = 키 순서 고정 Map) | `Finding` |
| 메시지 | **저장하지 않음** — 화면 언어로 규칙 + 근거에서 다시 만든다 (번역·문구 변경 대응) | — |
| 상태 · 최초 발견 · 마지막 발견 · 해결 · 재발 횟수 · 최초/마지막 실행 ID · 해결 사유 · 예외 정보 | 필수 (13장) | 판정 |

## 13. FindingFingerprint v1 분석 [코드 + 제안]

- 정규 문자열 : `v1 ␟ P{프로젝트} ␟ 규칙 ␟ 대상 유형 ␟ 정렬한 대상 ID(,) ␟ 핵심 값…` → `v1:` + SHA-256 (소문자 16진 64자). 메시지·심각도·현재 값·화면 순서·대상 이름은 **넣지 않는다** (식별자 규칙은 이름이 핵심 값). DBMS 는 컬럼·테이블·용어 규칙에 넣지 않고 식별자 규칙에만 넣는다.

| 시나리오 | 결과 | 평가 |
|---|---|---|
| 테이블명 변경 (`TERM_NOT_LINKED` 등 컬럼 규칙) | 같다 (ID 기준) | 좋음 |
| 테이블명 변경 (`TABLE_WORD_NOT_STANDARD`) | 위반 조각이 바뀌면 다르다 → 옛것 해결 + 새것 신규 | 의도대로 (다른 문제) |
| 컬럼명 변경 (`COLUMN_NAME_MISMATCH`) | 같다 (핵심 값 = 기대 이름) | 좋음 |
| 연결 용어 변경 | 기대 이름이 바뀌면 다르다 | 합리적 |
| 업무 설명 변경 | 무관 | 좋음 |
| 심각도·메시지 변경 | 같다 | 좋음 |
| 대상 삭제 후 같은 이름으로 재생성 | 새 ID → 다르다 | 맞음 (다른 객체) — 옛것은 '대상 삭제' 로 닫아야 한다 (14장) |
| 프로젝트 DBMS 변경 (식별자) | 다르다 | 맞음 (기준이 다르다) |
| 같은 대상·같은 규칙 여러 건 | 컬럼·테이블은 규칙당 1건, 용어·식별자는 핵심 값으로 구분 | 문제 없음 |
| **용어 그룹 구성원 증감** (`TERM_*` 그룹) | 대상 ID 목록이 바뀌어 **다르다** → 기존 그룹 해결 + 새 그룹 신규, 예외 처리도 풀린다 | **문제** — '중복 용어가 하나 늘었다' 가 '해결 + 신규' 로 보인다 |
| 충돌 | SHA-256 — 실질적으로 없음 | 좋음 |

**판단** : v1 은 컬럼·테이블·식별자 규칙에 그대로 적합하다. 용어 그룹 규칙은 '그룹 = 같은 문제' 로 보려면 대상 ID 를 빼고 그룹 키(이미 핵심 값에 있음)만으로 식별해야 한다 — 그러면 그룹 구성원 변화는 같은 Finding 의 대상 목록 갱신이 된다. 바꾼다면 **v2 는 용어 그룹 규칙에만** 적용(접두어 올림)하고, 저장된 결과가 아직 없으므로 이전 대응은 필요 없다 (PG-D31). 결정 전 구현하지 않는다 (AI6-D11). 대안 : v1 유지 + 화면에서 '구성원 변경' 을 해결/신규로 그대로 보여 준다.

## 14. Finding 생명주기 [제안]

### 14.1 두 개념 분리 (권장)

- **현재 상태 `STTS`** : `OPEN`(미해결) · `RESOLVED`(해결) · `EXCLUDED`(예외 처리 — 로드맵 완료 조건) .
- **이번 실행의 변화 (실행 집계 + Finding 의 마지막 변화)** : `NEW` · `PERSISTING` · `RESOLVED` · `REOPENED`.
- 단일 칼럼에 '신규·유지·해결' 을 넣으면, 다음 실행 전까지 '신규' 가 상태처럼 남고(언제 기준인지 모호), 예외 처리와 변화가 섞이며, 재발을 표현할 수 없다.

### 14.2 상태 전이

| 이전 | 이번 실행 (그 규칙·대상 범위를 정상 판정함) | 결과 |
|---|---|---|
| 없음 | 발견 | `OPEN`, 변화 NEW |
| OPEN | 같은 fingerprint 발견 | OPEN, PERSISTING, 마지막 발견 갱신 |
| OPEN | 미발견 + 대상 존재 | `RESOLVED` (사유 FIXED), 변화 RESOLVED |
| OPEN | 미발견 + 대상 삭제 | `RESOLVED` (사유 **TARGET_DELETED**) — 실제 개선과 구분 |
| RESOLVED | 다시 발견 | OPEN, 변화 REOPENED, 재발 횟수 +1 |
| EXCLUDED | 다시 발견 | **EXCLUDED 유지** (신규로 나타나지 않음 — 로드맵), 마지막 발견 갱신 |
| EXCLUDED | 미발견 | EXCLUDED 유지 또는 RESOLVED — AI6-D12 |
| 모든 상태 | 규칙 꺼짐 | **변화 없음** (판정 안 함), 목록·집계에서 숨김 |
| 모든 상태 | 실행 실패·중단·취소 | **변화 없음** (결과 확정 안 함) |

### 14.3 잘못된 해결 판정 방지

'이번 결과에 없다' 를 해결로 보는 조건 (모두 만족) :
1. 같은 프로젝트, **COMPLETED 실행**.
2. 그 Finding 의 **규칙이 이번 실행의 적용 규칙 집합에 있음** (꺼진 규칙·이번에 돌리지 않은 계열 제외).
3. 그 규칙 계열을 **오류 없이 끝까지 판정**함 (계열 단위 성공 기록).
4. 대상이 이번 실행 범위 안 (전체 실행이면 항상).
5. 결과를 자르지 않음 — **저장 판정은 페이지·상한 없이 전체** (화면 페이징과 분리).
6. 권한 범위 제한 없음 — 진단은 사용자 권한이 아니라 프로젝트 전체를 읽는다 (조회 권한은 결과를 볼 때만 적용).

사유 구분 : `FIXED`(대상 있음, 문제 없음) · `TARGET_DELETED`(대상 ID 가 없음 — 판정 때 대상 존재 여부를 함께 확인) · 규칙 꺼짐·범위 제외·검사 실패·미실행은 **해결이 아니라 '변화 없음'** 이다.

## 15. 결과 이력 · 재발 관리 [제안]

| 수준 | 답할 수 있는 것 | 비용 |
|---|---|---|
| ① Finding 의 최초·마지막 발견·해결 시각 + 재발 횟수 + 실행별 집계 | 처음/마지막 확인, 해결 시점, 재발 여부, 품질 추이(실행 집계), 규칙별 증가(실행 × 규칙 집계 필요) | 작음 |
| ② ① + 상태 변경 이력 (신규·해결·재발·예외 처리·예외 해제 행) | 위 + 언제 몇 번 해결·재발했는지, 예외 처리 감사 | 변화 수만큼 |
| ③ 실행별 전체 스냅샷 | 과거 임의 시점 목록 재현 | 큼 |

**권장 : ① + 규칙별 실행 집계(실행 × 규칙 × 변화 건수) + 예외 처리 이력만 ②** — 예외 처리는 사람의 판단이라 감사 기록이 필요하다. ③ 은 AI-9 보고서가 요구할 때 다시 본다. 보관 기간은 DEC-14 에 따라 지금은 자동 삭제하지 않고 후속으로 정한다 (AI6-D15).

## 16. 실패 · 취소 · 원자성 [제안]

| 상황 | 처리 |
|---|---|
| 판정 중 예외 (조회 실패 등) | 실행 `FAILED`, 오류 코드·요약만 저장, Finding 변화 없음 |
| 결과 반영 중 예외 | 반영 트랜잭션 전체 롤백 → Finding 은 이전 정상 상태 그대로, 실행 FAILED (별도 짧은 트랜잭션) |
| 서버 종료·DB 연결 끊김 | 반영 트랜잭션 미커밋 → 롤백, 다음 기동 때 PENDING/RUNNING → `INTERRUPTED` (AI-5 `ApplicationReadyEvent` 와 같다) |
| 중복 실행 | 시작 시 거절 (19장) |
| 늦게 끝난 옛 실행 | 반영 직전에 '이 실행이 프로젝트의 가장 최근에 시작된 실행인가' 확인, 아니면 반영하지 않고 SUPERSEDED 성격의 실패 코드 |

**확정 방식 비교** : 단일 트랜잭션 확정 / 임시 결과 저장 후 확정 / 실행 ID 단계 저장 후 공개 / 현재·실행 결과 분리.
**권장 : 판정(읽기 전용 트랜잭션) → 메모리의 Finding 목록 → 단일 쓰기 트랜잭션으로 현재 Finding UPSERT·해결 처리·실행 집계·COMPLETED 를 한 번에 확정.** 컬럼 10만 기준 Finding 약 11만 행의 갱신이라 몇 초 안의 트랜잭션으로 추정한다 [추정 — 측정 필요]. 이보다 커지면(백만 단위) 임시 테이블 단계 확정으로 바꾼다 (16장 대량, 측정 뒤 결정).

## 17. 동시성 · 데이터 변경 [제안]

- **판정 읽기** : 읽기 전용 트랜잭션 하나를 `REPEATABLE READ` 로 열어 진단에 쓰는 조회(모델 4 · 용어 7 · 식별자 6문)를 **한 시점의 MVCC 스냅샷** 에서 읽는다. 행 잠금을 잡지 않으므로 편집을 막지 않는다. 진단 중 수정은 이번 결과에 반영되지 않을 뿐 다음 실행에서 반영된다 (결과에 '판정 시점' = 실행 시작 시각 기록).
- **장시간 트랜잭션 영향** : 판정 읽기는 컬럼 10만에서 1초 안팎 — VACUUM 지연·스냅샷 유지 영향이 작다. 수십만 이상이면 계열별로 스냅샷을 나눈다 (계열 사이 시점 차이는 허용 — 계열마다 결과가 독립이므로).
- **결과 반영** : 프로젝트 행을 배타로 잡지 않는다 (모든 모델 저장을 막게 된다 — M-8). 대신 실행 테이블의 '진행 중 실행 1개' 제약(부분 UNIQUE, 원본 수집의 `UX_SRC_CLCT_EXCN_03` 패턴) 또는 시작 시 `ProjectWriteGuard.lock`(공유) + 진행 중 확인(AI-5 패턴)으로 직렬화한다. 결과 테이블은 진단만 쓰므로 업무 저장과 행 경합이 없다. 반영 시 `ProjectWriteGuard` 가 프로젝트 행을 공유 잠금 — 프로젝트 삭제와 직렬화된다.
- **규칙 설정 변경 중 실행** : 실행 시작 때 켠 규칙 집합을 실행 행에 고정하고 그 집합으로만 판정·해결한다.
- **진단 중 프로젝트 삭제** : 반영 시 프로젝트 행 공유 잠금 실패 → 롤백, 삭제 정리 목록에 새 테이블 추가 (23장).
- 기존 프로젝트 잠금·NOWAIT 정책은 바꾸지 않는다.

## 18. 대량 데이터 처리 · 성능 검증 계획 [제안]

### 18.1 현재 구현 분석

| 항목 | 현재 [코드·문서] |
|---|---|
| 전체 메모리 적재 | 예 — 컬럼 전체 DTO (`ColumnRow` 22 필드) |
| 규칙별 반복 조회 | 없음 (계열마다 한 번) |
| N+1 | 없음 (SQL 수 고정) |
| Finding 전체 보관 | 예 (요청마다 생성 후 버림) |
| fingerprint 중복 계산 | 요청마다 전체 계산 (SHA-256 × 수만) |
| 영속성 컨텍스트 | 엔티티 없음 (DTO) — 증가 없음 |
| 응답 크기 | 페이지만 응답, 판정은 전체 |
| 정렬 | Java 전체 정렬 |

### 18.2 저장 방식 [제안]

- 판정은 지금처럼 SQL 조회 + Java 판정 (규칙 정의 하나 — SQL 판정 이중 구현 금지).
- 반영은 JDBC 배치(`reWriteBatchedInserts` — DB-1 에서 여러 행 INSERT 로 바뀜 확인) + PostgreSQL `INSERT … ON CONFLICT (프로젝트, fingerprint) DO UPDATE`, 해결 처리는 '이번 실행에서 본 fingerprint 가 아닌 OPEN 행' 갱신 (임시 테이블 또는 `<> ALL(array)` — 측정 뒤 선택). 이것은 모델 엔티티가 아니므로 `ModelChangeRecorder` 우회 문제가 없다 (진단 테이블은 모델 이력 대상이 아님). 단 `ProjectWriteGuard.guard(projectId)` 는 직접 불러야 한다 (JDBC 쓰기 규칙).
- 결과 조회는 DB 에서 걸러·정렬·페이징 (Keyset 은 필요해지면).

### 18.3 검증 규모·측정 항목

| 규모 (컬럼) | 1,000 | 10,000 | 100,000 | 1,000,000 (확장 검토) |
|---|---|---|---|---|
| 용도 | 단위 | 통합 | 성능 (M-5 측정 재사용) | 측정만, 지원 한계로 확정 안 함 |

측정 : 전체·계열별 판정 시간, SQL 수, 조회 행 수, 최대 JVM 힙(테스트 힙 2g 여유 — DB-1 70장), Finding 생성 수, 반영 시간·행 수, 결과 조회 시간(목록·요약), 진단 중 업무 API 응답 시간. 이번 1차에서는 실행하지 않았다.

## 19. 증분 진단 [제안]

| 변경 유형 | 직접 영향 | 간접 영향 | 관련 규칙 | 증분 가능 |
|---|---|---|---|---|
| 표준 도메인 | 도메인 | 그 도메인 용어의 **모든 컬럼** | DOMAIN_DEFINITION_MISMATCH, TERM_ABBR_* | 부분 — 사전 변경은 `TB_MDL_CHG_HSTRY` 에 기록되지 않는다 |
| 표준 단어 | 단어 | 그 약어를 쓰는 **모든 테이블명**, 단어 구성 용어 | TABLE_WORD_NOT_STANDARD, TERM_SAME_WORDS·SYNONYM·RESTRICTED | 어려움 (이력 없음, 간접 범위 큼) |
| 표준 용어 | 용어 | 연결 컬럼, 같은 그룹 용어 | 컬럼 3종, 용어 6종 | 부분 (이력 없음) |
| 테이블 | 테이블 | 그 컬럼(식별자), 제약조건명 | 테이블 2종, 식별자 | 가능 (`PHYSICAL_TABLE` 이력) |
| 컬럼 | 컬럼 | 제약조건명 충돌 | 컬럼 3종, 식별자 | 가능 (`PHYSICAL_COLUMN`) |
| 프로젝트 설정 (접두어·DBMS) | 프로젝트 | **전체** | 테이블 접두어, 식별자, 도메인 비교(DBMS) | 전체 재진단 필요 |
| 논리·매핑·원본 | — | — | AI-6 1단계 규칙 없음 | 해당 없음 |

단순 수정 시각 비교로는 사전 변경의 간접 영향을 찾을 수 없다. 프로젝트 전체 판정이 컬럼 10만 ≈ 1초 [문서] 이므로 **AI-6 은 전체 진단만**, 증분은 AI-8 에서 측정 근거와 함께 다시 본다 (AI6-D16). 데이터 변경 직후 자동 진단은 하지 않는다 (편집 경로 부담·M-8 잠금 영향).

## 20. 프로젝트별 실행 제어 · 권한 [제안]

| 항목 | 권장 |
|---|---|
| 같은 프로젝트 중복 실행 | 진행 중 1개 (거절 409) |
| 다른 프로젝트 | 동시 허용, 서버 전체 실행 스레드 1~2 + 대기열 (AI-5 와 같은 크기 — 설정 키는 결정 시) |
| 취소 | AI-6 에서는 넣지 않음 권장 (짧은 실행) — AI6-D08 |
| 로그아웃 뒤 | 계속 실행 (AI-5 와 같다, 실행 직전 요청자 권한 재확인) |
| 재시작 | INTERRUPTED, 자동 재개 없음 |

**권한 매트릭스 후보 (새 역할 없음)** :

| 기능 | 권장 권한 | 근거 |
|---|---|---|
| 결과·요약·실행 이력 조회 | VIEWER (프로젝트 멤버) | 기존 진단 조회 = VIEWER |
| 전체 진단 실행 | USER | 결과를 바꾸는 쓰기 — 기존 '버전 만들기' 등과 같다 (VIEWER 도 허용할지 결정) |
| 예외 처리·해제 | USER + 사유 필수 (또는 승인 권한자) | AI6-D12 |
| 규칙 설정 변경 | 프로젝트 관리자 / ADMIN | 프로젝트 설정 성격 |
| 실행 취소 | (넣으면) 요청자·프로젝트 관리자 | — |
| Export | VIEWER (넣으면) | 조회와 같다 |
| AI 개선 권고 | AI-7 에서 정함 | — |

보안 : 모든 조회·실행 ID·Finding ID·규칙 설정은 로그인 프로젝트로 범위 고정(다른 프로젝트 ID → 404 처리), 비동기 실행은 `ExecutionContext.async` + 실행 직전 `ExecutionContextVerifier`, 결과에 비밀값 없음(근거는 이름·코드·값 — 원본 VIEW 정의·원본 비밀번호는 진단 대상 아님). `MenuAuthorization.MENU_ROLES` 에 새 버튼 ID 추가 (`UiActionAuthorizationIntegrationTest`).

## 21. UI [코드 + 제안]

### 21.1 현재 화면

`/standardizationQuality` (메뉴 '데이터 품질 관리 › 표준화 품질 진단', `standardization-quality` VIEWER) — 탭 3개 :
- `model` : 컬럼·테이블 요약 카드(클릭 = 필터), 유형·심각도·대상·스키마·승인 필터, 서버 페이징 그리드, [테이블 상세]·[ERD] 이동, 컬럼 [개선 후보] 대화상자.
- `terms` : 요약·규칙 카드, 그룹 그리드(구성원 칩), 그룹 상세 → 표준 용어 이동, 아래 도메인 중복 구역.
- `identifiers` : DBMS 기준, 규칙 카드, 종류·스키마 필터.
- 공통 : 요청마다 실시간 계산, 실행 버튼·결과 시각·상태·Export 없음, fingerprint 비표시.

### 21.2 권장 구성 [제안]

**같은 메뉴 안 확장** (새 메뉴로 같은 진단을 두 번 보여 주지 않는다) :
- 기존 3탭은 **'현재 상태 (실시간)'** 로 그대로 둔다 — 편집 직후 확인용, 실행 없이 바로 보임.
- 새 탭 **'진단 결과'** (저장 결과) : 마지막 진단 시각·상태(진행 중이면 진행 표시) · [진단 실행] 버튼(USER) · 요약(미해결 심각도별, 이번 실행 신규·해결·재발, 규칙별) · 목록(심각도·규칙·대상 유형·대상 이름·현재/기대 값·최초/마지막 발견·상태·이동 링크) · 필터(상태·심각도·규칙·대상 유형·새로 생김) · 예외 처리(사유) · 오래된 결과 안내(마지막 진단 뒤 모델 변경이 있으면 '최신이 아닐 수 있음').
- **'실행 이력'** 탭 또는 같은 탭 아래 구역 : 실행 시각·요청자·범위·상태·소요·신규/유지/해결/재발, 실패 안내.
- **'규칙 설정'** (프로젝트 관리자만 수정, 다른 사람은 읽기) : 규칙명·설명·대상·심각도(읽기 전용)·사용 여부.
- 한국어·영어·라이트·다크·375px 유지, 문구는 규칙 + 근거로 화면에서 만든다 (메시지 저장 안 함).
- 대안 : 기존 탭을 저장 결과로 바꾸기 — 편집 직후 확인이 실행을 기다려야 해 권장하지 않는다 (AI6-D19).

## 22. 대시보드 연계 [코드 + 제안]

현재 `GET /api/dashboard/stats` 는 M-5 컬럼 요약(`selectSummary`, D2)과 설명·업무 설명 누락 수를 **요청 때 계산**한다. 후보 : 미해결 문제 수·심각도 분포·마지막 진단 시각·최근 실행 신규/해결·추이.
권장 : AI-6 범위에서는 **저장 결과 카드 하나**(마지막 진단 시각 + 미해결 ERROR/WARNING/INFO + 최근 신규/해결, 진단 이력이 없으면 '아직 진단하지 않음', 오래되면 안내)만 추가하고, 대시보드 접근 때 진단을 실행하지 않는다. 기존 준수율 카드는 D2 대로 그대로 둔다. 품질 점수는 확정 정책이 없으므로 만들지 않는다. 추이 그래프는 AI-9 (AI6-D20).

## 23. AI 연계 [코드 + 제안]

- 현재 AI Tool 13개·MCP 11+2 에 품질 Tool 없음. Tool 은 `MetadataTools.tool(name, description, record, fn)` → `FunctionToolCallback`, `MetadataToolset.Guarded` 가 컨텍스트 재확인(VIEWER)·읽기 전용 트랜잭션·결과 24,000자 상한·근거 기록을 맡는다. 호출 8회·같은 Tool 4회 상한, 부분 답변·근거 검증(AES).
- 후보 Tool (AI-7 에서 쓰일 것) : 품질 요약 (`getQualitySummary` — 심각도·규칙별 수, 마지막 진단), 규칙·심각도별 Finding 목록 (페이지·limit 20), 특정 테이블·컬럼·용어의 Finding, Finding 근거. 전체 결과를 한 번에 넘기지 않는다.
- 근거 검증 : 새 evidence 유형(예 `FINDING`, 버전 = 마지막 발견 시각)이 필요하다 — `EvidenceCatalog`·`EvidenceType` 확장.
- **권장 : AI-6 에서는 Tool 을 만들지 않고, 결과 조회 서비스(읽기 전용, 프로젝트 범위, 페이지·limit)를 Tool 이 그대로 부를 수 있게만 설계한다. Tool·근거 유형·MCP 노출은 AI-7** (AI6-D21). M-5 완료 조건 'AI-6 에서 호출' 은 AI-6 의 저장 진단이 M-5 판정을 부르는 것으로 충족한다 (ai 모듈 호출이 아니다) — 해석 확인 필요 (AI6-D01 에 포함).

## 24. AI-7~AI-9 경계

| 작업 | 역할 (로드맵 기준) | AI-6 에서 미리 확보할 것 | AI-6 에서 하지 않을 것 |
|---|---|---|---|
| AI-6 | 규칙 진단·결과 저장·상태·규칙 설정·수동 전체 실행·실행 기록 | — | — |
| AI-7 | 6 결과에서 LLM 후보 선별·의미 검사·개선안·승인 반영, 반복 추론 방지 | fingerprint · 대상 ID · 근거 · 마지막 발견 시각 (재추론 판단), 결과 조회 서비스 | Tool·근거 유형·개선안 저장 |
| AI-8 | 정기·증분·테이블 단위·재시도·스케줄·락(U-17·18·22) | 실행 유형 칼럼(MANUAL/SCHEDULED 확장 가능)·적용 규칙 집합·실행기 | 스케줄러·증분·배치 설정 |
| AI-9 | 보고서·항목 검토 이력·이전 비교·엑셀·보관 | 실행별 집계·규칙별 집계·예외 처리 이력 | 보고서·Export·보관 정책 |

## 25. DB 물리 모델 초안 [제안 — 이름·타입 미확정]

**이름** : 표준 단어 예제에 `RULE`·`RSLT`(결과)·`EVDNC`(근거)·`TRGT`·`STTS`·`EXCN`·`EXCL`(예외)·`STNG`·`LAST`·`CHG`·`RSN`(사유)·`VL` 은 있으나 **품질·진단·발견·심각도·해결 단어가 없다** — 공통표준 단어 확인 후 예제 사전에 추가해야 하며(V1 예제 규칙 — 새 `TB_` 는 COMMENT·예제 행·논리 엔터티까지 같은 마이그레이션), 아래 이름은 자리표시다 (예 `QLTY`=품질, `DGNS`=진단).

| 후보 | 역할 | 주요 칼럼 후보 | 키·제약 |
|---|---|---|---|
| `TB_PRJ_QLTY_RULE_STNG` | 프로젝트 규칙 설정 (방식 B — 변경분만) | `…_SN`, `PRJ_SN`, `RULE_CD varchar`, `ACTVTN_YN boolean not null`, 감사 칼럼 | PK, FK→TB_PRJ, `UX(PRJ_SN, RULE_CD)` |
| `TB_QLTY_DGNS_EXCN` | 진단 실행 | `…_SN`, `PRJ_SN`, `ACNT_SN`, `EXCN_TYPE_CD`(MANUAL), `EXCN_STTS_CD`(PENDING·RUNNING·COMPLETED·FAILED·INTERRUPTED), `RULE_CN`(적용 규칙 목록·엔진/fingerprint 버전 — TEXT JSON 또는 별도 행), `BGNG_DT`, `END_DT`, 대상 수, `FND_CNT`·`NEW_CNT`·`PRSST_CNT`·`RSLV_CNT`·`REOPN_CNT`(이름 미정), `ERR_CD`, `ERR_CN`, 진행 표시용 칼럼(부분 UNIQUE용) | PK, FK→TB_PRJ, IX(PRJ_SN, BGNG_DT desc), **진행 중 1개 보장** : 부분 UNIQUE `(PRJ_SN) where 상태 in (PENDING, RUNNING)` (PostgreSQL 15 지원) 또는 원본 수집식 진행 칼럼 UNIQUE |
| `TB_QLTY_DGNS_EXCN_RULE` (선택) | 실행 × 규칙 집계 (추이·규칙별 증감) | 실행 SN, `RULE_CD`, 판정 성공 여부, 발견·신규·해결 수 | PK(실행, 규칙) |
| `TB_QLTY_FND` | 현재 Finding (fingerprint 당 1행) | `…_SN`, `PRJ_SN`, `RULE_CD`, `TRGT_TYPE_CD`, `SVRT_CD`, `FNGRPRT_VL varchar(70)`, 대표 대상 SN `TRGT_SN`, 대상 목록 `TRGT_SN_LIST`(그룹 — `bigint[]` 또는 TEXT), 이름 스냅샷(스키마·테이블·컬럼·용어), `ACTL_VL`, `EXPCT_VL`, `EVDNC_CN`(JSONB 또는 TEXT JSON), `STTS_CD`(OPEN·RESOLVED·EXCLUDED), `LAST_CHG_CD`(NEW·PERSISTING·RESOLVED·REOPENED), `FRST_DSCVR_DT`, `LAST_DSCVR_DT`, `RSLV_DT`, `RSLV_RSN_CD`(FIXED·TARGET_DELETED), `REOPN_CNT`, 최초·마지막 실행 SN, 예외 처리자·시각·사유 | PK, FK→TB_PRJ, FK→실행(최초·마지막, 삭제 정책 확인), **`UX(PRJ_SN, FNGRPRT_VL)`**, CHECK(상태·변화 코드 — `case when` 식, IN 금지 관례는 H2 시절 것이나 기존 스타일 유지 여부 결정) |
| `TB_QLTY_FND_HSTRY` (선택) | 상태 변경·예외 처리 이력 (append-only) | Finding SN, 실행 SN(nullable), 변경 유형, 이전·이후 상태, 사유, 사용자, 시각 | PK, FK→Finding |

- **대상 FK 없음** : Finding 은 여러 유형(컬럼·테이블·용어 그룹·식별자)을 가리키고 대상 삭제 뒤에도 해결 기록을 남겨야 하므로 대상 FK 를 두지 않는다 (AI-5 제안 테이블과 같은 방식). 대신 해결 판정에서 대상 존재를 확인한다.
- **JSONB vs 정규화** : 근거는 규칙마다 키가 달라 정규화 칼럼으로 만들기 어렵다 → JSONB(PostgreSQL 9.4+ — 15 문제없음) 또는 TEXT JSON. 검색·필터는 정규화 칼럼(규칙·심각도·상태·대상 유형·대상 이름)으로만 하므로 TEXT 로도 충분 — 기존 테이블들이 JSON 을 TEXT(`MDL_CN`, `TRGT_CN`)로 두는 관례를 따를지 결정 (AI6-D17).
- **프로젝트 삭제** : 모두 `PRJ_SN` 보유 → `ProjectDataCleanup` 목록(자식 먼저 : 이력 → Finding → 실행 규칙 → 실행 → 설정)과 `ProjectDeletionIntegrationTest.PROJECT_TABLES` 에 추가.
- **모델 객체 삭제** : 대상 FK 가 없으므로 영향 없음, 다음 실행에서 `TARGET_DELETED` 해결.
- **규칙 제거(코드)** : 저장된 그 규칙의 Finding 은 '알 수 없는 규칙' — 표시·집계 제외, 정리는 마이그레이션에서 명시적으로.
- **보관** : 자동 삭제 없음 (DEC-14). 실행 기록은 프로젝트당 실행 수만큼, Finding 은 '지금까지 발견된 서로 다른 문제' 수만큼 늘어난다.

## 26. 인덱스 · 데이터 증가량 [제안 · 추정]

| 조회 | 인덱스 후보 |
|---|---|
| 프로젝트 미해결 목록 (심각도·규칙 필터, 마지막 발견 정렬) | `IX(PRJ_SN, STTS_CD, SVRT_CD, RULE_CD)` 하나로 대부분 — 또는 부분 인덱스 `where STTS_CD = 'OPEN'` |
| fingerprint 동일 문제 | `UX(PRJ_SN, FNGRPRT_VL)` (UPSERT 키) |
| 대상별 Finding (테이블·컬럼 상세 화면) | `IX(PRJ_SN, TRGT_TYPE_CD, TRGT_SN)` |
| 실행 이력 | `IX(PRJ_SN, BGNG_DT)` |
| 최초·마지막 발견·해결 시각 정렬 | 별도 인덱스 없이 상태 인덱스 + 정렬 (측정 뒤) |

추정 (측정 아님) : 컬럼 10만 · 최악 조건 데이터에서 M-5 는 Finding 112,200건 [문서]. 실제 데이터는 그보다 적을 가능성이 크지만, 상한 가정으로 Finding 행 약 11만 × 행당 약 0.5~1KB(근거·이름 스냅샷 포함) ≈ 55~110MB + 인덱스. 실행 행은 하루 수 회 × 프로젝트 수로 무시할 만하다. 상태 변경 이력은 변화 수만큼 — 첫 실행에 전체(11만 행)가 NEW 로 쌓이는 것을 피하려면 **이력에는 예외 처리·재발만 기록**하는 쪽을 권장 (15장).

## 27. API 초안 [제안]

기존 계약 : 프로젝트 = 로그인 프로젝트, 그리드 `page`·`size`(1~100)·`sort`(허용 목록) → `{recordsTotal, data…}` (`PageableUtil.getGridPageable`), 잘못된 enum 400, 기준값 충돌 409 `MODEL_CONFLICT` 계열. 기존 `/api/standardizationQuality/**` (실시간) 와 겹치지 않게 하위 경로를 나눈다.

| 기능 | 메서드 · URL | 요청 / 응답 | 권한 | 비고 |
|---|---|---|---|---|
| 요약 | `GET /api/standardizationQuality/findings/summary` | → 마지막 실행(시각·상태), 미해결 심각도·규칙별 수, 마지막 실행 신규·해결·재발, 꺼진 규칙 수 | VIEWER | 저장 결과만 |
| 목록 | `GET …/findings/data` | `status`·`severity`·`rule`·`targetType`·`change`(NEW 등)·`searchColumn/Query`(대상 이름), 페이징·정렬 허용 목록 | VIEWER | DB 페이징 |
| 상세 | `GET …/findings/{id}` | → Finding + 근거 + (이력) | VIEWER | 다른 프로젝트 ID → 404 성격 |
| 예외 처리 | `PUT …/findings/{id}/exclusion` · `DELETE` 동일 | `{reason, modifiedAt}` | USER (AI6-D12) | 기준값 충돌 409 |
| 실행 요청 | `POST …/runs` | `{}` (AI-6 은 전체만) → 실행 ID·상태 (202) | USER | 진행 중이면 409, 대기열 가득 503 (원본 수집과 같다) |
| 실행 상태 | `GET …/runs/{id}` | → 상태·집계·오류 | VIEWER | |
| 실행 이력 | `GET …/runs/data` | 페이징 | VIEWER | |
| 취소 | `POST …/runs/{id}/cancel` | | (AI6-D08 결정 시) | |
| 규칙 설정 조회 | `GET …/rules` | → 규칙 전체(코드·대상·심각도·기본값·현재 사용 여부) | VIEWER | |
| 규칙 설정 변경 | `PUT …/rules` | `[{rule, enabled}]` 전체 또는 일부 | 프로젝트 관리자 / ADMIN | 행 UNIQUE, 잠금 = 설정 행 |

경로를 `/api/qualityDiagnosis/**` 처럼 따로 둘지(진단이 표준화 밖 규칙까지 넓어질 때) 는 AI6-D19 와 함께 정한다. 요청 로그 본문은 일반 마스킹 경로(비밀값 없음).

## 28. 테스트 전략 [제안]

- **단위** (DB 없이) : 규칙별 판정(기존 테스트 유지), Finding·fingerprint 고정(골든 파일에 식별자 추가·v2 결정 시 새 골든), **상태 전이 순수 함수**(14장 표 전체 — 규칙 꺼짐·실패·대상 삭제·예외 처리 포함), 해결 판정 조건(14.3), 적용 규칙 집합 계산(설정 합치기).
- **PostgreSQL 통합** (`TestDatabaseGuard`) : 실행 저장·Finding UPSERT·같은 데이터 재실행 시 중복 없음·수정 후 해결·재발·대상 삭제 사유·예외 처리 유지·규칙 끄기/켜기·실행 실패 시 이전 결과 유지(반영 중 예외 주입)·늦은 옛 실행 반영 거절·재시작 INTERRUPTED·프로젝트 격리(다른 프로젝트 실행·Finding·설정 ID)·프로젝트 삭제 정리(`ProjectDeletionIntegrationTest`)·예제 프로젝트 기대 결과(INFO 1건 등 기존 단언과 일치).
- **동시성** : 같은 프로젝트 중복 실행(부분 UNIQUE·409), 다른 프로젝트 동시 실행, 진단 중 모델·사전 수정(편집이 기다리지 않음 — `ConcurrencyDiagnostics.lockWaitCount` 0), 진단 중 규칙 설정 변경(실행 고정 집합), 진단 중 프로젝트 삭제.
- **성능** (`LEXORMA_PERF` opt-in 유지) : 18.3 표 — 컬럼 1만·10만, 반영 시간·SQL·힙, 목록 조회.
- **API·UI** : 권한(VIEWER 조회 / USER 실행 / 관리자 설정 — `UiActionAuthorizationIntegrationTest`·`MenuAuthorization`), SmokeTest 그리드 등록(정렬 허용 목록·400), 화면(실행·진행·결과·필터·예외 처리·규칙 설정·실패 안내, ko/en·light/dark·1440/375).
- H2·Testcontainers 도입 없음 (DB-1).

## 29. 기존 기능 영향 범위 [제안]

| 기능 | 영향 |
|---|---|
| M-5 표준화 진단 (판정·실시간 API·화면 3탭) | 기존 구현 재사용, 판정을 '전체 Finding 목록' 으로 꺼내는 내부 리팩터링, 식별자 `Finding` 변환 추가, 화면 확장 |
| M-7 정합성·물리 구조 진단 | 변경 없음 (1단계) / 2단계에 물리 구조 어댑터 |
| M-8 동시성 | 변경 없음 (진단은 잠금을 잡지 않음, 결과 테이블은 진단 전용) |
| 표준·논리·물리 승인 | 변경 없음 (진단은 승인을 막지 않음) |
| 원본 DB 수집 | 변경 없음 |
| 프로젝트 관리 | 삭제 정리 목록 확장 |
| AI 메타데이터 조회·부분 답변·MCP 13 | 변경 없음 (Tool 은 AI-7) |
| 메인 대시보드 | 저장 결과 카드 추가 (AI6-D20), 기존 수치 그대로 |
| 모델 변경 이력·버전 | 변경 없음 (진단 테이블은 모델 이력 대상이 아님) |

## 30. 구현 단계 제안

| 단계 | 목적 · 범위 | 선행 결정 | 검증 · 완료 조건 |
|---|---|---|---|
| **AI-6 1차** (이번) | 분석·설계 | — | 이 문서 |
| **AI-6 2차** — 진단 엔진 + 저장 | 판정 공통 진입점(계열별 `Finding` 목록, 식별자 변환), 마이그레이션 V4 (설정·실행·Finding·(이력) 테이블, 표준 단어·예제·COMMENT), 실행기(AI-5 패턴), 상태 전이·해결 판정, 단일 트랜잭션 확정, 프로젝트 삭제 정리, 실행·조회·규칙 설정 API | D01~D18 | 단위·통합·동시성 테스트, 컬럼 10만 성능 측정, 전체 회귀 |
| **AI-6 3차** — 화면 · 대시보드 · 완전성 규칙 | 진단 결과·이력·규칙 설정 화면, 대시보드 카드, (결정 시) 완전성 규칙 4종 | D04·D19·D20 | 브라우저 ko/en·light/dark·1440/375, 권한 |
| **AI-6 FINAL** | 성능·동시성·업무·브라우저 통합 검증, 문서 완료 | — | 완료 조건 (로드맵 6단계) |
| (후속) 물리 구조 진단 어댑터 | DEC-09·12 8종을 Finding 으로 | D03 | — |

단계를 더 나누지 않는다 (엔진과 저장은 상태 판정이 같은 트랜잭션이라 함께). 3차를 2차에 합칠 수도 있다 (화면 없이 API 만 먼저 내는 것은 사용 검증이 어려움).

## 31. 사용자 결정 요청 (AI6-D01~D22)

이미 확정된 것(심각도 값 D8, 모듈 방향, PostgreSQL 전용, U-15 실행 방식)은 다시 묻지 않는다. 아래 권장안은 **확정이 아니다**.

| 정책 ID | 결정 사항 | 선택지 | 권장안 | 권장 이유 | 영향 범위 |
|---|---|---|---|---|---|
| AI6-D01 | AI-6 최종 범위 · AI-8 경계 | (a) 로드맵 6단계 전부 (b) **저장·상태·규칙 설정·수동 전체 실행** + 실행 기록, 정기·증분·테이블 단위는 AI-8 | (b) | 6단계 완료 조건(저장·신규/유지/해결·예외)을 최소 실행 단위로 충족, 8단계와 중복 없음 | 로드맵 해석 |
| AI6-D02 | 모듈 책임 | (a) **`lexorma-data` 새 패키지** (b) `lexorma-ai` (c) 새 Gradle 모듈 | (a) | 규칙·조회가 data 에 있음, AI Provider 와 분리, ai → data 로 AI-7 이 재사용 | 모듈 경계 |
| AI6-D03 | 1단계 규칙 범위 | (a) **M-5 16 규칙만** (b) + 완전성 4종 (c) + 물리 구조 8종 (d) + 로드맵 새 규칙 전부 | (a)로 엔진, 3차에 (b) — D04 | 검증된 판정부터, 새 규칙은 심각도·오탐 결정 필요 | 규칙 수 |
| AI6-D04 | 완전성 규칙 (테이블 코멘트·컬럼 설명·테이블/컬럼 업무 설명 누락) 포함 여부 · 심각도 | 포함/제외, 심각도 WARNING/INFO | 포함, 코멘트 누락 WARNING · 업무 설명 누락 INFO | 대시보드에 이미 같은 기준 수치가 있음, 업무 설명은 AI-5 대상이라 참고 수준 | 새 규칙 4 |
| AI6-D05 | 규칙 설정 저장 방식 · 기본 상태 | A 전체 행 / **B 변경값만** · 기본 전부 켬 / INFO 기본 끔 | B · 전부 켬 | 새 규칙 추가가 쉽고 기존 화면 결과와 같음 | 설정 테이블 |
| AI6-D06 | 프로젝트별 심각도 변경 | 허용 / **불허** | 불허 | D8 확정 값, Finding 의미가 프로젝트마다 달라짐 | — |
| AI6-D07 | 실행 범위 (AI-6) | **프로젝트 전체만** / + 계열 / + 개별 규칙 | 전체만 (내부는 계열 단위 설계) | 해결 판정이 완전, 비용 작음 | API |
| AI6-D08 | 실행 방식 · 취소 | 동기 / 비동기 메모리 / **비동기 + DB 상태** · 취소 넣음/뺌 | 비동기 + DB, 취소 뺌 | U-15 패턴 재사용, 판정이 짧음 | 실행 테이블 |
| AI6-D09 | 실행 이력 범위 | 실행 집계만 / **+ 실행 × 규칙 집계** / + 전체 스냅샷 | 실행 + 규칙 집계 | 추이·규칙별 증감, 스냅샷은 과함 | 테이블 1개 |
| AI6-D10 | Finding 저장 구조 | A 실행별 / B 현재만 / **C 현재 + 실행 분리** | C | 추적과 저장량 균형 | 테이블 |
| AI6-D11 | fingerprint v1 유지 | **용어 그룹 규칙만 v2 (대상 ID 제외, 그룹 키)** / v1 그대로 | 용어 그룹만 v2 | 구성원 증감을 같은 문제로 보고 예외 처리가 풀리지 않게 | 골든 파일, 용어 6 규칙 |
| AI6-D12 | 상태 체계 · 예외 처리 | 상태 OPEN/RESOLVED/EXCLUDED + 변화 NEW/PERSISTING/RESOLVED/REOPENED 분리 여부 · 예외 처리 권한(USER / 승인 권한자) · 사유 필수 · 예외 항목이 사라지면 RESOLVED 로 바꿀지 | 분리 · USER + 사유 필수 · 사라지면 RESOLVED(사유 FIXED, 예외 기록 유지) | 로드맵 '예외 처리 항목은 신규로 안 나타남', 감사 가능 | 상태 칼럼·이력 |
| AI6-D13 | 해결 판정 조건 · 대상 삭제 | 14.3 조건 · 대상 삭제 = **RESOLVED + TARGET_DELETED** / 별도 상태 | RESOLVED + 사유 | 개선과 삭제 구분, 상태 수 최소 | 판정 |
| AI6-D14 | 규칙 끈 뒤 기존 Finding | **상태 유지 + 숨김** / 즉시 해결 / 삭제 | 유지 + 숨김 | 다시 켜면 그대로 판정, 해결 오판 없음 | 조회 |
| AI6-D15 | 이력 · 보관 | 상태 변경 이력 전부 / **예외 처리·재발만** / 없음 · 보관 기간 | 예외 처리·재발만 · 자동 삭제 없음 (DEC-14) | 첫 실행 11만 행 이력 방지, 감사는 유지 | 이력 테이블 |
| AI6-D16 | 증분 · 자동 · 정기 진단 시점 | AI-6 / **AI-8** · 변경 직후 자동 진단 하지 않음 | AI-8, 자동 없음 | 사전 변경은 이력이 없어 간접 영향 판단 불가, 전체 판정이 빠름 | — |
| AI6-D17 | 결과 확정 · 근거 저장 형식 | **단일 쓰기 트랜잭션** / 임시 테이블 단계 · 근거 JSONB / TEXT JSON | 단일 트랜잭션 · TEXT JSON (기존 관례) — 측정 뒤 재검토 | 원자성 단순, 컬럼 10만 규모 추정상 가능 | 반영 |
| AI6-D18 | 동시 실행 · 진단 중 변경 | 프로젝트당 진행 중 1 · 서버 스레드 1(대기 10) · 읽기 = REPEATABLE READ 스냅샷, 잠금 없음 · 늦은 옛 실행 반영 거절 | 권장안 그대로 | 편집 비차단, 일관된 시점 | 실행기 |
| AI6-D19 | 권한 · UI 위치 · API 경로 | 실행 USER / VIEWER · 규칙 설정 프로젝트 관리자 · **기존 메뉴 확장(실시간 탭 유지 + 진단 결과·이력·규칙 설정)** / 새 메뉴 · 경로 `/api/standardizationQuality/findings·runs·rules` / 새 경로 | 실행 USER · 설정 관리자 · 기존 메뉴 확장 · 기존 경로 하위 | 같은 진단을 두 메뉴에 두지 않음, 새 역할 없음 | 권한 지도·화면 |
| AI6-D20 | 대시보드 | **저장 결과 카드 1개** / 없음 / 추이까지 | 카드 1개, 추이는 AI-9 | 진단 자동 실행 금지, 점수 없음 | 대시보드 |
| AI6-D21 | AI Tool 시점 | AI-6 / **AI-7** | AI-7 (AI-6 은 Tool 이 부를 조회 서비스만) | AI-6 을 AI Provider 와 분리, 근거 유형 설계는 AI-7 에서 | AI 모듈 |
| AI6-D22 | Export · 보고서 | AI-6 / **AI-9** | AI-9 | 로드맵 9단계 범위 | — |

## 32. 1차 분석 결과

- 결정적 규칙 판정(16 규칙)·Finding·fingerprint 는 이미 `lexorma-data` 에 있고 **저장·실행·상태·규칙 설정은 전혀 없다**. AI-6 은 새 판정을 만들기보다 '저장 진단' 층을 더하는 작업이다.
- 큰 위험 3가지 : ① 해결 오판(꺼진 규칙·실패·범위 밖) — 상태와 변화 분리, 적용 규칙 집합 고정, 계열 성공 기록으로 막는다 ② 용어 그룹 fingerprint 의 구성원 의존 — 결정 필요 ③ 대량 반영 비용 — 컬럼 10만 판정 1초 [문서], 반영은 미측정.
- 표준 단어(품질·진단·발견·심각도·해결)가 예제 사전에 없어 테이블 이름 확정 전 단어 추가가 필요하다.
- **Flyway** : 커밋된 마이그레이션은 V0~V3 (`c8bd92b`), **다음 번호는 V4** 이다. 단 인덱스에 다른 세션의 `V5__column_name_unique.sql`·`V6__…`·`V7__…`(postgresql·h2, `AD`) 가 남아 있다 — 내용은 이미 V0 에 합쳐진 예전 것으로 보이지만(CLAUDE.md '네 번째 합침'), 그 세션이 커밋하면 번호가 겹칠 수 있으므로 2차 착수 때 다시 확인한다. 이번에 새 마이그레이션을 만들지 않았다.
- 미확인 : 결과 반영의 실제 시간·메모리(측정 안 함), 실제 업무 데이터에서의 Finding 수, 원격 CI 로그(사용자 확인).

---

# AI-6 2차 — 규칙 기반 품질 진단 실행 · 결과 영속화 (2026-11-26)

확정 정책 : AI6-D01~D22(1차 31장) + 2차 지시의 D23~D35. 아래 '[2차 결정]' 은 구현 중 정한 세부로, **2026-11-26 최종 보완에서 사용자가 AI6-2-01~07 로 확정했다** (44장 · 46장). 46장이 33~45장과 다르면 46장이 우선이다.
React 화면·대시보드 카드·완전성 규칙·AI/MCP Tool·자동/정기/증분 실행·취소·점수·내보내기는 이번 범위가 아니다.

## 33. 구현 구조

| 층 | 위치 (`lexorma-data`) | 내용 |
|---|---|---|
| 판정 연결 | `dictionary/quality/InspectionItem`, 세 진단 서비스의 `inspect(projectId)` | 화면 진단과 **같은 계산**을 그대로 부른다 (`StandardizationQualityService.diagnoseModel` 5규칙, `IdentifierQualityService.diagnose` 5규칙 → 식별자 진단 행을 `Finding` 으로 변환, `TermQualityService.diagnose` 6규칙). 규칙 판정 클래스는 바꾸지 않았다 |
| fingerprint | `FindingFingerprint.group` (v2), `TermDuplicateRules.fingerprint` | 34장 |
| 판정 실행 | `inspection/service/InspectionJudge` | 묶음(MODEL·IDENTIFIER·TERM) 단위, 적용 규칙만 남긴다 |
| 생명주기 | `inspection/service/InspectionLifecycle` (순수 함수) | 36장 |
| 실행 | `inspection/service/InspectionRunService` | 35장 — 실행기·스냅숏·저장·실패·재시작 |
| 저장소 | `inspection/repository/InspectionRepository`·`InspectionQueryRepository` (JDBC) | 엔티티를 쓰지 않는다 (결과 수십만 건). 쓰기 경로는 `ProjectWriteGuard.lock` 을 직접 부른다 |
| 서비스 | `InspectionQueryService`(조회, AI-7 재사용 — 프로젝트 ID 만 받는다) · `InspectionExceptionService` · `InspectionRuleSettingService` | 37·38장 |
| API | `inspection/controller/QualityInspectionRestController` (`/api/qualityInspection/**`) | 39장 |
| 테스트 확장점 | `inspection/service/InspectionHooks` | 운영에는 빈이 없다 (`NONE`) |

모듈 경계 그대로 (`lexorma-data` → `lexorma-ai` 역방향 없음, 새 모듈 없음 — `ModuleBoundaryTest` 통과).

## 34. FindingFingerprint v2 (AI6-D11)

- 용어 그룹 규칙 5종(TERM_ABBR_DUPLICATE · TERM_ABBR_SHARED · TERM_NAME_DUPLICATE · TERM_SAME_WORDS · TERM_SYNONYM 의 단어 치환 WORD 그룹)은 `v2:` + SHA-256(`v2␟P{프로젝트}␟규칙␟대상유형␟G␟그룹 키…`) — **구성원 ID 를 넣지 않는다**. 그룹 키는 M-5 판정이 이미 쓰는 값(약어+도메인 키, 약어, 정규화 용어명, 단어 ID 구성, 대표 단어 치환 구성) 그대로다.
- **[2차 결정]** v1 유지 : TERM_SYNONYM 의 TERM 종류(용어명 = 다른 용어의 이음동의어)는 그룹이 아니라 두 용어의 짝이라 키(용어명)만으로는 서로 다른 짝이 같은 값이 된다 → 대상 ID 포함 v1. TERM_RESTRICTED_WORD 는 용어 1건 단위라 v1. 컬럼·테이블·식별자 규칙은 v1 그대로.
- 회귀 : `StandardizationFindingGoldenTest` 기대 파일은 용어 그룹 7줄의 fingerprint 만 바뀌었다(다른 값·순서·판정 동일 — fingerprint 를 뗀 비교로 확인). 화면 진단 API 의 용어 그룹 `id`(= fingerprint)도 v2 로 바뀌었다 (SmokeTest 기대값 수정). 판정 결과 자체는 바뀌지 않았다.

## 35. 실행 · 스냅숏 · 원자성 (D07·D08·D17·D18·D23·D27·D28·D32·D33)

1. **요청** (USER, `ExecutionContextVerifier` 로 DB 의 현재 역할 확인) : 프로젝트 공유 잠금 → 대기·진행 중 실행 확인(409 `INSPECTION_ALREADY_RUNNING`) → `TB_INSP_EXCN` PENDING 행. `PRGRS_PRJ_SN`(대기·진행 중일 때만 프로젝트 ID) 의 UNIQUE 가 동시 요청 둘 중 하나만 남긴다 (중복 키 → 409). 커밋 뒤 실행기에 제출.
2. **실행기** : 서버 전체 스레드 1 + 대기열(기본 10, `lexorma.inspection.queue-capacity`) — AI-5 와 같은 비공개 `ThreadPoolTaskExecutor`(빈 아님). 가득 차면 그 실행은 FAILED(`INSPECTION_EXECUTOR_BUSY`), 서버 종료 때 기다리지 않는다.
3. **시작** (짧은 트랜잭션) : 실행 컨텍스트 재확인(ASYNC, USER) → RUNNING · 시작 일시 → 그때의 규칙 설정으로 적용 규칙 확정(`TB_INSP_EXCN_RULE` RUNNING, D28).
4. **판정** (읽기 전용 REPEATABLE READ 한 트랜잭션, D27) : 기준 일시 = 스냅숏을 잡는 첫 문장 직전 시각. 세 묶음을 같은 스냅숏에서 판정하고, 찾지 못한 OPEN 결과의 대상이 그 스냅숏에 있는지 본다(대상 삭제 판정). 잠금을 잡지 않아 편집이 기다리지 않는다.
   - **[2차 결정] 묶음 단위 실패 격리 (L18·D32)** : 묶음마다 JDBC 세이브포인트(트랜잭션 관리자가 중첩 트랜잭션을 허용하지 않아 같은 연결에서 직접). 한 묶음이 실패하면 그 묶음의 규칙만 FAILED, 나머지 묶음은 같은 스냅숏으로 판정해 확정하고 실행은 COMPLETED + `ERR_CD = RULE_FAILED`. 실패한 규칙의 결과는 그대로 둔다(해결하지 않는다). 결과를 자르지 않는다.
5. **저장** (쓰기 한 트랜잭션, D17) : 프로젝트 공유 잠금 → 실행 행 잠금(아직 RUNNING 인지 — 아니면 아무것도 쓰지 않는다, C13) → 컨텍스트 재확인 → 신규 INSERT(배치) · 유지/재발 UPDATE(`unnest` 배열, 5,000행씩) · 해결 UPDATE · 재발 이력 · 규칙별 완료·건수 → COMPLETED(기준 일시·종료 일시 따로).
6. **실패** : 판정·저장 중 예외 → 저장 트랜잭션 전체 롤백, 실행만 FAILED(오류 코드 + 예외 종류 이름만 — 메시지·SQL 은 남기지 않는다). 이전 결과 그대로, 다시 실행하지 않는다 (D33).
7. **재시작** : `ApplicationReadyEvent` 에서 대기·진행 중 실행을 INTERRUPTED(`SERVER_RESTARTED`)로 닫고 판정 중이던 규칙은 FAILED. 다시 실행하지 않는다.

## 36. 결과 생명주기 (D10·D12·D13·D14·D24·D25)

| 이번 실행 | 저장된 결과 | 결과 |
|---|---|---|
| 찾음 | 없음 | 새 행 OPEN · `LAST_CHG_TYPE_CD` NEW |
| 찾음 | OPEN | PERSISTING (이력 없음) |
| 찾음 | RESOLVED | OPEN · REOPENED, 이력 REOPEN(직전 종료 사유·일시) |
| 못 찾음 (적용 규칙, 끝까지 판정) | OPEN | RESOLVED : 대상(근거의 targetIds)이 스냅숏에 하나도 없으면 TARGET_DELETED, 아니면 RULE_SATISFIED |
| 못 찾음 | RESOLVED | 그대로 |
| — (끈 규칙·실패한 묶음) | 아무 상태 | 그대로 |

- 예외 여부(`EXCL_YN`)는 상태와 따로다 : 판정·저장은 예외 칼럼·버전 번호를 쓰지 않는다 — 예외 결과도 재진단에서 유지(신규 아님)·해결·재발하고 예외는 그대로다 (L06~L08).
- 실행 건수(`TB_INSP_EXCN_RSLT`) : 규칙 × NEW·PERSISTING·REOPENED·RULE_SATISFIED·TARGET_DELETED·EXCLUDED (0 은 행 없음). **[2차 결정]** EXCLUDED = 이 실행이 찾은 결과 중 **저장 시점**에 예외인 수 — 이 실행이 고친 행은 저장 트랜잭션이 잠그고 있어 그사이 예외 변경은 커밋 뒤로 밀린다(C11). 판정 스냅숏의 값을 쓰지 않는다.
- 같은 실행에서 같은 fingerprint 가 두 번 나오면 처음 것만 쓰고 수를 로그에 남긴다 (현재 규칙에서 나온 적 없음).
- 이력(`TB_INSP_RSLT_HSTRY`, 추가만) : EXCLUDE·UNEXCLUDE(사유 필수)·REOPEN. PERSISTING·NEW·RESOLVED 는 이력을 남기지 않는다(현재 행과 실행 건수로 본다). 자동 삭제 없음 (D15).

## 37. 예외 처리 · 규칙 설정 (D05·D06·D14·D15·D19·D24·D29)

- 예외 처리·해제 : USER, 사유 필수(앞뒤 공백 제거 후 1~1,000자 — `INSPECTION_REASON_REQUIRED`·`_TOO_LONG`), 화면이 읽은 `version` 필수(`MODEL_BASE_REQUIRED`). 프로젝트 공유 잠금 → 결과 행 `FOR UPDATE` → 버전·현재 예외 상태 비교(다르면 409 `INSPECTION_RESULT_CONFLICT`, 자동 재시도·덮어쓰기 없음) → `EXCL_YN`·`VER_NO+1` + 이력. **[2차 결정]** 예외 처리는 미해결(OPEN) 결과만(해결된 결과 409 `INSPECTION_RESULT_NOT_OPEN`), 해제는 상태와 무관.
- 규칙 설정 : 조회 VIEWER(`editable` = 지금 사용자가 바꿀 수 있는지), 변경 USER + 프로젝트 관리자·ADMIN(`verifyProjectManager`, DB 재확인). 사용 여부만 바꾸며 끈 규칙만 행으로 남기고 켜면 지운다(D05). 바꿔도 실행하지 않고, 진행 중 실행은 시작 때 확정한 규칙으로 끝난다(C08). 끈 규칙의 결과는 상태 그대로, 기본 집계에서 빠지고 `ruleScope=INACTIVE` 로 따로 조회한다(D14·D26).

## 38. 조회 (D09·D25·D26·D31)

- 요약 : 지금 쓰는 규칙의 미해결(예외 제외) 수·심각도별 수, 미해결 중 예외 수, 끈 규칙의 미해결 수, 해결(실제 개선·대상 삭제 따로), 규칙별 수, 마지막 확정 실행 · 진행 중 실행 · 최근 실행(실패·중단이면 결과는 그 전 확정 실행 기준).
- 목록 : 기본 = OPEN · 예외 제외 · 지금 쓰는 규칙. 조건 rule · severity · targetType · status(OPEN/RESOLVED/ALL) · exclusion(NOT_EXCLUDED/EXCLUDED/ALL) · ruleScope(ACTIVE/INACTIVE/ALL) · endReason · tableId · searchQuery(대상 이름 부분 일치). 정렬 허용 목록 severity · rule · targetType · targetName · status · firstSeenAt · lastSeenAt · endedAt (그 밖은 400 `INVALID_PAGE_REQUEST`), 기본 심각도 → 규칙 → ID.
- 상세 : 결과 + fingerprint + 근거 JSON + 이력. 실행 이력(최근부터, 정렬 고정)·실행 상태(규칙별 판정 상태·발견 수·변화 건수).
- 저장된 결과만 읽고 다시 판정하지 않는다. 기존 화면 진단 API(`/api/standardizationQuality/**`, 조회할 때마다 계산)는 그대로다.

## 39. API (`/api/qualityInspection`)

| 메서드 · 경로 | 역할 | 내용 |
|---|---|---|
| GET `/summary` | VIEWER | 38장 요약 |
| GET `/findings?page&size&sort&…` | VIEWER | 목록 (그리드 규격) |
| GET `/findings/{id}` · `/findings/{id}/history` | VIEWER | 상세 · 이력 |
| POST `/findings/{id}/exclude` · `/unexclude` | USER | `{reason, version}` |
| POST `/runs` | USER | 202 + 실행 상태 |
| GET `/runs?page&size` · `/runs/{id}` | VIEWER | 실행 이력 · 상태(규칙별 집계) |
| GET `/rules` · PUT `/rules` | VIEWER · USER(+관리자) | 규칙 설정 `{rules:[{rule, active}]}` |

`MenuAuthorization` 에 버튼 노출용 `quality-inspection-run`·`-exception`·`-rule`(USER)을 더했다 (화면은 3차, 메뉴 그룹 변경 없음). 요청 로그 본문은 일반 정책(`SensitiveDataMasker`)을 따른다 — 결과·사유에 비밀값이 없다.

## 40. DB — V4 (`V4__quality_inspection.sql`, PostgreSQL 전용)

| 테이블 | 역할 | 주요 제약 |
|---|---|---|
| `TB_INSP_RULE_STNG` 검사 규칙 설정 | 끈 규칙만 | UX(PRJ_SN, RULE_CD) |
| `TB_INSP_EXCN` 검사 실행 | 실행 1회 | UX(PRGRS_PRJ_SN) 진행 중 1개, UX(SN, PRJ_SN) 복합 FK 대상, CK 상태·진행 값 일관성 |
| `TB_INSP_EXCN_RULE` 검사 실행 규칙 | 적용 규칙·판정 상태·발견 수 | UX(실행, 규칙), FK 실행 |
| `TB_INSP_EXCN_RSLT` 검사 실행 결과 | 실행 × 규칙 × 변화 유형 건수 | UX(실행, 규칙, 유형), CK 유형·0 이상 |
| `TB_INSP_RSLT` 검사 결과 | 현재 결과 (fingerprint 당 1행) | UX(PRJ_SN, HASH_VL), 복합 FK (최초·최종 실행, 프로젝트) → 실행, CK 상태·종료 값 일관성, CK 근거 = JSON 객체 |
| `TB_INSP_RSLT_HSTRY` 검사 결과 이력 | 예외 처리·해제·재발 | 복합 FK (결과, 프로젝트)·(실행, 프로젝트), CK 사건별 필수 값 |

- 근거 `RSLT_EVDNC_CN` 은 JSONB (D30) : `{fingerprintVersion, targetIds, actualValue, expectedValue, evidence, details}` — 판정 결과(모델 이름·용어·DBMS 식별자 정보)만, 비밀값 없음. 대상 이름 `TRGT_CN` 은 판정 당시 표시 이름이라 대상이 지워져도 남는다.
- 모델 테이블로 가는 FK 는 없다(대상 삭제가 결과를 막거나 지우지 않는다). `PRJ_SN`·`ACNT_SN` 에도 FK 를 두지 않는다(기존 관례). 인덱스 8개.
- 예제(프로젝트 0) : 6테이블 79컬럼을 물리 모델·논리 모델(엔터티·속성·주 식별자)·매핑·관계(FK 6 — 단일 2개는 속성 짝, 복합 4개는 `(…, 프로젝트 일련번호)` UNIQUE 를 가리켜 짝 없음)·UNIQUE 7·CHECK 12·인덱스 8·업무 설명으로 넣었다. 새 단어 2(최초 FRST·등급 GRD — 공통표준단어), 새 도메인 2(`코드V50` VARCHAR(50), `내용J` JSONB), 새 용어 21. 나머지 단어(검사·규칙·설정·실행·진행·요청·기준·결과·예외·종료·사유·근거·확인·활성 등)·용어(`프로젝트_일련번호`·`계정_일련번호`·`실행_상태_코드`·`변경_유형_코드`·`활성_여부` 등)는 이미 있었다. **[2차 결정 — 사용자 승인 필요]** 테이블 이름의 '품질 진단' 대신 기존 단어 '검사(INSP)' 를 썼다 (1차 25장 초안의 품질·진단·발견·심각도·해결 단어를 새로 만들지 않기 위해 — 심각도는 '결과_등급_코드', 해결은 '종료' 로 표현).
- 고정 테스트 기준값 변경 : 마이그레이션 4 → 5, 예제 인덱스 159 → 167 · 인덱스 컬럼 225 → 239, 짝 없는 논리 관계 9 → 13. 신규 설치에서 V0~V4 적용, 예제 검증(`V0SeedMigrationTest`·`LexormaIndexSeedTest`·`LogicalRelationshipAttributeMigrationTest`·SmokeTest 표준화 진단 0건) 통과. 예제를 저장 검사로 돌린 결과 = 용어 참고 1건(기존 TERM_SAME_WORDS)뿐.
- 다른 세션의 `V5`~`V7` 파일은 인덱스에만 있고(`AD`) 작업 트리에 없어 Flyway 가 보지 않는다 — 건드리지 않았다. 그 세션이 커밋하면 번호가 겹치므로 커밋 전에 확인이 필요하다.

## 41. 검증

- 단위 : `InspectionLifecycleTest` 5 (생명주기·중복·빈 규칙 집합·v2 fingerprint·근거 모양), `TermDuplicateRulesTest` (v2 : 구성원이 늘어도 같은 값·키가 다르면 다른 값, TERM 짝·금칙어는 v1), `StandardizationFindingGoldenTest` (34장).
- 통합 (실제 실행 경로, PostgreSQL 18.6) : `QualityInspectionIntegrationTest` 15 — L01~L18 전부, 스냅숏 일관성(판정 중 다른 연결에서 표준·물리·논리 모델 수정·커밋 → 기다리지 않음, 결과는 스냅숏 기준, 기준 일시 < 종료 일시, 다음 진단에 반영), C01·C02·C05~C18 (경합은 latch·barrier·실행기 중간 지점으로 고정하고 C11 은 `ConcurrencyDiagnostics.lockWaitCount` 로 실제 행 잠금 대기를 확인 — sleep 으로 판정하지 않는다), 권한(VIEWER 실행 거절, 비관리자 설정 변경 거절), 프로젝트 격리. `QualityInspectionQueueIntegrationTest` 1 — C03·C04·C19 (대기열 1 인 별도 컨텍스트). SmokeTest `qualityInspectionApi` (HTTP 202·409·400·404, 파라미터 바인딩, `/api/auth/me` 액션) + 그리드 페이지 요청 26번째 목록. `ProjectDeletionIntegrationTest` (V4 테이블 포함 남는 행 없음).
- 전체 회귀·단일 JAR : 45장.

## 42. 성능 (기초 측정, `QualityInspectionPerformanceTest`, LEXORMA_PERF=true)

환경 : 로컬 macOS, PostgreSQL 18.6 컨테이너(pg_stat_statements), 테스트 JVM 최대 힙 2GB. 조건 : 모든 컬럼 용어 미연결 + 모든 테이블 비표준 단어 (결과 = 컬럼 + 테이블/50, 결과가 가장 많은 쪽). 실행 ① 신규 ② 같은 데이터 ③ 컬럼 10% 해결.

| 컬럼 | 실행 | 전체 | 판정(모델·식별자·용어) | 기존 결과 읽기 | 저장 트랜잭션 (신규·유지 갱신·해결) | 결과 | 클라이언트 SQL (+FK 검사) | 실행기 할당 | 힙 풀 최대 합 | GC |
|---|---|---|---|---|---|---|---|---|---|---|
| 1,000 | ① | 211ms | 31·16·11 | 0 | 89 (75·1·0) | NEW 1,020 | 82* | 16MB | 130MB | 0 |
| 1,000 | ② | 114ms | 11·7·4 | 9 | 55 (0·42·0) | PERSISTING 1,020 | 75 (+1,038) | 11MB | 119MB | 0 |
| 10,000 | ① | 397ms | 49·18·3 | 0 | 303 (291·0·0) | NEW 10,200 | 89 (+20,418) | 93MB | 201MB | 0 |
| 10,000 | ② | 322ms | 34·14·3 | 22 | 219 (0·199·0) | PERSISTING 10,200 | 77 (+10,218) | 101MB | 232MB | 4회/6ms |
| 10,000 | ③ | 323ms | 40·14·2 | 21 | 218 (0·173·21) | 9,200 유지 · 1,000 해결 | 79 (+10,219) | 93MB | 212MB | 3회/4ms |
| 100,000 | ① | 2,725ms | 284·115·3 | 0 | 2,253 (2,227·0·0) | NEW 102,000 | 200 (+204,018) | 908MB | 512MB | 14회/36ms |
| 100,000 | ② | 3,133ms | 292·120·2 | 170 | 2,433 (0·2,377·0) | PERSISTING 102,000 | 95 (+102,018) | 966MB | 803MB | 8회/42ms |
| 100,000 | ③ | 3,361ms | 292·102·3 | 209 | 2,663 (0·2,245·348) | 92,000 유지 · 10,000 해결 | 98 (+102,019) | 935MB | 564MB | 14회/52ms |

\* 새 DB 의 첫 실행은 pg_stat_statements 가 FK 검사 문장을 다른 문장으로 분류해 합계(2,140)만 맞다 — 같은 JVM 의 두 번째 1,000 컬럼 ①은 82 (+2,058).

- 판정은 컬럼 10만에서 약 0.4초 (1차에서 인용한 M-DIAG-PERF·M-5 측정 약 1초와 같은 계열 — 모델 진단 0.29초 + 식별자 0.1초). 전체 시간의 대부분은 저장(신규 INSERT·유지 UPDATE 약 2.2~2.4초)이다. 판정 1초와 전체 실행 시간(약 2.7~3.4초)은 다르다.
- 'FK 검사' 는 PostgreSQL 참조 무결성 트리거가 행마다 내부에서 실행하는 `SELECT 1 … FOR KEY SHARE` 다 (최초·최종 실행 복합 FK, `LAST_INSP_EXCN_SN` 이 바뀌는 행마다 1번). 클라이언트 왕복이 아니다.
- 메모리 : '실행기 할당' 은 실행 스레드의 누적 할당(쓰레기 포함), '힙 풀 최대 합' 은 측정 구간에서 힙 풀별 최대 점유를 더한 값(풀마다 최대 시점이 달라 실제 동시 점유보다 크게 나올 수 있다 — 상한 추정). 1차의 '600~740MB' 는 할당량이었고 최대 점유가 아니다. 2GB 힙에서 GC 는 실행당 최대 14회·52ms.
- 결과 테이블 : 컬럼 10만 3번 실행 뒤 225MB (유지 갱신이 행마다 새 버전을 만든다 — 자동 VACUUM 대상). 개선 후보(FINAL 검토) : 내용이 같은 유지 행은 마지막 판정 값만 고치기, 최종 실행 FK 의 필요성 재검토, 결과 행 보존 정책. 100만 컬럼과 최종 지원 규모는 FINAL 에서 판정한다.

## 43. 3차 인계

- 화면(결과 중심, D31) : 요약·목록·상세(근거 JSON 의 `details`·`evidence` 표시)·예외 처리/해제 대화상자(사유·버전·409 안내)·실행 요청/진행 표시(폴링)·규칙 설정(관리자) · 대시보드 카드(D20) · 완전성 규칙 4종(D04 : 코멘트 누락 주의·업무 설명 누락 참고 등 — 판정 클래스·fingerprint 키·예제 0건 확인 필요).
- **FK 타입 불일치 규칙 → 46.6 에서 확정 : 물리 구조 진단의 책임, AI-6 에 넣지 않는다 (pending-work.md [M-DIAG-FKTYPE])**. 확정 전 분석 : 현재 물리 모델 구조 진단(`ModelStructureDiagnosticService`, DEC-09/12)에는 FK 타입 불일치 유형이 없다 (FK 대상 없음·모호·PK 없음·복합 PK·대상이 키 아님 + 인덱스 3종). 타입은 ① FK 등록(`PhysicalModelServiceImpl.validateForeignKey`)·논리→물리 생성·FK 보정·상속 점검이 `TableDdlGenerator.isReferenceTypeCompatible` 로 **거절**하고 ② DDL 생성이 `checkReferenceType` 으로 **경고**(타입 계열 다름 / 길이·정밀도만 다름)만 낸다. 그래서 FK 를 만든 뒤 컬럼 타입을 바꾼 경우는 DDL 경고로만 보인다. 선택지 : (가) 구조 진단에 `FK_TYPE_MISMATCH`(ERROR: 호환 불가, WARNING: 길이만 다름)를 더하고 AI-6 은 다루지 않음 (나) AI-6 규칙으로 추가(규칙 코드·심각도·fingerprint 키 결정, `StandardizationRule` 확장) (다) 둘 다 — 판정 함수는 `isReferenceTypeCompatible`·`checkReferenceType` 하나만 쓴다. 이번에 규칙을 더하지 않았다.
- 성능 후속(42장), 실행 결과 보존 정책, 오류 사유 문구(현재 코드·예외 종류 이름만).

## 44. 2차 결정 사항 (2026-11-26 최종 보완에서 사용자 확정 — 표의 왼쪽 열이 확정 정책, 오른쪽은 채택하지 않은 대안. 세부는 46.2)

| ID | 내용 | 대안 |
|---|---|---|
| AI6-2-01 | 테이블·용어 명명에 기존 단어 '검사(INSP)' 사용, 새 단어 2(최초 FRST·등급 GRD) · 새 도메인 2(코드V50·내용J) · 새 용어 21 (40장) | '품질·진단' 단어 신규 등록 후 이름 변경 (V4 커밋 전이면 V4 를 고쳐도 된다) |
| AI6-2-02 | 묶음 단위 실패 격리 : 한 묶음 실패 → 실행 COMPLETED + RULE_FAILED, 그 규칙은 해결 판정 안 함 (L18) | 어떤 실패든 실행 전체 FAILED |
| AI6-2-03 | 예외 처리는 OPEN 결과만, 해제는 언제나 | 해결된 결과도 예외 처리 허용 |
| AI6-2-04 | EXCLUDED 건수 = 저장 시점 값 | 판정 스냅숏 시점 값 |
| AI6-2-05 | TERM_SYNONYM(TERM 짝)·TERM_RESTRICTED_WORD 는 v1 유지 | 짝도 키 기반 v2 (짝 = 정렬한 두 용어명) |
| AI6-2-06 | 대상 삭제 = 근거의 대상 ID 가 스냅숏에 **하나도** 없을 때 (용어 그룹은 구성원이 하나라도 남으면 실제 해결) | 대표 대상만 보기 |
| AI6-2-07 | 실행 이력·결과 행은 자동 삭제하지 않는다 (`TB_LOG` 보존 정책과 별개) | 보존 기간 설정 (FINAL·AI-9) |

## 45. 전체 회귀 · 단일 JAR (2026-11-26)

- **전체 `./gradlew build --continue`** (npm ci · React typecheck·build · 모든 모듈 테스트, 격리 복사본, PostgreSQL 18.6 새 빈 관리 테스트 DB + 별도 원본 테스트 DB) : **1,671건 · 실행 1,643 · 실패 0 · 건너뜀 28**, BUILD SUCCESSFUL. DB-1 기준 1,647 · 1,620 · 0 · 27 에 신규 24건 (`InspectionLifecycleTest` 5, `QualityInspectionIntegrationTest` 15, `QualityInspectionQueueIntegrationTest` 1, `QualityInspectionPerformanceTest` 1 — LEXORMA_PERF 없으면 건너뜀, SmokeTest `qualityInspectionApi` 1, `TermDuplicateRulesTest.pairAndSingleStayV1` 1). 건너뜀 +1 은 그 성능 시험. 원본 DB 테스트 19건 실행(선언 20, 1건은 기존 opt-in). 기존 테스트는 지우거나 건너뛰게 바꾸지 않았다.
- 첫 전체 실행(같은 날)은 12건 실패 — 모두 이번 변경의 기대값 : 마이그레이션 수 4 고정(7개 시험), 예제 도메인 30, V2→V3 업그레이드 시험의 '최신까지' 적용(목표 V3 로 고정), SmokeTest 두 곳(전체 테스트에서는 다른 테스트가 예제 프로젝트에 용어를 더해 TERM 짝 v1 그룹·용어명 중복이 생긴다 → 건수 고정 대신 같은 시점 화면 진단과 비교). 고친 뒤 위 결과.
- 기존 규칙 판정 변화 : 없음 (골든 파일은 용어 그룹 fingerprint 만 v1 → v2, 34장). M-5·M-7·M-8·사전·모델·원본·프로젝트 삭제·AI-5·AI 질의·부분 답변·MCP 회귀는 위 전체 테스트로 확인 (기존 시험 그대로 통과). AI·MCP 는 품질 검사 설정에 의존하지 않는다.
- **단일 JAR** (`lexorma.jar`, 같은 소스, AI Provider·MCP 없음, 새 DB) : 최초 관리자 설정 → 로그인 → 실행 202 → 완료(16규칙) → 저장 결과 1건 = 화면 진단 1건(예제의 같은 단어 구성 참고) → 상세 근거 JSON → 예외 처리·같은 버전 409·사유 없음 400 → 규칙 끄기 → 두 번째 실행(15규칙)·예외 유지 (22/22). DB 에 RUNNING 실행을 남기고 SIGTERM 으로 종료 → 재기동 : 이전 실행·결과·예외·이력·규칙 설정 유지, 남긴 실행은 INTERRUPTED(`SERVER_RESTARTED`), 요약의 마지막 확정 실행 = 두 번째, 새 실행 정상, 미인증 401 (13/13). 로그에 DB 계정·비밀번호 없음.
- **단일 JAR 업무 흐름** (같은 소스로 다시 만든 JAR, 새 DB, AI·MCP 없음, 실제 HTTP → 실행기 경로) : 최초 관리자 → 프로젝트 생성 → 도메인 2·단어 3·용어 2 등록·승인 → 테이블·컬럼(일부러 도메인보다 짧은 길이) 등록 → 프로젝트 접두어 `TB_` 지정 → ① 검사(접두어 누락·도메인 불일치 NEW) → 규칙 설정 변경 → 예외 처리 → 해제 → 오래된 버전 409 → 다시 처리 → ② 재검사(유지·예외 그대로) → 접두어 해제 → ③ 해결(RULE_SATISFIED) → 접두어 복원 → ④ 같은 결과 REOPENED·재발 이력 → 실행 이력 4건 (49/49). 재기동 뒤 규칙 설정·실행 이력·현재 결과·예외 설정·예외 이력(처리·해제·처리)·재발 이력 유지, 새 실행 완료, 프로젝트 삭제 → 그 프로젝트의 검사 행 0 (10/10). 로그에 계정·비밀번호 없음.
- 미검증 : 원격 CI(커밋 전), 브라우저(화면 없음), 실제 업무 데이터 규모, 컬럼 100만.

## 46. 2차 최종 보완 (2026-11-26)

사용자가 복원 보고서를 검토하고 AI6-2-01~07 을 확정했다. 2차 구현은 되풀이하지 않고 확정 정책 반영·부족한 검증·발견한 결함 보완만 했다. 아래 수치는 모두 **이번에 실제로 측정**한 값이다(33~45장의 수치는 2차 당시 값). 시작 상태 : `dev`, HEAD `c8bd92b`, 작업 트리·인덱스는 2차 끝과 같다 (신규 AI-6 파일은 인덱스에 `A` — 올라간 경위 미확인, 손대지 않음; 다른 세션 `AD` 8개 그대로).

### 46.1 확정 정책 (AI6-2-01~07)

| ID | 확정 | 반영 |
|---|---|---|
| AI6-2-01 | 기존 검사(INSP) 단어 재사용, 새 단어 최초(FRST)·등급(GRD), 새 도메인 코드V50·내용J, 새 용어 21, 테이블 6 | 46.3 전수 검토 — 충돌 없음, V4 그대로. `RSLT_GRD_CD` = 결과의 심각도 등급 (화면 표현은 기존 오류·주의·참고 그대로) |
| AI6-2-02 | 판정 묶음 단위 부분 실패 허용, 전체 실패·저장 실패와 구분 | 46.2 — 결함 보완 |
| AI6-2-03 | OPEN 결과만 예외 처리, 해제는 상태 무관, 사유 필수, version 동시성, 자동 재시도 없음 | 2차 구현 그대로. 예외는 해결·재발에도 유지 (L07·L08, 단위 시험 `exclusionIndependentOfLifecycle`) |
| AI6-2-04 | 실행의 EXCLUDED = 저장 트랜잭션 시점 | 2차 구현 그대로 + 시점별 검증 (46.4). 실행 건수와 현재 요약의 시점 차이를 DTO 주석에 명시 |
| AI6-2-05 | 용어 그룹 v2, TERM 짝·금칙어 v1 | v2 키를 길이 접두로 보완 (46.5) |
| AI6-2-06 | 모든 대상이 삭제됐을 때만 TARGET_DELETED | 2차 구현 그대로 + 표 검증 (46.4) |
| AI6-2-07 | 결과·이력 자동 삭제 없음 | 그대로 (보존 기간·정기 삭제·아카이빙·일괄 삭제 기능 없음, 프로젝트 삭제 때만 함께 삭제) |

### 46.2 부분 실패 (D32·D33 보완)

상태값은 늘리지 않고 `ERR_CD` 로 나눈다. 실행 응답(`InspectionDto.Run`)에 `appliedRuleCount`(시작 때 확정한 적용 규칙 수)·`partialFailure`(COMPLETED + RULE_FAILED)·`failedRules`(판정하지 못한 규칙)를 더했다.

| 상황 | 실행 | ERR_CD | 결과 반영 |
|---|---|---|---|
| 모든 적용 묶음 성공 | COMPLETED | 없음 | 전체 |
| 일부 묶음 실패 | COMPLETED | `RULE_FAILED` | 성공한 묶음의 규칙만 (실패한 규칙 = 검사하지 않음 : 신규·유지·재발·해결·최종 실행·최종 확인 일시·예외·건수 모두 그대로) |
| 적용 규칙이 있는데 성공한 묶음 없음 | FAILED | `ALL_RULES_FAILED` (신규) | 없음 |
| 모든 규칙을 끔 | COMPLETED | 없음 | 없음 — 적용 규칙 0 (빈 검사, 실패와 다르다) |
| 판정 단계(묶음 밖) 실패 | FAILED | `INSPECTION_FAILED` | 없음 |
| 저장 트랜잭션 실패 | FAILED | `STORE_FAILED` (신규, 전에는 `INSPECTION_FAILED`) | 전체 롤백 |
| 저장 직전 권한 상실 | FAILED | 그 오류 코드 (`ExecutionContextVerifier`) | 없음 |
| 실행 중 프로젝트 삭제 | (행이 함께 삭제) | — | 다시 만들지 않음 |
| 서버 중단 | INTERRUPTED | `SERVER_RESTARTED` | 없음 |
| 대기열 초과 | FAILED | `INSPECTION_EXECUTOR_BUSY` | 없음 (정상 검사 실패와 코드로 구분) |

**발견한 결함과 보완** (`InspectionRunService`) :
1. **판정 서비스 안의 실제 오류가 부분 실패가 되지 않았다** — 세 진단 서비스는 `@Transactional(readOnly = true)` 라 그 안에서 난 예외(SQL 오류 등)가 바깥 판정 트랜잭션을 rollback-only 로 만들고, 커밋 때 `UnexpectedRollbackException` 으로 실행 전체가 FAILED 가 됐다. 2차의 L18 은 서비스 밖(hook)에서 예외를 던져 이 경로를 보지 못했다. 보완 : `judgeInSnapshot` 이 판정 결과를 잡아 두고, **실패한 묶음이 있을 때만** 그 롤백을 받아들인다 (판정 트랜잭션은 읽기 전용이라 커밋과 롤백의 결과가 같다). 그 밖의 원인이면 판정 실패로 올린다. 실제 로그로 확인 (`판정 트랜잭션 롤백 (실패한 묶음 때문, 읽기 전용)`, 통합 시험 PF02 와 단일 JAR 의 실제 권한 오류).
2. **모든 묶음이 실패해도 COMPLETED** 였다 → FAILED `ALL_RULES_FAILED` (적용 규칙 모두 FAILED, 결과·이력·건수 없음).
3. 묶음 결과를 **세이브포인트 해제 전에** 실행 결과에 더하고 있었다 (해제가 실패하면 실패 묶음의 결과가 남는다) → 묶음이 끝까지 성공한 뒤에만 더한다. 묶음 판정 뒤 확장점 `InspectionHooks.afterGroup` 을 두어 '판정을 마친 뒤 실패' 도 시험한다.
4. 저장 실패와 판정 실패가 같은 코드였다 → `STORE_FAILED`.
5. 같은 실행에서 같은 fingerprint 가 **다른 내용**으로 나오면 하나를 조용히 버렸다 → `InspectionLifecycle.checkFingerprints` : 내용(규칙·대상 유형·대표 대상·근거 JSON)이 같으면 단순 중복(수만 센다), 다르면 `FingerprintCollisionException` 으로 그 묶음을 실패시키고 로그에 규칙을 남긴다. 현재 규칙에서 충돌은 나오지 않았다.

세이브포인트 검증 : 같은 연결의 SQL 오류(PostgreSQL 트랜잭션 오류 상태) 뒤 세이브포인트 복구 → 다음 묶음이 같은 REPEATABLE READ 스냅숏에서 정상 판정 (PF03·두 묶음 실패), 판정 서비스 트랜잭션 경계 안의 SQL 오류(rollback-only 전파) (PF02), 판정 뒤 예외(중간 결과 제거) (PF04). 세이브포인트로 되돌리지 못하는 오류는 판정 실패(FAILED)로 처리한다 (코드 경로, 시험으로 재현하지 않음). 읽기·쓰기 트랜잭션은 2차 그대로 분리.

### 46.3 표준 명명 전수 검토 (새 빈 PostgreSQL 18.6 에 V0~V4 적용 후 SQL 대조)

- 실제 스키마 : 테이블 6, 컬럼 79, 인덱스(IX) 8, PK 6 · FK 6 · UNIQUE 7 · CHECK 12, 모든 테이블·컬럼 COMMENT 있음.
- 예제 물리 모델(프로젝트 0) ↔ 실제 컬럼 79개 전부 : 컬럼명 = 연결 용어의 영문약어, 도메인 타입·길이, NULL 허용, 컬럼 설명 = COMMENT 일치 (차이는 일시 컬럼의 도메인 `연월일시분초D` 가 DATETIME, 실제가 timestamp(6) — Lexorma 전체의 기존 관례).
- 새 용어 21개 : 단어 구성 이름·약어를 이은 값 = 용어 이름·약어 (불일치 0). 단어 약어·이름·영문명, 용어 약어·이름, 도메인 이름(대소문자 무시) 중복 0. 최초·등급과 비슷한 기존 단어(처음·첫·초기·수준·단계·심각도·레벨 등) 없음, VARCHAR(50)·JSONB 의 기존 도메인 없음 (새 도메인 둘이 처음). 승인되지 않은 사전 항목 0.
- 예제 저장 검사 결과 1건(TERM_SAME_WORDS 참고 : 뷰_참조_일련번호 ↔ 참조_뷰_일련번호)은 V4 이전부터 있던 용어 짝이다.
- 결론 : 표준 위반·충돌 없음 → **V4 수정하지 않음**. 새 정책 결정이 필요한 항목 없음.

### 46.4 추가 검증 (PostgreSQL 18.6, 격리 테스트 DB)

`QualityInspectionIntegrationTest` 15 → 20 :
- `partialFailureByGroup` (PF01~PF04 + 두 묶음 실패) : 묶음별 결과 행 전체(상태·예외·최종 변경·종료·최종 확인 일시·최종 실행·버전·근거)가 실패 묶음은 그대로, 성공 묶음은 이번 실행 기준. 실패 규칙의 건수 행 없음, 예외 유지, 이력 추가 없음, 요약의 마지막 확정 = 부분 실패 실행.
- `totalFailureStoreFailureAndRecovery` (PF05~PF07) : 전체 실패 FAILED `ALL_RULES_FAILED`·결과 그대로·마지막 확정 실행 그대로, 부분 성공 뒤 저장 실패 FAILED `STORE_FAILED`·전체 롤백·규칙 모두 FAILED, 부분 실패 뒤 정상 실행에서 실패했던 용어 규칙이 정상 해결(최종 실행 갱신, 예외 유지).
- `partialFailureBoundaries` : 모든 규칙 끔 = COMPLETED·적용 0·결과 그대로, 용어 묶음만 켠 상태에서 그 묶음 실패 = FAILED, 저장 직전 구성원 비활성(권한 상실) = FAILED·반영 없음, 저장 직전 프로젝트 삭제 = 검사 행 0 (다시 만들지 않음), 끝난 실행을 다시 돌리면 아무것도 하지 않음.
- `exclusionCountsByTime` : 시작 전 예외 → EXCLUDED 1, 판정 중 해제 → 0, 판정 중 처리 → 1 (저장 시점 값), 실행 뒤 해제·재처리 → 과거 실행 건수 불변·현재 요약만 변경, 부분 실패 실행은 검사하지 않은 규칙의 예외를 세지 않는다. 저장과 예외 변경의 경합은 기존 C11·C12.
- `targetDeletionMatrix` : 그룹 일부 삭제·문제 유지 → PERSISTING(구성원 갱신), 일부 삭제·문제 해소 → RULE_SATISFIED, 전체 삭제 → TARGET_DELETED, 구성원 전체 교체·같은 키 → 같은 결과 PERSISTING. 실제 개선과 대상 삭제를 따로 센다, 삭제된 대상의 표시 이름·근거 유지. 단일 대상 삭제는 기존 L05.
- 단위 : `InspectionLifecycleTest` 5 → 7 (충돌 검사, 예외 독립), `TermDuplicateRulesTest` 8 → 10 (v2 경계 : 구성원 제거·순서·전체 교체·키 변경·다른 규칙 / TERM 짝 한쪽 재생성 = 새 문제), `groupFingerprint` 에 구분자·null·빈 문자열·재현성.
- 비동기·대기열 (17장 재확인) : 커밋 뒤 제출, 거절 시 FAILED 로 닫아 PENDING 이 남지 않음(C04·C19), 다른 프로젝트 대기 처리(C02), 재시작 INTERRUPTED(C16~C18) — 기존 시험 그대로 통과, 코드 변경 없음.

### 46.5 Fingerprint v2 보완

v2 정규 문자열이 키를 구분자(U+001F)로만 이어 붙여, 사용자가 입력한 이름에 그 문자가 있거나 null·빈 문자열이면 서로 다른 그룹이 같은 값이 될 수 있었다 → 키 개수 + 키마다 `길이:값`(null = `-`). v2 는 아직 저장된 결과가 없어(PG-D31, 커밋 전) 변환이 필요 없다. 골든 파일은 v2 7줄의 fingerprint 만 바뀌었다 (fingerprint 를 뺀 비교 동일). v1(M-5 기존, 컬럼·테이블·식별자·TERM 짝·금칙어)은 바꾸지 않았다 — v1 의 같은 구분자 위험은 46.2 의 충돌 검사가 '조용한 유실' 대신 묶음 실패로 드러낸다 (잔여 위험).

### 46.6 FK 타입 불일치 — 책임 분리 (확정)

AI-6(표준화 품질) 책임 : 표준 용어 연결·명명·도메인 정의·용어 중복·금칙어·식별자 표준화·(3차) 완전성. 물리 구조 진단 책임 : FK 대상·참조 키·복합 키·인덱스·**FK 참조 컬럼 타입 호환성**. 같은 문제를 두 곳에서 판정하지 않는다. 규칙은 구현하지 않았고 pending-work.md [M-DIAG-FKTYPE] 로 등록, 로드맵 6단계에 책임 조정 사유를 적었다 (완료 조건에서 빠뜨린 것이 아니라 나눈 것). FK 등록·생성·보정의 거절, 컬럼 타입 변경 정책, DDL 경고는 그대로다.

### 46.7 Flyway

HEAD 의 마이그레이션 V0~V3, 작업 트리 V0~V4, 인덱스 V0~V7 (V5~V7 은 다른 세션의 `AD` — h2·postgresql 각 3개, 작업 트리에 없어 Flyway 가 보지 않는다). 같은 번호의 파일은 작업 트리에 없다. V0~V3·V5~V7 은 건드리지 않았고 V4 도 바꾸지 않았다. 새 빈 DB 에서 V0~V4 적용 (단일 JAR 로그 `Successfully applied 5 migrations … now at version v4`). **사용자 확인 필요** : 인덱스의 `AD` 상태 그대로 일반 커밋을 하면 V5~V7 등 8개 파일의 추가·삭제가 함께 처리될 수 있다.

### 46.8 저장 공간 증가 원인 (`QualityInspectionPerformanceTest.storage`, LEXORMA_PERF + LEXORMA_PERF_STORAGE)

환경 : 로컬 macOS, PostgreSQL 18.6 컨테이너(tmpfs, autovacuum 기본값, pg_stat_statements), 테스트 힙 2GB, 이 측정만의 빈 DB. 조건 : 컬럼 10만(테이블 2,000), 결과 102,000건(42장과 같은 생성 조건). 크기는 `pg_relation_size`(본체) · TOAST(`reltoastrelid` 전체) · `pg_indexes_size` · `pg_total_relation_size` 를 나눠 적었다 (`pg_table_size` = 본체 + TOAST + FSM/VM). 실제 튜플 수는 pgstattuple, 통계 뷰는 추정·누적값.

| 시점 (TB_INSP_RSLT) | 본체 | 인덱스 | 전체 | 행 (OPEN/RESOLVED) | pgstattuple 빈 공간 | n_tup_upd / hot | 자동 VACUUM |
|---|---|---|---|---|---|---|---|
| 검사 전 | 0 | 0.1MB | 0.1MB | 0 | — | 0 / 0 | 0 |
| 1회 | 53.3MB | 28.4MB | 81.7MB | 102,000 / 0 | 2.0MB (3.7%) | 0 / 0 | 0 |
| 2회 | 106.7MB | 38.9MB | 145.6MB | 102,000 / 0 | 55MB (51%) | 102,000 / 0 | 0 |
| 3회 | 160.1MB | 42.0MB | 202.1MB | 102,000 / 0 | 108MB (67%) | 204,000 / 0 | 0 |
| 5회 | 266.8MB | 47.6MB | 314.5MB | 102,000 / 0 | 214MB (80%) | 408,000 / 0 | 1 |
| 10회 | 427.0MB | 75.0MB | 502.2MB | 102,000 / 0 | 372MB (87%) | 918,000 / 0 | 1 |
| 11회 (10% 해결) | 480.8MB | 78.0MB | 558.9MB | 92,000 / 10,000 | 425MB (88%) | 1,020,000 / 0 | 1 |
| 일반 VACUUM 뒤 | 480.8MB | 78.1MB | 559.0MB | 같음 | 428MB (89%) | — | — |
| VACUUM 뒤 1회 (같은 데이터) | **480.8MB (안 늘어남)** | 78.1MB | 559.0MB | 같음 | 427MB | 1,112,000 / 0 | — |

- TOAST 는 모든 시점 0 (근거 JSONB 평균 238B, 행 평균 518~521B — TOAST 임계보다 작다). 실행·실행 규칙·실행 건수·이력 테이블은 10회 뒤에도 각 100KB 안팎 (행 : 실행 12, 실행 규칙 192, 건수 25, 이력 0).
- 원인 (측정 근거) : ① 같은 데이터의 유지 판정도 결과 102,000행을 모두 UPDATE 하고 **HOT 갱신이 0건**이다 — 매번 바뀌는 `LAST_INSP_EXCN_SN` 에 인덱스(`IX_INSP_RSLT_05`)가 있어 HOT 이 될 수 없고(fillfactor 100 이라 같은 페이지 여유도 없다), 행마다 새 튜플과 8개 인덱스 항목이 생긴다. ② 지난 판(dead)은 곧 정리되지만(pgstattuple dead ≈ 0) 그 빈 공간은 VACUUM 이 FSM 에 올리기 전까지 다음 실행이 쓰지 않아 실행마다 본체가 약 53MB 늘었다 (5→10회 구간은 자동 VACUUM 1회 뒤 약 32MB/회). ③ 일반 VACUUM 은 파일 크기를 줄이지 않지만, 그 뒤 같은 실행은 **본체가 늘지 않았다** — 빈 공간을 다시 썼다. ④ 인덱스는 2.6배(28→78MB) — 갱신마다 모든 인덱스에 새 항목, 실행 일련번호 인덱스 둘(04·05)과 대상 인덱스(03)가 가장 컸다.
- 2차 42장의 '225MB' 는 `pg_total_relation_size('tb_insp_rslt')`(본체 + 인덱스 + TOAST) 이고, 같은 DB 에서 컬럼 1천·1만·10만을 차례로 3회씩 돌린 뒤의 값이다 (이번 10만 3회만 = 202.1MB).
- 추정 (측정 아님) : 실제 사용처럼 실행 사이가 길면 자동 VACUUM 이 그사이 돌아 본체는 live 의 약 2배 안팎에서 멈출 것으로 보인다 — 이번 측정은 실행 간격이 짧아(약 3~15초) 자동 VACUUM 이 1번만 돌았다.
- 시간·메모리 (10만 컬럼, 12회) : 전체 2.6~3.6초, 판정(모델·식별자·용어) 0.36~0.60초, 저장 트랜잭션 1.9~2.9초 (유지 UPDATE 1.9~2.8초), 클라이언트 SQL 93~200건, 실행기 스레드 누적 할당 1.08~1.17GB, 힙 풀 최대 합 544~768MB(상한 추정), GC 실행당 10~15회·35~58ms. pg_stat_statements 가 이번에는 참조 무결성 내부 문장을 세지 않았다(0 — 기본 `track = top`), 2차의 'FK 검사 10~20만 건' 과 비교하지 못한다.
- **적용한 성능 개선 : 없음** (분석 우선 — 지시 16장). **FINAL 후보 (모두 사용자 승인 필요)** : `IX_INSP_RSLT_05` 필요성 재검토(FK 자체는 참조하는 쪽 인덱스를 요구하지 않는다) + 결과 테이블 fillfactor 를 낮춰 유지 갱신을 HOT 으로, 최종 실행 FK·최종 확인 일시의 의미를 지키며 갱신 줄이기(같은 내용이면 근거·이름 열은 그대로 두기 — 단 이것만으로는 튜플 수가 줄지 않는다), 대량 실행 직후 VACUUM 권장 여부, 결과 보존 정책(AI-9).

### 46.9 전체 회귀 · 단일 JAR

- **전체 `./gradlew build --continue`** (격리 복사본, npm ci·React typecheck·build·모든 모듈 테스트, PostgreSQL 18.6 새 빈 관리 테스트 DB + 별도 원본 테스트 DB) : **1,681건 · 실행 1,652 · 실패 0 · 건너뜀 29**, BUILD SUCCESSFUL (8분 52초). 2차 기준 1,671 · 1,643 · 0 · 28 에서 +10 = 통합 +5, Lifecycle +2, TermDuplicateRules +2, 성능 `storage` +1(건너뜀 +1). 다른 세션 작업으로 늘어난 시험 없음. 모듈별 : app 1,189(건너뜀 29) · data 285 · core 94 · common 35 · system 28 · ai 25 · source 16 · operation 9. 기존 시험을 지우거나 건너뛰게 바꾸지 않았다 (L14 에 `STORE_FAILED` 확인 한 줄 추가).
- **단일 JAR** (같은 복사본에서 만든 `lexorma.jar`, 새 빈 DB, **슈퍼유저가 아닌 DB 소유자 계정**, AI Provider·MCP 없음, 새 필수 환경변수 없음) : V0~V4 적용·기동, 최초 관리자 → 검사(16규칙, `appliedRuleCount` 16·`partialFailure` false) → 저장 결과 = 화면 진단 → 근거 JSON → 예외·409·400 → 규칙 끄기 → 2차 검사(15) → 모든 규칙 끈 빈 검사(COMPLETED·0) 29/29; **실제 부분 실패** — `TB_STD_TRM_SYNM`(용어 묶음만 읽는 표)의 SELECT 권한을 뺀 상태에서 검사 → COMPLETED + RULE_FAILED, 실패 규칙 = 켜 둔 용어 5규칙, 다른 규칙 COMPLETED, 용어 결과(예외 포함) 그대로 7/7 (로그 : `group=TERM cause=JpaSystemException` → `판정 트랜잭션 롤백 (실패한 묶음 때문)`); 권한 복구 뒤 정상 판정 4/4; RUNNING 실행을 남긴 채 SIGTERM → 재기동 : 이력·결과·예외·규칙 설정 유지, 남긴 실행 INTERRUPTED, 새 실행 정상, 미인증 401 13/13; 새 프로젝트 업무 흐름(사전·모델 등록 → 검사 4회 신규·유지·해결·재발 → 예외 처리·해제 → 실행 이력) 49/49, 재기동 뒤 유지·새 실행·프로젝트 삭제 → 그 프로젝트 검사 행 0 10/10. **합계 112/112.** 로그에 DB·계정 비밀번호 없음.
- 신규 의존성·설정 없음.

### 46.10 변경 파일 (최종 보완)

운영 : `InspectionRunService`(판정 트랜잭션·부분/전체 실패·충돌 검사·실패 코드), `InspectionLifecycle`(`checkFingerprints`), `InspectionHooks`(`afterGroup`), `InspectionCodes`(`ALL_RULES_FAILED`·`STORE_FAILED`), `InspectionDto.Run`(+3 필드)·`InspectionQueryService`, `FindingFingerprint.group`(키 길이 접두). 시험 : `QualityInspectionIntegrationTest`(+5), `QualityInspectionPerformanceTest`(+`storage`), `InspectionLifecycleTest`(+2), `TermDuplicateRulesTest`(+2), 골든 파일(v2 7줄). 문서 : 이 문서(머리·43·44·46장), pending-work.md, completed-work.md, 로드맵 6단계, CLAUDE.md. V4·프런트엔드·권한(`MenuAuthorization`·권한 점검 문서) 변경 없음.

### 46.11 미검증 · 잔여 위험 · 3차 인계

- 미검증 : PostgreSQL 15 (FINAL), 원격 CI (커밋 전), 브라우저(화면 없음), 실제 업무 데이터 규모, 실행 간격이 긴 실제 사용에서의 자동 VACUUM 효과(46.8 추정), 세이브포인트 복구 자체가 실패하는 오류(연결 끊김)의 재현.
- 잔여 위험 : 결과 테이블이 VACUUM 전까지 실행마다 커짐(46.8), v1 fingerprint 의 구분자 충돌 가능성(묶음 실패로 드러남), 인덱스의 `A`/`AD` 상태(46.7).
- 3차 인계 : pending-work.md [AI-6] 남은 작업 — 특히 부분 실패 표시(정상 완료와 구분, 실패한 규칙의 기존 결과가 그대로라는 안내), 현재 집계(조회 시점)와 과거 실행 건수(확정 시점)의 기준 구분, 빈 검사(적용 규칙 0) 표시, 오류 코드별 안내 문구(`ALL_RULES_FAILED`·`STORE_FAILED`·`INSPECTION_EXECUTOR_BUSY`·`SERVER_RESTARTED`·권한 오류 — 지금은 코드만).

## 47. 3차 — 품질 진단 화면 개편 · 완전성 규칙 (2026-11-26, 커밋 전)

지시 AI6-3-01~18 을 구현했다. FINAL·AI-7 은 착수하지 않았다. 마이그레이션 없음 (V0~V4 그대로, 다른 세션의 V5~V7 손대지 않음), 인덱스·FK·fillfactor·autovacuum 변경 없음, 점수·등급 없음, 강제 VACUUM 없음.

### 47.1 화면 (AI6-3-01·04~09)

- 메뉴는 그대로 `standardization-quality` (`/standardizationQuality`, 표시 이름 '품질 진단'). 옛 실시간 화면(`StandardizationQualityPage`)을 지우고 저장 결과 화면 `features/quality/QualityInspectionPage.tsx` 로 바꿨다. 탭 3개 : **진단 결과 / 실행 이력 / 규칙 설정**.
- 상태 영역 : 검사 기준 시각 + 마지막 확정 실행(번호 → 실행 상세) + 결과 배지. 결과 구분 (`model.ts runOutcome`) : 정상 완료 · **부분 실패**(COMPLETED + `partialFailure`, 노란 안내 + 판정하지 못한 규칙 칩, '그 규칙의 결과는 이전 검사 그대로') · **전체 규칙 판정 실패**(`ALL_RULES_FAILED`) · **결과 저장 실패**(`STORE_FAILED`, '모두 되돌렸다') · **빈 검사**(적용 규칙 0, '문제가 없다는 뜻이 아니다') · **실행 중단**(`SERVER_RESTARTED` 등 INTERRUPTED) · 그 밖의 실패(실행기 가득 참·권한·프로젝트 상태 — 코드별 문구). 마지막 실행이 실패·중단이면 빨간 상자로 '결과는 이전 정상 검사(기준 시각 …) 그대로' 를 함께 보인다.
- 요약 카드 : 오류·주의·참고(켜진 규칙의 미해결, 예외 제외 — **조회 시점**), 예외, 해결(실제 개선 / 대상 삭제 따로). 카드를 누르면 같은 조건의 목록 필터. 실행 이력의 건수는 **그 실행이 확정한 시점** 값이라는 문구를 탭 위에 둔다 (AI6-3-07).
- '재검사 필요' : 켜져 있지만 마지막 검사에서 판정하지 않은 규칙(끈 상태로 검사 → 다시 켬, 또는 실패 묶음) 수. 규칙 설정 표에도 규칙별로 표시 (`RuleSetting.recheckNeeded`, `lastInspectedRunId/At`).
- 폴링 : 진행 중 실행(`activeRun`)이 있을 때만 2초 간격 (`refetchInterval`). 끝나면 요약·목록·이력을 한 번 다시 읽는다. 새로 고침·다른 화면 다녀오기 뒤에도 서버 상태로 복원. 프로젝트 전환 시 그리드는 `key={projectId}` 로 새로 만든다.
- 진단 결과 필터 (AI6-3-05) : 대상 구분(모델·용어·식별자) · 규칙(20) · 심각도 · 대상 유형 · 상태(미해결 기본·해결·전체) · 예외(예외 아님 기본·예외·전체) · 규칙 상태(켜진 규칙 기본·꺼진 규칙·전체) · 마지막 변화(신규·유지·재발·해결) · 검색(대상·메시지). 대시보드·요약 카드 링크는 `?rule=`·`?severity=` 등 URL 로 넘긴다.
- 결과 상세 : 규칙·대상·심각도·상태, 근거(현재 값·기대 값, `details`·`evidence` 를 라벨로 — 내부 ID 키 `canonicalWordIds`·`wordIds`·`synonymOwnerTermId` 는 숨김, 참/거짓은 예/아니오), 검사 이력(최초 발견·최종 확인 실행·해결), 예외·재발 이력, 테이블 상세 이동(대상이 삭제됐으면 '대상 삭제' 표시·이동 비활성 — `FindingDetail.targetExists`), 컬럼명 개선 후보(기존 API 재사용).
- 예외 처리/해제 (AI6-3-06) : 사유 1~1,000자 필수, `version` 전송. 409 이면 입력한 사유를 지우지 않고 '다른 사용자가 먼저 바꿨다' 안내 + 최신 상태 다시 읽기, 같은 버전으로 다시 보내기는 막는다. 처리 후 요약·목록 갱신. (브라우저 검증에서 찾은 결함 수정 : 예외 처리 사유가 해제 입력란에 남아 이어 붙던 문제 → 시작·취소 때 사유·충돌 상태 초기화.)
- 실행 이력 : 요청·결과·요청자·기준 시각·완료·소요·적용 규칙·실패 규칙·변화 건수, 요청 일시 정렬(`/runs/data?sort=requestedAt,asc|desc`). 실행 상세 : 결과·오류 문구·실패 규칙·규칙별 건수·'검사 당시 꺼져 있던 규칙'.
- 규칙 설정 (AI6-3-08·09) : 묶음별 20규칙(이름·심각도·설명·마지막 검사·재검사 필요), 체크박스 1개 = PUT 1번. 프로젝트 관리자·ADMIN 만 바꿀 수 있고(`editable`), 나머지는 비활성 + 안내 문구. 진행 중 실행은 시작 때 확정한 규칙으로 끝난다(바꾸면 '다음 검사부터' 안내).
- 오래된 화면 문구 '표준화 품질 진단' → '품질 진단' (용어 재연결 2곳, 대시보드 주석). 도움말 `guide.ko/en.json` [10] 본문을 새 화면 기준으로 고침 — **도움말 이미지는 옛 화면 그대로** (교체 필요).
- 스타일 `styles/quality.css` (motion.css 앞), 기존 토큰만 사용 — light/dark 모두 기존 테마 변수.

### 47.2 실시간 API·화면 제거 (AI6-3-02·03)

- 지운 API : `GET /api/standardizationQuality/summary`·`/data`·`/model/summary`·`/model/data`·`/terms/summary`·`/terms/data`·`/identifiers/summary`·`/identifiers/data` (SmokeTest 가 404 확인). 함께 지운 것 : 서비스의 `selectIssues`·`selectModelSummary`·`selectModelIssues`·`TermQualityService.selectSummary/selectGroups`·`IdentifierQualityService.selectSummary/selectIssues`, DTO (`ModelIssue`·`ModelSummary`·`ModelSearch`·`Search`, `TermQualityDto.Member/Group/Summary/Search`, `IdentifierQualityDto.Summary/Search`), `StandardTermRepository.findAuditByIds`, 정렬 맵, 화면 `StandardizationQualityPage.tsx`·`TermGroupDetail`, en.ts 키 49개.
- 남긴 것 : 판정 엔진(`StandardizationQualityService.inspect`·`diagnose`, `TermQualityService.inspect`, `IdentifierQualityService.inspect` — 저장 검사가 부른다), `StandardizationQualityService.selectSummary`(대시보드 '데이터 품질 요약'이 쓴다), `GET /api/standardizationQuality/domains/duplicates`(규칙이 아닌 [DICT-2] 보고 — 진단 결과 탭 위 알림으로 유지), `GET …/columns/{id}/suggestions`(컬럼명 개선 후보).
- 판정 결과는 그대로다 : 옛 실시간 테스트들(`StandardizationQualityIntegrationTest`·`IdentifierQualityIntegrationTest`·`StandardizationQualityRulesIntegrationTest`·`StandardRulePolicyIntegrationTest`·`StandardTermDuplicateIntegrationTest`·`PhysicalGenerationStoredAbbreviationIntegrationTest`·성능 시험)을 `inspect()` 판정으로 옮겼다. 화면 전용 페이징·정렬·검색 단언만 지웠고(그 API 가 없다), 식별자 권한 시험 1건은 대상 API 가 없어 지웠다 (권한은 `QualityInspectionIntegrationTest`·SmokeTest 가 본다).

### 47.3 저장 검사 API 변경

- 목록 경로를 그리드 규약에 맞춤 : `GET /api/qualityInspection/findings/data` (`searchColumn`·`searchQuery`·`group`·`lastChange` 추가, 잘못된 값 400), `GET /api/qualityInspection/runs/data` (`sort` 허용 목록 `requestedAt`). 결과 행에 `actualValue`·`expectedValue`(근거 JSON), 상세에 `targetExists`.
- 규칙 설정 : `PUT /rules` 의 각 항목에 `expectedActive`(화면이 본 값) 필수 → 없으면 400 `MODEL_BASE_REQUIRED`, 프로젝트 행 잠금 뒤 현재 값과 다르면 409 `INSPECTION_RULE_SETTING_CONFLICT` (전체 롤백), 같은 값 변경은 기록하지 않는다. 응답 행에 `group`·`lastInspectedRunId/At`·`recheckNeeded`.
- 409 두 코드(`INSPECTION_RULE_SETTING_CONFLICT`·예외 버전 충돌)는 `api.ts` `CONFLICT_CODES` 에 넣어 화면이 직접 안내한다.
- 권한 그대로 : 조회 VIEWER, 실행·예외 USER (`quality-inspection-run`·`-exception`), 규칙 설정 USER + 프로젝트 관리자/ADMIN (`quality-inspection-rule`) — `MenuAuthorization`·[권한 점검 문서](../reference/ui-action-authorization-audit.md) 갱신.

### 47.4 완전성 규칙 4종 (AI6-3-10~13, D04)

| 규칙 | 대상 | 심각도 | 판정 열 |
|---|---|---|---|
| `TABLE_COMMENT_MISSING` | 테이블 | 주의 | `TB_TBL.TBL_KORN_NM` (화면 '테이블 코멘트', DDL COMMENT 값) |
| `COLUMN_COMMENT_MISSING` | 컬럼 | 주의 | `TB_COL.COL_EXPLN` |
| `TABLE_BIZ_DESCRIPTION_MISSING` | 테이블 | 참고 | `TB_TBL.TASK_EXPLN` |
| `COLUMN_BIZ_DESCRIPTION_MISSING` | 컬럼 | 참고 | `TB_COL.TASK_EXPLN` |

- 누락 = NULL · 빈 문자열 · ASCII 공백류(U+0020·TAB·LF·VT·FF·CR)만 — `ColumnNameKey.isBlankName` 과 같은 규칙(`CK_COL_01` 과 같다). 다른 유니코드 공백(NBSP 등)은 값으로 본다.
- 판정 `dictionary/quality/CompletenessRules` (순수 함수), 읽기 `DictionaryUsageRepository.findCompletenessTables/Columns` (프로젝트 범위 1쿼리씩), MODEL 묶음에 포함. fingerprint v1 = (프로젝트, 규칙, 대상 ID). 근거 `{missing: COMMENT|BIZ_DESCRIPTION}` + 스키마·테이블·컬럼 이름. AI 업무 설명 제안(AI-5)이 승인되면 다음 검사에서 해결된다 — 제안 자체는 바꾸지 않는다.
- **대시보드 정렬 (사용자 확인 필요)** : 대시보드 '데이터 품질 요약' 의 코멘트·업무 설명 누락 건수가 같은 공백 규칙을 쓰도록 `DashboardRepositoryImpl.blank()` 를 HQL `completeness_blank(…)`(`CompletenessSql`, `FunctionContributor`) 로 바꿨다 — 전에는 `trim()`(공백 U+0020 만)이라 탭·줄바꿈만 있는 값을 '있음' 으로 셌다. 저장 검사 결과 = 대시보드 건수 (SmokeTest 7규칙 비교).
- 테이블 코멘트는 `TBL_KORN_NM` 만 본다. DDL 생성기는 그 값이 비면 `TBL_EXPLN` 을 쓰지 않는다 [코드] — 그래서 대체값을 두지 않았다 (결정 항목).
- 화면 저장 경로는 테이블 코멘트·컬럼 설명을 필수로 받으므로(`TABLE_KOR_NAME_REQUIRED`, 컬럼 설명 필수) 코멘트 누락은 주로 엑셀·원본 가져오기·생성·교환 같은 다른 경로나 옛 데이터에서 나온다. 업무 설명 API 는 공백만인 값을 거절한다 (400).
- 예제(프로젝트 0) : 새 DB 에서 4규칙 모두 0건 (테이블 97, 컬럼 1,089).

### 47.5 대시보드 카드 (AI6-3-14, D20)

`DashboardPage` '품질 진단 요약' 카드 1개 : 오류·주의·참고·예외 수(조회 시점, 저장 결과 요약 API 재사용) + 검사 기준 시각 + 마지막 결과 배지, 숫자는 해당 필터 목록 링크. 검사 전이면 '아직 품질 진단을 실행하지 않았습니다' 안내. 점수·등급 없음. 기존 '데이터 품질 요약' 의 `?type=` 링크는 `?rule=` 로 바꾸고 완전성 4행에도 링크를 달았다.

### 47.6 검증

- 단위 : `CompletenessRulesTest` 10 (NULL·빈·공백류·NBSP·심각도·fingerprint·근거).
- 통합 (PostgreSQL 18.6) : `CompletenessInspectionIntegrationTest` 3 (CP15~19 생명주기 신규·유지·해결·재발, CP20~22 대상 재생성·규칙 끄기·예외, CP23~25 프로젝트 격리·공백 규칙·부분 실패), `QualityInspectionIntegrationTest` 20, SmokeTest 74 (제거 API 404, 그리드 23개 400 검사, 20규칙, 저장 = 대시보드 7규칙, 409, group/lastChange 400).
- **전체 `./gradlew build --continue`** (격리 복사본, PostgreSQL 18.6 새 DB) : **1,693건 · 실행 1,664 · 실패 0 · 건너뜀 29** (건너뜀은 모두 opt-in 성능·외부 원본). 2차 1,681 에서 +10 단위, +3 통합, −1 지운 식별자 권한 시험. 이 빌드 뒤 바꾼 것은 프런트엔드뿐(예외 사유 초기화, `.quality-error`, 숫자 천 단위 표시, 근거 라벨) — `tsc -b` 통과·`bootJar` 재빌드로 확인, 전체 시험은 다시 돌리지 않았다.
- **성능 (20규칙, PostgreSQL 18.6, 힙 2GB, `QualityInspectionPerformanceTest`)** :

| 컬럼 | 결과 | 전체 (3회) | 판정 | 저장 | 할당 | 힙 최대 합 | GC |
|---|---|---|---|---|---|---|---|
| 1천 | 3,060 | 331 / 146 / 136ms | — | — | — | — | — |
| 1만 | 30,600 | 844 / 826 / 879ms | — | — | — | — | — |
| 10만 | 306,000 | 7.6 / 8.7 / 10.0초 | 0.41~0.55초 | 6.5~8.2초 | 2.55~2.74GB | 953~1,385MB | 최대 29회 / 105ms |

  조회 중앙값 (10만, 결과 30.6만) : 요약 155ms · 첫 페이지 294ms · 마지막 페이지 358ms · 필터 108ms · 실행 이력 2ms · 대시보드 통계 237ms. 결과 테이블 3회 뒤 688MB (46.8 의 증가 특성 그대로 — 완전성 규칙으로 결과 수가 3배). 브라우저에서 10만 프로젝트 마지막 페이지·규칙 필터 응답 92~184ms.
- **브라우저** (Chrome, 단일 JAR, 비슈퍼유저 DB 소유자 계정) UI01~40 : 빈 상태 · 대기/진행 중(잠금으로 유지) · 완료 후 자동 갱신 · 새로 고침/재방문 복원 · 부분 실패(실제 SELECT 권한 회수) · 전체 규칙 판정 실패 · 결과 저장 실패(INSERT 권한 회수) · 빈 검사 · 실행 중단(RUNNING 남기고 재기동) · 기본 필터 · 심각도/규칙/묶음/예외 필터 · 해결(실제 개선/대상 삭제) · 상세·근거·삭제 대상 · 테이블 이동·개선 후보 · 예외 처리/해제·빈 사유·409 입력 유지 · 실행 이력·상세 · 규칙 설정 20·실행 중 변경·409·재검사 필요 · 대시보드 카드·링크·미실행 안내 · 10만 페이징 · 진행 중 프로젝트 전환 · 상태 조회 네트워크 오류 안내 후 회복 · 역할별(VIEWER 실행·예외 버튼 없음·설정 비활성, USER 설정 불가, 프로젝트 관리자·ADMIN 가능). 한국어 light/dark(결과·상세·예외 입력·이력·규칙), 영어 light/dark(결과·상세) 확인. API 역할·격리 21/21 (다른 프로젝트 결과·실행 404, 비구성원 로그인 거절, 미인증 401).
- **단일 JAR 업무** : 위 브라우저 흐름 + 새 프로젝트(사전·테이블·컬럼 등록 → 20규칙 검사 → 업무 설명 누락 3건 → 테이블 업무 설명 입력 → 재검사 해결·유지 → 대시보드 → 프로젝트 삭제, 검사 행 0) 19/19, 재기동 뒤 INTERRUPTED 정리·결과 유지, 로그에 DB·계정 비밀번호 없음·ERROR 는 의도한 권한 오류뿐.

### 47.7 미검증 · 결정 필요 · FINAL 인계

- 미검증 : 반응형(좁은 화면), 꺼진 규칙 결과 필터의 브라우저 확인(통합 시험만), 영어로 본 실행 이력·규칙 설정·대시보드 화면, 용어 묶음 근거 라벨의 브라우저 표시(예제 프로젝트에 용어 결과 없음 — 타입 검사만), Safari·Firefox, PostgreSQL 15, 원격 CI(커밋 전), 컬럼 100만.
- 사용자 결정 필요 : ① 테이블 코멘트 = `TBL_KORN_NM` 만 (대체값 없음) ② 대시보드 공백 판정을 `trim` → ASCII 공백류로 바꾼 것 ③ 도메인 이름 중복 보고를 규칙이 아닌 알림으로 둔 것 ④ 10만 컬럼·30.6만 결과에서 실행기 힙 최대 합 약 1.4GB (힙 2GB 기준) ⑤ 도움말 이미지 교체 ⑥ 인덱스의 `A`/`AD` 정리(다른 세션 파일 — 사용자가 정리).
- FINAL 인계 : PostgreSQL 15 전체 검증, 컬럼 100만, 저장 공간(46.8 + 완전성으로 결과 3배), 결과 보존(AI-9), 실행기 메모리.

## 48. FINAL — 성능 최적화 · 최종 통합 검증 (2026-11-26, 커밋 전)

AI6-F-01~18 확정 정책으로 진행했다. AI-7 이후는 착수하지 않았다. **저장 구조(인덱스·fillfactor) 변경은 사용자 승인 대상이라 격리 DB 비교까지만 했고 V4·정식 마이그레이션은 바꾸지 않았다** — 그래서 AI-6 완료 처리와 문서 이동은 보류한다 (48.12).

### 48.1 시작 상태

- HEAD `c8bd92b` (변화 없음), 브랜치 `dev`. 인덱스 해시는 3차 종료 때(`52d9cd6a…`)와 다르다 — 3차 신규 파일 7개(`CompletenessRules`·`CompletenessSql`·`CompletenessRulesTest`·`CompletenessInspectionIntegrationTest`·`QualityInspectionPage.tsx`·`inspectionDialogs.tsx`·`quality.css`)가 `A` 로 스테이징되어 있었다 (사용자 작업으로 보고 시작 기준 `387ed31…` 로 기록, 에이전트는 인덱스를 바꾸지 않았다). 커밋된 Flyway V0~V3, 작업 트리 V4(`A`), 다른 세션 V5~V7 6개 `AD` 그대로.
- 측정 환경 : Apple M3 Max 16코어·48GB, Docker 16GB/16 CPU, PostgreSQL 18.6 공식 이미지(tmpfs 데이터, 기본 설정 + `pg_stat_statements`), JDK 21.0.2 (G1), 테스트 JVM 최대 힙 2GB (`build.gradle` 그대로), 측정마다 새 빈 DB (앞선 측정 DB 삭제). 같은 조건에서 비교한 값만 나란히 둔다.

### 48.2 메모리 — 원인 (AI6-F-04, JFR·힙 히스토그램)

최적화 전 재측정 (컬럼 10만·결과 30.6만, 1회 신규 + 유지 4회) : 실행기 할당 2.56~2.83GB, GC 직후 점유 최대 594~802MB, 풀 최대 합 0.93~1.33GB, 실행 뒤 전체 GC 사용량은 실행 전과 같음(106MB — 누수 없음). 판정 직후 살아 있는 힙(전체 GC 뒤, 앱 기본 약 106MB 포함) 444MB (유지 실행).

| 원인 (JFR 실행기 스레드 할당, 3회 합 8.6GB) | 비율 |
|---|---|
| 근거 JSON 직렬화 — 결과마다 Jackson 으로 **두 번**(충돌 비교용 `content` + 저장) | 34% (+ 비교 문자열 7.5%) |
| unnest 배열 문자열화 (`createArrayOf`) | 약 9% |
| 저장된 결과 읽기 — 행마다 `targetIds` 배열(`PgArray`, 잠금 객체 포함) | 약 8% |
| 완전성 조회 결과 행 · fingerprint SHA-256 입력 · `Timestamp.valueOf` 반복 · 건수 Integer 박싱 | 각 1.5~6% |

살아 있는 객체 (판정 직후) : 결과마다 근거 Map 2개(`LinkedHashMap` 65만 개·항목 124만 개 ≈ 150MB), `Finding`·`InspectionItem`, 충돌 비교용 내용 문자열 30.6만 개(판정 중), 저장된 결과 목록(`Existing`, 저장이 끝날 때까지).

### 48.3 메모리 — 바꾼 구조 (결과·판정 의미 동일)

- `StoredItem` (inspection/service) : 판정 결과를 받는 즉시 저장 형태(규칙·fingerprint·대상·표시 이름·근거 JSON 문자열)로 바꾸고 근거 Map·`Finding` 을 버린다. 근거 JSON 은 `EvidenceJson` 이 재사용 버퍼에 한 번만 직접 쓴다 (문자열·숫자·참거짓·목록·Map, 그 밖의 형은 Jackson — `EvidenceJsonTest` 가 Jackson 결과와 같은 JSON 값·같은 키 순서임을 비교).
- 충돌 검사(`InspectionLifecycle.checkFingerprints`) : fingerprint → 결과를 기억하고 **같은 fingerprint 가 다시 나왔을 때만** 내용(규칙·대상 유형·대표 대상·근거)을 비교한다 — 판정은 같다(AI6-2-05).
- 모델 묶음 스트리밍 : `StandardizationQualityService.inspect(projectId, Consumer)`·`CompletenessRules.diagnose(…, Consumer)`·`InspectionJudge.judge(…, Consumer)` — 결과 30만 건을 목록으로 모으지 않는다 (목록판은 그대로 남아 기존 시험이 쓴다). 스냅숏·세이브포인트·묶음 실패 처리 그대로.
- 계획을 세운 뒤 저장 단계에는 계획만 넘긴다 (`Judged.forStore` — 저장된 결과 목록을 저장 트랜잭션 동안 들고 있지 않는다).
- 저장된 결과 읽기 : 대상 ID 배열은 대표 대상 하나가 아닐 때(용어 그룹)만 읽는다 (`RSLT_EVDNC_CN -> 'targetIds' = jsonb_build_array(TRGT_SN)` 이면 null → 대표 대상).
- 저장 : 시각 값 `Timestamp` 를 묶음마다 한 번, 건수는 int 배열로 센 뒤 Map. 트랜잭션·문장 구조·잠금 순서·원자성 그대로.

| 컬럼 10만 (같은 조건) | 최적화 전 | 최적화 후 |
|---|---|---|
| 실행기 할당 (신규 / 유지) | 2,561 / 2,782~2,827MB | 1,552 / 1,824~1,834MB (**−35%**) |
| GC 직후 점유 최대 | 594~802MB | 399~584MB (**−27~33%**) |
| 판정 직후 살아 있는 힙 (앱 기본 포함, 유지 실행) | 444MB | 276MB (검사 몫 약 338 → 170MB, **−50%**) |
| 전체 시간 (신규 / 유지 4회) | 8.0 / 9.4~10.5초 | 7.2 / 8.6~9.6초 (약 −8%) |
| 실행 뒤 전체 GC 사용량 | 106MB (실행 전과 같음) | 106MB (같음) — 반복 5회 누적 없음 |
| GC 최장 정지 | 10~21ms | 13~21ms |

JVM 힙은 늘리지 않았고 결과를 줄이거나 규칙을 빼지 않았다 (AI6-F-05).

### 48.4 JDBC 배치 (100k, 같은 조건)

`reWriteBatchedInserts=true` 가 실제로 적용된다 (신규 30.6만 행이 문장 456회 — 2의 거듭제곱 다중 VALUES). 저장 한 문장 행 수 비교 (`storeChunk`, 시험만 바꿈) :

| 행 수 | 신규 저장 / 문장 | 유지 저장 / 문장 | WAL(신규·유지) |
|---|---|---|---|
| 250 | 7.1초 / 7,428 | 7.7초 / 1,308 | 398 · 604MB |
| 500 | 6.7초 / 3,756 | 7.8초 / 696 | 390 · 593MB |
| 1,000 | 6.4초 / 1,920 | 7.5초 / 390 | 387 · 577MB |
| 2,000 | 6.4초 / 1,002 | 7.4초 / 237 | 387 · 577MB |
| **5,000 (현재)** | **6.2초 / 456** | **7.3초 / 146** | 387 · 574MB |

차이가 작고 5,000 이 가장 빨라 바꾸지 않았다. 저장 시간의 대부분은 서버의 30만 행 UPDATE(행마다 인덱스 8개)다 → 48.5.

### 48.5 저장 구조 (AI6-F-06~10) — 격리 DB 비교만, 적용 안 함

**인덱스 전수 검토 (TB_INSP_RSLT)** : PK·`UX_INSP_RSLT_01`(프로젝트+fingerprint, 무결성)·`UX_INSP_RSLT_02`(이력 FK 대상) 유지 필수. `IX_01`(상태·등급 — 요약·목록), `IX_02`(규칙 — 규칙 필터·건수), `IX_03`(대상 — 대상 삭제 판정·화면 이동)은 조회가 쓴다. `IX_04`(최초 실행)는 갱신되지 않아 HOT 을 막지 않는다. **`IX_05`(최종 실행)는 유지 실행마다 모든 행에서 값이 바뀌는 유일한 인덱스 열**이라 HOT 을 막는다 (다른 인덱스 열 — 등급·대상 유형·대상·상태 — 은 유지 실행에서 같은 값을 다시 쓴다. PostgreSQL 은 값이 같으면 HOT 을 막지 않는다). `IX_05` 를 쓰는 곳 : 저장의 예외 건수 조회(`excludedFound`)와 실행 행 삭제 때의 FK 확인(`FK_INSP_RSLT_02`) 둘뿐이다.

**반복 실행 (10회 연속 + 10% 해결 + 일반 VACUUM + 2회, 실행 간격 없음)** :

| 후보 | 1회 뒤 전체 | 10회 뒤 본체 / 전체 | HOT 비율(누적, 마지막) | 유지 저장(마지막 5회) | 유지 WAL/회 |
|---|---|---|---|---|---|
| A 현재 (fillfactor 100) | 238MB | 1,504 / 1,706MB, 계속 증가 | 0% | 6.8~8.1초 | 830~940MB |
| B `IX_05` 제거 | 236MB | 1,504 / 1,684MB | 0% (페이지에 여유 없음) | 6.9~8.2초 | 660~950MB |
| C B + fillfactor 90 | 249MB | 777 / 900MB, 5회 뒤 멈춤 | 52~60% | 4.0~5.5초 | 440~830MB |
| D B + fillfactor 80 | 280MB | 730 / 844MB, 5회 뒤 거의 멈춤 | 73~77% | 3.7~4.3초 | 180~580MB |
| A90 인덱스 유지 + 90 | 251MB | 824 / 1,018MB, 계속 증가 | 0% | 8.8~9.9초 (느려짐) | 760~1,220MB |

- fillfactor 만 낮춰서는 HOT 이 0 이다 (A90). 인덱스만 지워서도 0 이다 (B — 페이지에 여유가 없다). 둘 다 해야 HOT 이 된다.
- 일반 VACUUM 은 파일을 줄이지 않는다 (모든 후보 그대로) — 그 뒤 실행이 빈 공간을 다시 써서 더 늘지 않는다. C·D 는 VACUUM 뒤 유지 실행 WAL 이 38~280MB 로 떨어졌다.

**실제 autovacuum (AI6-F-09, 기본 설정, 실행 사이 70초 — naptime 60초)** : 10회 동안 autovacuum 이 A 7회·D 8회 실제로 돌았다 (`pg_stat_user_tables.autovacuum_count`·`last_autovacuum`).

| 후보 | 결과 테이블 전체 (2·3·5·10회 뒤) | 유지 저장 | 유지 WAL/회 | HOT |
|---|---|---|---|---|
| A | 443 · 456 · 465 · 633MB (autovacuum 이 한 번 늦은 구간에 본체 +167MB) | 6.2~6.6초 | 480~700MB | 0% |
| D | 461 · 461 · 468 · 475MB | 3.5초 | 60~220MB | 73~79% |

조회 (autovacuum 실험 끝, 중앙값) : A/D 요약 53/45ms, 첫 쪽 105/91ms, 마지막 쪽 164/162ms, **규칙+검색 필터 102/195ms**, 실행 이력 4/4ms, 대시보드 269/268ms. 프로젝트 삭제(실제 서비스, 컬럼 10만·결과 30.6만·실행 13) A 6.1초, D 6.4초.

**IX_05 를 지울 때의 비용 (실행 계획)** : 예외 건수 조회는 병렬 Seq Scan 43~87ms (A 는 인덱스 72ms) — 저장 UPDATE 가 행마다 `EXCL_YN` 을 돌려주게(RETURNING) 바꾸면 조회 자체가 필요 없다(제안). 실행 행 삭제 때의 FK 확인은 결과 테이블 전체를 읽는다 (250~280ms/실행 행, A 는 인덱스로 0.05ms) — 실행 행은 지금 **프로젝트 삭제 때만** 지워지므로(결과를 먼저 지운 뒤) 실행 수만큼 늘어난다 (13실행 +0~2초, 실행 1,000회면 수 분 추정 — 측정 아님). AI-9 의 결과 보존(실행 삭제)을 만들면 다시 따져야 한다.

**추천 (사용자 결정 필요)** : **D — `IX_INSP_RSLT_05` 제거 + `TB_INSP_RSLT` fillfactor 80**. 근거 : 실제 autovacuum 아래에서 결과 테이블이 실행 수와 무관하게 거의 멈추고(475MB vs 633MB+), 유지 저장 시간 약 −45%, WAL 약 −75%, HOT 73~79%, PostgreSQL 15·18 공통 기능만 쓴다. 대가 : 첫 저장 크기 +18%(280 vs 238MB), 규칙+검색 필터 조회 0.1 → 0.2초, 프로젝트 삭제의 FK 확인이 실행 수에 비례. C 는 같은 방향이지만 효과가 작고, B·A90 은 효과가 없다. 바꾸지 않는 것(A 유지)도 가능하다 — autovacuum 이 돌면 본체가 크게 늘지 않지만 늦으면 한 번에 167MB(컬럼 10만)씩 는다.
적용 방식 후보 (AI6-F-12 / 29장) : ① V4 에 반영 — V4 는 커밋되지 않았지만 이미 V4 를 적용한 개발 DB(사용자 개발 DB·다른 세션)는 checksum 이 달라 기동이 멈춘다(그 DB 를 다시 만들어야 한다) ② 새 V5 — 다른 세션의 `V5~V7` 이 인덱스에 `AD` 로 남아 있어 번호가 겹칠 수 있다 (사용 가능한 번호 확인 필요) ③ 그대로 둔다. 어느 쪽이든 예제(프로젝트 0)의 인덱스 행·고정 인덱스 수(167)와 `V0SeedMigrationTest` 비교를 함께 고쳐야 한다. **승인 전이라 아무것도 바꾸지 않았다.** 애플리케이션 VACUUM·REINDEX·스케줄러는 만들지 않았다 (AI6-F-10).
같은 결과의 UPDATE 생략(§27)은 '최종 실행·최종 확인 일시' 의 의미를 바꿔야 해서 하지 않았다 — 구조 후보 : 규칙별 최종 실행 정보를 실행 규칙 표에 두고 결과 행은 변화가 있을 때만 갱신.

### 48.6 Fingerprint (AI6-F-11)

- v1 정규 문자열 : `v1␟P{프로젝트}␟{규칙}␟{대상 유형}␟{대상 ID,…}␟{키}…`. 앞부분(숫자·규칙·유형)에는 구분자가 올 수 없다. **키에 구분자(U+001F)가 없고 null 이 없으면** 정규 문자열을 구분자로 나눠 입력을 되찾을 수 있다(단사) — 이 경우 서로 다른 입력이 같은 값이 될 수 없다 (SHA-256 자체의 충돌은 별개 — 무시할 수 있다).
- 구조적 충돌 : 키는 사용자 입력에서 온다(식별자 이름 `upper(issue.name())`·테이블명 토큰·접두어·금칙어 키·이음동의어 짝) — ① 키 안의 구분자 `[A␟B]` = `[A, B]`, ② null = 빈 문자열. 식별자 문자 규칙이 바로 이런 이름을 찾아내는 규칙이라 실제로 생길 수 있다.
- 보완 : 모호한 입력(구분자 포함 또는 null)만 **v3**(`v3:` + SHA-256(개수 + 키마다 `길이:값`, null = `-`) — v2 와 같은 인코딩)으로 만든다. 그 밖의 입력은 v1 값이 **바뀌지 않는다** (골든 파일 `findings-v1.txt` 그대로 통과, 기존 저장 결과의 식별 유지 — 변환·초기화 필요 없음). 구분자·null 을 담은 기존 결과가 있으면 다음 검사에서 그 결과만 새 결과로 바뀐다 (개발 DB 에서 해당 행 확인 필요 — 확인하지 않았다).
- v2(용어 그룹) 는 2차 최종 보완의 길이 접두로 이미 모호하지 않다.
- 검증 : `FindingFingerprintTest` (FP01~FP07 순수 부분, 기존 v1 값 불변을 FINAL 전 구현 사본과 비교) · `EvidenceJsonTest` · `InspectionLifecycleTest` (FP15 다른 근거 충돌, FP16 같은 내용 단순 중복) · 통합 `QualityInspectionIntegrationTest.fingerprintIdentityByTarget` (FP08 이름 변경 → 같은 결과·예외 유지, FP09 삭제 후 같은 이름 재생성 → 옛 결과 TARGET_DELETED·새 결과 NEW) · 기존 L15~L17·AI6-2-06 (FP10~FP14 : 구성원 추가·일부 삭제·전체 교체·해체 후 재발·예외 유지).

### 48.7 부분 실패 · 연결 장애 (AI6-F-12)

정책(33장 표) 그대로. 새 통합 시험 (실제 PostgreSQL 연결을 `pg_terminate_backend` 로 끊는다) :
- SP01 스냅숏 직후 끊김 → 세이브포인트 생성 불가, SP02 묶음 판정 중 끊김 → 세이브포인트 되돌리기 불가, SP03 묶음 판정 뒤 끊김 → 해제 불가 : 모두 **부분 실패가 아니라** FAILED `INSPECTION_FAILED`, 결과·이력·예외 그대로, 실행이 PENDING/RUNNING 으로 남지 않음, 다음 실행 정상 (실행기·연결 풀 회복).
- ST01 저장을 모두 쓰고 확정 직전 끊김 → 커밋되지 않고 FAILED `STORE_FAILED`, 결과 그대로. ST02 끝난 실행의 재실행·재종료 → 아무것도 바뀌지 않음.
- 기존 : 묶음 실패(실제 SQL 오류·rollback-only)·모든 묶음 실패·저장 실패(훅 예외)·저장 직전 권한 상실·프로젝트 삭제·실행 중단 정리(C13·C16~C18).
- 미재현 : 커밋 응답만 잃는 장애(서버는 커밋, 클라이언트는 오류) — 이 경우 실행 행은 이미 COMPLETED 이고 `closeRun` 은 PENDING/RUNNING 만 바꾸므로 FAILED 로 덮지 않는다 (코드 근거, 실제 재현 안 함). 실행 상태 기록 자체가 실패하면 RUNNING 으로 남고 다음 기동 때 INTERRUPTED (C16~C18).

### 48.8 화면 (AI6-F-14·15)

- 실패 안내 문구 : 실패·중단 안내의 '이전 정상 검사' → '마지막 확정 검사' (마지막 확정 실행이 부분 실패였을 수 있다), 그 확정 실행이 부분 실패면 '그 검사에서 판정하지 못한 규칙의 결과는 그보다 앞선 검사 기준' 을 덧붙인다. 대시보드 카드 문구도 같게. 첫 검사부터 실패했으면 '아직 저장된 검사 결과가 없습니다' (3차 그대로).
- **영어 화면에서 찾아 고친 결함 3건** : ① 식별자 결과의 '판정 기준' 이 한국어로 보였다 — 판정 때 서버가 만든 문구(한국어 원문)가 근거에 저장되기 때문. 화면이 번역 사전(`tv`)으로 표시 언어로 바꾼다 (en.ts 에 서버 `EnglishMessages` 와 같은 13문구). ② 식별자 충돌의 '겹치는 이름' 이 '(인덱스, 테이블)' 처럼 한국어 종류 이름을 담은 문자열이었다 — 근거(`evidence.related`)에서 빼고 표시 정보 `details.related` 에 이름·종류 코드·테이블로 저장(`IdentifierQualityDto.Issue.relatedObjects`), 화면이 종류를 표시 언어로 바꾼다 (fingerprint 와 무관 — 식별 그대로, `IdentifierQualityIntegrationTest` 갱신). ③ 마지막 변화 '유지' 가 영어에서 'Keep'(다른 화면의 버튼 문구)로 보였다 → 문맥 키 '마지막 변화|유지' = Persisting. 꺼진 규칙 필터 선택지도 'Disabled rules' 로(문맥 키).
- 도움말 이미지 교체 : `public/images/help/{ko,en}/standardization-quality.jpg` (기존과 같은 1280×800 JPEG, 같은 경로 — 옛 실시간 화면 → 저장 결과 화면, 검증 전용 프로젝트 AI6_UI 의 결과 탭, 개인정보·접속 정보 없음). 도움말 본문(guide ko/en [10])은 3차에서 고쳤다. 다른 기능 도움말 이미지는 바꾸지 않았다.

### 48.9 대량 규모 (AI6-F-16, 최적화 후, PostgreSQL 18.6, 힙 2GB — PostgreSQL 15 빌드와 같은 시간에 돌려 시간 값은 참고)

| 컬럼 | 결과 | 신규 / 유지 / 10% 해결 | 실행기 할당 | GC 직후 점유 최대 | 결과 목록 첫 쪽·마지막 쪽 | 결과 테이블 |
|---|---|---|---|---|---|---|
| 10만 | 30.6만 | 7.2 / 8.6 / 9.2초 | 1.55~1.83GB | 399~584MB | 247 / 319ms | 959MB |
| 20만 | 61.2만 | 14.7 / 18.6 / 22.6초 | 3.1~3.7GB | 753~971MB | 713 / 878ms | 1.24GB |
| 30만 | 91.8만 | 23.4 / 32.6 / 38.5초 | 4.6~5.5GB | 966~1,505MB | 1,120 / 1,339ms | 1.86GB |

- 실행 뒤 남는 메모리는 어느 규모에서도 없었다 (실행 전과 같은 106MB). 30만 컬럼에서 GC 직후 점유가 힙 2GB 의 약 75% 라 **50만·100만 컬럼은 실행하지 않았다** — 같은 결과 비율(컬럼당 3.06건)이면 100만 컬럼은 약 306만 건, GC 직후 점유 약 5GB 이상, 결과 테이블 6GB 이상으로 추정한다 (측정 아님). 힙을 늘려 지원한다고 하지 않는다.
- 한계의 원인 : 결과 전체를 한 판정 스냅숏·한 저장 트랜잭션에서 다룬다(원자성 정책). 넘으려면 구조 변경이 필요하다 — 규칙 묶음·테이블 범위로 나눈 판정(AI-8 증분·테이블 단위 검사), 저장된 결과를 fingerprint 순 커서로 읽어 판정 결과와 병합(현재는 전체 목록), 유지 갱신 생략(48.5 마지막 문단). AI-8 에서 다룬다.

### 48.10 회귀 · 단일 JAR · 브라우저

- **전체 회귀 (최종 작업 트리, 격리 복사본, `./gradlew clean build --continue` — npm ci·tsc·vite 프로덕션 빌드·모든 모듈 시험·bootJar)** : **PostgreSQL 18.6 : 1,704건 · 실행 1,675 · 실패 0 · 건너뜀 29 (8분 30초), PostgreSQL 15.20 : 1,704 · 1,675 · 0 · 29 (8분 21초)** — 3차 1,693 에서 +11 (`FindingFingerprintTest` 6, `EvidenceJsonTest` 2, 통합 3). 모듈별 app 1,194(건너뜀 29) · data 303 · core 94 · common 35 · system 28 · ai 25 · source 16 · operation 9. 앞서 같은 날 두 판(식별자·화면 수정 전)도 PostgreSQL 18.6·15.20 모두 1,704 · 1,675 · 0 · 29 였다. 건너뜀 29건은 모두 `LEXORMA_PERF=true` 성능 시험이고, 원본 DB 수집 시험은 격리 원본 DB 로 실행했다. PostgreSQL 15 는 V0~V4 를 처음부터 적용했다 (18 전용 기능 없음 — `pg_stat_user_tables.n_tup_newpage_upd` 는 성능 시험만 쓰며 있을 때만 읽는다).
- 프런트엔드 : 별도 시험 도구는 없다(CLAUDE.md) — `tsc -b`·프로덕션 빌드가 전체 빌드 안에서 통과.
- **단일 JAR** (`lexorma.jar` 110MB — SPA·PostgreSQL JDBC 42.7.14·V0~V4 포함, H2·DevTools 없음; PostgreSQL 18.6, 슈퍼유저가 아닌 DB 소유자 계정, AI Provider·MCP 없음, 새 필수 환경변수 없음) : 업무 흐름 22단계 (1~18단계는 같은 소스의 JAR, 재기동 뒤 19~22단계는 최종 회귀 빌드가 만든 JAR — 둘의 차이는 도움말 이미지뿐) (로그인 → 프로젝트 → 사전 → 테이블·컬럼 → 최초 검사 20규칙 → 상세·근거 → 예외 처리·409·해제 → 규칙 끄기(적용 19)·409·다시 켜기 → 메타데이터 수정 → 재검사 해결·유지 → 재발(예외 유지) → 대상 삭제 → 실행 이력·건수 → 대시보드 → 재기동 → 요약·이력·예외 유지·새 실행 → 비활성화·프로젝트 삭제 → 검사 행 0) **57/57**, 역할·격리 26/26 (VIEWER 조회만, USER 실행·예외, 프로젝트 관리자 규칙 설정, `expectedActive` 누락 400, 다른 프로젝트 결과·실행 404, 비구성원 로그인 거절, 미인증 401, 직접 URL), 로그에 DB·계정 비밀번호 없음, ERROR 없음.
- **검사 중 일반 업무 (컬럼 10만 프로젝트 검사 9.2초 동안, 다른 프로젝트 USER)** : 대시보드·표준 단어·표준 용어·테이블·컬럼·논리 모델·ERD 테이블 찾기·품질 결과·업무 설명 수정 모두 오류 0, 검사 전/중/후 중앙값 4~39ms, 검사 중 최대 71ms, 잠금 대기 0, 앱 DB 연결 활성 최대 4, 검사 중 GC 직후 힙 최대 441MB (JAR 기본 힙). 검사 중인 프로젝트의 요약 조회만 결과가 늘어 43 → 약 110ms.
- **브라우저** (Chrome 헤드리스 — CDP, 실제 Chrome 엔진; 3차는 Chrome 확장으로 대화형 확인) : 한국어·영어 × 라이트·다크 4조합으로 결과(부분 실패·빈 검사·정상), 실행 이력, 실행 상세, 규칙 설정, 용어 그룹 근거 상세, 예외 처리 입력, 1,000자 사유의 예외 해제·이력, 긴 대상 이름(69자) 상세, 꺼진 규칙 필터, 대시보드 카드(빈 검사·정상) 캡처를 확인했다. 좁은 화면(390px) : 상태·안내·카드·필터가 줄바꿈되고 목록은 공통 그리드처럼 표 안에서만 가로 스크롤(필터 선택 상자 글자 일부 잘림 — 공통 스타일). 역할별 화면 : VIEWER 실행 버튼 없음·규칙 비활성·상세에 예외 버튼 없음, USER 규칙 비활성, 프로젝트 관리자 규칙 가능. 폴링 : 검사 중 2초 간격, 끝난 뒤 늘지 않음, 검사 중 화면을 떠나면 멈춤(대시보드 카드 1회 조회뿐).
- Firefox·WebKit 은 이 장비에 없어 확인하지 못했다. 실제 Safari 는 있지만 원격 자동화를 켜려면 사용자 설정 변경이 필요해 확인하지 못했다 (미검증).


### 48.11 신규 의존성·설정

없음 — 운영 의존성·npm 의존성·필수 환경변수·설정 파일·JVM 옵션·DB 권한 요구 변화 없음. 측정 도구(JFR·`jcmd` 히스토그램·CDP 헤드리스 Chrome 스크립트)는 시험·검증용으로만 썼고 저장소에 넣지 않았다. 저장 행 수(`storeChunk`)는 설정이 아니라 성능 시험이 반사로만 바꾼다.

### 48.12 완료 판단 · 인계

- **AI-6 완료 처리 보류** — 완료 기준 중 '최종 저장 구조 사용자 승인' 이 남았다 (48.5 추천 D / 적용 방식 ① V4 ② 새 번호 ③ 유지). 승인되면 그 구조를 반영하고(예제 인덱스 행·고정 수 포함) 같은 회귀를 다시 돌린 뒤 이 문서를 `completed/` 로 옮긴다. 원격 CI 는 사용자 커밋·푸시 뒤.
- 그 밖의 완료 기준 : 기능(20규칙·저장 화면·실시간 제거 유지·예외·집계 구분·부분 실패/빈 검사·규칙 설정 충돌·대시보드), 성능(메모리 원인 분석·개선·평가, 저장 공간 원인 재검증, 인덱스·fillfactor 비교, 실제 autovacuum, 10만 최종 측정, 일반 업무 동시 처리), 안정성(fingerprint 보완, 부분 실패·세이브포인트·저장 원자성, 예외·이력 보존, 권한·격리, M-8 잠금 그대로), 검증(PostgreSQL 18·15 전체 회귀, 프런트엔드 타입 검사·빌드, 단일 JAR 업무, ko/en·light/dark, Chrome, 도움말 이미지), 관리(신규 인프라·설정 없음, 승인 안 된 DB 구조 변경 없음, 다른 세션 파일·Git 인덱스 그대로)는 충족했다. 미충족·한계 : Firefox·WebKit·Safari, 50만 이상 실측, 원격 CI.
- **AI-7 인계** (Tool 은 만들지 않았다) : 조회는 `InspectionQueryService`(요약 `summary` — 조회 시점 집계·마지막 확정 실행·진행 중 실행·`partialFailure`·`failedRules`, 결과 목록 `findings` — 서버 페이징·필터(대상 구분·규칙·심각도·대상 유형·상태·예외·규칙 상태·마지막 변화·검색), 상세 `finding` — 근거 JSON·이력·`targetExists`, 실행 `runs`/`run` — 확정 시점 건수, 규칙 `rules` — 사용 여부·마지막 검사·재검사 필요). 모두 `projectId` 범위 — Tool 은 검증된 실행 컨텍스트의 프로젝트만 넘겨야 한다. **결과는 실시간이 아니다** : 응답에 검사 기준 시각(`basisAt`)과 규칙별 마지막 검사 시각을 함께 주고, 꺼진 규칙 결과(`ruleActive=false`)·예외(`excluded`)·부분 실패로 판정하지 못한 규칙을 구분해 전해야 한다. 목록은 페이지(최대 100)로만 — 전체 결과를 모델에 넘기지 않는다.
- **AI-8 인계** : 자동·정기·증분·테이블 단위 검사, 변경 감지·최신성, 사용자 취소, 대량 프로젝트(48.9 — 30만 컬럼에서 힙 75%, 결과 전체를 한 번에 다루는 구조), 저장 구조(48.5)·유지 갱신 생략. 실행기는 서버 스레드 1·대기열 10 그대로.
- **AI-9 인계** : 내보내기·보고서·추세·실행별 통계(실행 건수 표 `TB_INSP_EXCN_RSLT` 는 확정 시점 값으로 이미 있다), 보존 기간(실행 삭제가 생기면 `FK_INSP_RSLT_01/02` 의 참조 확인 비용 — 48.5), 과거 실행 전체 결과 스냅숏은 없다.

## 49. FINAL 최종 보완 — 저장 구조 D 적용 · Fingerprint 공통 인코딩 (2026-11-26, 커밋 전)

사용자 확정 AI6-FC-01~15 로 진행했다. 48.5 의 비교(A~D)는 그때의 격리 환경 기록으로 남기고, 아래는 **최종 V4 로 다시 잰 값**이다. AI-7 이후는 착수하지 않았다.

### 49.1 시작 상태

HEAD `c8bd92b`, 브랜치 `dev`. 인덱스는 48장 종료 때(`387ed31…`)와 다른 `984d3fd…` — FINAL 신규 파일 4개(`EvidenceJson`·`StoredItem`·`FindingFingerprintTest`·`EvidenceJsonTest`)가 사용자 쪽에서 `A` 로 스테이징되어 있었다 (시작 기준으로 기록, 에이전트는 인덱스를 바꾸지 않았다). 다른 세션 `AD` 8개 그대로.

### 49.2 저장 구조 D (AI6-FC-01~04·06·07)

- **V4 직접 수정** (새 마이그레이션 없음, V0~V3·다른 세션 V5~V7 그대로) : `create table TB_INSP_RSLT (…) with (fillfactor = 80)`, `create index IX_INSP_RSLT_05` 와 그 예제 인덱스 행 2줄(`TB_TBL_IDX`·`TB_TBL_IDX_COL`) 삭제, 머리 주석에 근거. PK·UNIQUE 2·IX_01~04·FK 2(`FK_INSP_RSLT_01/02`)·CHECK·COMMENT 그대로.
- 예제(프로젝트 0) : 고정 인덱스 수 167 → **166**, 인덱스 컬럼 239 → **238** (`LexormaIndexSeedTest`·`V0SeedMigrationTest`·`IndexManagementIntegrationTest`). 이 시험들은 마이그레이션의 `create index` 정의와 예제 행을 먼저 비교하고(누락·추가·불일치 0) 그다음 수를 확인한다 — 수만 바꾼 것이 아니다. 예제 모델에는 fillfactor 개념이 없어 따로 고칠 것이 없다 (표준 단어·용어·도메인 변경 없음).
- 실제 카탈로그 확인 : 49.8.
- 결과의 최종 실행 참조·최종 확인 일시 갱신은 그대로다 (같은 결과의 UPDATE 생략 없음). 저장 시 예외 건수 조회(`excludedFound`)는 그대로 두었다 (AI6-FC 25장 — IX_05 없이 병렬 순차 스캔 수십 ms, 문제 없음).

### 49.3 실행 이력 누적과 프로젝트 삭제 (AI6-FC-05) — **사용자 결정 필요**

조건 : PostgreSQL 18.6, 삭제할 프로젝트 컬럼 10만·결과 30.6만(실제 검사 13회), 같은 DB 에 다른 프로젝트 컬럼 10만·결과 30.6만이 남아 있음, 실제 프로젝트 삭제 서비스(`ProjectManageService.deleteProject` → `ProjectDataCleanup`), `pg_stat_statements.track=all`(참조 무결성 내부 문장 포함). 실행 100·1,000건은 실제 실행 13건 + **시험용 지난 실행**(마지막 실제 실행의 실행 규칙·변화 건수 행을 그대로 복사, 결과가 참조하지 않음)이다.

| 실행 이력 | D (최종 V4) 삭제 전체 | `delete from TB_INSP_EXCN` | 최종 실행 FK 확인 (회수·합) | A (IX_05·fillfactor 100 재현) 삭제 전체 |
|---|---|---|---|---|
| 13 | 9.97초 | 2.31초 | 13회 1.99초 | 8.78초 |
| 100 | 24.9초 | 17.4초 | 100회 17.1초 | 8.78초 |
| 1,000 | **181.2초** | 173.7초 | 1,000회 173.0초 | 9.00초 |

- 그 밖 (D·1,000) : 결과 삭제 2.1초(30.6만 행), 최초 실행 FK 확인 1,000회 0.32초(`IX_INSP_RSLT_04` 사용), 실행 규칙·변화 건수 삭제 수 ms, 결과 테이블 순차 스캔 6,917회 (같은 시간의 다른 프로젝트 조회 포함). 남은 행 0 (결과·실행·이력·고아 실행 규칙/건수), 다른 프로젝트 결과 30.6만 그대로.
- 잠금 : 삭제는 한 트랜잭션이라 그 시간 동안 삭제할 프로젝트 행이 잠긴다. 삭제 중 다른 프로젝트 조회(요약+목록) 658회 오류 0, 중앙값 275ms·최대 344ms (평소와 같다) — 다른 프로젝트는 영향이 없었다.
- 원인 : 실행 행을 지울 때 PostgreSQL 이 `FK_INSP_RSLT_02` 를 확인하려고 결과 테이블을 `LAST_INSP_EXCN_SN` 으로 찾는다. D 에는 이 열의 인덱스가 없어 실행 행마다 그 프로젝트의 결과(같은 트랜잭션에서 지워 아직 정리되지 않은 행)를 훑는다 — 결과 30.6만 기준 실행 1건당 약 0.17초, 실행 수에 비례한다. A 는 인덱스로 바로 찾는다.
- 서비스 안에서 고칠 수 있는 비효율은 없었다 (삭제 순서 : 이력 → 결과 → 실행 건수 → 실행 규칙 → 실행 → 규칙 설정, 각각 집합 단위 한 문장. FK 가 있는 한 실행 행 하나마다 확인이 돈다). 인덱스 재도입·새 인덱스·FK 제거·검사 비활성화·트랜잭션 분할·비동기 삭제·이력 자동 삭제는 승인 범위 밖이라 하지 않았다.
- 판단 : 결과 30만 건 규모 프로젝트를 매일 검사하면 약 3년에 1,000건 — 그 프로젝트를 지우면 약 3분 동안 한 트랜잭션이 돈다 (다른 프로젝트 영향 없음, 데이터 무결성 문제 없음). AI-9 의 이력 보존(실행 삭제)을 만들 때도 실행 1건당 같은 비용이 든다. **선택지 (사용자 결정)** : ① **D 유지·비용 수용** — 문서화하고, AI-9 의 이력 보존 정책에서 프로젝트당 실행 수를 제한해 상한을 둔다 (지금 1,000건이면 삭제 약 3분, 다른 프로젝트 영향 없음) ② **A 로 되돌림** — 삭제는 실행 수와 무관하게 약 9초, 대신 48.5 의 저장 공간·유지 저장 시간·WAL·HOT 이점이 없다 (V4 를 다시 고친다) ③ 승인 범위 밖의 다른 구조(FK 정의·삭제 방식 변경 등) — 이번에는 검토만 하고 하지 않았다. 인덱스 없이 이 확인 비용을 줄이는 방법은 서비스 안에서 찾지 못했다. **결정 전이라 D 그대로 두었고, 이 결정이 AI-6 완료 처리를 막는다.**

### 49.4 Fingerprint 공통 인코딩 (AI6-FC-08·09)

- 논리 키는 그대로다 : 대상 식별(`of` — 프로젝트·규칙·대상 유형·대상 ID 집합·핵심 값)과 그룹 식별(`group` — 프로젝트·규칙·대상 유형·그룹 키, 구성원 ID 없음). 프로젝트 ID 는 예전부터 키에 있었다 (UNIQUE(PRJ_SN, HASH_VL) 와 함께).
- 인코딩 `v4` (모든 규칙, `FindingFingerprint` 주석에 정의) : `S(x)` = null 이면 0x00, 아니면 0x01 + UTF-8 바이트 길이(4바이트) + UTF-8 바이트, `L(n)` = 8바이트 정수. 입력 = S("v4")·L(프로젝트)·S(규칙)·S(대상 유형)·S("T"|"G")·[T : L(대상 수)·L(대상 ID)… 오름차순]·L(키 수)·S(키)…, SHA-256. 해시 입력 문자열을 만들지 않고 다이제스트에 바로 쓴다 (메모리 — 49.6 에서 악화 없음 확인).
- 없앤 것 : v1(구분자 연결)·v2(그룹 키 길이 접두)·조건부 v3 와 그 상수·분기(`GROUP_VERSION`·`SAFE_VERSION`·`SEPARATOR`·`ambiguous`·`safe`). 변환·이중 조회·매핑 없음.
- 골든 파일 `findings-v1.txt` (99줄) : **fingerprint 열 71개만 바뀌었다** (v1 64·v2 7 → v4). 그 밖의 열(규칙·대상·심각도·메시지·현재/기대 값·근거)은 99줄 모두 같다. 예전 서로 다른 식별자 42개 → 새 식별자 42개, 1:1 대응 (같은 식별자는 같은 새 값, 다른 식별자는 다른 새 값 — 식별 의미 유지).
- 시험 : `FindingFingerprintTest` 8 (FP01~FP07, FC-FP01~FC-FP10·FC-FP16 — 문서의 인코딩을 따로 구현한 기준값과 비교, 한글·일본어·이모지·짝 없는 서러게이트·조합형, 대상/그룹 식별 구분, 규칙 접두 충돌, 예전 상수·메서드 없음), `InspectionLifecycleTest`(FP15·FP16 = FC-FP14·15), 통합 L15~L17·AI6-2-06·FP08·FP09 (FC-FP11~13 : 구성원 추가·제거·전체 교체·순서, 해체 후 재발·예외 유지, 삭제 후 재생성). 예전 판 접두(`v1:`/`v2:`)를 단언하던 단위·통합 시험 10곳을 `v4:` 로 바꿨다 (판정 단언은 그대로).
- 구조적 충돌은 인코딩으로 없다. SHA-256 자체의 이론적 충돌은 별개다 (같은 fingerprint 의 다른 내용은 실행 중 충돌 검사가 묶음 실패로 막는다 — AI6-2-05).

### 49.5 기존 개발 DB 영향 (AI6-FC-10 — 사용자 조치 필요)

- V4 내용이 바뀌어 Flyway checksum 이 달라졌다 — 수정 전 V4 를 적용한 개발 DB(사용자 개발 DB·다른 세션 DB)는 기동 때 검증이 실패해 멈춘다. 에이전트는 개발 DB·Flyway 이력·repair·검증 설정을 건드리지 않았다. **새 DB 로 다시 만드는 것은 사용자 결정·작업**이다.
- 그런 DB 를 다시 만들지 않고 쓰는 방법은 없다 (repair·이력 수정은 금지 정책). 다시 만들면 fingerprint 도 새 값으로 시작한다. 다시 만들지 않은 DB 에 저장된 결과는 예전 판(v1·v2·v3) fingerprint 라, 그 DB 에서 검사하면 같은 문제가 새 결과로 생기고 예전 결과는 해결로 닫히며 예외·이력이 새 결과로 넘어가지 않는다.

### 49.6 최종 성능 (최종 V4·v4 fingerprint, PostgreSQL 18.6 tmpfs, 힙 2GB, G1, 측정마다 새 DB — 48장과 같은 조건)

| 컬럼 | 신규 / 유지 / 10% 해결 (반복 유지) | 실행기 할당 | GC 직후 점유 최대 | 실행 뒤 전체 GC |
|---|---|---|---|---|
| 1천 | 299 / 138 / 116ms | 17~24MB | — (GC 없음) | 106MB |
| 1만 | 750 / 694 / 623ms | 147~177MB | 124~134MB | 106MB |
| 10만 | 7.4 / 7.7 / 7.7초 (6.7·6.5초) | 1.47~1.75GB | 394~588MB | 106MB (반복 5회 누적 없음) |

- 판정 직후 살아 있는 힙(앱 포함) 265~275MB — 48장(265~276MB)과 같다. 공통 인코딩으로 메모리가 나빠지지 않았다. GC 최장 정지 6~35ms.
- **10회 반복 (D, 실행 간격 없음)** : 결과 테이블 전체 1회 280 → 2회 461 → 3회 587 → 5회 725 → 10회 839MB (본체 725·인덱스 114·TOAST 0) → 일반 VACUUM 뒤 847MB 그대로, 그 뒤 2회 더 늘지 않음. HOT 누적 26.5 → 47.2 → 70.9 → 77.3%. 유지 저장 6.5 → 3.8초(6~10회), VACUUM 뒤 3.1~3.3초. 실행당 WAL 168~674MB, VACUUM 뒤 45~153MB. 48.5 의 D(844MB, HOT 73~77%, 3.7~4.3초)와 같다 — 영구적인 크기 보장이 아니라 이 조건의 측정이다 (실제 autovacuum 효과는 운영 환경에 따라 다르다 — 48.5 의 70초 간격 실험에서는 475MB).
- 조회 (10회 + VACUUM + 2회 뒤, 중앙값) : 요약 64ms, 목록 첫 쪽 130ms, 마지막 쪽 208ms, **규칙+검색 필터 391ms**, 실행 이력 3ms, 대시보드 270ms. **연속 실행 직후(VACUUM 전) 조회가 느려진다** : 같은 데이터로 실행 5회를 간격 없이 돌린 직후 목록 첫 쪽 842ms·마지막 쪽 951ms·필터 377ms (48장 A 같은 조건 247·319·98ms) — HOT 체인과 정리 전 지난 판을 지나기 때문이고, 정리(autovacuum) 뒤에는 위 값으로 돌아온다.
- 일반 업무 동시 처리 : 49.9.

### 49.7 회귀 · 단일 JAR · 화면 (최종 작업 트리)

- **전체 회귀** (`./gradlew clean build --continue`, 격리 복사본 — npm ci·`tsc -b`·vite 프로덕션 빌드·모든 모듈 시험·bootJar, 새 관리 테스트 DB + 별도 원본 테스트 DB) : **PostgreSQL 18.6 1,707건 · 실행 1,677 · 실패 0 · 건너뜀 30 (8분 49초), PostgreSQL 15.20 1,707 · 1,677 · 0 · 30 (8분 32초)**. 48장 1,704 에서 +3 (`FindingFingerprintTest` 6 → 8, opt-in 삭제 성능 시험 1 — 건너뜀 30 은 모두 `LEXORMA_PERF=true` 성능 시험). 모듈 app 1,195 · data 305 · core 94 · common 35 · system 28 · ai 25 · source 16 · operation 9. 원본 DB 수집 시험은 격리 원본 DB 로 실행했다. AI·MCP 회귀(기존 MCP Tool 시험 포함)는 이 안에서 통과했다.
- **단일 JAR** (`lexorma.jar` 110,709,531 바이트, SHA-256 `df272e838ab2d9fa1ac9efac98054294d764ecab809789ad6e1a9d5fae485cfe`, PostgreSQL 15 회귀 빌드 산출물 — 18 회귀 빌드와 같은 크기·같은 소스) : SPA·PostgreSQL JDBC 42.7.14·V0~V4(수정한 V4 — fillfactor 80, IX_05 없음) 포함, V5 없음, H2·DevTools 없음. PostgreSQL 18.6 새 DB, 슈퍼유저가 아닌 소유자 계정, AI Provider·MCP 없음, 새 필수 설정 없음. 업무 흐름 22단계 57/57 (재기동 전 48, 재기동 뒤 9 — 남은 검사 행 0), 공통 fingerprint 추가 검증 11/11 (반복 검사 결과 행 그대로·NEW 0, 모든 값 v4, 그룹 구성원 추가·제거에도 같은 결과와 예외 유지, 해체 → 실제 해결, 같은 키 재발 → REOPENED·예외 유지, 최종 실행 참조 = 이번 실행, 예외 해제 뒤 과거 실행 건수 그대로·현재 요약만 바뀜), 로그에 비밀번호·ERROR 없음.
- **일반 업무 동시 처리** (컬럼 10만 프로젝트 첫 검사 8.7초 동안, 다른 프로젝트 USER) : 대시보드·표준 단어·용어·테이블·컬럼·논리 모델·ERD 테이블 찾기·업무 설명 수정 오류 0, 검사 전/중/후 중앙값 3~47ms, 검사 중 최대 72ms, 잠금 대기 0, 앱 DB 연결 활성 최대 2, 검사 중 GC 직후 힙 최대 330MB (JAR 기본 힙). 검사 중인 프로젝트 자기 결과 목록은 결과가 30.6만 건이 된 뒤 첫 쪽 약 0.38초 (49.6 의 연속 실행 직후 특성).
- **화면** (최종 JAR, 헤드리스 Chrome) : 한국어 라이트·영어 다크로 결과·상세·예외 입력·실행 상세·규칙 설정·대시보드 카드, 교체한 도움말 이미지(ko 98,937 / en 107,085 바이트) 제공 확인. 화면 코드는 이번에 바꾸지 않았다 — 나머지 언어·테마 조합과 좁은 화면은 48.10 결과 그대로. Firefox·WebKit·실제 Safari 는 환경 제약으로 미검증 (완료 차단 조건 아님).

### 49.8 실제 DB 구조 확인 (시스템 카탈로그, 회귀에 쓴 새 DB — PostgreSQL 18.6·15.20 같은 결과)

- Flyway 0·1·2·3·4. `pg_class.reloptions` of `tb_insp_rslt` = `fillfactor=80`. `tb_insp_rslt` 인덱스 = `pk_insp_rslt`·`ux_insp_rslt_01`·`ux_insp_rslt_02`·`ix_insp_rslt_01~04` (**`ix_insp_rslt_05` 없음**).
- 검사 6개 테이블 제약 : PK 6, UNIQUE 7, FK 6 (`fk_insp_rslt_01/02` 포함), CHECK 12, NOT NULL 41 (PostgreSQL 18 은 NOT NULL 도 제약 행으로 보인다 — 15 는 없음, 같은 정의). 컬럼 79·COMMENT 79.
- 예제(프로젝트 0) : `TB_INSP_RSLT` 인덱스 행 `IX_INSP_RSLT_01~04`, 예제 인덱스 전체 166·인덱스 컬럼 238 = 실제 DB 의 제약 아닌 인덱스 166 (세션·Flyway 테이블 제외).

### 49.9 잔여 위험 · 인계

- 잔여 위험 : 49.3 실행 이력 누적 시 프로젝트 삭제 비용(사용자 결정), 49.6 연속 실행 직후 조회 지연(autovacuum 의존), 30만 컬럼 이상 메모리(48.9), 개발 DB 재생성 필요(49.5).
- AI-7 · AI-8 · AI-9 인계는 48.12 그대로이고, AI-9 에 '실행 삭제 1건당 FK 확인 비용(49.3)' 을, AI-8 에 '대량 최초 검사 — 증분 검사만으로 해결되지 않는다(48.9)' 를 더한다.

## 50. 최종 승인 · 완료 처리 (2026-11-27, 사용자 확정 AI6-CLOSE-01~10)

| ID | 확정 |
|---|---|
| AI6-CLOSE-01 | 저장 구조 D 최종 채택 — `TB_INSP_RSLT` fillfactor 80, `IX_INSP_RSLT_05` 없음, 수정한 V4 유지(새 마이그레이션 없음), PostgreSQL 기본 autovacuum, 애플리케이션 강제 VACUUM 없음, fingerprint v4 공통 인코딩 유지. 구조 A 로 되돌리지 않는다 |
| AI6-CLOSE-02 | 대량 실행 이력 삭제 비용 수용 (49.3) |
| AI6-CLOSE-03 | 기존 FK·무결성 제약 유지 (`FK_INSP_RSLT_01/02` 포함) |
| AI6-CLOSE-04 | 실행 이력 수 상한은 지금 정하지 않는다 |
| AI6-CLOSE-05 | 실행 이력 보존·삭제 구조는 AI-9 에서 검토 |
| AI6-CLOSE-06 | 장시간 프로젝트 삭제와 HTTP 타임아웃 위험을 후속 과제로 등록 (pending-work.md [PRJ-DEL]) |
| AI6-CLOSE-07 | 연속 검사 직후 조회 지연은 AI-8 에서 검토 |
| AI6-CLOSE-08 | 이번에 코드·시험·마이그레이션·예제 데이터 변경 없음 (문서만) |
| AI6-CLOSE-09 | AI-6 완료 처리, 이 문서를 `docs/completed/` 로 이동 |
| AI6-CLOSE-10 | 커밋·푸시는 사용자가 직접 |

**삭제 비용 — 설계 결정의 근거로 남긴다 (해결된 것이 아니다)** : 프로젝트 삭제 시간 (결과 30.6만, 다른 프로젝트 결과 30.6만 존재, PostgreSQL 18.6, 49.3)

| 실행 이력 | 구조 A | 구조 D (채택) |
|---:|---:|---:|
| 13건 | 8.8초 | 10.0초 |
| 100건 | 8.8초 | 24.9초 |
| 1,000건 | 9.0초 | 181초 (그중 `FK_INSP_RSLT_02` 확인 1,000회 173초) |

반복 실행되는 품질 검사의 저장 성능·저장 공간·WAL 개선(48.5·49.6)을 우선해 이 삭제 비용을 받아들였다. **삭제 성능 문제는 해결되지 않았다.** '다른 프로젝트 조회에 영향 없음·데이터 무결성 문제 없음' 은 그 시험 조건(한 서버, 삭제 중 다른 프로젝트 요약·목록 조회)에서 확인한 사실이다.

**후속 과제** (pending-work.md) :
- [AI-8] 자동·정기·증분 검사, 대규모 최초 전체 검사 구조 개선(증분 검사만으로 해결된다고 보지 않는다), 30만 컬럼 이상 메모리 부담(48.9), 연속 검사 직후 조회 지연(49.6), 검사 빈도에 따른 저장 공간·WAL 영향, 실행 이력 누적 규모 분석.
- [AI-9] 실행 이력 보존 정책, 최초·최종 실행 FK 참조 관계 분석, 실행 이력 삭제 비용 개선(실행 수 제한만으로 FK 확인 비용이 해결된다고 보지 않는다 — 결과 수에도 비례), 과거 집계·품질 추세 보존, 보존 기간·최대 실행 건수 검토.
- [PRJ-DEL] 장시간 프로젝트 삭제 요청, HTTP·리버스 프록시 타임아웃, 클라이언트 연결이 끊긴 뒤 서버 처리 상태, 삭제 완료 여부의 사용자 확인, 중복 삭제 요청 방지, 장시간 트랜잭션·잠금 영향 — 구현 방식은 별도 설계 때 사용자와 정한다.

**최종 검증 (49장 그대로 — 이번에 다시 돌리지 않았다, 코드·마이그레이션이 그때와 같다)** : PostgreSQL 18.6·15.20 각각 1,707건 중 실행 1,677 통과·실패 0·opt-in 성능 시험 30 건너뜀, 단일 JAR 업무 57/57, FP01~16·FC-FP01~16 통과, 규칙 20, 예제 인덱스 166·인덱스 컬럼 238, 신규 필수 설정·의존성 없음. **미검증** : Firefox·WebKit·실제 Safari, 컬럼 50만 이상, 커밋 응답만 잃는 장애의 실제 재현, 원격 GitHub Actions(사용자 커밋·푸시 뒤), 장시간 삭제 요청의 HTTP 타임아웃 영향.

**AI-6 완료 (로컬 구현·검증)** — 원격 CI 는 사용자 커밋·푸시 뒤 확인한다. 다음 작업은 AI-7 (착수하지 않았다). 인계는 48.12·49.9.

**문서 참조 경로 (2026-11-27 커밋 전 정리)** : `V4__quality_inspection.sql` 1행 머리 주석과 `InspectionCodes.java` 주석의 경로를 `docs/completed/ai-metadata-quality-diagnosis.md` 로 고쳤다 (주석만 — V4 checksum 이 바뀌어 수정 전 V4 를 적용한 개발 DB 는 다시 만들어야 한다, 49.5).
