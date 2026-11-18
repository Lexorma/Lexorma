# M-7. 논리·물리 모델 매핑 — 설계 결정과 구현 기록 (1차 : 도메인·API)

> 2026-11-12 : 이 문서의 대상(엔터티 ↔ 테이블·속성 ↔ 컬럼 매핑)을 바꾸면 변경 이력으로 남는다 (M-9 2차, [model-change-history-m9.md](model-change-history-m9.md) 11장). 저장 구조는 바뀌지 않았다.

> [데이터 모델링 로드맵](../reference/data-modeling-roadmap.md) M-7 중 **1차(매핑 저장 구조·관리 API·현황·후보 조회)** 의 기록이다. 작성일 2026-11-11.
> 매핑 관리 화면과 정합성 비교는 **2차**에서 구현했다 — [model-mapping-quality-m7.md](model-mapping-quality-m7.md). 물리명 추천은 구현하지 않았다. 아래 본문은 1차 시점의 기록이다.

## 1. 기존 구조 분석과 결정

| 확인 항목 | 결과 → 결정 |
|---|---|
| 논리 모델 (M-6) | 프로젝트 → 논리 모델 N → 엔터티 → 속성·식별자, 관계. 엔터티·관계에 승인 플래그. 변경은 **논리 모델 행 잠금**으로 직렬화. 속성은 표준 용어만 참조하고 물리 객체를 참조하지 않는다 → **매핑은 별도 패키지(`data/mapping`)·별도 테이블.** 논리·물리 엔티티에는 컬럼을 추가하지 않았다 |
| 물리 모델 | `SchemaInfo` → `TableInfo` → `ColumnInfo`, 관계·인덱스·제약. 테이블은 컬럼이 없고 미승인일 때만, 컬럼은 미승인이고 관계·인덱스·제약에 쓰이지 않을 때만 삭제(`releaseColumns`). 테이블·컬럼 삭제에는 잠금이 없었다 |
| 표준화 | 속성 → 표준 용어, 컬럼 → 표준 용어는 각각 따로 연결된다 → **매핑은 표준 용어 연결을 대체하지 않는다.** 같은 용어·같은 이름이어도 자동으로 매핑하지 않고, 달라도 매핑을 막거나 고치지 않는다 (조회에 양쪽 용어를 함께 준다) |
| 프로젝트 격리 | 모든 조회·변경이 `AccountDto.getProjectId()` 기준 → 동일. 요청의 프로젝트 ID 는 받지 않는다 |
| 권한·승인 | 논리·물리 API 가 조회 VIEWER / 변경 USER → 동일. 로드맵에 매핑 승인 요구가 없다 → **매핑에는 승인 절차가 없다.** 매핑은 엔터티·테이블 자체를 바꾸지 않으므로 승인된(또는 미승인) 엔터티·테이블도 USER 가 매핑할 수 있고, 승인 상태는 조회 응답에 표시만 한다 |
| 로드맵 M-7 | "1:1 우선, 1:N 은 검토" → **1:1.** 로드맵의 M-7 범위에는 물리명 추천·도메인 → 타입 매핑·불일치 확인도 있으나 지시서가 1차에서 제외했다 → **2차로 분리** (로드맵 갱신) |
| 마이그레이션 | CLAUDE.md 정책은 "V0 + 데이터 전용 V1, 추가 마이그레이션은 요청 시에만". 지시서가 기존 DB 보존과 신규 버전 마이그레이션을 요구 → **`V2__logical_physical_mapping.sql` 추가** (V0·V1 은 바꾸지 않았다). 5장 |

## 2. 저장 구조

```text
TB_LGCL_ENTT (LogicalEntity) ──1:1── TB_ENTT_TBL_MPNG (EntityTableMapping) ──1:1── TB_TBL (TableInfo)
                                          │ 1:N
TB_LGCL_ATRB (LogicalAttribute) ─1:1─ TB_ATRB_COL_MPNG (AttributeColumnMapping) ─1:1─ TB_COL (ColumnInfo)
```

| 테이블 | 컬럼 | 제약 |
|---|---|---|
| `TB_ENTT_TBL_MPNG` (엔터티 테이블 매핑, `SQ_ENTT_TBL_MPNG`) | `ENTT_TBL_MPNG_SN`, `PRJ_SN`, `LGCL_ENTT_SN`, `TBL_SN`, 감사 컬럼 4개 | `UX_…_01 (LGCL_ENTT_SN)`, `UX_…_02 (TBL_SN)`, `IX_…_01 (PRJ_SN)`, FK → `TB_LGCL_ENTT`, `TB_TBL` |
| `TB_ATRB_COL_MPNG` (속성 컬럼 매핑, `SQ_ATRB_COL_MPNG`) | `ATRB_COL_MPNG_SN`, `ENTT_TBL_MPNG_SN`, `LGCL_ATRB_SN`, `COL_SN`, 감사 컬럼 4개 | `UX_…_01 (LGCL_ATRB_SN)`, `UX_…_02 (COL_SN)`, `IX_…_01 (ENTT_TBL_MPNG_SN)`, FK → `TB_ENTT_TBL_MPNG`, `TB_LGCL_ATRB`, `TB_COL` |

- 이름은 [DB 명명 규칙](../reference/db-naming-standard.md). 새 기관 표준단어 : 매핑 `MPNG`.
- FK 에는 CASCADE 가 없다 : 매핑이 남아 있으면 엔터티·테이블·속성·컬럼·부모 매핑 행은 DB 에서도 지워지지 않는다.
- 상태 컬럼·설명·이력 테이블은 만들지 않았다.
- **DB 가 보장하는 것** : 1:1 (UNIQUE 4개), 참조 무결성 (FK 5개). **서비스가 보장하는 것** : 프로젝트 격리, 속성 매핑의 속성·컬럼이 부모 매핑의 엔터티·테이블에 속하는지. (복합 FK 로 DB 에서 보장하려면 `TB_LGCL_ATRB`·`TB_COL` 에 UNIQUE 를 추가해야 해서 하지 않았다. 속성은 엔터티를, 컬럼은 테이블을 옮길 수 없으므로 등록·변경 시점 검사로 충분하다.)

## 3. 정책

| 항목 | 정책 |
|---|---|
| 매핑 단위 | 엔터티 1 : 테이블 1, 속성 1 : 컬럼 1. 매핑이 없는 엔터티·테이블·속성·컬럼도 그대로 존재한다 |
| 자동 매핑 | 없다. 명시적으로 등록한 것만 매핑이다. (1차 시점에는 예제도 매핑하지 않았고, 이후 `V4__mapping_example_data.sql` 이 예제의 논리·물리 모델을 매핑 데이터로 이었다 — 10장) |
| 원본 불변 | 매핑 등록·변경·삭제는 엔터티·테이블·속성·컬럼과 그 승인 상태·표준 용어를 바꾸지 않는다 |
| 부모·자식 | 속성 매핑은 부모(엔터티 ↔ 테이블 매핑)의 엔터티에 속한 속성과 테이블에 속한 컬럼만 연결한다. 그 밖의 조합은 `INVALID_MODEL_MAPPING` |
| 엔터티 매핑 변경 | 테이블만 바꾼다 (요청의 `entityId` 는 무시). **속성 매핑이 있으면 거절** (`INVALID_MODEL_MAPPING`) — 속성 매핑을 먼저 해제한다. 같은 테이블로 다시 저장하는 것은 허용 |
| 속성 매핑 변경 | 컬럼만 바꾼다 (요청의 `mappingId`·`attributeId` 는 무시) |
| 매핑 삭제 | 속성 매핑이 있는 엔터티 매핑은 삭제할 수 없다 (`RELATION_EXISTS`) |
| 엔터티·속성 삭제 | 매핑된 엔터티·속성은 삭제할 수 없다 (`RELATION_EXISTS`, 메시지에 매핑된 테이블·컬럼명). 엔터티의 속성 매핑은 엔터티 매핑이 있어야만 존재하므로 엔터티는 엔터티 매핑만 검사한다 |
| 테이블·컬럼 삭제 | 매핑된 테이블·컬럼은 삭제할 수 없다 (`RELATION_EXISTS`, 메시지에 매핑된 엔터티·속성명). 컬럼은 단건 삭제·일괄 저장의 삭제 모두 `releaseColumns` 에서 검사한다 |
| 논리 모델·스키마 삭제 | 기존 정책(엔터티가 있는 모델, 테이블이 있는 스키마는 삭제 불가)으로 충분하다 — 매핑이 남은 채 지워지는 경로가 없다 |
| 프로젝트 삭제 | 기존 정책대로 프로젝트의 모델 데이터를 지우지 않는다 (`ProjectDeletedEvent` 리스너 없음). 매핑도 양쪽 원본과 함께 남으므로 끊어진 참조는 생기지 않는다. 프로젝트 데이터 정리를 만들 때 매핑 → 원본 순서로 지워야 한다 |
| 권한 | 조회 VIEWER, 등록·변경·삭제 USER. 승인 절차 없음. `NOT_APPROVE` 는 조회도 불가 |

오류 코드 : `MODEL_MAPPING_IS_NOT_DEFINED`(매핑 없음·다른 프로젝트), `INVALID_MODEL_MAPPING`(부모와 맞지 않는 속성·컬럼, 속성 매핑이 있는 매핑의 테이블 변경), `SAVED_DATA_EXISTS`(이미 매핑된 엔터티·테이블·속성·컬럼), `RELATION_EXISTS`(삭제 차단), `LOGICAL_MODEL_IS_NOT_DEFINED`(엔터티·모델 없음), `TABLE_IS_NOT_DEFINED`(테이블 없음). 모두 400.

## 4. API (`/api/modelMapping`, `ModelMappingRestController`)

| 메서드·경로 | 권한 | 내용 |
|---|---|---|
| `GET /entity/data?page&size&sort&searchColumn&searchQuery&modelId&schemaId&mapped` | VIEWER | **엔터티 기준 목록** (그리드 형식 `{data, recordsTotal, recordsFiltered}`). 엔터티 1행 : 모델·엔터티(승인 상태)·매핑된 스키마·테이블(승인 상태)·속성 수·매핑된 속성 수. 매핑하지 않은 엔터티는 `mappingId`·테이블이 null. `mapped=true` 매핑 목록 / `mapped=false` **매핑 후보 엔터티**. 검색 `entityName`(기본)·`tableName` 부분 일치, 정렬 `modelName`·`entityName`·`schemaName`·`tableName` (없으면 모델명 → 엔터티명) |
| `GET /entity/{mappingId}` | VIEWER | 매핑 상세 : `mapping`(위 1행), `attributes`(엔터티의 **속성 전체** — 매핑된 컬럼과 양쪽 표준 용어, 매핑하지 않은 속성은 `mappingId`·컬럼이 null = 후보 속성), `unmappedColumns`(매핑된 테이블의 컬럼 중 아직 연결하지 않은 것 = **후보 컬럼**) |
| `POST /entity` `{id?, entityId, tableId}` | USER | 엔터티 ↔ 테이블 매핑 등록(`id` 없음)·변경 |
| `DELETE /entity/{mappingId}` | USER | 엔터티 ↔ 테이블 매핑 삭제 |
| `POST /attribute` `{id?, mappingId, attributeId, columnId}` | USER | 속성 ↔ 컬럼 매핑 등록·변경 |
| `DELETE /attribute/{attributeMappingId}` | USER | 속성 ↔ 컬럼 매핑 삭제 |
| `GET /table/data?page&size&sort&searchQuery&schemaId` | VIEWER | **매핑 후보 테이블** : 엔터티에 매핑하지 않은 프로젝트의 테이블 (그리드 형식). 검색은 테이블명·한글명 |
| `GET /status?modelId` | VIEWER | **현황** : 논리 모델별 `totalEntities`·`mappedEntities`·`unmappedEntities`·`totalAttributes`·`mappedAttributes`·`unmappedAttributes`. `modelId` 가 없으면 프로젝트의 모든 모델(모델명 순) |

