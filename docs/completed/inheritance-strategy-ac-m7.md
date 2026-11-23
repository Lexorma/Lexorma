# M-7 상속 A형·C형 물리 모델 생성 (M7-AC, 2026-11-22)

상속 구조(슈퍼타입·서브타입, M-6 후속 5차 — [logical-subtype.md](logical-subtype.md))를 B형(엔터티마다 테이블, [subtype-physical-transformation.md](subtype-physical-transformation.md)) 외에
**A형(단일 테이블 통합, `SINGLE_TABLE`)** 과 **C형(말단 서브타입별 테이블, `TABLE_PER_CONCRETE`)** 으로도 물리 모델을 만들 수 있게 했다.
사전 분석(권장안 D-AC-01~15)과 사용자 확정 D-AC-01~18, 그리고 작업 중 확정한 두 결정(아래 0.2)을 따른다.

## 0. 확정 정책

### 0.1 D-AC-01~18 (요약)

| 번호 | 결정 |
|---|---|
| D-AC-01 | A : 계층 전체 → 루트 테이블 하나. 모든 그룹이 Disjoint, Total·Partial 모두 허용, 그룹마다 분류 속성·구성원 분류 값 필수. C : 말단 엔터티만 테이블, 계층의 **모든** 그룹이 Disjoint·Total |
| D-AC-02 | 기존 `TB_ENTT_TBL_MPNG`·`TB_ATRB_COL_MPNG`·`TB_SBTYP_IDNTFR_COL_MPNG` 와 UNIQUE 를 바꾸지 않는다. 새 테이블 두 개 : 그룹 변환 기록 `TB_SBTYP_GROUP_CNVR`, C형 상속 일반 속성 매핑 `TB_SBTYP_ATRB_COL_MPNG`. A : 루트 직접 매핑 아래에 서브타입 속성을 매핑(기록·계층 소속 확인 시에만). C : 말단 직접 매핑 + 상속 PK 매핑 + 상속 일반 속성 매핑, 루트·중간은 매핑 없음 |
| D-AC-03 | A·C 는 루트 계층 전체가 요청에 있어야 한다 (100개 제한 유지) |
| D-AC-04 | A 는 속성을 한 번만 통합, C 는 말단마다 복제. 이름 충돌은 오류(접미사를 붙이지 않는다) |
| D-AC-05 | A : 서브타입·중간의 필수 속성은 NULL 허용 + 경고 (조건부 CHECK 없음). C : 논리 필수 그대로 |
| D-AC-06 | 분류 검증 : 빈 값·중복(타입별 정규화)·타입(문자·정수·소수)·도메인 길이. 분류 컬럼·값·CHECK 를 자동으로 만들지 않는다 |
| D-AC-07 | PK = 루트 주 식별자. C 의 말단 테이블 간 유일성은 `NOT_ENFORCED` |
| D-AC-08 | A : 모든 구성원의 자체 AK → 통합 테이블 UNIQUE. C : 자체 + 상속 AK → 말단마다 UNIQUE. D-INH-05 유지 |
| D-AC-09 | A 루트 참조 FK 허용. C 의 분산 부모(루트·중간) 참조는 제외 |
| D-AC-10 | A 의 루트가 아닌 구성원 참조는 제외 (`SUBTYPE_SCOPE_NOT_ENFORCED`) |
| D-AC-11 | 한 계층에 전략 하나, 혼합 금지. 독립 계층끼리는 달라도 된다 |
| D-AC-12 | 전체가 미매핑인 계층만 생성 |
| D-AC-13 | 정합성 상태값 4개 유지 + 상세 코드. 자동 보정 3종(FK·UNIQUE·상속 FK)은 A·C 대상에서 `STRATEGY_EXCLUDED` |
| D-AC-14 | 새 마이그레이션 V2 하나(두 테이블), 교환 형식 7(1~6 가져오기 유지), 스냅샷 형식 10 |
| D-AC-15 | 루트마다 전략 선택, 기본 B. planToken v9 |
| D-AC-16 | 매핑률 = (직접 + 유효한 통합) / (전체 − 유효한 분산) |
| D-AC-17 | C 에서 루트·중간이 자식인 FK 는 말단마다 복제하고, 기대 FK 마다 따로 판정 |
| D-AC-18 | 관련 매핑이 남아 있으면 변환 기록을 지울 수 없다. 관련 경로에 삭제 보호 |

### 0.2 작업 중 확정 (2026-11-22)

- **모델 버전 복원** : 기존에 복원 기능이 없으므로 새로 만들지 않는다. 스냅샷 10 은 새 구조를 담고 목록·상세·비교·ERD 비교만 검증한다. 교환 왕복 검증은 유지. pending-work ④ 유지.
- **교환과 승인** : DEC-22 유지 — 형식 7 도 `isApproval` 을 내보내지 않고 가져온 객체는 모두 미승인. 왕복 검증에서 모두 미승인인지 확인. 스냅샷의 승인 처리는 그대로, V2 는 기존 승인 상태를 바꾸지 않는다.

