# M-6. 논리 데이터 모델 — 설계 결정과 구현 기록 (1차 : 도메인·API, 2차 : 관리 화면·논리 ERD·메뉴)

> 2026-11-12 : 이 문서의 대상(논리 모델·엔터티·속성·식별자·관계)을 바꾸면 변경 이력으로 남는다 (M-9 2차, [model-change-history-m9.md](model-change-history-m9.md) 11장). 저장 구조는 바뀌지 않았다.

> [데이터 모델링 로드맵](../reference/data-modeling-roadmap.md) M-6 중 **1차(저장 구조·관리 API·논리 ERD 조회 API)** 의 기록이다.
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

- ID 는 모두 `SEQUENCE`(`SQ_LGCL_*`, allocation 50). 이름은 공공 표준 약어 규칙 ([db-naming-standard.md](../reference/db-naming-standard.md)) — 필요한 단어(논리 `LGCL`, 엔터티 `ENTT`, 속성 `ATRB`, 식별자 `IDNTFR` 등)와 용어, 새 테이블 6개의 물리 모델을 V0 예제 데이터에 추가했다.
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
# PostgreSQL 18.6 : 빈 격리 DB(lexorma_m6_test)를 만들어 실행 후 삭제 (개발 DB 는 건드리지 않았다)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/lexorma_m6_test \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
./gradlew :lexorma-app:test -PfrontendPrebuilt=true \
  --tests 'com.lexorma.LogicalModelIntegrationTest' --tests 'com.lexorma.StandardizationQualityIntegrationTest' \
  --tests 'com.lexorma.DictionaryUsageIntegrationTest' --tests 'com.lexorma.SmokeTest' \
  --tests 'com.lexorma.ReferenceSyncIntegrationTest' --tests 'com.lexorma.SchemaManagementIntegrationTest' \
  --tests 'com.lexorma.PhysicalModelIntegrationTest' --tests 'com.lexorma.ErdIntegrationTest' \
  --tests 'com.lexorma.DataPortalTemplateTest' --tests 'com.lexorma.TermUploadNameGenerationTest'      # 98 tests, 실패 0, 건너뜀 0
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
# PostgreSQL 18.6 : 빈 격리 DB(lexorma_m6b_test)를 만들어 실행 후 삭제 (개발 DB 는 건드리지 않았다)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/lexorma_m6b_test SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
LEXORMA_TEST_POSTGRES_URL=jdbc:postgresql://localhost:15439/lexorma_m6b_test \
./gradlew :lexorma-app:test -PfrontendPrebuilt=true --tests '...'   # 6장의 목록 + V0SeedMigrationTest : 101 tests, 실패 0, 건너뜀 0
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

V0 체크섬 불일치는 1차 때와 같은 방식으로 정리해야 한다. 예제 데이터(LEXORMA 프로젝트의 자체 물리 모델에 추가한 테이블·컬럼·용어·관계)는 기능에 필요하지 않다.

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

- `./gradlew clean build` : 341 tests, 실패 0, 건너뜀 2. PostgreSQL 18.6 격리 DB(`lexorma_m6c_test`, 실행 후 삭제) : 101 tests, 실패 0, 건너뜀 0.
- 고친 테스트 : `MenuAuthorizationTest`(새 그룹·화면 ID), `LogicalModelIntegrationTest`(자동 승인, 승인 권한자의 속성 편집, USER 거절·승인 대기 등록), `SmokeTest`(자동 승인 → 승인취소 → 삭제 흐름, `/api/auth/me` 메뉴).
- 브라우저(Chrome, ADMIN, 인메모리 H2) : 메뉴 순서, 표준 도메인·단어·용어 화면과 강조 메뉴, 예전 주소 이동(검색 유지), 품질 진단 위치, 대시보드 카드 링크, 엔터티·관계 등록 직후 승인 표시, 승인된 엔터티에 속성 추가, 엔터티명 읽기 전용·삭제 버튼 숨김, 승인된 관계의 수정·삭제 비활성. 콘솔 오류 0.
- **미검증** : 영어 화면(브라우저 언어를 바꿔 보지 않았다), VIEWER·USER 계정 화면.

## 10. 논리 모델 예제 데이터 (V1) · 권한명 국제화 · 메뉴 순서

### 10.1 `V1__logical_model_seed.sql` (H2·PostgreSQL)

V0 가 LEXORMA 프로젝트에 Lexorma 자체 테이블의 **물리 모델 예제**를 넣는 것처럼, V1 은 같은 내용의 **논리 모델 예제**를 넣는다 (데이터만, 스키마 변경 없음).

| 논리 | 물리 예제에서 옮긴 것 | 건수 |
|---|---|---|
| 논리 모델 `LEXORMA` | — | 1 |
| 엔터티 | 테이블 한글명·설명 (승인 상태) | 35 |
| 속성 | 컬럼 설명(이름), 순서, NOT NULL → 필수, 컬럼의 표준 용어 연결 | 341 |
| 주 식별자 | PK 컬럼 (`{엔터티명} 식별자`) | 35 (속성 35) |
| 관계 | FK 관계 : 참조되는 엔터티(출발, FK 가 필수면 `1` 아니면 `0..1`) → 참조하는 엔터티(도착, `0..N`). FK 가 PK 면 식별 관계. 관계명 = 참조 속성명에서 "일련번호" 를 뺀 것 | 39 |

- **ID 는 시퀀스에서 받고 부모는 이름으로 찾는다.** V0 처럼 고정 ID 를 쓰면 이미 논리 모델을 등록한 DB 와 겹칠 수 있다. 시퀀스 값을 그대로 ID 로 써도 Hibernate 의 pooled 할당 구간과 겹치지 않는다.
- 표준 용어는 V0 예제 용어 ID 를 쓰되, 그 용어가 지워진 DB 에서는 연결 없이(null) 들어간다.
- 논리 모델은 물리 테이블·컬럼을 참조하지 않는다 — 작성 시점의 물리 예제를 옮겨 적은 것이고 이후에는 따로 바뀐다. V0 의 물리 예제를 바꾸면 V1 도 맞춘다 (`V0SeedMigrationTest` 가 엔터티·속성·관계·식별자 속성 수를 물리 예제와 비교한다).
- **기존 DB** : V1 은 새 버전이라 체크섬 문제 없이 자동 적용된다 (V0 체크섬 불일치와 `TB_LGCL_ERD_LYT` 반영은 8.6 대로 별개). 프로젝트 0 에 이미 `LEXORMA` 라는 논리 모델이 있으면 UNIQUE 로 실패하므로 그 경우 이름을 바꾼 뒤 적용한다.
- 저장소 정책 변경 : "V0 만 유지"에서 **V0 + 데이터 전용 V1** 로 (요청에 따른 예외, `CLAUDE.md` 반영).

### 10.2 권한명 국제화

V0 의 권한 설명(`TB_ROLE.ROLE_EXPLN`)이 영문(Administrator, Manager, …)으로 바뀌어 서버가 내려주는 `roleName` 이 영문 고정이 됐다. 화면은 `lib/roles.ts` 의 `roleLabel()` 로 화면 언어에 맞춰 보여 준다 (한국어 : 관리자·매니저·DBA·사용자·뷰어 사용자·권한 미승인 사용자·익명 사용자 / 영어 : `en.ts` 의 `권한|…`).

- 권한 코드(`ROLE_ADMIN`), 영문 설명, 예전 한글 설명을 모두 받는다 — 예전에 만든 DB 와 이미 쌓인 시스템 로그(`roleName` 문자열 저장)도 같은 이름으로 보인다. 모르는 값은 그대로 표시.
- 적용 화면 : 내 정보, 사용자 관리(권한 명), 시스템 로그(권한). 사용자 관리의 권한 선택 값은 권한 코드 그대로다.
- 서버 코드에는 권한 설명 문자열에 의존하는 곳이 없어 바꾸지 않았다.

### 10.3 사이드바 순서

대시보드 → **운영 관리** → 시스템 관리 → 데이터 사전 → 논리 모델 관리 → 물리 모델 관리 → 데이터 품질 관리 (9.1 의 순서보다 이 순서가 우선한다).

### 10.4 검증

- `./gradlew clean build` : 341 tests, 실패 0, 건너뜀 2. PostgreSQL 18.6 격리 DB(`lexorma_m6d_test`, 실행 후 삭제) : 101 tests, 실패 0, 건너뜀 0 — Flyway 이력 V0·V1 성공, `LEXORMA` 엔터티 35·관계 39 확인.
- 브라우저(Chrome, ADMIN, 인메모리 H2) : 메뉴 순서, 내 정보의 권한명 "관리자"(서버 값은 Administrator), 논리 모델 관리에 `LEXORMA` 모델(엔터티 35·관계 39), 논리 ERD 자동 배치 표시. 콘솔 오류 0.
- **미검증** : 이미 데이터가 있는 DB 에 V1 을 적용하는 경우(빈 DB 에서만 실행), 영어 화면.

## 11. M-7 1차 이후 달라진 점 (논리·물리 매핑)