- 집계 기준 : 엔터티·속성 수 = 그 논리 모델의 것 전부, 매핑 수 = 매핑 행이 있는 것, 미매핑 = 전체 − 매핑. 매핑하지 않은 엔터티의 속성도 전체 속성 수에 들어간다.
- 물리 기준 역방향 현황 API 는 만들지 않았다 : 매핑 안 된 테이블 수는 `GET /table/data` 의 `recordsTotal`, 매핑 안 된 컬럼은 상세의 `unmappedColumns` 로 알 수 있다. 화면에서 필요해지면 2차에서 추가한다.
- 후보 조회는 "아직 매핑하지 않은 것"의 목록일 뿐이다. 이름 유사도·추천은 없다.
- 목록·현황·후보는 QueryDSL 로 DB 에서 걸러 페이징·집계한다 (`ModelMappingQueryRepository`). 목록은 조회 2회(행 + 건수), 상세는 3회, 현황은 1회 (모델 1행에 상관 서브쿼리 4개).

## 5. DB 마이그레이션

- `V2__logical_physical_mapping.sql` (postgresql·h2 같은 내용) : 시퀀스 2개, 테이블 2개, 인덱스 6개, FK 5개. **스키마만 추가하고 기존 테이블·데이터는 바꾸지 않는다.**
- 신규 설치 : V0 → V1 → V2. 기존 DB(V0·V1 적용) : V0·V1 체크섬 변화 없이 V2 만 적용된다. `repair`·초기화·수동 DDL 이 필요 없다.
- **개발 DB(`localhost:15439/lexorma`)** : 작업 전 이력은 V0(-1532103848)·V1(-514501166). 작업 중 IDE 에서 실행 중이던 애플리케이션이 재시작하면서 **Flyway 가 V2 를 개발 DB 에 적용했다** (2026-11-11 15:56, 체크섬 324210209, 빈 테이블 2개 추가, 기존 데이터 변화 없음). 의도해서 실행한 것은 아니지만 위 업그레이드 경로 그대로다. **V2 파일은 이미 적용됐으므로 내용을 바꾸면 안 된다** (바꿀 것은 다음 버전으로).
- V0 의 LEXORMA 물리 모델 예제(Lexorma 자체 테이블)에는 새 테이블 2개와 단어 `매핑` 이 없다 — V0 를 바꾸면 기존 DB 의 체크섬이 달라지므로 넣지 않았다. 넣으려면 V1 같은 데이터 전용 마이그레이션이 필요하다 (결정 필요, 8장).

## 6. 동시성

| 상황 | 처리 |
|---|---|
| 잠금 순서 | **논리 모델 행 → 물리 테이블 행** (고정). 매핑 변경은 엔터티의 논리 모델 행(M-6 과 같은 잠금)을 잠그고, 이어서 매핑할 테이블 행을 잠근다. 논리 변경은 모델 행만, 테이블·컬럼 삭제는 테이블 행만 잠그므로 순환이 없다. 프로젝트 전체는 잠그지 않는다 |
| 같은 엔터티·속성 동시 매핑 | 모델 행 잠금으로 직렬화 → 뒤 요청은 `SAVED_DATA_EXISTS` |
| 같은 테이블·컬럼 동시 매핑 (다른 모델의 엔터티) | 테이블 행 잠금으로 직렬화 → `SAVED_DATA_EXISTS`. 최종 방어는 UNIQUE (`DataIntegrityViolationException` → `SAVED_DATA_EXISTS`) |
| 부모 매핑 삭제 ↔ 속성 매핑 등록 | 같은 모델 행 잠금. 삭제가 먼저면 등록은 `MODEL_MAPPING_IS_NOT_DEFINED`, 등록이 먼저면 삭제는 `RELATION_EXISTS` |
| 엔터티·속성 삭제 ↔ 매핑 등록 | 같은 모델 행 잠금. 잠금 뒤에 엔터티·매핑을 다시 읽어 검사한다 |
| 테이블·컬럼 삭제 ↔ 매핑 등록 | 테이블 삭제(`deleteTable`)와 컬럼 삭제(`releaseColumns`)가 테이블 행을 잠근 뒤 매핑을 검사한다 (`TableInfoRepository.findForUpdate`, 여러 테이블이면 ID 순) |
| 예외 | 서비스 메서드 단위 트랜잭션이라 중간 예외는 전부 롤백된다 |

## 7. 검증

```bash
./gradlew clean build      # 결과는 아래 표
# PostgreSQL 18.6 : 빈 격리 DB(lexorma_m7_test)를 만들어 실행 후 삭제
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/lexorma_m7_test SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
LEXORMA_TEST_POSTGRES_URL=jdbc:postgresql://localhost:15439/lexorma_m7_test \
./gradlew :lexorma-app:test -PfrontendPrebuilt=true \
  --tests 'com.lexorma.ModelMappingIntegrationTest' --tests 'com.lexorma.LogicalModelIntegrationTest' \
  --tests 'com.lexorma.StandardizationQualityIntegrationTest' --tests 'com.lexorma.DictionaryUsageIntegrationTest' \
  --tests 'com.lexorma.SmokeTest' --tests 'com.lexorma.ReferenceSyncIntegrationTest' \
  --tests 'com.lexorma.SchemaManagementIntegrationTest' --tests 'com.lexorma.PhysicalModelIntegrationTest' \
  --tests 'com.lexorma.ErdIntegrationTest' --tests 'com.lexorma.DataPortalTemplateTest' \
  --tests 'com.lexorma.TermUploadNameGenerationTest' --tests 'com.lexorma.V0SeedMigrationTest'
```

| 자동 테스트 | H2 | PostgreSQL 18.6 |
|---|---|---|
| `ModelMappingIntegrationTest` 6건 : 엔터티 매핑(등록·조회·변경·삭제, 중복, 없는 ID, 다른 프로젝트, 목록 조건·페이징, 후보 테이블) / 속성 매핑(중복, 부모 불일치, 다른 프로젝트, 표준 용어 불변, 후보 컬럼, 부모 변경·삭제 차단) / 삭제 정책(엔터티·테이블·속성·컬럼·일괄 저장 삭제 차단, 해제 후 삭제, 모델·스키마·프로젝트 삭제, DB UNIQUE·FK, 롤백) / 현황(빈 모델·없음·일부·전체, 모델별·프로젝트별) / 권한(VIEWER·USER·NOT_APPROVE) / 동시 요청(같은 엔터티·테이블·속성·컬럼, 부모 매핑·엔터티·테이블·컬럼 삭제와 등록) | 통과 | 통과 |
| `SmokeTest.modelMapping` (HTTP : 현황 → 후보 → 매핑 → 상세 → 오류 코드 → 삭제, 예제의 계정 ↔ `TB_ACNT`) | 통과 | 통과 |
| `V0SeedMigrationTest` (V0 → V1 → V2 적용·재시작 검증, 매핑 테이블은 비어 있음) | 통과 | 통과 |
| `TableDesignServiceImplTest` (테이블 삭제가 행 잠금 조회를 쓰도록 수정) | 통과 | — |
| 회귀 : 위 명령의 나머지 (M-1 물리 모델·관계 동기화, M-2 ERD, M-3 스키마, M-5 사용 현황·품질 진단, M-6 논리 모델·ERD·레이아웃, Excel 업로드, DDL) | 통과 | 통과 |

- `./gradlew clean build` : **348 tests, 실패 0, 건너뜀 2** (`V0SeedMigrationTest` 의 PostgreSQL 1건, `UploadPerformanceTest` 1건). npm ci + vite build 포함.
- PostgreSQL 격리 DB : **108 tests, 실패 0, 건너뜀 0.** Flyway 이력 V0·V1·V2 성공, V0·V1 체크섬이 개발 DB 와 같다.
- 기존 데이터가 있는 DB : 개발 DB 를 `pg_dump` 로 복사한 격리 DB(`lexorma_m7_upgrade`, 실행 후 삭제)에서 `ModelMappingIntegrationTest`·`LogicalModelIntegrationTest`·`PhysicalModelIntegrationTest` 18건 통과.

**미검증 (완료로 보지 않음)**

- 성능 : 큰 모델(엔터티·테이블 수천 건)에서의 목록·현황 조회 시간, 실행 계획. 측정하지 않았다.
- 동시성 : 테스트는 스레드 2~4개로 결과의 일관성만 확인한다. 부하 상황의 잠금 대기·교착 빈도는 측정하지 않았다. MySQL·MariaDB·Oracle 은 대상이 아니다 (자체 DB 는 PostgreSQL·H2).
- 화면 : 매핑 UI 가 없으므로 브라우저 검증 없음. 영어 메시지는 등록 여부(`MessagesCoverageTest`)만 확인.
- V2 가 적용된 뒤의 개발 DB 에서 실행 중인 애플리케이션의 화면 동작은 확인하지 않았다.

## 8. 2차 연계 · 후속

- **M-7 2차 (매핑 관리 UI·정합성 비교)** : 화면은 `GET /entity/data`(그리드) → 상세(`/entity/{id}`)의 `attributes`·`unmappedColumns` 로 좌우 연결 UI 를 만들 수 있다. 메뉴를 추가할 때 `MENU_ROLES`(VIEWER, 변경 버튼은 USER)·`menu.ts` 를 함께 고친다.
- 정합성 비교 : 상세의 양쪽 표준 용어(`attributeTermId` ↔ `columnTermId`)가 출발점이다. 이름·타입·길이·NULL 차이는 M-5 품질 진단처럼 읽을 때 계산하고 저장하지 않는 방식을 권한다 (타입은 `TableDdlGenerator.mapType`).
- 물리명 추천(용어 → 컬럼명, 엔터티 → 테이블명), 논리 → 물리 일괄 생성(D-08)은 미구현.
- 1:N·N:M 확장 : UNIQUE 4개가 1:1 을 강제한다. 확장할 때는 해당 UNIQUE 를 (엔터티, 테이블) 복합으로 바꾸고 "속성 매핑이 있으면 변경 차단" 규칙을 부모 매핑 단위로 유지하면 된다. 로드맵 요구가 생길 때 결정한다.
- **결정 필요** : V0 예제 물리 모델·데이터 사전에 매핑 테이블 2개와 단어 `매핑` 을 넣을지 (넣는다면 데이터 전용 새 마이그레이션 + `V1` 논리 예제 보강).
- 목록 조회 범위가 커지면 `TB_ENTT_TBL_MPNG (PRJ_SN)` 외 인덱스와 현황의 상관 서브쿼리를 조인 집계로 바꾸는 것을 검토한다 (측정 후).
- 프로젝트 삭제 시 모델 데이터 정리는 여전히 없다 (기존 공백). 만들 때 매핑을 먼저 지운다.

## 9. 후속 반영 : 예제 모델·COMMENT (V3)