### 0.3 구현 중 정한 세부 (보고 대상 → 2026-11-23 확정, 8장)

- 분류 속성 하나를 여러 그룹이 함께 쓰는 A형 계층은 오류로 막는다 (그룹마다 분류 컬럼이 따로 필요하다 — D-AC-06 9.4). → **AC-P01 로 확정** (유지).
- ~~A형 루트에 주 식별자가 없으면 경고(PK 없는 통합 테이블)~~ → **AC-P02 : A형도 생성 차단** (C형은 처음부터 오류).
- 생성 뒤 그룹 구성원 변경은 막지 않고 진단으로 드러낸다 (B형 선례). 기록이 있는 그룹 삭제는 막는다. → **AC-P03 으로 확정**, 변환 당시 그룹 상태와의 차이 진단을 더했다 (8.3).

## 1. 저장 구조 (V2 `V2__subtype_strategy_conversion.sql`, PostgreSQL·H2 같은 내용)

- `TB_SBTYP_GROUP_CNVR` : 그룹마다 0~1행 (`UX_SBTYP_GROUP_CNVR_01`). `CNVR_TYPE_CD` = SINGLE_TABLE·TABLE_PER_CONCRETE (`CK_01`), A 는 `TBL_SN`·`CLSF_COL_SN` 필수, C 는 둘 다 null (`CK_02`). 행이 없는 그룹은 B형 또는 아직 만들지 않은 그룹 — **B형은 저장하지 않으며 기존 데이터에 행을 만들지 않는다**.
- `TB_SBTYP_ATRB_COL_MPNG` : (엔터티 테이블 매핑, 원본 속성) UNIQUE, 컬럼 UNIQUE — 상속 PK 매핑과 같은 꼴. 교차 중복(속성 매핑·상속 PK 매핑과 같은 컬럼)과 원본 속성의 조상 소속은 서비스가 검사.
- 예제(프로젝트 0) : 새 단어 변환(CNVR), 새 용어 4개, 물리·논리·매핑·관계(속성 짝)·CHECK·인덱스 7개(인덱스 컬럼 8개)·업무 설명. 예제 인덱스 159개·인덱스 컬럼 225개, 마이그레이션 3개.
- V0·V1 은 바꾸지 않았다. **다음 스키마 변경은 V3.**

## 2. 생성 (`PhysicalGenerationService` + `AlternativeInheritancePlanner`)

- 요청 `strategies` (`StrategyChoice(rootEntityId, strategy)`), 없으면 B. 미리보기 `strategies`(StrategyPlan : 상태·테이블·컬럼·UNIQUE·FK·전략상 제외 FK 수, 빠진·통합·분산 엔터티, 오류·경고)와 `conversions`(ConversionPlan : 그룹·분류 속성·분류 컬럼·값).
- 테이블 계획 상태에 `MERGED`(A 구성원)·`DISTRIBUTED`(C 루트·중간) 추가. 컬럼 계획 `mappingKind` (DIRECT·INHERITED_KEY·INHERITED_ATTRIBUTE·MERGED), 원본 엔터티, 논리 필수, 분류 여부.
- A : 루트 테이블 하나에 계층 모든 엔터티의 직접 속성 (PK 먼저, 이후 루트 → 깊이 순), 서브타입·중간 필수 → NULL 허용 경고, 구성원 자체 AK 마다 UNIQUE, 상속 FK·CHECK 없음, 그룹마다 변환 기록(통합 테이블 + 분류 속성의 컬럼).
- C : 말단마다 테이블 — 루트 PK 복제(상속 PK 매핑) + 조상 일반 속성 복제(상속 일반 속성 매핑) + 자기 속성, 필수 그대로, 자체·상속 AK UNIQUE, 상속 FK 없음, 그룹마다 C 기록.
- 관계 : A 루트 참조 FK 생성, A 서브타입 참조 `STRATEGY_EXCLUDED`(`SUBTYPE_SCOPE_NOT_ENFORCED`), C 분산 부모 참조 제외(`SUPERTYPE_DISTRIBUTED`), C 에서 분산 엔터티가 자식인 FK 는 말단마다 (자식 문맥 키 `ctx:attr`), 물리 자기 참조 검사.
- 혼합 차단 : 이미 기록이 있는 계층의 엔터티를 B 로 고르면 오류. 재생성 차단 (D-AC-12).
- 반영 : 같은 잠금 순서(프로젝트 배타 → 모델·스키마·참조 테이블 NOWAIT) → 재계획·planToken v9 → `GENERATE-` 버전 → 생성 → 매핑(종류별 저장소) → 기록. 실패하면 전체 롤백.

