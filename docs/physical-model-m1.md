# M-1. 물리 모델 보강 — 설계 결정과 구현 기록

> [데이터 모델링 로드맵](data-modeling-roadmap.md) M-1 의 설계·구현 기록이다. 결정 요약은 로드맵 10장(D-03, D-04)에 반영했다.
> 작성일 2026-11-10. Flyway `V2__physical_model_schema_relation_index.sql` (postgresql·h2).

## 1. 기존 구조 (변경 전)

| 항목 | 현재 | 한계 |
|---|---|---|
| 테이블 | `TableInfo` (`table_info`, SEQUENCE pooled 50, `project_id` 로 격리) | 스키마 없음. 테이블명은 프로젝트 안에서 중복 불가(서비스 검사, DB 제약 없음) |
| 컬럼 | `ColumnInfo` (`table_info_id` FK + 테이블명 문자열 `table_name` 중복 저장), 정렬 `sort_order`, 승인 `is_approval` | — |
| 관계 | `ColumnInfo.refTableName` 문자열. DDL 이 참조 테이블의 **단일 PK** 를 추정해 FK 생성(복합 PK·PK 없음·미등록은 경고만) | 참조 컬럼·복합 FK·관계 유형 없음 |
| 인덱스·제약 | 컬럼 boolean `isPk`, `isUnique`, `isIndex`, `isNullable`/`isRequired` | 복합 인덱스·복합 UNIQUE·CHECK·이름 없음 |
| 설명 | `tableDescription`, `columnDesc` | 원본 COMMENT / 업무 설명 / AI 제안 구분 없음 |
| 삭제 | 테이블은 컬럼이 없고 미승인일 때만, 컬럼은 미승인일 때만 | 참조 테이블 삭제·이름 변경 시 `refTableName` 문자열만 갱신 |
| 프로젝트 삭제 | 프로젝트·멤버만 지운다 (`ProjectDeletedEvent` 리스너 없음) | 테이블·컬럼이 남는다 → V2 이전 데이터에 반영 |

쓰기 경로: 테이블 등록·수정·삭제·승인·엑셀 업로드(`TableDesignServiceImpl`), 컬럼 단건 등록·삭제·승인·정렬·일괄 저장·엑셀 업로드(`ColumnManageServiceImpl`). 새 저장 경로는 만들지 않고 이 경로들에 연동했다.

## 2. 데이터 모델 (V2)

```text
Project ─1:N─ schema_info ─1:N─ table_info ─1:N─ column_info            (기존 확장)
                                  │
                                  ├─1:N─ table_relation (원본 테이블) ─1:N─ table_relation_column (원본 컬럼 → 대상 컬럼, column_order)
                                  ├─1:N─ table_index ─1:N─ table_index_column (column_order)
                                  └─1:N─ table_constraint (UNIQUE/CHECK) ─1:N─ table_constraint_column (column_order)
```

| 엔티티 (테이블) | 주요 필드 | 비고 |
|---|---|---|
| `SchemaInfo` (`schema_info`) **신규** | projectId, schemaName, schemaDescription, isDefault | 프로젝트당 기본 스키마 1개. `(project_id, schema_name)` 유니크 + 서비스에서 대소문자 무관 중복 검사 |
| `TableInfo` **수정** | + `schema` (`schema_info_id` NOT NULL, FK) | 기존 컬럼·연관 그대로 |
| `TableRelation` (`table_relation`) **신규** | projectId, sourceTable, targetTable, relationName, relationType(`FK`/`LOGICAL`), relationSource(`USER`/`REF_COLUMN`/`IMPORTED`/`AI_APPROVED`), derivedColumnId, isApproval | 관계 1건 = 복합 FK 1건 |
| `TableRelationColumn` **신규** | relation, sourceColumn, targetColumn, columnOrder | `(relation, column_order)` 유니크 → 순서 보존 |
| `TableIndex` / `TableIndexColumn` **신규** | indexName, indexType(저장만), isUnique, isApproval / column, columnOrder | 복합·이름 있는 인덱스 |
| `TableConstraint` / `TableConstraintColumn` **신규** | constraintName, constraintType(`UNIQUE`/`CHECK`), checkExpression, isApproval / column, columnOrder | PK·FK 는 두지 않는다(아래) |

