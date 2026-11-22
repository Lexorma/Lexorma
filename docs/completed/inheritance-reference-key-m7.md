# M-7 상속 공통 참조 키 판단 통일 · 상속 계층 자체 AK 참조 FK · 누락 상속 FK 보정 (M7-INH-01~03)

2026-11-22. 확정 정책 D-INH-01~12 (사용자 확정, 작업 지시서). 작업 기준 HEAD `462c34f`. 새 테이블·컬럼·관계 출처·마이그레이션 없음 (V0·V1 그대로, 다음 스키마 변경은 V2), 모델 교환 형식·모델 버전 스냅숏 형식 그대로.

관련 기록 : [보조 식별자 UNIQUE·참조 FK](alternate-key-unique-m7.md) (D16 보류 → 이 작업으로 해제), [누락 FK 보정](physical-fk-correction-m7.md) (보정 계약·잠금 규칙, FK-11 기대값 변경), [슈퍼타입·서브타입 물리 변환](subtype-physical-transformation.md) (B형 TABLE_PER_TYPE, 상속 PK 매핑).

## 1. 범위

| ID | 내용 |
|---|---|
| M7-INH-01 | 참조 키 후보 정책 공통화 (`ReferenceKeys.referenceable`·`inherited`), 정확한 상속 FK 판별 (D-INH-11 — 일반 FK 와 상속 FK 분리) |
| M7-INH-02 | 슈퍼타입·중간·최하위 서브타입 **자체** 보조 식별자(단일·복합) 참조 짝·생성·FK 보정 (상속받은 AK 는 참조 불가) |
| M7-INH-03 | 기존 서브타입의 누락 상속 FK 보정 — `GET /api/modelMapping/quality/inheritanceFk/status` (VIEWER), `POST …/preview`·`…/apply` (USER) |
| D-INH-12 | REF_COLUMN·USER 같은 컬럼 쌍 역방향 중복 방지 |

범위 밖 (후속) : 상속 A·C형 물리 생성과 그 참조 키 정책, 상속받은 AK 의 전략별 참조, 상속 PK 컬럼·매핑 자동 생성, 기존 FK·UNIQUE 수정/교체, 일반 단일 PK FK 보정·REF_COLUMN 승인 구조([M-8]), I-5(생성의 컬럼명 기준 PK 검증 ↔ 정합성의 ID 기준 차이 — 기록만), AI·MCP 상속 정보 노출.

## 2. 확정 정책 (D-INH-01~12)

| ID | 정책 | 구현 |
|---|---|---|
| D-INH-01 | 참조 키 후보 정책 공통화 | `ReferenceKeys.referenceable(parent, own)` — 짝 저장·짝 화면·ERD·정합성 판정·물리 생성·교환 검증이 같은 함수로 후보를 정한다 |
| D-INH-02 | 실제 물리 테이블에 있는 키만 참조 | 논리 해석은 물리를 보지 않는다. 생성은 새 부모 테이블의 생성 예정 UNIQUE 또는 기존 부모의 순서 일치 UNIQUE 만 (`PARENT_UNIQUE_MISSING`·`UNIQUE_ORDER_DIFFERS`), FK 보정은 부모 UNIQUE 진단 MATCH 요구 (`PARENT_UNIQUE_MISSING`·`PARENT_UNIQUE_ORDER`) |
| D-INH-03·04 | 슈퍼타입·서브타입 자체 AK 참조 허용 | 예전 `participates ? 빈 목록` 분기 5곳 제거 (3장) |
| D-INH-05 | B형에서 상속받은 AK 참조 불허 | 조상 AK 속성을 부모 속성으로 보내면 저장 거절 — "상속받은 보조 식별자는 현재 부모 엔터티의 참조 키로 사용할 수 없습니다. 해당 식별자를 직접 정의한 엔터티를 부모로 지정해 주세요." (한·영). 부모를 자동으로 바꾸지 않는다 |
| D-INH-06 | 루트 주 식별자 상속·직계 부모 참조 | 유효 주 식별자 = `LogicalHierarchy.root` 의 주 식별자 (기존), 상속 FK 는 직계 슈퍼타입 테이블만 |
| D-INH-07 | 단일 상속 FK 도 미승인 USER 보정 | `InheritanceForeignKeyCorrectionService` → `PhysicalModelService.createGeneratedForeignKey` (단일·복합 모두 USER, 미승인). 생성기의 단일 상속 FK(REF_COLUMN)는 그대로 |
| D-INH-08 | 기존 승인 제한 유지 | 승인된 자식 테이블·상속 PK 컬럼은 ADMIN 도 막는다 (`CHILD_TABLE_APPROVED`·`CHILD_COLUMN_APPROVED`), 부모 승인은 막지 않는다 |
| D-INH-09 | 전용 보정 API · 다단계 순차 보정 | 대상 = 서브타입 엔터티 ID, 상위 FK 가 없으면 `ANCESTOR_FK_PENDING` (선행 대상 이름·ID), 한 요청에서 연쇄 보정하지 않는다 |
| D-INH-10 | 상속 PK·매핑 누락은 자동 보정 제외 | 진단·차단만 (`INHERITED_KEY_MAPPING_MISSING`·`CHILD_KEY_MISMATCH`·`CHILD_KEY_NULLABLE`·`TYPE_INCOMPATIBLE`·`ANCESTOR_INVALID` …) |
| D-INH-11 | 정확한 상속 PK 쌍 기준 FK 판별, FK-11 계약 변경 | 4장 |
| D-INH-12 | REF_COLUMN·USER 역방향 중복 방지 | 6장 |