## 3. 매핑 (`ModelMappingService`)

- 목록 행 `physical` (DIRECT·MERGED·DISTRIBUTED·INCONSISTENT, 유효 여부, 통합·말단 테이블). 통합·분산 엔터티는 엔터티 매핑을 따로 만들 수 없다 (`INVALID_MODEL_MAPPING`). A 서브타입 속성은 기록·계층 확인 뒤 루트 매핑 아래에만.
- 상태(매핑률) D-AC-16 : `mergedEntities`·`distributedEntities`·`logicalEntities`·`directEntities`. 미매핑 필터는 유효한 통합·분산을 매핑으로 센다.
- 상속 일반 속성 매핑 API : `GET /api/modelMapping/entity/{id}/subtypeAttributes`(+ `/candidates`), `POST`·`DELETE /subtypeAttribute`.
- 변환 기록 : `GET /api/modelMapping/conversions?modelId=`, `DELETE /api/modelMapping/conversions/{rootEntityId}` — 관련 매핑(루트·말단 엔터티 매핑, 통합 속성 매핑, 상속 PK·일반 속성 매핑)이 남아 있으면 409 `RELATION_EXISTS` + 해제할 목록 (D-AC-18).
- 삭제 보호 : 기록이 있는 그룹 삭제, 기록이 쓰는 통합 테이블·분류 컬럼 삭제, 상속 일반 속성 매핑이 있는 속성·컬럼 삭제·테이블 변경을 막는다. 원본 삭제 흐름(`SourceDeletionService`)도 이 컬럼을 매핑된 것으로 본다. 프로젝트 삭제는 두 테이블을 지운다.

## 4. 정합성 (`StrategyQualityRows`, `ModelStructureQualityJudge.Strategies`)

- 속성 : A 통합 속성 행(루트 테이블 기준), C 복제 속성 행(말단마다). A 서브타입 필수 ↔ NULL 허용은 MATCH + `notEnforced` `CONDITIONAL_REQUIRED_NOT_ENFORCED`.
- [상속 구조] : 상세 코드 INHERITANCE_MERGED·INHERITANCE_DISTRIBUTED·INHERITANCE_CONCRETE·INTEGRATED_TABLE_UNMAPPED·CONCRETE_TABLE_UNMAPPED·INHERITED_ATTRIBUTE_MAPPING_MISSING·STRATEGY_STRUCTURE_MISMATCH. 상태값은 4개 그대로.
- 관계·AK : C 는 말단마다 행(키 `id:leaf`), 한 말단의 FK 누락은 그 말단만 `FK_MISSING`. A 서브타입 참조·C 분산 부모 참조는 NOT_COMPARABLE (`SUBTYPE_SCOPE_NOT_ENFORCED`·`SUPERTYPE_DISTRIBUTED`). 통합·복제 FK·UNIQUE 는 물리 전용으로 보지 않는다. B형 상속 FK 판정은 A·C 엔터티에서 건너뛴다.
- 자동 보정 3종은 A·C 대상에 `STRATEGY_EXCLUDED` 차단. 화면의 보정 상태 캐시 키에 페이지 버전·프로젝트를 넣었다 (§48 결함 수정).

## 5. 이력·버전·교환

- 변경 이력 대상 `SUBTYPE_CONVERSION`(group·strategy·table·classificationColumn)·`SUBTYPE_ATTR_MAPPING`. 서브타입 그룹 이름 필드 추가.
- 스냅샷 형식 10 (9 이하 읽기 유지). 복원 기능은 없다 (0.2).
- 교환 형식 7 : `SUBTYPE_CONVERSION`·`SUBTYPE_ATTR_MAPPING`. 검증 — 계층 전략 일관성, A 테이블·분류 컬럼이 루트 매핑과 맞는지, 매핑 구조, 통합 속성 매핑 소속, 상속 일반 속성 규칙. 모순되거나 형식 6 이하에 새 종류가 있으면 파일 전체 거절. 계층 단위로 가져오고(전부 아니면 없음) 승인 값은 없다(DEC-22).

## 6. 화면

- 생성 대화상자 : 루트마다 전략 선택(기본 B), 계층 전체 함께 선택(100개 제한), 전략 계획·A 분류 컬럼, MERGED·DISTRIBUTED 상태, 컬럼 종류 배지·원본 엔터티, 새 경고·관계 사유.
- 매핑 관리 : 통합·분산 표시, 매핑 버튼 숨김, 매핑률 주석, [상속 변환 기록] 대화상자(삭제 가능 여부·해제할 매핑), C 말단의 상속 일반 속성 매핑 영역.
- 정합성 비교 : DB 미강제 표시, 상세 코드 행. ERD 매핑 표시 : `MERGED`·`DISTRIBUTED` 상태, 상속 속성 복제 연결, 분산 엔터티는 말단 테이블마다 이동 버튼, 통합 테이블의 통합 엔터티 목록.
- 이력·버전·교환 화면 이름표·건수.

