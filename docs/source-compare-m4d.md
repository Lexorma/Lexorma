# M-4d 원본 DB ↔ 물리 모델 비교

> 2026-11-14 구현. **읽기 전용** : 원본 DB 에 접속하지 않고(마지막 반영 수집만 읽음), 물리 모델·매핑을 바꾸지 않으며, 비교 결과를 저장하지 않는다.
> 가져오기(M-4e)는 미착수. 정책은 [m4-foundation.md](m4-foundation.md), 매핑은 [source-mapping-m4c.md](source-mapping-m4c.md).

## 1. 기존 구조와 재사용

| 영역 | 쓰는 것 | 비고 |
|---|---|---|
| 원본 메타데이터 | 테이블 버전(`TB_SRC_TBL_VER`)의 정규화 JSON (`SourceCatalogCanonical.Table`) : 컬럼·PK/UNIQUE/CHECK·인덱스·FK | 반영 순서 R 시점의 테이블마다 마지막 버전. 같은 시점을 한 번에 읽는다 |
| core 계약 | `SourceCatalogReader` 에 **`tableDetails(projectId, connectionId, R, ids)`** 와 `NameKind.COLUMN` 만 추가 | 값(record)만 오간다. `lexorma-data` 는 `lexorma-source` 를 참조하지 않는다 (`ModuleBoundaryTest`) |
| 매핑 상태 | M-4c `SourceMappingService` 의 상태 계산(같은 패키지에서 `basis()`) | 매핑 화면과 같은 계산 : 재확인·충돌·원본 없음·모델 없음을 그대로 반영 |
| 이름 규칙 | `SourceCatalogReader.compareName` (원본 DBMS 의 접기 규칙, `SourceIdentifiers`) | M-4c 와 다른 이름 정책을 만들지 않는다 |
| 모델 타입·NOT NULL·DEFAULT | `TableDdlGenerator.mapType` · `isNotNull` · `defaultLiteral` (공개로 바꿈) | "모델이 만드는 PostgreSQL DDL" 을 비교 기준으로 쓴다 |
| 모델 읽기 | `SourceCompareModelRepository` : 종류마다 스칼라 JPQL 한 번 (IN 1000개씩) | 엔티티·연관(표준 용어)을 올리지 않아 N+1 이 없다 |

`lockConnection`(M-4c 후속, 쓰기 정합성용)은 비교에서 쓰지 않는다.

## 2. 영속 세부 매핑 — 만들지 않음

- 컬럼·제약조건·인덱스·FK 는 **비교할 때 대응을 계산**한다 (TABLE 매핑 안에서 비교 이름 또는 구조). 신규 매핑 테이블·마이그레이션 없음.
- 이름이 다르면 구조가 같아도 같은 객체로 확정하지 않는다 → "이름 변경 후보" 로 제안만 한다.
- 영속 매핑이 필요해지는 시점 : 사용자가 이름 변경 후보를 **확정**해 가져오기(M-4e)에 쓰려 할 때. 그때 V3 이후로 검토한다 (사용자 결정).

## 3. 비교 상태

| 상태 | 의미 |
|---|---|
| SAME | 비교한 속성이 모두 같다. 비교하지 않은 속성(NOT_COMPARED)은 따로 보인다 |
| MODIFIED | 양쪽에 있고 다른 속성이 있다 |
| ADDED_IN_SOURCE / ADDED_IN_MODEL | 한쪽에만 있다 |
| RENAME_CANDIDATE | 한쪽에만 있지만 맞은편에 구조가 같은 객체가 있다 (제안, `ambiguous` 면 여럿) |
| CONFLICT | 매핑·이름이 부딪힌다 |
| UNKNOWN | 판단하지 못했다. **SAME 이 아니고 삭제 대상도 아니다** |

