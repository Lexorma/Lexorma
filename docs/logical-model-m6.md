# M-6. 논리 데이터 모델 — 설계 결정과 구현 기록 (1차 : 도메인·API, 2차 : 관리 화면·논리 ERD·메뉴)

> [데이터 모델링 로드맵](data-modeling-roadmap.md) M-6 중 **1차(저장 구조·관리 API·논리 ERD 조회 API)** 의 기록이다.
> 작성일 2026-11-11. 1~7장은 1차 기록이고, **2차(관리 화면·논리 ERD·레이아웃 저장·사이드바 개편)는 8장**이다.

## 1. 기존 구조 분석과 설계 결정

| 확인 항목 | 결과 → 결정 |
|---|---|
| 물리 모델 | `TableInfo`·`ColumnInfo`·`SchemaInfo`·`TableRelation`(+`TableRelationColumn`)·인덱스·제약. 전부 `projectId` 로 격리, 승인 플래그(`isApproval`)가 있으면 승인 취소 후에만 변경 → **논리 모델은 별도 패키지(`data/logical`)·별도 테이블.** 물리 엔티티를 재사용하지 않고 테이블·컬럼 ID 도 갖지 않는다. 기존 물리 테이블은 변경하지 않았다 |
| 표준화 도메인 | 컬럼 → 표준 용어(`TB_COL.STD_TRM_SN`) → 표준 도메인(`TB_STD_TRM.STD_DMN_SN`). 도메인은 용어를 거쳐서만 연결된다 → **논리 속성도 표준 용어 ID 만 저장**하고 도메인은 용어의 도메인을 조회해 내려준다 (용어-도메인 관계를 중복 저장하지 않는다). 승인 여부는 컬럼과 같이 따지지 않는다 |
| 로드맵 M-6 / D-07 | 초기 범위 = 엔터티·속성·관계 + 논리 ERD 조회, 주제 영역·서브타입은 후속. 프로젝트당 모델 수 정책은 없음 → **프로젝트에 논리 모델 여러 개.** 엔터티 유형·주제 영역은 로드맵이 요구하지 않아 만들지 않았다 (지시서의 "엔터티 유형"·"식별자 유형"은 미구현 — 아래 5장) |
| 카디널리티 | 로드맵은 "M-6 논리 관계 설계 시 확정" → **양쪽 최소·최대 참여 수 4개 값**으로 저장 (문자열 하나보다 선택/필수·1:1/1:N/N:M 을 모두 표현). 최대 `null` = 다(N) |
| 관계 속성 매핑 | 논리 관계는 엔터티 간 관계만 표현한다. FK 속성 쌍은 물리(M-1 `TB_TBL_REL_COL`)의 몫이고 M-7 매핑에서 필요해지면 추가 → **관계 속성 매핑 테이블은 만들지 않았다** |
| 권한 | 물리 모델 API 가 조회 VIEWER / 변경 USER / 승인은 `AuthUtil.isApprovalAvailable()` → 동일 |
| 마이그레이션 | CLAUDE.md 정책상 **`V0__init.sql` 만 유지** (지시서의 "다음 순번 마이그레이션"과 다르다). H2·PostgreSQL 두 파일에 같은 내용을 넣었다. **이미 만들어진 DB 는 V0 체크섬이 달라진다** — 개발 DB 는 초기화·repair 하지 않았다 (적용 방법은 별도 결정 필요) |

## 2. 저장 구조

```text
Project
 └── TB_LGCL_MDL (LogicalModel)            UNIQUE(PRJ_SN, MDL_NM)
      ├── TB_LGCL_ENTT (LogicalEntity)      UNIQUE(LGCL_MDL_SN, ENTT_NM), 승인 플래그
      │    ├── TB_LGCL_ATRB (LogicalAttribute)     UNIQUE(LGCL_ENTT_SN, ATRB_NM), STD_TRM_SN → TB_STD_TRM
      │    └── TB_LGCL_IDNTFR (LogicalIdentifier)  UNIQUE(LGCL_ENTT_SN, IDNTFR_NM), BSC_YN(주 식별자)
      │         └── TB_LGCL_IDNTFR_ATRB            UNIQUE(식별자, 속성), SORT_ORD
      └── TB_LGCL_REL (LogicalRelationship) 원본·대상 엔터티, IDNTF_YN, 양쪽 MIN/MAX_CNT, 승인 플래그
```

- ID 는 모두 `SEQUENCE`(`SQ_LGCL_*`, allocation 50). 이름은 공공 표준 약어 규칙 ([db-naming-standard.md](db-naming-standard.md)) — 필요한 단어(논리 `LGCL`, 엔터티 `ENTT`, 속성 `ATRB`, 식별자 `IDNTFR` 등)와 용어, 새 테이블 6개의 물리 모델을 V0 예제 데이터에 추가했다.
- FK 는 전부 `CASCADE` 없이 건다. CHECK : `MIN >= 0`, `MAX is null or (MAX >= 1 and MAX >= MIN)` (양쪽).
- 조회용 인덱스 : 속성의 용어, 식별자-속성의 속성, 관계의 모델·원본·대상.

