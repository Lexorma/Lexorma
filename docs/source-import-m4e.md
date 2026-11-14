# M-4e 원본 메타데이터 선택 가져오기

> 2026-11-14 구현. 흐름 : **비교 → 항목 고르기 → 서버 계획(미리보기, 저장 안 함) → 최종 확인 → 잠금 뒤 다시 계획해 같을 때만 반영 → 이력**.
> 원본 DB 에 접속하지 않는다 (저장된 마지막 반영 수집만 읽는다). 원본 DB 를 바꾸지 않는다. M-4f(재가져오기·자동 동기화)는 미착수.

## 1. 기존 구조 분석

| 항목 | 지금 구조 | M-4e 에서 |
|---|---|---|
| 모델 변경 서비스 | TABLE·컬럼·제약·인덱스·관계 저장 서비스가 화면 단위(DTO 전체 저장, 한글명 → 표준 이름 생성, 승인 권한자는 바로 승인)로 되어 있다 | 그대로 부르지 않는다 : 부분 속성 반영·미승인 생성·원본 물리명이 맞지 않는다. 같은 트랜잭션에서 엔티티를 직접 쓰되 같은 규칙(이름 공간 잠금·충돌 검사, 객체 이름 형식, 승인된 객체 수정 금지, DDL 생성기 기준)을 따른다 |
| 모델 변경 이력 | `ModelChangeRecorder` 가 Hibernate 이벤트로 기록. 트랜잭션 하나 = 변경 그룹 하나, 커밋 직전 같은 트랜잭션에 저장 (실패하면 함께 롤백) | 그대로 쓴다. 출처 `IMPORT` 추가(칸은 varchar 라 DDL 없음), `markImport(requestId)` 로 변경 그룹 ID = 요청 식별자 |
| 실행 이력 | 없음 (정책 5.10 에서 새 테이블로 정함) | `TB_SRC_IMPRT_EXCN` (V3) : 기준 연결·수집 실행·반영 순서·변경 그룹·요약. 성공만 남는다 |
| 승인 | TABLE·컬럼 : 승인된 것은 수정 불가(승인 취소 후 수정), 인덱스 : 승인된 것은 변경 요청. 새 객체는 등록자가 승인 권한자면 바로 승인 | 만든 객체는 모두 미승인(정책 5.4). 승인된 TABLE·컬럼의 속성은 바꾸지 않는다 (APPROVED 로 막고 안내). 승인 흐름을 새로 만들지 않는다 |
| 권한 | 모델 변경 USER, 매핑 변경 USER + 프로젝트 관리자·ADMIN | 확정 = USER + 프로젝트 관리자·ADMIN (더 엄격한 쪽). 대상·계획·이력 조회 = VIEWER |
| M-4d 재사용 | 비교 규칙·DTO | 대상 = `SourceModelComparator` 결과 그대로 (별도 비교 알고리즘 없음). 새 TABLE 은 빈 모델과 비교 |
| 마이그레이션 | V0·V1·V2 적용 | **V3 (`V3__source_import.sql`, PostgreSQL·H2 같은 내용)** : TB_TBL·TB_COL 의 `SRC_ANTN_CN`(원본 COMMENT), `TB_SRC_IMPRT_EXCN`, 예제(단어 가져오기 IMPRT, 용어 3, 물리·논리·매핑·인덱스 2) |

## 2. 가져오기 정책

### 2.1 항목 (선택 단위)
| 항목 ID | 동작 | 조건 |
|---|---|---|
| `TABLE:CREATE` | 미매핑 원본 TABLE 로 새 모델 TABLE + **같은 트랜잭션에서 TABLE 매핑 확정** | 원본 스키마 매핑이 유효, 같은 이름 모델 TABLE 후보 없음(있으면 매핑 관리에서 확정), 스키마 이름 공간에 같은 이름의 TABLE·VIEW 없음, 이름이 원본과 같은 비교 이름이 됨 |
| `TABLE:COMMENT` | 원본 COMMENT → `TB_TBL.SRC_ANTN_CN` | 승인된 TABLE 은 불가. 원본 COMMENT 가 없으면 기존 값을 지우지 않는다 |
| `COLUMN:<c>:ADD` | 새 컬럼 (원본 순서로 뒤에 붙임, 미승인) | 타입을 모델로 표현할 수 있음. 이름 변경 후보·충돌은 불가 |
| `COLUMN:<c>:TYPE` / `NULLABLE` / `DEFAULT` / `COMMENT` | 고른 속성만 수정 (TYPE = 타입·길이·정밀도·소수) | 승인된 컬럼 불가, 판단 불가 불가, NULL 허용으로 바꾸려는 PK 컬럼 불가 |
| `PRIMARY_KEY:<n>` | 모델에 PK 가 없을 때만 컬럼 PK 표시 | PK 컬럼 순서가 적용 후 모델 컬럼 순서와 같아야 함, 승인된 컬럼 불가. PK 교체는 불가(삭제 동반) |
| `UNIQUE:` · `CHECK:` · `INDEX:` · `FOREIGN_KEY:<n>` | 추가만 (미승인). FK 출처 `IMPORTED` | 구성 컬럼이 모델에 있거나 함께 고름. 바뀐(MODIFIED)·판단 불가·이름 변경 후보는 불가(교체·삭제 없음). FK 참조 대상은 매핑된 모델 TABLE 또는 같은 요청에서 만드는 TABLE, 참조 컬럼이 그 TABLE 의 PK·UNIQUE |

- 막는 이유는 항목마다 코드로 보인다 (`SourceImportDto.Reason` : APPROVED, UNKNOWN, RENAME_CANDIDATE, REPLACE_REQUIRED, MISSING_DEPENDENCY, TARGET_NOT_KEY, PK_ORDER, ORDER_NOT_SUPPORTED, NO_FIELD …).
- 고를 수 없는 것 : 같음(목록에 없음), 판단 불가, 이름 변경 후보, 충돌, 모델에만 있는 객체(지우지 않으므로 목록에 없음), 컬럼 순서(ORDER_NOT_SUPPORTED), 자동 증가 차이(NO_FIELD).
- **의존 항목은 자동으로 더하지 않는다** : 계획에서 MISSING_DEPENDENCY 와 필요한 항목 ID 를 보인다.