## 7. 검증

- H2 전체 1,389 실패 0 · 건너뜀 100, PostgreSQL 18.6 전체 1,389 실패 0 · 건너뜀 43 (격리 복사본·전용 컨테이너)
- 새 테스트 `InheritanceStrategyGenerationIntegrationTest` 5·`InheritanceStrategyScenarioIntegrationTest` 6(다단계 A·C, 관계 정책, 정합성, 매핑률, 보정 차단, 기록 수명, 토큰 409·롤백, 스냅샷 10·교환 7 왕복·가져온 객체 미승인·모순 파일 거절)·`InheritanceStrategyConcurrencyIntegrationTest` 7(PostgreSQL 동시성 8종 양방향 각 3회 — 40P01·중복·부분 적용 없음)·`SubtypeStrategyConversionMigrationTest` 2(V1 → V2 업그레이드 H2·PostgreSQL — ※ 정정 : 제약 검사 블록은 예제에 상속 그룹이 없어 실행되지 않았다, 9장), 생성 DDL 프로젝트 전체 97개 테이블 PostgreSQL 실행 + 데이터 검사(A AK 중복 거절·유형 안 필수/분류 값 미강제 확인·CHECK 없음, C NOT NULL·FK·말단 간 같은 키 허용·주문 FK 는 외국 기업 회원만), 단일 JAR(H2 파일 DB·PostgreSQL 기동·V0~V2 적용), Docker 이미지 빌드·PostgreSQL 기동(검증 뒤 삭제), 화면 : Chrome 확장으로 A·C 생성·기록·정합성·ERD 표시 조작 + Playwright Chromium 한·영 × 라이트·다크 × 1440·375 스크린숏 64장(가로 넘침·콘솔 오류 0), 성능(루트 1 + 서브타입 99, PostgreSQL 3회 : 미리보기 B 104~238ms · A 40~43ms · C 90~99ms, 생성 B 240~417ms · A 98~106ms · C 235~253ms, 정합성 요약 A·C 22~37ms). 화면 검증 중 찾아 고친 것 : A 루트 속성 분모, A·C 행의 B형 상속 PK·구조 표시, C 말단 상속 PK 영역의 B형 구조 판정, 정합성 안내·상속 PK 현황 문구, ERD 범례, 생성 대화상자 소제목·버튼 줄, 상속 일반 속성 영역 표 모양. **미검증** : 동시성 시나리오 나머지(pending [M-7] '상속 A·C 후속' ②), Oracle·MySQL·MariaDB DDL 실행, Chromium·Chrome 외 브라우저, 운영 규모 데이터. 이 Mac 에서는 `ExcelUtilsTest` 가 AWT 초기화로 JVM 이 중단(SIGABRT)되어 전체 실행을 `-Djava.awt.headless=true` 로 했다 (코드 변경과 무관 — 헤드리스면 통과).
- 남은 결정·검증은 pending-work.md [M-7] '상속 A·C 후속'.

## 8. 후속 보완 (2026-11-23, 사용자 확정 AC-P01~P03)

기능을 다시 만들지 않고 정책 3건 반영·누락 동시성 검증·회귀 검증·확인된 UI 결함만 다뤘다. V0·V1·V2 와 planToken 형식(v9), 교환 7·스냅샷 10 은 바꾸지 않았다. 새 마이그레이션 없음 (다음 V3).

### 8.1 AC-P01 — A형 분류 속성 공유 차단 (유지)

- 판정은 분류 속성 **ID** 기준 (`AlternativeInheritancePlanner.single`) : 같은 컬럼명·같은 값만으로 다른 속성을 같다고 보지 않는다. 다단계도 같다. 미리보기와 반영(반영은 같은 계획을 다시 세운다)이 같은 판정.
- 오류는 공유 속성 하나에 한 번, 충돌한 그룹을 `그룹명(슈퍼타입)` 으로 모두 적는다. 논리 등록은 허용, 분류 속성·값은 자동으로 바꾸지 않는다.
- 구분되는 다른 오류 : 다른 속성이 같은 컬럼명 → 통합 테이블 컬럼명 충돌, 값 타입 불일치 → 분류 값 오류, 다른 프로젝트·계층 밖 속성 → '슈퍼타입의 유효 속성이 아닙니다'. C형은 분류 컬럼이 없어 상관없다.
- 검증 중 찾아 고친 결함 : 구성원은 그대로 두고 분류 속성만 **상속받은(조상의) 속성**으로 바꾸는 그룹 수정이 거절됐다 (`LogicalSubtypeService.saveGroup` 이 슈퍼타입만 읽고 그 조상을 읽지 않음) — 슈퍼타입의 조상 체인을 함께 읽게 고쳤다.