## 3. 정책

| 항목 | 정책 |
|---|---|
| 프로젝트 격리 | 프로젝트 ID 는 항상 로그인 프로젝트(`AccountDto.getProjectId()`). 요청의 모든 ID(모델·엔터티·속성·식별자·관계·용어)를 그 프로젝트 것인지 확인하고, 아니면 `LOGICAL_MODEL_IS_NOT_DEFINED`(용어는 `NOT_FOUND_TERM_DATA`) |
| 이름 중복 | 프로젝트 내 모델명, 모델 내 엔터티명, 엔터티 내 속성명·식별자명. 앞뒤 공백 제거, **대소문자 무시**(서비스) → `SAVED_DATA_EXISTS`. DB UNIQUE 는 대소문자를 구분하는 마지막 방어선이다 |
| 식별자 | 속성 1개 이상(복합 가능, 순서 유지), 같은 엔터티의 속성만, 중복 불가. 주 식별자는 엔터티당 최대 1개 (서비스 검증 + 모델 행 잠금) |
| 관계 | 양쪽 엔터티는 같은 모델·같은 프로젝트. 자기 참조 허용. `cardinality`(`1:1`/`1:N`/`N:1`/`N:M`)는 조회 전용 요약. 최소 0 = 선택, 1 이상 = 필수 |
| 삭제 | 모델 : 엔터티가 있으면 차단. 엔터티 : 관계에 쓰이면 차단(관계를 먼저 정리), 속성·식별자는 엔터티에 딸린 데이터라 함께 삭제. 속성 : 식별자에 쓰이면 차단. 모두 `RELATION_EXISTS` |
| 표준 용어 삭제 | 논리 속성이 참조하는 용어는 삭제 차단 (`StandardTermService`, `RELATION_EXISTS`) |
| 승인 | 엔터티(속성·식별자 포함)·관계는 승인 상태면 수정·삭제 불가. 승인/취소는 승인 권한자만 (`APPROVAL_PERMISSION_DENIED`) |
| 동시성 | 변경은 모델 행(모델 등록은 프로젝트 행)을 `PESSIMISTIC_WRITE` 로 잠근 뒤 검사한다 → 같은 이름 동시 등록, 주 식별자 동시 등록, 관계 생성과 엔터티 삭제의 교차가 직렬화된다. 예외 시 트랜잭션 전체 롤백 |
| 물리 모델과의 관계 | 없음. M-7 에서 ID 기반 매핑 테이블을 추가하는 것을 전제로 한다 |

## 4. API (`/api/logical`, `LogicalModelRestController`)

저장(POST)은 `id` 가 없으면 등록, 있으면 수정. 조회 VIEWER, 변경 USER.

| 대상 | 경로 |
|---|---|
| 모델 | `GET /model`, `GET /model/{id}`, `POST /model`, `DELETE /model/{id}` |
| 엔터티 | `GET /model/{id}/entity`, `GET /entity/{id}`(속성·식별자 포함), `POST /entity`, `DELETE /entity/{id}`, `PATCH /entity/{id}/approval/{isApproval}` |
| 속성 | `GET /entity/{id}/attribute`, `POST /attribute`, `DELETE /attribute/{id}`, `PUT /entity/{id}/attribute/order`(속성 ID 전체를 원하는 순서로) |
| 식별자 | `GET /entity/{id}/identifier`, `POST /identifier`(`attributeIds` 로 구성), `DELETE /identifier/{id}` |
| 관계 | `GET /model/{id}/relationship`, `POST /relationship`, `DELETE /relationship/{id}`, `PATCH /relationship/{id}/approval/{isApproval}` |
| 논리 ERD | `GET /model/{id}/erd` |

논리 ERD 응답 (`LogicalModelDto.Graph`, 조회 4번 고정 — 모델, 엔터티+속성, 식별자, 관계):

```json
{
  "modelId": 1, "modelName": "영업",
  "entities": [{
    "id": 10, "name": "회원", "description": null, "isApproval": false,
    "attributes": [{ "id": 100, "name": "회원번호", "dataType": null, "isRequired": true, "sortOrder": 1,
                     "termId": 1, "termName": "계정_일련번호", "domainName": "…", "identifier": true, "primaryIdentifier": true }]
  }],
  "relationships": [{ "id": 5, "sourceEntityId": 10, "targetEntityId": 11, "isIdentifying": false,
                      "sourceMin": 1, "sourceMax": 1, "targetMin": 0, "targetMax": null, "cardinality": "1:N", "isApproval": false }]
}
```

오류 코드 : `INVALID_LOGICAL_MODEL`(정의 오류), `LOGICAL_MODEL_IS_NOT_DEFINED`(없음·다른 프로젝트) 를 추가했다 (ko/en 메시지 포함).

## 5. 지시서와 다른 점 · 미구현

