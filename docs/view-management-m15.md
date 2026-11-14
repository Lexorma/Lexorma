# M-15 1차 — VIEW 관리 구현 기록

> 상태 : **M-15 1차 구현 + 안정화** (2026-11-13). 설계 결정 D-25~D-27 은 12장, 검증 결과는 10장(1차)·12.5(안정화). 로드맵 : [data-modeling-roadmap.md M-15](data-modeling-roadmap.md#m-15-view-관리-view-모델링-신규-도입)
> 마이그레이션 : `V6__view_management.sql` (2026-11-13 V0·V1 로 합쳤다 — [기록](schema-scoped-naming.md#6-v0v1-통합)) (PostgreSQL·H2 같은 내용, H2 는 시퀀스 문법만 다르다). 이미 적용된 V0~V5 는 고치지 않았다.

## 1. 구현 전 구조 (2026-11-13 코드 확인)

| 항목 | 확인 결과 | M-15 에 준 영향 |
|---|---|---|
| 물리 모델 | `TB_TBL`(TableInfo)에 객체 유형이 없다. 모든 행이 테이블이다 | VIEW 는 새 테이블 `TB_VIEW` (TABLE 에 유형을 더하지 않음) |
| 테이블명 중복 | **프로젝트 안에서** 하나 (`findAllByTableNameAndProjectId`, 대문자 저장). DB UNIQUE 없음(확인 후 저장). 컬럼의 참조 테이블명이 이름으로 테이블을 가리킨다 | TABLE/VIEW 이름 공간을 프로젝트 단위로 잡았다 (3장) |
| 테이블 이름을 만드는 경로 | 등록 `saveTable`, 수정 `updateTable`, 엑셀 업로드 `uploadTableExcelFile` 세 곳 (ERD 편집도 같은 API) | 세 곳에 이름 공간 잠금·확인을 넣었다 |
| 스키마 | `TB_SCHM`, 프로젝트마다 기본 스키마 1개, 이름 규칙 `SchemaInfo.NAME_PATTERN` (따옴표 없는 식별자, 63자) | 그대로 재사용 (별도 스키마 체계 없음) |
| 식별자 인용 | DDL 은 따옴표 없이 이름을 그대로 쓴다 (테이블·컬럼·인덱스) | VIEW 이름도 같은 규칙으로 검증 후 따옴표 없이 |
| 승인 | 테이블 : 승인된 테이블은 수정·삭제 불가. 인덱스(M-14 D-20) : 승인된 인덱스 수정 = 변경 요청 `TB_IDX_CHG_DMND`(전후 정의 JSON), 승인 권한자의 요청은 즉시 승인 | 인덱스의 변경 요청 구조를 VIEW 정의 전체 단위로 재사용 (`TB_VIEW_CHG_DMND`) |
| 변경 이력 | `ModelChangeRecorder` : Hibernate 이벤트, 트랜잭션 = 변경 그룹, 부모 + 자식 행 배열. **부모 하나에 자식 행 종류 하나만** 지원 | VIEW 는 출력 컬럼·참조 객체 두 종류라 기록기를 여러 종류로 일반화 (6장) |
| 모델 버전 | 이력과 같은 필드 목록의 스냅샷, `FORMAT_VERSION` 3, null 필드 = 없는 필드 | 대상 종류 `PHYSICAL_VIEW` 추가, `FORMAT_VERSION` 4 |
| DDL | `TableDdlGenerator` (PostgreSQL·Oracle·MySQL·MariaDB), 골든 파일 고정. 프로젝트 전체 DDL Export 는 없다 (M-11 "일부 구현") | VIEW 는 별도 생성기 `ViewDdlGenerator` (테이블 생성기·골든 파일 변경 없음), VIEW 단위·프로젝트 단위 내보내기 |
| ERD | React Flow, 노드 id = 테이블 ID, 배치 저장(`TB_ERD_LYT`)은 테이블 ID 만 받는다 | VIEW 노드는 `v<ID>`, 배치 저장 대상이 아니다 (8장) |
| Flyway | V0~V5, V5 = LEXORMA 예제 인덱스(데이터만). 미완료 작업 없음 | 새 버전 **V6**. 새 `create index` 는 같은 파일에 예제 인덱스를 넣는다 (V5 규칙) |
| SQL 파서 | 없다. 인덱스 표현식·조건은 정적 검사(`sqlFragment`) | 새 의존성 없이 정적 검사기 `ViewDefinitionSql` (4장) |

설계 충돌로 중단할 사항은 없었다. 지시서와 다르게 정한 것은 **이름 공간 단위**(스키마가 아니라 프로젝트, 3장) 하나이고, 기존 테이블 이름 규칙과 맞추기 위해서다.

참고 : 로드맵의 D-22~D-24 는 미확정 질문(출력 컬럼 범위 / 참조 지정·무결성 / DBMS·SQL 검증)이었다. 이번 지시서의 D-22(독립 모델)·D-23(정의 SQL)·D-24(참조 객체) 결정이 그 질문에 답하므로 로드맵을 지시서의 번호·내용으로 맞췄다.

## 2. D-22 — VIEW 독립 모델

```text
TB_VIEW (VIEW_SN, PRJ_SN, SCHM_SN → TB_SCHM, VIEW_NM, VIEW_KORN_NM, VIEW_EXPLN, VIEW_DFN_CN(TEXT), APRV_YN, 감사 컬럼)
 ├── TB_VIEW_COL      (VIEW_COL_SN, VIEW_SN, COL_NM, COL_KORN_NM, DATA_TYPE_NM, COL_ORD, COL_EXPLN)
 ├── TB_VIEW_RFRNC    (VIEW_RFRNC_SN, VIEW_SN, RFRNC_TYPE_CD, RFRNC_TBL_SN → TB_TBL, RFRNC_VIEW_SN → TB_VIEW, RFRNC_SCHM_NM, RFRNC_TRGT_NM)
 └── TB_VIEW_CHG_DMND (VIEW_CHG_DMND_SN, PRJ_SN, VIEW_SN, CHG_BFR_CN, CHG_AFTR_CN, DMND_STTS_CD, 감사 컬럼)
```

| 제약 | DB | 서비스 |
|---|---|---|
| 프로젝트·스키마 정합성 | `FK_VIEW_01` (스키마) | 스키마는 로그인 프로젝트 것만 (`resolveSchema`) |
| VIEW명 중복 | `UX_VIEW_01 (PRJ_SN, VIEW_NM)` (대문자 저장이라 대소문자 무관) | 이름 공간 잠금 안에서 확인 |
| TABLE/VIEW 이름 충돌 | — (두 저장 구조라 UNIQUE 하나로 못 막는다) | 이름 공간 잠금 + 양쪽 확인 (3장) |
| 출력 컬럼 순서 | `UX_VIEW_COL_01 (VIEW_SN, COL_ORD)` | 1..n 연속 (순서 자리를 유지하고 내용만 바꾼다, 인덱스 구성과 같은 방식) |
| 출력 컬럼명 중복 | — (이름을 서로 바꾸는 수정이 flush 중에 걸리므로 두지 않았다) | 대문자 비교로 거절 |
| 참조 중복 | `UX_VIEW_RFRNC_01 (VIEW_SN, RFRNC_TBL_SN)`, `UX_VIEW_RFRNC_02 (VIEW_SN, RFRNC_VIEW_SN)` | 미확인 참조는 (유형, 스키마, 이름) 대문자 비교 |
| 자기 참조·순환 | — | 확정 참조 그래프로 검사 (5장) |
| 참조 대상 삭제 | `FK_VIEW_RFRNC_02·03` (CASCADE 없음 : 서비스를 거치지 않아도 DB 가 막는다) | 사유를 알리고 거절 (5장) |
| 프로젝트 간 참조 | — | 참조 대상은 로그인 프로젝트 것만 |
| 프로젝트 삭제 | — (`PRJ_SN` 에 FK 없음, 기존과 같다) | `ProjectDataCleanup` 이 VIEW → 테이블·스키마 순으로 지운다 |

- **이름 규칙** : VIEW·출력 컬럼 이름은 `SchemaInfo.NAME_PATTERN`(영문자·숫자·`_`·`$`, 영문자나 `_` 로 시작, 63자)과 같고 대문자로 저장한다. 네 DBMS 의 대표 예약어(SELECT, TABLE, VIEW, USER, ORDER …)는 거절한다. 따옴표가 필요한 이름은 받지 않으므로 DDL 은 따옴표 없이 쓴다 (테이블 DDL 과 같다).
- **엔티티** : `PhysicalView`·`PhysicalViewColumn`·`PhysicalViewReference`·`ViewChangeRequest` (`com.lexorma.data.view`). 테이블 엔티티는 바꾸지 않았다.
- **예제** : V6 는 LEXORMA 예제 프로젝트에 이 4개 테이블의 물리·논리 모델·매핑·데이터 사전(새 단어 `뷰`(VIEW)·`정의`(DFN), 용어 13개)과 자체 인덱스 8개를 넣는다. **VIEW 예제 행(TB_VIEW)은 넣지 않았다.** 프로젝트 LEXORMA·기본 스키마·논리 모델이 없거나 같은 이름이 이미 있으면 그 행은 건너뛴다 (지웠거나 고친 설치 보호). 네이티브 SQL 이라 변경 이력이 없고, V6 이전 모델 버전과 비교하면 예제 테이블 4개가 추가로 보인다 (V3·V5 와 같다).

## 3. TABLE/VIEW 이름 충돌 정책

- **이름 공간 = 프로젝트** (지시서는 "같은 스키마"). Lexorma 의 테이블명은 이미 프로젝트 안에서 하나이고 컬럼의 참조 테이블명이 이름으로 테이블을 찾으므로, VIEW 만 스키마 단위로 두면 "다른 스키마의 같은 이름" 이 테이블끼리는 안 되고 테이블-VIEW 사이에서는 되는 어긋난 규칙이 된다. 같은 스키마 안의 충돌(지시서의 요구)은 이 규칙에 포함된다. 다른 프로젝트는 같은 이름을 쓸 수 있다.
- **비교** : 대문자. 테이블 경로는 원래 대문자로 저장한다.
- **동시성** : `ObjectNamespace.lock(projectId)` = **프로젝트 기본 스키마 행 배타 잠금** (`SELECT … FOR UPDATE`). 이름을 만들거나 바꾸는 쓰기와 참조 관계를 바꾸는 쓰기가 모두 이 잠금 뒤에 확인한다 :
  테이블 등록·수정·엑셀 업로드·삭제, VIEW 등록·수정·삭제·변경 요청 처리. 잠금 순서는 이름 공간 → 테이블·VIEW 행 (`updateTable`·`deleteTable` 은 테이블 행보다 이름 공간을 먼저 잠그도록 바꿨다).
  프로젝트 행은 먼저 잠그지 않는다 : 처음에는 프로젝트 공유 잠금 → 이름 공간 순서로 했으나, H2 가 그 공유 잠금을 배타 잠금으로 잡아 "테이블 승인(테이블 행 잠금 → 커밋 때 프로젝트 확인)" 과 서로 기다리는 교착이 `ModelIntegrityHardeningIntegrationTest` 에서 드러났다. 다른 하위 행 잠금과 같이 커밋 직전 `ProjectWriteGuard` 확인에 맡긴다.
  기존 테이블 모델·저장 구조는 바꾸지 않았다 (잠금과 확인만 더함).
- **승인 대기 이름** : 변경 요청의 새 이름은 예약하지 않는다. 요청 뒤 같은 이름의 테이블·VIEW 가 생기면 **승인할 때 다시 검증**해 거절하고 아무것도 바꾸지 않는다 (인덱스 변경 요청과 같은 방식).
- 엑셀 업로드 : 같은 이름의 VIEW 가 있는 행은 "이미 등록된 이름" 과 같이 건너뛴다 (기존 동작).
- 알려진 한계 : 대문자가 아닌 이름으로 저장된 예전 테이블(직접 SQL 등)은 대문자 비교에서 빠진다.

## 4. D-23 — 정의 SQL

- **저장** : `CREATE VIEW … AS` 뒤의 조회 문 **원문 그대로** (대소문자·공백·줄바꿈·주석·끝의 `;` 포함). 서버는 포맷·대문자 변환을 하지 않는다.
- **정적 검사** (`ViewDefinitionSql`, 실행·파싱 없음) : 문자열(`'…'`, MySQL 은 `\` 이스케이프 포함)·따옴표 식별자(`"…"`, MySQL `` `…` ``)·주석(`--`, `/* */` — PostgreSQL 은 중첩, MySQL `#`)·PostgreSQL 달러 인용(`$tag$…$tag$`)을 건너뛰며 낱말과 `; ( )` 만 본다.

  | 거절 | 메시지 요지 |
  |---|---|
  | 빈 값, 100,000자 초과 | 입력 / 길이 |
  | `CREATE VIEW …` 전체 | AS 뒤의 조회 문만 |
  | `;` 뒤의 다른 문장 (끝의 `;` 와 그 뒤 주석·공백만 허용) | 문장 하나 |
  | SELECT·WITH 로 시작하지 않음 (`(` 는 건너뜀) | 조회 문 |
  | **문장이 시작할 수 있는 자리**(맨 앞, `(` 바로 뒤 = 하위 질의·CTE 본문, WITH 의 CTE 목록 뒤 = 본 문장)의 INSERT·UPDATE·DELETE·MERGE·DROP·ALTER·CREATE·TRUNCATE·GRANT·REVOKE·COMMIT·ROLLBACK·SAVEPOINT·CALL·EXEC(UTE)·COPY·LOCK·VACUUM·ANALYZE·SET·DO, 그리고 자리와 관계없이 INTO (`.` 뒤 제외) | 조회가 아닌 명령 (안정화 12.3 에서 자리 기준으로 좁힘) |
  | 닫히지 않은 따옴표·주석, 괄호 짝 | 형식 |

- **보장하지 않는 것** : 문법, 참조 테이블·컬럼 존재, 출력 컬럼·타입, DBMS 호환성, 성능. 화면에 "VIEW 정의 SQL 은 실행하지 않습니다. 실제 DBMS 적용 시 문법 및 참조 객체 검증이 필요합니다." 를 표시한다. 1차에는 낱말 단위 검사라 별칭 `AS update`, `FOR UPDATE`, `CHARACTER SET` 도 거절했다(오탐) — 안정화에서 고쳤다 (12.3). `REPLACE` 는 문자열 함수라 막지 않는다 (MySQL `REPLACE INTO` 는 INTO 로 걸린다). Oracle `q'[…]'` 인용은 인식하지 않는다.
- **SQL 파서 라이브러리** : 도입하지 않았다 (새 의존성·DBMS 별 문법 차이·유지보수 부담. M-14 에서도 같은 판단). 필요하면 별도 결정.
- **어디서도 실행하지 않는다** : 원본 DB·Lexorma DB 모두. 정의 SQL 을 실행하는 코드 경로가 없다 (서비스는 문자열 검사와 DDL 문자열 생성만 한다).

## 5. D-24 — 참조 객체

- **수동 관리 메타데이터** : SQL 에서 추출하지 않고, SQL 이 실제로 쓰는지 보장하지 않으며, 서로 자동으로 고치지 않는다 (SQL 을 바꿔도 참조·출력 컬럼은 그대로, 반대도 같다). 화면에 "직접 관리하는 정보" 로 표시한다. SQL 을 바꾸면 수정 화면에 "출력 컬럼 메타데이터가 현재 SQL 과 일치하는지 확인하세요" 안내.
- **내부 참조** : 같은 프로젝트의 테이블(`RFRNC_TBL_SN`) 또는 VIEW(`RFRNC_VIEW_SN`), 다른 스키마 포함. 다른 프로젝트 객체의 ID 는 "찾을 수 없음" 으로 거절.
- **미확인(외부) 참조 — 지원** : 두 ID 가 비고 `RFRNC_SCHM_NM`(선택)·`RFRNC_TRGT_NM` 만. 존재하는 객체처럼 다루지 않는다 (화면 "미확인", DDL 순서·ERD 선에 쓰지 않음, 내보내기에 경고). 모델에 있는 이름(스키마를 비웠거나 같은 스키마)을 미확인으로 적으면 내부 참조로 연결하라고 거절한다.
- **순환** : 자기 참조 거절. VIEW→VIEW 참조 그래프는 **확정된 참조**(승인 대기 요청의 참조 제외)로 만들고, 이 VIEW 의 나가는 참조를 새 것으로 바꿨을 때 자기에게 돌아오면 거절한다 (메시지에 경로 `VW_A → VW_C → VW_B → VW_A`). 이름 공간 잠금 안에서 검사하므로 동시 승인이 서로의 결과를 본다 (테스트 : 서로를 참조하게 하는 두 요청을 동시에 승인 → 하나만 반영).
- **참조 대상 삭제** : 자동으로 정리하지 않는다. VIEW 가 참조하는 테이블은 삭제 거절(`RELATION_EXISTS`, 참조하는 VIEW 이름 안내), 다른 VIEW 가 참조하는 VIEW 도 거절, VIEW 가 있는 스키마도 삭제 거절. 테이블 삭제의 기존 조건(컬럼·매핑·승인)은 그대로이고 그 뒤에 확인한다. 승인 대기 요청이 지워진 대상을 참조하면 그 요청은 승인할 때 거절된다.
- **컬럼 수준 Lineage** 는 하지 않는다 (M-12).

## 6. API·승인·이력·버전

### 6.1 API (`/api/physical/view`, 로그 본문 정책은 기존 마스킹 대상)

| API | 역할 | 비고 |
|---|---|---|
| `GET /data` | VIEWER | Grid. `schemaId`·`isApproval`(Y/N), 검색 `viewName`·`viewKorName`·`viewDescription`. 출력 컬럼 수·참조 수·변경 요청 대기 |
| `GET /{id}` | VIEWER | 확정 정의, 변경 요청 이력, 이 VIEW 를 참조하는 VIEW |
| `GET /{id}/ddl` | VIEWER | CREATE VIEW (확정 정의, `defaultSchemaName`) |
| `GET /ddl` | VIEWER | 프로젝트(또는 `schemaId`)의 VIEW 전체, 참조 순서 |
| `GET /targets` | VIEWER | 참조 후보 (테이블·VIEW 이름·논리명 검색, 종류별 50) |
| `POST /ddl/preview` | USER | 저장 전 미리보기 (같은 검증, 저장·실행 없음) |
| `POST` / `PUT /{id}` / `DELETE /{id}` | USER | 등록 / 수정·변경 요청 / 삭제 |
| `PATCH /{id}/approval/{bool}` | USER + 승인 권한 | 승인·승인 취소 |
| `PATCH /request/{requestId}/{approve}` | USER + 승인 권한 | 변경 요청 승인·반려 |
| `GET /api/erd/views` | VIEWER | 물리 ERD 의 VIEW 노드 (스키마 조건, 이름순 300) |

모든 조회·변경이 로그인 프로젝트로 범위를 건다. 다른 프로젝트의 VIEW·요청 ID 는 "대상을 찾을 수 없습니다" (`INVALID_PHYSICAL_MODEL`, 인덱스와 같은 응답). 권한 거절은 기존 규약대로 역할 403 / 승인 권한 400 `APPROVAL_PERMISSION_DENIED`. 메뉴 `view-manage`(VIEWER), 변경 버튼은 기존 `physical-model-edit`(USER), 승인·반려는 `approvalAvailable` — 새 액션 id 는 만들지 않았다 (`MenuAuthorization`, `menu.ts`, 물리 모델 관리 그룹).

### 6.2 승인 (인덱스 M-14 D-20 과 같은 규칙, 새 규칙 없음)

| 상태 | 동작 |
|---|---|
| 등록 | 승인 권한자(관리자·매니저·프로젝트 관리자) = 바로 승인, 아니면 승인 대기 |
| 미승인 | 바로 수정 (내부 ID 유지) |
| 승인 | 수정 = 변경 요청 (`REQUESTED`, 전후 정의 전체 JSON). 확정 정의·DDL·ERD·모델 버전은 그대로. 승인 권한자의 요청은 즉시 반영(`APPROVED`) |
| 변경 승인 대기 | 새 요청·승인 취소 거절. 삭제는 승인된 VIEW 라 원래 불가 |
| 요청 승인 | 요청의 변경 전 정의가 현재와 다르면 거절. 등록과 같은 검증(이름 공간·참조 대상·순환·SQL)을 다시 거쳐 **기본 정보·정의 SQL·출력 컬럼·참조 객체를 한 트랜잭션으로** 반영 — 하나라도 실패하면 전부 롤백 (테스트 : 요청 뒤 같은 이름의 테이블이 생긴 경우) |
| 반려 | 확정 정의 유지, 요청 행은 남는다 |

- 동시성 : 이름 공간 잠금 → VIEW 행 잠금(`findForUpdate` 후 refresh) → 요청 상태 다시 읽기. 같은 VIEW 의 동시 수정은 차례로 처리된다 (미승인 VIEW 의 직접 수정은 나중 것이 이긴다 — 테이블·인덱스와 같다, 버전 기반 충돌 검증은 넣지 않았다). 같은 요청의 동시 승인은 하나만 처리된다.
- 비교(같은 정의 판정) : 이름·스키마·논리명·설명·SQL 원문·출력 컬럼(순서 포함)은 그대로, 참조 객체는 집합. 화면의 전후 비교는 SQL 이 공백·줄바꿈만 다르면 그렇다고 알린다 (원문 보존과 비교용 정규화를 나눔).

### 6.3 변경 이력 (M-9)

- 대상 종류 `PHYSICAL_VIEW` (이름 `viewName`). 필드 : `schema`, `viewName`, `viewKorName`, `viewDescription`, `definition`, `isApproval` + 자식 배열 `columns`(이름·논리명·타입·순서·설명), `references`(유형·참조 테이블/VIEW(ID + 그 시점 이름)·스키마명·객체명).
- 생성·수정·삭제·승인(APPROVE)·승인 취소(REVOKE)가 남는다. 변경 요청 승인은 승인한 사람·시점의 UPDATE 1건 (요청 자체는 이력 대상이 아니고 요청 행에 요청자·처리자가 남는다). 초기 등록(CREATE)과 승인(APPROVE)은 다른 행이다.
- **기록기 일반화** : 부모 하나에 자식 행 종류 여러 개(VIEW 의 출력 컬럼·참조 객체)를 담도록 `ModelChangeRecorder` 의 자식 행 추적을 종류별로 나눴다. 기존 대상(관계·인덱스·제약조건·식별자)의 결과는 같다 (기존 이력·버전 테스트 통과). `ModelSnapshotFieldCoverageTest` 에 새 엔티티 3개.

### 6.4 모델 버전 (M-10)

- 스냅샷에 `PHYSICAL_VIEW` (확정 정의만 — 변경 요청은 스냅샷에 들어가지 않는다). `FORMAT_VERSION` 4. 기존 스냅샷은 고치지 않는다.
- 비교 : VIEW 추가·삭제·이름·SQL·출력 컬럼(순서 포함)·참조 객체(순서 무관) 변경. FORMAT 3 이하 버전에는 `PHYSICAL_VIEW` 가 없어 현재 VIEW 가 모두 "추가" 로 보이고, 테이블·인덱스는 달라 보이지 않는다 (테스트로 확인).
- 버전 비교 ERD(M-10 ③)에는 VIEW 를 그리지 않는다 (목록 비교에만 나온다) — 잔여.

## 7. DDL (D-24 의 DBMS 범위)

```sql
CREATE VIEW SALES.VW_ACTIVE_USER AS
<정의 SQL 원문 (끝의 ; 와 그 뒤는 뺌)>;
COMMENT ON VIEW SALES.VW_ACTIVE_USER IS '활성 사용자';   -- PostgreSQL
```

| DBMS | 생성문 | 논리명 COMMENT | 실제 DB 검증 |
|---|---|---|---|
| PostgreSQL | `CREATE VIEW` | `COMMENT ON VIEW` | 10장 |
| Oracle | `CREATE VIEW` | `COMMENT ON TABLE` (VIEW 에도 쓰는 문법) | **안 함** |
| MySQL·MariaDB | `CREATE VIEW` (스키마 = database) | 없음 (VIEW 에 COMMENT 가 없다) — 경고 | **안 함** |

- 이름 : 테이블 DDL 과 같은 규칙 (기본 스키마는 접두어 없음 / [스키마명 포함] 을 주면 그 이름, 다른 스키마는 `스키마.이름`).
- 세미콜론 : 원문 끝의 `;`(여러 개도)와 그 뒤 공백·주석은 빼고 생성기가 `;` 하나를 붙인다. 원문이 `--` 주석으로 끝나면 `;` 를 다음 줄에 둔다 (주석에 묻히지 않게). 원문 안의 문자열·주석은 건드리지 않는다 (치환하지 않음).
- `CREATE OR REPLACE VIEW`, `WITH CHECK OPTION`, MySQL `ALGORITHM`·`DEFINER`·`SQL SECURITY`, 출력 컬럼 목록 `CREATE VIEW v (a, b)` 는 내지 않는다.
- 승인 대기 : 확정 정의 기준. 변경 요청은 반영하지 않고 경고, 미승인 VIEW 는 테이블 DDL 처럼 "승인 대기 항목" 에 표시.
- 프로젝트 DDL 이 정의 SQL 을 저장할 때와 다른 DBMS 로 바뀌어 검사를 통과하지 못하면, 원문 그대로 넣고 경고한다 (기본값으로 바꾸지 않음).
- **내보내기** : VIEW 하나(상세 → DDL 보기 → 내보내기 `.sql`), 프로젝트/스키마 전체(목록 [DDL 내보내기]). 전체는 내부 VIEW→VIEW 참조로 위상 정렬(같은 단계는 이름순), 순환이 있으면(등록 때 막으므로 보통 없음) 끝에 두고 경고, 스크립트 밖 VIEW 참조·미확인 참조는 경고. 테이블 DDL 과 한 스크립트로 묶는 프로젝트 전체 Export 는 M-11 범위로 남겼다 (지금 테이블도 프로젝트 단위 Export 가 없다).

## 8. 화면

- 메뉴 `물리 모델 관리 > VIEW 관리` (`/viewManage`). 인덱스 관리(M-14 UI/UX 통일 이후)와 같은 구성·같은 CSS 패턴(`index_manage.css` 의 편집 표·요청 이력 + `view_manage.css`).
- 목록 : 스키마·승인 상태 필터, VIEW명·논리명·설명 검색, 출력 컬럼 수·참조 수·승인 상태·수정 일시, 변경 요청 라벨, 960px 아래 표 영역만 가로 스크롤·긴 값 말줄임 + 툴팁, [DDL 내보내기].
- 등록·수정 : 기본 정보(스키마·VIEW명·논리명·설명) / 정의 SQL(고정폭, 줄바꿈 없는 스크롤 입력, 실행하지 않는다는 안내, SQL 변경 시 출력 컬럼 확인 안내, [DDL 미리보기]) / 출력 컬럼(추가·↑↓·빼기, 타입 선택 입력) / 참조 객체(테이블·VIEW 검색 300ms 디바운스·추가·빼기, 모델 객체/미확인 표시, 미확인 참조 추가). 저장 중 버튼 잠금, 승인된 VIEW 는 [변경 요청].
- 상세 : 상태 → 기본 정보 → 정의 SQL(복사) → 출력 컬럼 → 참조 객체(테이블 상세·VIEW 상세로 이동, 참조하는 VIEW 안내) → 변경 정보(변경 이력 보기) → 변경 요청(전후 비교, 승인·반려). 아래 DDL 보기·ERD 에서 보기·승인/승인취소·삭제·수정.
- 기존 SQL 에디터는 없어 새 에디터 라이브러리 없이 textarea 를 썼다.
- 모델 변경 이력 화면 : 대상 종류 `VIEW`, 필드 이름, 참조 객체 배열은 순서 무관 비교.

### 8.1 물리 ERD (조회·이동만)

- VIEW 노드 : 테이블 노드와 같은 크기·구성(이름·논리명·출력 컬럼), **점선 테두리 + VIEW 표시** 로 구분. [VIEW 표시] 체크(기본 켜짐)·개수. 범위 조회는 같은 스키마 조건, 이웃 조회는 화면의 테이블을 참조하는 VIEW 만.
- 참조선 : VIEW → 참조 객체, 보라 점선 + 화살표 (FK·논리 관계선과 다른 모양, 범례 "VIEW 참조 (FK 아님, 직접 관리)"). 미확인 참조·화면 밖 대상은 선이 없다. FK 로 저장하거나 표현하지 않는다.
- 선택하면 상세 패널(출력 컬럼·참조 객체·이동·DDL·[VIEW 관리에서 보기] → `/viewManage?viewId=`). VIEW 관리 상세의 [ERD 에서 보기] → `/physicalErd?viewId=` 로 그 노드를 가운데로.
- **편집·배치 저장 없음** : VIEW 노드는 끌어 옮길 수 있지만 위치는 이 화면에서만 유지된다 (배치 저장은 테이블만, `TB_ERD_LYT` 형식 변경 없음). 연결점으로 FK 를 만들 수 없다.
- VIEW 가 없는 프로젝트는 예전과 같다 (노드·선·배치 동작 변화 없음).

## 9. AI-2b·M-4 연계

- 원본 VIEW 정의 SQL 수집, 원본 VIEW ↔ 물리 VIEW 매핑·가져오기·비교·동기화는 하지 않았다. 원본 수집 코드(`lexorma-source`)는 바꾸지 않았다 (모듈 경계 그대로).
- M-4 가 쓸 대응 : 원본 `TB_SRC_TBL(TBL_TYPE_CD=VIEW)` 의 이름·COMMENT → `VIEW_NM`·`VIEW_KORN_NM`, `TB_SRC_COL` 의 이름·순서·타입·COMMENT → `TB_VIEW_COL`. 정의 SQL(`pg_get_viewdef`)은 AI-2 후속 수집이 먼저 필요하고, 수집하면 비밀값 정책(U-21)·카탈로그 정규화 해시(AI-1c)를 함께 정한다.
- AI 없이 동작한다. SQL 자동 실행·MCP 연결 없음.

## 10. 검증

| 항목 | 결과 |
|---|---|
| `./gradlew build` (H2, React typecheck·build 포함) | 통과 — 542건, 실패 0, 스킵 19 (PostgreSQL·성능·원본 DB 환경 변수 테스트) |
| 신규 `ViewManagementIntegrationTest` (10건) | 등록·원문 보존·DDL·세미콜론, SQL 정적 검사(거절 15·허용 6, MySQL 백슬래시, DBMS 변경 시 원문+경고), 이름 공간(대소문자·이름 변경 양방향·다른 프로젝트), 동시 등록·테이블 이름 변경 경합(하나만 성공), 출력 컬럼, 참조(다른 스키마·미확인·자기·순환 경로·중복·다른 프로젝트·삭제 제한·DDL 순서), 변경 요청(대기 중 확정 정의·DDL 유지, 원자적 승인, 반려, 재검증 실패 시 롤백, 승인 권한자 즉시 반영), 동시 승인의 순환 방지, 이력·모델 버전(FORMAT 3 과 비교), 격리·VIEWER 403·ERD — 통과 |
| 신규 `ViewPostgresDdlTest` | PostgreSQL 18 에서 생성 스크립트 실행 : 주석·문자열 안 `;`·`--`, 달러 인용, `--` 로 끝나는 원문, 다른 스키마, `COMMENT ON VIEW`, 참조 순서(이름순과 다른 순서) — 통과 |
| 갱신한 기존 테스트 | 마이그레이션 수·예제 인덱스 수(83→91, 116→129), `LexormaIndexSeedV5Test` 의 V5 시점 비교는 V5 이하 파일만, `FORMAT_VERSION` 4, 스냅샷 대상 엔티티 3개, 프로젝트 삭제 준비 데이터에 VIEW, SmokeTest 그리드 목록, 테이블 서비스 단위 테스트의 이름 공간 mock. 삭제·비활성화한 테스트 없음 |
| PostgreSQL 18.6 (로컬 컨테이너의 임시 DB, 끝나고 삭제) | 전체 `./gradlew test --rerun` 541건 통과 (스킵 13 : 성능·원본 DB 수집) + `ViewPostgresDdlTest`. 개발 DB `lexorma` 에는 V6 를 적용하지 않았다 |
| 기존 회귀 | DDL 골든 파일·인덱스·테이블·ERD·이력·버전·`ModelIntegrityHardening`·`SmokeTest`(표준화 품질 0건 포함) 통과 |
| 브라우저 | Claude in Chrome 확장 창은 이 PC 의 localhost 에 접속하지 못했다(`chrome-error`). 대신 로컬 Chrome 157 헤드리스를 DevTools 프로토콜로 조작 (`h2-local`, ADMIN) : 목록 1440/1024/768/500/375(페이지 가로 넘침 0, 2줄 넘는 헤더·셀 0 — 처음엔 수정 일시가 3줄로 줄바꿈되어 폭을 고침), 상세·정의 SQL·참조하는 VIEW, DDL 보기, 화면 입력으로 등록(이름 소문자 → 대문자, SQL 원문, 컬럼 추가, 참조 검색·추가, DDL 미리보기) → 저장 결과 API 로 확인, 375px 등록 폼, 같은 이름 테이블 거절 메시지(ko/en), 물리 ERD VIEW 노드 4·참조선 4·범례·상세 패널, VIEW 관리 → ERD(해당 VIEW 가운데) → VIEW 관리(상세 열림), 다크 모드, 영어(라벨·버튼에 한글 0). 콘솔 오류 0, 4xx 는 의도한 것(초기 비밀번호 401, 이름 중복 400)뿐 |
| 브라우저 미검증 | 승인 권한 없는 USER 의 변경 요청·승인/반려 화면, VIEWER 화면 (API·권한은 통합 테스트로만), 변경 요청 전후 비교 화면 |

## 11. 잔여·위험

- 실제 DBMS 미검증 : Oracle·MySQL·MariaDB 의 생성 DDL (같은 형식의 표준 문법이지만 실행 확인 안 함).
- SQL 정적 검사의 한계 (4장·12.3). SQL 파서는 도입하지 않기로 했다 (D-27).
- 버전 비교 ERD 의 VIEW 표시, ERD 의 VIEW 배치 저장(저장 구조 변경 필요 — D-10 과 함께).
- 테이블과 VIEW 를 한 스크립트로 묶는 프로젝트 DDL Export (M-11).
- 출력 컬럼과 표준 용어 연결·표준화 품질 진단(M-5) 편입 — 결정만 필요.
- 원본 VIEW 연계 (9장).
- ~~결정 필요 : 이름 공간 단위, 승인 대기 이름의 예약 여부~~ → D-25·D-26 으로 확정 (12장). 남은 결정 : 미승인 VIEW 직접 수정의 버전 충돌 검증 도입 여부.
- **스키마 단위 TABLE/VIEW 식별자 재검토** (D-25 후속) : M-4 원본 역공학·스키마 단위 모델링 때 (스키마+이름 식별, 이름 기반 참조의 ID 전환, 엑셀·기존 API 호환, 데이터 이관).

## 12. 안정화·최종 검증 (2026-11-13)

새 기능·DB 변경 없음 (V6 는 고치지 않았고 새 마이그레이션도 없다). 바꾼 코드 : SQL 정적 검사(12.3), 변경 요청 승인 때의 이름 충돌 안내(12.2). 나머지는 테스트·문서.

### 12.1 D-25 — 프로젝트 단위 TABLE/VIEW 이름 유일성 (확정)

> **2026-11-13 변경** : 스키마 단위로 바꿨다 (`UX_VIEW_01 = SCHM_SN + VIEW_NM`, 스키마 이름 공간 잠금 + VIEW 참조 그래프 잠금, 승인 때 요청한 스키마에서 재검증) — [스키마 단위 식별](schema-scoped-naming.md). 아래는 당시 결정 기록이다. D-26 은 그대로다.

- TABLE·VIEW 는 프로젝트 안에서 한 이름 공간이다 (대문자 비교). 스키마가 달라도 같은 이름은 하나뿐이다 : `SCHEMA_A.TB_USER` 가 있으면 `SCHEMA_B.TB_USER`(TABLE) 도 `SCHEMA_B.TB_USER`(VIEW) 도 안 되고, `SCHEMA_B.VW_USER` 는 된다.
- 이유 : 기존 테이블명이 프로젝트 안에서 하나이고 컬럼의 참조 테이블명 등이 이름으로 테이블을 찾는다. 실제 DBMS 는 스키마별로 같은 이름을 허용하므로 **현재 Lexorma 모델 구조에 맞춘 제약**이다.
- 적용 경로 (코드 확인 — 이름을 쓰는 곳은 이것뿐이다) : 테이블 등록·수정(이름 변경)·엑셀 업로드, VIEW 등록·수정(이름·스키마 변경)·변경 요청 승인(승인 권한자의 즉시 반영 포함). 테이블에는 변경 요청 구조가 없고 테이블 승인은 이름을 바꾸지 않는다 (확인만, 변경 없음). ERD 편집·추천 적용은 같은 테이블 API 를 쓴다.
- 후속 재검토 (M-4 원본 역공학·스키마 단위 모델링) : 스키마+이름 식별, 이름 기반 참조의 ID 전환, 엑셀·기존 API 호환, 기존 데이터 이관. 이번에는 전환하지 않았다.

### 12.2 D-26 — 승인 대기 이름은 선점하지 않는다 (확정)

- 변경 요청의 새 이름(`VW_USER → VW_MEMBER` 대기)으로 다른 VIEW·TABLE 을 등록하는 것을 막지 않는다.
- 승인할 때 확정 모델로 다시 검사한다. 그 이름이 먼저 확정되었으면 승인을 거절한다 : 확정 정의·요청 상태(`REQUESTED`)·다른 객체 모두 그대로 (트랜잭션 롤백, 일부 반영 없음). 메시지 "`<이름>` : 요청 뒤에 같은 이름의 테이블·VIEW 가 등록되어 승인할 수 없습니다. 요청을 반려하고 다른 이름으로 다시 요청해주세요." (영어 포함).
- 응답 코드는 **400 `SAVED_DATA_EXISTS`** 로 두었다 (409 아님) : 테이블·VIEW·인덱스의 이름 중복과 인덱스 변경 요청 승인의 재검증이 모두 이 코드를 쓴다. 409 는 지금 ERD 배치 버전 충돌·원본 연결 같은 다른 뜻으로만 쓰여, 같은 상황에 다른 코드를 섞지 않았다.
- 재시도 : 반려 → 다른 이름으로 요청 → 승인 (테스트).

### 12.3 D-27 — SQL 파서는 도입하지 않는다 (확정), 정적 검사의 오탐 개선

- 원인 : 1차 검사는 문자열·주석·따옴표 식별자 밖의 **모든 낱말**을 금지 목록과 비교했다. 그래서 `SELECT a AS update`, `SELECT id FROM t FOR UPDATE`, `CAST(x AS CHAR(10) CHARACTER SET utf8)` 같은 정상 SQL 도 거절했다.
- 수정 (`ViewDefinitionSql`, 같은 스캐너에 상태 하나) : 명령은 **문장이 시작할 수 있는 자리**에서만 본다 — 맨 앞, `(` 바로 뒤(하위 질의·CTE 본문), WITH 의 CTE 목록이 끝난 뒤(본 문장). `INTO` 만 자리와 관계없이 거절 (`SELECT … INTO`, `REPLACE INTO`). 나머지 규칙(빈 값·길이·CREATE VIEW 전체·`;` 뒤 다른 문장·SELECT/WITH 시작·닫히지 않은 따옴표·주석·괄호)은 그대로.
- `FOR UPDATE` : PostgreSQL 18 은 VIEW 정의로 받는다 (임시 DB 에서 `CREATE VIEW … FOR UPDATE` 실행으로 확인) → 거절하지 않는다. 다른 DBMS 에서 되는지는 DDL 을 실행할 때 DB 가 판단한다 (명백히 안 된다고 확인한 DBMS 가 없어 별도 오류를 두지 않았다).
- 허용 예 (테스트) : 일반 SELECT, WITH CTE·여러 CTE, 중첩 하위 질의, `'DELETE FROM tb_user'` 문자열, `-- UPDATE …` 주석, `"update"` 인용 식별자, 끝의 `;`(여러 개·뒤 주석), 문자열 안 `;`, 달러 인용 `$$; DELETE …$$`, `AS update`, `t.update`, FOR UPDATE, CHARACTER SET, `REPLACE()`, `(SELECT …) UNION ALL (SELECT …)`. MySQL `\'`·`#`·`` ` ``, PostgreSQL 겹친 주석.
- 거절 예 (테스트) : `SELECT …; DELETE …;`, `SELECT 1; 'x'`, `CREATE VIEW … AS …`, `DELETE`·`UPDATE`·`INSERT` 문, `WITH d AS (DELETE …)`, `WITH a AS (…) INSERT …`, `SELECT … INTO`, `SELECT (DROP …)`, `VALUES (1)`, 닫히지 않은 `'`·`"`·`(`·`/*`·`$$`, 짝 없는 `)`.
- 한계 (변함 없음) : 문법·참조 객체·출력 컬럼·DBMS 호환성·실행 성공·성능·완전한 읽기 전용성(함수 안의 부작용 등)은 보장하지 않는다. 검사를 통과했다고 안전하거나 실행된다고 표시하지 않는다. 원문 보존·실행 금지 정책 그대로.

### 12.4 잠금 구조와 동시성

- 이름 공간 잠금 = 프로젝트 기본 스키마 행 배타 잠금 (`ObjectNamespace.lock`). 순서 : **이름 공간 → 테이블·VIEW 행** (모든 경로 동일). 커밋 직전 프로젝트 행 확인(`ProjectWriteGuard`)은 다른 쓰기와 같이 마지막.
  - 잡는 경로 : 테이블 등록·수정·엑셀 업로드·삭제, VIEW 등록·수정·삭제·변경 요청 처리. 잡지 않는 경로 : 테이블·VIEW 승인/승인 취소(이름·참조를 바꾸지 않음, 행 잠금만), 인덱스·제약조건·관계·매핑(테이블 행 잠금만) — 이들은 이름 공간을 잡지 않으므로 역순 대기가 생기지 않는다.
  - 1차의 H2 교착(프로젝트 행을 먼저 잡던 순서)은 위 순서로 고친 뒤 재현되지 않는다 (`ModelIntegrityHardeningIntegrationTest` H2·PostgreSQL 통과).
  - 범위 : 프로젝트 단위라 같은 프로젝트의 이름·참조 쓰기는 차례로 처리된다 (짧은 트랜잭션. 엑셀 업로드는 끝날 때까지 잡는다 — 그동안 같은 프로젝트의 테이블·VIEW 등록이 기다린다).
- 시나리오 (`ViewNamePolicyIntegrationTest`, `ViewManagementIntegrationTest`) — **H2·PostgreSQL 18 모두 통과, 교착 실패 없음** :

  | 시나리오 | 결과 |
  |---|---|
  | 같은 이름 VIEW 3건 동시 등록 | 1건 성공, 2건 `SAVED_DATA_EXISTS` |
  | VIEW 등록 ↔ 같은 이름으로 테이블 이름 변경 | 1건 성공 |
  | 서로 다른 VIEW 의 같은 새 이름 요청 2건 동시 승인 | 1건 반영, 다른 쪽은 원래 이름·정의 그대로 |
  | 이름 변경 승인 ↔ 같은 이름 VIEW 등록 ↔ 같은 이름으로 테이블 이름 변경 (3건 동시) | 정확히 1건 성공, 나머지 `SAVED_DATA_EXISTS`, 그 이름의 객체 1개 |
  | 같은 요청 3번 동시 승인 | 1건 처리, 2건 "이미 처리된 변경 요청" |
  | 참조를 더하는 승인 ↔ 참조 대상 VIEW 삭제 | 하나만 성공, 지워진 대상을 가리키는 참조 0 |
  | 서로를 참조하게 하는 요청 2건 동시 승인 | 1건만 반영 (순환 없음) |
  | 이름 충돌 뒤 재시도 | 반려 → 다른 이름 요청 → 승인 |

### 12.5 권한·브라우저·회귀 검증

| 항목 | 결과 |
|---|---|
| HTTP 권한 (`ViewAuthorizationHttpTest`, 실제 서버·세션·CSRF, H2·PostgreSQL) | USER : 조회 6종 200, 등록(승인 대기)·미승인 수정·삭제·변경 요청·DDL 미리보기 200, 요청 승인/반려·VIEW 승인 400 `APPROVAL_PERMISSION_DENIED`, 승인된 VIEW 삭제 400 `APPROVED_DATA_CANNOT_BE_DELETED`, 대기 중 확정 정의 유지. VIEWER : 조회 6종 200(정의 SQL·출력 컬럼·참조 객체·DDL·ERD), 등록·수정·삭제·승인·요청 처리·미리보기 403 `ACCESS_DENIED`. 다른 프로젝트(관리자 역할) : 상세·DDL·수정·삭제·승인·요청 처리·다른 프로젝트 테이블 참조 400 `INVALID_PHYSICAL_MODEL`, 목록·후보·ERD 비어 있음. 비로그인 401. `/api/auth/me` 의 `view-manage`·`physical-model-edit`·`approvalAvailable` 이 역할과 맞다 |
| 브라우저 (로컬 Chrome 157 헤드리스·DevTools, `h2-local`, 프로젝트 LEXORMA, 가입·역할·멤버 승인은 앱 API) | USER : 메뉴, 목록 [DDL 내보내기]·[추가], 상세 버튼 DDL 보기·ERD 에서 보기·삭제(비활성)·변경 요청, 승인 버튼 없음, 변경 요청 폼(승인된 VIEW 안내·SQL 변경 시 출력 컬럼 확인 안내·DDL 미리보기) → 확인 → "변경 요청을 만들었습니다" → 상세에 변경 요청 라벨, 확정 SQL 그대로, 승인/반려 버튼 없음, 등록 폼 열림, ERD VIEW 노드·편집 모드 버튼. VIEWER : 메뉴, [추가] 없음, 상세 버튼 DDL 보기·ERD 에서 보기뿐, 정의 SQL·출력 컬럼·참조 객체·변경 요청 이력 표시, DDL 보기, ERD VIEW 노드(편집 모드 버튼 없음). ADMIN : 요청 전후 비교에서 바뀐 줄은 "정의 SQL" 하나(나머지 6줄 표시 없음), 승인 → 새 SQL 반영. 콘솔 오류 0, 네트워크 4xx 는 의도한 초기 비밀번호 401 뿐. Claude in Chrome 확장 창은 이 PC 의 localhost 에 닿지 않아 쓰지 않았다 (1차와 같음). 반응형·다크·영어는 이번에 바꾼 화면이 없어 반복하지 않았다 (1차 10장) |
| 승인·이력 회귀 | 미승인 직접 수정, 변경 요청, 대기 중 유지, 원자적 승인, 반려, 충돌 시 보존, 이력(VIEW + 출력 컬럼·참조 배열) — `ViewManagementIntegrationTest`. 기록기 일반화 후 테이블·인덱스·관계·제약조건·논리 이력 테스트(`ModelChangeHistoryIntegrationTest` 등) 통과 |
| 모델 버전 | FORMAT 4, FORMAT 3 스냅샷과 비교(테이블 변화 없음, VIEW 만 추가), VIEW 추가·SQL·컬럼·참조 변경 — 통과. `ModelVersionIntegrationTest` 회귀 통과 |
| DDL | 네 DBMS 생성 결과 고정 (`ViewDdlGeneratorTest` 신규 : 이름·스키마·원문·`;`·COMMENT·참조 순서·경고), PostgreSQL 실행 (`ViewPostgresDdlTest`), 승인 대기 미반영, 테이블·인덱스 DDL 골든 파일 동일 |
| 마이그레이션 | V0~V5 변경 없음, V6 도 1차 이후 변경 없음(체크섬 그대로, repair 없음). 신규 `ViewMigrationV6Test` : V5 설치(사용자 프로젝트·테이블 포함) → V6 에서 기존 테이블·인덱스 그대로, 예제 테이블 4·인덱스 8 만 추가, VIEW 행 없음, 재실행 0건·검증 통과 / 예제를 지운 설치에서도 실패 없이 예제만 건너뜀 — H2·PostgreSQL 18. 새 설치·재시작은 `V0SeedMigrationTest`·`LexormaIndexSeedV5Test` |

| DBMS | 생성 | 실제 DB 검증 |
|---|---|---|
| PostgreSQL | 지원 | 검증 완료 (18.6, `ViewPostgresDdlTest`) |
| Oracle | 지원 | 미검증 |
| MySQL | 지원 | 미검증 |
| MariaDB | 지원 | 미검증 |

### 12.6 빌드

| 항목 | 결과 |
|---|---|
| `./gradlew build` (npm ci·typecheck·Vite 빌드 포함) | 통과 |
| H2 전체 (`test --rerun`) | 593건, 실패 0, 스킵 21 — PostgreSQL 전용 테스트(검증 DB 미지정) 8, 성능 2, 원본 DB 수집 11(원본 DB 환경 변수) |
| PostgreSQL 18.6 (로컬 컨테이너 임시 DB, 끝나고 삭제 — 개발 DB `lexorma` 미사용) | 전체 589건 통과(스킵 13 : 성능·원본 DB) + 이후 추가한 `ViewMigrationV6Test` 4건 통과 |
| 1차 대비 | 542 → 593 (+51 : SQL 검사 40, DDL 2, 이름 정책·동시성 4, HTTP 권한 1, V6 업그레이드 4). 삭제·비활성화한 테스트 없음 |

### 12.7 완료 판단과 잔여

M-15 1차 완료 조건(이름 공간·CRUD·정의 SQL·출력 컬럼·참조·DDL·승인·원자적 승인·이력·버전·ERD 조회·회귀 없음·H2/PostgreSQL·브라우저·문서)을 충족한다 → **M-15 1차 완료**. 아래는 완료 범위에 넣지 않은 후속이다 :

- Oracle·MySQL·MariaDB 실제 DDL 실행 검증
- 모델 버전 비교 ERD 의 VIEW 표시, ERD VIEW 노드 위치 저장
- 원본 VIEW 정의 SQL 수집·가져오기·원본 VIEW 와 물리 VIEW 비교 (AI-2 후속·M-4·M-10)
- 스키마 단위 TABLE/VIEW 식별자 재검토 (D-25 후속, M-4)
- 미승인 VIEW 직접 수정의 버전 충돌 검증 도입 여부 (결정 필요)