### 2.2 모델이 표현할 수 없는 원본 속성 (조용히 버리지 않는다)
| 원본 속성 | 처리 |
|---|---|
| IDENTITY (`GENERATED … AS IDENTITY`) · 생성 컬럼 | ~~컬럼은 가져오되 그 절은 빼고 보인다~~ → **컬럼 추가 불가** (10.3) |
| FK `ON DELETE/UPDATE` 가 NO ACTION 이 아닌 것 | ~~FK 는 가져오되 규칙은 뺀다~~ → **FK 추가 불가** (10.3) |
| 모델로 되돌릴 수 없는 타입 | 불가 (TYPE_NOT_REPRESENTABLE). 변환 = `mapType(POSTGRESQL)` 로 되돌렸을 때 원본과 같은 타입이 되는 표현 (VARCHAR(n)·NUMERIC(p,s)·REAL → FLOAT4·배열·사용자 정의 타입은 원문) |
| 모델 기본값으로 같은 식을 만들 수 없는 DEFAULT | 불가 (DEFAULT_NOT_REPRESENTABLE) 또는 컬럼 추가 때 `excluded` |
| 따옴표 식별자(대소문자 섞인 이름 등) | 불가 (QUOTED_NAME) : 모델 DDL 은 따옴표 없이 쓴다 |

모델에 칸을 더해 저장할지(IDENTITY·FK 규칙)는 사용자 결정 사항이다 (7장).

### 2.3 원본 COMMENT
- `TB_TBL.SRC_ANTN_CN`·`TB_COL.SRC_ANTN_CN` (V3, nullable). 업무 설명(`TBL_EXPLN`·`COL_EXPLN`)·논리명은 바꾸지 않는다. 새 TABLE 의 논리명·설명도 비워 둔다.
- 비교(M-4d)는 이제 원본 COMMENT 를 모델의 원본 COMMENT 칸과 비교한다 (TABLE 객체 `TABLE` 추가). 업무 설명과는 비교하지 않는다.
- 원본 COMMENT 가 없으면 기존 값을 지우지 않는다 (EMPTY_COMMENT). 지우는 동작은 만들지 않았다.
- 변경 이력에 `sourceComment` 로 남는다 (업무 설명 `columnDesc`·`tableDescription` 과 다른 필드). 모델 버전 형식 5 (상태에 sourceComment).

### 2.4 이름 변경 후보
- 미리보기에 이유로만 보이고(RENAME_CANDIDATE) 고를 수 없다. 기존 객체의 이름 변경·영속 세부 매핑은 없다.

## 3. 계획·재검증·동시성

- **계획** (`POST /api/sourceImport/plan`, VIEWER) : 고른 TABLE 만 원본 상세(반영 순서 고정)와 모델을 읽어 M-4d 비교 → 항목 → 의존 검사. 읽기 전용 REPEATABLE READ 트랜잭션, 저장 없음. `planToken` = 기준(연결·반영 순서)과 TABLE·항목마다 대상·변경 전후·적용 여부·이유·빼는 속성·의존의 SHA-256. `requestId` 를 새로 정해 준다.
- **확정** (`POST /api/sourceImport/apply`) : ① 프로젝트 관리자·ADMIN 확인 ② 잠금 : 프로젝트(공유) → 원본 연결(공유, `lockConnection`) → 기준 연결 행 → 새 TABLE 스키마의 이름 공간(스키마 행, 기존 순서) → 대상·FK 참조 모델 TABLE 행(ID 순) → 그 컬럼 행 ③ 같은 `requestId` 의 실행 이력이 있으면 거절 ④ 최신 상태로 계획을 다시 만들어 `planToken` 이 같고 모두 적용 가능할 때만 반영.
  - 다르면 **409 `SOURCE_COMPARE_STALE`** (기존 오류 코드 재사용 : 수집·기준 연결·매핑·모델 값·승인 상태가 바뀜, 같은 요청 두 번). 적용할 수 없는 항목·미리보기 없는 확정은 **400 `SOURCE_IMPORT_INVALID`** (새 오류 코드).
  - 잠금 순서는 연결 삭제(프로젝트 → 연결 → 매핑 정리)·매핑 변경·TABLE 등록(이름 공간 → TABLE 행)과 같은 방향이라 순환이 없다.
- **원자성** : 새 TABLE·매핑 확정·컬럼·속성·PK·UNIQUE/CHECK·인덱스·FK·모델 변경 이력·실행 이력이 한 트랜잭션. 하나라도 실패하면 모두 롤백.
- **중복 요청** : `CHG_GROUP_ID` 유니크(요청 식별자). 동시에 보내도 기준 연결 행 잠금으로 차례가 정해지고 두 번째는 409.
- **한도** : 한 요청에 TABLE 50개 (9장 측정 근거). 세션에 계획을 저장하지 않는다.

## 4. API (`/api/sourceImport`, 요청·응답 본문은 로그에 남기지 않음)

| 메서드 | 경로 | 권한 | 내용 |
|---|---|---|---|
| GET | `/candidates?key=` | VIEWER | 원본 TABLE 하나의 고를 수 있는 항목·막는 이유·원본 값·현재 모델 값·적용 후 값·빼는 속성·의존 |
| POST | `/plan` | VIEWER (읽기 전용) | 계획 : 항목별 변경 전후·적용 여부, 건수, planToken·requestId |
| POST | `/apply` | USER + 프로젝트 관리자·ADMIN | 확정 (planToken·requestId 필요) |
| GET | `/executions`, `/executions/{id}` | VIEWER | 실행 이력, 그 변경 그룹의 모델 변경 이력(객체·속성별 변경 전후) |

`POST /plan` 은 읽기 전용이라 변경 API 역할 검사의 예외 목록에 넣었다 (`UiActionAuthorizationIntegrationTest`).

## 5. 화면

- 원본·모델 비교 > TABLE 상세 대화상자 아래 **가져오기** : 고를 수 있는 항목 표(선택 | 객체 | 원본 값 | 현재 모델 | 적용 후, 의존·빼는 속성 표시), 가져올 수 없는 항목(이유) 접기, [미리보기] → 적용 계획(항목별 적용/불가와 이유, 건수) → [가져오기 확정](프로젝트 관리자·ADMIN 만) → 확인 대화상자(새 TABLE·바뀌는 TABLE·추가 컬럼·속성 변경·제약조건·인덱스·원본 COMMENT 수) → 결과(성공 요약 / 실패 이유 + "물리 모델은 바뀌지 않았습니다", 비교 새로 고침).
- 비교 화면 아래 **가져오기 이력** (실행자·시각·수집 시점·요약, 변경 내용 펼치기). 모델 변경 이력 화면의 출처에 "원본 가져오기".
- 기본 선택 없음 (같음·판단 불가·충돌·이름 변경 후보는 고를 수 없다). 한국어·영어, 라이트·다크, Lucide 아이콘.

## 6. 검증

| 항목 | 결과 |
|---|---|
| H2 전체 (`./gradlew build --rerun-tasks`, React 포함) | 632 통과, 실패 0, 건너뜀 22 (성능 측정 2 포함) |
| PostgreSQL 18.6 새 임시 DB 전체 | 632 통과, 실패 0, 건너뜀 16 |
| `SourceImportIntegrationTest` (6) | 대상, 계획(읽기 전용·의존), 확정, 재검증·롤백, 동시성, 권한 — H2·PostgreSQL 통과 |
| M-4c(10)·M-4d(5)·`ModuleBoundaryTest` | 통과 |
| V2 → V3 증분 업그레이드 (임시 DB, 사용자 데이터 포함) | 기존 TABLE·컬럼 행이 그대로(행 지문 같음), TABLE +1·컬럼 +13·예제 인덱스 +2 |