- 모든 신규 id 는 `SEQUENCE`(pooled, 50) — 엑셀 업로드에서 관계를 대량으로 만들 수 있어 기존 대량 처리 패턴과 같게 했다.
- **같은 제약을 두 곳에 저장하지 않는다:** PK 는 컬럼 `isPk`, FK 는 `table_relation`, 단일 컬럼 UNIQUE/INDEX 는 기존 컬럼 플래그를 계속 쓰고, 새 테이블은 이름 있는·복합 키만 담는다. 등록 시 PK·컬럼 플래그와 같은 구성은 거절하고, DDL 은 같은 구성을 한 번만 만든다.
- **DB 무결성:** 테이블 FK 는 `ON DELETE CASCADE`(테이블은 컬럼이 없어야 지워지므로 CHECK 제약만 함께 지워짐), 컬럼 FK 는 RESTRICT — 컬럼 삭제는 서비스가 먼저 정리·차단한다.
- **내부 ID 식별:** 관계·인덱스·제약은 모두 테이블·컬럼 **내부 ID** 로 참조한다(이름이 바뀌어도 유지). 원본 카탈로그 객체는 이름이 아니라 매핑으로 연결한다(6장).

## 3. D-03 스키마 — 결정: **B안 (프로젝트 하위 `schema_info` 엔티티)**

| | A안 `schemaName` 속성 | **B안 별도 엔티티 (채택)** |
|---|---|---|
| 스키마 설명·기본 지정·삭제 관리 | 어려움 | 가능 |
| ERD 스키마 필터(M-2), 역공학 매핑(M-4, AI-1) | 문자열 비교 | 내부 ID 로 매핑 |
| 변경량 | 컬럼 1개 | 테이블 1개 + FK 1개 + 이전 |

- **기본 스키마:** 프로젝트마다 `DEFAULT`(is_default=true). DBMS 마다 기본 스키마 개념이 달라(PostgreSQL `public`, Oracle 접속 계정, MySQL·MariaDB 데이터베이스) 실제 이름을 정하지 않고, **DDL 에서 스키마명을 붙이지 않는 스키마**로 정의했다 → 기존 DDL 출력이 바뀌지 않는다. 이름은 관리자가 바꿀 수 있다(바꿔도 DDL 무접두). 새 프로젝트는 처음 필요할 때 자동 생성한다(`SchemaService.resolveSchema`).
- **다른 스키마의 같은 테이블명:** **허용하지 않는다(현행 유지).** 엑셀 업로드(테이블명으로 소속 테이블 탐색), `refTableName` 해석, 참조 테이블 검색이 모두 프로젝트 단위 테이블명으로 동작하기 때문이다. 이름 기반 경로를 스키마 포함 경로로 바꾸는 작업(엑셀 템플릿 스키마 열 등)과 함께 후속 결정한다.
- **기존 API 호환:** `TableInfoDto.schemaId` 를 **보내지 않으면** 등록은 기본 스키마, 수정은 현재 스키마 유지. 응답에 `schemaId`/`schemaName` 추가(필드 추가만, 기존 필드 변경 없음). 테이블 엑셀 업로드는 기본 스키마에 등록(템플릿 변경 없음).
- **권한:** 스키마 등록·이름 변경·삭제는 승인 권한(`AuthUtil.isApprovalAvailable`: 관리자·매니저·프로젝트 관리자)만. 기본 스키마·테이블이 있는 스키마는 삭제 불가. 다른 프로젝트 스키마 ID 는 `SCHEMA_IS_NOT_DEFINED`.
- **DDL:** 기본 스키마가 아닌 테이블은 `스키마.테이블`(CREATE / INDEX ON / ALTER / REFERENCES / COMMENT ON). 제약·인덱스 이름에는 붙이지 않는다.

