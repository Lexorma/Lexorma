# M-14 1차 — 인덱스 관리 구현 기록

> 상태 : **M-14 1차·안정화·2차 구현 완료, 브라우저 E2E 조건부 통과** (11장 — Critical/High 결함 없음, Medium 1·Low 6 개선 사항과 BLOCKED 1건. 완료 처리는 사용자 결정 대기). 1차 당시의 2차 범위(표현식·부분 인덱스·정렬·INCLUDE)는 10장에서 구현했다 — [로드맵](data-modeling-roadmap.md#m-14-인덱스-관리-기존-인덱스-기능의-관리-화면-확장).
> 마이그레이션 : `V3__index_management.sql`(1차), `V4__index_advanced.sql`(2차) — 2026-11-13 V0·V1 로 합쳤다 ([기록](schema-scoped-naming.md#6-v0v1-통합), `IX_TBL_01` → `UX_TBL_01`) — PostgreSQL·H2 같은 내용, 이미 적용된 파일은 고치지 않았다.

## 1. 구현 전 구조 (2026-11-13 코드 확인)

| 항목 | 확인 결과 |
|---|---|
| 저장 | `TB_TBL_IDX`(이름·유형 문자열·고유·승인) + `TB_TBL_IDX_COL`(컬럼·순서) |
| `IDX_YN` | DDL 생성기가 **직접** `CREATE INDEX IX_<테이블>_<컬럼>` 을 냈다. 조건 : `IDX_YN` 이고 PK·고유값이 아닌 컬럼. `TB_TBL_IDX` 행이 아니라 목록·ERD·이력에 인덱스로 보이지 않았다 |
| 컬럼 고유값(`UNQ_YN`) | **UNIQUE 제약** `CONSTRAINT UK_<테이블>_<컬럼> UNIQUE (…)` (인덱스가 아니다). 이번 작업에서 바꾸지 않았다 |
| 중복 | 같은 컬럼 하나의 일반 인덱스가 두 경로에 있으면 DDL 은 컬럼 쪽(`IX_…`) 하나만 냈다. 등록 API 는 `IDX_YN` 컬럼 하나짜리 일반 인덱스를 거절했지만, 등록 뒤에 `IDX_YN` 을 켜는 경로는 막지 않았다 |
| 승인 | 승인 권한자가 등록하면 바로 승인, 승인된 인덱스 삭제 불가, 수정 API 없음 |
| 이력 | `ModelChangeRecorder` 가 `PHYSICAL_INDEX` 로 추적 (변경 이력은 기록일 뿐, 승인 요청 구조는 없었다) |
| 유형 | `IDX_TYPE_NM` 은 저장만 하고 DDL 에 쓰지 않았다 |

## 2. D-19 — 저장 기준 통합

- **`TB_TBL_IDX` 가 인덱스의 유일한 저장소다.** 목록·ERD·테이블 물리 모델 조회·DDL·이력·모델 버전이 모두 이 행을 본다. DDL 생성기는 컬럼의 `IDX_YN` 을 더 이상 읽지 않는다.
- **컬럼의 `IDX_YN` 은 단일 컬럼 인덱스의 약식 입력으로 남는다** (컬럼 관리·일괄 편집·엑셀·공통 컬럼 API 계약은 그대로). 참조 테이블명 → `REF_COLUMN` 관계(D-04)와 같은 방식으로, 출처 `COLUMN` 인덱스(`IDX_SRC_CD = 'COLUMN'`, `DRVN_COL_SN` = 컬럼, 컬럼당 1개 — `UX_TBL_IDX_01`)로 **자동 동기화**한다.
  - 동기화 : `PhysicalModelService.syncReferenceRelations` 안의 `syncColumnIndexes`. 테이블·컬럼이 JPA 로 바뀐 모든 트랜잭션의 커밋 직전(`ReferenceSyncBoundary`)과 엑셀 청크에서 직접 부르는 시점에 돈다.
  - 규칙(예전 DDL 과 같다) : `IDX_YN` 이고 PK·고유값이 아니며, 같은 컬럼 하나의 **사용자 일반 인덱스가 없을 때** 만든다. 이름 `IX_<테이블명>_<컬럼명>`(테이블·컬럼 이름이 바뀌면 따라간다), 승인 상태 = 컬럼의 승인 상태. 조건이 풀리면(해제, PK·고유값이 됨, 컬럼 삭제) 지운다.
  - `COLUMN` 인덱스는 인덱스 API 로 수정·삭제·승인할 수 없다 (`INVALID_PHYSICAL_MODEL`, 화면도 버튼을 숨김). 같은 구성의 사용자 인덱스 등록은 기존 "같은 컬럼 구성" 규칙으로 거절된다 → **같은 일반 인덱스가 두 경로에 생기지 않는다.** 단, 같은 컬럼의 사용자 UNIQUE 인덱스와 `COLUMN` 일반 인덱스의 공존은 등록 순서에 따라 다르다 (9장, 정책 미확정).
  - 복합 인덱스에 들어 있는 컬럼은 "인덱스 있음" 으로 보지 않는다 (단일 컬럼 인덱스만 `IDX_YN` 과 짝이다).
  - 컬럼 삭제(`releaseColumns`) : `COLUMN` 인덱스는 함께 지우고, 사용자 인덱스에 쓰인 컬럼은 예전처럼 거절한다.
  - 변경 이력 : 동기화가 만든 인덱스 변경은 `AUTO_SYNC` 출처로 남는다. 컬럼의 `isIndex` 변경은 컬럼 이력에도 그대로 남는다.
- **기존 데이터 이관 (V3, 모든 프로젝트)** : 위 규칙과 같은 조건으로 `TB_TBL_IDX`/`TB_TBL_IDX_COL` 에 `COLUMN` 인덱스를 넣는다. 승인 상태는 컬럼의 값, 등록자 `SYSTEM`. 컬럼의 `IDX_YN` 값과 기존 인덱스·승인 상태·이력 행은 바꾸지 않는다.
  - DDL 의미 : 같은 이름·같은 순서(컬럼 인덱스를 컬럼 정렬 순서대로 먼저)로 나온다 — 예전 DDL 골든 파일(`ddl-golden/*.sql`) 이 그대로 통과한다.
  - 판단을 보류한 경우는 없었다 : 인덱스와 제약조건의 의미가 섞인 데이터가 없다(`UNQ_YN` 은 제약, `IDX_YN` 은 인덱스로만 쓰였다).
  - 알려진 차이 : ① 같은 컬럼 하나의 사용자 일반 인덱스가 이미 있던 컬럼은 `COLUMN` 인덱스를 만들지 않으므로, DDL 의 인덱스 이름이 `IX_…` 에서 그 사용자 인덱스 이름으로 바뀐다 (인덱스는 여전히 하나). ② 예전에도 사용자 인덱스 이름이 `IX_<테이블>_<컬럼>` 과 같으면 같은 이름이 두 번 나왔다 — 이관은 그대로 옮긴다 (이름 충돌 확인 SQL 은 아래).
  - 이관 뒤 모델 변경 이력에는 이관 행이 없다(네이티브 SQL). V3 이전에 만든 모델 버전과 현재를 비교하면 `COLUMN` 인덱스가 추가로 보이고, 인덱스 state 에 필드가 늘어 `FORMAT_VERSION` 을 2 로 올렸다.

```sql
-- 이관 뒤 같은 테이블에 같은 이름의 인덱스가 있는지 (있으면 이름을 정리할 것)
select TBL_SN, upper(IDX_NM), count(*) from TB_TBL_IDX group by TBL_SN, upper(IDX_NM) having count(*) > 1;
```

## 3. D-20 — 수정과 승인

| 상태 | 동작 |
|---|---|
| 미승인 | `PUT /api/physical/index/{id}` 로 바로 수정 (내부 ID 유지, 이력 UPDATE 1건) |
| 승인 | 같은 API 가 **변경 요청**(`TB_IDX_CHG_DMND`, 변경 전·후 정의 JSON, `REQUESTED`)을 만들고 승인된 정의는 그대로 둔다. 승인 권한자의 요청은 즉시 `APPROVED` 로 반영 (등록 시 즉시 승인과 같은 규칙) |
| 변경 승인 대기 | 새 요청·승인 취소 거절 (승인 대기 요청이 있습니다). 삭제는 승인된 인덱스라 원래 불가 |
| 승인 / 반려 | `PATCH /api/physical/index/request/{requestId}/{true|false}` (USER + `AuthUtil.isApprovalAvailable()`). 승인하면 요청 정의를 **등록과 같은 검증**으로 다시 확인한 뒤 반영(승인 상태 유지), 반려하면 정의 유지. 요청 행은 남는다 |

- 처리자·처리 일시는 요청 행의 수정자·수정 일시, 요청자·요청 일시는 등록자·등록 일시다.
- 승인 처리 시 요청의 변경 전 정의와 현재 정의(이름·유형·고유·컬럼 ID 순서)가 다르면 거절한다.
- 동시성 : 인덱스 등록·수정·삭제·승인·요청 처리는 **테이블 행 잠금**(`TableInfoRepository.findForUpdate`, M-7 과 같은 잠금) 후 인덱스를 다시 읽는다. 요청 처리도 잠금 뒤 요청 상태를 다시 읽어 중복 처리를 막는다. 프로젝트 권한 회수는 기존 `ProjectAccessInterceptor`, 프로젝트 격리는 모든 조회의 `projectId` 조건.
- 인덱스가 지워지면(미승인 인덱스 삭제, 테이블 삭제의 FK CASCADE) 요청 이력도 함께 지워진다. 프로젝트 영구 삭제는 `ProjectDataCleanup` 이 지운다.
- 승인 상태를 초기화하거나 삭제 후 재등록으로 승인을 우회하지 않는다.

## 4. D-21 — 유형의 DDL 반영 (1차)

| DBMS | 지원 유형 | DDL |
|---|---|---|
| PostgreSQL | BTREE HASH GIN GIST SPGIST BRIN | `CREATE [UNIQUE] INDEX n ON t USING gin (…)` (고유는 BTREE 만) |
| Oracle | BTREE BITMAP | `CREATE BITMAP INDEX …` (BITMAP 은 고유 불가) |
| MySQL·MariaDB | BTREE HASH FULLTEXT SPATIAL | `CREATE FULLTEXT INDEX …` / `… (…) USING HASH` (FULLTEXT·SPATIAL 은 고유 불가) |

- 저장값은 대문자, `-`·`_`·공백 제거 (`B-Tree` → `BTREE`). 비어 있으면 기존처럼 DBMS 기본(유형 표기 없음).
- 등록·수정·요청 승인 때 프로젝트 DBMS 에서 만들 수 없는 유형·고유 조합은 **거절**한다 (기본 유형으로 바꾸지 않음).
- 이미 저장된 값이 대상 DBMS 에서 안 되면(프로젝트 DBMS 를 바꾼 경우 등) DDL 에 **주석으로만** 내고 경고한다.
- 기존에 `btree` 처럼 저장한 인덱스는 이제 `USING btree` 로 나온다 (의미는 같다).

## 5. API (`/api/physical`, 기존 등록·삭제·승인은 그대로)

| API | 역할 | 비고 |
|---|---|---|
| `GET /index/data` | VIEWER | Grid. `schemaId` `tableId` `isApproval`(Y/N) `indexSource`(USER/COLUMN), 검색 `indexName`·`tableName`·`columnNames`. 테이블·스키마·컬럼은 일괄 조회(batch fetch), 승인 대기 요청은 페이지당 1 쿼리 |
| `GET /index/{id}` | VIEWER | 정의·소속·변경 요청 이력 |
| `GET /index/{id}/ddl` | VIEWER | `CREATE INDEX` 하나 (실행 없음) |
| `GET /index/types` | VIEWER | 프로젝트 DBMS 의 지원 유형 |
| `PUT /index/{id}` | USER | 수정 / 변경 요청 |
| `PATCH /index/request/{requestId}/{approve}` | USER + 승인 권한 | 승인·반려 |

## 6. 화면

- 메뉴 `물리 모델 관리 > 인덱스 관리` (`/indexManage`, 메뉴 id `index-manage`, VIEWER). 등록·수정·삭제 버튼은 `physical-model-edit`, 승인·반려는 `approvalAvailable`.
- 목록 : 스키마·승인 상태·출처 필터, 테이블명·인덱스명·컬럼 검색, 승인 대기 요청 표시. `?tableId=` 로 테이블 조건.
- 등록·수정 : 스키마 → 테이블 검색·선택, 컬럼 추가·↑↓ 순서·빼기, 이름(비우면 자동), DBMS 별 유형, UNIQUE. 수정할 때 테이블은 바꿀 수 없다.
- 상세 : 정의, DDL 보기(복사·스키마명 포함), ERD 에서 보기, 테이블 상세, 수정/변경 요청, 삭제, 승인·승인취소, 변경 요청 이력(승인·반려).
- 물리 ERD [인덱스·제약조건] : 같은 데이터, 출처 `컬럼 속성` 표시(관리 버튼 대신 "컬럼 편집에서 변경"), [인덱스 관리에서 보기].

## 7. 검증

| 항목 | 결과 |
|---|---|
| H2 : 전체 Gradle 테스트 (`lexorma-data`·`lexorma-core`·`lexorma-app`) | 통과 |
| 신규 `IndexManagementIntegrationTest` (동기화·수정·변경 요청·유형·목록·격리) | H2·PostgreSQL 18 통과 |
| 신규 `IndexMigrationV3Test` (V2 → V3 이관) | H2·PostgreSQL 18 통과 |
| `V0SeedMigrationTest` (스키마·COMMENT·예제 비교, 마이그레이션 4개) | H2·PostgreSQL 18 통과 |
| `PhysicalModelIntegrationTest`·`ProjectDeletionIntegrationTest`·`ModelVersionIntegrationTest`·`ModelChangeHistoryIntegrationTest` | H2·PostgreSQL 18 통과 |
| DDL 골든(예전 출력) 회귀 | 통과 (바이트 동일) |
| React `typecheck`·`build` | 통과 |
| 브라우저 (h2-local, 관리자, 한국어·라이트, 데스크톱 폭) | 메뉴, 등록(복합·순서 변경·GIN), 목록, 상세, DDL `USING gin`, 인덱스 관리 → ERD → 인덱스 관리(테이블 조건) 왕복, 콘솔 오류 없음 |
| 브라우저 미검증 | 승인 권한 없는 USER 의 변경 요청·VIEWER 화면·다크 모드·좁은 화면·영어 (API·권한은 통합 테스트로만 확인) |

PostgreSQL 검증은 로컬 컨테이너에 임시 데이터베이스를 만들어 실행하고 지웠다 (개발 DB `lexorma` 에는 V3 를 적용하지 않았다).

## 8. 2차 잔여 (M-14 2차)

- 표현식 인덱스, 부분 인덱스 WHERE, ASC/DESC·NULLS, INCLUDE 컬럼 — 모델(새 마이그레이션)·DDL·이력 필드 목록·`FORMAT_VERSION`
- 고급 속성의 DBMS 별 변환·지원 불가 표시
- AI-2b 원본 인덱스(`TB_SRC_IDX*`)와 물리 모델의 전체 호환 (가져오기는 M-4, 차이 비교는 M-10 ②)
- 결정 필요 : 변경 요청 반려 사유 입력, 승인 대기 요청 목록 화면(현재는 목록의 "변경 요청" 표시와 상세에서 처리), `IDX_YN` 을 장기적으로 없앨지(현재는 약식 입력으로 유지)

## 9. 안정화 (2026-11-13)

### 9.1 `BaseEntity` 감사 접근자

| 항목 | 결과 |
|---|---|
| `BaseEntity` 상속 엔티티 | 32개 (core 4·data 22·operation 3·source 4, `TableIndex`·`IndexChangeRequest` 포함) |
| 1차에서 새로 공개된 getter | `getCreated()` `getUpdated()` `getCreatedBy()` `getUpdatedBy()` (32개 엔티티 전부) |
| getter 사용처 | M-14 코드(인덱스 목록·상세·변경 요청 DTO)뿐. `ModelVersion` 의 `getCreatedBy` 는 자기 필드(`BaseEntity` 아님) |
| 엔티티를 직접 반환하는 API | **없음.** 모든 `@RestController` 의 반환 타입을 중첩 DTO·레코드·제네릭·배열까지 따라가도 엔티티가 없다(`EntityJsonExposureTest`). `Map<String, Object>` 응답은 값이 DTO·기본 타입이다(코드 확인) |
| 다른 JSON 경로 | 변경 이력·모델 버전(필드 목록 Map), ERD 배치·로그 마스킹·원본 카탈로그(DTO·Map) — 엔티티를 직렬화하지 않는다. Jackson 전역 설정 없음. 세션에는 `AccountDto` 만 들어간다 |
| 실제 JSON 응답 변경 | 없었다 (엔티티가 응답에 들어가지 않으므로) |
| 감사 컬럼 내용 | 일시와 사용자 아이디(로그인 ID). 비밀번호 등은 없지만 공개 API 를 넓힐 이유가 없다 |

**수정** : 전역 `@Getter` 는 필요하지 않아 `@Getter(AccessLevel.PROTECTED)` 로 줄였다. 화면에 등록·수정 정보가 필요한 두 엔티티(`TableIndex`, `IndexChangeRequest`)만 `audit()` (bean getter 가 아니라 JSON 프로퍼티가 되지 않는다) 로 `BaseEntity.Audit` 레코드를 꺼내 DTO 로 옮긴다. `@JsonIgnore`·Jackson 전역 설정은 쓰지 않았다. 모든 모듈이 그대로 컴파일된다(다른 사용처 없음).

### 9.2 `IDX_YN` 중복 판정

판정 로직 (코드 기준) :
- 등록·수정·변경 요청 승인(`indexSpec`) : ① 단일 컬럼이 PK·고유값(`UNQ_YN`)이면 거절, 일반 인덱스면 `IDX_YN` 컬럼도 거절 ② PK 전체와 같은 UNIQUE 거절 ③ 같은 테이블의 다른 인덱스(출처·고유 여부·유형·승인 상태 무관)와 **컬럼 ID 순서가 같으면** 거절(`SAVED_DATA_EXISTS`) ④ 이름 중복(대소문자 무시) 거절.
- 동기화(`syncColumnIndexes`) : `IDX_YN` 이고 PK·고유값이 아니고, **같은 컬럼 하나의 사용자 일반 인덱스**가 없으면 `COLUMN` 인덱스. 사용자 UNIQUE 인덱스는 대신하지 않는다 (V3 이관과 같은 규칙).
- DDL : 일반 인덱스는 같은 컬럼 순서, UNIQUE 인덱스는 PK·고유값·UNIQUE 제약과 같은 컬럼 집합이면 한 번만 낸다.

| 시나리오 | 결과 (저장 / 출처·고유·승인 / DDL / 응답) |
|---|---|
| A `IDX_YN`, 사용자 인덱스 없음 | `COLUMN` 일반 1개, 승인 = 컬럼(미승인), `CREATE INDEX IX_…` 1번 |
| B `IDX_YN` + 같은 컬럼 사용자 일반 | 거절 `INVALID_PHYSICAL_MODEL`, 1개 유지 |
| C `IDX_YN` + 같은 컬럼 사용자 UNIQUE | 거절 `SAVED_DATA_EXISTS`, 1개 유지 |
| C 역순 : 사용자 UNIQUE 먼저 → `IDX_YN` 켬 | **둘 다 저장** (`USER` UNIQUE + `COLUMN` 일반), DDL 두 문장 — M-14 이전 DDL·V3 이관과 같은 결과 |
| D `IDX_YN` 아님 + UNIQUE | 허용, `CREATE UNIQUE INDEX` |
| E `UNQ_YN` 컬럼 + UNIQUE / 일반 | 둘 다 거절 `INVALID_PHYSICAL_MODEL`, DDL 은 UNIQUE 제약 하나 |
| F PK 단일 컬럼 / PK 전체 UNIQUE | 거절. PK 전체와 같은 **일반** 복합 인덱스는 허용 (M-1 부터의 동작, DDL 에 그대로 나온다) |
| G 단일 `COLUMN` + 복합 사용자 | 공존 (복합 인덱스는 `IDX_YN` 을 대신하지 않는다) |
| H 같은 구성·다른 유형 | 거절 `SAVED_DATA_EXISTS` |
| I `IDX_YN` 을 대신하던 사용자 일반 인덱스 삭제 | **수정 전 : 아무 일도 없어 DDL 에서 인덱스가 사라졌다 (버그).** 수정 후 : 같은 트랜잭션에서 `COLUMN` 인덱스 생성, 이력 `CREATE`·`AUTO_SYNC` |
| J 그 인덱스의 변경 요청 | 승인 대기·반려 : 그대로. 승인 반영·미승인 직접 수정으로 단일 일반 인덱스가 아니게 되면 `COLUMN` 인덱스 생성 (수정 전에는 생기지 않았다) |
| K 테이블명·컬럼명 변경 | 같은 ID, 이름 `IX_<새 테이블>_<새 컬럼>` |
| L V3 전후 DDL | `IndexMigrationV3Test`(H2·PostgreSQL) + DDL 골든 파일 동일 |

**버그 수정 (I·J)** : 동기화는 테이블·컬럼이 바뀔 때만 표시되는데 사용자 인덱스 삭제·수정은 표시를 남기지 않았다. 사용자 인덱스를 삭제·직접 수정·변경 요청 승인 반영한 뒤 그 테이블만 다시 맞춘다(`resyncColumnIndexes`). 데이터 구조·마이그레이션 변경 없음.

### 9.3 일반 vs UNIQUE — (2차에서 방안 C 로 확정·구현, 10.2)

현재(방안 A 와 B 가 섞인 상태) : `IDX_YN` 이 먼저면 UNIQUE 등록을 거절하고, UNIQUE 가 먼저면 `IDX_YN` 의 일반 인덱스도 만든다. 결과가 순서에 달려 있다.

| 방안 | 내용 | 영향 |
|---|---|---|
| A 현재 유지 | 그대로 | 순서 의존성 남음 |
| B 공존 허용 | `IDX_YN` 컬럼에도 UNIQUE 등록 허용 (composition 검사에서 고유 여부 구분) | 같은 컬럼 일반 + UNIQUE 두 인덱스(물리적으로 중복). 기존 데이터 변경 없음 |
| **C UNIQUE 우선 (권장)** | 같은 컬럼 하나의 사용자 UNIQUE 인덱스도 `IDX_YN` 을 대신한다 (동기화 `covered` 에 포함) + `IDX_YN` 컬럼에 UNIQUE 등록 허용 | UNIQUE 를 지우면 `COLUMN` 인덱스가 돌아온다(I 와 같은 경로). **V3 가 만든 "UNIQUE + COLUMN 일반" 공존 데이터는 다음 동기화 때 `COLUMN` 쪽이 삭제되어 DDL 에서 일반 인덱스가 빠진다**(승인된 `COLUMN` 인덱스 포함) — 기존 데이터·DDL 이 바뀌므로 결정 전에는 구현하지 않는다 |

C 를 택하면 정리 시점(다음 동기화에 맡길지, 새 마이그레이션으로 한 번에 할지)과 승인된 `COLUMN` 인덱스 삭제를 허용할지도 함께 정한다.

### 9.4 데이터 정합성

- `V3__index_management.sql` 은 고치지 않았고 새 마이그레이션도 없다. 인덱스 ID·승인 상태·변경 요청·이력·`IDX_YN`/`UNQ_YN` 값을 바꾸는 코드 변경 없음 (I·J 수정은 앞으로의 동기화에서 빠진 `COLUMN` 인덱스를 만드는 것뿐).
- 이미 I·J 상황이 된 데이터(사용자 인덱스를 지운 뒤 `COLUMN` 인덱스가 없는 컬럼)는 그 테이블·컬럼이 다음에 바뀔 때 자동으로 복구된다. 바로 맞추려면 그 테이블을 저장하면 된다.
- DDL 골든 파일 동일.

### 9.5 테스트

| 항목 | 결과 |
|---|---|
| `./gradlew build` | 통과 — 513건 (기존 504 + 신규 9), 실패 0, 스킵 15 (변화 없음 : PostgreSQL·성능·원본 DB 환경 변수 테스트) |
| 신규 | `IndexDuplicatePolicyIntegrationTest` 7건 (A~K), `EntityJsonExposureTest` 2건 |
| PostgreSQL 18 (임시 DB, 끝나고 삭제) | `IndexDuplicatePolicy`·`IndexManagement`·`IndexMigrationV3`·`V0SeedMigration`·`PhysicalModel`·`ProjectDeletion`·`ModelChangeHistory`·`ModelVersion` 36건 통과 |
| 기존 API 응답 | `SmokeTest`(API·그리드 정렬·검색, OpenAPI 문서) 통과 |
| 엑셀 업로드의 `IDX_YN` | 업로드는 청크마다 `syncReferenceRelations` 를 직접 부르므로 같은 동기화를 탄다 (코드 확인). 전용 테스트는 없다 |
| 브라우저 | **이번 안정화에서는 하지 않았다** (화면 코드 변경 없음). 승인 권한 없는 USER 화면·VIEWER·다크·좁은 화면·영어는 여전히 미검증 |

### 9.6 남은 위험

- 9.3 정책 미확정 (순서 의존).
- PK 전체와 같은 일반 복합 인덱스 허용(F) — M-1 부터의 동작, 필요하면 같은 결정에서 함께 다룬다.
- 엑셀 업로드 `IDX_YN` 동기화는 전용 테스트가 없다.

## 10. M-14 2차 (2026-11-13)

### 10.1 구현 전 구조 확인

| 항목 | 확인 결과 |
|---|---|
| `COLUMN` 인덱스 승인 상태 | 컬럼의 승인 상태를 그대로 따른다 (동기화가 맞춘다) |
| 사용자 UNIQUE 등록·승인 | 승인 권한자 등록 = 즉시 승인, 아니면 미승인 → `PATCH …/approval/true`. 승인된 인덱스 수정은 `TB_IDX_CHG_DMND` (인덱스 1개 단위) |
| 복수 인덱스 원자 변경 | 변경 요청은 인덱스 하나만 담는다 → **재사용하지 않았다.** 대신 UNIQUE 의 기존 승인 처리(같은 트랜잭션·같은 변경 그룹·처리자 기록)에서 대신할 컬럼 인덱스를 지운다 (새 승인 구조 없음) |
| 동기화 시점 | 테이블·컬럼이 바뀐 모든 쓰기 트랜잭션의 커밋 직전 + 사용자 인덱스 등록(승인)·수정·삭제·승인·승인 취소·변경 요청 승인 뒤 그 테이블 (안정화 9.2 의 `resyncColumnIndexes`) |
| 모델 버전 | 인덱스 state = 필드 목록 + 구성 요소(`columns`). 비교가 "null 필드"와 "없는 필드"를 다르게 봐서, 새 필드를 더하면 예전 버전과 비교할 때 모든 인덱스가 MODIFIED 로 보일 상황이었다 → 10.5 |
| AI-2b | `TB_SRC_IDX.CND_CN`, `TB_SRC_IDX_COL.EXPR_CN`(컬럼 없음 허용)·`SORT_CD`(null / DESC / DESC NULLS LAST / NULLS FIRST / INCLUDE), 키와 INCLUDE 가 한 순서 |

### 10.2 방안 C (UNIQUE 우선) — 최종 정책

- **"대신한다" = 같은 컬럼 하나를 키로 하는(조건 없음) 승인된 사용자 UNIQUE 인덱스.** 그런 UNIQUE 가 있으면 `IDX_YN` 컬럼 인덱스를 새로 만들지 않는다.
- **이미 있는 컬럼 인덱스는 그 UNIQUE 의 승인 처리에서만 지운다** : 승인 권한자의 등록, 승인(`approval/true`), 변경 요청 승인 반영(또는 승인 권한자의 수정)으로 그 UNIQUE 가 승인 상태의 단일 컬럼 UNIQUE 가 될 때, 같은 트랜잭션에서 그 컬럼의 컬럼 인덱스만 지운다. 이력은 같은 변경 그룹에 UNIQUE 의 APPROVE/CREATE/UPDATE 와 컬럼 인덱스 DELETE(`AUTO_SYNC`, 처리자 = 승인한 사람).
- **동기화만으로는 지우지 않는다** : 테이블·컬럼 저장, 엑셀, 반복 동기화는 "새로 만들지 않음" 만 적용한다 → V3 이전부터 공존하던(승인된 UNIQUE + 컬럼 인덱스) 데이터와 DDL 이 그대로다.
- 승인 대기 UNIQUE 는 아직 확정 정의가 아니므로 대신하지 않는다 (컬럼 인덱스·DDL 유지, DDL 의 승인 대기 목록에 UNIQUE 표시).
- 복원 : UNIQUE 승인 취소, 복합·표현식·부분 인덱스로 변경(승인 반영), 삭제(승인 취소 뒤) → 동기화가 컬럼 인덱스를 다시 만든다.
- 등록 검증 : 컬럼 인덱스와 같은 구성의 **UNIQUE** 는 받는다 (일반 인덱스는 예전처럼 거절).
- 순서 독립 : 경로 A(UNIQUE → `IDX_YN`)와 경로 B(`IDX_YN` → UNIQUE 요청 → 승인)의 결과가 같다 (테스트에서 두 테이블의 인덱스·DDL 을 비교).
- 공존 현황 식별 : 인덱스 상세 `replacedColumnIndexes` — 미승인 UNIQUE 면 "승인하면 대신할 인덱스", 승인된 UNIQUE 인데 남아 있으면 "예전부터 공존하는 정리 대상" 으로 화면에 표시한다. 전체 목록이 필요하면 :

```sql
select u.TBL_SN, u.IDX_NM as unique_index, c.IDX_NM as column_index, c.APRV_YN
from TB_TBL_IDX u join TB_TBL_IDX_COL uc on uc.IDX_SN = u.IDX_SN
join TB_TBL_IDX c on c.TBL_SN = u.TBL_SN and c.IDX_SRC_CD = 'COLUMN' and c.DRVN_COL_SN = uc.COL_SN
where u.IDX_SRC_CD = 'USER' and u.UNQ_YN = true and u.APRV_YN = true and u.CND_CN is null
  and (select count(*) from TB_TBL_IDX_COL k where k.IDX_SN = u.IDX_SN and (k.SORT_CD is null or k.SORT_CD <> 'INCLUDE')) = 1;
```

정리는 자동으로 하지 않는다 (필요하면 그 UNIQUE 를 승인 취소 → 다시 승인하면 승인 처리로 대신한다).

### 10.3 고급 인덱스 모델 (V4)

| 구분 | 변경 |
|---|---|
| DB | `TB_TBL_IDX.CND_CN`(조건), `TB_TBL_IDX_COL.EXPR_CN`(표현식)·`SORT_CD`(정렬), `TB_TBL_IDX_COL.COL_SN` null 허용. 용어는 V2 의 `조건_내용`·`표현식_내용`·`정렬_코드` 재사용(새 단어·용어 없음). 예제·COMMENT 포함. 기존 행은 새 컬럼이 null = 지금과 같은 뜻 → 데이터 변환 없음 (`IndexMigrationV3Test` 가 V4 전후 인덱스·ID·승인 상태 동일 확인) |
| 표기 | AI-2b 와 같다 : 키 = 컬럼 또는 표현식, `SORT_CD` null(ASC·NULLS 기본) / `DESC` / `DESC NULLS LAST` / `NULLS FIRST`, INCLUDE 컬럼 = `SORT_CD = INCLUDE`, 한 `COL_ORD` 순서(키 먼저) |
| 엔티티 | `TableIndex.condition`, `keyMembers()`·`includeMembers()`·`isPlain()` / `TableIndexColumn.expression`·`sort` |
| API (`/api/physical/index/**` 그대로) | 요청·응답에 `members`([{columnId 또는 expression, direction ASC/DESC, nulls FIRST/LAST}]), `includeColumns`, `condition`. `members` 가 없으면 예전 `columns` 를 오름차순 키로 쓴다. 응답의 `columns` 는 키 중 컬럼만(예전 화면 호환). 목록 행에 `condition`, `columnNames` 는 표현식·정렬·INCLUDE 포함 표시 |
| 변경 요청 | 전후 정의(JSON)에 `members`·`includeColumns`·`condition`. 1차에 저장한 요청(없음)은 `columns` 를 오름차순 키로 읽는다. 승인 반영은 전체 정의로 다시 검증 |
| 이력 | 인덱스 필드 `condition`, 구성 요소 `expression`·`sort` (`ModelSnapshotFieldCoverageTest`) |
| 모델 버전 | `FORMAT_VERSION` 3. 비교는 값이 null 인 필드와 없는 필드를 같게 본다 → 예전 버전(1·2)과 비교해도 고급 속성이 없는 인덱스는 달라 보이지 않는다 (1차의 `indexSource`·`derivedColumnId` 는 값이 있어 v1 과는 여전히 다르게 보인다) |

### 10.4 검증 규칙과 DDL

- 키 : 컬럼과 표현식 중 하나만, 키 컬럼 중복·같은 표현식 중복 거절, 다른 테이블 컬럼 거절. INCLUDE : 중복·키와 겹침 거절. 정렬 값은 ASC/DESC, NULLS FIRST/LAST 만.
- 표현식·조건 SQL 조각 : 실행하지 않는다. 1000자 이하, `;` `--` `/*` `*/` 금지, 괄호·작은따옴표 짝 검사 — **문법·함수·연산자 클래스의 유효성은 보장하지 않는다** (DDL 을 실행할 때 DB 가 판단).
- 같은 인덱스 판정 : 키(컬럼 ID 또는 표현식 + 정렬) 순서·INCLUDE·조건이 같으면 중복 (이름·유형·고유 여부는 보지 않음 = 1차와 같은 기준의 확장). 조건만 다르면 다른 인덱스.
- PostgreSQL 조합 (PostgreSQL 18 에서 실행으로 확인) : 정렬은 BTREE 만, INCLUDE 는 BTREE·GIST·SPGIST 만, HASH·SPGIST 는 키 하나만, 고유는 BTREE 만. 표현식·WHERE 는 모든 유형.
- DDL (PostgreSQL) : `CREATE [UNIQUE] INDEX 이름 ON 테이블 [USING 방식] (키, …) [INCLUDE (…)] [WHERE 조건];` — 표현식은 항상 괄호로 감싼다(`((lower(EMAIL)))`, 어떤 식이든 유효). 키·INCLUDE 순서 보존. 미리보기·테이블 DDL 이 같은 생성기.
- 중복 출력 : 예전 형태(오름차순 컬럼 키만, 조건·INCLUDE 없음) 인덱스는 1차 규칙 그대로, 고급 인덱스는 같은 정의일 때만 한 번. 부분·표현식 UNIQUE 는 UNIQUE 제약과 같은 것으로 보지 않고 FK 참조 키로도 쓰지 않는다.
- 다른 DBMS(Oracle·MySQL·MariaDB) : 일반 인덱스 생성은 그대로. 표현식·정렬·INCLUDE·WHERE 는 저장 거절, 이미 있는 값(프로젝트 DBMS 변경)은 DDL 에 주석 + 경고 — 기본값으로 바꾸지 않는다. (Oracle 함수 기반 인덱스·DESC, MySQL 함수형 키 등은 이번 범위가 아니다)
- 기존 DDL : 골든 파일 동일, 예전 인덱스는 새 속성이 없어 출력이 같다.

### 10.5 화면

- 인덱스 등록·수정 : 검색 키(컬럼 / 표현식 키 추가, ASC·DESC, NULLS, ↑↓), INCLUDE 컬럼, WHERE 조건, 유형·UNIQUE. 프로젝트 DBMS·유형에서 쓸 수 없는 설정은 막고 이유를 보인다(서버도 같은 기준으로 거절).
- 상세 : 검색 키·INCLUDE·WHERE, UNIQUE 의 대신할/공존 컬럼 인덱스 안내, 승인·승인 취소 확인 문구에 대신·복원 안내, 변경 요청 전후 비교(속성별, 바뀐 줄 표시).
- 목록 : 구성에 표현식·정렬·INCLUDE, 숨김 열 WHERE 조건.
- 물리 ERD [인덱스·제약조건] : 같은 API·모델, 고급 속성을 표시만 한다(ERD 의 간단 등록은 일반 인덱스만 만들고 기존 인덱스를 고치지 않으므로 값을 덮어쓰지 않는다. 수정은 인덱스 관리).
- 모델 변경 이력 화면 : `condition`·`expression`·`sort`·`indexSource` 이름 표시.

### 10.6 AI-2b 대응 (M-4 가 쓸 대응표, 가져오기는 구현하지 않음)

| 원본 (`TB_SRC_*`) | 물리 모델 |
|---|---|
| `TB_SRC_IDX.IDX_TYPE_NM` (btree 등) | `TB_TBL_IDX.IDX_TYPE_NM` (대문자 정규화) |
| `TB_SRC_IDX.UNQ_YN` | `UNQ_YN` |
| `TB_SRC_IDX.CND_CN` | `CND_CN` |
| `TB_SRC_IDX_COL.SRC_COL_SN` / `EXPR_CN` | `COL_SN`(같은 이름의 물리 컬럼으로 대응) / `EXPR_CN` |
| `TB_SRC_IDX_COL.SORT_CD` | `SORT_CD` (같은 표기) |
| `TB_SRC_IDX_COL.COL_ORD` | `COL_ORD` (같은 순서 규칙) |

원본 표현식·조건은 PostgreSQL 이 정규화한 문자열이라 가져올 때 같은 정적 검사를 통과하는지 M-4 에서 확인한다. 원본에만 있는 연산자 클래스·COLLATE·인덱스 저장 옵션은 두 모델 모두 담지 않는다.

### 10.7 테스트

| 항목 | 결과 |
|---|---|
| `./gradlew build` | 통과 — 526건 (안정화 513 + 방안 C 6 + 고급 7), 실패 0, 스킵 16 (+1 : PostgreSQL 전용 `AdvancedIndexPostgresDdlTest`) |
| 신규 | `IndexUniquePriorityIntegrationTest` 6 (순서 독립·대기·반려·승인·복원·기존 공존 보존·멱등·동시 처리·프로젝트 격리), `IndexAdvancedIntegrationTest` 3, `AdvancedIndexPostgresDdlTest` 1, DDL 단위 3. `IndexDuplicatePolicyIntegrationTest` 의 C 기대값을 방안 C 로 변경 |
| PostgreSQL 18 (임시 DB, 끝나고 삭제) | 14개 클래스 120건 통과 (`SmokeTest` 전체 포함). 생성 DDL 11종을 실제 실행(BTREE·HASH·GIN·GIST·SPGIST·BRIN, 표현식·부분·정렬·NULLS·INCLUDE·부분 UNIQUE), 거절하는 9개 조합은 PostgreSQL 도 거절함을 확인 |
| React `typecheck`·`build` | 통과 |
| HTTP 종단 확인 (h2-local, 실행 중인 앱) | 고급 인덱스 등록 → DDL·목록, 잘못된 조합 거절 메시지, 새 화면 번들 제공 |
| 브라우저 | **하지 못했다** (Chrome 확장 연결 안 됨). 화면(등록·수정 폼, 상세 비교, ERD 표시)·권한별·영어·다크·좁은 화면은 미검증 |

### 10.8 잔여

- 브라우저 수동 검증 (위) — 끝나면 M-14 를 완료로 바꾼다.
- 기존 공존 데이터 정리 방식(자동 정리 없음, 수동 절차만).
- 표현식·조건의 문법 검증은 정적 검사뿐 (SQL 파서는 새 의존성이라 범위 밖).
- 다른 DBMS 의 고급 인덱스(Oracle 함수 기반·DESC, MySQL 8 함수형 키·DESC).
- 연산자 클래스·COLLATE·저장 옵션(`WITH (...)`)·`CONCURRENTLY` 는 모델에 없다.
- M-4 : 원본 인덱스 → 물리 모델 가져오기·매핑·차이 비교 (대응표 10.6).

## 11. 브라우저 E2E 검증 (2026-11-13)

### 11.1 환경

| 항목 | 내용 |
|---|---|
| 앱 | `h2-local` (인메모리 H2, 검증 뒤 종료) — 개발 DB `lexorma` 는 쓰지 않았다. `./gradlew :lexorma-app:bootRun -PfrontendPrebuilt=true` (최신 `dist`) |
| 브라우저 | Google Chrome 157.0.8103.79, **Claude in Chrome 확장**으로 실제 창을 조작 (클릭·입력·선택·창 크기). 영어 화면만 같은 Chrome 157 을 헤드리스로 띄워 DevTools 프로토콜로 `en-US` 언어를 지정해 확인 (사용자 Chrome 설정을 바꾸지 않기 위해, 임시 프로필) |
| 테스트 데이터 | 프로젝트 LEXORMA(PostgreSQL, 예제 테이블 TB_PST — 컬럼 13개) + 격리 확인용 프로젝트 M14ISO. 계정 `admin`(ADMIN·승인 권한), `user1`(USER·승인 권한 없음), `viewer1`(VIEWER) — 앱의 가입·사용자 관리 API 로 만들었다 (인메모리 DB, 비밀번호는 문서에 남기지 않음). CTGRY_CD·WRTR_ID 컬럼은 `IDX_YN` 을 바꿀 수 있게 승인 취소 상태로 준비 |
| 다크 모드 | 앱은 `prefers-color-scheme` 로 `<html data-theme>` 를 정한다. 확장으로는 미디어 쿼리를 바꿀 수 없어 같은 속성을 직접 지정해 확인 (같은 CSS) |
| 좁은 화면 | 창 너비 768px (태블릿). 375px 를 요청했지만 데스크톱 Chrome 의 최소 창 너비 때문에 **실제 뷰포트는 500px** 였다 |

### 11.2 결과

| 영역 | 결과 | 확인한 내용 |
|---|---|---|
| 인덱스 목록·검색 | PASS | 메뉴·진입, 스키마·승인 상태·출처 필터, 인덱스명·테이블명·컬럼(대소문자 무시) 검색이 API 파라미터(`indexSource`·`isApproval`·`searchColumn`)와 일치, 변경 요청 대기 표시, 빈 결과 안내, ERD 에서 온 `?tableId=` 칩·해제 |
| 일반 인덱스 CRUD | PASS | 단일 BTREE 등록 → 목록·상세·DDL 일치. 복합 3개 추가·빼기·↑ 이동 → `MDFCN_DT, CTGRY_CD` 순서가 목록·상세·DDL 에 동일. 미승인 인덱스 이름·유형(BRIN)·컬럼·순서 직접 수정(변경 요청 없음), 삭제 확인 후 목록에서 사라짐 |
| 표현식 인덱스 | PASS | 표현식 키 `lower(PST_TTL)` → `((lower(PST_TTL)))` 로 DDL, 수정(변경 요청) 뒤 `upper(...)` 반영 |
| 부분 인덱스 | PASS | `WHERE DEL_YN = false` 저장·상세·DDL, 변경 요청으로 `= true` 반영 |
| ASC/DESC·NULLS | PASS | `REG_DT DESC NULLS LAST` → 변경 요청으로 `NULLS FIRST` (ASC) — 상세·DDL 일치. GIN 에서는 NULLS 선택 비활성·정렬 안내 |
| INCLUDE | PASS | 추가·빼기, 키 컬럼·이미 넣은 컬럼은 선택지에서 빠짐(중복 불가), DDL `INCLUDE (...)` |
| 복합 고급 인덱스 | PASS | 입력 = 상세 = DDL `CREATE INDEX IX_E2E_ADV ON TB_PST ((lower(PST_TTL)), REG_DT DESC NULLS LAST) INCLUDE (WRTR_ID) WHERE DEL_YN = false;` |
| 잘못된 조합 | PASS | GIN+DESC : 화면에서 안내·저장 막음. `;` 가 든 WHERE·HASH+UNIQUE : 서버 오류 메시지 표시. 빈 표현식 : 안내. 오류 뒤 입력값 유지, 실패한 등록은 목록에 없음. (다른 DBMS 의 고급 속성·다른 테이블 컬럼 참조는 화면에서 고를 수 없어 통합 테스트로만 확인) |
| UNIQUE 우선 정책 | PASS | 경로 A(승인된 UNIQUE → 컬럼 편집에서 인덱스 여부 켬) : 컬럼 인덱스 생기지 않음. 경로 B(컬럼 편집으로 인덱스 여부 → 컬럼 승인 = 승인된 컬럼 인덱스 → `user1` 의 UNIQUE 등록) : 승인 대기 중 두 인덱스 공존·DDL 유지·승인 대기 목록에 UNIQUE, 상세에 "승인하면 대신" 안내 → 관리자 승인 시 컬럼 인덱스 삭제. 두 경로 최종 구성 동일. 승인 취소 → 컬럼 인덱스 복원 |
| UNIQUE 재승인 경고 | PASS | 승인 확인 문구가 대신(삭제)될 인덱스 이름을 밝힌다 : "승인하면 이 UNIQUE 인덱스가 같은 컬럼의 컬럼 인덱스 IX_TB_PST_CTGRY_CD 을(를) 대신하며 그 인덱스는 삭제됩니다." 승인 취소 문구도 복원을 알린다. 변경 이력 : 승인+삭제(자동 동기화), 승인 취소+생성이 각각 같은 변경 그룹 |
| 기존 공존 데이터 | BLOCKED | 화면으로는 만들 수 없는 데이터라(설계상 승인 시 대신) H2 콘솔로 넣으려 했으나 `h2-local` 의 H2 콘솔이 앱 CSRF(403 `CSRF_INVALID`)로 막혀 있다 (M-14 밖의 기존 문제). `IndexUniquePriorityIntegrationTest.legacyCoexistencePreserved`(H2·PostgreSQL)로만 확인 |
| 승인·반려 | PASS | `user1` 의 변경 요청 2건 → 관리자 승인(새 정의·DDL 반영, 승인 상태 유지, 처리자 기록) / 반려(BTREE 유지, 요청 행 보존). 대기 중 수정·승인 취소 버튼 비활성, 중복 요청은 화면·서버 모두 거절 |
| 변경 전후 비교 | PASS | 속성별 전후, 바뀐 줄만 ● 강조 (이름·구분처럼 같은 줄은 표시 없음). 표현식·정렬·INCLUDE·WHERE 차이 모두 표시 |
| ERD 연계 | PASS | 인덱스 관리 → ERD(해당 테이블 선택), ERD 대화상자에 같은 인덱스·고급 속성·승인 상태. ERD 에서 일반 인덱스를 추가해도 IX_E2E_ADV 의 표현식·NULLS·INCLUDE·WHERE 그대로. ERD → 인덱스 관리(테이블 조건) |
| 권한별 동작 | PASS | ADMIN : 전부. USER(승인 권한 없음) : 등록·미승인 수정·삭제·변경 요청, 승인 버튼 없음, 승인·요청 처리 API 는 400 `APPROVAL_PERMISSION_DENIED`, 승인된 인덱스 삭제 400. VIEWER : 메뉴·조회만, 추가·수정·삭제·승인 버튼 없음, 변경 API 모두 403 `ACCESS_DENIED`. 다른 프로젝트(M14ISO) : 목록 비어 있음, LEXORMA 인덱스 ID 의 상세·DDL·수정·승인·삭제 모두 400 (찾을 수 없음) |
| 한국어·영어 | PASS (Low 2건) | 영어 화면에 한글 문구 없음(사용자 이름 제외), 서버 오류도 영어. D-6·D-7 |
| 라이트·다크 | PASS | 목록·상세·변경 비교 강조·DDL 코드·폼·라벨 대비 정상 |
| 반응형 | PASS (Medium 1·Low 2) | 768px·500px 에서 상세(한 열)·폼(키 순서·INCLUDE·WHERE·등록)·닫기 모두 사용 가능. 목록 가독성 D-3, 비교 표 D-4 |
| Console·Network | PASS | 콘솔 오류·예외 없음. 4xx 는 의도한 권한·격리 확인뿐, 5xx 없음. 요청 반복 D-5 |

### 11.3 발견 사항 (수정하지 않음 — 사용자 협의 후 별도 작업)

| ID | 심각도 | 화면 | 내용 | 권장 |
|---|---|---|---|---|
| D-3 | Medium | 목록 (좁은 화면) | 열 8개에 고정 폭(%)을 줘서 768px 에서는 값이 한두 글자씩 줄바꿈(`DE/FA/UL/T`), 500px 에서는 `nowrap` 이라 옆 칸과 겹친다. 표가 가로 스크롤되지 않는다(테이블 `overflow: hidden`). 공통 Grid 의 동작이고 열이 많은 인덱스 목록에서 두드러진다. 행 → 상세로 작업은 가능 | 좁은 화면에서 열 숨김(유형·출처) 또는 Grid 의 가로 스크롤 허용 |
| D-1 | Low | 등록·수정 폼 | 키 줄의 ASC/DESC·NULLS 선택이 대화상자 공통 `.form-control { width: 100% }` 에 밀려 한 줄씩 전체 폭으로 쌓인다 (`.index-form-small` 이 적용되지 않음). 동작은 정상 | 선택자 우선순위 조정 (`dialog .index-form-small`) |
| D-2 | Low | 상세 | 이름을 바꾼 뒤 상세 대화상자 제목이 예전 이름 그대로 (다시 열면 정상) | 상세 다시 조회 때 `setTitle` |
| D-4 | Low | 변경 요청 비교 (좁은 화면) | 값 칸이 좁아 `overflow-wrap: anywhere` 로 한 글자씩 줄바꿈 | 좁은 화면에서 전·후를 세로로 쌓기 |
| D-5 | Low | 등록 폼 테이블 검색 | 입력 한 글자마다 `/api/selectTable/data` 호출 (TB_PST 입력에 6회) | 디바운스 |
| D-6 | Low | 영어 목록 | 구분(INDEX/UNIQUE)·유형(BTREE…) 열 제목이 모두 "Type" | 구분 → "Kind" 등으로 구별 |
| D-7 | Low | 영어 목록 | 출처 열이 좁아 "Registered" 가 "Registere / d" 로 끊긴다 | 열 폭 조정 또는 짧은 표기 |

참고 (M-14 밖) : `h2-local` 의 H2 콘솔이 CSRF 로 로그인되지 않는다. 권한 거절은 기존 규약대로 400 + 코드(`APPROVAL_PERMISSION_DENIED` 등)이고 VIEWER 의 역할 거절만 403 이다.

### 11.4 판단

**조건부 완료 (B)** : 인덱스 정의·DDL·승인·UNIQUE 우선·ERD·권한의 핵심 흐름이 실제 브라우저에서 모두 통과했고 Critical/High 결함은 없다. 남은 것은 좁은 화면 가독성(Medium)과 Low 표시 문제, 그리고 기존 공존 데이터 화면 확인(BLOCKED, 통합 테스트로 확인)이다. 로드맵의 M-14 완료 처리는 D-3 처리 방침을 사용자와 정한 뒤 한다.

코드·마이그레이션 변경 없음 (이번 작업은 검증과 문서만). 자동 테스트는 다시 돌리지 않았다 — 기준 결과는 10.7 (526건 통과, PostgreSQL 120건 통과).


## 12. UI/UX 통일·최종 안정화 (2026-11-13)

화면만 바꿨다 (백엔드·API·마이그레이션·승인·UNIQUE 우선 정책 변경 없음).

| 화면 | 바꾼 것 (재사용한 공통 패턴) |
|---|---|
| 목록 | `Grid` 에 선택 prop `minWidth` 추가 (주지 않은 그리드는 그대로). 인덱스 목록은 980px 아래에서 표 영역만 가로 스크롤, 긴 값은 한 줄 말줄임 + `title` 툴팁, 변경 요청 라벨은 항상 보인다. 좁은 화면에서 필터 줄 나눔은 이 화면에만 (`.index-grid-container`) |
| 등록·수정 | 테이블 등록과 같은 `edit-form` / `form-group` (라벨 위 입력), 키·INCLUDE 는 상세와 같은 `dict-detail-columns` 표, 아래 `gh-dialog-actions` (닫기·저장) |
| 상세 | 테이블 상세와 같은 `dict-detail-status` → `dict-detail` (기본 정보 · 검색 키 · 고급 속성 · 변경 정보 · 변경 요청) → `gh-dialog-actions` (승인 `btn-primary` / 승인취소·삭제 `btn-danger`) |
| 변경 요청 비교 | 모델 변경 이력과 같은 `history-diff` (항목 / 변경 전 / 변경 후, 바뀐 값 취소선·강조), 최소 480px + 가로 스크롤 |

| 결함 | 처리 | 확인 |
|---|---|---|
| D-1 | 표 안 정렬·NULLS 선택은 내용 폭 (`dialog .index-editor td .form-control`) | 1440·768·375 : 두 선택이 한 줄, 96px |
| D-2 | 상세가 다시 조회한 이름으로 `setTitle` | 이름 변경 후 제목·기본 정보 모두 새 이름 |
| D-3 | 위 목록 처리 | 1440/1024/768/500/375 : 줄바꿈 셀 0, 2줄 넘는 헤더 0, 페이지 가로 넘침 없음, 잘린 값 모두 툴팁 |
| D-4 | 위 비교 처리 | 375 : 비교 표 480px, 칸당 최대 4줄 (키 줄마다 하나) |
| D-5 | 300ms 디바운스 (컬럼 등록과 같은 값), 조회 키 = 확정된 검색어라 늦게 온 이전 응답이 덮지 않고, 언마운트 시 타이머 해제 | `TB_PST` 빠른 입력 → 요청 1회, 결과 TB_PST… |
| D-6 | 구분 → `tc('인덱스','구분')` "Kind", 유형 → "Index type" | 영어 헤더 중복 없음 |
| D-7 | "직접 등록" 영어 "Manual", 헤더 `nowrap` | 영어 2줄 헤더 0 |

추가 수정 : 변경 요청 반려 버튼이 영어에서 상태 라벨 "Rejected" 로 보이던 것을 `tc('버튼','반려')` "Reject" 로.

검증 : in-memory `h2-local` (개발 DB 미사용), 헤드리스 Chrome 157 CDP `Emulation.setDeviceMetricsOverride` 로 1440/1024/768/500/375 실제 뷰포트, ko/en × light/dark (ADMIN), USER·VIEWER 권한 (USER : 승인 버튼 없음·수정은 "변경 요청", VIEWER : DDL·ERD·테이블 상세만), 콘솔 오류 0, 앱 4xx/5xx 0. 실제 Chrome(확장)에서 1920px 상세 확인. `npm run typecheck`·`npm run build` 통과, `./gradlew build` 526 tests / 0 failures / 16 skipped (기준선 그대로).

남은 것 (완료 판단에 영향 없음) : 프론트엔드 테스트 러너가 없어 D-2·D-5 는 자동 테스트 대신 위 브라우저 검증으로 확인 (러너 도입은 별도 결정). 기존 공존 데이터 화면 확인은 §11 그대로 BLOCKED (H2 콘솔 CSRF, `IndexUniquePriorityIntegrationTest` 로 확인).

**결과 : M-14 완료.**

## 13. LEXORMA 예제 인덱스 등록 (2026-11-13, `V5__lexorma_index_seed.sql`)

인덱스 관리 화면에서 Lexorma 자체 테이블의 인덱스도 보이도록, 프로젝트 LEXORMA(id 0)의 예제 물리 모델에 실제 스키마의 인덱스를 넣었다 (데이터만, 스키마 변경 없음, PostgreSQL·H2 같은 내용).

- 대상 : V0·V3 의 `create [unique] index … on TB_…` 83개 (구성 컬럼 116개). 이름·컬럼·순서·UNIQUE 여부 그대로, 출처 `USER`, 승인 상태, 유형 없음(기본), 등록자 `SYSTEM`.
- 넣지 않은 것 : PK, `alter table … add constraint … unique` 로 만든 UNIQUE 제약 4개(`UX_SRC_SCHM_02`·`UX_SRC_TBL_02`·`UX_SRC_CLCT_EXCN_04` 등 — 제약조건 관리 대상), `SPRING_SESSION` 인덱스(예제에 테이블이 없다).
- 기존 DB 보호 : 같은 테이블에 같은 이름의 인덱스가 이미 있거나 테이블·구성 컬럼이 하나라도 없으면(프로젝트 LEXORMA 를 지웠거나 고친 경우) 그 인덱스는 건너뛴다. 기존 인덱스·컬럼의 `IDX_YN` 은 바꾸지 않는다.
- 네이티브 SQL 이라 모델 변경 이력에는 행이 없고, V5 이전 모델 버전과 현재를 비교하면 인덱스 83개가 추가로 보인다 (V3 이관과 같다).
- 검증 : `V0SeedMigrationTest` (마이그레이션 6개, 인덱스 83·컬럼 116, 임시 테이블 없음), `IndexMigrationV3Test` 의 V4 단계는 `target("4")` 로 고정.
- 앞으로 `create index` 를 추가하는 마이그레이션은 같은 파일에 예제 인덱스도 넣는다.