- 속성 결과 : SAME · DIFFERENT · UNKNOWN · NOT_COMPARED(모델에 칸이 없음, 모델이 이름을 만들어 씀, 원본 COMMENT).
- 객체 상태 : DIFFERENT 가 있으면 MODIFIED, 없고 UNKNOWN 이 있으면 UNKNOWN, 아니면 SAME.
- TABLE 상태 : 차이 객체(SAME·UNKNOWN 이 아닌 것)가 있으면 MODIFIED, 없고 UNKNOWN 객체가 있으면 UNKNOWN.
- M-4c 매핑 상태 → TABLE 비교 상태

  | 매핑 | 비교 상태 (이유) |
  |---|---|
  | 매핑됨 | 하위 객체 비교 결과 (COMPARED) |
  | 미매핑, 스키마 매핑 없음 | UNKNOWN (SCHEMA_UNMAPPED) |
  | 미매핑, 같은 이름 모델 TABLE 후보 | UNKNOWN (MAPPING_CANDIDATE) — 임의로 이어 보지 않는다 |
  | 미매핑, 같은 이름 모델 TABLE 이 다른 원본에 매핑됨 / 같은 이름 모델 VIEW | CONFLICT (MODEL_NAME_TAKEN / VIEW_NAME_TAKEN, D-25) |
  | 미매핑, 후보 없음 | ADDED_IN_SOURCE (UNMAPPED) |
  | 재확인 필요 | UNKNOWN (RECHECK) — 비교하지 않는다 |
  | 충돌 | CONFLICT (MAPPING_CONFLICT) |
  | 원본 없음 | ADDED_IN_MODEL (SOURCE_REMOVED) — 삭제 후보 표시만, 자동 삭제 없음 |
  | 모델 없음 | ADDED_IN_SOURCE (MODEL_DELETED) |
  | (모델 쪽) 매핑된 모델 스키마의 어떤 원본 행에도 나오지 않는 모델 TABLE | ADDED_IN_MODEL (MODEL_ONLY). 매핑되지 않은 모델 스키마는 판단하지 않는다 |

- VIEW 는 비교하지 않는다 (요약에 개수만, 정책 5.9). TABLE 이름 변경은 M-4c 정책대로 삭제 + 새로 생김이다 (TABLE 단위 후보 없음).

## 4. 객체별 비교 규칙 (PostgreSQL 원본 → PostgreSQL 대상, 정책 5.8)

### 4.1 이름·짝짓기
- 원본 이름은 카탈로그의 비교 이름, 모델 이름은 `compareName(COLUMN)` 으로 접는다 (PostgreSQL : 따옴표 없는 이름은 소문자).
- 컬럼·CHECK·인덱스·이름 있는 UNIQUE·이름 있는 FK : **이름으로만** 짝짓는다.
- 모델이 이름을 저장하지 않는 객체는 구조로 짝짓고 이름은 NOT_COMPARED : PK(DDL 의 `PK_테이블`), 컬럼 고유값 표시로 만든 UNIQUE(`UK_테이블_컬럼`), 참조 테이블명 약식 입력 FK(`REF_COLUMN`).
- 모델에서 비교 이름이 같은 컬럼이 여럿이면 CONFLICT + 경고.

### 4.2 컬럼
| 속성 | 규칙 |
|---|---|
| 타입·길이·정밀도·소수 | 원본 `format_type` 과 모델 `mapType(POSTGRESQL)` 을 같은 규칙으로 나눈다 (`PostgresCompareRules.type`). 별칭(`character varying`=VARCHAR, `int4`=INTEGER, `numeric(10,0)`=NUMERIC(10), `character`=CHAR(1), 시간 타입의 생략 정밀도=6)만 같게 본다. REAL↔DOUBLE PRECISION, TIMESTAMP↔TIMESTAMPTZ 는 다르다. 별칭을 모르는 타입(사용자 정의, 배열 외)은 표기가 같으면 SAME, 다르면 UNKNOWN |
| NULL 허용 | 모델은 DDL 기준(PK·필수·NULL 불허면 NOT NULL). 원본이 값을 주지 않으면 UNKNOWN |
| 자동 증가 | 원본 IDENTITY 절 / `nextval(…)` ↔ 모델 IDENTITY 절 / SERIAL 계열 / `nextval` 기본값 |
| DEFAULT | 안전한 정규화(리터럴의 `::type`, 숫자 괄호·따옴표, 바깥 괄호, 따옴표 밖 대소문자·공백)를 한 뒤 같으면 SAME. 한쪽만 있으면 DIFFERENT, 둘 다 리터럴·키워드 하나면 DIFFERENT, 그 밖(함수·식 모양 차이)은 UNKNOWN. SERIAL 의 시퀀스 기본값은 SAME |
| 순서 | 양쪽 모두에 있는 컬럼 사이의 **상대 순서** (원본 위치 번호의 빈 자리, 한쪽에만 있는 컬럼 영향 없음) |
| 원본 COMMENT | 모델의 원본 COMMENT 칸(V3 `SRC_ANTN_CN`, M-4e)과 비교한다 (2026-11-14 변경 — 이전에는 표시만, NOT_COMPARED). 업무 설명과는 비교하지 않고 덮어쓰지 않는다. TABLE 의 원본 COMMENT 는 객체 `TABLE` 로 비교한다 |
| 경고 | 모델 타입이 DDL 에서 다른 타입이 되는 경우(`REAL` → DOUBLE PRECISION, `DATETIME` → TIMESTAMP), 모델 타입 없음 |

