# DEC-04 · DEC-05 · DEC-07 원본 동기화 확장 (삭제 · 교체 · COMMENT 비우기)

> 2026-11-16 구현 (4차 통합 구현). 정책 : [pending-work.md 7.2](../pending-work.md) DEC-04·05·07, 선행 [DEC-06 이름 변경 매핑](source-rename-mapping-dec06.md).
> 원본 DB 는 계속 읽기 전용이다 (DDL·DML 없음). 모두 **비교 → 후보 → 사용자 선택 → 검증 → 계획 미리보기 → 확정(잠금 뒤 재계획, planToken 이 같을 때만) → 모델 반영·이력** 이다.
> 바뀐 이전 결정 : [source-import-m4e.md](source-import-m4e.md)·[source-reimport-m4f.md](source-reimport-m4f.md) 의 "추가·갱신만 (삭제·교체 없음, 원본 COMMENT 가 비면 지우지 않음)" → 삭제는 별도 흐름(DEC-04), 교체·명시적 비우기는 가져오기 항목(DEC-05·07). 새 마이그레이션 없음.

## 1. DEC-04 원본 삭제 후보 선택 반영 (`SourceDeletionService`, `/api/sourceMapping/deletions`)

- **후보** (저장하지 않음)
  - TABLE : 원본 없음인 TABLE 매핑 중 **이름 변경 후보(DEC-06)가 없는 것**의 모델 TABLE. 이름 변경 후보가 있으면 삭제 후보에서 빼고 건수만 알린다 (`renameCandidates`).
  - 컬럼 : 매핑됨 TABLE 의 M-4d 비교에서 **모델에만 있는 컬럼** (이름 변경 후보 `RENAME_CANDIDATE`·확정된 컬럼 매핑은 해당 없음).
  - 기준은 기준 연결의 마지막 반영(COMPLETED) 수집이다. 실패·진행 중 수집은 반영되지 않으므로 삭제로 보지 않고, PARTIAL 수집의 범위 밖 객체는 원본 없음이 아니다 (M-4c 규칙 그대로).
- **고를 수 없는 이유** (`Blocker`) : APPROVED(승인 객체) · APPROVED_COLUMN(승인 컬럼이 있는 TABLE) · LOGICAL_MAPPED(엔터티·속성 매핑) · VIEW_REFERENCE · REFERENCED(다른 TABLE 의 관계가 이 TABLE 을 참조 — REF_COLUMN 관계 포함) · IN_USE(관계·인덱스·제약조건이 이 컬럼을 씀; 그 컬럼에서 자동으로 만든 REF_COLUMN 관계·COLUMN 인덱스는 함께 지워져 제외) · NOT_FOUND. 참조 무결성을 자동으로 고치지 않는다.
- **영향** (`Impact`) : 컬럼·인덱스·제약조건·나가는 관계 수, 업무 설명이 있는 객체 수, 표준 용어 연결 수, AI 설명 제안 수(제안은 지우지 않는다 — 다음 재검증에서 재검토 필요).
- **DEC-17 연동** : 기준 수집이 직전 같은 종류의 정상 수집보다 테이블·컬럼 수가 50% 미만이면(`SourceCatalogReader.dropSuspected`, 수집 화면 경고와 같은 계산) 후보·계획에 `dropSuspected` 를 보이고, 확정은 `acknowledgeDrop=true` 없이는 거절한다 (400 `SOURCE_MAPPING_INVALID`).
- **확정** : USER + 프로젝트 관리자·ADMIN. 잠금 순서는 가져오기와 같다 — 프로젝트 행 배타 → 원본 연결 공유 → 기준 연결 행 → 스키마 이름 공간(기본 스키마 먼저) → 대상 TABLE 행 → 그 컬럼 행, 프로젝트 잠금 뒤의 행 잠금은 NOWAIT(바로 못 잡으면 409). 잠근 뒤 다시 계획해 planToken(기준·항목·이유·영향의 SHA-256)이 다르면 409 `SOURCE_COMPARE_STALE`.
  - 반영 직전 프로젝트 전체 모델 버전 `DELETE-<요청 ID>` (같은 요청을 다시 보내면 409), 변경 이력은 그룹 = 요청 ID 로 묶는다(`markImport`, 출처 IMPORT).
  - 삭제는 기존 서비스를 쓴다 : 컬럼 = `ColumnManageService.deleteColumn`(승인·사용 검사, 참조 관계 재동기화), TABLE = 나가는 관계 → 제약조건 → 인덱스 → 컬럼 → `TableDesignService.deleteTable`(엔터티 매핑·VIEW 참조·승인 검사). 지운 TABLE 의 원본 매핑은 해제 이력(`TB_SRC_MPNG_HSTRY` RELEASE)과 함께 지운다.
  - 한 트랜잭션이라 중간 실패는 전체 롤백이다. 한 요청 최대 200 항목.