## 3. 참조 키 후보 공통화 (M7-INH-01·02)

`logical/service/ReferenceKeys` (순수 함수) :

- `referenceable(parentEntityId, ownAlternates)` = 부모가 **직접 정의한** 보조 식별자 (식별자 ID 순). 상속 참여 여부와 무관.
- `inherited(ancestors, ownAlternates)` = 조상(직계 → 루트)이 정의한 보조 식별자 — 참조 후보가 아니고 저장 경로의 안내에만 쓴다.
- `resolve` 는 그대로 (PK 집합 우선, 같은 구성 AK 는 최소 식별자 ID, 일부·혼합은 PARENT_KEY_MISMATCH).

적용한 호출 경로 (예전 `hierarchy.participates(…) ? List.of() : …` 분기 제거) :

| 경로 | 변경 |
|---|---|
| `LogicalModelService.keyLookup` (짝 판정 — 관계 목록·엔터티 관계·ERD part/neighbors·짝 화면이 모두 이 조회를 쓴다) | `referenceable` |
| `LogicalModelService.saveRelationshipPairs` | 부모 자체 AK 후보, 조상 AK 속성이면 D-INH-05 안내 |
| `LogicalModelService.pairsView` | `ParentKey.ownerEntityId/ownerEntityName` (정의 위치 — 서브타입 부모의 PK 는 루트, AK 는 부모 자신) |
| `ModelStructureQualityJudge.expected` | `referenceable` |
| `PhysicalGenerationService` 관계 계획의 KeyLookup | `referenceable` (서브타입 부모 자체 AK 속성은 부모 테이블의 직접 컬럼 — 기존 직접 매핑 경로) |
| `ModelExchangeService.validatePairs` | `referenceable` (상속 참여 원본 차단 제거) |
| `LogicalSubtypeService.requireReferencesKept` | 유효 주 식별자가 바뀌는 엔터티(서브타입으로 넣기)라도 짝이 그 엔터티 자체 AK 하나 전체면 막지 않는다. PK·기타 짝은 예전처럼 막는다 |

표시용 상속 식별자(`effectiveIdentifiers`)는 그대로이고 참조 후보와 다르다. 짝 편집기의 [참조할 부모 식별자]는 정의 위치를 함께 보인다 ("정의 : 당사자 (상속)", "자체 정의"). 보조 식별자는 여전히 엔터티 자신의 속성으로만 정의한다 (상속·자체 혼합 AK 는 범위 밖).

설계 제약 : 이 후보 정책은 B형(TABLE_PER_TYPE — 서브타입 테이블에 조상 AK 컬럼이 없다)을 전제로 한다. A·C형에서는 그대로 쓸 수 없을 수 있다 (전략별 별도 검토).

## 4. 상속 FK 판별 (D-INH-11)

### 4.1 정의

서브타입 S 의 **상속 FK 쌍** = 루트 주 식별자 속성마다 (S 문맥 컬럼 = 상속 PK 매핑, 직계 슈퍼타입 P 문맥 컬럼 = P 가 루트면 직접 매핑·아니면 P 의 상속 PK 매핑), 식별자 순서.