### 4.3 제약조건·인덱스
- **PK** : 구성 컬럼(순서 포함). 복합 PK 지원.
- **UNIQUE** : 구성 컬럼(순서 포함). DDL 생성기처럼 PK 와 같은 구성·중복 구성은 모델 쪽에서 뺀다.
- **CHECK** : 식 정규화 후 같으면 SAME, 아니면 UNKNOWN (의미 동등성을 추정하지 않는다).
- **FK** : 참조 TABLE(원본 참조 대상 → 유효한 TABLE 매핑 → 모델 TABLE), 로컬·참조 컬럼(순서 포함). 참조 대상 원본 TABLE 이 매핑되지 않았으면 UNKNOWN. 삭제·갱신 규칙은 모델에 칸이 없다 : 원본이 NO ACTION 이면 SAME(DDL 기본과 같음), 아니면 UNKNOWN. 모델의 LOGICAL 관계(추정·문서용)는 비교하지 않는다 — 실제 FK 만.
- **인덱스** : 고유 여부, 유형(모델 빈 값 = BTREE), 검색 키(컬럼·식·정렬, 순서대로), INCLUDE, 부분 조건(식 규칙). 식이 다르면 UNKNOWN. PostgreSQL 의 PK·UNIQUE 제약조건이 만든 같은 이름의 고유 인덱스는 인덱스 비교에서 빼고 경고로 알린다. 수집기가 주지 않는 속성(fillfactor·tablespace 등)은 추정하지 않는다.

### 4.4 이름 변경 후보
- 대상 : 짝을 찾지 못한 원본에만·모델에만 있는 객체.
- 기준 : 컬럼 = 타입(길이·정밀도·소수 포함) + NULL 허용, UNIQUE = 구성 컬럼, CHECK = 정규화한 식, FK = 구성·참조 대상·참조 컬럼, 인덱스 = 이름 외 모든 속성.
- 후보가 여럿이거나 맞은편에 다른 후보가 있으면 `ambiguous`. 후보가 없으면 만들지 않는다. 저장하지 않고 SAME 으로 바꾸지 않는다. 유사도·AI 추정 없음.

## 5. 수집 버전 일관성

- 비교 기준 = 기준 연결 + 마지막 반영(COMPLETED) 수집의 반영 순서 R. PARTIAL 수집의 범위 밖 객체는 앞 버전이 유효하다 (M-4c·AI-1c 규칙 그대로, 삭제로 보지 않는다).
- 한 요청은 `REPEATABLE READ` 읽기 트랜잭션이다 : 매핑·모델·원본을 같은 스냅샷에서 읽고, 원본 하위 객체는 R 로 고정해 읽는다.
- 요청 사이 : 목록·상세·후보 요청에 요약에서 받은 `connectionId`·`reflectedOrder` 를 보내면, 그 사이 기준 연결이 바뀌었거나 새 수집이 반영된 경우 **409 `SOURCE_COMPARE_STALE`** (새 오류 코드). 화면은 새로 고침을 안내한다.

## 6. API (`/api/sourceCompare`, 모두 GET, VIEWER)

| 경로 | 내용 |
|---|---|
| `/api/sourceCompare` | 요약 : 기준 연결, 비교 시점, 원본 TABLE·VIEW 수, 비교·같음·다름·판단 불가·미매핑·모델에만·충돌·재확인 건수, 상태·이유별 건수, 비교 불가 사유(NO_BASE·NO_COLLECTION·UNSUPPORTED_DBMS) |
| `/api/sourceCompare/tables` | TABLE 비교 목록 (`status`·`sourceSchemaId`·`keyword`, 쪽 단위 최대 500) : M-4c 매핑 상태와 비교 상태, 종류별 차이 수 |
| `/api/sourceCompare/table?key=` | TABLE 상세 (`key` = `S`+원본 TABLE ID / `M`+모델 TABLE ID, `kind` 로 종류 거르기) : 객체별 원본 값·모델 값·속성 결과·설명, 이름 변경 후보, 경고 |
| `/api/sourceCompare/renameCandidates` | 비교한 모든 TABLE 의 이름 변경 후보 (`kind`·`keyword`, 쪽) |