### 8.2 AC-P02 — A형·C형 루트 주 식별자 필수

- `AlternativeInheritancePlanner.rootKeyErrors` (A·C 공용) : 주 식별자 없음, 구성 속성 없음, 루트에 속하지 않는 속성, 같은 속성 중복 → 생성 불가 오류 (한국어·영어). 논리 저장 경로도 막는 조건이지만 다른 경로(교환·직접 수정)로 들어온 데이터도 생성 단계에서 다시 본다.
- 반영은 계획을 다시 세워 `creatable` 이 아니면 거절하므로 오래된 planToken 으로 PK 없는 통합 테이블을 만들 수 없다 (미리보기 뒤 PK 삭제 → 409, 실패 뒤 테이블·매핑·기록·버전 없음).
- B형 정책은 그대로 : 일반 엔터티는 '주 식별자가 없어 PK 없이 만듭니다' 경고 후 생성. 논리 엔터티 등록에 PK 를 요구하지 않는다.
- 이미 만든 PK 없는 A형 테이블 : 이번 변경 전에는 경고 뒤 만들 수 있었다 (A·C 는 아직 커밋 전이라 실제 운영 데이터는 없다 — 개발 DB 는 보지 않았다). 자동 수정·삭제하지 않고, [상속 구조] 의 A형 루트 행이 통합 테이블에 PK 컬럼이 없으면 `MISMATCH` 로 보인다 (논리·물리 모두 PK 가 없으면 식별자 진단은 일치라 따로 둔다).

### 8.3 AC-P03 — 생성 뒤 논리 변경

- 그룹 구성원·분류 속성·분류 값·속성·식별자 변경은 기존 권한·승인·참조 규칙대로 허용된다. 물리 테이블·컬럼·PK·UNIQUE·FK·매핑·변환 기록·전략은 자동으로 바뀌지 않는다. D-AC-18(관련 매핑이 있으면 기록 삭제 불가)은 그대로다.
- **변환 당시 상태와의 비교** (`ConversionBasis`, 순수) : 변환 기록에는 구성원·분류 값이 없다. 모델 변경 이력(append-only)에서 변환 기록 CREATE 이력과 같은 작업이거나 그보다 앞선 그룹 이력의 마지막 변경 후 상태를 변환 당시 상태로 보고 지금 상태와 비교한다 (시각·ID 순, 되돌린 변경은 차이 없음). 비교 항목 : 구성원, 배타·완전, A형만 분류 속성 ID·구성원별 분류 값 (C형은 분류 컬럼이 없어 값 변경은 물리 구조와 무관). 이력이 없으면 비교하지 않았다고 사유에 밝힌다 — 추측해 일치로 두지 않는다.
- 차이가 있으면 그 그룹의 슈퍼타입 [상속 구조] 행이 `MISMATCH` · 코드 `CONVERSION_BASIS_CHANGED` (이미 불일치·미매핑이면 사유에 덧붙인다). 분류 컬럼이 있다는 이유로 바뀐 분류 값을 일치로 두지 않는다. 조회 4번(그룹·구성원·변환 기록 이력·그룹 이력)으로 모델 단위.
- 상태 구분 : ① 일치 = 기존 판정 MATCH ② 논리 계층이 바뀜 = `CONVERSION_BASIS_CHANGED` ③ 물리 컬럼·매핑 누락 = 기존 `INHERITED_ATTRIBUTE_MAPPING_MISSING`·`INTEGRATED_TABLE_UNMAPPED`·속성 미매핑 ④ 분류 값·속성 변경 = ②의 사유 + A형 분류 컬럼 ↔ 분류 속성 매핑 컬럼 불일치 ⑤ 기존 매핑의 참조 대상이 유효하지 않음 = 아래 두 진단.
- 새 진단 (지우거나 고치지 않고 `MISMATCH` · `STRATEGY_STRUCTURE_MISMATCH`) : A형 계층에서 빠진 엔터티의 속성이 루트 통합 매핑 아래 그대로 매핑돼 있으면 (예전 : 미매핑으로 보였다), C형 말단이 아니게 된 엔터티(또는 그 말단의 조상 속성이 아닌 속성)의 상속 일반 속성 매핑이 남아 있으면 (예전 : 진단 없음).
- 미보장 제약(`notEnforced`)과 실제 구조 불일치를 섞지 않는다 — 기존 의미 그대로.

### 8.4 계획 토큰의 변환 기록 범위 (AC-C18 에서 발견)