- **마이그레이션 순번** : 새 버전 파일이 아니라 `V0__init.sql` 수정 (저장소 정책). 기존 DB 반영 방법은 미정.
- **엔터티 유형, 식별자 유형, 관계 속성 매핑** : 로드맵에 요구가 없어 만들지 않았다.
- **메뉴·화면** : 1차에는 없음 → 2차에서 추가 (8장).
- **프로젝트 삭제** : 기존 물리 모델·데이터 사전과 같이 논리 모델 행도 정리하지 않는다 (`ProjectDeletedEvent` 리스너 없음 — 기존 동작).

## 6. 검증

```bash
./gradlew clean build      # 340 tests, 실패 0, 건너뜀 2 (환경 변수가 필요한 V0SeedMigrationTest 1건, UploadPerformanceTest 1건)
# PostgreSQL 18.6 : 빈 격리 DB(metamora_m6_test)를 만들어 실행 후 삭제 (개발 DB 는 건드리지 않았다)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/metamora_m6_test \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
./gradlew :metamora-app:test -PfrontendPrebuilt=true \
  --tests 'com.koboolean.metamora.LogicalModelIntegrationTest' --tests 'com.koboolean.metamora.StandardizationQualityIntegrationTest' \
  --tests 'com.koboolean.metamora.DictionaryUsageIntegrationTest' --tests 'com.koboolean.metamora.SmokeTest' \
  --tests 'com.koboolean.metamora.ReferenceSyncIntegrationTest' --tests 'com.koboolean.metamora.SchemaManagementIntegrationTest' \
  --tests 'com.koboolean.metamora.PhysicalModelIntegrationTest' --tests 'com.koboolean.metamora.ErdIntegrationTest' \
  --tests 'com.koboolean.metamora.DataPortalTemplateTest' --tests 'com.koboolean.metamora.TermUploadNameGenerationTest'      # 98 tests, 실패 0, 건너뜀 0
```

| 항목 | H2 | PostgreSQL 18.6 |
|---|---|---|
| `LogicalModelIntegrationTest` 7건 : 모델(중복명·다른 프로젝트 같은 이름·격리), 엔터티·속성(중복, 순서, 용어 연결·다른 프로젝트 용어 차단), 식별자(주 식별자 1개, 복합·순서, 다른 엔터티 속성·중복 차단), 관계(1:1·1:N·N:M, 선택·필수, 식별·비식별, 자기 참조, 다른 모델 차단, 엔터티 삭제 차단, 승인), 논리 ERD(빈 모델, 물리 ERD 와 독립), 권한(VIEWER/USER/승인권자), 동시 요청 | 통과 | 통과 |
| `SmokeTest` 61건 (HTTP) : `logicalModel`(전체 흐름, 오류 코드, 삭제 순서) 추가 | 통과 | 통과 |
| 회귀 : `PhysicalModelIntegrationTest` 4, `ReferenceSyncIntegrationTest` 6, `ErdIntegrationTest` 7(V0 예제에 논리 모델 테이블 포함), `SchemaManagementIntegrationTest` 4, `DictionaryUsageIntegrationTest` 1, `StandardizationQualityIntegrationTest` 2, `DataPortalTemplateTest` 3, `TermUploadNameGenerationTest` 3 | 통과 | 통과 |
| 나머지 모듈 단위 테스트 (데이터 사전·테이블·컬럼·DDL·엑셀 등), `npm` typecheck·build | 통과 | — |

PostgreSQL 첫 실행에서 1건 실패했다 : 모델 목록의 이름순이 DB 정렬 규칙(H2 코드포인트 / PostgreSQL 로케일)에 따라 한글·영문 순서가 달라, 테스트 단언을 순서 무관으로 고쳤다 (기능 변경 없음).

**미검증 (완료로 보지 않음)**

- 브라우저 검증 (이번에 화면 변경이 없다. 기존 화면 회귀는 HTTP 스모크 테스트까지만).
- 이미 데이터가 있는 DB 에 V0 변경을 반영하는 절차 (체크섬 불일치).
- 엔터티·속성이 많은 모델의 논리 ERD 조회 성능 (페이징 없음, 모델 전체 반환).
- 엑셀 업로드 실측 성능 (`UploadPerformanceTest` 는 opt-in 이라 건너뜀).

## 7. 후속

- **M-6 2차** : 구현 (8장). 큰 모델용 조회 범위 제한만 남았다.
- **M-7 고려사항** : 엔터티 ↔ 테이블, 속성 ↔ 컬럼 매핑 테이블을 ID 기반으로 추가 (논리·물리 테이블 모두 변경 불필요). 논리 관계 ↔ 물리 FK 대응 시 관계 속성 매핑 필요 여부 재검토. 속성의 `dataType` 은 자유 입력이라 타입 추천은 용어의 도메인을 기준으로.
- **남은 설계 위험** : DB UNIQUE 가 대소문자를 구분(서비스 잠금을 우회한 직접 입력은 `Sales`/`sales` 공존 가능), 주 식별자 1개 제약은 DB 제약이 아니라 서비스+잠금, 프로젝트 삭제 시 논리 모델 잔존.

## 8. 2차 : 논리 모델 관리 화면 · 논리 ERD · 사이드바 개편