- **화면** : 원본 매핑 관리 > 원본 삭제 반영 (`SourceDeletionPanel.tsx`) — 후보 표(대상·사라진 원본·함께 사라지는 것·지울 수 없는 이유), 급감 경고와 확인 체크, [삭제 계획 미리보기] → [삭제 확정](확인 대화상자, 결과에 모델 버전 이름).

## 2. DEC-05 제약조건·인덱스 교체 (가져오기 항목 `…:REPLACE`)

- 이름으로 짝지은 PK·UNIQUE·CHECK·FK·인덱스의 비교 상태가 MODIFIED(구성이 다름)면, 지금처럼 `<종류>:<이름>` 항목은 REPLACE_REQUIRED 로 고를 수 없고, **따로** `<종류>:<이름>:REPLACE`(Action REPLACE) 를 고를 수 있다. UNKNOWN(식 차이 등 판단 불가)·RENAME_CANDIDATE·CONFLICT 는 교체 대상이 아니다.
- 고를 수 있는 조건 : 모델 TABLE·그 객체가 미승인 (승인 객체는 APPROVED — 승인 취소·인덱스 변경 요청 절차), 새로 만들 때와 같은 검사(이름·인덱스 유형·FK 참조 대상·규칙 표기·컬럼 의존). PK 는 바뀌는 PK 컬럼·지금 PK 컬럼이 미승인이고, **다른 TABLE 의 관계가 이 TABLE 을 참조하지 않아야** 한다 (REFERENCED — 그 관계가 무효가 된다).
- 반영 : 같은 트랜잭션에서 기존 모델 객체를 지우고(flush) 원본 구성으로 **같은 모델 이름**으로 다시 만든다 (PK 는 모든 컬럼의 PK 표시를 끄고 원본 PK 컬럼을 켠다, NOT NULL). 모델 변경 이력에 삭제 + 생성으로 남는다. 계획 → 확정의 planToken·잠금·직전 모델 버전(`IMPORT-<요청 ID>`)은 M-4e 그대로다.

### 2.1 판단 불가(UNKNOWN) 객체 정책 (7차 확정, 2026-11-17)

정책 : 구성 차이를 안전하게 판단할 수 없는 객체(CHECK·인덱스 조건·표현식처럼 정규화해 같다고 할 수 없는 식, DBMS 별 표현 차이, 정규화할 수 없는 정의)는 **자동으로 교체하지 않는다**. 임의 SQL 식 비교 엔진은 만들지 않았다 — 비교 엔진 개선은 별도 작업(pending-work 7.3 DEC-05 행).

- 가져오기 후보 : UNKNOWN 객체에는 `…:REPLACE` 항목이 없고, `<종류>:<이름>` 항목은 사유 `UNKNOWN` 으로 고를 수 없다(계획에 넣어도 적용할 수 없다). 기존 모델 객체는 그대로 둔다.
- 수동 검토용 정보 : 항목의 `source`(원본 정의)와 함께 **`before` 에 지금 모델의 정의**를 준다 (7차, `SourceImportService.constraintOrIndex`).
- 화면 : 가져오기 패널에 **[수동 검토 필요 N개]** 묶음을 따로 둔다 (예전에는 접힌 "가져올 수 없는 항목" 목록 안에 섞여 있었다) — 객체, 원본 값, 현재 모델 값, "판단 불가 — 자동 교체하지 않음" 과 사유 안내(식 비교 불가·DBMS별 표현 차이·정규화할 수 없는 정의라 자동 교체하지 않고 기존 객체를 그대로 둔다, 두 정의를 비교해 필요하면 모델 관리에서 직접 고친다). 비교 화면의 기존 "판단 불가" 표시·`EXPRESSION` 근거는 그대로.
- 검증 : `SourceSyncIntegrationTest.replaceUniqueCheckForeignKey` 에 UNKNOWN CHECK(`(id >= 1)` ↔ 모델 `(id > 0)`)의 사유 UNKNOWN·고를 수 없음·`source`/`before` 정의·그 항목만 골라 계획하면 적용 불가·반영 뒤 모델 식 그대로를 더했다 (H2·PostgreSQL). 브라우저 (7차, 단일 JAR·PostgreSQL 임시 앱) : 실제 PostgreSQL 원본(임시 DB)의 `ck_customer_id CHECK (id >= 1)` ↔ 모델 `CK_CUSTOMER_ID (id > 0)` 을 교환 파일·SEC-1 허용 규칙·연결·수집·스키마/TABLE 매핑으로 만들고 비교 화면 가져오기 패널을 열어 [수동 검토 필요 1개] 묶음(원본 `expression=(id >= 1)`, 현재 모델 `expression=(id > 0)`, 사유)을 확인 — 한국어·다크·1440 / 영어·라이트·390, 가로 넘침 0, 콘솔 오류 없음. 이 묶음에서는 동작(추가) 표시를 빼고 보인다 (아무것도 하지 않으므로).

