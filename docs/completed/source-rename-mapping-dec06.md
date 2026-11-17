# DEC-06 원본 이름 변경 매핑

> 2026-11-16 구현 (4차 통합 구현). 정책 : [pending-work.md 7.2 DEC-06](../pending-work.md) · [policy-decision-review.md](../policy-decision-review.md) DEC-06.
> 원본에서 이름이 바뀐 TABLE·컬럼을 **사용자가 확인해** 기존 모델 객체와 잇는다. 이름 유사도로 자동 확정하지 않는다. 원본 DB 는 읽기 전용, 물리 모델(이름·승인 상태)은 바꾸지 않는다.
> 바뀐 이전 결정 : [source-compare-m4d.md 2장](source-compare-m4d.md) "영속 세부 매핑을 만들지 않음" → 컬럼 수준 매핑은 사용자 확정분만 저장한다 (비교 자체는 그대로 읽기 전용).

## 1. 저장 구조 (V4 `V4__source_rename_mapping.sql`, PostgreSQL·H2 같은 내용)

| 테이블 | 내용 | 제약 |
|---|---|---|
| `TB_SRC_COL_MPNG` 출처 컬럼 매핑 | TABLE 매핑(`SRC_TBL_MPNG_SN`) 안의 원본 컬럼 비교 이름(`CMPR_NM`)·확정 때 원본 이름(`COL_NM`) ↔ 모델 컬럼(`COL_SN`), 확정 때 반영 순서 | `UX_SRC_COL_MPNG_01 (SRC_TBL_MPNG_SN, CMPR_NM)`·`_02 (SRC_TBL_MPNG_SN, COL_SN)` — TABLE 매핑 안에서 1:1. `FK_SRC_COL_MPNG_01 → TB_SRC_TBL_MPNG ON DELETE CASCADE`, `FK_SRC_COL_MPNG_02 → TB_COL ON DELETE SET NULL` |
| `TB_SRC_MPNG_HSTRY` 출처 매핑 이력 | TABLE·컬럼 매핑의 확정(CONFIRM)·변경(CHANGE)·이름 변경(RENAME)·해제(RELEASE), 변경 전후 JSON, 변경 일시 | FK 없음 (해제 뒤에도 남는다). 추가만 한다 |

- 원본 컬럼은 행 ID 가 없다 (수집 버전 JSON 안에 있다) → TABLE 매핑 + 비교 이름으로 가리킨다.
- 이름이 같은 컬럼은 저장하지 않는다 (지금처럼 비교 때 이름으로 짝짓는다).
- 기존 데이터·`TB_SRC_TBL_MPNG` 는 바꾸지 않는다. 예제(프로젝트 0)에 새 용어 2(`SRC_COL_MPNG_SN`·`SRC_MPNG_HSTRY_SN`), TABLE 2·컬럼 25, 논리 엔터티·매핑, FK 관계 2(삭제 규칙 CASCADE·SET_NULL)·논리 관계 2, 인덱스 6(컬럼 8), 업무 설명을 넣었다 → 예제 인덱스 122·인덱스 컬럼 177, 마이그레이션 5.
- 정리 : 프로젝트 삭제(`ProjectDataCleanup`)가 두 테이블을 지운다. TABLE 매핑을 해제·연결 삭제로 지우면 컬럼 매핑은 DB 가 함께 지운다 (이력은 남는다).

## 2. 후보 (저장하지 않음)

- **TABLE** : 원본에서 사라진(원본 없음) TABLE 매핑 ↔ 같은 모델 스키마에 매핑된 원본 스키마의 **미매핑·예외 아님** TABLE. 근거는 컬럼 비교 이름이 겹치는 수 / 컬럼이 더 많은 쪽의 수 ≥ 0.5 (`TABLE_SCORE_MIN`). 한 매핑에 후보가 여럿이거나 한 원본이 여러 매핑의 후보면 `ambiguous`. TABLE 이름 자체의 유사도는 보지 않는다.
- **컬럼** : M-4d 비교의 이름 변경 후보(같은 타입·길이·NULL 허용) 중 COLUMN, 매핑됨 상태의 TABLE 만.
- 후보가 없는 원본 없음 TABLE 매핑은 `removedTables` 로 따로 보낸다 — DEC-04 삭제 후보와 이름 변경 후보를 나누는 근거.

## 3. 확정·변경·해제 (`SourceRenameService`)