- `V3__mapping_example_and_comments.sql` (postgresql·h2, 시퀀스 문법만 다름) : LEXORMA 예제에 매핑 테이블 2개를 넣었다 — 단어 `매핑`·용어 2개, 물리 모델(테이블 2·컬럼 16·FK 관계 5), 논리 모델(엔터티 2·속성 16·주 식별자 2·관계 5). 논리·물리 예제를 매핑으로 잇지는 않았다.
- Lexorma 자체 테이블 37개·컬럼 357개 전부에 `COMMENT ON` 을 달았다 (테이블 = 한글명, 컬럼 = 한글 용어). 이전에는 COMMENT 가 하나도 없었다. `SPRING_SESSION*`·`flyway_schema_history` 는 제외.
- **관리 규칙** : 테이블·컬럼을 추가하는 마이그레이션은 같은 파일에 COMMENT 와 예제(물리·논리)를 넣는다. `V0SeedMigrationTest` 가 실제 스키마의 `TB_` 테이블·컬럼 집합과 COMMENT 를 예제(`TB_TBL.TBL_KORN_NM`, `TB_COL.COL_EXPLN`)와 비교해 빠지면 실패한다.
- 5장·8장의 "V0 예제에 새 테이블이 없다 / 결정 필요" 는 이 마이그레이션으로 해소됐다.
- 기존 DB : 프로젝트 0 에 같은 이름의 테이블·단어를 직접 등록해 둔 경우 이름으로 찾는 하위 조회가 여러 행이 되어 V3 가 실패할 수 있다 (V1 과 같은 전제).

## 10. 2차에서 달라진 점

- API 추가 : `GET /entity/lookup`(엔터티 또는 테이블의 매핑 1건), `GET /quality/summary`·`/quality/data`(정합성 비교). 4장의 기존 API 와 DTO 는 바뀌지 않았다.
- 메뉴 : `model-mapping`(VIEWER)·`model-mapping-edit`(USER) 를 `MENU_ROLES` 에, `model-mapping` 을 `logical-modeling` 그룹에 넣었다.
- 7장의 "화면 : 매핑 UI 가 없으므로 브라우저 검증 없음"은 2차에서 해소됐다. 상세는 [model-mapping-quality-m7.md](model-mapping-quality-m7.md).
- **예제 매핑 (V4)** : `V4__mapping_example_data.sql` 이 LEXORMA 예제의 엔터티 37개·속성 357개를 전부 물리 테이블·컬럼에 매핑한다 (엔터티명 = 테이블 한글명, 같은 표준 용어). 5장·7장·9장의 "매핑 테이블은 비어 있다 / 예제를 매핑으로 잇지 않았다"는 V4 로 바뀌었다. 상세는 [model-mapping-quality-m7.md](model-mapping-quality-m7.md) 8장.

## 11. 3차에서 달라진 점

- API 추가 : `GET /recommendation/column/summary`·`/recommendation/column/data`·`/recommendation/table/data`(물리명 추천, 조회 전용). `GET /entity/data` 에 `entityId` 조건이 생겼다 (요청·응답의 나머지는 그대로, `EntitySearch` 에 필드 1개 추가).
- 8장의 "물리명 추천은 미구현"은 3차에서 해소됐다 — [physical-name-recommendation-m7.md](physical-name-recommendation-m7.md).

## 12. 보강 1차 (2026-11-17) : DBMS 식별자 검사 · 논리 관계 FK 추론 중단

DEC-10 논리 → 물리 선택 생성의 1차 보강이다 (보강 5단계 중 1차, 사용자 확정 정책 P1·P1-a·P12·P13·P14·P15). 기존 API 경로·요청 형식·`planToken` 확인·잠금 순서·전체 롤백·이름 충돌 차단·건너뛰기·미승인 생성·매핑 자동 저장·USER 권한은 그대로다. DB 스키마·마이그레이션 변경 없음 (V7 없음).

### 12.1 DBMS 식별자 규칙 (`table/ddl/DbIdentifierRules`, `DbIdentifierCheck`)

DDL 생성기와 같은 곳에 두고, DDL 생성기의 제약조건명 생성(`PK_`·`UK_`·`FK_`)과 자르기(`TableDdlGenerator.constraintName`)가 이 규칙을 그대로 쓴다 — **DDL 출력은 바뀌지 않았다** (골든 파일·DDL 테스트 그대로 통과). 대상은 실제 DDL 대상 4종(PostgreSQL·Oracle·MySQL·MariaDB)이고 H2 는 Lexorma 자체 저장소라 규칙이 없다. 예약어는 검사하지 않는다 (P13).

| DBMS | 길이 기준 (P14) | 비인용 식별자 문자 (DDL 은 따옴표를 쓰지 않는다) |
|---|---|---|
| PostgreSQL | 63바이트 (UTF-8). 넘으면 DBMS 가 오류 없이 잘라서 만든다 | 문자(비라틴 포함) 또는 `_` 로 시작, 문자·숫자(0-9)·`_`·`$` |
| Oracle | 30바이트 — DDL 생성기와 같은 12.1 이하 기준. 12.2 이상은 128바이트지만 이번 단계는 30 (버전 설정은 후속) | 문자로 시작(`_` 시작 불가), 문자·숫자·`_`·`$`·`#` |
| MySQL | 64자 (데이터베이스·테이블·컬럼·인덱스·제약조건 모두 64) | `0-9 a-z A-Z $ _` 와 U+0080..U+FFFF, 숫자만은 불가 |
| MariaDB | 64자 | MySQL 과 같다 |

- **Lexorma 권장 형식** `^[A-Za-z_][A-Za-z0-9_$]*$` 은 DBMS 규칙과 따로 판정한다 : DBMS 가 받지 않으면 `IDENTIFIER_INVALID_CHARACTER`(오류), DBMS 는 받지만 권장 형식이 아니면 `IDENTIFIER_NOT_RECOMMENDED`(주의). 유효한 DBMS 식별자를 권장 형식과 다르다는 이유로 DBMS 오류라고 하지 않는다 (예 : 한글 이름은 네 DBMS 모두 받으므로 주의, Oracle 의 `_TMP` 는 오류, MySQL 의 `1ST` 는 주의).
- **길이 단위** : PostgreSQL·Oracle 은 UTF-8 바이트, MySQL·MariaDB 는 문자 수 (한글 21자 = 63바이트는 PostgreSQL 통과, 22자는 초과). DDL 생성기의 자르기는 예전처럼 문자 수 기준이다 — 영문 이름은 같고, 비ASCII 이름은 문자 규칙에서 따로 알린다.
- **자동 제약조건명** : DDL 이 이름을 만드는 대상만 잘림을 본다 — `PK_테이블`(PK 컬럼이 있을 때), `UK_테이블_컬럼`(PK 가 아닌 고유값 컬럼), `FK_테이블_컬럼`(참조 테이블명이 REF_COLUMN 관계가 된 컬럼 = DDL 의 FK 조건). 길이 기준을 넘으면 `CONSTRAINT_NAME_TRUNCATED`(주의, 최종 이름 포함). MySQL·MariaDB 의 PK 는 이름이 `PRIMARY` 로 고정이라 제외.
- **최종 이름 충돌** `CONSTRAINT_NAME_COLLISION`(오류) : 잘린 뒤의 이름(대소문자 무시)이 같은 이름 공간에서 겹친다. 자동 이름이나 잘린 이름이 낀 충돌만 알린다 — 저장된 이름끼리(사용자 UNIQUE·CHECK·인덱스·FK 관계 이름)의 그대로 중복은 각 저장 화면이 다룬다. 이름 공간 (`DbIdentifierCheck.sameNamespace`, 확인한 범위) :

| DBMS | 스키마 단위 | 테이블 단위 |
|---|---|---|
| PostgreSQL | PK·UNIQUE·인덱스 (인덱스 = 릴레이션) | 제약조건 PK·UNIQUE·FK·CHECK |
| Oracle | 제약조건 PK·UNIQUE·FK·CHECK / 인덱스(PK·UNIQUE 가 만드는 인덱스 포함) 따로 | — |
| MySQL | FK·CHECK | UNIQUE·인덱스 (PK 제외) |
| MariaDB | FK | UNIQUE·인덱스, CHECK (PK 제외) |

  확인하지 않은 것 : PostgreSQL 의 인덱스 이름 ↔ 테이블·VIEW 이름 충돌(같은 릴레이션 이름 공간), MySQL 에서 FK 가 만드는 암묵 인덱스 이름, DBMS 버전별 차이(MySQL 8.0.16 미만 CHECK 등). VIEW 이름은 대상이 아니다 (M-5 테이블명 규칙과 같다).
- 판정 기준을 바꾸면 `DbIdentifierRules.VERSION` 을 올린다 (생성 계획 `planToken` 에 들어간다).

### 12.2 생성 미리보기 (`POST /api/modelMapping/generate/preview`)

- 응답 `Plan` 에 필드 3개를 더했다 (기존 필드는 삭제·의미 변경 없음) : `dbType`(검사 기준인 프로젝트 DBMS), `identifiers`(식별자 진단 — 엔터티·속성 ID, 규칙 코드, 심각도, 종류, 이름, DDL 최종 이름, 실제·허용 길이, 근거, 사유, 개선 안내, 충돌한 이름), `relations`(아래 12.3).
- 검사 대상 : 만들 테이블명·컬럼명, DDL 이 만들 PK 제약조건명, 대상 스키마 이름(기본 스키마 제외). 대상 스키마의 기존 테이블·컬럼·제약조건·인덱스·FK 관계 이름을 한 모델에 넣어 판정하고(만들 객체는 음수 임시 ID), 만들 객체와 스키마 이름의 결과만 남긴다 — 기존 객체의 진단은 표준화 품질 진단에서 본다.
- **심각도와 생성 가능 여부를 분리했다** : 식별자 진단은 경고만 하고 `creatable` 을 바꾸지 않으며 이름을 자동으로 고치지 않는다 (기존 생성 가능 여부 그대로).
- P15 : 미승인 엔터티는 만들되 `엔터티 … 이(가) 승인 전입니다.` 경고, 미승인 관계는 `relations[].approved = false`.
- 화면 (`PhysicalGeneration.tsx`) : 문제가 있는 테이블명·컬럼 행을 강조하고 [DBMS 식별자 진단]·[논리 관계 FK] 구역을 더했다. 진단·관계가 없으면 예전 화면과 같다 (참조 테이블 열은 값이 있을 때만).

### 12.3 논리 관계 FK 추론 중단 (P1-a — 의도적인 동작 변경)

- **변경 전** : `references()` 가 "부모 단일 PK 와 같은 표준 용어의 첫 자식 속성" 을 FK 로 추론해 그 컬럼의 참조 테이블명(`refTableName`)을 채웠다 (REF_COLUMN 관계 동기화가 FK 로 만든다). 부모가 복합 PK·주 식별자 없음·부모 테이블 없음·같은 용어 속성 없음이면 경고만.
- **재현한 결함 (변경 전 코드, H2, 2026-11-17)** :
  - 자기 참조(부서 → 부서, PK `DEPT_ID` + 다른 용어의 `UPR_DEPT_ID`) : PK 컬럼 `DEPT_ID` 에 자기 테이블 `DEPT` 가 참조 테이블로 들어갔고 미리보기에는 경고가 없었다. 생성 뒤 참조 동기화는 관계를 만들지 않고 DDL 은 `DEPT_ID : 참조 테이블 DEPT 의 PK 가 자기 자신이라 FK 를 만들지 않았습니다.` 로 건너뛰었다 — 미리보기와 DDL 이 달랐다.
  - 같은 부모의 관계 두 개(공항 → 항공편 출발·도착, 자식에 공항 코드 속성 하나) : 두 관계가 경고 없이 같은 컬럼의 FK 하나(`FK_FLT_ARPT_CD`)로 합쳐졌다.
  - 복합 PK 부모(주문 번호 + 상세 번호) : 경고만, FK 없음 (분석대로).
  - 같은 엔터티 안에 같은 용어 속성이 둘이면 컬럼명 중복 오류가 먼저 나서 추론까지 가지 않는다.