- 변경 API 없음. 로그인한 프로젝트만 (다른 프로젝트의 key 는 "비교 대상에 없는 TABLE", 다른 프로젝트의 연결을 기준으로 보내면 409).
- 요청 로그 본문은 남기지 않는다 (`@ProtectedLogBody` : 원본 DEFAULT·CHECK 식, 대량 목록).
- DTO(`SourceCompareDto`)는 객체·속성 단위의 원본 값/모델 값/결과라 M-4e 가 "무엇을 가져올지" 고를 때 그대로 쓸 수 있다. 가져오기 실행 계약은 만들지 않았다.

## 7. 화면 (원본 DB 관리 > 원본·모델 비교, `/sourceCompare`, 메뉴 `source-compare` VIEWER)

- 비교 요약 : 기준 연결, 비교 시점, 건수 타일(차이·충돌·재확인은 강조), 비교 불가 안내와 매핑·수집 화면 링크, VIEW 미비교·UNKNOWN·NOT_COMPARED 설명.
- TABLE 비교 : 상태 필터(건수)·이름 검색·50건 쪽. 원본 TABLE, 모델 TABLE, 매핑 상태, 비교 상태(이유), 차이 수(종류별), [상세].
- TABLE 상세 대화상자 : 객체 종류 고르기, "같음 숨기기", 객체마다 속성 행(속성 | 원본 값 | 모델 값 | 결과 + 설명), 후보·모호함, 경고, 이 TABLE 의 이름 변경 후보.
- 이름 변경 후보 목록 (판단 근거 = 같은 속성 + 원본 값 요약).
- 가져오기 버튼 없음. Lucide 아이콘, 한국어·영어, 라이트·다크.

## 8. 검증

| 항목 | 결과 |
|---|---|
| H2 전체 (`./gradlew build --rerun-tasks`, React 빌드 포함) | 625 통과, 실패 0, 건너뜀 21 (성능 테스트 1 포함) |
| PostgreSQL 18.6 새 임시 DB 전체 | 625 통과, 실패 0, 건너뜀 15 |
| `SourceCompareIntegrationTest` (5) | 비교 정확성, TABLE 상태, 매핑·수집 상태, 기준 연결 변경, 권한·격리 — H2·PostgreSQL 통과 |
| `PostgresCompareRulesTest` (3) | 타입 별칭·정밀도, 식 정규화, 자동 증가 |
| M-4c 회귀 (`SourceMappingIntegrationTest` 10, 동시성 T-01~T-08 포함) | 통과 (PostgreSQL 동시성 테스트 유지) |
| `ModuleBoundaryTest` | 통과 |
| React `tsc -b`·`vite build` | 통과 |