- **확정 테스트가 다루는 것** : 타입만 고르면 NULL 허용 그대로, 정밀도 변경, 승인된 컬럼 DEFAULT 그대로, 새 컬럼(미승인·원본 COMMENT·업무 설명 비어 있음·배열 타입·IDENTITY 뺌), 기존 컬럼 원본 COMMENT(업무 설명 유지), TABLE 원본 COMMENT(업무 설명 유지), UNIQUE·CHECK·복합 정렬 인덱스·PK, 새 TABLE 2개(미승인·매핑 확정·복합 PK·새 TABLE 끼리 복합 FK·IMPORTED), 확정 뒤 비교에서 새 TABLE 이 매핑됨·같음, IMPORT 이력·실행 이력, 같은 요청 재전송 409.
- **재검증·롤백** : 모델 값 변경·같은 이름 TABLE 등록·새 수집·기준 연결 변경 → 409, 아무것도 안 바뀜. 확정 뒤 같은 트랜잭션에서 실패 → 모델·매핑·이력·실행 이력 모두 롤백.
- **동시성 (DB 잠금 대기로 순서 고정, PostgreSQL 에서 실제 행 잠금)** : 같은 계획 동시 확정 → 하나만 반영·다른 하나 409, 확정 중 연결 삭제 → 삭제가 기다렸다가 새 매핑까지 정리(고아 없음, 모델은 남음), 연결 삭제 중 확정 → 거절·아무것도 안 만듦.
- **권한** : VIEWER 대상·계획·이력 조회 가능·확정은 역할에서 거절, 관리자 아닌 USER 거절, 다른 프로젝트 TABLE 거절.
- **브라우저 (headless Chrome, bootJar `h2-local`, 실제 PostgreSQL 원본 `m4e_src` — 확인 후 삭제)** : 매핑된 TB_COL 에 CHECK·정렬 인덱스·새 컬럼(DEFAULT false) 가져오기, 승인된 컬럼·TABLE 의 COMMENT 는 막힘 표시, 미매핑 원본 TB_IMPORT_DEMO 를 새 TABLE 로(컬럼 5·PK·UNIQUE·매핑된 TB_SCHM 으로 FK·원본 COMMENT 2), 확인 대화상자 → 결과 → 비교에서 매핑됨·같음, 가져오기 이력. 한국어·영어, 라이트·다크, 1440·375px 가로 넘침 0, 콘솔 오류 없음 (확인 스크립트의 로그인 전 401 제외).

## 7. 성능 (`SourceComparePerformanceTest.importScales`, `LEXORMA_PERF=true`)

PostgreSQL 18.6, TABLE 마다 컬럼 10개, 모두 매핑, 10% 는 컬럼 길이가 다름. 항목 = `COLUMN:c_3:TYPE`. 계획은 첫 호출을 버리고 3회 중 최소, 확정은 한 번씩만(반영하면 차이가 없어진다) 재서 흔들림이 있다.

| 프로젝트 TABLE | 호출 | 시간(ms) | SQL 문 | 할당(MB) |
|---|---|---|---|---|
| 300 | 대상(TABLE 1) / 계획 1·10·30 | 21 / 21·36·26 | 17~18 | 7~12 |
| 300 | 확정 19·10·1 TABLE | 133·109·64 | 97·79·60 | 16~28 |
| 3,000 | 대상(TABLE 1) / 계획 1·10·50 | 93 / 94·257·137 | 17~18 | 68~76 |
| 3,000 | 확정 50·10·1 TABLE | 468·616·329 | 158·78·60 | 143~173 |

- 계획은 고른 TABLE 만 상세로 읽는다 : TABLE 수가 늘어도 SQL 문이 거의 같다.
- 병목 : M-4c 상태 계산(기준 연결의 모든 테이블 버전 JSON)이 요청마다 한 번, 확정은 잠금 전후 두 번 돈다 (3,000 TABLE 에서 약 70MB 씩). 캐시는 넣지 않았다 (지시대로). 개선은 M-4d 할당량 개선과 함께 검토한다.
- 한도 50 TABLE 은 3,000 TABLE 프로젝트에서 확정 0.5초 안팎·170MB 할당으로 정했다.

## 8. 바꾼 파일

- DB : `V3__source_import.sql` (PostgreSQL·H2)
- data : `SourceImportService`, `SourceImportRestController`, `SourceImportDto`, `SourceImportExecution`(+리포지토리), `SourceCompareModelRepository`(원본 COMMENT·승인 여부), `CompareModel`, `SourceModelComparator`(TABLE 원본 COMMENT, 컬럼 원본 COMMENT 비교), `SourceCompareService`, `SourceCompareDto`(ObjectKind.TABLE), `PostgresCompareRules.stripLiteralCasts`, `SourceMappingService.lockBase`(같은 패키지), `TableInfo`·`ColumnInfo`(sourceComment), `ModelChangeRecorder`(IMPORT·markImport·sourceComment), `ModelChangeHistory.Source.IMPORT`, `ModelVersionService`(형식 5), `ProjectDataCleanup`
- common : `ErrorCode.SOURCE_IMPORT_INVALID` + `errors_{ko,en}`, `EnglishMessages`
- web : `SourceImportPanel.tsx`, `SourceComparePage.tsx`, `history/model.ts`, `source.css`, `en.ts`
- 테스트 : `SourceImportIntegrationTest`, `SourceComparePerformanceTest.importScales`, 기준값 조정(마이그레이션 4, 예제 인덱스 102·컬럼 145, 모델 버전 형식 5, 프로젝트 삭제 대상 + `TB_SRC_IMPRT_EXCN`, 비교의 원본 COMMENT 기대값, 역할 검사 예외 `POST /api/sourceImport/plan`)

## 9. 남은 것·결정 필요

- **개발 DB 에 V3 가 이미 적용되었다** (2026-11-14 10:59, 사용자의 IntelliJ 실행 중인 앱이 DevTools 재시작으로 빌드 결과의 V3 를 읽어 적용). V3 는 이제 수정 금지다. 기존 데이터는 그대로이고 가져오기 데이터는 없다.
- **사용자 결정**
  1. IDENTITY·FK 삭제/갱신 규칙을 모델에 저장할지 (지금은 빼고 보인다, 칸을 더하려면 V4+).
  2. 승인된 TABLE·컬럼의 원본 COMMENT 갱신을 허용할지 (원본 기준 칸이지만 지금은 승인 규칙대로 막는다).
  3. 이름 변경 후보를 확정해 저장할지(영속 세부 매핑) — M-4f 재가져오기 전에 정한다.
  4. 원본 COMMENT 를 지우는 명시적 선택이 필요한지.
  5. ~~가져오기 전 모델 버전~~ → **10장에서 구현 (확정마다 프로젝트 전체 버전, 같은 트랜잭션)**.
  6. 새 TABLE 의 매핑 확정은 실행 이력 요약(create)에 남고 모델 변경 이력에는 남지 않는다 (원본 매핑은 모델 변경 이력 대상이 아니다, M-4c 와 같다).
