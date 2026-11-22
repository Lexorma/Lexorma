# M-7 보조 식별자 기반 UNIQUE 생성·보정 및 참조 FK (M7-AK-01~03)

2026-11-22 완료. 확정 정책 M7-AK-D01~D17 (사용자 확정, 작업 지시서). 새 테이블·컬럼·시퀀스·승인 필드·마이그레이션 없음 (V0·V1 그대로, 다음 스키마 변경은 V2), 모델 교환 형식·모델 버전 스냅숏 형식 그대로.

관련 기록 : [구조 정합성 진단 9장](model-mapping-quality-m7.md) (보조 식별자 ↔ UNIQUE 진단), [누락 FK 보정](physical-fk-correction-m7.md) (보정 계약·잠금 규칙), [논리 → 물리 생성 15장](logical-physical-mapping-m7.md) (관계 속성 짝·FK 생성).

## 1. 범위

| ID | 내용 |
|---|---|
| M7-AK-01 | 논리 → 물리 생성 때 **새로 만드는 테이블**에 보조 식별자마다 명시적 UNIQUE 제약조건 (단일·복합, 식별자 속성 순서, 미승인) |
| M7-AK-02 | 기존 물리 테이블의 누락 UNIQUE 보정 — `GET /api/modelMapping/quality/unique/status` (VIEWER), `POST …/unique/preview`·`…/unique/apply` (USER) |
| M7-AK-03 | 논리 관계 속성 짝이 부모의 보조 식별자 **전체**를 참조할 수 있다. 생성·FK 보정은 그 FK 를 **미승인 USER 관계**로 만든다 (단일도) |

일반 단일 PK FK 보정(`SINGLE_FK_HELD`)은 그대로 보류 — [M-8] REF_COLUMN 승인 구조.

## 2. 확정 정책 (D01~D17)

| ID | 정책 | 구현 |
|---|---|---|
| D01·D02 | 단일·복합 모두 명시적 UNIQUE 제약조건, 구성 순서 = 식별자 속성 순서 | `PhysicalModelService.createGeneratedUnique`, 고유값 컬럼(`UNQ_YN`)으로 만들지 않는다 |
| D03 | 필수 여부를 바꾸지 않는다. NULL 허용 구성 컬럼은 경고 `NULLABLE_COLUMNS` 만 | 생성 계획·보정 항목의 `warnings` |
| D04 | 진단은 순서까지 비교(기존 그대로), 중복 방지는 컬럼 **집합** 비교 | `PhysicalModelServiceImpl.rejectDuplicateUnique`, 보정의 `UNIQUE_DUPLICATE`·`UNIQUE_INDEX_DUPLICATE`·`COLUMN_UNIQUE_DUPLICATE`·`PRIMARY_KEY_DUPLICATE` |
| D05 | 같은 엔터티에 같은 속성 집합의 보조 식별자가 여럿이면 대표 = 식별자 ID 가 가장 작은 것 | `ReferenceKeys.representative`, 생성 `DUPLICATE_ALTERNATE_KEY` (SKIP), 보정 `DUPLICATE_ALTERNATE_KEY` (차단, 모델 전체 식별자 기준 — 고른 순서와 무관) |
| D06 | 생성은 새 테이블에만 UNIQUE 를 더한다. 기존 부모 테이블에 UNIQUE 가 없으면 FK 를 만들지 않는다 | 생성 관계 사유 `PARENT_UNIQUE_MISSING` |
| D07 | 보정 대상은 진단 `UNIQUE_MISSING` 만 (순서·구성 불일치·후보 여러 개는 고치지 않는다) | 보정 `NOT_MISSING` |
| D08 | 생성·보정한 UNIQUE 는 언제나 미승인 (ADMIN 도). 승인된 테이블·구성 컬럼은 막는다 | `APPROVED_TABLE`·`APPROVED_COLUMN` |
| D09 | 짝은 부모의 주 식별자(기존) 또는 부모 자체 보조 식별자 하나의 속성 전체. 일부·혼합은 저장 거절 | `ReferenceKeys.resolve`, `LogicalModelService.saveRelationshipPairs` |
| D10 | 생성·보정하는 FK 는 유효한 PK 또는 순서까지 일치(MATCH)하는 UNIQUE 를 참조하고, FK 컬럼 순서 = 그 UNIQUE 순서 | 생성 `UNIQUE_ORDER_DIFFERS`·`PARENT_UNIQUE_MISSING`, FK 보정 `PARENT_UNIQUE_MISSING`·`PARENT_UNIQUE_ORDER` |
| D11 | 보조 식별자 참조 FK 는 미승인 USER 관계 (단일도 — REF_COLUMN 은 PK 만 가리키고 언제나 승인 상태라서) | 생성 루프·FK 보정 `Representation.USER_RELATION` |
| D12 | DEC-09 유지 : 참조된 UNIQUE 를 지우는 것은 막지 않고, 남은 FK 는 진단만 (자동 삭제 없음) | 구조 판정 `FK_KEY_INVALID` (MISMATCH) |
| D13 | 화면은 기존 정합성 비교 | [보조 식별자·UNIQUE] 범위의 고르기·보정 판정 칸·미리보기 팝업 |
| D14 | 최대 100개, planToken, 변경 전 모델 버전, 원자적 | `uk-correction/1`, `UKFIX-<uuid>`, 적용 뒤 모두 MATCH 아니면 전체 롤백 |
| D15 | 대상 행만 잠근다. `saveConstraint` 의 확인→저장 사이 경합(TOCTOU) 제거 | §6 |
| D16 | 상속 B : 보조 식별자 UNIQUE 는 엔터티 **자체**의 것만. 상속 구조에 참여하는 부모의 보조 식별자를 참조하는 짝은 보류 | `LogicalHierarchy.participates` 이면 후보 없음 (저장 거절 메시지 별도) |
| D17 | 마이그레이션·교환 형식 변경 없음 | 짝 행(`TB_LGCL_REL_ATRB`)의 부모 속성 집합으로 참조 키를 해석 — 저장 구조 없음 |