- **정상 상속 FK** : S 테이블 → P 테이블, 자식·부모 컬럼 쌍이 상속 FK 쌍과 **순서까지** 같다 (`ModelStructureQualityJudge.isExactInheritanceFk`). 출처(REF_COLUMN·USER)는 묻지 않는다.
- **상속 FK 후보** : S 테이블에서 나가는 FK 중 자식 컬럼 집합이 상속 PK 컬럼 집합과 같은 것 (`isInheritanceCandidate`, 참조 대상·순서 무관). 상속 PK 매핑이 모자라 쌍을 정할 수 없으면 자식 테이블 PK 컬럼 집합으로 P 테이블을 가리키는 FK 만 후보로 본다.

### 4.2 공통 판별 사용처

| 경로 | 동작 |
|---|---|
| `SubtypePlanContext.hasForeignKey` (기존 부모 재사용·상속 구조 진단) | 쌍 집합 비교 → **순서까지** 비교로 좁힘 |
| `SubtypePlanContext.foreignKeyDetail` (새, 순수) | 부모 FK 상세 : `INHERITANCE_FK_OK`·`_DUPLICATE`(정상 2개 이상 — WARNING)·`_MISSING`(후보 없음)·`_ORDER`·`_COLUMNS`·`_TARGET` |
| 상속 구조 진단 `PARENT_FK` 점검 | `Check.detail` 에 상세 코드 (화면·보정은 문구를 해석하지 않는다), `ModelMappingDto.StructureCheck.detail` |
| 관계 진단 (`ModelStructureQualityJudge.relationships`) | 상속 FK 후보는 일반 관계의 FK 후보(정확 일치·순서·대상·구성·여러 개·공유)에서 뺀다 (I-3) |
| 물리 기준 역방향 (`physicalForeignKeys`) | 상속 FK 후보만 뺀다 — 같은 두 테이블 사이의 다른 일반 FK 는 `PHYSICAL_ONLY_FK` 로 나온다 (I-4) |
| 일반 FK 보정 | 관계 진단이 `INHERITANCE_FK_OVERLAP` 일 때만 `INHERITANCE_LINK` 로 막는다 (토큰 `fk-correction/3`) |
| 상속 FK 보정 | 상세가 `INHERITANCE_FK_MISSING` 일 때만 보정 (5장) |

### 4.3 같은 구성 일반 관계 (29.3)

일반 논리 관계의 기대 쌍이 자식(서브타입)의 상속 FK 쌍과 정확히 같으면 FK 를 따로 대응시키지 않고 `INHERITANCE_FK_OVERLAP` (NOT_COMPARABLE) — 상속 FK 가 있든 없든. 한 FK 를 두 관계가 충족한 것으로 보지 않고, 일반 FK 보정이 상속 FK 를 우회 생성하지 않는다. 정상 데이터에서는 자식 속성이 상속 PK 컬럼에 직접 매핑될 수 없어(속성 매핑 ↔ 상속 PK 매핑 배타) 방어 분기이며, 순수 판정 단위 테스트로 확인했다.

### 4.4 FK-11 기대값 변경

`ForeignKeyCorrectionIntegrationTest.inheritance` : 당사자(슈퍼) → 조직(서브) 관계, 짝 당사자 번호 → 조직.상위 당사자 번호. 예전 기대 `FK_COLUMNS`(상속 FK 가 있을 때) / `INHERITANCE_LINK`(지운 뒤)는 "직계 부모 테이블로 가는 모든 FK = 상속 FK" 판정 때문이었다. D-INH-11 뒤 상속 FK(조직.PTY_NO → 당사자)는 이 관계의 후보가 아니므로 두 경우 모두 `FK_MISSING`, 보정 판정은 일반 단일 PK FK 보류 `SINGLE_FK_HELD`([M-8] 정책 유지). FK_MISSING 이 곧 보정 가능은 아니다.

## 5. 누락 상속 FK 보정 (M7-INH-03)

`mapping/service/InheritanceForeignKeyCorrectionService`, DTO `InheritanceForeignKeyCorrectionDto`, 화면 `features/mapping/inheritanceFkCorrection.tsx`.