- 논리 모델은 여전히 물리 객체를 참조하지 않는다. 연결은 별도 매핑 테이블(`TB_ENTT_TBL_MPNG`·`TB_ATRB_COL_MPNG`)이 맡는다 — [logical-physical-mapping-m7.md](logical-physical-mapping-m7.md).
- **삭제 정책 추가** : 물리 테이블에 매핑된 엔터티, 물리 컬럼에 매핑된 속성은 삭제할 수 없다 (`RELATION_EXISTS`). 매핑을 먼저 해제한다. 모델 삭제는 그대로다 (엔터티가 없어야 하므로 매핑도 없다).
- 매핑 변경도 엔터티의 논리 모델 행을 잠근다 (3장의 잠금과 같은 행).
- 마이그레이션 : V0·V1 은 바꾸지 않았고 매핑 테이블은 `V2__logical_physical_mapping.sql` 로 추가했다. 8.6 의 수동 DDL 과는 무관하다.
- **M-7 2차** : 엔터티 상세 대화상자에 매핑 상태(물리 테이블·속성 매핑 수 / 미매핑)와 [매핑 관리] 버튼, 논리 ERD 의 엔터티 패널에 [매핑 관리] 버튼이 생겼다. 탭·노드 클릭·배치 저장은 그대로다 — [model-mapping-quality-m7.md](model-mapping-quality-m7.md).

## 12. M-8 1차 이후 달라진 점 (논리 ERD 편집)