## 3. 참조 키 해석 (`logical/service/ReferenceKeys`)

순수 함수. 짝의 부모 속성 집합으로 정한다 (짝 순서는 판정에 쓰지 않고 `ORDER_DIFFERS` 경고와 생성의 순서 검사에만).

1. 짝 없음 → `NONE`; 같은 부모 속성 두 번 → `PARENT_KEY_MISMATCH`
2. 집합 = 유효 주 식별자(서브타입이면 루트) → `COMPLETE` PRIMARY (같은 구성의 보조 식별자보다 먼저 — 기존 동작)
3. 집합 = 부모 자체 보조 식별자 → `COMPLETE` ALTERNATE (대표 = 최소 식별자 ID)
4. 주 식별자 없음 → `PARENT_KEY_MISSING`; 주 식별자 일부 → `PARTIAL` PRIMARY (기존 계약); 그 밖 → `PARENT_KEY_MISMATCH`

같은 해석을 논리 짝 상태(`LogicalRelationshipPairs`, 화면·ERD), 구조 정합성 판정(`ModelStructureQualityJudge`), 물리 생성, FK 보정, 모델 교환 가져오기 검사(`ModelExchangeService.validatePairs`)가 쓴다. `PairState` 에 `keyType`·`keyIdentifierId`·`keyIdentifierName`, 짝 편집 응답에 `parentKeys`(주 식별자 먼저, 보조 식별자는 ID 순)를 더했다. 화면의 짝 편집기는 [참조할 부모 식별자]를 고르고 그 식별자의 속성만 행으로 보인다.

## 4. 생성 (M7-AK-01·03, `PhysicalGenerationService`)

- 계획 `uniques` (`UniquePlan` : CREATE / SKIP + `PRIMARY_KEY_DUPLICATE`·`DUPLICATE_ALTERNATE_KEY`·`COLUMN_UNAVAILABLE`, `warnings`), 이름 = `DbIdentifierRules.uniqueName` = `UK_<테이블>_<컬럼…>` (일반 UNIQUE 등록의 기본 이름과 같은 함수), DBMS 식별자 진단에 UNIQUE 이름을 넣는다.
- 보조 식별자 속성은 제외할 수 없다 (요청하면 경고 후 포함).
- 반영 : 테이블·컬럼 → flush → UNIQUE (FK 보다 먼저) → FK. 결과 `uniques` 수.
- 보조 식별자 참조 관계 : 새 부모면 짝 순서 = 대표 식별자 순서여야 (`UNIQUE_ORDER_DIFFERS`), 기존 부모면 그 순서의 UNIQUE 제약조건(또는 단일 고유값 컬럼)이 있어야 (`PARENT_UNIQUE_MISSING`, 그 테이블의 UNIQUE 구성은 계획 근거). `refTableName` 은 쓰지 않고 단일도 `createGeneratedForeignKey` 로 미승인 USER 관계.
- planToken 형식 `v8` (UNIQUE 계획, 짝의 참조 키, 기존 부모 UNIQUE).