- **변경 후** : 논리 관계에 부모 식별자 ↔ 자식 속성 짝이 저장되지 않으므로(P1 은 3차) 어떤 관계로도 FK 를 만들지 않는다. `ColumnPlan.refTableName` 은 항상 null(필드 유지), 생성된 컬럼의 참조 테이블명도 비어 있어 REF_COLUMN 관계·DDL FK 가 생기지 않는다 — 미리보기·생성 결과·DDL 이 같다. 기존 FK 경고 문구 2개는 없앴다.
- `relations` : 고른 엔터티(자식, 건너뛰는 엔터티 제외)를 대상으로 하는 관계마다 `fkCreated=false` 와 사유 — `ATTRIBUTE_PAIRS_MISSING`(항상), `SELF_REFERENCE`, `MULTIPLE_RELATIONSHIPS`(같은 부모의 관계가 둘 이상), `COMPOSITE_PARENT_KEY`, `PARENT_KEY_MISSING`, `PARENT_TABLE_MISSING`(부모가 생성 대상도 매핑도 아님). 화면은 "속성 짝 지정 기능 전까지 FK 를 만들지 않으며, 필요하면 생성 뒤 물리 모델에서 관계를 등록" 을 안내한다.
- 임시 FK 매핑 테이블을 만들지 않았고, 기존 관계의 속성 짝을 추측해 저장하지 않았다. 3차에서 `TB_LGCL_REL_ATRB`(가칭)가 생기면 `relationPlans` 가 명시적 짝을 읽도록 확장한다.

### 12.4 planToken

정규 문자열 접두어를 `v2` 로 올렸다 (이전 미리보기의 토큰은 반영 때 409). 더한 것 : 프로젝트 DBMS, 관계마다(ID·MDFCN_DT·부모 → 자식·FK 생성 여부·사유), 식별자 규칙 버전과 진단 결과(코드·종류·대상·이름·최종 이름·충돌 이름). 관계 추가·삭제·방향 변경, 프로젝트 DBMS 변경, 대상 스키마의 이름 변화로 진단이 바뀌면 409 `MODEL_CONFLICT`. 관계의 승인 상태는 생성 결과를 바꾸지 않아 넣지 않았다. 잠금 순서·트랜잭션 경계는 그대로다.

### 12.5 표준화 품질 진단 연계 (M-5 D3)

- 규칙 5개를 `StandardizationRule` 에 더했다 (`TargetType.IDENTIFIER` — 대상 종류는 진단의 `kind`) : `IDENTIFIER_LENGTH_EXCEEDED`(오류), `IDENTIFIER_INVALID_CHARACTER`(오류), `IDENTIFIER_NOT_RECOMMENDED`(주의), `CONSTRAINT_NAME_TRUNCATED`(주의), `CONSTRAINT_NAME_COLLISION`(오류). fingerprint 는 `FindingFingerprint` (핵심 값 : 종류·DBMS·이름, 충돌은 상대 이름).
- **따로 조회·집계** : `GET /api/standardizationQuality/identifiers/summary`·`/identifiers/data` (`IdentifierQualityService`, VIEWER, 조건 type·severity·kind·schemaId, 검색 tableName·name). 기존 `/summary`·`/data`(대시보드)·`/model/*`·`/terms/*` 는 코드와 값이 그대로다 — 모델 진단에 식별자 규칙을 type 으로 주면 0건.
- 데이터 : `table/repository/DdlIdentifierQueryRepository` 가 프로젝트(또는 스키마 하나) 단위 SQL 6번으로 스키마·테이블·컬럼·REF_COLUMN 관계·사용자 FK 관계·제약조건·인덱스 이름을 읽는다 (테이블 수와 무관). 조회 전용, 아무것도 고치지 않는다.
- 화면 : 표준화 품질 진단의 세 번째 탭 [DBMS 식별자 진단] (`?tab=identifiers`) — 기준 설명, 오류·주의 카드, 규칙별 카드, 그리드(이름·DDL 이름·길이/허용·사유·근거·안내·이동). 새 메뉴 없음.
- LEXORMA 예제(프로젝트 0, PostgreSQL) : 0건. 같은 예제를 Oracle 기준으로 보면 `FK_테이블_컬럼` 자동 이름 7개가 30자에서 잘린다(겹침 없음), MySQL·MariaDB 기준 0건 — 예제를 고치지 않았다 (`IdentifierQualityIntegrationTest.example`).

### 12.6 검증 (2026-11-17)

- 단위 : `DbIdentifierRulesTest` 6건(DBMS별 길이 경계, 한글 바이트·문자 수, DBMS 문자 규칙 ↔ 권장 형식, DDL 자르기 = 생성기), `DbIdentifierCheckTest` 7건(Oracle PK 잘림·충돌, 같은 모델의 DBMS별 차이, FK·PK·인덱스 이름 공간, 저장된 이름, 대상). 기존 DDL 골든·`TableDdlGenerator*Test` 그대로 통과.
- 통합 : `PhysicalGenerationIntegrationTest` 6건(기존 생성 흐름 + FK 미생성, 409·차단·격리, P1-a 회귀 — 자기 참조·같은 부모 두 관계·복합 PK 부모의 미리보기 = 생성 결과 = DDL, planToken 의 관계 추가·삭제·방향·DBMS 변경, 식별자 진단 — Oracle 길이·PK 잘림·스키마 기존 인덱스와 충돌·생성 가능 여부 그대로, P15 경고와 VIEWER 403·USER 허용), `IdentifierQualityIntegrationTest` 6건(Oracle 요약 = 목록·조건·fingerprint·조회 불변·DBMS 변경 반영, PostgreSQL 충돌, 기존 진단과 분리·격리, 권한, 예제 H2/PostgreSQL), `SmokeTest`(HTTP 바인딩·400, 그리드 정렬·검색 등록). 모두 AI Provider 없이 실행.
- 전체 : H2 `./gradlew build` 949건 실패 0 (건너뜀 42), PostgreSQL 18.6 빈 격리 DB `./gradlew test --rerun` 949건 실패 0 (건너뜀 29). 기존 대시보드 수치 : `/summary`·`/model/*` 코드 변경 없음, 테스트로 식별자 결과가 섞이지 않음을 확인 (변경 전후 실행 앱의 수치를 따로 비교하지는 않았다).
- 브라우저 (Chrome, h2-local 임시 데이터, 2026-11-17) : 생성 대화상자 — 한국어 라이트·다크 1120px, 테이블명 강조·제약조건명 충돌 경고(Oracle)·관계 FK 미생성(자기 참조) 표시, 참조 테이블 열 숨김; 품질 진단 [DBMS 식별자 진단] 탭 — PostgreSQL 예제 0건, Oracle 로 바꾼 예제 FK 잘림 7건. 606px(창 최소 폭)에서 가로 넘침 없음. 영어는 API 메시지(Accept-Language en)만 확인.
- **미검증** : 375px 화면(창이 606px 아래로 줄지 않고 앱이 iframe 을 막아 확인 못 함), 영어 화면 문구의 브라우저 표시(키 누락은 `typecheck` 가 막는다), 동시 생성 요청의 PostgreSQL 잠금 동작 신규 시험(기존 잠금 코드 그대로 — 전체 테스트만 통과), 실제 Oracle·MySQL·MariaDB 에서 DDL 실행.

## 13. 보강 2차 (2026-11-17) : PK 순서 · 속성 고르기 · 오류 엔터티 제외 · 생성 전 모델 버전

사용자 확정 정책 P3·P7·P10·P11. 1차와 같이 기존 API 경로를 유지하고 DB 스키마·마이그레이션 변경 없음 (V7 없음). 논리 관계 속성 짝(P1)·복합 FK(P2)·기존 테이블 수정은 하지 않았다.

### 13.1 P3 — 복합 PK 순서

- 새로 만드는 테이블의 컬럼 순서 (`PhysicalGenerationService.columnOrder`) : 주 식별자 속성을 식별자의 속성 순서(`TB_LGCL_IDNTFR_ATRB.SORT_ORD`, 같거나 없으면 식별자 속성 ID)대로 먼저, 같은 속성이 식별자에 두 번 있으면 한 번만, 나머지 속성은 속성 순서(SORT_ORD, ID)대로. 컬럼 `SORT_ORD` 가 이 순서이고 DDL 은 컬럼 순서로 PK 를 만들므로 복합 PK 가 논리 식별자 순서가 된다.
- 기존 테이블·이미 매핑된 엔터티는 그대로 (건너뜀). 논리 식별자 ↔ 물리 PK 강제 일치 정책(M-7)은 바꾸지 않았다 — 새 생성의 기본 순서일 뿐이다.

### 13.2 P7 — 속성 고르기

- 요청 `Request.excludedAttributeIds` (선택, 없거나 비면 모든 속성 — 이전 클라이언트 호환. Java 쪽은 3인자 생성자 유지).
- 서버 정규화 : 주 식별자 속성은 제외를 요청해도 만든다 (`주 식별자 속성 … 은(는) 제외할 수 없어 포함했습니다.` 경고). 정규화한 결과(최종 제외 속성)로 계획과 `planToken` 을 만든다 — PK 를 빼라는 요청과 빼지 않은 요청의 토큰이 같다.
- 제외한 속성은 컬럼·컬럼명·용어/도메인/타입 검사·속성 매핑·컬럼 오류 판정에서 모두 빠진다 (표준 용어 없는 일반 속성을 빼면 엔터티를 만들 수 있다). 엔터티 자체의 오류와 PK 속성의 오류는 그대로.
- 400 (`INVALID_PHYSICAL_MODEL`, 조용히 무시하지 않는다) : 고른 엔터티의 속성이 아닌 ID(다른 엔터티·다른 프로젝트·없는 ID), 중복 ID, null. 주 식별자 없는 엔터티의 속성을 모두 빼면 `모든 속성을 제외해 만들 컬럼이 없습니다.` 오류 (PK 만 남기는 것은 가능).
- 응답 `TablePlan.attributes` (`AttributeChoice` : 속성 ID·이름·primary·excluded·excludeRequested, 컬럼 순서) — 화면이 이것으로 속성 목록을 그린다.
- 명시적 FK 속성 짝의 필수 포함은 P1(3차)·4차에서 함께 한다.

### 13.3 P10 — 오류 엔터티 제외 후 다시 미리보기

- 서버 동작은 그대로다 : 오류(ERROR) 엔터티가 하나라도 있으면 반영하지 않고(400), 서버가 오류 엔터티를 빼고 만들지 않는다. 엔터티별 오류는 기존 `TablePlan.status`·`entityId`·`errors` 로 충분해 DTO 를 늘리지 않았다. 경고만 있는 엔터티는 CREATE.
- 화면 : [오류 엔터티 제외 후 다시 미리보기] — 오류 엔터티를 선택에서 빼고(그 엔터티의 속성 제외도 버림) 새 미리보기를 요청해 새 `planToken` 을 받는다. 남는 엔터티가 없으면 버튼이 꺼진다. 엔터티·속성·스키마 선택을 바꾸면 "다시 미리보기해야 생성할 수 있습니다" 를 보이고 [물리 테이블 생성] 을 끈다 (이전 토큰을 다시 쓰지 않는다 — 서버도 409).