- **한계** : 컬럼 순서는 가져오지 않는다. 기존 제약조건·PK·인덱스의 교체는 없다(삭제 동반). 새 TABLE 은 논리명이 비어 있어 표준화 품질 진단에 나온다. PostgreSQL 원본만.
- **M-4f** : 미착수. 재가져오기는 이 계획·재검증 구조(항목·planToken)를 그대로 쓸 수 있다.

## 10. 후속 안정화 (2026-11-14) — 모델 버전 보존·미지원 속성 제한·개발 환경

### 10.1 기존 구조와 차이
| 구분 | 무엇을 남기나 | 가져오기 직전 상태를 복원할 수 있나 |
|---|---|---|
| 모델 변경 이력 (`TB_MDL_CHG_HSTRY`, 변경 그룹) | 바뀐 객체·필드의 전후 값 | 바뀐 것만 알 수 있다. 프로젝트 전체 상태는 없다 |
| 가져오기 실행 이력 (`TB_SRC_IMPRT_EXCN`) | 기준 연결·수집 실행·반영 순서·항목 요약 | 모델 상태는 없다 |
| 모델 버전 (`TB_MDL_VER`, M-9 3차) | **프로젝트 전체** 모델의 JSON 스냅샷 (변경 이력과 같은 필드 목록 : 스키마·TABLE·컬럼·관계·제약조건·인덱스·VIEW·논리 모델·매핑, 승인 여부·원본 COMMENT 포함) | 상태는 보존된다. **복원 기능은 없다** (버전은 만들기·조회·비교만, 수정·삭제·복원 없음) |

→ 실행 이력·변경 그룹만으로는 직전 상태가 완전히 보존되지 않아, 기존 모델 버전을 가져오기마다 만든다. 새 버전 시스템·스키마 변경은 없다.

### 10.2 가져오기 전 모델 버전
- **시점** : 확정에서 잠금·재계획·planToken 검증·적용 가능 확인을 마친 뒤, 모델을 바꾸기 직전. 미리보기·대상 조회에서는 만들지 않는다.
- **범위** : 기존 버전과 같이 프로젝트 전체. 이름 `IMPORT-<요청 식별자>`, 설명 "원본 가져오기 직전 상태 (기준 연결·반영 순서·요청)", 만든 사람 = 확정한 사용자.
- **트랜잭션** : `ModelVersionService.createBeforeImport` (Propagation.MANDATORY) 가 가져오기 트랜잭션에 참여한다. 버전·모델 변경·매핑·변경 이력·실행 이력이 한 커밋 — 하나라도 실패하면 모두 없다. 버전명이 이미 있으면(SAVED_DATA_EXISTS) 가져오기 전체가 롤백된다.
- **잠금 변경** : 확정이 프로젝트 행을 공유 → **배타** 잠금으로 맨 먼저 잡는다. ① 그동안 같은 프로젝트의 다른 쓰기가 커밋되지 않아(`ProjectWriteGuard` 커밋 직전 확인이 기다린다) 여러 번 읽는 스냅샷이 한 시점이다. ② 기존 버전 만들기(`create`)는 프로젝트 행을 배타로 잡는데, 공유를 잡은 뒤 배타로 올리면 동시 가져오기끼리 교착한다 — 처음부터 배타로 잡아 순서가 같다. 잠금 순서 : 프로젝트(배타) → 원본 연결(공유) → 기준 연결 행 → 스키마 이름 공간 → TABLE 행 → 컬럼 행.
- **연결** : 실행 이력 요약에 `modelVersionId`·`modelVersionName`, 확정 응답에 `modelVersionId`·`modelVersionName`, 버전 설명에 요청 식별자. 같은 요청은 다시 적용되지 않으므로(실행 이력 유니크) 버전도 하나다.
- **승인** : 버전은 읽기만 한다. 승인 상태를 바꾸지 않는다 (스냅샷에 승인 여부가 그대로 남는다).
- **복원** : 자동 복원·복원 화면은 만들지 않았다. 남긴 버전은 모델 버전 화면에서 조회하고 현재 모델과 비교(목록·ERD)할 수 있다 — "복원할 수 있다" 고 표시하지 않는다.

### 10.3 미지원 원본 속성 — 객체를 만들지 않는다
| 원본 속성 | 이전 | 지금 |
|---|---|---|
| IDENTITY (`GENERATED … AS IDENTITY`) | 컬럼을 만들고 IDENTITY 만 뺐다 | **컬럼 추가 불가** (UNSUPPORTED_ATTRIBUTE) |
| 생성 컬럼 (`GENERATED ALWAYS AS (…) STORED`) | 컬럼을 만들고 식만 뺐다 | **컬럼 추가 불가** |
| 모델 기본값으로 같은 식을 만들 수 없는 DEFAULT | 컬럼을 만들고 DEFAULT 만 뺐다 | **컬럼 추가 불가** (기존 컬럼의 DEFAULT 변경은 DEFAULT_NOT_REPRESENTABLE) |
| FK ON DELETE/ON UPDATE 가 NO ACTION 이 아님 (CASCADE·SET NULL·SET DEFAULT·RESTRICT) | FK 를 만들고 규칙만 뺐다 | **FK 추가 불가** |
| 지원하지 않는 인덱스 유형 | 불가 | 그대로 불가 (UNSUPPORTED_INDEX_TYPE). 수집기가 주는 다른 인덱스 속성(식·정렬·INCLUDE·부분 조건)은 모델이 표현한다 |

- **부분 가져오기** : 기존 컬럼의 독립 속성은 고를 수 있다 — 원본 COMMENT·NULL 허용·타입은 IDENTITY 와 무관하다. 예 : 원본 `seq_no` 가 IDENTITY 여도 원본 COMMENT 만 반영 (IDENTITY 를 가져온 것으로 표시하지 않고, 자동 증가 차이는 NO_FIELD 로 따로 보인다). 새 컬럼은 전체를 만들어야 하므로 막는다. 막힌 컬럼에 기대는 PK·UNIQUE 등은 선행 항목을 고를 수 없어 MISSING_DEPENDENCY.
- **안내** : 대상·미리보기에 막힌 항목·이유·원본 속성 값(`excluded`)·대안(모델 관리에서 직접 정하거나 모델이 속성을 지원한 뒤 가져오기)을 보인다.
- **서버 검증** : 고를 수 없는 항목 ID 를 직접 보내도 계획에서 적용 불가, 확정은 `SOURCE_IMPORT_INVALID`. 새 오류 코드 없음 (`Reason.UNSUPPORTED_ATTRIBUTE` 는 응답 항목의 이유 값).
- 모델 확장(IDENTITY·생성 컬럼·FK 규칙)은 하지 않았다 — 로드맵 후속 항목.