## 4. D-04 관계와 `refTableName` — 결정: **`refTableName` 은 단일 FK 약식 입력으로 유지, 확정 가능한 것만 관계(REF_COLUMN)로 자동 반영**

### 4.1 관계 유형·출처 (로드맵 4.2 반영)

| 로드맵 4.2 유형 | 저장 위치 | 이 작업 |
|---|---|---|
| 실제 FK (원본 DB 에서 수집) | **원본 카탈로그 (AI-1·AI-2)** — 내부 모델에 섞지 않는다 | 미구현 (설계만) |
| 모델 관계 | `table_relation`, source `USER`(관계 API) / `REF_COLUMN`(참조 테이블명) / `IMPORTED`(M-4 가져오기) | USER·REF_COLUMN 구현 |
| AI 추정 관계 (미승인) | **내부 모델에 저장하지 않음** — AI 결과 저장소(AI-12) | 미구현 |
| 승인된 관계 | 승인 시 `table_relation` 으로 전환, source `AI_APPROVED` (원본에 FK 가 있다는 뜻 아님) | 값만 정의 |

`relationType` 은 출처와 별개 축이다: `FK`(DDL 에 FOREIGN KEY 생성) / `LOGICAL`(문서·ERD 용, DDL 제약 없음 — 실제 DB 에 FK 를 걸지 않는 운영 관행 표현). 카디널리티는 사용처(M-2 표기, M-6 논리 모델)가 정해질 때 추가한다.

### 4.2 기존 데이터 이전 (V2)

DDL 생성기가 FK 를 만들던 조건과 **같은 조건**일 때만 관계를 만든다.

| 경우 | 처리 |
|---|---|
| ① 참조 테이블이 같은 프로젝트에 정확히 1개 + PK 가 정확히 1개 + 자기 PK 가 아님 | `REF_COLUMN` 관계 + 컬럼 쌍 1개 (`FK_원본테이블_컬럼`) |
| ② 같은 이름의 후보가 여러 개 | 이전하지 않음 |
| ③ 참조 테이블 없음 | 이전하지 않음 |
| ④ 대상에 PK 없음 / ⑤ 복합 PK | 이전하지 않음 (참조 컬럼을 확정할 수 없음 — 임의로 만들지 않는다) |
| ⑥ 소속 테이블 없는 컬럼, 공백 값 | 이전하지 않음 |
| 다른 프로젝트의 같은 이름 테이블 | 같은 프로젝트만 대상 (프로젝트 경계 유지) |

- **정보 손실 없음:** `ref_table_name`·`is_pk`·`is_unique`·`is_index` 등 기존 값은 그대로 둔다. 이전하지 못한 참조는 "참조 테이블명은 있으나 관계가 없는 컬럼"으로 남고, 테이블 물리 모델 조회 API 의 `unresolvedReferences` 와 DDL 경고(기존과 동일 문구)로 보인다. 별도 이전 상태 테이블은 두지 않았다(조건으로 언제든 다시 계산 가능).
- 업그레이드 검증: 위 ①~⑥, 다른 프로젝트 동명 테이블, 삭제된 프로젝트의 테이블, 자기 참조를 넣은 V1 데이터로 PostgreSQL·H2 모두 확인(`PhysicalModelMigrationTest`).

### 4.3 이전 이후의 동작 (동기화)

`refTableName` 은 기존 화면·엑셀 템플릿의 입력이므로 유지하고, 컬럼을 저장할 때마다 `REF_COLUMN` 관계를 다시 맞춘다(`PhysicalModelService.syncReferenceRelations`). 호출 지점: 컬럼 단건 등록, 컬럼 삭제, 컬럼 일괄 저장, 컬럼 엑셀 업로드(청크마다), 테이블명 변경. 대상 테이블 쪽 컬럼이 바뀌어도(PK 추가·삭제) 그 테이블을 참조하는 컬럼까지 다시 계산한다.