### 13.4 P11 — 생성 직전 모델 버전

- 기존 버전 기능 재사용 : `ModelVersionService.createBeforeModelChange` (원본·모델 가져오기가 쓰던 `createBeforeImport` 의 본문을 공통 진입점으로 옮기고 `createBeforeImport` 는 그대로 위임 — 기존 호출 변경 없음). 스냅샷은 `ModelChangeRecorder.currentState`(물리·논리 모델 전체), 형식 버전·목록·상세·스냅샷·비교 API 그대로. **버전 복원 기능은 원래 없다** (비교·스냅샷 조회만) — 이번에 만들지 않았다.
- 이름 `GENERATE-<요청 UUID>`(서버가 반영 때 만든다), 설명 `논리 → 물리 생성 직전 상태 (엔터티 n개, 요청 …)`. 새 출처 코드·이력 분류는 없다 (변경 이력은 일반 저장과 같다).
- 반영 순서 : 프로젝트 행 배타 잠금(+ `ProjectWriteGuard.lock`) → 기본/대상 스키마 해석 → 논리 모델 행(ID 순)·대상 스키마 행을 **NOWAIT** 로 잠금(잡지 못하면 409 `MODEL_CONFLICT` "다른 사용자가 … 바꾸는 중") → 계획 재계산·`planToken` 비교(다르면 409) → 생성 가능 확인(아니면 400) → 버전 저장 → 테이블·컬럼·매핑. 이전 순서(논리 모델 → 이름 공간, 기다림)에서 바뀌었다 : 버전 스냅샷이 한 시점이 되려면 프로젝트 행 배타가 먼저이고, 그 뒤에 일반 모델 저장이 쥔 행을 기다리면 교착이므로(그 저장은 커밋 직전에 프로젝트 행 공유 잠금) 원본 가져오기(`SourceImportService.apply`)와 같이 기다리지 않는다.
- 미리보기·409·400(오류·만들 것 없음)은 버전을 만들지 않는다. 한 트랜잭션이라 생성 중 실패하면 테이블·컬럼·매핑·버전 행이 모두 남지 않는다. 응답 `Result` 에 `versionId`·`versionName` 추가.

### 13.5 planToken

정규 문자열 접두어 `v3` : 엔터티별 최종 제외 속성(정규화 후)과 1차의 내용(엔터티·속성·용어 버전, 상태, 테이블명, 컬럼 계획 — 순서·PK 포함, 관계 계획, DBMS·식별자 진단). 원본 요청 JSON 이 아니라 정규화된 계획이다. 식별자 순서·속성명·도메인(컬럼 타입이 바뀌면)·제외 변경 → 다른 토큰(409). 같은 계획은 같은 토큰. 화면 표시만 바뀌는 값(속성 선택 목록의 이름 등)은 넣지 않았다.

### 13.6 화면

`PhysicalGeneration.tsx` : 엔터티마다 [속성 선택 (n개 중 m개 제외)] 펼침 — 일반 속성 체크·해제, 주 식별자 속성은 체크 고정 + `PK 필수` 표시, 선택이 바뀌면 다시 미리보기 안내. 요약 "만들 테이블 n개 · 컬럼 n개 · 오류 엔터티 n개. 생성 직전 모델 버전을 남깁니다.", [오류 엔터티 제외 후 다시 미리보기]. 완료 알림에 테이블·컬럼 수와 버전 이름. 새 메뉴·페이지 없음.

### 13.7 검증 (2026-11-17)

- 통합 (`PhysicalGenerationIntegrationTest` 13건 중 2차 7건) : P3 — 식별자 순서 ≠ 속성 순서, 식별자 순서 변경 409, 같은 SORT_ORD 의 식별자 속성 ID 순, 미리보기 = 저장 순서 = DDL `PRIMARY KEY (DTL_NO, ORD_NO)`; P7 — 용어 없는 일반 속성 제외 후 생성, PK 제외 요청 정규화·경고·같은 토큰, 제외 변경 409, 제외 속성 매핑 없음, 다른 엔터티·다른 프로젝트·없는·중복 ID 400, PK 만 남기기, 주 식별자 없이 전부 제외 오류; P10 — 오류 엔터티가 있으면 400(버전 없음), 이전 토큰으로 일부만 요청 409, 새 미리보기로 생성, 경고만 있는 엔터티는 CREATE, 빈 요청 400; P11 — 미리보기·409·만들 것 없음은 버전 없음, `GENERATE-` 버전 설명·스냅샷(생성 전)·현재 모델 비교(새 테이블 ADDED); 동시성 — 같은 계획 동시 반영 2건 → 1건 성공·1건 409·버전 1개, 같은 프로젝트의 다른 논리 모델 생성 + 모델 가져오기(DEC-22) 동시 → 60초 안에 둘 다 성공·버전 2개(교착 없음); planToken — 속성명·도메인 길이 변경 409, 같은 계획 같은 토큰, 도메인 설명만 바꾸면 토큰 그대로.
- `PhysicalGenerationRollbackIntegrationTest` 2건 (스파이 빈) : 버전 저장 뒤 속성 매핑 저장 실패 → 테이블·컬럼·매핑·버전 행 수가 반영 전과 같음, 실패를 거두면 같은 토큰으로 생성; 버전 저장 실패 → 아무것도 만들지 않음.
- 전체 : H2 `./gradlew build` 957건 실패 0 (건너뜀 42) — 그 뒤 추가한 토큰 테스트 1건은 H2 에서 따로 통과(합 958); PostgreSQL 18.6 빈 격리 DB `./gradlew test --rerun` 958건 실패 0 (건너뜀 29, 동시성 테스트 포함).
- 브라우저 (헤드리스 Chrome 을 CDP 로 직접 조작 — 로케일 en-US/ko-KR, prefers-color-scheme, 기기 폭 375/1440 을 지정; h2-local 임시 데이터) : 한국어·영어 × 라이트·다크 × 1440·375 의 8조합에서 생성 대화상자 — 두 엔터티 오류 → 속성 펼침(PK 속성 체크 고정·`PK 필수`) → 비고 해제 시 "다시 미리보기" 안내와 생성 버튼 꺼짐 → 재미리보기 → [오류 엔터티 제외 후 다시 미리보기] → 만들 테이블 1·컬럼 3·오류 0 → 생성 가능, 1차의 관계 FK 미생성 안내(자기 참조) 표시, 페이지·대화상자 가로 넘침 없음; 품질 진단 [DBMS 식별자 진단] 탭 영어 375·1440 넘침 없음; 한국어 1440 에서 실제 생성 → 알림에 테이블 1·컬럼 3·`GENERATE-…` 버전, 매핑 3/4. 1차에서 못 한 영어 화면·375px 를 이번에 확인했다.
- **미검증** : 영어 화면에서 식별자 진단 경고 항목의 표시(이번 데이터는 진단 0건 — 영어 문구는 1차에서 API 로 확인), 375px 의 컬럼 표는 줄바꿈으로 좁게 보인다(넘침은 없음). 버전 복원(기능 없음). 실제 Oracle·MySQL·MariaDB DDL 실행.

## 14. 보강 3차 (2026-11-17) : 논리 관계 속성 짝 · V7

사용자 확정 정책 P1(관계에 부모·자식 속성 짝을 명시적으로 저장)·P1-a(짝이 없으면 FK 없이 경고). P2·P4·P5·P7 의 FK 관련 실제 생성은 4차 — 이번에는 그 근거가 되는 데이터 구조·검사·표시까지다. 물리 FK·`refTableName`·`TableRelation` 을 만들지 않고, 기존 관계의 짝을 추론하지 않으며, 기존 물리 모델을 바꾸지 않는다.

### 14.1 V7 (`V7__logical_relationship_attribute.sql`, postgresql·h2 — 시퀀스 문법만 다름)

- `TB_LGCL_REL_ATRB` (논리 관계 속성) : `LGCL_REL_ATRB_SN`(PK) · `LGCL_REL_SN` · `ORGNL_ATRB_SN`(부모 = 관계 원본 엔터티 속성) · `TRGT_ATRB_SN`(자식 = 관계 대상 엔터티 속성) · `SORT_ORD` · 감사 컬럼 4개, 시퀀스 `SQ_LGCL_REL_ATRB`. 이름은 기존 `TB_LGCL_REL` 의 원본·대상 규칙을 따랐다.
- 제약 : `UX_LGCL_REL_ATRB_01 (LGCL_REL_SN, ORGNL_ATRB_SN)` · `_02 (LGCL_REL_SN, TRGT_ATRB_SN)` · `_03 (LGCL_REL_SN, SORT_ORD)` — 한 관계 안에서 부모·자식·순서가 각각 하나, 다른 관계는 같은 속성을 다시 쓸 수 있다(출발·도착 공항). `IX_LGCL_REL_ATRB_01/02` (속성별 조회 — 속성 삭제 보호). FK 3개(`TB_LGCL_REL`, `TB_LGCL_ATRB` ×2), CASCADE 없음 : 관계 삭제는 서비스가 짝을 먼저 지운다(변경 이력이 남도록), 짝에서 쓰는 속성은 DB 도 지우지 못한다. 엔터티 소속·부모 주 식별자 검사는 서비스.
- 기존 데이터 : 행 변경 없음, 기존 관계에 짝을 만들지 않는다. 예제(프로젝트 0) : 새 단어 없음, 새 용어 3개(`논리_관계_속성_일련번호`·`원본_속성_일련번호`·`대상_속성_일련번호`), 물리 테이블·컬럼 9·논리 엔터티·속성·주 식별자·매핑, 물리 FK 관계 3(USER)과 거기서 나온 논리 관계 3(논리 관계 → 논리 관계 속성, 논리 속성 → 논리 관계 속성 ×2 — 같은 부모의 관계 둘), **그 새 관계 3개에만** 속성 짝(FK 컬럼 그대로 — 추론이 아니라 물리 FK 정의), 인덱스 5·인덱스 컬럼 8, 업무 설명. 고정 수치 : 예제 인덱스 132 → 137, 인덱스 컬럼 187 → 195, 마이그레이션 7 → 8 (`LexormaIndexSeedTest`·`V0SeedMigrationTest`·`IndexManagementIntegrationTest`). 다음 스키마 변경은 V8.

### 14.2 도메인 · 서비스 · API