| API | 권한 | 내용 |
|---|---|---|
| `GET /api/modelMapping/quality/inheritanceFk/status?modelId=&entityIds=` | VIEWER | 서브타입별 보정 가능 여부·사유·선행 보정 대상·기존 FK (토큰 없음, 최대 100 — 목록 한 쪽) |
| `POST …/inheritanceFk/preview` | USER | `{modelId, entityIds}` → `Plan {items, total, correctable, blocked, applicable, planToken}` (아무것도 저장하지 않는다) |
| `POST …/inheritanceFk/apply` | USER | `{modelId, entityIds, planToken}` → `Result {selected, created, relationIds, versionId, versionName}` |

- 판정은 상속 구조 진단과 같은 `SubtypePlanContext.inspect` 결과를 그대로 쓴다. 점검 코드 → 사유 : `KEY_COMPLETE`→`INHERITED_KEY_MAPPING_MISSING`, `COLUMN_OWNER`→`INHERITED_KEY_COLUMN_INVALID`, `PK_SET`→`CHILD_KEY_MISMATCH`, `NOT_NULL`→`CHILD_KEY_NULLABLE`, `PARENT_TABLE`→`PARENT_UNMAPPED`, `ANCESTOR_PATH`→`ANCESTOR_FK_PENDING`(상위가 상속 FK 만 없는 경우 — 가장 위 대상)·`ANCESTOR_INVALID`, `KEY_TYPE`→`TYPE_INCOMPATIBLE`, `PARENT_FK` 상세 ORDER·COLUMNS·TARGET → `FK_ORDER`·`FK_COLUMNS`·`FK_TARGET`, 정상 → `NOT_MISSING`, `ROOT_KEY_MISSING`, 그 밖 `STRUCTURE_INVALID`. 물리 충돌 : `CHILD_TABLE_APPROVED`·`CHILD_COLUMN_APPROVED`·`REF_COLUMN_CONFLICT`(상속 PK 컬럼에 참조 테이블명)·`RELATION_EXISTS`(같은 자식 컬럼 순서로 부모를 가리키는 관계, LOGICAL 포함)·`NAME_CONFLICT`(같은 스키마·같은 요청). 요청 검사 : 빈 목록·중복·101개·다른 모델/프로젝트 엔터티 400 (다른 프로젝트 모델은 404 성격 `LOGICAL_MODEL_IS_NOT_DEFINED`), 서브타입이 아니면 `NOT_SUBTYPE`, 매핑 없으면 `NOT_MAPPED`.
- 만들 FK : 상속 PK 매핑 컬럼(루트 주 식별자 순서) → 직계 부모 테이블 키 컬럼, 이름 `DbIdentifierRules.foreignKeyName(자식 테이블, 첫 컬럼)`, 미승인 USER.
- 계획 토큰 `inheritance-fk-correction/1` : 서브타입·직계 부모·그룹·루트 주 식별자 속성·자식/부모 테이블(ID·이름)·컬럼 쌍(ID·이름·타입)·진단·부모 FK 상세·판정 코드·만들 이름·선행 대상·막는 사유 코드·기존 FK 표시. 설명 변경은 토큰을 바꾸지 않는다.
- 적용 : 프로젝트 행 배타 → 논리 모델 행 NOWAIT → 자식·부모 테이블 행 NOWAIT(ID 순) → 키 컬럼 행 NOWAIT(ID 순) → 다시 계획·토큰 비교(다르면 409) → 막는 사유 400 → `INHFIX-<uuid>` 직전 모델 버전 + 변경 이력 그룹 → 생성 → flush → 새 판정에서 고른 서브타입이 모두 MATCH 아니면 전체 롤백. 일반 FK·UNIQUE 보정과 같은 잠금 (키를 바꾸지 않아 FOR UPDATE 강도 불필요 — 7장 실측).
- 화면 : 정합성 비교 [상속 구조] 범위에 고르기 칸(편집 권한)·[상속 FK 보정] 판정 칸·선택 막대(최대 100, 쪽·검색 이동에도 유지, 범위·모델이 바뀌면 비움 — 일반 FK·UNIQUE 와 다른 선택)·미리보기 팝업(선택·가능·불가 수, 자식 → 부모 테이블, 컬럼 쌍, 만들 FK 이름, USER·미승인, INHFIX 버전·원자 적용 안내, [보정 불가 항목 빼고 다시 미리보기], 409 뒤 새로 고침·자동 재시도 없음)·상세 대화상자의 판정. 상태 조회는 쪽의 일치하지 않는 서브타입만 한 번에 (행마다 부르지 않는다).

## 6. REF_COLUMN·USER 역방향 중복 방지 (D-INH-12)