### 10.4 개발 환경 — Flyway 자동 적용 방지
- **원인 (확인)** : IntelliJ 실행 설정 `LexormaApplication` 은 프로필을 지정하지 않아 `local`(개발 DB) 로 뜨고, 클래스패스가 Gradle 결과물(`lexorma-app/build/classes`, `build/resources/main`)이다. `spring-boot-devtools`(`developmentOnly` — 실행 jar·Docker 에는 없다)가 이 폴더를 지켜보다가 Gradle 빌드·테스트가 새 마이그레이션 파일을 복사하면 재시작했고, 시작할 때 Flyway 가 그 마이그레이션을 개발 DB 에 적용했다.
- **조치** : `application-local.yml` 에 `spring.devtools.restart.enabled: false` (로컬 프로필만). 코드 변경 없음. 테스트(`test`)·`h2-local`·`dev`·`docker`·실행 jar 는 영향 없음 (Flyway 는 그대로 켜져 있다). IDE 의 HotSwap 은 그대로. 다시 켜려면 실행 설정에 `--spring.devtools.restart.enabled=true`.
- **검증 (격리)** : 개발 DB 의 사본(임시 DB, V3) + 빌드 결과의 사본 폴더로 DevTools 를 켜고 앱을 띄운 뒤 그 폴더에 시험 마이그레이션(V999)을 넣고 클래스를 바꿨다. 설정 전(재시작 켬) : 재시작되고 V999 가 적용됨(사고 재현). 설정 후 : 재시작 없음, V3 그대로. 개발 DB 는 0·1·2·3 그대로 (시험 파일은 실제 빌드 폴더에 넣지 않았다). 임시 DB·사본은 지웠다.
- **절차** : README "Database migrations" — 새 마이그레이션은 앱을 직접 다시 띄울 때만 적용된다. 지켜야 할 데이터가 있는 로컬 DB 는 먼저 사본(`pg_dump` → 임시 DB, `SPRING_DATASOURCE_URL`)에서 띄워 확인한다.
- **이미 떠 있는 앱** : 지금 떠 있는 IntelliJ 앱은 이 변경 뒤 한 번 재시작되며 새 설정을 읽었다 (이번 작업에는 새 마이그레이션이 없어 DB 변경 없음). 확실히 하려면 앱을 한 번 다시 띄운다.
- **선택 사항 (결정 필요)** : 재시작 없이 앱을 직접 다시 띄울 때도 새 마이그레이션을 막으려면, 로컬 프로필에서만 "미적용 마이그레이션이 있으면 명시적 허용 없이는 시작하지 않는" Flyway 전략(코드)을 둘 수 있다. 편의와 안전의 선택이라 이번에는 넣지 않았다.

### 10.5 검증
| 항목 | 결과 |
|---|---|
| H2 전체 (`./gradlew build --rerun-tasks`, React 포함) | 632 통과, 실패 0, 건너뜀 22 |
| PostgreSQL 18.6 새 임시 DB 전체 | 632 통과, 실패 0, 건너뜀 16 |
| 모델 버전 V-01·V-06·V-07 | 확정 하나에 버전 하나(TABLE 4개 가져오기), 이름 `IMPORT-<요청>`, 실행 이력 요약과 연결, 스냅샷에 새 TABLE 없음·바뀐 컬럼의 이전 값(길이 50)·승인 상태(TABLE 승인 그대로), 버전 ↔ 현재 비교에 ADDED LINE_ITEM·MODIFIED CUSTOMER.NAME |
| V-02 실패 시 롤백 | 확정 뒤 같은 트랜잭션 실패 → 모델·매핑·이력·실행 이력·버전 모두 없음 |
| V-03 같은 요청 재전송 | 409, 버전 1개 그대로 |
| V-04 모델 변경 경합 | 기존 409 (버전 없음) |
| V-05 승인된 객체 | 적용 불가 → 확정 400, 버전 없음 |
| V-08 버전 실패 | 같은 이름의 버전이 이미 있으면 확정이 SAVED_DATA_EXISTS, 모델·이력·실행 이력 변화 없음 |
| 동시성 (PostgreSQL) | 서로 다른 두 가져오기를 동시에 : 프로젝트 배타 잠금으로 차례대로 둘 다 반영, 버전 2개, 교착 없음. 같은 계획 동시 확정·연결 삭제 경합은 기존대로 |
| 미지원 속성 | IDENTITY·생성 컬럼 추가 불가, FK CASCADE·SET NULL·SET DEFAULT·ON UPDATE CASCADE 불가, NO ACTION FK 는 가능, 기존 IDENTITY 컬럼의 원본 COMMENT 만 반영, 막힌 항목 직접 전송 → 계획 적용 불가·확정 400 |
| M-4c(10)·M-4d(5)·M-4e(6)·모델 버전(6)·ModuleBoundaryTest | 통과 |

### 10.6 성능 변화 (PostgreSQL, `importScales`)
| 프로젝트 TABLE | 확정 50·10·1 TABLE | SQL 문 | 할당(MB) |
|---|---|---|---|
| 300 | 303·193·168 ms (19·10·1 TABLE) | 82~156 | 71~84 |
| 3,000 | 1,830·1,942·1,555 ms | 107~303 | 832~861 |

- 이전(버전 없음) 3,000 TABLE 확정 0.3~0.6초·143~173MB → 버전 스냅샷(프로젝트 전체 엔티티를 읽어 JSON 으로) 때문에 약 1.6~1.9초·830~860MB. 사용자가 버전을 직접 만들 때와 같은 비용이다. 그동안 프로젝트 배타 잠금이 잡혀 같은 프로젝트의 다른 쓰기는 커밋 직전에 기다린다.
- 계획·대상 조회는 그대로다 (버전은 확정에서만).
- 개선 후보 : 버전 스냅샷을 스칼라 조회로 만들기(엔티티를 올리지 않기). 모델 버전 기능 전체에 해당해 이번에는 바꾸지 않았다. → 11장에서 구현.

## 11. 모델 버전 스냅샷 성능 최적화 (2026-11-14)