### 8.1 분석과 결정

| 확인 항목 | 결과 → 결정 |
|---|---|
| 논리 모델 API | 1차 API 를 그대로 쓴다. 엔터티 목록의 속성 수·관계 수는 `GET /model/{id}/erd` 응답에서 센다 (조회 API 를 늘리지 않았다). 수정일은 API 에 없어 목록에 넣지 않았다 |
| 물리 ERD 재사용 범위 | **재사용** : ELK 자동 배치(`erd/layout.ts`, 간격만 인자로 추가), 노드 크기 규칙·축소 시 행 생략(`erd/model.ts`, `useSimpleNodes`), 스타일(`erd.css`), 배치 저장 UX(저장 전 표시, 저장된 배치 불러오기, version 충돌). **따로 구현** : 화면(`LogicalErdPage`), 엔터티 노드, 관계선(참여 수 표기), 조회·레이아웃 API. 물리 DTO·조회 범위(스키마·이웃·페이지)는 쓰지 않는다 |
| 레이아웃 저장 | 물리 `TB_ERD_LYT` 는 (프로젝트, 사용자) 1건에 **테이블 ID** 좌표라 논리 엔터티 ID 와 섞을 수 없다 → **전용 테이블 `TB_LGCL_ERD_LYT` (논리 모델 × 사용자 1건)** 과 전용 API. 모델 삭제 시 그 모델의 배치(모든 사용자)를 함께 지운다. 지워진 엔터티의 좌표는 조회·저장 때 정리 |
| 메뉴 ID 불일치 | 서버 그룹 `operation-modeling` ↔ 화면 `data-modeling` 불일치를 확인했다 (화면은 `MENU_ROLES` 의 같은 이름 항목 덕에 우연히 동작). 그룹을 새로 나누면서 둘 다 없앴다 |
| 변경 버튼 권한 | 화면이 "USER 이상"을 알 방법이 없었다 → `MENU_ROLES` 에 `logical-model-edit`(USER) 를 두고 `/api/auth/me` 의 `menus` 로 내려 버튼 노출에 쓴다 (권한 계층을 화면에 복제하지 않는다). 실제 차단은 API 의 `@PreAuthorize` |
| 마이그레이션 | 저장소 정책대로 `V0__init.sql`(H2·PostgreSQL)에 테이블·시퀀스·인덱스·FK 와 예제 물리 모델(테이블 35, 컬럼 329~341, 용어 153, 관계 39)을 추가했다. **이미 만들어진 DB 는 V0 체크섬이 다시 달라지고 테이블이 없다** — 개발 DB 는 건드리지 않았다. 수동 반영용 DDL 은 8.6 |

### 8.2 사이드바 (`components/layout/menu.ts`, `MenuAuthorization`)

```text
대시보드
데이터 사전                (단일 메뉴 /dataDictionary — 단어·용어·도메인은 기존 탭 그대로)
논리 모델 관리             logical-modeling
  ├ 논리 모델 관리         logical-model  /logicalModel   VIEWER
  └ 논리 ERD               logical-erd    /logicalErd     VIEWER
물리 모델 관리             physical-modeling
  ├ 스키마 관리 / 테이블 관리 / 컬럼 관리(USER) / 물리 ERD     (URL·권한 그대로)
데이터 품질 진단           (단일 메뉴 /standardizationQuality)
운영 관리 / 시스템 관리    (그대로)
```

- `데이터 관리`(`data-management`)·`데이터 모델링`(`data-modeling`, `operation-modeling`) 그룹은 없앴다. 메뉴 ID 는 화면 단위라 기존 화면의 ID·URL·최소 권한은 바뀌지 않았다.
- 사이드바는 1뎁스 단일 메뉴(`MenuItem`)와 그룹(`MenuGroup`)을 함께 그린다. 대시보드 "승인 대기" 영역은 없앤 그룹 대신 카드별 메뉴 권한으로 노출한다.

### 8.3 논리 모델 관리 (`features/logical/LogicalModelPage.tsx`, `entityDetail.tsx`)

| 구성 | 내용 |
|---|---|
| 모델 | 선택 상자 + [모델 등록]·[모델 수정]·[모델 삭제]·[논리 ERD]. 선택 = URL `?modelId=` → 이 브라우저에서 마지막으로 고른 모델(**프로젝트별** `localStorage`) → 첫 모델. 목록에 없는 ID 는 무시. 엔터티가 있으면 삭제 버튼을 막고 이유를 보여 준다 |
| 엔터티 탭 | 엔터티명·설명·속성 수·관계 수·승인 상태, [상세]·[ERD](그 엔터티에 포커스). 등록 후 바로 상세가 열린다 |
| 엔터티 상세 (대화상자, ERD 와 공용) | 탭 : 속성 / 식별자 / 관계(조회) / 기본 정보. 속성 : 등록·수정·삭제·순서(↑↓, 순서 변경 API), 표준 용어 검색·연결(도메인은 용어의 도메인을 표시만), 식별자에 든 속성은 삭제 버튼을 막는다. 식별자 : 주/보조 구분, 구성 속성을 고른 순서대로(복합), 순서 변경. 승인·승인취소(승인 권한자), 엔터티 삭제(관계가 있으면 막고 안내) |
| 관계 탭 | 관계명·출발/도착 엔터티(각 참여 수)·식별 여부·카디널리티·승인 상태, 등록·수정·삭제·승인. 참여 수는 쪽마다 `1` / `0..1` / `1..N` / `0..N` 선택 (**N = 서버의 null**), 목록에 없는 서버 값(예 2..5)은 그대로 보존 |
| 권한 | 변경 버튼은 `logical-model-edit`(USER 이상), 승인 버튼은 `approvalAvailable`. 승인된 엔터티·관계는 변경 버튼이 사라지거나 막힌다 |
| 오류 | 서버 메시지를 공통 알림으로 보여 준다 (이름 중복, 주 식별자 중복, 삭제 차단 사유 등) |