- **USER → REF_COLUMN** (I-6 — 수정 전 재현됨) : `PhysicalModelServiceImpl.syncReferenceRelations` 가 REF_COLUMN 관계를 새로 만들거나 대상 키가 바뀔 때, 같은 자식 테이블의 다른 출처 FK 중 같은 컬럼 쌍(자식 컬럼 1개 → 같은 부모 PK 컬럼)이 있으면 `SAVED_DATA_EXISTS` 로 거절 (요청 전체 롤백). 대상이 그대로인 관계(이름만 따라가는 경우)는 보지 않으므로 예전 중복 데이터가 다른 저장·이름 변경을 막지 않는다. 기존 관계를 지우거나 바꾸지 않는다.
- **REF_COLUMN → USER** : 기존 규칙 그대로 (`buildRelation` 이 참조 테이블명 컬럼의 관계 등록을 거절 — 관계 등록·생성·FK 보정·상속 FK 보정 공통).
- 같은 자식 컬럼이라도 참조 컬럼이 다른 관계(AK 참조 USER + PK 참조 REF_COLUMN)는 막지 않는다.
- 직렬화 : 참조 테이블명을 쓰는 경로(단일 등록·일괄 저장·엑셀은 테이블 행을 먼저 잠근다, 이름 변경·생성·가져오기는 프로젝트 배타 + NOWAIT)와 관계를 만드는 경로(관계 등록은 자식 테이블 행을 먼저 잠근다, FK·상속 FK 보정은 NOWAIT)가 같은 자식 테이블 행에서 만난다 (7장 실측).
- 경로별 : 단일 컬럼 등록·엑셀은 **새 컬럼**만 만들므로 기존 USER 관계와 같은 쌍을 만들 수 없다 (엑셀은 기존 컬럼 이름이면 `SAVED_DATA_EXISTS`). 일괄 저장 = 거절·전체 롤백 확인. 교환 가져오기 = REF_COLUMN 은 동기화가 다시 만들므로 같은 검사를 거친다 (예전 중복이 있는 파일은 가져오기가 거절될 수 있다 — 자동 정리하지 않는다).

## 7. 동시성 (PostgreSQL 18.6 격리 임시 컨테이너, `InheritanceConcurrencyIntegrationTest`)

독립 스레드·트랜잭션·연결. A 를 잠금을 쥔 채 멈추고(상속 FK 보정 = FK 생성 직전, 다른 작업 = 서비스가 끝난 뒤 커밋 전) B 를 실행, `pg_locks`·`pg_stat_activity` 로 실제 대기를 확인. 시나리오마다 3회. 40P01 0건, 같은 FK 중복 0건, 부분 적용 0건.

| ID | 경합 | 결과 (3/3) |
|---|---|---|
| INH-C01 | 같은 상속 FK 보정 두 건 | 둘째가 `tb_prj` 행(ShareLock transactionid)에서 대기 → 409 `MODEL_CONFLICT`, FK 1개·INHFIX 1개 |
| INH-C02 | 보정 ↔ 같은 쌍 USER 관계 등록 | 보정 선행 : 등록이 `tb_tbl` 행에서 대기 → `SAVED_DATA_EXISTS` / 등록 선행 : 보정 NOWAIT 409, 버전 없음 |
| 36.1 | USER 등록 ↔ 같은 쌍 참조 테이블명 일괄 저장 | USER 선행 : 저장이 `tb_tbl` 행에서 대기 → `SAVED_DATA_EXISTS` / REF 선행 : 등록이 대기 → `INVALID_PHYSICAL_MODEL`(참조 테이블명 컬럼) — 같은 쌍 FK 1개 |
| 36.2 | 보정 ↔ 같은 자식 테이블 일괄 저장 | 보정 선행 : 저장이 `tb_tbl` 에서 대기 → 업무 거절(일괄 저장은 승인된 테이블만) / 저장 선행 : 보정 NOWAIT 409. 보정 대상은 미승인 테이블, 일괄 저장·엑셀 수정은 승인된 테이블만이라 같은 컬럼에 둘 다 성공하는 순서는 앱 경로로 만들 수 없다 |
| INH-C03 | 보정 ↔ 상속 그룹 변경 | 보정 선행 : 그룹 저장이 `tb_lgcl_mdl` 행(FOR NO KEY UPDATE)에서 대기 → 성공 / 그룹 선행 : 보정 NOWAIT 409 |
| INH-C04~06 | 보정 ↔ 자식 상속 PK 컬럼 삭제 (PK 삭제 = 구조 변경 경로) | 보정 선행 : 삭제가 `tb_prj` 에서 대기 → `RELATION_EXISTS` / 삭제(거절·잠금 보유) 선행 : 보정이 `tb_prj` 에서 대기 → 성공. 이 모델의 PK 컬럼은 매핑·관계에 쓰여 삭제가 항상 거절된다 |
| INH-C08 | 보정 ↔ 자식 테이블 승인 | 보정 선행 : 승인이 `tb_tbl` 에서 대기 → 성공(FK 는 미승인) / 승인 선행 : 보정 NOWAIT 409 |
| INH-C12 | 독립 작업 | 다른 프로젝트 : 대기 없이 둘 다 성공 / 같은 프로젝트 무관 테이블 저장 : 커밋 때 `tb_prj` FOR SHARE 로 대기 → 성공 (구조 변경 경로의 프로젝트 배타 — 기존 계약) |