- **비교 정확성 테스트가 다루는 것** : 같은 TABLE(SAME, PK 이름 NOT_COMPARED), 원본에만·모델에만 컬럼, 타입(bigint↔INTEGER)·길이·NULL 허용·DEFAULT·컬럼 순서 차이, SERIAL ↔ nextval, 리터럴 형 변환·CURRENT_TIMESTAMP·numeric(12,2), 원본 COMMENT 미비교, 이름 변경 후보 1:1·모호함(2:1), 컬럼 고유값 UNIQUE ↔ 원본 제약조건, 제약조건 인덱스 제외, CHECK 정규화 SAME·식 모양 차이 UNKNOWN, FK CASCADE → UNKNOWN, 인덱스 정렬 SAME·이름만 다른 후보, 복합 PK·복합 FK·복합 인덱스·이름이 다른 복합 UNIQUE 후보, 모르는 타입 UNKNOWN, 종류 거르기, 읽기 전용(모델·매핑·카탈로그 지문 무변경).
- **매핑·수집 상태** : PARTIAL(범위 밖 유지), FULL 삭제 → 모델에만(원본 없음), 재생성 → 재확인(UNKNOWN, 상세 비교 없음), 모델 이동 → 충돌, FK 대상의 매핑이 무효 → 참조 UNKNOWN, 모델 삭제 → 원본에만, 수집 버전이 바뀌면 새 시점·이전 시점 요청 409, 기준 연결 변경 409·NO_COLLECTION·NO_BASE.
- **권한** : VIEWER 가 컨트롤러로 요약·목록·상세·후보 조회, 다른 프로젝트 key 거절, 다른 프로젝트 결과에 이 프로젝트의 객체 없음. 원본 DB 직접 연결 없음(없는 호스트로 등록한 연결로 비교).
- **브라우저 (headless Chrome, bootJar `h2-local`, 실제 PostgreSQL 원본 `m4d_src` — 확인 후 삭제)**
  - 원본 : Lexorma 자체 V0 DDL 로 `TB_SCHM`·`TB_TBL`·`TB_COL` 을 만들고 일부러 차이를 넣음 (TBL_NM 길이, 원본에만 있는 컬럼, ENCPT_YN → ENCRYPT_YN, EXMPL_CN 제거, CHECK 추가, FK ON DELETE CASCADE), 원본에만 있는 TABLE(IDENTITY), VIEW.
  - API 로 연결·FULL 수집·기준 연결·스키마 매핑·후보 확정을 한 뒤 비교 화면 확인 : 차이·이름 변경 후보(encrypt_yn ↔ ENCPT_YN)·모델에만(EXMPL_CN, FK_COL_02)·CHECK 원본에만·FK 판단 불가(CASCADE)·제약조건 인덱스 경고·원본 COMMENT 미비교.
  - **찾아서 고친 문제** : 원본 `timestamp(6)` 과 모델 `TIMESTAMP` 가 "다름" 으로 나왔다. PostgreSQL 은 시간 타입의 정밀도를 생략하면 최대(6)라 같은 타입이다 → 생략 정밀도를 6 으로 정규화 (단위 테스트 추가, 다시 확인 시 같음).
  - 한국어·영어, 라이트·다크, 1440·375px : 페이지 가로 넘침 0, 상세 대화상자 넘침 0 (좁은 화면에서는 표 안에서만 가로 스크롤). 콘솔 오류 없음 (확인 스크립트의 로그인 전 401 1건 제외).

## 9. 성능 (`SourceComparePerformanceTest`, `LEXORMA_PERF=true`)

- 환경 : Apple Silicon 개발 PC, JDK 21, PostgreSQL 18.6(docker, 새 임시 DB), Spring Boot 테스트 컨텍스트. 서비스 호출 단위(HTTP·JSON 직렬화 시간 제외, 응답 크기는 따로 직렬화해 잼). 첫 호출을 버리고 3회 중 가장 빠른 값.
- 데이터 : public 스키마 TABLE N 개 (컬럼 10, PK, 인덱스 1, 앞 TABLE 로 가는 FK 1, 컬럼 COMMENT). 모델에 같은 TABLE 을 만들고 모두 매핑, 10% 는 컬럼 길이가 다름.
- SQL 문 = Hibernate 가 준비한 문장 수, 할당 = 그 스레드가 할당한 바이트 (GC 전, 메모리 점유가 아니라 만든 객체 양).

| TABLE | 호출 | 시간(ms) | SQL 문 | 할당(MB) | 응답(KB) |
|---|---|---|---|---|---|
| 300 | M-4c 매핑 현황 / TABLE 목록 50 | 14 / 12 | 12 / 9 | 10 | 0.7 / 15.2 |
| 300 | M-4d 요약 · 목록(첫·마지막 쪽·필터+검색) · 후보 | 104~109 | 16 | 47 | 0.1~18.5 |
| 300 | M-4d TABLE 상세 | 15 | 16 | 10.6 | 6.8 |
| 1,000 | M-4c 매핑 현황 / TABLE 목록 50 | 34 / 32 | 12 / 9 | 34 | 0.7 / 15.3 |
| 1,000 | M-4d 요약 · 목록 · 후보 | 222~306 | 16 | 156 | 0.1~18.7 |
| 1,000 | M-4d TABLE 상세 | 34 | 16 | 34 | 6.8 |
| 3,000 | M-4c 매핑 현황 / TABLE 목록 50 | 90 / 92 | 12 / 9 | 101 | 0.7 / 15.6 |
| 3,000 | M-4d 요약 · 목록 · 후보 | 333~351 | 26 | 468 | 0.1~19.6 |
| 3,000 | M-4d TABLE 상세 | 88~89 | 16 | 103 | 6.1~6.8 |