- 엔티티 `logical/domain/entity/LogicalRelationshipAttribute` (관계 쪽에 목록을 두지 않는다 — 저장소 `LogicalRelationshipAttributeRepository` 로만 읽고 쓴다).
- 판정 `logical/service/LogicalRelationshipPairs` (저장 화면·목록·논리 ERD·물리 생성 미리보기 공용) : status `NONE` · `PARENT_KEY_MISSING` · `PARENT_KEY_MISMATCH`(짝의 부모 속성이 지금 부모 주 식별자에 없음 — 식별자 변경 뒤) · `PARTIAL` · `COMPLETE`(부모 주 식별자 전체), warnings `ORDER_DIFFERS` · `SELF_KEY_REUSED`(자기 참조에서 자식 = 부모 키 속성 자신) · `IDENTIFYING_CHILD_NOT_KEY`(P4) · `REQUIRED_CHILD_NULLABLE`(P5, 관계 원본 최소 ≥ 1 인데 자식 속성이 필수 아님 — NULL 여부는 속성 기준).
- `GET /api/logical/relationship/{id}/attributes` (VIEWER) : 짝·판정·편집 후보(부모 = 부모 주 식별자 속성 순서대로, 자식 = 자식 엔터티 속성). `PUT …/attributes` (USER) : 관계 단위 일괄 교체 `{modifiedAt, pairs:[{parentAttributeId, childAttributeId}]}` — 순서 = 목록 순서, 빈 목록 = 모두 해제.
  - 검사(400 `INVALID_LOGICAL_MODEL`, 하나라도 어기면 아무것도 바꾸지 않는다) : 부모 속성은 관계 부모 엔터티의 주 식별자 속성, 자식 속성은 관계 자식 엔터티 속성(다른 엔터티·다른 프로젝트·없는 ID 거절), 부모·자식 각각 중복 없음, 빈 ID, 부모 주 식별자 없음, 50개 한도. 부모 주 식별자의 일부만 잇는 저장은 허용하되 `PARTIAL` 로 보인다(완성으로 표시하지 않는다).
  - 잠금·충돌 : 관계 저장과 같다 — 논리 모델 행 잠금 → 관계 다시 읽기 → `modifiedAt` 비교(DEC-08, 다르면 409 `MODEL_CONFLICT`, HTTP 는 기준값 필수 `MODEL_BASE_REQUIRED`), 승인된 관계는 바꿀 수 없다(`APPROVED_DATA_CANNOT_BE_UPDATE`). 짝이 바뀌면 관계의 `MDFCN_DT` 를 올린다(구성만 바뀌어도 기준값이 바뀐다 — 식별자와 같은 규칙), 같은 구성이면 아무것도 쓰지 않는다.
- 관계 목록(`/model/{id}/relationship`)·논리 ERD(`/model/{id}/erd`) 응답의 관계에 `attributePairs`(판정) 추가 — ERD 는 이미 읽은 식별자로 판정해 SQL 5번(짝 1번 추가, 데이터 크기와 무관). 관계 저장 응답(`POST /relationship`)에는 짝이 없다(null).
- 삭제 : 관계 삭제 = 짝 먼저 삭제(같은 트랜잭션). 속성 삭제 = 짝에서 쓰면 409 성격의 `RELATION_EXISTS` "관계 … 의 속성 짝에서 사용 중인 속성입니다"(다른 관계의 짝을 말없이 지우지 않는다). 엔터티 삭제는 관계가 있으면 원래 막힌다. 식별자 변경 : 짝을 자동으로 바꾸지 않고 `PARENT_KEY_MISMATCH` 로 보인다. 프로젝트 삭제(`ProjectDataCleanup`) : 관계보다 먼저 짝을 지운다.

### 14.3 이력 · 버전 · 교환

- 변경 이력·모델 버전 : `ModelChangeRecorder` 의 자식 행으로 등록(관계 state 의 `attributes` 배열 : `parentAttributeId·Name`, `childAttributeId·Name`, `sortOrder`) — 관계 이력·버전 스냅샷·교환 내보내기에 함께 담긴다. 모델 버전 형식 번호 5 → 6. 비교는 빈 `attributes` 와 없는 필드를 같게 본다 — 형식 5 이하 버전과 짝 없는 현재를 비교해도 관계가 바뀐 것으로 보이지 않는다. 복원 기능은 원래 없다. 2차의 `createBeforeModelChange` 는 같은 `currentState` 를 쓰므로 생성 전 버전에도 짝이 들어간다.
- 모델 교환 형식 2 → **3** (가져오기는 1·2·3) : 관계 state 의 `attributes` 를 파일 안의 속성 번호로 내보내고, 가져올 때 새로 만든 속성에 다시 잇는다(원래 DB ID 를 쓰지 않는다). 파일 검사 : 부모 = 관계 원본 엔터티의 주 식별자 속성, 자식 = 대상 엔터티 속성, 부모·자식·순서 중복 없음, 형식 1·2 파일에 짝이 있으면 거절 — 어기면 파일 전체 400 (계획·반영 모두, 아무것도 만들지 않음). 결과 `created` 에 `LOGICAL_RELATIONSHIP_ATTRIBUTE` 건수.

### 14.4 물리 생성 미리보기 연계 (3차 당시 — FK 는 만들지 않았다, 4차는 15장)

- `RelationPlan` 에 `pairStatus`·`pairWarnings`·`pairs` 추가, 사유 첫 항목을 짝 상태로 : `ATTRIBUTE_PAIRS_MISSING`(없음) · `ATTRIBUTE_PAIRS_INCOMPLETE`(일부) · `ATTRIBUTE_PAIRS_INVALID`(식별자 불일치) · `FK_GENERATION_NOT_SUPPORTED`(완성 — FK 생성은 4차). `fkCreated` 는 항상 false, 메시지도 상태별.
- `planToken` 접두어 v4 : 관계는 ID·부모·자식·FK 생성 여부·사유(짝 상태 포함)만 넣는다. 1·2차의 관계 `MDFCN_DT` 는 뺐다 — 관계 이름·설명·카디널리티 같은 표시 정보나 같은 상태 안의 짝 세부는 지금 생성 결과(FK 없음)를 바꾸지 않아 토큰을 무효화하지 않는다. 짝 상태가 바뀌면 409. 4차에서 FK 를 만들면 짝 자체를 넣는다.

### 14.5 화면

- 공용 `features/logical/relationshipPairs.tsx` : `RelationshipPairsEditor`(행 = 저장된 짝 + 짝이 없는 부모 식별자 속성, 행마다 자식 속성 선택·↑↓ 순서 변경, 같은 자식 중복 표시와 저장 막기, 자기 참조에서 부모 키 자신 선택 경고, 고르지 않은 행 수 안내, 서버 판정·경고 표시, 409·400 메시지), `PairStatusLabel`, `PairList`.
- 논리 모델 관리 [관계] 표에 [속성 짝] 열(상태 라벨·짝 수 — 누르면 대화상자, 편집 권한 + 승인 전이면 편집, 아니면 보기). 논리 ERD 관계 패널에 속성 짝 목록·상태·경고, [속성 짝 편집](편집 모드 + 승인 전)/[속성 짝 보기], 저장하면 ERD 를 다시 읽는다. 관계선의 연결 위치는 바꾸지 않았다(범위 밖). 물리 생성 대화상자의 관계 항목에 짝 상태·목록. 새 메뉴 없음.

### 14.6 검증 (2026-11-17)

- 마이그레이션 : `V7LogicalRelationshipAttributeMigrationTest` (H2·PostgreSQL) — V6 까지 적용된 DB 에 사용자 논리 모델(공항 → 항공편 관계 2개)을 넣은 뒤 V7 만 적용·체크섬 검증·재실행 0건, 기존 관계·행 그대로, 기존 관계에 짝 0건, 예제는 새 관계 3개에만 짝, 관계별 부모·자식·순서 UNIQUE·없는 관계/속성 FK·짝에서 쓰는 속성 삭제 거절, 다른 관계의 같은 부모 속성 허용. 신규 설치 : `V0SeedMigrationTest`·`LexormaIndexSeedTest` (H2·PostgreSQL, 실제 스키마 = 예제), `SmokeTest` 예제 표준화 진단 0건, `IdentifierQualityIntegrationTest.example` (예제 식별자 진단 결과 변화 없음).
- 기능 : `LogicalRelationshipPairsIntegrationTest` 8건 — 복합 짝 저장·조회·순서 경고·일부·해제·같은 구성 재저장 무변경, 검사 400 7종(아무것도 바꾸지 않음)·부모 주 식별자 없음·다른 프로젝트 관계, 자기 참조(별도 속성 / 키 자신 경고)·같은 부모 관계 둘·P4·P5 경고, 관계 삭제·속성 삭제 보호·식별자 변경 후 MISMATCH, 409·기준값 필수·승인 잠금·VIEWER/USER 권한, 변경 이력의 attributes·버전 비교(형식 5 흉내 버전과 짝 없는 현재 UNCHANGED, 짝 변경 MODIFIED)·스냅샷, 교환(형식 3, 새 속성 번호로 재연결, 형식 2 의 짝·잘못된 참조 400 이고 아무것도 만들지 않음, 형식 2 파일 가져오기), 물리 생성 미리보기(짝 없음·일부·완성 사유, FK 미생성, 짝 상태 변경 409, 관계 이름만 바꾸면 토큰 그대로). 기존 테스트 수정 : `ProjectDeletionIntegrationTest` 준비 데이터에 짝, `ModelSnapshotFieldCoverageTest`(자식 행 7), `ModelExchangeIntegrationTest`(형식 3, 형식 1 호환 파일에서 짝 제거), `ModelVersionIntegrationTest`(형식 6), 고정 인덱스 수치.
- 전체 : H2 `./gradlew build --rerun-tasks` 968건 실패 0 (건너뜀 43), PostgreSQL 18.6 빈 격리 DB `./gradlew test --rerun` 968건 실패 0 (건너뜀 29 — V7·V0·인덱스 시드의 PostgreSQL 변형 실행됨). 그 전 PostgreSQL 실행 1회에서 `ProjectRecoveryLoginTest` 가 "HTTP/1.1 header parser received no bytes" 로 초기화 실패 — 단독 재실행·전체 재실행에서 재현되지 않았다 (pending-work [M-5] ⑦ 의 첫 로그인 연결 끊김과 같은 증상으로 보이며 원인 미확인).
- 브라우저 (헤드리스 Chrome · CDP, h2-local 임시 데이터) : 한국어 1440 라이트 흐름 — 복합 짝 지정·저장 → 다시 열어 같은 값, ↓ 순서 변경 → '순서가 다릅니다' 경고, 같은 자식 중복 표시·저장 막힘, 자기 참조(상위부서번호), 출발·도착 두 관계에 서로 다른 자식 속성, 관계 목록의 짝 상태 라벨, 관계 삭제. 한국어·영어 × 라이트·다크 × 1440·375 의 8조합 — 속성 짝 대화상자, 논리 ERD 관계 패널(편집 모드의 [속성 짝 편집]·짝 목록)과 대화상자, 물리 생성 미리보기의 관계 짝 상태·목록, 페이지·대화상자 가로 넘침 없음.
- 검증 중 고친 화면 결함 : ① 미리보기 관계 항목의 테두리 스타일이 하위 짝 목록에도 적용 — 선택자를 직계로. ② **2차부터 있던 결함** — 오류 엔터티가 있을 때 생성 대화상자의 버튼 세 개가 줄바꿈되지 않아 375px 에서 대화상자 안쪽이 넓어졌다(한국어 452px·영어 610px / 338px). 2차의 375px 측정은 오류 엔터티를 뺀 뒤라 드러나지 않았다 — 버튼 줄 줄바꿈으로 고쳤고 8조합에서 다시 확인했다.
- **미검증** : 관계선의 속성별 연결 위치(범위 밖), 동시 짝 저장의 PostgreSQL 잠금 경쟁 시험(관계 저장과 같은 잠금 경로 — 409 계약은 단일 스레드 테스트로만), MCP·AI 도구 응답의 짝 노출(관계 DTO 에 필드가 더해졌을 뿐 따로 확인하지 않음), 실제 DBMS 에서의 DDL 실행.

## 15. 보강 4차 (2026-11-17) : 논리 관계 속성 짝 기반 물리 FK 생성