### 11.1 무엇을 바꿨나
- `ModelChangeRecorder.currentState` (사용자 버전 만들기와 가져오기 직전 버전이 같이 쓰는 스냅샷) 를 **엔티티 조회 → 스칼라 조회** 로 바꿨다.
  - 종류마다 `select e.id, <기록 필드…> from X e where <프로젝트> order by e.id` 한 번. 연관은 `e.<필드>.id`(FK 값, 조인 없음)만 읽는다. 자식 행은 부모 ID 500개씩 같은 방식.
  - 값 변환 규칙(연관 → `<필드>Id`·`<필드>Name`, BigDecimal 뒤쪽 0 제거, enum 이름, 필드 순서)은 이력 기록과 같다. 종류별 조회 열·변환 정보는 한 번만 만든다 (`Projection`).
  - 영속성 컨텍스트에 아무것도 올리지 않는다 (`clear()`·detach 없음). 가져오기 트랜잭션이 들고 있는 엔티티에 영향이 없다.
- **바꾸지 않은 것** : JSON 구조·의미(`FORMAT_VERSION` 5 그대로), 프로젝트 전체 범위, 승인 상태·원본 COMMENT 포함, 버전 API, 트랜잭션 경계, 잠금 순서, SQL 문 수. 마이그레이션·캐시·영속 구조 없음.
- 이전 구현과의 동등성 : 임시 A/B 테스트로 예제 프로젝트 + 제약조건(UNIQUE·CHECK)·식 인덱스·정렬·INCLUDE·FK·VIEW(출력 컬럼·참조)·null FK·BigDecimal 을 더한 상태에서 **JSON 문자열이 같음** 을 H2·PostgreSQL 에서 확인한 뒤 이전 구현을 지웠다. 값 규칙은 `ModelSnapshotReadTest` 가 고정한다.
- 발견 : 이전 구현은 같은 트랜잭션에 **초기화되지 않은 프록시**(예 : `table.getSchema()`)가 있으면 그 객체의 값을 null 로 읽었다 (`persister.getValues` 가 프록시를 풀지 않음). 지금 화면 흐름에서는 생기지 않았지만 스칼라 조회에서는 원천적으로 없어진다.

### 11.2 측정 (PostgreSQL 18.6 격리 임시 DB, `SourceComparePerformanceTest.snapshotScales`, 예열 뒤 5회)
| TABLE | 구분 | 전체 시간 (버전 만들기 / 가져오기 확정) | 스냅샷 시간 | 할당량 (스냅샷 / 버전 / 확정) | SQL 수 (스냅샷 / 버전 / 확정) |
|---|---|---|---|---|---|
| 300 | 이전 | 77 / 172 ms | 61 ms | 28.8 / 47.9 / 78.1 MB | 20 / 23 / 100 |
| 300 | 이후 | 43 / 129 ms | 26 ms | 11.6 / 30.7 / 54.7 MB | 20 / 23 / 100 |
| 1,000 | 이전 | 252 / 443 ms | 179 ms | 102.3 / 167.9 / 264.5 MB | 25 / 28 / 105 |
| 1,000 | 이후 | 114 / 283 ms | 44 ms | 38.4 / 102.1 / 177.6 MB | 25 / 28 / 105 |
| 3,000 | 이전 | 1,092 / 1,606 ms | 834 ms | 406.7 / 604.4 / 899.1 MB | 45 / 48 / 125 |
| 3,000 | 이후 | 363 / 851 ms | 142 ms | 114.8 / 306.7 / 537.3 MB | 45 / 48 / 125 |

- 3,000 TABLE : 스냅샷 할당 **−72%** (시간 −83%), 버전 만들기 할당 −49%, 가져오기 확정 할당 **−40%**·시간 −47%. GC 시간 스냅샷 28 → 12 ms, 버전 79 → 40 ms, 확정 61 → 52 ms.
- 유지 힙(스냅샷 결과 자체)은 64.5 → 66.0 MB 로 같다 : 결과 맵 크기는 JSON 구조가 정한다. JSON 직렬화(40 ms·121 MB)는 바꾸지 않았다.
- 프로젝트 배타 잠금 보유 시간 ≈ 확정 전체 시간 : 3,000 TABLE 1.6초 → 0.85초. 같은 프로젝트의 다른 쓰기가 기다리는 시간도 그만큼 줄었다.
- 확정에 남은 비용(약 230 MB)은 M-4c 상태 계산을 두 번(잠금 전 계획·잠금 후 재계획) 하는 몫이다. 재검증 계약이라 이번에는 그대로 둔다.
- 측정 코드는 테스트에만 있다 (`LEXORMA_PERF=true` 일 때만 실행).

### 11.3 동시성 점검에서 고친 것 — 확정 ↔ 일반 모델 저장 교착 (T-07)
- 재현 (PostgreSQL) : 다른 사용자가 같은 TABLE 의 컬럼을 고치고 새 컬럼을 등록하는 트랜잭션(커밋 전)이 있을 때 확정을 누르면 `deadlock detected`. 일반 저장은 컬럼 행을 잡은 채 커밋 직전에 프로젝트 행을 공유 잠금으로 확인하고(`ProjectWriteGuard`), 확정은 프로젝트 행을 배타로 잡은 뒤 같은 컬럼 행을 기다리기 때문이다. 결국 PostgreSQL 이 한쪽을 죽여 500 이 됐다.
- 수정 : 프로젝트 배타 잠금 **이후** 의 행 잠금(스키마 이름 공간·TABLE·컬럼)을 `NOWAIT`(잠금 대기 0) 로 잡는다. 잡지 못하면 기다리지 않고 409 `SOURCE_COMPARE_STALE` ("다른 사용자가 같은 모델을 바꾸는 중입니다…"), 상대 저장은 그대로 커밋된다. 잠금 종류·대상·순서는 같다 (스키마 이름 공간은 `ObjectNamespace` 와 같은 행·순서 : 기본 스키마 → ID 순). 프로젝트·연결·기준 행 잠금은 지금처럼 기다린다.
- 같은 계획 동시 확정·서로 다른 두 가져오기·연결 삭제 경합은 기존 결과 그대로다.

### 11.4 검증
| 항목 | 결과 |
|---|---|
| H2 전체 (`./gradlew build --rerun-tasks`, React 포함) | 635 통과, 실패 0, 건너뜀 23 |
| PostgreSQL 18.6 새 임시 DB 전체 | 635 통과, 실패 0, 건너뜀 17 |
| 스냅샷 A/B (임시) | H2·PostgreSQL 다른 객체 0 |
| T-06 다른 프로젝트 | 한 프로젝트 확정이 커밋 전이어도 다른 프로젝트 확정은 기다리지 않고 끝난다 (전역 직렬화 없음) |
| T-07 모델 수정 경합 | 교착 없음, 확정 409, 상대 저장 커밋, 버전 없음 |
| T-08 승인 변경 경합 | 대상 TABLE 승인이 커밋 전이면 확정 409, 승인 반영, 이후 계획은 APPROVED 로 막힘 |
| T-09 버전 만들기 경합 | 둘 다 프로젝트 배타 잠금으로 차례대로 끝나고 교착 없음. 사용자 버전은 가져오기 전 또는 뒤의 온전한 상태 (아래) |
| T-01~T-05·T-10 (같은 계획 동시 확정, 연결 삭제 양방향, 실패 롤백, 재전송, 버전 이름 충돌) | 기존 테스트 통과 |
| 화면 기본 확인 (빌드 JAR, h2-local) | 버전 만들기·목록·스냅샷·현재와 비교(변화 0, 같은 2,424개), 가져오기 이력 조회, `/model-version`·`/source/import` 200 |
| 개발 DB | 변경 없음 (V0~V3, V3 checksum 1188533421) |