## 3. DEC-07 원본 COMMENT 명시적 비우기 (가져오기 항목 `…:COMMENT_CLEAR`)

- 원본 COMMENT 가 없고 모델의 원본 COMMENT 칸(`SRC_ANTN_CN`)에 값이 있을 때 : 기존 `TABLE:COMMENT`·`COLUMN:<c>:COMMENT` 는 그대로 고를 수 없다 (EMPTY_COMMENT — 빈 값으로 자동으로 지우지 않는다). 대신 `TABLE:COMMENT_CLEAR`·`COLUMN:<c>:COMMENT_CLEAR`(Action CLEAR, 변경 전 = 지금 값, 변경 후 = 없음)를 따로 고를 수 있다.
- 승인 TABLE·컬럼은 APPROVED (승인 취소 후 — 현행 규칙). 재가져오기 판정(M-4f)은 COMMENT 와 같은 기준값(마지막으로 COMMENT·COMMENT_CLEAR 를 반영한 실행)으로 MODEL_EDITED·BOTH_CHANGED 를 가른다.
- 원본 COMMENT 칸만 비운다 : 업무 설명(`TASK_EXPLN`)·DDL 설명(`COL_EXPLN`·`TBL_KORN_NM`)은 바꾸지 않는다.

## 4. 검증 (H2)

| 테스트 | 내용 |
|---|---|
| `SourceSyncIntegrationTest.deletionFlow` | 이름 변경 후보 TABLE 제외, 후보·이유(승인·참조·사용 중)·영향(업무 설명), 막힌 계획 applicable=false, 다른 프로젝트 후보 없음, 관리자 아닌 구성원 거절, 계획 뒤 모델 변경 → 409·모델 무변경, 확정 → TABLE·컬럼 삭제·막힌 것 유지·매핑 해제·직전 버전·DELETE 이력·매핑 해제 이력, 같은 요청 재적용 409 |
| `SourceSyncIntegrationTest.deletionNeedsDropAcknowledge` | 급감 수집 → dropSuspected, 확인 없이 확정 400·모델 무변경, 확인하면 삭제 |
| `SourceSyncIntegrationTest.replaceConstraintsAndIndexes` | 인덱스·PK 구성 변경 → REPLACE_REQUIRED + 교체 항목, 참조되는 PK REFERENCED, 승인 인덱스 APPROVED, 교체 반영(같은 이름·새 구성, PK ID·EMAIL), 인덱스 DELETE 이력, 다시 비교 SAME |
| `SourceSyncIntegrationTest.commentClear` | EMPTY_COMMENT 유지, CLEAR 항목(전·후 값), 승인 컬럼 APPROVED, 반영 → 원본 COMMENT 칸만 비움(업무 설명·DDL 설명 그대로), 비운 뒤 항목 없음 |
| 회귀 | `SourceImportIntegrationTest` 7 · `SourceReimportIntegrationTest` 4 · `SourceCompareIntegrationTest` 5 통과 |

| `SourceSyncIntegrationTest.replaceUniqueCheckForeignKey` (5차) | UNIQUE 구성·CHECK(단일 리터럴)·FK 삭제 규칙 교체, 판단 불가 CHECK 제외, 승인 UNIQUE APPROVED, 계획 뒤 모델 변경 409·무변경 |
| `SourceSyncIntegrationTest.replaceRollsBackWhenLaterItemFails` (6차, H2·PostgreSQL) | 한 계획의 UNIQUE·INDEX 교체에서 두 번째(INDEX) INSERT 를 테스트에서만 거는 임시 CHECK 로 실패 → 먼저 지나간 UNIQUE 교체까지 되돌아감(기존 구성), 직전 모델 버전·변경 이력·가져오기 기록(`TB_SRC_IMPRT_EXCN`)·매핑 이력 없음, 원인 제거 뒤 다시 계획·반영 성공. 운영 코드에 실패 주입 없음 |

- 5차 : DEC-07 비우기 화면 E2E(실제 PostgreSQL 원본, 한국어·영어). PostgreSQL·브라우저 결과는 [completed-work.md](../completed-work.md) 4·5차 기록을 본다. DEC-06 이름 변경 뒤 재가져오기 기준값은 [source-rename-mapping-dec06.md](source-rename-mapping-dec06.md).