3차에서 저장한 부모 주 식별자 ↔ 자식 속성 짝(`TB_LGCL_REL_ATRB`)만 근거로 물리 FK 를 만든다 (P1). 용어·이름 추론은 하지 않는다 (P1-a 유지). DB 마이그레이션 없음 (다음 V8).

### 15.1 FK 계획 (`PhysicalGenerationService.relationPlans`)

- 대상 : 이번에 만드는 자식 엔터티(건너뛰는 엔터티 제외)로 들어오는 논리 관계, 자식 ID → 관계 ID 순.
- 상태 `fkStatus` : `READY` · `NO_PAIRS` (짝 없음) · `PARTIAL_PAIRS` (일부) · `IDENTIFIER_MISMATCH` (짝이 지금 부모 주 식별자와 다름·부모 주 식별자 없음) · `PARENT_TABLE_UNAVAILABLE` (부모를 이번에 만들지 않고 매핑도 없음) · `COLUMN_UNAVAILABLE` (자식 속성의 용어·약어·도메인 없음, 기존 부모 테이블에 부모 속성 매핑 없음) · `UNSUPPORTED` (`PARENT_KEY_NOT_TABLE_KEY` 기존 부모 테이블 PK ≠ 짝의 부모 컬럼 / `SELF_KEY_ONLY` 자기 참조인데 자식 속성이 부모 키 자신 / `TYPE_INCOMPATIBLE` 관계 등록과 같은 `TableDdlGenerator.isReferenceTypeCompatible`) · `CONFLICT` (`CHILD_COLUMN_IN_USE` — 앞선 READY 관계가 이미 쓴 자식 속성; 관계를 합치지 않는다).
- READY 가 아닌 관계는 **생성을 막지 않는다** (경고, 그 관계만 FK 없음 — P1-a). 생성을 막는 것은 테이블 오류뿐(P10 — 오류가 하나라도 있으면 전체 거부).
- 유형 `fkType` (P2·P2 보완) : `SINGLE` 일반 단일 = 자식 컬럼의 참조 테이블명 `refTableName`(커밋 직전 참조 동기화가 REF_COLUMN 관계로 만든다; 부모가 다른 스키마면 `스키마.테이블`) · `SELF_SINGLE` 자기 참조 단일 · `COMPOSITE` 복합 · `SELF_COMPOSITE` 자기 참조 복합 = `TableRelation` (USER, FK, 미승인). 자기 참조 단일을 TableRelation 으로 하는 이유 : 참조 동기화의 `referencedPk` 가 자기 자신을 가리키는 참조 테이블명을 관계로 만들지 않는다.
- 부모 테이블 : 이번에 만드는 테이블, 또는 이미 매핑된 테이블(부모 속성 → 컬럼 매핑이 그 테이블에 있고, 그 컬럼 구성이 테이블 PK 전체와 같아야 함). 기존 테이블은 고치지 않는다. 매핑된 자식 엔터티는 건너뛴다(P9).
- 순서 : 짝 순서 = `TB_TBL_REL_COL.COL_ORD` = DDL 의 FK·참조 컬럼 순서. 부모 PK 순서와 다르면 3차의 `ORDER_DIFFERS` 경고를 그대로 보여준다(바꾸지 않음).
- 이름 : `DbIdentifierRules.foreignKeyName(자식 테이블, 첫 자식 컬럼)` (`FK_자식_컬럼` — DDL·관계 등록 기본 이름과 같은 꼴). 같은 부모로 가는 관계 둘은 자식 컬럼이 달라 이름도 다르다. 식별자 진단(1차)에 계획한 FK 이름을 더해 잘림·충돌을 본다 (SINGLE 은 컬럼의 참조 표시로, TableRelation 은 저장 이름 목록으로).
- P4·P5 : 짝 판정의 `IDENTIFYING_CHILD_NOT_KEY`·`REQUIRED_CHILD_NULLABLE` 경고를 관계 계획에 그대로 싣는다. PK·NULL 은 속성 기준 그대로 — 자동으로 바꾸지 않는다.
- P7 : READY 관계의 자식 속성은 제외 요청이 있어도 포함(`AttributeChoice.foreignKey`, 경고 "FK 속성 … 제외할 수 없어 포함"). READY 가 아닌 관계의 자식 속성은 뺄 수 있다(필수로 만들지 않음). 정규화로 'FK 속성 제외 요청' 과 '요청 없음' 의 토큰이 같다.

### 15.2 planToken (접두어 v5)

관계마다 관계 ID·부모 > 자식, 상태·유형·사유, 부모 테이블(새 / 기존 테이블 ID), 짝(부모·자식 속성 ID·순서), 부모·자식 컬럼명(순서대로), 제약조건명, 참조 테이블명. 메시지·표시 이름은 넣지 않는다. 짝 변경, 부모 주 식별자 변경, 부모 매핑 변경, 자식 속성 용어 변경은 409.

### 15.3 반영 (`apply`)

잠금(프로젝트 행 배타 → 논리 모델·스키마 NOWAIT) → 재계획·토큰 비교 → `GENERATE-…` 버전 → 테이블·컬럼(SINGLE 의 참조 테이블명 포함)·매핑 → **모든 테이블을 만든 뒤** TableRelation FK (`PhysicalModelService.createGeneratedForeignKey` — `saveRelation` 과 같은 `buildRelation` 검사 경로: 참조 테이블명 컬럼 거절·중복·`validateForeignKey`, 미승인, 규칙 없음). 순환(A → B, B → A)·자기 참조도 대상 테이블이 이미 있다. 어느 단계든 실패하면 한 트랜잭션 전체 롤백(버전 포함). 응답 `Result.foreignKeys` (SINGLE + TableRelation).

### 15.4 화면

생성 대화상자 [논리 관계 FK] : 관계마다 FK 생성/미생성, 유형, 부모 → 자식, `자식 테이블 (컬럼…) → 부모 테이블 (컬럼…)`, 제약조건명, 기존 부모 테이블 표시, 상태·메시지, 사유 라벨, 짝 상태·목록(3차). 속성 선택에 [FK 필수] 라벨과 잠긴 체크박스. 생성 결과 알림에 FK 수. 속성 짝 편집기의 안내 문구를 4차 기준으로 고쳤다.

### 15.5 검증 (2026-11-17)

- `PhysicalGenerationForeignKeyIntegrationTest` 8건 : 일반 단일(참조 테이블명 → REF_COLUMN 관계 → DDL)·같은 부모 관계 둘(FK 2개, 다른 이름), 자기 참조 단일·복합(TableRelation 미승인, DDL ALTER)·자기 키 UNSUPPORTED, 복합 순서(짝 순서 = 관계 컬럼 순서 = DDL, ORDER_DIFFERS 유지), 순환, 기존 매핑 부모(다른 스키마 `SALES.CUST`, 기존 테이블 무변경, 매핑 해제 → COLUMN_UNAVAILABLE·409, PK 불일치 → UNSUPPORTED), 만들 수 없는 관계 6종(생성은 막지 않음), P7·P4·P5·짝 변경 409, 동시 반영(하나만 성공, FK 한 벌). `PhysicalGenerationRollbackIntegrationTest.foreignKeyFailure` : FK 저장 실패 → 테이블·컬럼·매핑·버전·관계 0. 3차 `LogicalRelationshipPairsIntegrationTest.generationPreview` 를 4차 상태(부모 테이블 없음)로 고침.
- 전체 : H2 `./gradlew build --rerun-tasks` 977건 실패 0 (건너뜀 43), PostgreSQL 18.6 빈 격리 DB `./gradlew test --rerun` 977건 실패 0 (건너뜀 29). 마이그레이션 없음 — 예제 인덱스 137·인덱스 컬럼 195·마이그레이션 8 그대로.
- 브라우저 (헤드리스 Chrome · CDP, h2-local 임시 데이터, 포트 9941) : 한국어·영어 × 라이트·다크 × 1440·375 의 8조합 — 엔터티 3개(자기 참조 관계 둘, 일반 단일, 복합 짝)의 생성 미리보기에 FK 4개(자기 참조 단일 FK 2·일반 단일 1·복합 1)의 유형·컬럼·제약조건명·사유·짝 상태, [FK 필수] 체크박스 잠김, 페이지·대화상자 가로 넘침 없음. 1440 라이트에서 생성 → 결과 알림 "FK 4개", DDL 의 FOREIGN KEY, 물리 ERD(1440 라이트·375 다크)에 FK 컬럼 표시와 관계선·관계 목록.
- **미검증** : READY 가 아닌 상태(짝 없음·충돌 등)의 화면 표시는 통합 테스트로만(브라우저 시나리오는 READY 4종), 실제 Oracle·MySQL·MariaDB DDL 실행, 이미 만든 테이블에 나중에 FK 더하기(범위 밖 — 기존 테이블은 고치지 않는다), ON DELETE/ON UPDATE 규칙(논리 관계에 정보가 없어 규칙 없이 만든다).
- 검증 중 발견한 **기존 결함 (4차 범위 밖, 고치지 않음)** : 용어 등록 API `POST /api/insertDataDictionary/standardTerms` 에 `synonyms` 를 빼면 `DataDictionaryServiceImpl` 에서 NullPointerException → 500 (화면은 항상 보내 드러나지 않는다).

## 16. 보강 5차 (2026-11-17) : 통합 안정화 · 최종 검증

새 기능·정책·마이그레이션 없음 (다음 V8). 1~4차 정책 그대로, 결함은 재현 뒤 수정.

### 16.1 FK 정합성 (논리 관계 → 짝 → 미리보기 → 테이블·컬럼 → 물리 FK → DDL → ERD)

`PhysicalGenerationStabilizationIntegrationTest` (13건, H2·PostgreSQL) 와 4차 `PhysicalGenerationForeignKeyIntegrationTest`.

| 확인 | 결과 |
|---|---|
| 유형별 저장 경로 | 일반 단일 = `refTableName` → 커밋 때 REF_COLUMN 관계 / 자기 참조 단일·복합·자기 참조 복합 = USER `TableRelation`(미승인). 미리보기의 관계마다 저장된 관계의 부모·자식 테이블, 컬럼 쌍 순서(`COL_ORD`), 이름이 같다 (`assertStoredMatchesPlan`) |
| 일반 단일 FK 중복 | 논리 관계 하나 = 물리 FK 하나 : 참조 동기화 두 번, 테이블 상세, ERD 조회, DDL 재생성 두 번 뒤에도 2개(같은 부모 관계 둘)이고 합쳐지지 않는다. 같은 토큰 재시도 → 409, 새 미리보기는 모두 건너뜀 → 400, 버전 그대로 |
| 다른 스키마 같은 이름 | SA.CUST·SB.CUST·SC.ORD : 계획의 부모 테이블 ID·참조 테이블명 `SB.CUST`·저장된 관계 대상·DDL `REFERENCES SB.CUST`·ERD 연결 대상이 모두 SB, 다시 동기화해도 같다 |
| 순환·자기 참조 | A → B(관계 둘) → C → A(복합) + 자기 참조 단일·복합을 한 요청에 : FK 6개, 참조 동기화 뒤에도 자기 참조 FK 2개가 남는다, 통합 DDL 의 FK 6개 |
| DDL 4개 DBMS | 통합 내보내기 PostgreSQL·Oracle·MySQL·MariaDB 에서 FK 이름·FK 컬럼 순서·참조 컬럼 순서가 계획과 같고 Oracle 제약조건명은 30자 이내. **PostgreSQL 은 격리 스키마에서 실제 실행** — `pg_constraint` 의 FK 3개(복합 순서 포함)가 계획과 같다. Oracle·MySQL·MariaDB 는 격리 환경이 없어(로컬 Docker 이미지 없음, 새로 받지 않음) 문자열·기존 골든·식별자 규칙 테스트로만 |
| P3 | PK 순서 = 논리 주 식별자 순서 = 물리 컬럼 순서 = DDL `PRIMARY KEY (…)` |
| 결과 수 | `Result.foreignKeys` = 저장된 FK 수 (모든 생성 테스트), READY 아닌 관계는 세지 않는다 |