- 계획 토큰이 모델의 **모든** 변환 기록을 담아, 같은 프로젝트에서 무관한 계층을 먼저 만들면 대기 중인 다른 계층의 반영이 409 가 됐다. 이 계획이 닿는 계층(고른 엔터티와 그 관계 상대가 속한 계층)의 기록만 담게 줄였다 (`PhysicalGenerationService.touchedRoots`). 닿는 계층의 기록이 계획을 바꾸면 테이블·관계 상태도 바뀌어 토큰에 들어간다. 그룹을 찾을 수 없거나 순환인 기록은 보수적으로 계속 담는다. 형식 번호(v9)는 그대로 — 토큰은 저장하지 않는 미리보기 ↔ 반영 비교값이다.

### 8.5 동시성 (PostgreSQL 18.6, 격리 컨테이너, 독립 스레드·트랜잭션·연결, 잠금을 쥔 채 멈춤 → `pg_locks` 로 대기 확인, 방향별 3회)

| ID | 시나리오 | 결과 |
|---|---|---|
| C01·C02 | 같은 A형·C형 계층 생성 두 건 | 둘째는 프로젝트 행 대기 → 409, 테이블·기록·GENERATE 버전 한 번 |
| C03 | 같은 계층 A형 ↔ C형 생성 | 둘째는 프로젝트 행 대기 → 409, 전략 하나·테이블 한 벌 |
| C04 | 생성 ↔ 그룹 삭제 / 완전·부분 변경 | 생성 선행 : 삭제는 대기 후 RELATION_EXISTS, 변경은 대기 후 성공 · 변경 선행 : 생성 NOWAIT 409 |
| C05 | 생성 ↔ 구성원 추가·삭제 | 생성 선행 : 대기 후 성공(새 구성원 테이블·매핑 없음) · 변경 선행 : 생성 409 |
| C06 | 생성 ↔ 분류 값 변경 (예전 C03) | 같은 꼴 |
| C07 | 생성 ↔ 분류 속성 변경·삭제 | 변경 양방향 같은 꼴. 삭제는 정책상 항상 거절이라 '삭제 선행' 경합 불가 — 생성 선행에서 대기 후 RELATION_EXISTS, 단독 삭제 거절 확인 |
| C08 | A·C 생성 ↔ 루트 PK 변경·삭제 | 생성 선행 : 대기 후 성공(물리 PK 그대로) · PK 변경 선행 : 생성 409, 그 뒤 미리보기는 생성 불가(AC-P02) |
| C09 | 생성 ↔ 자체 AK 변경·삭제 | 생성 선행 : 대기 후 성공(UNIQUE 그대로) · AK 선행 : 생성 409 |
| C10 | 생성 ↔ 구성원 엔터티 매핑 등록 | 생성 선행 : 대기 후 INVALID_MODEL_MAPPING · 매핑 선행 : 생성 409 |
| C11 | 생성 ↔ 같은 물리명 테이블 등록 | 생성 선행 : 스키마 행 대기 후 SAVED_DATA_EXISTS · 등록 선행 : 생성 NOWAIT 409. MBR 은 하나 |
| C12 | 생성 ↔ 다른 테이블을 생성 예정 이름으로 변경 | 둘 다 프로젝트 배타 — 생성 선행 : 대기 후 SAVED_DATA_EXISTS · 이름 변경 선행 : 대기 후 409 |
| C13 | 생성(기존 부모 UNIQUE 참조 FK) ↔ 그 UNIQUE 삭제 | 생성 선행 : 삭제는 부모 테이블 행 대기 후 성공 → FK 남음(DEC-09 진단) · 삭제 선행 : 생성 NOWAIT 409 |
| C14 | A 생성 ↔ 다른 B형 계층 상속 FK 보정 | 둘 다 프로젝트 배타 — 뒤 작업 대기 후 둘 다 성공 |
| C15 | 기록 삭제 ↔ 통합 속성 매핑 재등록 (예전 C08) | 재등록 선행 : 대기 후 RELATION_EXISTS · 삭제 선행 : 대기 후 INVALID_MODEL_MAPPING |
| C16 | 기록 삭제 ↔ 통합 테이블 이름 변경 / 분류 컬럼 삭제 | 이름 변경 양방향 교착 없이 둘 다 성공. 분류 컬럼 삭제는 기록이 보이는 동안 RELATION_EXISTS (컬럼 삭제 선행은 정책상 항상 거절이라 경합 불가) |
| C17 | 생성 ↔ 루트 엔터티 승인 | 생성 선행 : 대기 후 승인 성공(생성 테이블은 미승인 그대로) · 승인 선행 : 생성 409 |
| C18 | 독립 계층 동시 생성 | 다른 프로젝트 : 서로 기다리지 않고 둘 다 성공 · 같은 프로젝트 : 프로젝트 행에서 직렬화된 뒤 둘 다 성공 (8.4 수정 전에는 둘째가 409) |
| X01~X03 | 혼합 B 생성·상속 일반 속성 매핑 중복·기록 삭제 중복 (예전 C05~C07) | 그대로 통과 |