추정으로만 남긴 것 (실측 안 함) : C05 부모 PK 변경, C07 매핑 변경(논리 모델 행 잠금 — C03 과 같은 경로), C09 물리명 변경·C10 신규 생성(프로젝트 배타 — C01 과 같은 경로), C11 AK 참조 FK 보정 ↔ UNIQUE 삭제(FK 보정 경로 — 기존 [alternate-key-unique-m7.md](alternate-key-unique-m7.md) 6장), 엑셀 업로드 경합(새 컬럼만 — 6장).

## 8. 검증

### 8.1 수정 전 재현 (I-1~I-6)

| ID | 수정 전 (HEAD `462c34f`, H2 + PostgreSQL 18.6 격리 사본) | 수정 후 | 회귀 테스트 |
|---|---|---|---|
| I-1 | 자체 AK 참조 짝이 슈퍼타입 지정 뒤 `PARENT_KEY_MISMATCH` (재현) | COMPLETE ALTERNATE, 생성 FK USER·MATCH | `InheritanceReferenceKeyIntegrationTest.supertypeKeepsOwnAlternateReference`·`subtypeKeepsOwnAlternateReference` |
| I-2 | 상속 참여 부모 AK 짝 저장 거절 ("아직 지원하지 않습니다" — 수정 전 JAR API 로도 확인) | 자체 AK 허용, 상속받은 AK 만 거절 | `ownAlternateReferences`·`rejectedPairs`·`generation` |
| I-3 | 슈퍼타입 → 서브타입 일반 관계 `FK_COLUMNS` (재현) | `FK_MISSING` (보정 판정은 `SINGLE_FK_HELD`) | `generalRelationshipIgnoresInheritanceForeignKey`, FK-11 |
| I-4 | 같은 두 테이블 사이 추가 일반 FK 가 역방향 진단 0건 (재현) | `PHYSICAL_ONLY_FK` 1건, 상속 FK 는 제외 | `extraGeneralForeignKeyListedAsPhysicalOnly`, 판정 단위 테스트 |
| I-5 | 생성(컬럼명 집합) ↔ 정합성(컬럼 ID) PK 판정 기준 차이 | 기록만 (변경 없음) | — |
| I-6 | USER 단일 FK 컬럼에 같은 대상 참조 테이블명 → `REF_COLUMN`(승인) 관계가 하나 더 생김 (H2·PostgreSQL 순차 재현) | `SAVED_DATA_EXISTS`, USER 관계 유지, 요청 전체 롤백 | `refColumnAfterUserRelationRejected`·`bulkRollbackAndDifferentKeys`, 동시성 36.1 |

### 8.2 테스트