### 8.4 논리 ERD (`features/logical/LogicalErdPage.tsx`)

- 모델 1개 전체를 조회한다 (`GET /api/logical/model/{id}/erd`). 모델을 바꾸면 화면 상태를 새로 만들어 배치·선택이 섞이지 않는다.
- **엔터티 노드** : 엔터티명, 설명, 승인 대기 표시, 속성(앞 `PI` 주 식별자 / `AI` 보조 식별자, 가운데 도메인명 또는 데이터 유형, 뒤 `*` 필수). 20개를 넘는 속성과 축소 상태는 물리 ERD 와 같은 규칙으로 생략.
- **관계선** : 실선 = 식별, 점선 = 비식별. **각 엔터티 옆에 그 엔터티 쪽 참여 수**를 적는다 (출발 쪽 `sourceMin..sourceMax`, 도착 쪽 `targetMin..targetMax`, 예 `1` — `0..N`). 가운데는 관계명. 자기 참조는 오른쪽 위에서 나가 오른쪽 아래로 들어온다. Crow's Foot 기호는 그리지 않았다 (글자 표기).
- **상세 패널** : 엔터티(속성·관계, [이동]·[엔터티 상세]·[관리 화면에서 보기]) 또는 관계선(양쪽 엔터티·참여 수·식별 여부·승인).
- **배치** : 저장된 위치가 없는 엔터티만 자동 배치, [자동 배치]·[전체 보기]·[저장된 배치 불러오기]·[배치 저장]. 저장하지 않은 배치가 있으면 모델 변경·화면 이동·새로고침 전에 확인한다.
- **이동·포커스** : `/logicalErd?modelId=&entityId=` → 그 엔터티를 선택·가운데로 (한 번 쓰고 `entityId` 는 지운다). `/logicalModel?modelId=&entityId=` → 그 엔터티 상세를 연다. 없는 ID 는 조용히 무시한다.

레이아웃 API (VIEWER, 개인 화면 설정) :

| API | 내용 |
|---|---|
| `GET /api/logical/model/{id}/erd/layout` | `{version, nodes: {엔터티ID: {x, y}}, viewport}`. 저장한 적 없으면 `version`·`viewport` null |
| `PUT /api/logical/model/{id}/erd/layout` | 요청 좌표를 기존 좌표에 합친다. 다른 모델의 엔터티 ID → `LOGICAL_MODEL_IS_NOT_DEFINED`, 오래된 `version` → 409 `ERD_LAYOUT_CONFLICT`, 잘못된 좌표·배율 → `INVALID_ERD_REQUEST` |

### 8.5 검증

```bash
./gradlew clean build      # 341 tests, 실패 0, 건너뜀 2 (V0SeedMigrationTest 의 PostgreSQL 1건, UploadPerformanceTest 1건). npm ci + typecheck + vite build 포함
# PostgreSQL 18.6 : 빈 격리 DB(metamora_m6b_test)를 만들어 실행 후 삭제 (개발 DB 는 건드리지 않았다)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/metamora_m6b_test SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
METAMORA_TEST_POSTGRES_URL=jdbc:postgresql://localhost:15439/metamora_m6b_test \
./gradlew :metamora-app:test -PfrontendPrebuilt=true --tests '...'   # 6장의 목록 + V0SeedMigrationTest : 101 tests, 실패 0, 건너뜀 0
```

| 자동 테스트 | H2 | PostgreSQL 18.6 |
|---|---|---|
| `LogicalModelIntegrationTest` 8건 (+1 `layout` : 저장·복원·합치기, 사용자별·모델별 격리, 다른 모델 엔터티·오래된 version·잘못된 좌표 거절, VIEWER 저장·NOT_APPROVE 거절, 다른 프로젝트 차단, 엔터티 삭제 후 정리, 모델 삭제 시 배치 삭제) | 통과 | 통과 |
| `SmokeTest` 61건 : `logicalModel` 에 레이아웃 HTTP(저장 → 조회, 409)와 `/api/auth/me` 메뉴(새 ID 포함, 없앤 그룹 ID 미포함) 추가 | 통과 | 통과 |
| `MenuAuthorizationTest` 7건 (새 그룹, `logical-model-edit` 은 USER 부터, 없앤 그룹 ID 는 false) | 통과 | — |
| `V0SeedMigrationTest` (새 테이블 포함 V0 적용·재시작 검증) | 통과 | 통과 |
| 회귀 (6장과 같은 목록) | 통과 | 통과 |