- 논리 ERD 에 **조회 모드·편집 모드**가 생겼다. 편집 모드(변경 권한 필요)에서 엔터티 추가·수정·삭제, 속성 추가·수정·삭제·순서 변경·표준 용어 연결을 ERD 에서 한다. 9장의 "편집은 논리 모델 관리에서 한다"는 관계·식별자에만 해당한다 — [erd-editing-m8.md](erd-editing-m8.md).
- ERD 에서 여는 엔터티 상세는 조회 모드에서 읽기 전용이다 (전에는 변경 권한이 있으면 바로 편집할 수 있었다). 논리 모델 관리 화면의 엔터티 상세는 그대로다.
- API·서비스·테이블은 바뀌지 않았다. `EntityForm`·`AttributeForm` 을 두 화면이 함께 쓴다.
- **M-8 2차** : 논리 ERD 의 편집 모드에서 관계(연결점을 이어 등록, 수정·삭제·승인)와 식별자(주·보조·복합)도 편집한다. 관계선 끝에 참여 수 표기(Crow's Foot)가 생겼고, 같은 두 엔터티 사이의 관계선을 띄워 그린다. 관계 폼(`relationshipForm.tsx`)·식별자 폼을 두 화면이 함께 쓴다. API·서비스·테이블은 그대로다 — [erd-editing-m8.md](erd-editing-m8.md) 6~10장.

## 13. 후속 1차 (2026-11-18) : 대규모 논리 ERD 조회 범위 · 엔터티 찾기 · 이웃 탐색

목표 : 논리 ERD 가 모델 전체(엔터티·속성·식별자·관계)를 한 번에 받던 것을, 서버가 고른 범위만 받도록 한다. 저장 구조·권한·관리 화면은 그대로. DB·마이그레이션 변경 없음.

### 13.1 기존 구조와 병목

- `GET /api/logical/model/{id}/erd`(`selectGraph`) : 모델 전체를 쿼리 5번(모델, 엔터티+속성+용어+도메인, 식별자, 관계, 관계 속성 짝)으로 읽는다. 쿼리 수는 일정하지만 응답·화면이 모델 크기에 비례한다 (엔터티 3,000개 = JSON 6.5MB, 13.4 측정).
- 같은 응답을 **논리 모델 관리 화면**(`useLogicalGraph` — 엔터티 목록·속성/관계 수)이 쓴다 → 이 API 는 바꾸지 않았다 (제한 없음, 계약 그대로). 논리 ERD 만 새 부분 조회로 옮겼다.
- 물리 ERD(`ErdService`) 재사용 범위 : 같은 한도(한 번 300개·이웃 1~3단계·검색어 100자)·단계별 BFS(단계마다 관계 쿼리 1번)·`totalCount`/`truncated` 계약·화면 형태(범위 조회 / [범위 조회로] / 단계 선택 + [이웃 탐색] / 이전·다음 페이지)를 따랐다. 물리 DTO·저장소는 쓰지 않는다 (논리 전용 쿼리·DTO).

### 13.2 API (추가만, VIEWER 이상)

| API | 내용 |
|---|---|
| `GET /api/logical/model/{modelId}/erd/part?keyword=&page=0&size=100` | 이름·설명에 검색어가 든 엔터티(비우면 모델 전체)를 이름순 page 번째 size 개(1~300). 대소문자 무시, `%`·`_` 는 글자 그대로 (`!` 이스케이프) |
| `GET /api/logical/model/{modelId}/erd/neighbors/{entityId}?depth=1` | 관계의 출발·도착 모두를 따라 1~3단계. 자기 참조·순환·복수 관계·여러 경로는 엔터티 한 번씩. 300개에서 그 단계를 멈추고 `truncated` |
| `GET /api/logical/model/{modelId}/erd/entities?keyword=&size=20` | 엔터티 찾기 : ID·이름·승인 여부만(속성 없음), 이름순 최대 size 개(1~50) + 전체 수 |

응답 `GraphPart` = `Graph` 와 같은 `entities`·`relationships` + `totalCount`(범위 조회는 조건에 맞는 전체 수, 이웃은 담은 수) · `truncated` · `modelEntityCount`(모델 전체 엔터티 수) · `outsideRelationships`(엔터티 ID → 상대가 범위 밖이라 빠진 관계 수). **관계는 양 끝이 모두 범위 안인 것만** 준다 — 화면이 없는 노드를 가리키는 선을 그리지 않고, 빠진 관계는 수로 알린다. 범위 밖 엔터티·다른 모델·다른 프로젝트의 엔터티로 이웃을 찾으면 `LOGICAL_MODEL_IS_NOT_DEFINED`(엔터티를 찾을 수 없음), 다른 프로젝트 모델은 모델 없음. 한도 밖 입력은 `INVALID_LOGICAL_MODEL`.

조회 수 (`LogicalModelService.graphPart`) : 모델, 조건 수, 범위 ID, 모델 엔터티 수, 식별자(범위 엔터티만), 걸린 관계(ID·양 끝 — 범위 안/밖 나누기), 범위 안 관계(ID 로, 양 끝 엔터티 fetch), 관계 속성 짝, 엔터티+속성 = 엔터티 수와 무관하게 9개 안팎, 이웃은 단계마다 +1. 처음에는 범위 안 관계를 `양 끝 IN (…) and IN (…)` 로 읽었는데 H2 에서 엔터티 300개일 때 0.1~0.7초 걸려(측정), 걸린 관계 목록에서 ID 를 골라 기본 키로 읽도록 바꿨다.

### 13.3 화면 (`LogicalErdPage`)

- 상단 : 논리 모델 선택 · 엔터티 검색(엔터티명 / 설명) [조회] · 조회/편집 모드. 상태 줄 : `모델 전체` 또는 `"검색어"` · `전체 N개 중 a–b` · 관계 수 · 범위 밖 관계 수 · (모델 엔터티 N개) · 한도 안내 · 이전/다음 100개, 이웃이면 [범위 조회로] + `X 기준 n단계 이웃 · 엔터티 n개`.
- 오른쪽 패널 : 기존 '엔터티로 이동'(지금 범위) + **엔터티 찾기**(서버, 300ms 입력 멈춤 뒤, 검색어마다 따로 캐시 — 늦게 온 이전 응답이 덮지 않음; 범위 안이면 그 자리로 이동, 밖이면 그 엔터티의 이웃을 불러와 포커스). 엔터티 패널에 단계(1~3) + [이웃 탐색], 관계 수는 범위 밖 포함, "이 중 n개는 상대 엔터티가 조회 범위 밖이라 그리지 않았습니다" 안내, 엔터티 삭제 판정도 범위 밖 관계를 센다.
- 기본 범위는 모델 전체 첫 100개 (엔터티 100개 이하 모델은 예전과 같은 화면). 상태 구분 : 불러오는 중 / 검색 결과가 없습니다 / 등록된 엔터티 없음(빈 모델) / 선택한 엔터티에 연결된 관계가 없습니다 / 한도·페이지 안내 / 조회 오류.
- 이동 : `/logicalErd?modelId=&entityId=` 의 엔터티가 지금 범위에 없으면 그 엔터티의 1단계 이웃을 불러와 포커스한다 (물리 ERD 와 같은 방식). 없는·다른 모델의 ID 는 오류 안내만. 관리 화면·매핑 화면 이동은 그대로.
- 관계 등록 폼 : 상대가 범위 밖일 수 있어 열 때 모델의 엔터티 이름 목록(`GET /model/{id}/entity`, 속성 없음)을 받는다. (후속 3차부터는 목록을 받지 않고 대화상자에서 모델 안을 검색해 고른다 — 15.4)

### 13.4 배치 저장 · 복원

- 서버는 그대로 (요청 좌표를 저장된 좌표에 합친다, 다른 모델의 엔터티는 거절, `ERD_LAYOUT_CONFLICT`, 사용자·모델별).
- 화면 : 옮긴 좌표를 범위를 바꿔도 기억한다 ("저장하지 않은 배치 n개"). [배치 저장] 은 지금 화면의 엔터티 + 범위를 바꾸기 전에 옮겨 둔 엔터티를 보낸다 — 저장하지 않은 범위 밖 엔터티의 저장 좌표는 서버에 있는 그대로다. 새로 불러온(좌표 없는) 엔터티만 기존 배치 오른쪽에 자동 배치하고, 모델 전체를 다시 배치하지 않는다. [저장된 배치 불러오기]·페이지 이동 확인은 그대로.

### 13.5 성능 측정 (`LogicalErdPerformanceTest`, `LEXORMA_PERF=true` 일 때만)

H2(테스트 메모리 DB, 개발 노트북), 엔터티마다 속성 8개·주 식별자 1개, 관계 = 사슬(앞 → 다음) + 허브(첫 엔터티 → 20번째마다), 두 번 잰 평균 (첫 번째는 준비). 문장 수 = Hibernate 준비 문장, JSON = 응답 직렬화 크기, 할당 = 요청 스레드 할당 바이트.

| 엔터티 | 조회 | ms | 문장 | JSON KB | 할당 MB | 응답 엔터티 / 관계 |
|---|---|---|---|---|---|---|
| 100 | 전체 selectGraph | 18 | 5 | 217 | 2 | 100 / 103 |
| 100 | 범위 100 | 18 | 9 | 217 | 3 | 100 / 103 |
| 1,000 | 전체 | 46 | 5 | 2,175 | 15 | 1,000 / 1,048 |
| 1,000 | 범위 100 · 300 | 26 · 30 | 9 | 216 · 652 | 3 · 9 | 100 / 103 · 300 / 313 |
| 1,000 | 이웃 1단계(허브) · 2단계 · 3단계(허브) | 7 · 8 · 28 | 9~11 | 110 · 119 · 537 | 2 · 2 · 8 | 51 · 55 · 249 |
| 3,000 | 전체 | 73 | 5 | 6,533 | 42 | 3,000 / 3,148 |
| 3,000 | 범위 100 · 300 | 13 · 33 | 9 | 216 · 652 | 4 · 9 | 100 / 103 · 300 / 313 |
| 3,000 | 이웃 1단계(허브) · 2단계 · 3단계(허브, 한도) | 20 · 19 · 40 | 9~10 | 327 · 336 · 649 | 5 · 5 · 9 | 151 · 155 · 300 |
| 3,000 | 검색 "0042" · 엔터티 찾기 20 | 5 · 1 | 9 · 3 | 23 · 1 | 1 · 0 | 11 · 20 |

PostgreSQL 18.6 (로컬 Docker, 빈 임시 DB) 같은 측정 : 엔터티 3,000개 — 전체 213ms·77MB 할당 / 범위 100 25ms / 범위 300 38ms / 이웃 1·2·3단계 25·24·36ms / 검색 10ms / 찾기 1ms (문장 수·JSON 크기는 H2 와 같다). 1,000개 — 전체 93ms / 범위 100 23ms / 이웃 3단계 30ms.

부분 조회의 문장 수는 모델 크기와 무관하고, 응답 크기는 고른 엔터티 수에 비례한다 (3,000개 모델에서 전체 6.5MB → 첫 화면 216KB). 화면 렌더링·ELK 배치 시간은 따로 재지 않았다 (13.7). 새 인덱스는 필요하지 않았다 (엔터티 300개 범위에서 ms 단위).

### 13.6 검증

- 전체 : H2 `./gradlew build --rerun-tasks -PfrontendPrebuilt=true` 1,050건 실패 0 · 건너뜀 46, PostgreSQL 18.6(빈 임시 DB) 1,050건 실패 0 · 건너뜀 31 — 1,042 + 새 8 (통합 7 + 측정 1, 측정은 LEXORMA_PERF 없으면 건너뜀이라 건너뜀 +1). `npm run typecheck`·`npm run build` 통과.
- `LogicalErdScopeIntegrationTest` 7건 (H2·PostgreSQL) : 이웃(출발·도착, 자기 참조·순환·복수 관계·여러 경로 한 번씩, 2단계, 범위 밖 관계 수, 고립, 속성 짝·엔터티 내용 = 전체 조회와 같음) / 거절(0·4단계, 없는 ID, 같은 프로젝트 다른 모델·다른 프로젝트 엔터티, 다른 프로젝트 모델의 범위 조회·찾기) / 범위 조회(모델 전체 = 전체 조회 순서, 이름·설명·대소문자, `%`·`_` 글자 그대로, 결과 없음, 페이지·truncated, 한도·검색어 길이, 빈 모델) / 엔터티 찾기 / 한도(허브 이웃 305 → 300·truncated, 응답 관계 일관성, 범위 조회 300) / 배치(부분 저장 뒤 범위 밖 좌표 그대로, 409, 모델별) / VIEWER(범위·이웃·찾기·내 배치 저장 가능, 엔터티 삭제 403). 모든 응답에서 관계 양 끝이 응답 엔터티 안에 있고 중복이 없음을 확인.
- 헤드리스 Chrome (임시 H2 앱, 엔터티 317개 모델 : 허브 1 + 이웃 305, 사슬 10(순환·자기 참조), 고립 1) : ADMIN 한국어·영어 × 라이트·다크 × 1440·375 8조합, VIEWER·USER·DBA 각 한국어 라이트 1440·영어 다크 375 — 모델 선택 → 모델 전체 첫 100(전체 317·범위 밖 관계 217·한도 안내) → 다음 100 → 검색 "사슬"(10개·관계 11) → 결과 없음 안내 → 엔터티 찾기로 첫 페이지 밖 '허브 이웃 250' (이웃 불러와 포커스) → 허브 이웃 = 300 한도 → 사슬 05 1단계(범위 밖 관계 2, 패널 안내) → 2단계(5개) → 자동 배치("저장하지 않은 배치 5개") → [범위 조회로] 뒤에도 5개 → 저장 → 범위 밖 '고립 엔터티' 저장 좌표 그대로 → 고립 엔터티 이웃 = 관계 없음 안내 → [관리 화면에서 보기] (엔터티 상세 열림) → URL 진입(첫 페이지 밖 엔터티 포커스) → 없는 ID 오류 안내 → 물리 ERD 회귀. VIEWER 는 편집 모드 버튼 없음. 가로 스크롤 없음, 콘솔 오류는 의도한 없는 ID 400 뿐. MANAGER 계정 화면은 확인하지 않았다.

### 13.7 알려진 한계 · 후속

- 논리 모델 관리 화면은 여전히 전체 조회(제한 없음)를 쓴다 — 큰 모델에서 관리 목록도 서버 페이징이 필요하면 별도 작업. (후속 3차에서 서버 페이징으로 바꿨다 — 15장)
- 브라우저의 렌더링·ELK 자동 배치 시간은 측정하지 않았다 (응답 크기·서버 시간만). 한 번에 그리는 엔터티는 최대 300개(기본 100개).
- 엔터티 찾기·범위 검색은 엔터티 이름·설명만 (속성명 검색 없음 — 물리 ERD 도 컬럼명을 검색하지 않는다).
- 범위 밖으로 이어진 관계는 선으로 그리지 않고 수만 보인다 (상대 쪽 '유령 노드' 표시는 하지 않음).
- 주제 영역·서브타입·평행 관계선 분리·Crow's Foot·DB UNIQUE 대소문자 정책은 이번 범위 밖 (pending-work [M-6]).

## 14. 후속 2차 (2026-11-19) : 논리 ERD 관계선 표현 — 평행 관계선 분리 · Crow's Foot · 자기 참조

목표 : 같은 두 엔터티 사이의 여러 관계와 자기 참조 관계를 하나씩 알아보고 고를 수 있게 한다. 저장 구조·관계 의미·부분 조회·배치·권한은 그대로. 화면 코드만 바뀌었다 (서버·DB 변경 없음).

### 14.1 기존 구조와 문제

- 관계선은 `features/logical/erdNodes.tsx` 의 사용자 정의 선(React Flow `getSmoothStepPath` + `BaseEdge`)이고, 끝 기호(Crow's Foot SVG marker 4종)·실선/점선(식별/비식별)·엔터티 쪽 참여 수 글자는 이미 있었다 (M-6 2차·M-8).
- 평행 관계 : 같은 두 엔터티 사이의 관계를 끝점만 16px 씩 아래로 밀었다 → 꺾이는 세로 구간이 같은 x 에 겹쳐 가운데에서는 고를 수 없고, 관계가 많으면 끝점이 헤더(52px) 밖으로 나갔다. 응답 순서에 따라 자리가 바뀔 수 있었다.
- 자기 참조 : 오른쪽 위(8px) → 오른쪽 아래(44px) 고리인데 여러 개면 같은 폭이라 세로 구간이 겹쳤다.

### 14.2 관계 데이터와 기호 (저장값 그대로 — 추정 없음)

`TB_LGCL_REL` : 출발(`ORGNL_ENTT_SN`, 부모 = 참조되는 쪽)·도착(`TRGT_ENTT_SN`), 관계명, `IDNTF_YN`(식별, not null), 출발·도착 쪽 최소 참여 수(`*_MIN_CNT` not null, 0 이상)·최대(`*_MAX_CNT`, null = 여러 건, 1 이상·최소 이상 — CHECK). 출발 쪽 참여 수 = 도착 1건에 이어지는 출발 건수(IE 표기와 같은 방향), 기호는 그 엔터티 쪽 끝에 그린다. 따로 저장하는 '관계 유형'은 없다 (1:1·1:N·N:M 은 양 끝 최대에서 나온다, N:1 은 방향을 바꾼 1:N).

| 그 엔터티 쪽 참여 수 | 끝 기호 |
|---|---|
| 최소 ≥1, 최대 1 (1..1) | 막대 + 막대 |
| 최소 0, 최대 1 (0..1) | 원 + 막대 |
| 최소 ≥1, 최대 null 또는 ≥2 (1..N, 1..5) | 막대 + 까치발 |
| 최소 0, 최대 null 또는 ≥2 (0..N, 0..5) | 원 + 까치발 |

최소가 없는 관계는 저장 구조상 없다 (not null) — 필수·선택은 모든 관계에서 표현된다. 표현할 수 없는 것 : 최대가 2 이상인 유한값(0..5)·최소 2 이상(2..N)은 기호로는 '여러 건'·'필수'까지만이라, 엔터티 옆 숫자 글자(그대로 둠)로 정확한 수를 본다 (범례에 적음). 관계명·카디널리티(`1 : 0..N`)·식별 여부·속성 짝은 상세 패널(그대로)에서 본다. 식별 여부는 `IDNTF_YN` 만 쓴다 (속성 짝·물리 FK 로 계산하지 않는다) : 실선 = 식별, 점선(6 4) = 비식별 — 기존 규칙 그대로.

### 14.3 평행 관계선·자기 참조 (`features/erd/relationLanes.ts` + `erdNodes.tsx`)

- 묶음 = 방향과 무관한 두 엔터티 쌍(자기 참조는 한 엔터티). 묶음 안 순서 = 관계 ID 오름차순 (`relationLanes`, 관계 수만큼 한 번 훑는 순수 함수, 응답 순서와 무관 — 다시 조회·범위를 바꿔도 같은 자리). 관계 방향은 그대로 (A → B 와 B → A 가 한 묶음이어도 각자의 출발·도착 연결점을 쓴다).
- 일반 관계 : 차선 k(가운데 0 대칭)마다 끝점을 8px 씩(모두 ±20px 안 — 헤더 안) 벌리고, 꺾이는 세로 구간(`centerX`)을 18px 씩, 돌아 나가는 폭(`offset`)을 20 + 10·순서로 달리한다 → 시작·끝 기호와 가운데 경로가 모두 갈라진다.
- 자기 참조 : 오른쪽 바깥 고리, 관계마다 폭을 20 + 14·순서로 키운다 (안쪽부터 ID 순). 연결점이 헤더에 있어 속성 수·축소 표시(행 생략)와 무관하다.
- 경로는 노드 좌표에서 매번 계산하므로 노드 이동·확대/축소·자동 배치·범위 변경 뒤에도 따라온다. 별도 저장 없음. 자동 배치(ELK)는 노드 위치만 쓰고 선 경로는 쓰지 않는다 (그대로).
- 선택 : 관계선마다 실제 관계 ID(`r<id>`)로 고르고, 고르는 영역(`interactionWidth`)을 16 → 12px 로 줄여 평행 차선 간격보다 좁게 했다. 고른 관계만 굵게(2.5px)·강조색·위에 그린다 (색만으로 구분하지 않음). 고른 관계가 다시 조회에서 사라지면 선택을 지운다 (기존).
- 범례 : 실선/점선 + Crow's Foot 4종(같은 marker 로 그린 짧은 선) + 숫자 설명, '최대 2 이상은 까치발, 정확한 수는 숫자' 안내.
- 물리 ERD 는 바꾸지 않았다 (차선 계산은 화면 전용 순수 함수라 나중에 쓸 수 있다).


### 14.4 부분 조회·배치와의 관계

범위 API(`/erd/part`·`/erd/neighbors`·`/erd/entities`)·한도(페이지 100·최대 300·단계 1~3·찾기 50·검색어 100)·배치 저장·합치기는 그대로다. 차선은 지금 받은 관계(양 끝이 범위 안)만으로 매기므로, 범위를 바꾸면 같은 쌍의 관계 수가 달라질 때만 자리가 바뀐다 (ID 순서는 그대로). 전체 그래프를 더 조회하지 않는다.

### 14.5 검증

- 화면 전용 순수 함수라 프런트엔드 테스트 도구(없음)를 새로 넣지 않았다. `relationLanes`·`endShift` 는 Node 로 따로 실행해 확인했다 : 응답 순서를 섞어도 같은 차선, A → B·B → A 가 한 묶음(5·10·20·30·40 → 0/2·0/3·1/3·2/3·1/2), 자기 참조는 따로, 9개 차선의 끝점이 ±20px 안. `npm run typecheck`·`npm run build` 통과.
- 헤드리스 Chrome(CDP, 임시 앱·임시 H2) — 모델 'M6 관계선' : 부서 → 직원 3개(1 : 0..N, 0..1 : 1, 0..N : 0..N) + 직원 → 부서 1개(1 : 0..1) + 직원 자기 참조 3개 + 고객 → 고객 상세 식별 1개(1 : 0..1). 확인 항목 : 관계선 8개·ID 중복 없음, 평행 4개의 경로·시작점이 모두 다름, 자기 참조 고리가 ID 순으로 바깥으로(최대 x 854·868·882), 8개 모두 양 끝 기호 = 저장된 최소·최대, 실선/점선 = 식별 여부, 범례 기호 4개, **실제 마우스 클릭**으로 평행·자기 참조 7개를 하나씩 → 상세 패널이 그 관계·그 선만 굵게, 노드 끌기 뒤 선이 따라오고 분리 유지, 배치 저장 → 다시 열어 같은 자리, 축소(속성 행 0개)에서도 관계선 8개·다시 확대하면 행 복원, 자동 배치 뒤 분리 유지, 범위(고객 상세 이웃)에서 관계 1개만, 물리 ERD 회귀(노드·관계선·가로 스크롤 없음), 콘솔 오류 없음.
  - ADMIN 한국어·영어 × 라이트·다크 × 1440·375px 8조합 모두 통과. VIEWER·USER·DBA·**MANAGER**(임시 계정을 만들어 확인) 각 한국어 라이트 1440 + 영어 다크 375 — 모두 같은 항목 통과(조회·선택·상세·배치 저장은 VIEWER 부터 — 기존 권한 그대로). 버튼 노출(한국어 1440) : [편집 모드] 는 USER·DBA·MANAGER·ADMIN 에만, VIEWER 는 없음, [논리 모델 관리]·[배치 저장]·[자동 배치]·범례는 모든 역할.
  - 처음 스크립트는 교차점(두 선이 겹치는 점)을 눌러 다른 관계가 잡혔다 — 선 위의 '그 선이 맨 위인 점'(`elementFromPoint`)을 누르게 고쳤다. 375px 에서는 저장된 화면 위치가 다른 폭 기준이라 [전체 보기] 후 확인했다.
- 브라우저 성능(헤드리스 Chrome 1440×1000, 임시 H2, 모델 'M6 성능' : 엔터티 300 × 속성 8, 관계 924 = 허브 299 + 평행 쌍 298×2 + 자기 참조 29, 3번 중앙값) : 첫 화면(범위 100, 탐색 → 그림, 서버 응답 포함) 683ms, 자동 배치 100개 341ms, 허브 이웃 300개 전환(찾기 선택 → 그림, 새 엔터티 자동 배치 포함) 197ms, 자동 배치 300개 2,093ms(ELK 워커), 관계선 클릭 → 상세 패널 28ms, 노드 끌기 마지막 이동 → 반영 51ms, 휠 확대 → 반영 28ms, 검색 전환 17ms. 화면 밖 노드·선은 그리지 않는다(첫 화면 DOM 노드 31·관계선 133, 이웃 300 전체 보기 노드 32·관계선 236 — 최소 배율이라 축소 표시). 경로 계산만 따로 잴 수는 없었다(React 렌더링 안에 섞여 있다 — 위 반응 시간에 포함). 자동 배치 직후는 최소 배율(0.05)이라 축소 반응 대신 확대 반응을 쟀다. 엔터티 3,000개 모델은 화면이 한 번에 300개까지만 받으므로 300개가 브라우저 최대치다.
- 전체 테스트 : H2 `build` 1,050건 실패 0 · 건너뜀 46, PostgreSQL 18.6(빈 임시 DB) 1,050건 실패 0 · 건너뜀 31 — 서버 변경이 없어 1차와 같은 수.

### 14.6 한계·후속

- 같은 쌍 관계가 아주 많으면(10개 이상) 끝점 간격이 ±20px 안에서 좁아진다(겹치지는 않음). 관계명 글자는 선 가운데에 놓여 많을 때 서로 가까울 수 있다.
- 다른 쌍의 선끼리 교차·겹침을 피하는 경로 탐색(edge routing)은 하지 않는다 (ELK 는 노드 배치만).
- 물리 ERD 의 평행 FK 표현은 바꾸지 않았다.

## 15. 후속 3차 (2026-11-19) : 논리 모델 관리 서버 페이징 · 조회 구조 개선

목표 : 논리 모델 관리 화면(`/logicalModel`)이 목록·수를 위해 모델 전체 그래프를 받던 것을 서버 요약·페이지·상세로 나눈다. 저장 구조·업무 규칙·권한·승인·동시성은 그대로, DB·마이그레이션 변경 없음.

### 15.1 기존 구조와 병목

- 관리 화면은 `GET /api/logical/model/{id}/erd`(`selectGraph`, 전체 엔터티·속성·식별자·관계·관계 속성 짝)를 한 번 받아 엔터티 표(이름·설명·속성 수 = `attributes.length`·관계 수 = 출발 또는 도착이 그 엔터티인 관계 수·승인), 관계 표(전체), 탭 수 `엔터티 (n)`·`관계 (n)`, 모델 삭제 가능 여부(엔터티 수), 관계 등록 대화상자의 엔터티 목록, `?entityId=` 진입 시 그 모델의 엔터티인지 확인에 썼다. 검색·정렬·페이지는 없었다. 엔터티 3,000개 모델에서 첫 화면 응답 6.5MB.
- 엔터티 상세 대화상자(관리·ERD 공용)는 관계 탭을 위해 **모델 전체 관계**(`GET /model/{id}/relationship`, 관계마다 속성 짝 판정 — 주 식별자를 엔터티마다 따로 읽어 쿼리 수가 관계 수에 비례)를 받아 걸렀다 (3,000개 모델의 허브 상세 1.5MB·68문장).
- 논리 ERD 의 관계 대화상자는 모델 엔터티 이름 목록 전체(`GET /model/{id}/entity`, 제한 없음)를 받았다.
- 식별자 수는 관리 목록에 없었다 (엔터티 상세의 식별자 탭에만) → 이번에도 목록에 넣지 않았다.

### 15.2 API (추가만, VIEWER 이상 — 기존 API 계약 그대로)

| API | 내용 |
|---|---|
| `GET /api/logical/model/{modelId}/summary` | `{ modelId, entityCount, relationshipCount }` — 모델 전체 수(검색과 무관). 화면 상단 탭 수·모델 삭제 가능 여부 |
| `GET /api/logical/model/{modelId}/entity/data?page=0&size=10&sort=entityName,asc&searchQuery=` | 공용 그리드 형식 `{ data, recordsTotal, recordsFiltered }`. 행 = `{ id, entityName, entityDescription, isApproval, attributeCount, relationshipCount, modifiedAt }` (속성·관계 목록 없음) |
| `GET /api/logical/model/{modelId}/relationship/data?page=0&size=10` | 공용 그리드 형식. 관계 ID 순(예전 전체 목록과 같은 순서), 행마다 속성 짝과 판정(관계 표의 '속성 짝' 열) |
| `GET /api/logical/entity/{entityId}/relationship` | 출발·도착 어느 쪽이든 그 엔터티인 관계와 속성 짝 (엔터티 상세의 관계 탭) |

- 페이지 크기 1~100 (공용 그리드 선택지 10·25·50·100과 같다; ERD 의 300 한도는 관리 목록에 쓰지 않는다), page 0 이상 — 벗어나면 400 `INVALID_LOGICAL_MODEL`("한 번에 1~100개 엔터티/관계까지 조회할 수 있습니다"). 범위 밖 페이지는 빈 `data` + 전체 수(화면이 마지막 페이지로 옮긴다). `sort` 는 기존 `PageableUtil.getGridPageable` 로 읽는다 (다른 그리드와 같은 계약 — 잘못된 방향은 보완에서 400 `INVALID_PAGE_REQUEST`, 15.10). (2026-11-19 [COMMON-LIST] 부터 크기·페이지·정렬 검사는 공용 `PageableUtil` 이 하고 오류 코드는 `INVALID_PAGE_REQUEST`, 모르는 정렬 필드도 무시가 아니라 400 — reference/list-query-policy.md)
- 검색 : 이름·설명, 대소문자 무시, `%`·`_` 는 글자 그대로(`!` 이스케이프), 앞뒤 공백 무시, 100자 — 논리 ERD 부분 조회(13장)와 같은 `likePattern`. 예전 관리 화면에는 검색이 없었으므로 새 기능이다 (속성명·관계명 검색은 하지 않는다).
- 정렬 허용 목록 : `entityName`(기본)·`entityDescription`·`isApproval` + `asc|desc`. 모르는 필드는 무시(이름순), 항상 엔터티 ID 오름차순을 마지막 기준으로 붙여 같은 값끼리 순서가 고정된다 (페이지 사이 겹침·빠짐 없음 — 테스트). 클라이언트 값을 SQL 에 잇지 않는다 (QueryDSL 경로 매핑). 예전 목록 순서 = 이름 → ID 와 같다.
- 격리 : 모델은 로그인 프로젝트에서만 찾고(`model(projectId, modelId)`), 엔터티의 관계는 로그인 프로젝트 엔터티만 — 다른 프로젝트·없는 ID 는 예전처럼 '찾을 수 없음'(400 `LOGICAL_MODEL_IS_NOT_DEFINED`).

### 15.3 집계 기준과 SQL (`logical/repository/LogicalModelQueryRepository`)

- 속성 수 = 그 엔터티의 속성 행 수 (승인 여부 무관 — 속성에는 승인 상태가 없다, 삭제는 행 삭제). 관계 수 = 출발이 그 엔터티인 관계 + 도착만 그 엔터티인 관계 (자기 참조 1번, 복수·반대 방향은 각각) — 예전 화면 `filter(source === id || target === id).length` 와 같다. 모델 관계 수는 관계 행 수 — 엔터티별 관계 수의 합과 다르다 (양 끝에서 한 번씩; 테스트 모델 8 vs 13).
- 쿼리 : ① 페이지 엔터티(검색·정렬·OFFSET·LIMIT, 컬럼만) ② 조건 전체 수 ③ 그 엔터티들의 속성 수 `group by` ④ 출발 관계 수 `group by` ⑤ 도착 관계 수(`출발 ≠ 도착`) `group by` — 모델 확인까지 6문장, 페이지 크기·모델 크기와 무관 (조인 한 번으로 세지 않아 곱이 생기지 않고, 엔터티마다 세지 않는다).
- 처음에는 목록 쿼리 안의 상관 서브쿼리로 셌는데 (문장 3개), PostgreSQL 이 OFFSET 으로 건너뛰는 행까지 서브쿼리를 계산해 엔터티 3,000개 모델의 마지막 페이지가 **830ms**, H2 는 모델 크기에 따라 첫 페이지 2 → 9ms·할당 9MB 로 늘었다 (측정) → 페이지 ID 를 먼저 고르고 묶어 세는 방식으로 바꿨다 (마지막 페이지 3ms).
- 관계 페이지 : ID 페이지(+ 전체 수) → 양 끝 엔터티 함께 읽기 → 양 끝 엔터티의 주 식별자 한 번(`withEndKeys`) → 관계 속성 짝 한 번 — 6문장. 엔터티의 관계도 같은 `withEndKeys` (예전 모델 전체 관계 조회의 엔터티별 식별자 읽기를 하지 않는다). 기존 `GET /model/{id}/relationship` 는 그대로 둔다 (화면은 더 쓰지 않는다).
- 실행 계획 (PostgreSQL 18.6, 엔터티 3,000개, 마지막 페이지) : 페이지 = `UX_LGCL_ENTT_01(LGCL_MDL_SN, ENTT_NM)` 인덱스 스캔 + 증분 정렬 1.4ms, 속성 수 = `UX_LGCL_ATRB_01` 비트맵 0.04ms, 출발·도착 관계 수 = `IX_LGCL_REL_02`·`IX_LGCL_REL_03` 0.02ms. **새 인덱스는 필요 없다.** 설명·승인 정렬은 모델 엔터티를 정렬하지만 3,000개에서 2~5ms.

### 15.4 화면 (`LogicalModelPage` — 레이아웃·대화상자 그대로)

- 모델마다 내용 영역을 새로 그린다(`key = 모델 ID`) : 페이지·검색·정렬·탭이 처음으로 돌아가고, 조회 키가 `['logical-manage', 프로젝트, 모델, …]` 라 앞 모델의 늦은 응답이 다른 모델에 보이지 않는다.
- 탭 수 = 요약(모델 전체), 목록 위 줄 = 공용 그리드와 같은 '페이지당 n 행 표시' + 건수(검색 중이면 '검색 결과 a개 (모델 전체 b개)'), 아래 = 공용 `Paging`(그리드에서 내보냄). 엔터티 검색(엔터티명 / 설명, [조회]·Enter, 검색 중 [초기화]), 엔터티명·설명·승인 상태 머리글 정렬(공용 그리드와 같은 표시). 관계 탭은 처음에는 페이지만이었고 보완에서 검색·식별 필터·정렬을 더했다 (15.10).
- 상태 : 모델 없음 / 등록된 엔터티 없음 / 검색된 데이터가 없습니다 / 불러오는 중 / 목록·요약 조회 실패(빈 목록으로 보이지 않게 따로 표시) / 상세·관계 탭 조회 실패.
- 변경 뒤 : 엔터티 등록·상세에서의 변경(속성·식별자·승인·삭제)·관계 등록·수정·삭제·승인·속성 짝 저장은 이 모델의 조회 키만 무효화해 요약·현재 페이지를 다시 받는다 (수는 서버가 다시 센다 — 화면이 더하거나 빼지 않는다). 빈 페이지가 되면(마지막 행 삭제·검색 결과 감소) 마지막 페이지로 옮긴다. 등록한 엔터티는 정렬 위치와 무관하게 상세가 바로 열린다(예전과 같다).
- 엔터티 상세 : 열 때 `GET /entity/{id}`(그대로) + `GET /entity/{id}/relationship`. 대화상자라 다른 엔터티를 고르려면 닫아야 하고, 조회 키가 엔터티 ID 라 늦은 응답이 다른 엔터티 상세를 덮지 않는다. 관계를 아직 못 받았으면 [엔터티 삭제] 를 누를 수 없다 (서버도 막는다).
- 관계 등록·수정 대화상자(관리·ERD 공용) : 출발·도착 엔터티를 **모델 안 검색**(논리 ERD 엔터티 찾기 API `GET /erd/entities`, 이름순 50개 + '전체 n개 중 50개만' 안내, 250ms 입력 멈춤)으로 고른다 — 관리 목록 페이지나 ERD 범위 밖 엔터티도 고를 수 있고, 모델 전체 목록을 받지 않는다. 고른 엔터티는 검색어를 바꿔도 남는다. 다른 모델·프로젝트 엔터티는 서버가 거절(그대로). 관계 속성 짝 편집은 기존 `GET/PUT /relationship/{id}/attributes`(부모 주 식별자·자식 속성 후보) 그대로.
- 동시성·승인 : 상세의 수정 요청은 상세 조회의 `modifiedAt` 을 그대로 보낸다 (목록 행의 값은 쓰지 않는다) — 충돌 409 안내·입력 보존·승인된 엔터티/관계 변경 차단 그대로.

### 15.5 화면 이동 · URL

- `/logicalModel?modelId=&entityId=` (논리 ERD 의 [관리 화면에서 보기] 등) : 목록 페이지와 무관하게 `GET /entity/{id}` 로 확인해 **그 모델의 엔터티면** 상세를 연다 (예전에는 전체 그래프에서 찾았다 — 같은 조건). 없는 ID·다른 프로젝트·다른 모델 엔터티는 예전처럼 열지 않고 URL 의 entityId 만 지운다 (서버는 400, 존재 여부를 따로 알리지 않는다). 목록 페이지는 옮기지 않는다 (예전에도 목록 위치를 옮기지 않았다).
- 관리 → 논리 ERD (`/logicalErd?modelId=&entityId=`, 행의 [ERD]·상세의 [논리 ERD 에서 보기]) 그대로 — ERD 가 범위 밖이면 이웃을 불러와 포커스한다(13장). 상세의 [매핑 관리] → `/modelMapping?modelId=&entityId=` 그대로. 매핑 화면에서 논리 모델 관리로 가는 링크는 원래 없다.

### 15.6 전체 그래프 API 잔여 사용처

`GET /api/logical/model/{id}/erd`(`selectGraph`) : 화면에서 쓰는 곳이 없다 (관리 화면 — 이번, 논리 ERD — 1차). API 호환을 위해 그대로 둔다 (제거하지 않음). 서버 안에서 이 메서드를 부르는 업무(매핑·생성·내보내기·AI·MCP)는 없다 — 각자 자기 조회를 쓴다. `GET /model/{id}/entity`·`GET /model/{id}/relationship`(전체 목록)도 그대로 두며 화면은 더 쓰지 않는다.

### 15.7 성능 (`LogicalModelManagementPerformanceTest`, `LEXORMA_PERF=true` 일 때만)

데이터 = `LogicalErdPerformanceTest.seed`(1차와 같은 생성 — 공용으로 뺐다 : 엔터티마다 속성 8개·주 식별자 1개, 사슬 + 허브) + 자기 참조 50번째마다 + 같은 쌍 두 번째 관계 10번째마다. 화면 단위로 잰다 (요청 수는 화면이 보내는 API 수), 두 번 평균, JSON = 화면이 받는 형식. H2 = 테스트 메모리 DB, PostgreSQL 18.6 = 로컬 Docker 빈 임시 DB.

| 엔터티 | 조회 | 요청 | H2 ms | PG ms | 문장 | JSON KB | 할당 MB (H2 · PG) |
|---|---|---|---|---|---|---|---|
| 100 | 예전 첫 화면 : 전체 그래프 | 1 | 18 | 17 | 5 | 221 | 2 · 2 |
| 100 | 지금 첫 화면 : 요약 + 엔터티 10 | 2 | 3 | 4 | 9 | 1.8 | 0 · 0 |
| 1,000 | 예전 첫 화면 | 1 | 65 | 92 | 5 | 2,227 | 15 · 27 |
| 1,000 | 지금 첫 화면 | 2 | 3 | 3 | 9 | 1.8 | 0 · 0 |
| 3,000 | 예전 첫 화면 | 1 | 90 | 225 | 5 | 6,690 | 45 · 80 |
| 3,000 | 지금 첫 화면 | 2 | 3 | 4~7 | 9 | 1.8 | 0 · 0 |
| 3,000 | 엔터티 페이지 100 · 마지막 페이지 · 검색 "0042" · 설명 내림차순 | 1 | 2~3 | 3~6 | 6 | 17.1 · 1.8 · 1.8 · 1.8 | 0 |
| 3,000 | 관계 페이지 10 | 1 | 1 | 3~4 | 6 | 4.3 | 0 |
| 3,000 | 예전 상세(허브) : 엔터티 + 모델 관계 전체 | 2 | 43 | 59~97 | 68 | 1,517 | 36 · 34 |
| 3,000 | 지금 상세(허브) : 엔터티 + 엔터티 관계 | 2 | 2 | 8~13 | 9 | 67 | 1 · 1 |

- 문장 수는 모델 크기·페이지 크기와 무관하다 (엔터티 페이지 6, 관계 페이지 6, 요약 3). 첫 화면 응답은 모델 크기와 무관하게 1.8KB (예전 3,000개 6.5MB). 상세(허브)는 그 엔터티의 관계 수에 비례한다 (허브 = 관계 150개 남짓, 67KB). 같은 표의 PostgreSQL 값은 두 번 측정의 범위다 (실행 계획 확인 측정과 본 측정).
- 바꾸기 전(상관 서브쿼리) PostgreSQL : 3,000개 마지막 페이지 830ms·엔터티 페이지 100 31ms → 바꾼 뒤 3ms·3~6ms.
- 서버 할당은 요청 스레드 할당 바이트 (H2 는 DB 엔진 할당 포함).
- 브라우저 (헤드리스 Chrome 1440×1000, 임시 H2 앱, ADMIN, 모델 'M6 관리 3000' : 엔터티 3,000·속성 2개씩·관계 3,212, 3번 중앙값) : 첫 화면(문서 탐색 시작 → 10행, SPA 로딩·로그인 확인 포함) 154ms, 다음 페이지 11ms, 마지막 페이지 16ms, 페이지 크기 100 20ms, 검색 11ms, 검색 초기화(100행) 19ms, 이름 내림차순 16ms, 관계 탭 12ms, 페이지 밖 엔터티 상세(URL 진입 → 대화상자) 155ms, 관계 대화상자 후보 검색 265ms(입력 멈춤 대기 250ms 포함). 같은 브라우저에서 예전 첫 화면 응답(전체 `/erd`) 140ms·2,707KB vs 지금(요약 + 엔터티 10) 6ms·2KB. 예전 화면 코드는 바뀌어 예전 화면의 그리기 시간은 재지 않았다 (응답만 비교).

### 15.8 검증

- 전체 : H2 `./gradlew build --rerun-tasks --continue -PfrontendPrebuilt=true` 1,060건 실패 0 · 건너뜀 47, PostgreSQL 18.6(빈 임시 DB) 1,060건 실패 0 · 건너뜀 32 — 1,050 + 새 10 (통합 9 + 측정 1, 측정은 LEXORMA_PERF 없으면 건너뜀이라 건너뜀 +1). `npm run typecheck`·`npm run build` 통과. 측정 테스트는 따로 H2·PostgreSQL 에서 실행 (15.7).
- `LogicalModelManagementIntegrationTest` 9건 (H2·PostgreSQL) : 집계 = 예전 전체 그래프 계산(엔터티마다 속성 수·관계 수 비교, 복수·반대 방향·자기 참조 2개·속성 없음·관계 없음·복합 속성 짝, 요약 = 그래프 크기, 엔터티별 합 13 ≠ 모델 8) / 변경 뒤(속성·관계 등록·삭제가 양쪽 엔터티 수·요약에, 엔터티 삭제) / 페이징(빈 모델, 23개 첫·마지막·범위 밖, 7개씩 모두 이어 붙여 빠짐·겹침 없음, page -1·size 0·101·검색어 101자 거절) / 정렬(이름·설명·승인 오름·내림, 같은 값 ID 순, 모르는 필드 무시) / 검색(대소문자, `_`·`%` 글자 그대로, 공백, 결과 없음, 빈 검색어, 검색 수 ≠ 요약) / 관계 페이지(ID 순, 복합 짝 COMPLETE, 페이지 이어 붙이기, 범위 밖·크기 거절) + 엔터티의 관계 = 예전 모델 전체 관계에서 거른 것과 필드까지 같음 / SQL 수(엔터티 5·30개 페이지 같음 ≤ 6, 관계 5·29개 같음 ≤ 6, 요약 ≤ 3) / 격리(다른 프로젝트의 요약·목록·관계·엔터티 관계 거절, 없는 ID, 같은 프로젝트 다른 모델) / VIEWER(조회 4종 가능, 엔터티 등록·관계 삭제 403).
- 헤드리스 Chrome (임시 H2 앱 9953, 모델 'M6 관리 3000' + 'M6 관리 소형'(12개)) : ADMIN 한국어·영어 × 라이트·다크 × 1440·375 8조합, VIEWER·USER·DBA·MANAGER 각 한국어 라이트 1440·영어 다크 375 — 조합마다 확인 26개 : 요약 탭 수, 첫 페이지 10행, 건수, 허브 집계(속성 2·관계 150), 다음·마지막(300쪽) 페이지, 페이지 크기 25, 검색 1건(자기 참조 집계 3)·'검색 결과 1개 (모델 전체 3000개)'·결과 없음·초기화, 이름 내림차순, URL 로 페이지 밖 엔터티 상세 + 관계 탭, 없는 ID(대화상자 없음·entityId 제거), 빠른 선택 전환(A 를 열자마자 닫고 B → B 상세 1개), 관계 탭 10행, 관계 대화상자에서 페이지 밖 엔터티를 검색해 출발·도착 고르기(USER 이상), 모델 전환(수·첫 페이지·행 바뀜), `50%` 검색(와일드카드 아님), 행의 [ERD] → 논리 ERD(그 엔터티), 상세 [매핑 관리] → 매핑 화면, 가로 스크롤 없음, 콘솔 오류 없음(의도한 없는 ID 400 제외), **전체 그래프 `/erd` 호출 0건**(화면 동안 모든 `/api` 요청 기록). 16조합 모두 통과.
  - 변경 흐름 (ADMIN 한국어 라이트 1440, 따로 1회 — 30개 확인 모두 통과) : 관계 등록(페이지 밖 엔터티 2개를 검색해 저장 → 요약 관계 수 +1), 엔터티 등록(요약 13, 상세가 바로 열림, 정렬상 다른 페이지라 현재 페이지 그대로), 승인취소 → 목록 '승인 대기' → 승인 → '승인', 마지막 페이지의 마지막 행 삭제(앞 2행은 API 로 지움) → 1쪽으로 옮김·대화상자 남지 않음, 충돌(상세를 연 뒤 다른 요청이 설명을 고침 → 저장 시 409 안내, 입력값 '내가 고침' 유지).
  - 역할별 버튼 : [관계 등록]·[수정]·[모델 등록] = USER·DBA·MANAGER·ADMIN, VIEWER 없음 / 관계 [승인]·[승인취소] = MANAGER·ADMIN, USER·DBA 없음 (승인 권한 = 기존 `approvalAvailable`) / [논리 ERD]·[매핑 관리] = 모든 역할. 서버 권한은 바꾸지 않았다.
  - 375px : 페이지 가로 스크롤은 없으나 엔터티 표(고정 폭 6열, 예전과 같은 폭)의 좁은 열 머리글이 겹쳐 보였다 — 보완에서 고쳤다 (15.10).
  - 검증 스크립트 오류 기록 : 첫 실행에서 충돌 확인 단계가 다른 대화상자의 이름으로 '소형 00' 의 이름을 바꿨다 (스크립트 결함, 임시 데이터만) — 데이터를 되돌리고 같은 엔터티를 ID·이름으로 확인하게 고쳐 다시 돌렸다.

### 15.9 알려진 한계 · 후속

- (보완에서 해결) 관계 탭 검색·정렬·식별 필터, 375px 머리글 겹침, 잘못된 정렬 방향·페이지 값의 500 — 15.10.
- 식별자 수는 목록에 넣지 않았다 (예전 목록에 없음 — 상세 식별자 탭에서 본다).
- 목록 순서 : 이름이 같은 엔터티는 없지만(모델 안 UNIQUE) 설명·승인 정렬에서 같은 값은 ID 순이다. 검색·정렬 상태는 화면 안에서만 유지되고 URL·새로고침에는 남지 않는다 (공용 그리드와 같다). 상세 대화상자를 열고 닫아도 목록 상태는 그대로다.
- 일괄 선택·일괄 승인은 이 화면에 원래 없다 (관계·엔터티 승인은 한 건씩) — 선택 범위 정책은 바뀌지 않았다.
- 기존 전체 조회 API(`/erd`, `/model/{id}/entity`, `/model/{id}/relationship`)는 남아 있고 제한이 없다 — 제거·제한은 별도 결정. 예전 `/model/{id}/relationship` 는 관계 속성 짝 판정에서 엔터티마다 식별자를 읽는다(3,000개 모델 68문장) — 화면은 더 쓰지 않아 고치지 않았다.
- Linux CI 는 확인하지 않았다.

### 15.10 후속 3차 보완 (2026-11-19) : 관계 목록 검색·필터·정렬 · PageableUtil 입력 검증 · 좁은 화면 머리글

**관계 목록 API** — `GET /api/logical/model/{id}/relationship/data` 를 호환되게 넓혔다 (경로·응답 그대로, 새 파라미터는 모두 선택 — 빠지면 예전과 같은 ID 순 결과).

| 파라미터 | 내용 |
|---|---|
| `searchQuery` | 관계명·출발 엔터티명·도착 엔터티명 중 하나라도 포함 (대소문자 무시, `%`·`_` 글자 그대로, 앞뒤 공백 무시, 100자 — 엔터티 검색과 같은 `likePattern`). 엔터티 영문 약어·설명은 대상이 아니다 |
| `isIdentifying` | `true` 식별 / `false` 비식별 / 없으면 전체. 저장된 `IDNTF_YN` 만 본다 (카디널리티·속성 짝·주 식별자·물리 FK 로 계산하지 않음). 불리언이 아닌 값은 Spring 변환 오류 400 (본문에 스택 없음) |
| `sort` | `relationshipName`·`sourceEntityName`·`targetEntityName` + `,asc|desc` (허용 목록, QueryDSL 경로 — 클라이언트 값을 SQL 에 잇지 않음). 출발·도착은 그 엔터티의 이름. 이름 없는 관계는 방향과 무관하게 뒤(`nulls last`, H2·PostgreSQL 같은 위치). 같은 값은 관계 ID 오름차순. 모르는 필드·정렬 없음 = 관계 ID 순 |

- `recordsTotal` = 검색·필터 조건에 맞는 관계 수 (기존 의미 '조건에 맞는 전체 수' 그대로), 모델 전체 관계 수는 요약 API(검색과 무관). 화면 : 검색·필터 중이면 '검색 결과 a개 (모델 전체 b개)'.
- 이름 정렬 순서는 DB 콜레이션을 따른다 (H2 = 글자 코드 순, PostgreSQL = DB 로캘 — 로컬 Docker 는 `팀 < 고객 < 주문 …`) — 엔터티 이름 정렬과 같은 성질. 같은 값의 ID 순·페이지 사이 빠짐·겹침 없음은 두 DB 모두 테스트.
- SQL (`LogicalModelQueryRepository.findRelationshipIds`) : ① 관계 + 출발·도착 엔터티 조인(관계마다 한 행 — 다대일이라 행이 늘지 않아 한 관계는 한 번만) 검색·필터·정렬 뒤 페이지 ID ② 같은 조건 수 ③ 그 ID 의 관계(양 끝 엔터티 함께) ④ 양 끝 엔터티 주 식별자 한 번 ⑤ 속성 짝 한 번 + 모델 확인 = 6문장, 페이지 크기·모델 크기·조건과 무관 (모델 전체 관계의 짝 판정을 먼저 하지 않는다). 응답 행은 예전 전체 관계 목록의 같은 관계와 필드까지 같다 (테스트).
- 실행 계획 (PostgreSQL 18.6, 관계 3,506개) : `IX_LGCL_REL_01(LGCL_MDL_SN)` 로 모델의 관계 → 양 끝 엔터티 기본 키 조인 → 정렬. 마지막 페이지 이름 정렬 9ms, 검색 + 비식별 + 도착명 내림차순 5.6ms. 이름 정렬 인덱스가 없어 모델 관계를 정렬하지만 이 규모에서 10ms 안 — **새 인덱스는 필요 없다** (만들지 않았다).

**화면** (`LogicalModelPage` 관계 탭) : 식별 여부 선택(전체 / 식별 관계 / 비식별 관계) + 관계 검색(관계명 / 출발·도착 엔터티명, [조회]·Enter) + 조건이 있을 때 [초기화](검색·필터를 비우고 정렬은 둔다), 관계명·출발·도착 머리글 정렬(엔터티 표와 같은 `SortHeader`). 엔터티 탭과 상태를 따로 둔다 (탭을 옮겨도 각자 유지, 모델을 바꾸면 둘 다 처음으로 — 3차와 같다). 검색·필터·정렬을 바꾸면 첫 페이지, 페이지 이동·관계 수정 뒤에는 조건 유지. 조회 키가 조건마다 달라 늦게 온 앞 조건의 응답이 지금 목록을 덮지 않고, 요청은 `signal` 로 취소된다. 관계 등록·수정·삭제·승인 뒤에는 3차와 같이 이 모델의 요약·목록만 다시 받는다 (엔터티별 관계 수·열린 상세의 관계 탭은 각자 다시 조회).

**PageableUtil 입력 검증** — 재현 (임시 앱, 공용 그리드 data API 14곳 × 입력 17종, 보완 전) :

| 입력 | 보완 전 | 보완 뒤 |
|---|---|---|
| `col,asc`·`col,desc`·`col,ASC`·`col,Desc` | 200 | 200 (그대로) |
| 잘못된 방향 `col,up` | **500** (14곳 모두, `Direction.fromString` IllegalArgumentException) | 400 `INVALID_PAGE_REQUEST` "정렬 방향은 asc 또는 desc 만 쓸 수 있습니다." |
| 공백 방향 `col,%20`·빈 필드명 `,asc` | **500** | 200 (방향 없는 항목처럼 건너뜀 — `col,`·`col` 과 같은 기존 처리) |
| 방향 없음 `col`·빈 방향 `col,`·정렬 없음·`sort=`·`;`·셋째 칸 `col,asc,x` | 200 | 200 (그대로) |
| 음수 페이지·크기 0·음수 | **500** (논리 엔터티 목록만 자체 검사 400) | 400 `INVALID_PAGE_REQUEST` ("페이지 번호는 0 이상…", "페이지 크기는 1 이상…") |
| 크기 100000 | 200 (상한 없음 — 논리 관리 목록만 100) | 그대로 (상한은 각 조회의 정책) |
| 허용하지 않는 정렬 필드 | QueryDSL 허용 목록 화면(매핑·품질·논리) 200 무시, **Spring Data 저장소 화면(사전 3종·테이블·컬럼·VIEW·인덱스·공지·사용자·로그) 500** (`PropertyReferenceException`) | 그대로 — 화면마다 정책이 달라 바꾸지 않았다 (pending-work, 결정 필요) |

- 수정 : `common/utils/PageableUtil.getGridPageable` 한 곳 (사용처 : 컨트롤러 12개 + 논리 모델 서비스, 호출 25곳 — 데이터 사전·사전 사용처·표준화 품질·테이블·컬럼·인덱스·VIEW·논리·물리 매핑·논리 관리 목록·공지·사용자·프로젝트·시스템 로그. 모델 이력·버전·원본 수집·카탈로그는 응답 형식(`getGridPageableMap`)만 쓴다) + 공통 오류 코드 `INVALID_PAGE_REQUEST`(400, 한국어·영어 문구) + 사유 문구 3개(`EnglishMessages`). 응답은 기존 `CustomException` 형식(`code`·`message`·`status`), 내부 예외·스택은 나가지 않는다. 모든 예외를 400 으로 바꾸지 않았다 (위 세 경우만). 정상 정렬·기본 정렬·여러 정렬 동작 그대로.
- 회귀 테스트 `PageableUtilTest` +2 (방향 대소문자·빈 항목·셋째 칸 그대로 / 잘못된 방향·음수 페이지·1 미만 크기 = `INVALID_PAGE_REQUEST`) — 고치기 전 코드(HEAD)로 돌려 2건 실패, 고친 뒤 7건 통과를 확인했다. HTTP 로는 임시 앱에서 같은 14곳에 다시 보내 500 이 사라진 것(허용하지 않는 필드 제외)과 한국어·영어 응답을 확인했다.

**좁은 화면 머리글** — 원인 : 두 표는 `grid-fixed`(`table-layout: fixed; width: 100%`)이고 공용 `layout.css` 가 `thead th { white-space: nowrap }` 이라, 375px 에서 6~8열이 각각 수십 px 로 줄며 머리글이 옆 칸으로 넘쳤다. 영어(`Identifying relationship`·`Approval status`)는 1440px 에서도 넘쳤다 (보완 중 브라우저 검사에서 발견). 해결 (논리 모델 관리 두 표만, 공용 스타일 그대로) : ① 공용 그리드 `minWidth` 와 같은 방식으로 표 최소 폭(엔터티 900px, 관계 960px·관리 열 있으면 1,120px) — 그보다 좁으면 표 영역(`.dt-layout-table`, overflow-x auto)만 가로 스크롤, 문서는 스크롤하지 않음 ② 두 표의 머리글만 칸 안 줄바꿈(`logical.css`) ③ 한 단어 머리글(영어 `Relationships` 등)이 최소 폭에서 들어가도록 열 비율 조정. 열은 빼지 않았다 (식별자 수 열도 넣지 않음). 엔터티 상세 대화상자의 속성·식별자·관계 표는 원래 겹치지 않았다 (확인만).

**검증**
- 전체 : H2 `build --rerun-tasks` 1,064건 실패 0 · 건너뜀 47, PostgreSQL 18.6(빈 임시 DB) 1,064건 실패 0 · 건너뜀 32 — 1,060 + 새 4 (통합 2 : 관계 검색·필터, 관계 정렬 / PageableUtil 단위 2). `npm run typecheck`·`npm run build` 통과.
- `LogicalModelManagementIntegrationTest` 11건 : 관계 검색·필터(9 검색어 × 전체·식별·비식별 = 예전 전체 목록에서 거른 기대값과 순서·수 일치, 중복 없음, 자기 참조 한 번, 영문 약어 비대상, `_`·`%` 글자 그대로, 공백, 식별 = 저장값, 검색 + 필터 수, 요약 무관, 검색어 101자 거절, 응답 = 예전 목록의 같은 관계) / 관계 정렬(정렬 없음·모르는 필드·허용 목록 밖 = ID 순, 세 필드 × 오름·내림 : 같은 값 ID 순·이름 없음 뒤·반대 방향 = 역순·3개씩 이어 붙이기, 출발이 같은 관계 묶음, 잘못된 방향 400) / SQL 수(검색·필터·정렬 관계 페이지 5·29개 같음 ≤ 6) / 격리(다른 프로젝트가 검색·필터로 불러도 거절) / VIEWER(검색·필터·정렬 조회). 정렬 테스트는 처음에 'H2 글자 순' 위치를 가정해 PostgreSQL 에서 실패했고(콜레이션 차이), 위치를 가정하지 않게 고쳤다.
- 측정 (`LogicalModelManagementPerformanceTest`, 관계 항목 추가, 데이터 = 1차 생성 + 자기 참조 + 같은 쌍 둘째 관계를 **식별 관계**로 — 3,000개 모델 관계 3,506개·식별 299개) : 엔터티 3,000개에서 관계 페이지 10·관계명 검색·출발/도착명 검색·식별 필터·검색 + 비식별·관계명 내림차순·출발명 오름차순·도착명 내림차순 마지막 페이지 = H2 2~7ms / PostgreSQL 5~14ms, 모두 6문장·응답 2.6~4.4KB, 관계 페이지 100 = H2 6ms·PG 13ms·43KB. 100·1,000개도 6문장 (결과 없는 조건은 3문장).
- 헤드리스 Chrome (임시 H2 앱, 'M6 관리 3000'(관계 3,212) + '소형'(관계 15, 식별 1, '보완 NN' 12)) : ADMIN 한국어·영어 × 라이트·다크 × 1440·375 8조합 + VIEWER·USER·DBA·MANAGER 각 한국어 라이트 1440·영어 다크 375 — 조합마다 28개 확인 : 기본 ID 순 = API, 건수, 관계명·출발명·도착명 검색(화면 = 같은 조건 API, '검색 결과 a개 (모델 전체 b개)'), 검색 유지한 다음 페이지, 세 머리글 오름·내림(첫 페이지로, 검색 유지), 결과 없음, 초기화, 식별·비식별 필터·검색 + 필터(소형), 머리글 겹침 없음(관계·엔터티 표, 글자 사각형이 칸 안), 375 에서 표 안 가로 스크롤(overflow-x auto)·문서 가로 스크롤 없음, 검색·필터 영역 겹침 없음, 페이지 이동 보임, 엔터티 상세 속성·식별자·관계 표 겹침 없음, 콘솔 오류 없음, **전체 그래프 `/erd` 호출 0건**. 16조합 모두 통과 (첫 실행에서 영어 8조합이 머리글 넘침으로 실패 → 위 ②③ 뒤 통과). 변경 흐름(ADMIN 한국어·영어 라이트 1440 각 1회, 30개 확인) : 검색 중 관계 수정 → 그 행 갱신·검색 유지, 마지막 페이지 관계 모두 삭제 → 앞 페이지로·수 갱신. 역할별 버튼 : 관계 [수정]·[삭제]·[관계 등록] = USER·DBA·MANAGER·ADMIN, [승인] = MANAGER·ADMIN, VIEWER 는 검색·필터·정렬만.
- 공용 그리드 회귀 (PageableUtil 을 거치는 화면) : 표준 도메인·단어·용어, 데이터 사전, 테이블·컬럼·인덱스·VIEW 관리, 논리·물리 매핑, 표준화 품질, 공지, 사용자 관리, 시스템 로그 13화면 × 한국어 라이트 1440·375·영어 다크 1440 — 행 표시, 머리글 정렬 두 번(aria-sort), 다음 페이지, 페이지 크기 25, 콘솔 오류(4xx·5xx 포함) 없음, 문서 가로 스크롤 없음. 3차 엔터티 흐름(`checkMng`)도 ADMIN 한국어 라이트 375·VIEWER 영어 다크 1440 다시 통과.
- 검증 데이터 준비 중 메모 : 승인 권한자가 만든 관계는 바로 승인돼 [수정]·[삭제] 가 꺼진다(정책 그대로) — 변경 흐름용 관계는 API 로 승인취소해 두었다.

**한계 · 후속**
- (2026-11-19 [COMMON-LIST] 에서 해결) 허용하지 않는 정렬 필드는 모든 공용 그리드 목록에서 400, 페이지 크기 상한 100 — completed-work.md [COMMON-LIST], reference/list-query-policy.md.
- 이름 정렬은 DB 콜레이션에 따라 H2·PostgreSQL 순서가 다를 수 있다 (기존 정렬 전반과 같음).
- 관계 검색은 엔터티 이름만 본다 (설명·속성명·영문 약어 제외). Linux CI 미확인.