- `REF_COLUMN` 관계는 API 로 지울 수 없다(참조 테이블명을 비우면 사라짐). 사용자 정의 관계(USER)의 원본 컬럼에는 참조 테이블명을 쓸 수 없다 → 같은 FK 를 두 곳에서 정의하지 않는다.
- DDL 은 `refTableName` 경로를 **기존 코드 그대로** 쓰고(REF_COLUMN 관계와 같은 조건), USER 관계(FK 유형)만 추가로 만든다 → 기존 DDL 이 바이트 단위로 같다.

### 4.4 삭제·변경 정책

| 상황 | 정책 |
|---|---|
| 참조 테이블 삭제 | 기존대로 컬럼이 없는 테이블만 삭제 가능 → 관계 컬럼이 있을 수 없다. 테이블의 CHECK 제약은 DB CASCADE 로 함께 삭제 |
| 참조(대상) 컬럼 삭제 | USER 관계·인덱스·제약에 쓰이면 **삭제 차단**(`RELATION_EXISTS`). REF_COLUMN 관계만 쓰면 관계를 지우고 삭제 후 재동기화 |
| FK 구성 컬럼 변경 | 관계는 컬럼 ID 로 연결되어 이름·타입 변경에도 유지. 타입 불일치는 DDL 경고(기존 규칙) |
| PK 변경 | REF_COLUMN 은 자동 재계산. USER 관계는 유지하되 대상 컬럼이 대상 PK 전체(또는 고유값 단일 컬럼)가 아니면 DDL 에서 FK 를 만들지 않고 경고 |
| 관계 삭제 | USER 관계만, 미승인일 때만 (`APPROVED_DATA_CANNOT_BE_DELETED`) |
| 프로젝트 간 관계 | 원본·대상 테이블과 모든 컬럼을 로그인 프로젝트 기준 ID 로 다시 조회 → 다른 프로젝트 ID 는 `TABLE_IS_NOT_DEFINED` / 컬럼 오류 |
| 다른 스키마 간 관계 | 같은 프로젝트면 허용, DDL 은 `REFERENCES 스키마.테이블` |
| 수정 | 관계·인덱스·제약 수정 API 는 두지 않았다 (삭제 후 재등록, 승인된 항목은 승인 취소 후) |

### 4.5 관계 동기화 공통 경계 (M-1 후속, 2026-11-11)

**문제 :** 4.3 의 동기화는 각 저장 경로가 `syncReferenceRelations` 를 직접 불러야 했다. 조사 결과 현재 경로에는 빠진 곳이 없었지만, 새 저장 경로(M-4 가져오기, M-8 ERD 편집)가 호출을 빠뜨리면 관계 조회·ERD 와 문자열 기반 DDL 이 조용히 어긋난다.

**저장 경로 조사 (변경 전)**

| 경로 | 관계에 영향 | 직접 호출 | 비고 |
|---|---|---|---|
| 테이블 단건 등록 / 엑셀 업로드 | 없음 (컬럼이 없어 PK 가 없다) | 없음 | 뒤늦게 PK 컬럼이 생길 때 그 컬럼 저장 경로가 맞춘다 |
| 테이블 수정 (이름 변경) | 있음 | 이름이 바뀔 때만 | 참조하던 컬럼의 `refTableName` 을 새 이름으로 바꾼다 (기존 정책 유지) |
| 테이블 수정 (스키마·설명) / 승인 | 없음 (관계는 ID 기준) | 없음 | |
| 테이블 삭제 | 없음 (컬럼이 없을 때만 삭제, 관계는 DB CASCADE) | 없음 | |
| 컬럼 단건 등록 | 있음 | 있음 | 수정은 일괄 저장으로만 한다 |
| 컬럼 삭제 / 일괄 저장 | 있음 | `releaseColumns` + 있음 | |
| 컬럼 엑셀 업로드 | 있음 | 청크마다 | |
| 컬럼 승인 / 순서 변경 | 없음 | 없음 | |
| 관계·인덱스·제약 등록·삭제·승인 | REF_COLUMN 과 무관 (USER 등) | 해당 없음 | 참조 테이블명이 있는 컬럼은 USER 관계의 원본으로 쓸 수 없다 |