## 5. 누락 UNIQUE 보정 (M7-AK-02, `UniqueCorrectionService`)

- 대상 판정은 구조 정합성 결과(`ModelMappingQualityService.structureRows`)를 그대로 쓴다 — 진단을 다시 구현하지 않는다. 보정 가능 여부는 별도 검사 (`UniqueCorrectionDto.Code`) : `NOT_MISSING`, `MAPPING_MISSING`, `COLUMN_INVALID`, `PRIMARY_KEY_DUPLICATE`, `UNIQUE_DUPLICATE`, `UNIQUE_INDEX_DUPLICATE`, `COLUMN_UNIQUE_DUPLICATE`, `DUPLICATE_ALTERNATE_KEY`, `NAME_CONFLICT` (`DbIdentifierCheck` 이름 공간 + 같은 요청 안 — 다른 사유로 막힌 항목은 세지 않는다), `APPROVED_TABLE`, `APPROVED_COLUMN`.
- 적용 : 프로젝트 행 배타 → 논리 모델 행 → 테이블 행(ID 순) → 구성 컬럼 행(ID 순), 모두 NOWAIT (409) → 다시 계산·planToken 비교 (409) → 막힌 항목이 있으면 400 → `UKFIX-<uuid>` 버전 + `markChangeGroup` → UNIQUE 생성 → flush → 고른 식별자가 모두 MATCH 인지 확인 (아니면 전체 롤백).
- 화면 : 정합성 비교 [보조 식별자·UNIQUE] 범위에서 `UNIQUE_MISSING` 행만 고르기 (FK 보정과 따로 — 범위를 바꾸면 비운다), 보정 판정 칸, 상세의 판정, 미리보기 팝업 (`features/mapping/uniqueCorrection.tsx`).

## 6. 잠금 (D15)

- `saveConstraint` : `requireTable` → **`lockTable`** (테이블 행 대기 잠금) 뒤 같은 집합 중복 검사. 제약조건 삭제(`delete(CONSTRAINT)`)도 테이블 행을 먼저 잡는다.
- UNIQUE 보정·FK 보정·생성 반영은 프로젝트 행 배타 뒤 테이블 행을 NOWAIT 로 잡으므로, 커밋 전인 일반 등록·삭제와 겹치면 기다리지 않고 409.
- 수정 전(HEAD `e01c11a`, PostgreSQL 18.6) : 같은 컬럼 집합의 UNIQUE 등록 두 트랜잭션이 모두 성공해 같은 UNIQUE 가 2개 생겼다 (`OK / OK / count=2`; 순차 등록도 중복 검사 자체가 없어 2개). 수정 뒤 `AlternateKeyConcurrencyIntegrationTest` :

| 시나리오 | 결과 |
|---|---|
| C01 UNIQUE 등록 ↔ 같은 집합 UNIQUE 등록 | 둘째가 기다렸다가 `SAVED_DATA_EXISTS`, UNIQUE 1개 |
| C02 UNIQUE 등록(커밋 전) ↔ UNIQUE 보정 반영 | 보정은 기다리지 않고 409, 커밋 뒤 보정은 막힘, UNIQUE 1개 |
| C03 UNIQUE 보정 ↔ UNIQUE 보정 | 하나만 성공 (다른 쪽 409), UNIQUE 1개 |
| C04 부모 UNIQUE 삭제(커밋 전) ↔ 그 UNIQUE 참조 FK 보정 | 보정 409, 커밋 뒤 `PARENT_UNIQUE_MISSING` — 만드는 시점에 무효인 FK 없음 |
| C05 부모 UNIQUE 삭제(커밋 전) ↔ 같은 테이블 UNIQUE 등록 | 등록이 기다렸다가 성공, 교착 없음 |