**브라우저 검증** : 빌드한 jar 를 `h2-local`(인메모리)·포트 19940 으로 따로 띄우고 Chrome 에서 ADMIN 계정으로 확인했다. 콘솔 오류 0.

| 구분 | 확인한 것 |
|---|---|
| 메뉴 | 대시보드 → 데이터 사전 → 논리 모델 관리 → 물리 모델 관리 → 데이터 품질 진단 → 운영·시스템 순서, 단일 메뉴 이동, 현재 화면 그룹 열림·강조 |
| 논리 모델 관리 (화면 조작) | 모델 등록 → 엔터티 등록 → 속성 등록(표준 용어 검색·연결 → 도메인 표시), 속성명 중복 오류 알림, 속성 순서 변경, 복합 주 식별자(순서 변경 포함), 두 번째 주 식별자 거절 알림, 보조 식별자, 식별자에 든 속성 삭제 차단, 관계 등록(1 : 0..N, 자기 참조 0..1 : 0..N 식별, 0..N : 1..N → N:M), 관계 승인 후 수정·삭제 비활성, 관계가 있는 엔터티 삭제 차단, 엔터티가 있는 모델 삭제 차단 |
| 논리 ERD | 엔터티 4·관계 5 렌더링과 양 끝 참여 수 값, 식별 실선·비식별 점선, 관계선 클릭 상세, 엔터티 이동 → [배치 저장] → 새로고침 후 위치·화면 복원, 저장 전 이동 시 브라우저 확인, 엔터티 상세 대화상자 → 승인 → 노드·패널 반영, [관리 화면에서 보기] → 상세 열림, 관리 화면 [ERD] → 포커스, 빈 모델 안내, 없는 `modelId`·`entityId` 무시, 다크 테마(`data-theme="dark"`) 색 |
| 회귀 | 물리 ERD(`?schemaId=&tableId=` 포커스), 표준화 품질 진단(카드 5), 데이터 사전(`?tab=`), 테이블 관리(상세 대화상자), 컬럼 관리, 스키마 관리, 대시보드 |

**미검증 (완료로 보지 않음)**

- VIEWER·USER·MANAGER 계정 화면 (버튼 노출은 ADMIN 만 봤다. 권한은 서버 테스트로만 확인).
- 영어 화면, 좁은 화면, Chrome 이외 브라우저, 키보드만으로 조작, OS 다크 모드 전환(속성을 직접 바꿔 확인).
- 엔터티·관계가 많은 모델의 표시 성능과 가독성 (같은 엔터티 쌍의 관계가 여러 개면 선이 겹치고 참여 수 글자만 옆으로 민다).
- PostgreSQL 을 붙인 상태의 화면, 프론트엔드 단위 테스트(테스트 러너가 없다).

### 8.6 이미 만들어진 DB 에 반영할 DDL (수동, 실행하지 않았다)

```sql
create sequence SQ_LGCL_ERD_LYT start with 1 increment by 50;
create table TB_LGCL_ERD_LYT (
    LGCL_ERD_LYT_SN bigint not null, PRJ_SN bigint not null, LGCL_MDL_SN bigint not null, ACNT_SN bigint not null,
    NODE_PSTN_CN text not null, SCRN_X_CRDNT double precision not null, SCRN_Y_CRDNT double precision not null, SCRN_MGNF double precision not null,
    VER_NO bigint not null, REG_DT timestamp(6), MDFCN_DT timestamp(6), RGTR_ID varchar(255), MDFR_ID varchar(255),
    constraint PK_LGCL_ERD_LYT primary key (LGCL_ERD_LYT_SN)
);
create unique index UX_LGCL_ERD_LYT_01 on TB_LGCL_ERD_LYT (LGCL_MDL_SN, ACNT_SN);
alter table TB_LGCL_ERD_LYT add constraint FK_LGCL_ERD_LYT_01 foreign key (LGCL_MDL_SN) references TB_LGCL_MDL;
```

V0 체크섬 불일치는 1차 때와 같은 방식으로 정리해야 한다. 예제 데이터(META-MORA 프로젝트의 자체 물리 모델에 추가한 테이블·컬럼·용어·관계)는 기능에 필요하지 않다.

### 8.7 후속

- **M-7 전** : 엔터티 상세에 물리 테이블 매핑 탭을 붙일 자리(탭 구조)가 있다. 논리 ERD ↔ 물리 ERD 상호 이동은 매핑이 생긴 뒤.
- **논리 ERD** : 큰 모델용 조회 범위(검색·이웃), 평행 관계선 분리, Crow's Foot 기호.
- **공통 ERD** : 배치 상태 관리(저장 전 좌표·version)가 두 화면에 비슷하게 있다. 세 번째 ERD 가 생기면 훅으로 뽑는다.
- **권한** : `logical-model-edit` 처럼 "버튼 권한"이 늘면 `menus` 와 분리된 권한 맵을 검토.