발견한 문제 : ① 구조적 누락 위험(위) ② 원본 테이블명·컬럼명이 바뀌어도 REF_COLUMN 관계 이름이 옛 이름으로 남아 DDL 의 FK 이름과 달랐다 ③ 동기화가 "새 이름을 참조하는 컬럼" 만 다시 봐서, 참조 입력을 고치지 않고 대상 테이블명만 바꾸는 경로가 생기면 옛 관계가 남을 수 있었다.

**방식 비교**

| 방식 | 판단 |
|---|---|
| 서비스 공통 저장 메서드에서 동기화 | 저장이 `repository.save`·`saveAll`·변경 감지로 흩어져 있어 공통 메서드로 모으려면 두 서비스를 크게 고쳐야 하고, 새 경로가 그 메서드를 쓰지 않으면 다시 빠진다 |
| 별도 동기화 서비스로 분리 | 호출 책임이 그대로 남는다 (옮기기만 하는 것) |
| 비동기·커밋 후 이벤트 | 저장과 다른 트랜잭션이라 동기화 실패 시 부분 저장이 남는다 |
| **엔티티 콜백으로 표시 + 커밋 직전 같은 트랜잭션에서 동기화 (채택)** | 저장 방식과 무관하게 걸리고, 실패하면 함께 롤백된다. 기존 서비스 코드는 그대로 둔다 |

**구현 (`metamora-data` `physical/sync/`)**

- `ReferenceSyncListener` : `TableInfo`·`ColumnInfo` 의 JPA 콜백(`@PostPersist`·`@PostUpdate`·`@PostRemove`). 바뀐 테이블 ID 를 현재 트랜잭션에 표시만 한다 (조회·쓰기 없음).
- `ReferenceSyncBoundary` : `TransactionExecutionListener` 로 쓰기 트랜잭션이 시작될 때마다 동기화 객체를 등록하고, 커밋 직전(`beforeCommit`)에 `flush` → 표시된 테이블을 500개씩 `syncReferenceRelations` 한다. 트랜잭션 시작 때 등록하는 이유는 엔티티 콜백이 flush 때 불리는데 첫 flush 가 커밋 시점일 수 있기 때문이다.
- `syncReferenceRelations` : 시작할 때 `flush` 하고, 맞춘 테이블을 표시에서 뺀다(같은 테이블을 두 번 맞추지 않는다). 기존 직접 호출은 그대로 두었다 — 엑셀 업로드는 청크마다 영속성 컨텍스트를 비우므로 청크 단위 동기화가 필요하고, 나머지는 커밋 직전에 할 일이 남지 않게 된다.
- 보강 : 다시 볼 컬럼에 "이 테이블을 대상으로 만들어져 있는 REF_COLUMN 관계의 컬럼" 을 더했다(③). 대상이 같아도 관계 이름이 다르면 고친다(②).

**정책 (4.3·4.4 그대로)**

- REF_COLUMN 만 다룬다. USER·IMPORTED·AI_APPROVED 관계는 읽지도 고치지도 않는다.
- 확정할 수 없는 참조(대상 없음·모호·PK 없음·복합 PK·자기 PK)는 관계를 만들지 않고 `refTableName` 을 보존한다. 다른 대상에 연결하지 않는다.
- 참조 대상 테이블명 변경 : 서비스(`updateTable`)는 참조하던 `refTableName` 을 새 이름으로 바꿔 관계를 유지한다(기존 동작). 약식 입력을 바꾸지 않고 대상 이름만 바뀐 경우에는 관계를 지우고 미해결로 둔다.
- 컬럼 삭제 전 `releaseColumns` 호출은 여전히 필요하다 (관계·인덱스·제약이 쓰는 컬럼은 DB FK 가 삭제를 막으므로 빠지면 조용히 어긋나지 않고 실패한다).

**새 저장 경로(M-4·M-8)에서의 사용**

- JPA 로 `TableInfo`·`ColumnInfo` 를 저장·수정·삭제하면 따로 부를 것이 없다.
- 대량 처리에서 `flush`·`clear` 로 청크를 나누면 청크마다 `syncReferenceRelations(projectId, 청크의 테이블 ID)` 를 직접 부른다 (커밋 직전에 한꺼번에 맞추면 메모리·IN 조건이 커진다).
- JPQL·네이티브 일괄 UPDATE/DELETE 는 엔티티 콜백이 없으므로 직접 불러야 한다.