## 7. 구조 정합성 판정 변경 (`ModelStructureQualityJudge`)

- 관계 행 `structure` 에 `referenceKey`(PRIMARY·ALTERNATE)·`referenceKeyId`·`referenceKeyName`.
- 보조 식별자 참조 관계는 `FK_NOT_EXPECTED` 가 아니라 FK 를 기대한다 (없으면 `FK_MISSING`).
- 구성이 일치하는 FK 라도 부모 컬럼이 지금 부모 테이블의 참조 가능한 키(PK·고유값 컬럼·UNIQUE·조건 없는 고유 인덱스 — DDL 생성기의 `referencesKey` 와 같은 범위)가 아니면 `FK_KEY_INVALID` (MISMATCH, DEC-09 사후 무효).
- 상속 구조 참여 부모는 보조 식별자 후보가 없다 (D16).

## 8. 검증

- 새 테스트 : `ReferenceKeysTest` 5, `AlternateKeyUniqueIntegrationTest` 12 (생성 UNIQUE·제외 불가·보조 식별자 참조 FK·DDL·순서 다름·기존 부모 UNIQUE 없음·UNIQUE 보정·차단 사유·409·요청 검사·`saveConstraint` 중복·FK 보정·DEC-09·짝 규칙·교환 왕복), `AlternateKeyConcurrencyIntegrationTest` 5 (PostgreSQL 전용).
- 전체·통합 : H2 전체 1,330 — 실패 0 · 건너뜀 82, PostgreSQL 18.6 빈 임시 DB 전체 1,330 — 실패 0 · 건너뜀 40 (두 전체 실행 뒤 교환 왕복 테스트 1개 추가·이름 문구 수정 — AK·FK 보정·구조 정합성·물리 생성·교환·메시지·SmokeTest 묶음을 H2·PostgreSQL 에서 다시 실행, 실패 0), PostgreSQL 동시성 5 (수정 전 HEAD 사본 재현 : 같은 집합 UNIQUE 동시 등록 두 건 모두 성공·2개 → 수정 뒤 둘째 대기 후 SAVED_DATA_EXISTS·1개), 생성 DDL 을 PostgreSQL 18.6 빈 DB 에서 실행(UNIQUE 2·UNIQUE 참조 FK 2 생성, FK 위반 거절), Chromium E2E 27/27 두 번(단일 JAR H2 단독 새 설치, Docker Compose PostgreSQL 새 설치 — 짝 저장·혼합 거절·생성 미리보기·반영·UNIQUE 보정 화면·보조 식별자 참조 FK 보정 화면·한/영·라이트/다크·1440/375 가로 넘침 없음·콘솔 오류 없음), 재시작(H2·Compose, 데이터·진단 유지, 마이그레이션 없음), 이번 작업 전 V0·V1 로 만든 H2 DB 를 새 JAR 로 기동(up to date), 성능(PostgreSQL 엔터티 300 구조 정합성 요약 48~52ms ≈ 수정 전 50~71ms, 쿼리 17→18 / 물리 생성 300×30 미리보기 같은 수준).
- 프런트엔드 : `npm run typecheck`(격리 사본) 통과, 새 한·영 키 52개 + 서버 메시지 37개 (`MessagesCoverageTest`).
- **미검증** : Chromium 외 브라우저, 짝 편집기의 [참조할 부모 식별자] 선택과 생성 미리보기 UNIQUE 구역의 화면 조작(API·타입 검사로만 확인), 이번 작업 전 V0·V1 로 만든 **Docker PostgreSQL** DB 의 기동(H2 로만 확인 — 이번 작업은 마이그레이션이 없다), 375 전체 페이지 캡처가 내부 스크롤 영역을 비워 찍는 문제(이번 작업 전 화면도 같음, 스크롤 캡처로 내용 확인).

## 9. 남은 것

- 상속 구조에 참여하는 부모의 보조 식별자 참조 (D16 보류), 서브타입 자체 보조 식별자의 상속 계층 안 의미.
- 일반 단일 PK FK 보정, REF_COLUMN 관계 승인 구조 — [M-8].
- 순서·구성이 다른 기존 UNIQUE 의 수정·교체 (D07 범위 밖).
- AI·MCP 는 참조 키 유형을 아직 노출하지 않는다.