### 11.5 남은 것
- **T-09 시점 (기존 M-9 동작, 이번에 바꾸지 않음)** : PostgreSQL 의 REPEATABLE READ 는 첫 문장(프로젝트 행 잠금 대기)을 시작할 때 시점을 잡는다. 그래서 가져오기가 끝나기를 기다린 사용자 버전은 **가져오기 전 상태**로 저장된다 (H2 는 뒤 상태). 섞인 상태는 아니지만 "만든 시각의 현재" 와 다를 수 있다. 고치려면 버전 만들기의 트랜잭션·격리를 바꿔야 해서(잠금을 먼저 잡고 시점은 그 뒤에) 사용자 결정 뒤에 한다.
- 확정의 M-4c 상태 이중 계산, JSON 직렬화 중간 객체 : 필요하면 별도로.
- T-09 는 12장에서 원인을 확인했다.

## 12. T-09 모델 버전 생성 시점 정합성 (2026-11-14)

### 12.1 원인 (PostgreSQL 18.6 격리 임시 DB에서 재현, SQL 로그·`pg_stat_activity` 로 확인)
- **격리 수준** : DB 기본 `read committed`, 기본 `@Transactional` 안도 `read committed`. 사용자 버전 만들기 `ModelVersionService.create` 는 **`@Transactional(isolation = REPEATABLE_READ)` 를 직접 지정**해 그 트랜잭션만 `repeatable read` 다 (연결을 돌려줄 때 원래대로 돌아간다). 가져오기 확정(`apply`)과 그 안의 `createBeforeImport` 는 `read committed`.
- **SQL 순서 (사용자 버전)** : ① `select … from tb_prj … for update` (트랜잭션의 첫 SQL) → ② 버전명 중복 확인 → ③ 스냅샷 스칼라 조회 15개 → ④ 이름 조회 → ⑤ `insert into tb_mdl_ver` → 커밋. 잠금 전에 도는 조회는 없고 JPA 1차 캐시도 쓰지 않는다 (스칼라 조회).
- **발생 원인** : PostgreSQL 의 REPEATABLE READ 는 트랜잭션의 첫 SQL 이 시작할 때 스냅샷을 정한다. 첫 SQL 이 프로젝트 잠금이라, 가져오기가 커밋되기를 기다리는 그 문장 시작 시점의 스냅샷이 트랜잭션 끝까지 쓰인다. 기다리는 동안 B 세션의 `backend_xmin` 은 가져오기 트랜잭션의 xid 와 같았고(그 xid 는 보이지 않음), ③의 조회는 모두 가져오기 커밋 뒤에 실행됐지만 가져오기 전 상태를 읽었다. 잠근 프로젝트 행 자체는 가져오기가 바꾸지 않으므로 직렬화 오류도 나지 않는다. 지시서의 "READ COMMITTED 의 문장별 스냅샷" 가설은 해당하지 않는다.
- **H2** : 같은 REPEATABLE READ 인데 잠금을 얻은 뒤에 읽는 시점을 잡아 가져오기 뒤 상태가 된다. PostgreSQL 이 기준이다.
- **재현** : `ModelVersionConcurrencyIntegrationTest` T09-03 — PostgreSQL 5회 모두 가져오기 전, H2 5회 모두 가져오기 뒤. 저장된 버전은 어느 쪽이든 한 시점의 온전한 상태다 (새 컬럼과 TABLE 설명이 같은 쪽). 시간 순서 예 (ms) : A 잠금 2 → A 변경 10 → B 호출·잠금 대기 10 → A 커밋 42 → B 스냅샷 조회 47~91 → B 커밋 105, 결과 가져오기 전 상태.

### 12.2 정책 대조
| 정책 | 지금 | 판정 |
|---|---|---|
| 6.1 사용자 버전 = 잠금을 얻은 뒤의 최신 커밋 상태 | PostgreSQL 에서는 **잠금을 요청한 시점** 의 상태 (기다리는 동안 커밋된 가져오기가 빠진다). 만든 시각은 가져오기 뒤라 "가져오기 뒤 만든 버전과 현재 비교" 에 가져오기 변경이 차이로 나온다 | **어긋남** |
| 6.2 가져오기 직전 버전 = 모델 변경 직전 상태 | 그대로 (V-01 등) | 맞음 |
| 6.3 섞인 스냅샷 없음 | 어느 경로든 한 시점. `read committed` 인 `createBeforeImport` 도 안전하다 : 모든 모델 변경은 같은 트랜잭션에서 변경 이력(`prj_sn` 있음)을 저장하고, `ProjectWriteGuard` 가 그 삽입을 보고 커밋 직전에 프로젝트 행을 공유 잠금으로 잡는다. 그래서 프로젝트를 배타로 잡은 동안에는 수정만 하는 저장도 커밋하지 못한다 (테스트 "일관성 근거", H2·PostgreSQL) | 맞음 |

### 12.3 수정 여부 — 사용자 결정 필요 (코드 그대로) → 12.6 에서 대안 1 로 결정·적용
- SQL 순서로는 고칠 수 없다 : 잠금이 이미 첫 SQL 이고, REPEATABLE READ 에서는 그 문장 시작이 스냅샷이다. 고치려면 격리 수준 또는 트랜잭션 경계를 바꿔야 해서 지시서 7.3 에 따라 멈췄다.
- **대안 1 (권장) : `create` 를 기본 격리(read committed)로** — `createBeforeImport` 와 같은 방식. 프로젝트 배타 잠금을 먼저 잡고, 그 뒤 모델 변경 커밋은 12.2 의 이유로 막히므로 여러 조회가 한 시점을 본다. 확인 : 같은 `create` 를 read committed 바깥 트랜잭션에서 부르면 PostgreSQL 에서도 가져오기 뒤 상태가 저장됐다. 한 줄 변경, 잠금·JSON·API 그대로. 조건 : 모델 변경이 반드시 변경 이력을 남겨야 한다 (지금은 모든 추적 엔티티가 그렇다. JPQL 일괄 UPDATE·DELETE 는 없다 — 이 조건을 문서·테스트로 고정한다).
- 대안 2 : REPEATABLE READ 유지 + 프로젝트 잠금을 NOWAIT 로 → 가져오기·다른 버전 만들기 중이면 409 로 다시 시도. 오래된 버전은 없지만, 일반 등록이 커밋 직전에 잡는 짧은 공유 잠금에도 409 가 날 수 있다.
- 대안 3 : 지금 그대로 두고 "버전은 요청을 시작한 시점의 모델" 로 정책을 바꿔 문서화. 일관성은 있지만 6.1 과 다르고, 만든 시각과 내용 시점이 다를 수 있다.