**검증** — `ReferenceSyncIntegrationTest` 6건 (H2·PostgreSQL 18.6 통과). 서비스를 거치지 않고 리포지토리로만 저장·수정해 "동기화를 부르지 않는 경로" 를 재현했다.

| 시나리오 | 확인 |
|---|---|
| 단일 PK 참조, 대상 없음, 대상·PK 의 뒤늦은 생성 | 관계 생성 / 미해결 보존 / 생성 |
| PK : 단일→복합→다른 단일→해제→재설정 | 정리·대상 컬럼 교체·복구, 입력값 보존 |
| 참조 변경·삭제, 자기 PK 참조 | 대상 교체·삭제·관계 없음 |
| 대상 테이블명만 변경 / 복귀, 서비스의 테이블명 변경 | 미해결 전환·복구 / 입력값 갱신과 같은 관계 유지 |
| 원본 테이블명·컬럼명 변경 | 관계 이름 추종, DDL 의 FK 이름과 일치 |
| 동기화 3회 반복, 무관한 컬럼 수정 | REF_COLUMN 1개, USER·IMPORTED·AI_APPROVED 불변 |
| 저장 중 예외, 커밋 직전 동기화 실패 | 컬럼·관계 모두 롤백, 재시도 성공 |
| 같은 컬럼 동시 동기화 8건 | 관계 1개 (나머지는 `UX_TBL_REL_01` 로 롤백) |
| 엑셀 600행 + 부모 PK 가 두 번째 청크 | 앞 청크의 자식까지 관계 생성, 복합 PK 가 된 부모의 관계는 정리 |

각 단계에서 관계 행·`selectTableModel`(관계/미해결 목록)·DDL 의 FK 문·ERD 조회가 같은 결과인지 함께 확인한다. 공통 경계를 끄면 6건 중 5건이 실패하는 것을 확인했다(엑셀은 청크 직접 호출로 통과).

**남은 위험**

- 서로 다른 트랜잭션이 동시에 "참조 컬럼 저장" 과 "대상 PK 저장" 을 하면 서로의 미커밋 데이터를 보지 못해 관계가 빠질 수 있다. 중복은 생기지 않으며, 어느 한쪽 테이블이 다음에 저장될 때 맞춰진다. 프로젝트 단위 잠금으로 막을 수 있으나 저장 전체를 직렬화하므로 넣지 않았다.
- 관계 이름(`FK_테이블_컬럼`)이 255자를 넘으면 동기화가 실패해 저장이 롤백된다 (테스트의 실패 주입으로 사용).
- 모든 쓰기 트랜잭션의 커밋 직전에 `flush` 가 한 번 더 불린다 (변경이 없으면 할 일이 없다). 컬럼 승인·순서 변경처럼 관계와 무관한 수정도 그 테이블을 다시 맞춘다 — 성능 측정은 "엑셀 업로드 관계 동기화 성능"(P2)에서 함께 본다.

## 5. 인덱스·제약조건

- 인덱스: 테이블의 컬럼만(순서대로, 중복 불가), 같은 컬럼 순서의 인덱스 중복 금지, 단일 컬럼이 이미 `isIndex/isUnique/isPk` 면 거절. `indexType` 은 저장만(DBMS 별 문법이 달라 DDL 미반영, M-11 검토).
- UNIQUE: 컬럼 집합 기준 중복 금지(다른 UNIQUE 제약·고유 인덱스 포함), PK 와 같은 구성 거절.
- CHECK: 조건식 필수, `;`·`--` 금지(DDL 은 출력만 하지만 문장 분리 방지). DBMS 지원: PostgreSQL·Oracle·MariaDB(10.2+) 적용, **MySQL 은 8.0.16 미만에서 무시** → DDL 경고.
- 승인: 등록 시 승인 권한이 있으면 승인, 아니면 미승인. 승인·승인 취소는 승인 권한만(`PATCH /api/physical/{kind}/{id}/approval/{true|false}`). 기존 테이블·컬럼 승인 API 는 권한 검사가 주석 처리되어 있으나(별도 개선 과제) 신규 API 는 검사한다.