- 쿼리 수는 TABLE 수와 무관하다 (3,000 에서 26 = IN 목록 1000개씩 나눈 만큼만 늘어남). N+1 없음.
- 쪽 나누기 : 응답은 쪽 크기만큼(50건 ≈ 19KB)이고, 서버는 상태 필터·건수를 위해 매번 전체를 계산한다.
- **주요 병목 = 할당량** : 3,000 TABLE 의 목록·요약 한 번에 약 468MB 를 만든다. 원인 : (1) M-4c 상태 계산의 `latest()` 가 모든 테이블 버전 JSON 을 전부 읽고, (2) 비교가 같은 JSON 을 상세로 한 번 더 읽고, (3) 모든 매핑 TABLE 의 객체·속성 결과를 만든다. 응답 시간은 1초 미만이지만 동시 요청이 많으면 GC 부담이 된다 → 후속(11장).
- 측정은 TIMESTAMP 정밀도 정규화(8장) 전에 했다 : 바뀐 것은 정수 하나의 기본값이라 비용에 영향이 없다.

## 10. 바꾼 파일

- core : `SourceCatalogReader` (`tableDetails`, `TableDetail`·`Column`·`Constraint`·`Index`·`ForeignKey`, `NameKind.COLUMN`)
- source : `SourceCatalogReaderService` (구현), `SourceTableVersionRepository.atTables`
- data : `sourcemapping/compare/` (`PostgresCompareRules`, `SourceModelComparator`, `CompareModel`), `SourceCompareService`, `SourceCompareRestController`, `SourceCompareDto`, `SourceCompareModelRepository`, `SourceMappingService`(같은 패키지 `basis()`·`State` 공개 범위), `TableDdlGenerator`(`isNotNull`·`defaultLiteral` 공개)
- common : `ErrorCode.SOURCE_COMPARE_STALE` + `errors_{ko,en}`, `EnglishMessages`
- core : `MenuAuthorization` (`source-compare` VIEWER)
- web : `SourceComparePage.tsx`, 라우트·메뉴·`MenuId`, `source.css`, `en.ts`
- 테스트 : `SourceCompareIntegrationTest`, `SourceComparePerformanceTest`, `PostgresCompareRulesTest`
- DB·마이그레이션 변경 없음 (V0·V1·V2 그대로).

## 11. 한계와 후속

- **M-4e 반영 (2026-11-14)** : 원본 COMMENT 칸이 생겨 COMMENT 차이가 비교 결과(다름)에 들어간다. 가져오기는 이 비교 결과를 그대로 대상으로 쓴다 ([source-import-m4e.md](source-import-m4e.md)).


- **비교 정확성의 한계**
  - 식(DEFAULT·CHECK·인덱스 식·부분 조건)은 표기 정규화만 한다 : `IN (…)` ↔ `= ANY (ARRAY[…])`, `now()` ↔ `CURRENT_TIMESTAMP` 같은 의미 동등은 UNKNOWN 이다.
  - FK 삭제·갱신 규칙, 원본 COMMENT, IDENTITY 옵션(시퀀스 시작값 등)은 모델에 칸이 없다 (원본 COMMENT 칸은 M-4e 의 V3 이후 마이그레이션, 정책 5.5).
  - 원본의 고유 인덱스(제약조건 없음)와 모델의 UNIQUE 제약조건은 서로 다른 객체로 보인다 (DDL 이 다르다).
  - 별칭 규칙은 PostgreSQL 만 있다. 다른 DBMS 연결은 매핑된 TABLE 을 UNKNOWN(UNSUPPORTED_DBMS) 으로 둔다 (DBMS 확대 단계).
  - 모델 스키마 안에서 TABLE·VIEW 이름 충돌은 미매핑 원본 TABLE 에만 보인다.
- **성능** : 할당량 줄이기 — 매핑 상태 계산과 비교가 버전 JSON 을 한 번만 읽도록 합치기, 또는 (연결, R) 단위의 읽기 캐시. 지금 범위에서는 구조를 바꾸지 않았다.
- **M-4e 착수 전 결정** : 이름 변경 후보를 확정해 저장할지(영속 세부 매핑, V3+), 원본 COMMENT 칸 추가(V3+), 가져오기 트랜잭션·승인 정책.