### 16.2 상태·경고·속성 선택

- READY 외 7개 상태를 브라우저에서 확인(16.6). P7 정규화 : PK 이면서 FK 인 속성·복합 FK 일부의 제외 요청은 무시(미리보기 = 토큰 = 생성), 용어 없는 일반 속성은 빼야 생성 가능.
- **수정한 결함** : 생성 대화상자에서 짝 경고(순서·자기 키·P4·P5)가 라벨의 "!" 와 마우스 툴팁으로만 보여 터치(375px)·키보드에서 내용을 볼 수 없었다 → 관계 항목 아래 본문 목록으로 표시(`PhysicalGeneration.tsx`, 짝 편집기와 같은 `PAIR_WARNINGS`). 대화상자 머리 주석의 "FK 는 만들지 않는다"(1차 기준) 설명도 고쳤다.

### 16.3 버전·스냅샷·교환

- 생성 전 버전 : 미리보기·400(생성할 수 없는 계획)·409(토큰 불일치)에는 없고 성공한 반영 1번에 1개(`GENERATE-…`), 실패하면 롤백(2차·4차 롤백 테스트 — 5차에서 관계·관계 컬럼·변경 이력 수까지 비교하도록 넓힘). 생성 전 버전에는 매핑이 없고, 생성 뒤 사용자 버전(형식 6)에는 엔터티·속성 매핑과 짝(순서)이 있다. 형식 5 ↔ 6 비교는 3차 테스트 그대로.
- 교환 형식 3 → 다른 프로젝트 → 생성 : 자기 참조 복합(짝 순서가 PK 순서와 반대)·같은 부모 관계 둘이 새 속성 번호로 이어지고 원본 프로젝트와 같은 FK 계획·같은 FK 3개가 생긴다. 교환은 표준 용어를 이름으로만 잇기 때문에 대상 프로젝트에 같은 이름의 용어가 없으면 컬럼을 정할 수 없어 COLUMN_UNAVAILABLE 이 된다(정책대로 — 테스트는 같은 이름의 용어를 먼저 둔다). 형식 1·2·잘못된 참조 거절은 3차 테스트 그대로.

### 16.4 planToken · 동시성 · 격리

- 토큰이 바뀌는 것 : 제외 속성, 대상 엔터티, 짝 순서, 자식 FK 속성의 용어(→ 컬럼명·제약조건명), 부모 주 식별자(→ IDENTIFIER_MISMATCH, 이전 토큰 409, 아무것도 만들지 않음), 부모 매핑·PK(4차). 바뀌지 않는 것 : 관계 이름·설명.
- 동시 (H2·PostgreSQL 에서 같은 불변식) : 같은 계획 둘(4차, 하나만 성공), 같은 모델의 다른 엔터티 둘(둘 다 성공 — 프로젝트 행에서 줄 선다), 생성 ↔ 같은 관계의 짝 저장, 생성 ↔ 부모 키 속성 매핑 해제(둘 다 결과는 성공 또는 409 뿐, 생성이 성공했으면 저장된 FK = 미리보기 계획, 실패면 아무것도 없음), 생성 ↔ 사용자 모델 버전(둘 다 성공, 사용자 버전은 생성 전 또는 후의 한 시점), 생성 ↔ 모델 가져오기(2차). 교착·타임아웃·500 없음. 잠금 순서(프로젝트 행 → 논리 모델 NOWAIT → 스키마 NOWAIT)는 코드 그대로.
- 다른 프로젝트 사용자의 미리보기·반영은 거절, 아무것도 만들지 않는다.

### 16.5 성능 (`PhysicalGenerationPerformanceTest`, `LEXORMA_PERF=true` 일 때만)

규모 : 10×10, 100×20, 300×30(엔터티×속성). 관계 = 엔터티마다 앞 엔터티로(3번째마다 복합 PK 부모 → 복합 FK), 7번째마다 같은 부모 관계 하나 더, 10번째마다 자기 참조. 한 요청 100개 제한(기존 정책)이라 300개는 100개씩 3번. SQL 수는 Hibernate 통계(JdbcTemplate 조회 제외). 절대 목표는 두지 않았다.

측정 → 원인 → 수정 :
1. 미리보기 쿼리 상위가 모두 엔터티 수만큼 반복 — 엔터티 개별 조회 100회, 엔터티 매핑 개별 조회 100회 → 한 번씩으로 (`LogicalEntityRepository.findAllByIds`, `EntityTableMappingRepository.findAllByEntityIds`). 속성·용어·도메인까지 조인 조회하면 H2 에서 오히려 느려져(1.0 → 1.8초) 엔터티만 한 번에 읽는다.
2. JFR 샘플링 : 미리보기 시간의 93~97% 가 조회마다 일어나는 Hibernate 자동 플러시(영속성 컨텍스트 전체 더티 체크 — 테이블·VIEW 이름 충돌 조회 등) → 계획 계산 동안 플러시 모드 COMMIT, 기본 스키마를 이번에 만든 경우에만 한 번 플러시(무조건 flush 는 플러시 리스너 때문에 문장이 467 → 1,010 으로 늘어 바꿨다).
3. 반영 시간의 72% 가 FK 등록 검사 조회의 자동 플러시 → FK 반복 동안 COMMIT 모드 + FK 하나마다 직접 flush (다음 FK 의 중복 검사가 앞 FK 를 지금처럼 본다).

미리보기 / 반영 시간(ms)과 Hibernate 준비 문장 수 (300×30 은 100개 묶음 3번의 합) :

| 규모 | H2 수정 전 | H2 수정 후 | PostgreSQL 중간(1번만) | PostgreSQL 수정 후 |
|---|---|---|---|---|
| 10×10 (FK 11) | 30 / 162 (문장 70 / 167) | 21 / 141 (52 / 140) | 49 / 234 | 48 / 245 |
| 100×20 (FK 123) | 1,029 / 3,137 (665 / 1,241) | 75 / 1,293 (467 / 944) | 1,201 / 3,642 | 198 / 1,894 |
| 300×30 (FK 371) | 5,313 / 16,216 (2,287 / 4,363) | 283 / 6,742 (1,693 / 3,472) | 6,347 / 19,033 | 825 / 8,984 |

- PostgreSQL 의 '수정 전'(1~4차 그대로) 은 재지 않았다 — 처음 잰 PostgreSQL 값은 1번(일괄 조회)만 적용한 중간 상태이고, H2 에서 1번은 시간에 영향이 거의 없었다(문장 수만 줄었다). 미리보기 힙 증가 3~51MB(GC 뒤 대략값). 엔터티 로드 수(100개 묶음 약 8,900)는 같다 — 지연 로딩 반복이 아니라 속성·용어 등 실제로 읽는 객체 수.
- 같은 요청 안에서 묶음 순서가 뒤로 갈수록(프로젝트 모델이 커질수록) 반영이 조금씩 늘어난다(PostgreSQL 2.6 → 3.3초).

남은 비용 : 미리보기의 표준 단어 조회(단어 수만큼)·테이블/VIEW 이름 충돌 조회(엔터티 수만큼)는 공용 서비스(`TableDesignService.generateTableName`, 이름 공간 검사)라 바꾸지 않았다. 반영의 나머지는 생성 전 모델 버전 스냅샷(JFR 약 24%) 등.

### 16.6 브라우저 (헤드리스 Chrome · CDP, h2-local 임시 데이터, 포트 9941)

한국어·영어 × 라이트·다크 × 1440·375 의 8조합 :
- 생성 대화상자 : 엔터티 4개 선택 → 첫 미리보기(용어 없는 속성·오류 엔터티 → 생성 불가, [오류 엔터티 제외 후 다시 미리보기] 표시) → 일반 속성 체크 해제 → 다시 미리보기 → 오류 엔터티 제외 → 생성 가능. 관계 9개 : READY 2(일반 단일 — P4·P5 경고 본문, 복합 — 순서 경고), NO_PAIRS, PARTIAL_PAIRS, CONFLICT, PARENT_TABLE_UNAVAILABLE, IDENTIFIER_MISMATCH(짝 저장 뒤 부모 식별자 변경), COLUMN_UNAVAILABLE(용어 없는 자식), UNSUPPORTED(자기 키) — 상태·메시지·사유·부모/자식이 한국어·영어로 보인다. [PK 필수] 4·[FK 필수] 3 잠금. 페이지·대화상자 가로 넘침 0, 잘린 버튼 0, 넓어진 관계 항목 0, 긴 영문 메시지 줄바꿈.
- 1440 라이트(언어별) : Tab 이동이 대화상자 안의 조작 요소로 이어진다 → 생성 → "테이블 3개·컬럼 7개·FK 2개" → 물리 ERD(1440 라이트·375 다크) 관계선·관계 이름·[DDL 보기]의 FOREIGN KEY 2개(복합 순서 포함) → 모델 버전 화면의 `GENERATE-…` → 표준화 품질 진단 [DBMS 식별자 진단] 탭.
- 3차 속성 짝 편집 회귀(복합 짝 저장·다시 열기·↓ 순서 변경 경고·중복 막기·자기 참조·출발/도착 관계·관계 삭제, 논리 ERD 편집) 8조합 통과.

### 16.7 테스트 결과

| 실행 | 결과 |
|---|---|
| H2 `./gradlew build --rerun-tasks` (npm ci·React 빌드·전체 모듈 테스트, 최종 소스 — 4차의 짝 편집기 문구 변경 포함) | 991건, 실패 0, 건너뜀 45, 종료 코드 0 |
| PostgreSQL 18.6 빈 격리 DB `./gradlew test --rerun` (`LEXORMA_TEST_POSTGRES_URL` 지정) | 991건, 실패 0, 건너뜀 30, 종료 코드 0 — `PhysicalGenerationStabilizationIntegrationTest` 의 DDL 실제 실행·V0~V7 마이그레이션 테스트 포함 |
| 성능 측정 (`LEXORMA_PERF=true`, H2·PostgreSQL 각각 별도 실행) | 일반 빌드에서는 건너뜀 1건으로 센다 |

두 전체 실행은 각각 한 번에 끝까지 돌았다 (나눠 실행한 결과를 합친 수치가 아니다). 건너뜀은 성능 측정·외부 원본 DB·PostgreSQL 전용 검증(H2 실행) 등 환경 조건이고, 이번에 새로 건너뛰는 것은 H2 실행의 `PhysicalGenerationStabilizationIntegrationTest` DDL 실제 실행 1건과 성능 측정 1건이다.

### 16.8 결정·제외

이번에 구현하지 않음 (정책 검토 대상, pending-work [M-7]) : 기존 물리 테이블에 FK 추가, ON DELETE/ON UPDATE 규칙, DBMS 버전별 식별자 길이, 모델 버전 복원. 기존 결함 `POST /api/insertDataDictionary/standardTerms` 의 동의어 누락 500 은 pending-work [DICT-1] 로 따로 관리한다.