## 9. 2차 후속 : 메뉴 재편 · 데이터 사전 화면 분리 · 등록 시 자동 승인

8.2 의 메뉴 구조와 3장·8.3 의 승인 정책은 아래로 바뀌었다 (8장의 해당 서술보다 이 장이 우선한다).

### 9.1 사이드바

```text
대시보드
시스템 관리        system-management     사용자 관리 / 프로젝트 관리 / 시스템 로그   ← 프로젝트·사용자가 다른 정보의 바탕이라 두 번째
데이터 사전        data-dictionary       표준 도메인 /standardDomain · 표준 단어 /standardWord · 표준 용어 /standardTerm
논리 모델 관리     logical-modeling      논리 모델 관리 / 논리 ERD
물리 모델 관리     physical-modeling     스키마 관리 / 테이블 관리 / 컬럼 관리 / 물리 ERD
데이터 품질 관리   data-quality          표준화 품질 진단
운영 관리          operation-management  공지사항
```

- **데이터 사전** : 한 화면의 탭 3개를 화면 3개로 나눴다 (`DictionaryPage`, 그리드·폼·사용 현황은 그대로). 엑셀 템플릿·다운로드·업로드는 세 항목을 함께 다루므로 세 화면 모두에 둔다.
- `data-dictionary` 는 화면 ID 에서 **그룹 ID** 로 바뀌었고 화면 ID 는 `standard-domain`·`standard-word`·`standard-term`(모두 VIEWER)이다. `MenuAuthorization` 의 `MENU_ROLES`/`MENU_GROUPS`, 화면의 `MenuId`·라우트 가드, 대시보드 카드, 물리 ERD 의 "데이터 사전에서 보기" 를 새 ID·경로로 맞췄다. API 권한(`@PreAuthorize`)은 바꾸지 않았다.
- **예전 주소** `/dataDictionary?tab=standardTerms&searchColumn=…` 는 해당 화면으로 옮겨 준다 (검색 조건 유지, `tab` 이 없으면 표준 도메인).
- 표준화 품질 진단은 `data-quality` 그룹 아래로 들어갔다 (화면 ID·URL·권한 그대로).

### 9.2 등록 시 자동 승인 (논리 모델)

테이블·컬럼·물리 관계·데이터 사전과 같은 규칙으로 맞췄다.

| 항목 | 정책 |
|---|---|
| 등록 | 승인 권한자(관리자·매니저·프로젝트 관리자, `AuthUtil.isApprovalAvailable()`)가 등록한 엔터티·관계는 **바로 승인**. 그 외 사용자가 등록하면 승인 대기 |
| 승인된 엔터티 자체 (이름·설명·삭제) | 누구든 승인취소 후에만 |
| 승인된 엔터티의 속성·식별자 | **승인 권한자만** 변경 (승인 상태 그대로). 등록 직후 승인되므로 관리자가 이어서 속성·식별자를 넣을 수 있어야 한다. 그 외 사용자는 `APPROVED_DATA_CANNOT_BE_UPDATE`/`_DELETED` |
| 승인된 관계 | 누구든 승인취소 후에만 수정·삭제 (물리 관계와 같다) |

화면도 같은 기준이다 : 승인된 엔터티 상세에서 승인 권한자에게는 속성·식별자 버튼이 보이고, 엔터티명·설명은 읽기 전용, [엔터티 삭제] 는 숨긴다.

### 9.3 국제화

새 문구는 모두 `locales/en.ts` 에 영어를 넣었다 (원문이 없으면 타입 검사가 실패한다). 쓰지 않게 된 키(데이터 관리, 데이터 모델링, 데이터 품질 진단, 예전 데이터 사전 설명)는 지웠다. 서버 메시지는 기존 `EnglishMessages` 로 충분해 추가하지 않았다.

### 9.4 검증

- `./gradlew clean build` : 341 tests, 실패 0, 건너뜀 2. PostgreSQL 18.6 격리 DB(`metamora_m6c_test`, 실행 후 삭제) : 101 tests, 실패 0, 건너뜀 0.
- 고친 테스트 : `MenuAuthorizationTest`(새 그룹·화면 ID), `LogicalModelIntegrationTest`(자동 승인, 승인 권한자의 속성 편집, USER 거절·승인 대기 등록), `SmokeTest`(자동 승인 → 승인취소 → 삭제 흐름, `/api/auth/me` 메뉴).
- 브라우저(Chrome, ADMIN, 인메모리 H2) : 메뉴 순서, 표준 도메인·단어·용어 화면과 강조 메뉴, 예전 주소 이동(검색 유지), 품질 진단 위치, 대시보드 카드 링크, 엔터티·관계 등록 직후 승인 표시, 승인된 엔터티에 속성 추가, 엔터티명 읽기 전용·삭제 버튼 숨김, 승인된 관계의 수정·삭제 비활성. 콘솔 오류 0.
- **미검증** : 영어 화면(브라우저 언어를 바꿔 보지 않았다), VIEWER·USER 계정 화면.