INH (B형 상속 FK 보정) 추가 실행 : C05(자식 PK 컬럼 추가 — 보정 선행이면 대기 후 진행, 추가 선행이면 보정 409; PK 삭제 쪽은 기존 C04~C06 시험), C07(상속 PK 매핑 해제 — 보정 선행 대기 후 진행, 해제 선행 보정 409), C09(부모 테이블 이름 변경 — 프로젝트 행 직렬화, 이름 변경 선행이면 보정 409), C10(같은 모델 신규 생성 — 직렬화 후 둘 다 성공), C11(자체 AK 참조 누락 FK 보정 ↔ 참조 UNIQUE 삭제 — 보정 선행이면 삭제가 부모 테이블 행 대기 후 성공 → FK 남고 `FK_KEY_INVALID`, 삭제 선행이면 보정 409·FK 없음 — 어느 한쪽이 반드시 실패한다고 가정하지 않음). 모든 시나리오 40P01 없음·중복 테이블·기록·매핑·FK 없음·부분 적용 없음. S4·S6b·D11·USER 관계 ↔ 일괄 저장·엑셀·REF_COLUMN/USER 중복은 기존 `RelationSyncDeadlockIntegrationTest`·`InheritanceConcurrencyIntegrationTest` 가 실제 경합(멈춤 지점 + `pg_locks`)으로 검증하며 이번 PostgreSQL 전체 실행에서 다시 통과했다.

### 8.6 화면

- 영어 매핑 목록 머리글 'Attribute mapping (direct · inherited PK)' 가 'Actions' 와 겹치던 것 : `#modelMappingGrid thead th { white-space: normal; }` (논리 모델 관리 표와 같은 방식, 열 폭·정렬·버튼 그대로).
- 정합성 보정 상태 캐시(§48) 회귀 : 키 = 프로젝트·모델·대상·그리드 응답 버전, `staleTime 0`, 적용·409 뒤 `refresh` — 실제 브라우저에서 화면 밖 승인·해제가 명시적 [조회] 뒤에만 반영되고(조회 전에는 이전 판정), 모델을 바꾸면 고르기·판정을 쓰지 않는 것을 확인. 자동 재시도·주기 조회는 없다.

### 8.7 검증

- H2 전체 1,414 — 성공 1,295 · 실패 0 · 건너뜀 119 (PostgreSQL 전용 동시성 51·PostgreSQL 전용 마이그레이션·DDL 25·원본 DB 수집 21·성능 측정 22), PostgreSQL 18.6 전체 1,414 — 성공 1,371 · 실패 0 · 건너뜀 43 (원본 DB 수집 21 — 원본 연결 미지정, 성능 측정 22 — LEXORMA_PERF 미지정), 격리 복사본·전용 컨테이너, `-Djava.awt.headless=true`(이 Mac 의 `ExcelUtilsTest` AWT 중단 회피 — 코드 무관); TypeScript 검사·Vite 빌드 성공; 단일 JAR(H2 파일 DB·PostgreSQL 기동, V0~V2), 프로젝트 전체 생성 DDL 100개 테이블 PostgreSQL 실행 오류 0 + A형 PK·AK 중복 거절·유형 안 필수·분류 값 미강제, C형 말단 PK·NOT NULL; Docker 이미지 빌드·PostgreSQL 기동(검증 뒤 삭제); Playwright Chromium 한·영 × 라이트·다크 × 1440·375 — 매핑 목록 머리글 칸 넘침·겹침 0, 가로 넘침 0, 콘솔 오류 0, C형 말단 대화상자·A형 차이 행 표시, 보정 상태 캐시 흐름(화면 밖 승인·해제는 명시적 조회 뒤 반영, 모델 변경 시 고르기 비움).
- 새 테스트 `InheritanceStrategyPolicyIntegrationTest` 6 (H2·PostgreSQL), `InheritanceStrategyConcurrencyIntegrationTest` 7 → 21 (PostgreSQL), `InheritanceConcurrencyIntegrationTest` +5 (INH-C05·07·09·10·11).
- **미검증** : Oracle·MySQL·MariaDB 의 A·C DDL 실행, Chromium 외 브라우저, 운영 규모 데이터, 보정 상태 캐시 흐름 중 상위 상속 FK 보정 뒤 하위 상태·다른 세션 FK 등록·부모 UNIQUE 삭제·프로젝트 변경(코드 확인 — 캐시 키에 프로젝트·모델·응답 버전, 적용·409 뒤 무효화 — 와 서버 테스트로만).