- 권한·잠금 : 매핑 변경과 같다 — USER 이상 + 프로젝트 관리자·ADMIN(지금의 DB), 프로젝트(공유) → 원본 연결(공유) → 기준 연결 행(배타). 요청은 화면이 본 `connectionId`·`reflectedOrder` 를 보내고, 다르면 409 `SOURCE_COMPARE_STALE`.
- **TABLE 이름 변경** (`PUT /table`) : 원본 없음인 TABLE 매핑만, 모델 TABLE 이 있어야 하고, 새 원본은 마지막 반영 수집의 TABLE·매핑 행 없음·그 스키마가 모델 TABLE 의 스키마에 매핑됨. **같은 매핑 행**의 원본 쪽(ID·스키마·경로·비교 이름·생성 차수·반영 순서)만 바꾼다 → 모델 TABLE·컬럼 매핑 유지. 이력 RENAME.
- **컬럼 매핑** (`PUT /columns`) : 매핑됨 TABLE 매핑 안에서, 원본 컬럼은 마지막 반영 수집에 있고 모델 컬럼은 그 매핑의 모델 TABLE 컬럼이어야 한다. 이름이 같으면 거절(필요 없음). 잠근 뒤 같은 트랜잭션에서 그 TABLE 을 다시 비교해 원본 컬럼은 **원본에만**, 모델 컬럼은 **모델에만** 있을 때만 저장한다 (이미 짝지어진 컬럼과 부딪히는 매핑 = 409 `SOURCE_MAPPING_CONFLICT`). 같은 원본 컬럼의 기존 매핑은 바꾼다(CHANGE). 같은 모델 컬럼을 다른 원본 컬럼에 또 매핑하면 409.
- **해제** (`DELETE /columns/{id}`) : 행만 지운다 (다시 이름으로 짝짓는다). TABLE 매핑 해제·스키마 매핑 해제·TABLE 매핑의 모델 TABLE 변경은 그 컬럼 매핑을 지우고 RELEASE 이력을 남긴다.
- 승인 객체 : 매핑은 모델 객체를 바꾸지 않으므로 승인 여부와 무관하게 확정할 수 있다 (화면에 승인 표시). 승인 객체의 값 변경은 가져오기가 기존 규칙대로 막는다.
- 이력 : TABLE 매핑 확정·변경(`mapTable`)·해제·가져오기(TABLE:CREATE)가 만든 매핑도 기록한다. 변경 전후는 그때의 원본·모델 이름과 번호.

## 4. 비교·가져오기 연동

- `SourceModelComparator.Context.columnKey(columnId, name)` : 확정된 컬럼 매핑이 있으면 원본 컬럼 비교 이름, 없으면 이름을 접은 값. 컬럼 짝짓기, PK·UNIQUE·CHECK·FK(참조 쪽 포함)·인덱스 구성 컬럼이 모두 이 키를 쓴다.
- 확정 매핑으로 짝지어진 컬럼은 속성 `name`(원본 이름 ↔ 모델 이름)을 NOT_COMPARED + `RENAME_MAPPED` 로 보인다 — 상태에 넣지 않는다 (모델 이름을 원본에 맞추지 않는다).
- 매핑의 원본 컬럼이 사라지면 이름으로 되돌아가지 않는다 : 모델 컬럼은 모델에만 있는 컬럼이 되고 매핑은 "원본 없음" 상태 — 사용자가 바꾸거나 해제한다.
- 가져오기(M-4e·M-4f)의 `Work.key` 가 같은 키를 써서 매핑된 모델 컬럼의 속성을 고친다 (새 컬럼 추가 항목을 만들지 않는다). 확정하지 않은 이름 변경 후보는 지금처럼 추가 대상이 아니다(`RENAME_CANDIDATE`).
- 재가져오기 기준값(M-4f)은 TABLE key(`S`+원본 TABLE ID)로 찾으므로, TABLE 이름 변경을 확정하면 그 TABLE 의 이전 기준값은 이어지지 않는다 (처음 가져오기로 판정).

## 5. 상태 (조회 때 계산)

| 컬럼 매핑 상태 | 조건 |
|---|---|
| TABLE_INVALID | TABLE 매핑이 매핑됨이 아니다 (원본 없음·재확인·충돌·모델 없음) |
| MODEL_MISSING | 모델 컬럼이 지워졌거나(FK SET NULL) 매핑된 모델 TABLE 의 컬럼이 아니다 |
| SOURCE_MISSING | 마지막 반영 수집의 원본 TABLE 에 그 비교 이름의 컬럼이 없다 |
| MAPPED | 그 밖 |