## 10. 논리 모델 예제 데이터 (V1) · 권한명 국제화 · 메뉴 순서

### 10.1 `V1__logical_model_seed.sql` (H2·PostgreSQL)

V0 가 META-MORA 프로젝트에 MetaMora 자체 테이블의 **물리 모델 예제**를 넣는 것처럼, V1 은 같은 내용의 **논리 모델 예제**를 넣는다 (데이터만, 스키마 변경 없음).

| 논리 | 물리 예제에서 옮긴 것 | 건수 |
|---|---|---|
| 논리 모델 `META-MORA` | — | 1 |
| 엔터티 | 테이블 한글명·설명 (승인 상태) | 35 |
| 속성 | 컬럼 설명(이름), 순서, NOT NULL → 필수, 컬럼의 표준 용어 연결 | 341 |
| 주 식별자 | PK 컬럼 (`{엔터티명} 식별자`) | 35 (속성 35) |
| 관계 | FK 관계 : 참조되는 엔터티(출발, FK 가 필수면 `1` 아니면 `0..1`) → 참조하는 엔터티(도착, `0..N`). FK 가 PK 면 식별 관계. 관계명 = 참조 속성명에서 "일련번호" 를 뺀 것 | 39 |

- **ID 는 시퀀스에서 받고 부모는 이름으로 찾는다.** V0 처럼 고정 ID 를 쓰면 이미 논리 모델을 등록한 DB 와 겹칠 수 있다. 시퀀스 값을 그대로 ID 로 써도 Hibernate 의 pooled 할당 구간과 겹치지 않는다.
- 표준 용어는 V0 예제 용어 ID 를 쓰되, 그 용어가 지워진 DB 에서는 연결 없이(null) 들어간다.
- 논리 모델은 물리 테이블·컬럼을 참조하지 않는다 — 작성 시점의 물리 예제를 옮겨 적은 것이고 이후에는 따로 바뀐다. V0 의 물리 예제를 바꾸면 V1 도 맞춘다 (`V0SeedMigrationTest` 가 엔터티·속성·관계·식별자 속성 수를 물리 예제와 비교한다).
- **기존 DB** : V1 은 새 버전이라 체크섬 문제 없이 자동 적용된다 (V0 체크섬 불일치와 `TB_LGCL_ERD_LYT` 반영은 8.6 대로 별개). 프로젝트 0 에 이미 `META-MORA` 라는 논리 모델이 있으면 UNIQUE 로 실패하므로 그 경우 이름을 바꾼 뒤 적용한다.
- 저장소 정책 변경 : "V0 만 유지"에서 **V0 + 데이터 전용 V1** 로 (요청에 따른 예외, `CLAUDE.md` 반영).

### 10.2 권한명 국제화

V0 의 권한 설명(`TB_ROLE.ROLE_EXPLN`)이 영문(Administrator, Manager, …)으로 바뀌어 서버가 내려주는 `roleName` 이 영문 고정이 됐다. 화면은 `lib/roles.ts` 의 `roleLabel()` 로 화면 언어에 맞춰 보여 준다 (한국어 : 관리자·매니저·DBA·사용자·뷰어 사용자·권한 미승인 사용자·익명 사용자 / 영어 : `en.ts` 의 `권한|…`).

- 권한 코드(`ROLE_ADMIN`), 영문 설명, 예전 한글 설명을 모두 받는다 — 예전에 만든 DB 와 이미 쌓인 시스템 로그(`roleName` 문자열 저장)도 같은 이름으로 보인다. 모르는 값은 그대로 표시.
- 적용 화면 : 내 정보, 사용자 관리(권한 명), 시스템 로그(권한). 사용자 관리의 권한 선택 값은 권한 코드 그대로다.
- 서버 코드에는 권한 설명 문자열에 의존하는 곳이 없어 바꾸지 않았다.

### 10.3 사이드바 순서

대시보드 → **운영 관리** → 시스템 관리 → 데이터 사전 → 논리 모델 관리 → 물리 모델 관리 → 데이터 품질 관리 (9.1 의 순서보다 이 순서가 우선한다).

### 10.4 검증

- `./gradlew clean build` : 341 tests, 실패 0, 건너뜀 2. PostgreSQL 18.6 격리 DB(`metamora_m6d_test`, 실행 후 삭제) : 101 tests, 실패 0, 건너뜀 0 — Flyway 이력 V0·V1 성공, `META-MORA` 엔터티 35·관계 39 확인.
- 브라우저(Chrome, ADMIN, 인메모리 H2) : 메뉴 순서, 내 정보의 권한명 "관리자"(서버 값은 Administrator), 논리 모델 관리에 `META-MORA` 모델(엔터티 35·관계 39), 논리 ERD 자동 배치 표시. 콘솔 오류 0.
- **미검증** : 이미 데이터가 있는 DB 에 V1 을 적용하는 경우(빈 DB 에서만 실행), 영어 화면.