## 9. 긴급 수정 — V3 변환 전략 CHECK 재정의 (2026-11-23)

- **증상** : GitHub Actions(`56d9b2e`, 두 번 실행 모두)에서 H2 테스트 13건 실패 — A·C 생성 반영과 `ProjectDeletionIntegrationTest` 준비 코드, 즉 `TB_SBTYP_GROUP_CNVR` 에 행을 넣는 모든 곳. 로컬(macOS 알파벳 순서·Linux 기본 순서)에서는 재현되지 않았고, Linux JDK 21 컨테이너에서 테스트 클래스 순서를 무작위(seed 11·22·33)로 하면 같은 실패가 났다.
- **원인** : H2 2.4.240 은 `CNVR_TYPE_CD in ('SINGLE_TABLE','TABLE_PER_CONCRETE')` 를 상수 집합(`ConditionInConstantSet`, `TreeSet`)으로 만들고 그 비교에 **제약을 만든 세션**(Flyway 마이그레이션 연결 — 연결 풀 소속)을 쓴다. 그 연결이 닫히면(테스트 설정 `idle-timeout 10s`·컨텍스트 정리, 운영 Hikari 수명) 이후 모든 삽입이 `Check constraint invalid`(23514, 원인 `The database has been closed` 90098)로 실패한다. 같은 열의 `=` 를 `or` 로 이은 식도 H2 가 IN 으로 바꿔 같다. `CASE` 식·`CK_02` 같은 꼴·단일 `=` 는 영향이 없다 (최소 재현으로 확인). V0 의 CHECK 5개에는 IN 목록이 없어 이 제약만 해당했다. H2 파일 DB(`h2-local`·`h2-docker`)는 V2 를 적용한 프로세스에서 마이그레이션 연결이 교체되면 다시 시작할 때까지 A·C 생성이 실패한다 (다시 열면 제약을 시스템 세션으로 다시 읽어 정상). PostgreSQL 은 해당 없음.
- **수정** : `V3__fix_subtype_conversion_check.sql` (두 벤더 같은 내용) — `CK_SBTYP_GROUP_CNVR_01` 을 지우고 같은 이름으로 `check (case when CNVR_TYPE_CD = 'SINGLE_TABLE' then true when CNVR_TYPE_CD = 'TABLE_PER_CONCRETE' then true else false end)`. 뜻은 같다 (두 값만 허용, 대소문자 구분). `CNVR_TYPE_CD` 는 NOT NULL 이라 NULL 은 CHECK 전에 거절된다 (IN 식은 NULL 을 '알 수 없음'으로 통과, CASE 식은 거절 — 차이는 드러나지 않는다). 예제(프로젝트 0) 제약조건 설명 `INSP_EXPR_CN` 은 V2 값 그대로일 때만 PostgreSQL 표기의 새 정의로 바꾼다. `CK_02`·FK·UNIQUE·데이터·변환 기록은 그대로, V0·V1·V2 체크섬 불변.
- **테스트 보강** (`SubtypeStrategyConversionMigrationTest`) : 예전 제약 검사 블록은 예제에 상속 그룹이 없어 한 번도 실행되지 않았고 예외 종류만 봐서 평가 오류도 통과로 받았다. 이제 그룹을 직접 만들고, 제약을 만든 연결이 닫힌 뒤 새 연결로 정상 두 값 삽입, 잘못된 값·NULL·CK_02·UNIQUE 거절을 SQLState(H2 위반 23513 / PostgreSQL 23514, NOT NULL 23502, UNIQUE 23505)와 제약 이름으로 확인하고 원인 사슬에 90098·'Check constraint invalid' 가 없어야 한다. 시나리오 : V1 → V2(기록 2건을 V2 연결로 넣음) → V3 업그레이드(기록 보존), 새 설치 V0 → V3(CK_02 를 그 전용 스키마에서만 지운 뒤 CK_01 단독 거절 확인), H2 파일 DB(다른 연결이 DB 를 연 채 마이그레이션 연결이 닫힌 뒤 + 재시작 뒤). V3 없이 돌리면 이 테스트가 정상 값 삽입에서 'Check constraint invalid' 로 실패함을 확인했다. `LexormaIndexSeedTest`·`V0SeedMigrationTest` 의 마이그레이션 수 3 → 4.
- **규칙** : H2 마이그레이션의 CHECK 에 `IN (...)`(또는 같은 열 `=` 의 OR)을 쓰지 않는다 — CASE 식으로 쓴다. 제약 검증 테스트는 만든 연결이 닫힌 뒤 새 연결로, 위반과 평가 오류를 구분한다.