## 6. API (`/api/sourceMapping/renames`, `SourceRenameRestController`, `@ProtectedLogBody`)

| 메서드 | 경로 | 권한 | 내용 |
|---|---|---|---|
| GET | `` | VIEWER | 후보 (TABLE·컬럼·후보 없는 원본 없음 TABLE) + 기준 |
| GET | `/columns?tableMappingId=` | VIEWER | 확정된 컬럼 매핑과 상태 |
| GET | `/history?tableMappingId=&page=&size=` | VIEWER | 매핑 변경 이력 (최근 것부터, 최대 200) |
| PUT | `/table` | USER + 관리자 | TABLE 이름 변경 확정 |
| PUT | `/columns` | USER + 관리자 | 컬럼 매핑 확정·변경 |
| DELETE | `/columns/{id}` | USER + 관리자 | 컬럼 매핑 해제 |

- 메뉴 권한은 기존 `source-mapping`(VIEWER)·`source-manage`(USER) + `projectManageable` 를 그대로 쓴다 (새 메뉴 ID 없음).

## 7. 화면 (원본 매핑 관리 > 이름 변경 매핑, `SourceRenamePanel.tsx`)

TABLE 이름 변경 후보(판단 근거·후보 여럿 표시·[이름 변경 확정]), 후보 없는 원본 없음 TABLE(삭제 후보) 안내, 컬럼 이름 변경 후보(승인 표시·[매핑 확정]), 확정된 컬럼 매핑(상태·[해제]), 매핑 변경 이력(쪽). 확정·해제는 확인 대화상자. 비교 화면의 속성 설명에 `RENAME_MAPPED` 문구. 한국어·영어.

## 8. 검증

| 항목 | 결과 |
|---|---|
| `SourceSyncIntegrationTest` 이름 변경 2건 (H2) | TABLE 후보·확정(같은 매핑 행)·409·원본 있는 매핑 거절, 컬럼 후보·충돌·같은 이름·없는 컬럼·중복 거절, 비교 SAME + `RENAME_MAPPED`, 인덱스 구성 SAME, 가져오기가 매핑된 컬럼의 타입을 고침(추가 항목 없음), 해제 → 후보 복귀, TABLE 해제 → 연쇄 삭제, 이력 순서·내용, 상태 SOURCE_MISSING·MODEL_MISSING, 다른 프로젝트 격리, 관리자 아닌 구성원 거절, 모델 무변경 |
| 시드·회귀 (H2) | `SmokeTest`(예제 표준화 품질 0건) · `V0SeedMigrationTest`·`LexormaIndexSeedTest`(H2) · `IndexManagementIntegrationTest` · `ProjectDeletionIntegrationTest` · `ExampleTermCodeDefinitionTest` · `MessagesCoverageTest` · `ModuleBoundaryTest` · 원본 관련 전체(`Source*`) 통과 |
| React `tsc -b` | 통과 |

- PostgreSQL 실행·기존 DB 증분 적용·브라우저 E2E 결과는 [completed-work.md](../completed-work.md) 의 4차 기록에 적는다 (이 문서를 쓴 시점에는 아직이다).


## 5차 보완 : 이름 변경 뒤 재가져오기 기준값 (2026-11-16)

- 재가져오기(M-4f) 기준값은 실행 요약의 TABLE 키(`S<원본 TABLE ID>`)로 찾는다. TABLE 이름 변경을 확정하면 매핑 행이 새 원본 TABLE 로 옮겨져 키가 바뀌므로, 매핑 이력의 RENAME 변경 전 `sourceTableId`(모델 TABLE 이 같은 것만)를 예전 키로 함께 본다 (`SourceImportService.renamedKeys`).
- 컬럼 이름 변경 매핑을 확정한 컬럼은 예전 가져오기가 예전 원본 이름(= 그때 짝지은 모델 컬럼 이름)으로 항목을 남겼으므로, 모델 컬럼 이름 항목도 함께 본다. 기준값은 같은 모델 객체 ID 의 IMPORT 이력에서만 꺼내므로 다른 객체와 섞이지 않는다.
- 검증 : `SourceRenameReimportJdbcTest` (실제 PostgreSQL 원본 : 가져오기 → TABLE·컬럼 이름·타입 변경 → 재수집 → 이름 변경 확정 → 재가져오기 SOURCE_CHANGED·기준값 있음 → 반영, 중복 TABLE 없음·컬럼 매핑 유지). 수정 전 코드로는 실패한다.