### 12.4 NOWAIT (T-07) 유지와 오류 처리
- NOWAIT 정책·잠금 순서·409·전체 롤백은 그대로다.
- 받은 예외 (실측) : PostgreSQL NOWAIT 실패 `PessimisticLockException ← LockTimeoutException ← SQLSTATE 55P03`, 실제 교착 `PessimisticLockException ← LockAcquisitionException ← 40P01`. H2 NOWAIT 실패 `LockTimeoutException ← 오류 50200`, 교착 `PessimisticLockException ← 40001`.
- 고친 것 : 이전에는 예외 종류만 보고 409 로 바꿔 **교착도 409 로 숨길 수 있었다**. 이제 원인 SQLException 이 55P03 / 50200 일 때만 `SOURCE_COMPARE_STALE` 로 바꾸고 나머지는 그대로 던진다 (`SourceImportService.lockNotAvailable`, `SourceImportLockErrorTest`). NOWAIT 문장은 기다리지 않으므로 그 구간에서 교착 희생자가 될 일은 원래 없다.
- 잠금 실패 뒤 : 확정 트랜잭션은 전체 롤백(버전 없음), 상대 저장은 커밋 (T-07 테스트).

### 12.5 검증
| 항목 | 결과 |
|---|---|
| PostgreSQL T-09 재현 | 5/5 가져오기 전 상태, 원인 확인 (12.1) |
| PostgreSQL T-09 정책 확인 | 6.1 어긋남 → 사용자 결정 (12.3), 6.2·6.3 맞음 |
| H2 T-09 | 5/5 가져오기 뒤 상태 |
| T09-01·02·05·06 | 통과 (현재 모델과 차이 0, 가져오기 뒤 버전에 변경 있음, 같은 프로젝트 동시 생성 같은 내용·같은 이름은 하나만, 다른 프로젝트는 기다리지 않음) |
| T09-04·07·08·09·10 | 기존 테스트 통과 (가져오기 직전 버전 V-01, T-07 NOWAIT, T-08 승인, V-02 롤백, 버전 조회·비교) |
| H2 전체 (`./gradlew build --rerun-tasks`, React 포함) | 640 통과, 실패 0, 건너뜀 23 |
| PostgreSQL 18.6 새 임시 DB 전체 | 640 통과, 실패 0, 건너뜀 17 |
| 개발 DB | 변경 없음 (V0~V3) |

### 12.6 최종 결정과 적용 (2026-11-14) — T-09 해결
- **결정** : 대안 1. `ModelVersionService.create` 를 `@Transactional(isolation = READ_COMMITTED)` 로 (명시). 프로젝트 배타 잠금·잠금 순서·전체 스냅샷·JSON 형식 5·API·승인 상태·원본 COMMENT·스칼라 조회는 그대로.
- **정책** : 사용자 버전 = 프로젝트 잠금을 얻은 뒤의 최신 커밋 상태. 가져오기 직전 버전 = 모델 변경 직전 상태(그대로). 스냅샷은 늘 프로젝트 전체의 한 시점.
- **일관성의 필수 조건** (READ COMMITTED 는 문장마다 스냅샷이 달라지므로 잠금이 시점을 고정한다)
  1. 추적하는 모델 엔티티의 삽입·수정·삭제는 모두 Hibernate 이벤트로 `ModelChangeRecorder` 에 잡히고, 같은 트랜잭션에서 커밋 직전에 변경 이력을 저장한다. 자식 행(관계·인덱스·제약조건의 컬럼, 식별자 속성, VIEW 출력·참조)의 변경은 부모의 이력이 된다.
  2. 변경 이력 행은 `PRJ_SN NOT NULL` 이라 `ProjectWriteGuard` 가 그 삽입을 보고 커밋 직전에 프로젝트 행을 공유 잠금으로 잡는다. 배타 잠금을 쥔 동안에는 커밋하지 못한다.
  3. 버전 만들기는 트랜잭션의 첫 SQL 로 프로젝트 배타 잠금을 잡고, 그 뒤에만 스냅샷을 읽는다.
  - **이 조건을 깨는 쓰기를 만들면 안 된다** : 모델 테이블에 대한 JPQL·네이티브 일괄 UPDATE/DELETE, JDBC 직접 쓰기, 변경 이력을 남기지 않는 저장 경로. 만들어야 한다면 `ProjectWriteGuard.guard(projectId)` 와 이력 처리를 직접 해야 한다.
- **우회 경로 점검 (2026-11-14)** : 운영 코드의 `@Modifying`·`executeUpdate`·`createNativeQuery`·JdbcTemplate 쓰기는 ① 원본 매핑 테이블 삭제 3곳(`TB_SRC_*_MPNG`·기준 연결 — 스냅샷 대상 아님) ② 프로젝트 삭제 정리(`ProjectDataCleanup`·공지 정리 — 프로젝트 행을 배타로 잡은 뒤 실행되므로 같은 잠금으로 직렬화) 뿐이다. 모델 엔티티의 일괄 쓰기는 없다. 일관성을 깨는 실제 우회 경로는 찾지 못했다.
- **NOWAIT (T-07)** : 그대로. PostgreSQL 55P03·H2 50200 만 409, 40P01 등은 그대로 던진다.

| 검증 | 결과 |
|---|---|
| PostgreSQL T09-03 (가져오기 커밋 전 → 버전 만들기 대기 → 가져오기 커밋 → 잠금 획득) | 10/10 가져오기 뒤 상태 (5회 × 2번 실행), 새 컬럼·TABLE 설명이 같은 시점 |
| H2 T09-03 | 5/5 가져오기 뒤 상태 |
| M-4e T-09 (실제 확정과 경합) | PostgreSQL·H2 모두 사용자 버전 = 가져오기 직전 버전 + EMAIL |
| T09-01·02·05·06, 일관성 근거, T-07·T-08, 가져오기 직전 버전·롤백, 버전 조회·비교 | 통과 |
| H2 전체 (`./gradlew build --rerun-tasks`, React 포함) | 640 통과, 실패 0, 건너뜀 23 |
| PostgreSQL 18.6 전체 (격리 임시 컨테이너) | 640 통과, 실패 0, 건너뜀 17 |