- 새 테스트 : `InheritanceReferenceKeyIntegrationTest` 14, `InheritanceForeignKeyCorrectionIntegrationTest` 9, `InheritanceConcurrencyIntegrationTest` 8 (PostgreSQL 7 + 공통 1), `ReferenceKeysInheritanceTest` 1, `SubtypeForeignKeyDetailTest` 1, `ModelStructureQualityJudgeTest` +2 (중복 구성·순수 함수) — INH-T01~T60 대응은 각 테스트 이름에 적었다. `InheritancePerformanceTest` 2 (`LEXORMA_PERF=true` 일 때만).
- 기대값을 바꾼 기존 테스트 : `ForeignKeyCorrectionIntegrationTest.inheritance` (FK-11, 4.4), `ModelStructureQualityJudgeTest.inheritanceForeignKeySkipped` (I-4 — 같은 두 테이블 사이의 일반 FK 는 물리 전용). 그 밖의 기존 테스트 기대값은 바꾸지 않았다.
- 전체 : H2 `./gradlew test -PfrontendPrebuilt=true --rerun --continue` 1,366 — 실패 0 · 건너뜀 89. PostgreSQL 18.6 (격리 임시 컨테이너의 빈 DB) 같은 명령 1,366 — 실패 0 · 건너뜀 40 (`InheritanceConcurrencyIntegrationTest` 8 포함). 두 전체 실행은 마지막 제품 코드 기준이고, 그 뒤에는 문서·성능 측정 테스트(환경 변수 없으면 건너뜀)만 바꿨다.
- 생성 DDL 을 PostgreSQL 18.6 격리 스키마(테스트 DB 안, 트랜잭션 롤백)에서 실행 : PK 4·UNIQUE 5·FK 8 생성(다단계 상속 FK 2, 슈퍼·서브·최하위 자체 AK 참조 단일·복합 FK), 정상 입력 성공, 없는 부모 키 5건 거절(23503). 기타 DBMS 는 기존 DDL 골든 테스트 회귀만 (실제 실행 안 함).
- 프런트엔드 : `npm run typecheck`, 격리 사본 `npm ci`·`npm run build`(Vite) 성공 — 새 한·영 키 40, 서버 메시지 영문 20 (`MessagesCoverageTest`).

### 8.3 빌드·배포·호환

| 항목 | 결과 |
|---|---|
| 단일 JAR | 격리 사본 `:lexorma-app:bootJar` (제품 소스가 작업 트리와 같음을 diff 로 확인), PostgreSQL 로 기동·재시작 |
| Docker 이미지 | 격리 사본 `docker build -f Dockerfile` 성공 (임시 태그, 확인 뒤 삭제) |
| Docker Compose | 임시 Compose(새 이미지 → 격리 PostgreSQL DB) 기동·재시작 |
| 기존 V0·V1 H2 DB | 수정 전 HEAD JAR 로 격리 H2 파일 DB 생성·상속 B형 데이터 저장 → 새 JAR 기동 `up to date` → 누락 상속 FK 보정 2건·다단계 선행 판정·서브타입 AK 짝 저장 → 재시작 뒤 유지 (미승인 USER) |
| 기존 V0·V1 PostgreSQL DB | 수정 전 HEAD **JAR** 로 격리 PostgreSQL DB 생성(이미지 대신 JAR — 지시서의 "수정 전 이미지"와 다름) → 새 **이미지**(Compose)로 기동 `up to date`, 보정·짝 저장 → Compose 재시작 뒤 유지 |
| 마이그레이션 | 없음 (`flyway_schema_history` 2행 그대로) |

### 8.4 브라우저 E2E (Chrome — Claude in Chrome, 새 JAR + 격리 PostgreSQL)

확인한 것 : 정합성 비교 [상속 관계] 범위의 고르기 칸·[상속 FK 보정] 판정 칸(보정 가능 / 상위 먼저 / 이름 충돌)·선택 막대·[이 쪽의 보정 가능 서브타입 선택], 미리보기(선택·가능·불가 수, 자식 → 부모 테이블, 복합 RK1·RK2 순서, FK 이름, 미승인 USER), 미리보기 뒤 FK 이름 충돌을 만든 상태의 적용 → 409 안내·계획 폐기·[다시 미리보기] → 충돌 항목 보정 불가 → [보정 불가 항목 빼고 다시 미리보기] → 적용·INHFIX 안내·선택 비움, 다단계 순차(조직 보정 뒤 외국 조직이 보정 가능으로 바뀌고 다시 적용 → 모두 일치), 적용 버튼 두 번 눌러도 확인·적용 1회, 관계 속성 짝 편집기(서브타입 부모 : "주 식별자 · 정의 : 당사자 (상속)", "보조 식별자 AK_사업자번호 · 자체 정의", 상속받은 AK 없음), 물리 ERD(보정한 FK 포함 9 관계), 앱 재시작 뒤 미승인 USER 유지, 다크·라이트, 콘솔 오류 없음.