## 6. AI-1 연계 (설계만, 구현 없음)

- **경계:** 내부 물리 모델(`schema_info`·`table_info`·`column_info`·`table_relation`·`table_index`·`table_constraint`)은 사용자가 저장할 때만 바뀐다. 원본 카탈로그는 별도 테이블(AI 로드맵 U-03 권고)이며 내부 모델을 덮어쓰지 않는다.
- **원본 식별 (AI-1 구현 시 제안):** `db_connection`(프로젝트 하위) → `catalog_schema` → `catalog_table` → `catalog_column` / `catalog_constraint` / `catalog_index`, 각 행은 대리키 + (연결 ID, 정규화 이름 경로, 원문 이름). 비교·매칭은 정규화 이름, 표시·DDL 은 원문(U-24).
- **매핑:** `physical_catalog_mapping(object_type, internal_id, connection_id, catalog_object_id, mapped_by, mapped_at)` — 물리 모델 하나가 연결 여러 개(개발·운영)와 매핑될 수 있다(D-17). 내부 스키마 `schema_info.id` ↔ 연결별 원본 스키마도 같은 표로 매핑하므로, 기본 스키마 이름이 `DEFAULT` 여도 원본 `public` 과 연결할 수 있다.
- **실제 FK:** `catalog_constraint`(type FK)에 저장. M-4 가져오기 때 사용자가 선택하면 `table_relation`(source `IMPORTED`)을 만든다.
- **설명 분리:** 이번 M-1 에는 **마이그레이션을 넣지 않았다.** 근거: 원본 COMMENT 는 원본 카탈로그(`catalog_*`)의 속성이고, AI 제안은 AI 결과 저장소의 속성이라, 내부 모델의 `tableDescription`/`columnDesc` 는 그대로 **업무 설명(사용자 관리)** 으로 정의하면 충돌하지 않는다. 컬럼을 미리 만들면 쓰는 곳 없이 두 군데 설명이 생긴다. AI-1 에서 카탈로그와 함께 추가한다.
- AI-1 마이그레이션 번호는 V3 이후. 이 작업의 V2 는 내부 모델만 바꿨다.

## 7. API (모두 `/api`, 로그인 프로젝트 범위)

| 메서드·경로 | 역할 | 설명 |
|---|---|---|
| `GET /schema` | VIEWER | 스키마 목록 (기본 먼저, 없으면 기본 스키마 생성) |
| `POST /schema` | USER + 승인 권한 | 등록(id 없음)·이름/설명 수정 |
| `DELETE /schema/{schemaId}` | USER + 승인 권한 | 테이블 없는 비기본 스키마 |
| `GET /physical/table/{tableId}` | VIEWER | 스키마, 나가는/들어오는 관계, 인덱스, 제약, `unresolvedReferences` |
| `POST /physical/relation` | USER | `{sourceTableId, targetTableId, relationType?, relationName?, columns:[{sourceColumnId, targetColumnId}]}` |
| `POST /physical/index` | USER | `{tableId, indexName?, indexType?, isUnique, columns:[{columnId}]}` |
| `POST /physical/constraint` | USER | `{tableId, constraintType: UNIQUE/CHECK, constraintName?, checkExpression?, columns?}` |
| `DELETE /physical/{relation\|index\|constraint}/{id}` | USER | 미승인만 |
| `PATCH /physical/{kind}/{id}/approval/{bool}` | USER + 승인 권한 | 승인·취소 |
| `PUT /saveTable` (기존) | USER | `schemaId` 선택 필드 추가 (테이블 스키마 이동) |

화면(UI)은 이번 범위에서 만들지 않았다 — 테이블 상세의 스키마 선택·관계 편집 UI 는 M-2/M-3 에서 ERD 와 함께 붙인다.