**미검증** : 영어 화면(언어가 브라우저 `navigator.language` 를 따르는데 이 브라우저는 한국어 — 영어 키 존재는 타입 검사로만), 375px(이 Chrome 창의 최소 폭이 606px — 606px 에서 가로 넘침 없음만 확인), 1440 외 다른 데스크톱 폭, Docker Compose 화면 조작(API 로만), 다른 브라우저, 짝 편집기의 저장 클릭(API·통합 테스트로만), 논리 ERD 화면.

관찰 : 목록의 [조회]는 판정 칸을 같은 조회 키로 다시 쓰므로 밖에서 바뀐 상태가 바로 보이지 않을 수 있다 (FK·UNIQUE 보정과 같은 기존 동작 — 미리보기·적용은 항상 서버가 다시 판정한다).

### 8.5 성능 (`InheritancePerformanceTest`, 같은 데이터·같은 방법, 수정 전 = HEAD 사본)

모델 : 상속 계층 60 × 5 + 자식 60 = 엔터티 360, FK 360. 워밍업 2~3회 뒤 5~20회 중앙값, SQL 수 = Hibernate `prepareStatementCount`/회. 메모리·잠금 대기 시간은 측정하지 않았다.

| 측정 | H2 수정 전 → 후 (ms / SQL) | PostgreSQL 18.6 수정 전 → 후 (ms / SQL) |
|---|---|---|
| 정합성 요약 | 31 → 32 / 29 → 29 | 63 → 65 / 29 → 29 |
| 정합성 전체 | 27 → 28 / 29 → 29 | 55 → 38 / 29 → 29 |
| 상속 구조 | 18 → 18 / 16 → 16 | 42 → 24 / 16 → 16 |
| 관계 | 7 → 7 / 14 → 14 | 11 → 11 / 14 → 14 |
| 컬럼 일괄 저장 (참조 테이블명 컬럼 설명) | 2 → 3 / 21 → 21 | 7 → 7 / 21 → 21 |
| 관계 등록 | 1 → 1 / 16 → 16 | 4 → 4 / 16 → 16 |

새 기능 (H2 / PostgreSQL) : 상태 100 — 12 / 17ms (SQL 19), 미리보기 1·50·100 — 6·9·11 / 9·11·14ms (SQL 17~19, 대상 수에 비례하지 않음), 적용 100 — 499 / 711ms (SQL 784, INHFIX 버전 1, 적용 뒤 재판정 포함). 역방향 중복 검사는 REF_COLUMN 을 새로 만들거나 대상이 바뀔 때만 쿼리 1개를 더한다 (대상이 그대로인 일반 저장은 SQL 수 같음). 엑셀 1,000·2,000건, 독립 테이블 동시 저장 처리량은 따로 재지 않았다 (엑셀 다중 청크·독립 저장 회귀는 기존 테스트가 전체 실행에서 통과).

### 8.6 환경

작업 시작·종료 HEAD `462c34f` (커밋·스테이징 안 함). V0·V1 변경 없음, 새 마이그레이션 없음. 개발 DB·개발 볼륨 접근·복사 없음. 격리 사본 : `git archive HEAD`(수정 전) + rsync(제외 `/lexorma/volume/`·`/docker/volume/`·`/.git/`·`**/build/`·`**/node_modules/`·`**/.env`·`/lexorma-web/dist/`·`/.gradle/`·`**/.idea/`·`/.claude/`, dry-run 확인). 저장소 `lexorma-web/dist`·`build.gradle` 변경 없음. 만든 임시 리소스(PostgreSQL 컨테이너 `lexorma-inh-pgtest`·Compose 스택 `lexorma-inh-test`·네트워크·이미지 `lexorma/lexorma:inh-m7-test`·앱 프로세스·작업 사본·H2 파일)는 모두 정리했다.

## 9. 남은 것

- 상속 A·C형 생성과 그 참조 키 정책 (B형의 자체 AK 후보 정책을 그대로 쓸 수 있다고 단정하지 않는다), 상속받은 AK 의 전략별 참조.
- 상속 PK 컬럼·매핑 자동 생성/보정, 잘못된 기존 상속 FK·UNIQUE 의 수정·교체 (진단만).
- 일반 단일 PK FK 보정, REF_COLUMN 관계 승인 구조 — [M-8].
- I-5 : 생성(`PhysicalGenerationService` 기존 부모 PK 검사 — 컬럼명 집합)과 정합성(컬럼 ID) 기준 차이 — 기록만, 수정하지 않았다.
- AI·MCP 는 상속·참조 키 유형을 노출하지 않는다.
