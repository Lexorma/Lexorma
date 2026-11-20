# 논리 모델 주제 영역 (Subject Area) — M-6 후속 4차

- 상태 : **구현·검증 완료** (2026-11-20). 1단계 설계(2026-11-19, 같은 파일의 이전 판 `docs/ongoing/logical-subject-area.md`)에서 사용자가 D-01~D-27 을 확정한 뒤 구현했다. 남은 일은 §21.
- 관련 : [logical-model-m6.md](logical-model-m6.md) (13장 부분 조회, 14장 평행 관계선, 15장 서버 페이징), [list-query-policy.md](../reference/list-query-policy.md), [ui-motion-guide.md](../reference/ui-motion-guide.md), [db-naming-standard.md](../reference/db-naming-standard.md), [model-exchange-dec22.md](model-exchange-dec22.md)
- 코드 : `lexorma-data` `logical/` (`LogicalSubjectAreaService`, `LogicalSubjectAreaRestController`, `LogicalSubjectArea`·`LogicalSubjectAreaEntity`·`LogicalSubjectAreaLayout`, `LogicalModelQueryRepository` 범위 조건), `history/record/ModelChangeRecorder`, `exchange/`, `version/`; 화면 `lexorma-web/src/features/logical/` (`SubjectAreaPanel.tsx`, `subjectArea.ts`, `LogicalModelPage.tsx`, `LogicalErdPage.tsx`, `entityDetail.tsx`); 마이그레이션 `V2__logical_subject_area.sql` (PostgreSQL·H2 같은 내용)

## 1. 업무 정의

주제 영역 = **한 논리 모델 안에서** 엔터티를 업무 영역별로 묶는 관리용 분류. 엔터티를 복제하지 않고, 엔터티의 정의·속성·식별자·관계·승인 상태·표준 용어 연결·논리↔물리 매핑·엔터티 변경 이력은 모두 엔터티에 그대로 있다. 물리 스키마와 무관하다.

| 구분 | 역할 |
|---|---|
| 프로젝트 | 데이터·권한 격리 |
| 논리 모델 | 업무 데이터 구조 정의 |
| 주제 영역 | 업무 영역별 엔터티 분류 (최대 3단계) |
| 논리 엔터티·관계 | 업무 데이터 대상·관계 정의 |
| 물리 스키마 | 실제 DB 객체의 네임스페이스 (주제 영역과 연결하지 않음) |
| ERD 배치 | 사용자별 시각적 배치 (모델 전체 · 주제 영역별 따로) |

## 2. 확정 정책 D-01~D-27 (2026-11-20 사용자 확정)

| ID | 정책 | 구현 |
|---|---|---|
| D-01 | 논리 모델별 관리 | `TB_LGCL_SBJ_AREA.LGCL_MDL_SN` |
| D-02·D-22 | 인접 리스트 계층, 최상위 = 1단계, 최대 3단계 | `UP_SBJ_AREA_SN`, 서비스 `MAX_DEPTH = 3` |
| D-03 | 단일 소속, 별도 소속 테이블 | `TB_LGCL_SBJ_AREA_ENTT` + `UX_LGCL_SBJ_AREA_ENTT_01 (LGCL_ENTT_SN)` |
| D-04 | 미분류 허용, 가상 필터 | 소속 행 없음 = 미분류 (`unclassified=true`) |
| D-05 | 승인된 엔터티도 분류 변경 | 소속은 엔터티 행이 아니라 별도 행 — 엔터티 수정 API 의 승인 검사는 그대로 |
| D-06 | 소속 엔터티·하위 영역이 있으면 영역 삭제 거절 | `RELATION_EXISTS` |
| D-07 | 주제 영역별 독립 ERD 배치 | `TB_LGCL_SBJ_AREA_LYT` (사용자 × 영역) |
| D-08 | 외부 관계 = 기존 범위 밖 관계 수 + 이웃 탐색 | `outsideRelationships`, 이웃 탐색은 모델 전체 |
| D-09 | 논리 모델 전용 | 물리·매핑 코드 변경 없음 |
| D-10 | 이력·버전·Export 포함, Export 형식 4 | §16~§18 |
| D-11 | 기존 논리 모델 권한 재사용 | 조회 VIEWER, 관리·분류 USER (`logical-model` / `logical-model-edit`) |
| D-12 | 기존 논리 모델 관리 화면 통합 | 별도 메뉴 없음 |
| D-13 | 공통표준단어·기존 명명 규칙 | §4 |
| D-14 | 같은 상위 안 이름 중복 금지 (대소문자 무시, 최상위끼리도) | §5 |
| D-15 | 하위 영역 기본 포함, 관계는 한쪽 이상 포함 | `includeChildren` 기본 true |
| D-16 | 상위 영역 직접 소속 허용 | 제약 없음 |
| D-17·D-24 | 신규 엔터티 = 선택 영역 기본, 등록 전 변경 가능 | 엔터티 등록 `subjectAreaId` (한 트랜잭션) |
| D-18 | LEXORMA 예제에 주제 영역 데이터 없음 | V2 는 예제 메타데이터만 |
| D-19 | 모델 삭제 시 영역 함께 삭제 (기존 삭제 조건 충족 시) | `deleteForModel` |
| D-20 | 분류 동시성 : 기존 소속 비교, 다르면 409 | `expectedSubjectAreaId` |
| D-21 | 일괄 분류 원자성 | 하나라도 실패하면 아무것도 바꾸지 않는다 |
| D-23 | 이동 시 하위 트리 깊이·순환 검증 | `height` + `subtree` |
| D-25 | 분류 이력은 엔터티 정의 이력과 분리 | 이력 종류 `SUBJECT_AREA_ENTITY` |
| D-26 | 최초 ERD 조회는 기존 좌표 참고, 전체 좌표 변경 금지 | 영역 배치가 없으면 모델 배치 좌표를 읽기만 (`source MODEL`) |
| D-27 | 검색 결과에 전체 계층 경로 | 서버가 `path` 를 준다 |

## 3. DB 구조 (V2)

Flyway 번호 : 2026-11-20 기준 작업 트리에는 `V0`·`V1` 만 있고 개발 DB 이력도 V1 까지라 **V2** 로 정했다. (인덱스에 스테이징만 되어 있고 작업 트리에서 지워진 `V2__remove_default_admin.sql` 은 다른 세션의 남은 스테이징이다 — 기본 관리자 제거는 V1 에 직접 반영됐다 (CLAUDE.md). 이 스테이징이 커밋되면 V2 가 둘이 되므로 커밋 전에 정리해야 한다.)

| 테이블 | 컬럼 | 비고 |
|---|---|---|
| `TB_LGCL_SBJ_AREA` 논리 주제 영역 | `LGCL_SBJ_AREA_SN` PK (`SQ_LGCL_SBJ_AREA`), `PRJ_SN`, `LGCL_MDL_SN`, `UP_SBJ_AREA_SN` (null = 최상위), `SBJ_AREA_NM` varchar(255), `SBJ_AREA_EXPLN` varchar(1000), `SORT_ORD` integer, 감사 4 | `CK_LGCL_SBJ_AREA_01 (UP_SBJ_AREA_SN <> LGCL_SBJ_AREA_SN)` |
| `TB_LGCL_SBJ_AREA_ENTT` 논리 주제 영역 엔터티 | `LGCL_SBJ_AREA_ENTT_SN` PK, `PRJ_SN`, `LGCL_MDL_SN`, `LGCL_SBJ_AREA_SN`, `LGCL_ENTT_SN`, 감사 4 | 엔터티당 0~1건 |
| `TB_LGCL_SBJ_AREA_LYT` 논리 주제 영역 레이아웃 | `LGCL_SBJ_AREA_LYT_SN` PK, `PRJ_SN`, `LGCL_MDL_SN`, `LGCL_SBJ_AREA_SN`, `ACNT_SN`, `NODE_PSTN_CN` text, `SCRN_X/Y_CRDNT`·`SCRN_MGNF` double, `VER_NO` (JPA `@Version`), 감사 4 | `TB_LGCL_ERD_LYT` 와 같은 모양 |

FK (모두 단일 컬럼, 삭제 규칙 없음 — 지울 때는 서비스가 엔티티로 지워 변경 이력이 남는다) : 영역 → 모델 (`_01`), 영역 → 상위 영역 (`_02`, 자기 참조); 소속 → 영역·엔터티·모델 (`_01~_03`); 배치 → 영역·모델 (`_01~_02`).

인덱스 6개 (예제 인덱스 143개·컬럼 205개로 늘었다) : `UX_LGCL_SBJ_AREA_01 (LGCL_MDL_SN, UP_SBJ_AREA_SN, SBJ_AREA_NM)`, `IX_LGCL_SBJ_AREA_01 (UP_SBJ_AREA_SN)`, `UX_LGCL_SBJ_AREA_ENTT_01 (LGCL_ENTT_SN)` (단일 소속 + 엔터티별 소속 조회 + 미분류 `not exists`), `IX_LGCL_SBJ_AREA_ENTT_01 (LGCL_SBJ_AREA_SN, LGCL_ENTT_SN)` (영역 범위 `exists`·영역별 수), `IX_LGCL_SBJ_AREA_ENTT_02 (LGCL_MDL_SN)` (모델의 영역별 수 집계), `UX_LGCL_SBJ_AREA_LYT_01 (LGCL_SBJ_AREA_SN, ACNT_SN)`.

**정합성 분담** — DB : 존재(FK), 자기 자신을 상위로 금지(CHECK), 단일 소속(UNIQUE), 사용자 × 영역 배치 하나(UNIQUE), 상위가 있는 형제의 대소문자까지 같은 이름(UNIQUE — PostgreSQL·H2 모두 NULL 은 서로 다른 값이라 최상위끼리는 막지 못한다). 서비스(논리 모델 행 잠금 뒤) : 같은 모델·같은 프로젝트(영역↔모델, 상위↔하위, 소속의 영역↔엔터티), 깊이 3, 순환, 대소문자 무시 이름 중복(최상위 포함). 논리 관계의 양 끝 엔터티(같은 모델)와 같은 방식이다. 대소문자 무시 UNIQUE 는 H2 에 식 인덱스가 없어(DICT-2 와 같은 사정) DB 로 두지 않았다. 전체 DB Collation 은 바꾸지 않았다.

기존 데이터 : 기존 모델·엔터티는 미분류로 남는다 — 기본 영역 생성·자동 분류·엔터티 수정 일시 변경·배치 복제를 하지 않는다 (`LogicalSubjectAreaMigrationTest` : V1 설치에 사용자 데이터를 넣고 V2 로 올려 그대로임을 H2·PostgreSQL 에서 확인).

예제(프로젝트 0) : 새 테이블 3개의 단어·용어 → 물리(`TB_TBL`·`TB_COL`) → 논리(엔터티·속성·주 식별자) → 매핑 → FK 7개(+ 논리 관계 7개와 속성 짝) → CHECK 1 → 인덱스 6 → 업무 설명. 주제 영역 데이터 자체는 없다 (D-18).

## 4. 명명 근거

공공데이터 공통표준(`static/excel/dataPortal.xlsx`) 확인 결과 : **상위 = `UP`** (공통표준단어 304번, 공통표준용어 `UP_MENU_SN` 등), **주제·영역은 공통표준단어에 없다** (가까운 단어 : 과목 `SBJCT`, 구역 `ZONE`, 분야 `FLD`; `AREA` 는 공통표준에서 **면적**). 2026-11-20 사용자 결정으로 **주제 `SBJ`, 영역 `AREA`** 를 Lexorma 기관 표준단어로 정했다 (`AREA` 는 공통표준의 면적과 약어가 같다 — 노드 `NODE`(공통표준 = 교점)와 같은 예외로 [db-naming-standard.md](../reference/db-naming-standard.md) 2장에 적었다). 새 용어 6개 : 논리_주제_영역_일련번호 `LGCL_SBJ_AREA_SN`, 상위_주제_영역_일련번호 `UP_SBJ_AREA_SN`, 주제_영역_명 `SBJ_AREA_NM`(명V255), 주제_영역_설명 `SBJ_AREA_EXPLN`(내용V1000), 논리_주제_영역_엔터티_일련번호, 논리_주제_영역_레이아웃_일련번호 (모두 일련번호L). 나머지 컬럼은 기존 용어(정렬_순서, 노드_위치_내용 …)를 재사용했다. 새 도메인은 없다.

## 5. 계층과 이름

- 등록 : 모델 존재·로그인 프로젝트 → 상위가 같은 모델의 영역 → 깊이(상위 깊이 + 1 ≤ 3) → 같은 상위 아래 이름(trim, 대소문자 무시) → 표시 순서(0~1,000,000, 비우면 같은 상위의 맨 뒤). 이름 255자·설명 1000자는 엔터티와 같은 규칙.
- 이동(같은 저장 API 의 `parentId` 변경) : 자기 자신·자기 하위를 상위로 금지, **옮긴 뒤 가장 깊은 하위**(`height`)까지 3단계 안, 대상 상위 아래 이름 중복 금지. 다른 모델·프로젝트의 상위는 '찾을 수 없음'. 옮겨도 엔터티의 소속 영역 ID 는 그대로이고 상위 집계·경로만 새 계층을 따른다.
- 영역 수 한도 : 모델당 1,000 (조회 범위의 영역 ID 목록을 IN 조건 하나로 보내므로 둔 기술적 한도).
- 수정·삭제는 영역의 `MDFCN_DT` 기준값을 비교한다 (DEC-08, 없으면 400 `MODEL_BASE_REQUIRED`, 다르면 409 `MODEL_CONFLICT`). 같은 이름 동시 등록은 모델 행 잠금으로 직렬화된다.

## 6. 엔터티 소속

- 미분류 = 소속 행 없음. 화면의 '미분류'는 가상 항목이다.
- 엔터티 등록 : 요청의 `subjectAreaId`(null = 미분류)로 처음 소속을 같은 트랜잭션에 만든다 — 영역이 잘못되면 엔터티도 남지 않는다. 수정 요청의 `subjectAreaId` 는 보지 않는다.
- 엔터티 삭제 : 기존 제약(승인·관계·매핑)을 모두 통과한 뒤에만 소속을 지운다 (실패하면 소속도 그대로).
- 일괄 분류 `PUT /model/{id}/subjectArea/entities` : `{ subjectAreaId, entities: [{ entityId, expectedSubjectAreaId }] }`. 두 값은 JSON 에 **반드시 있어야** 하고 명시적 null 은 미분류다 (`JsonNode` 로 받아 필드 없음 = Java null, null = NullNode 로 구분 — 필드 없는 요청은 400, 덮어쓰기로 처리하지 않는다). 한 번에 최대 500개 (컬럼 용어 일괄 연결 DEC-26 과 같은 한도, 화면은 한 페이지(최대 100)에서만 고른다).

## 7. 승인 정책 분리

분류는 엔터티 행을 쓰지 않으므로 승인된 엔터티도 분류할 수 있고 엔터티의 `MDFCN_DT`·수정자·승인 상태·정의는 그대로다 — 엔터티 수정 화면이 갖고 있는 기준값이 분류 때문에 바뀌어 409 가 나지 않는다. 엔터티 수정·삭제 API 의 승인 검사는 그대로다 (`classify` 테스트가 승인된 엔터티 분류 뒤 정의 수정 거절을 확인).

## 8. 권한·프로젝트 격리

| 역할 | 조회 | 영역 관리 | 분류 | 내 영역 배치 |
|---|---|---|---|---|
| VIEWER | O | X (403) | X (403) | O (모델 전체 배치와 같은 개인 설정) |
| USER·DBA·MANAGER·ADMIN | O | O | O | O |

새 메뉴·권한 ID 는 없다 (`logical-model`, `logical-model-edit`). 다른 프로젝트·다른 모델의 모델·영역·엔터티 ID 는 기존 논리 모델 계약대로 **400 `LOGICAL_MODEL_IS_NOT_DEFINED` "…을(를) 찾을 수 없습니다"** 다 (1단계 설계의 404 안 대신 기존 계약을 따랐다). `UiActionAuthorizationIntegrationTest` 의 'USER 미만 변경 API' 허용 목록에 영역 배치 저장을 모델 배치 저장과 같은 사유로 더했다.

## 9. 일괄 분류 동시성·원자성 (D-20·D-21)

모델 행 잠금 → 대상 영역 확인 → 엔터티 일괄 조회(로그인 프로젝트·이 모델) → 지금 소속 일괄 조회 → 엔터티마다 `expectedSubjectAreaId` 와 비교 → 하나라도 다르면 409 `MODEL_CONFLICT`("엔터티 X 의 주제 영역이 화면을 연 뒤 바뀌었습니다. 아무것도 바꾸지 않았습니다 …", `CustomException.withMessage`) → 모두 같을 때만 변경. 이미 그 영역인 엔터티는 바꾸지 않는다 (소속 수정 일시·이력 없음, 응답 `unchanged`). 쓰기는 한 트랜잭션이라 DB 제약 위반도 전체 롤백이다.

**확정 정책 (2026-11-20 사용자 확정)** : 비교 대상은 화면이 본 소속(`expectedSubjectAreaId`)과 지금 소속뿐이다. 그 사이 A→B→A 로 바뀌었다가 돌아온 경우는 충돌로 보지 않는다 — 의도된 동작이다 (화면이 본 상태와 지금 상태가 같으므로 요청 결과가 화면 기준으로 맞다). 소속 revision 컬럼·소속 `MDFCN_DT` 기준값·추가 마이그레이션은 두지 않는다. 모델 행 잠금과 일괄 처리 전체 롤백은 그대로다.

## 10. 삭제 정책

| 대상 | 규칙 |
|---|---|
| 주제 영역 | 하위 영역·직접 소속 엔터티가 없을 때만 (`RELATION_EXISTS`). 그 영역의 배치(모든 사용자)만 함께 지운다. 엔터티를 미분류로 바꾸거나 하위를 끌어올리지 않는다 |
| 엔터티 | 기존 제약 그대로, 지워지면 소속도 |
| 논리 모델 | 기존대로 엔터티가 있으면 거절. 없으면 모델 배치 → 영역 배치 → 영역(깊은 것부터, 자기 참조 FK) |
| 프로젝트 | `ProjectDataCleanup` : 영역 배치 → 소속 → 상위 비우기 → 영역 (`ProjectDeletionIntegrationTest` 의 PRJ_SN 테이블 목록에 3개 추가) |

## 11. API 계약

신규 (`LogicalSubjectAreaRestController`, `/api/logical`) :

| 메서드 · 경로 | 권한 | 내용 |
|---|---|---|
| `GET /model/{modelId}/subjectArea` | VIEWER | `{ modelId, entityCount, unclassifiedCount, areas: [{ id, modelId, parentId, areaName, areaDescription, sortOrder, depth, path, directEntityCount, totalEntityCount, childCount, modifiedAt }] }` (계층 순서) |
| `POST /subjectArea` | USER | 등록(`id` null)·수정·이동 — `modifiedAt` 수정 시 필수 |
| `DELETE /subjectArea/{id}?modifiedAt=` | USER | 기준값 필수 |
| `PUT /model/{modelId}/subjectArea/entities` | USER | 일괄 분류 (§6, §9) → `{ changed, unchanged }` |
| `GET·PUT /model/{modelId}/subjectArea/{id}/layout` | VIEWER | 영역별 내 배치 (§15) |

기존 API 의 선택 조건 (없으면 예전과 같다) : `entity/data`, `relationship/data`, `erd/part`, `erd/entities` 에 `subjectAreaId`(기본 하위 포함) · `includeChildren=false`(직접 소속만) · `unclassified=true`. 영역과 미분류를 함께 주면 400 `INVALID_LOGICAL_MODEL`, 이 모델의 영역이 아니면 400 `LOGICAL_MODEL_IS_NOT_DEFINED` (모델 전체로 바꿔 보여 주지 않는다). 페이지·정렬 검사(`PageableUtil`, 1~100, 허용 목록, 400)는 그대로 먼저 한다. 응답 추가 필드 : 엔터티 행 `subjectAreaId`, 관계 행 `sourceSubjectAreaId`·`targetSubjectAreaId`, 엔터티 상세 `subjectAreaId`. 기존 Java 호출용 오버로드(요청 매핑 없음, 권한 같음)를 컨트롤러에 남겼다.

## 12. 관리 화면 (`/logicalModel`)

- 왼쪽 `SubjectAreaPanel` : 전체·미분류·계층(펼침·접힘, 방금 펼친 묶음만 `motion-fade-in`), 엔터티 수(하위 포함), 영역명 검색 → 전체 경로 목록(D-27), [+] 등록. WAI-ARIA tree (`role=tree/treeitem`, `aria-level`·`aria-expanded`·`aria-selected`, 이동 가능한 항목 하나만 `tabindex=0`, ↑↓ Home End → ← Enter Space). 선택은 영역 ID.
- 오른쪽 머리 : 경로·설명·직접/하위 포함/하위 영역 수, [하위 영역 포함], [하위 영역 추가](3단계 미만) [영역 수정] [영역 삭제] [ERD에서 보기]. 삭제는 하위·소속이 있으면 안내만 하고 지우지 않는다.
- 엔터티 표 : 체크박스(편집 권한, 지금 페이지만 — 페이지·검색·정렬·범위가 바뀌면 비운다), '주제 영역' 열(경로, 말줄임 + title), [분류 변경 (n)] → 대화상자(고른 수·지금 소속별 수·옮길 영역·미분류) → 확인 → 성공 토스트. 409 는 공통 오류 대화상자 뒤 목록·영역 수를 다시 읽는다 (자동 재시도 없음).
- 엔터티 등록 대화상자 : 주제 영역 선택(기본 = 고른 영역, 전체·미분류면 미분류).
- 관계 탭 : 같은 범위, 범위가 있으면 양 끝 엔터티 아래에 소속 경로.
- 모바일(900px 이하) : 패널을 접고 '주제 영역 : …' 버튼으로 연다. 페이지 전체 가로 스크롤 없음 (표만 자기 영역에서 스크롤).

## 13. 엔터티·관계 조회

`LogicalModelQueryRepository` 의 범위 조건 : 미분류 `not exists (소속 where 엔터티)`, 영역 `exists (소속 where 엔터티 and 영역 in (…))`. 하위 포함 영역 목록은 메모리의 계층(최대 3단계)에서 만든다 — 재귀 쿼리·경로 컬럼 없음. 관계는 출발 또는 도착이 범위 안. 엔터티 목록은 페이지 엔터티의 소속을 한 번 더 읽고(7번), 관계 목록은 양 끝의 소속을 한 번 더 읽는다(7번) — 페이지 크기·모델 크기와 무관(`LogicalModelManagementIntegrationTest.statementCount`).

## 14. 논리 ERD 연동 (`/logicalErd`)

- 모델 선택 옆 주제 영역 선택(전체 논리 모델·미분류·영역) + [하위 영역 포함]. 범위 조회만 거르고(한 번에 100, 최대 300 그대로), 관계선은 양 끝이 화면에 있을 때만, 범위 밖 관계는 엔터티별 수.
- 이웃 탐색은 영역과 무관하게 모델 전체에서 (상태 줄에 '이웃 탐색은 전체 논리 모델 기준입니다', [주제 영역 범위로] 복귀).
- 엔터티 찾기는 영역을 고른 동안 그 범위 안에서 (최대 50), '찾기 범위 : 경로'.
- 깊은 링크 `?modelId=&subjectAreaId=&includeChildren=false|&unclassified=true&entityId=` (관리 화면 [ERD에서 보기]·ERD [논리 모델 관리]). 없는·지워진·다른 모델·다른 프로젝트 영역이면 '요청한 주제 영역을 찾을 수 없어 전체 논리 모델을 표시합니다' 를 알리고 전체를 보인다 (서버는 그 영역의 정보를 주지 않는다).

## 15. 독립 배치 (D-07·D-26)

- 저장 대상 : 영역 범위 조회 = 그 영역의 내 배치, 그 밖(전체·미분류·이웃 탐색) = 모델 전체 배치. 상태 줄에 '저장 대상 : 전체 논리 모델 배치 / 주제 영역 배치 : 경로'.
- 처음 : 영역 배치가 없으면 모델 배치의 좌표를 참고로 받는다(`source MODEL`, 저장하지 않음), 좌표가 없는 엔터티는 기존 자동 배치 규칙. 저장하면 그 영역의 배치만 생긴다.
- 저장 : 기존과 같이 저장된 좌표 + 요청 좌표 합치기(화면에 없는 엔터티 — 다른 페이지·검색·하위 포함 여부 — 좌표 보존), 이 모델에 없는 엔터티 좌표는 거절, 지워진 엔터티 좌표는 정리. 버전(`VER_NO`)은 영역 배치마다 따로라 다른 영역·모델 배치 저장이 충돌을 만들지 않는다.
- 저장하지 않은 좌표는 대상마다 따로 : 대상이 바뀌는 이동(영역 바꾸기, 이웃 탐색 들어가기·돌아오기)에서 저장하지 않은 배치가 있으면 버릴지 묻는다 (다른 대상의 저장에 섞지 않는다). 같은 대상 안의 검색·페이지·하위 포함 전환은 예전처럼 기억한다.
- 분류 변경은 어떤 배치도 바꾸지 않는다. 다른 영역으로 옮겨진 엔터티의 좌표는 그 영역 배치에 남지만(모델에 있는 엔터티라 정리 대상이 아니다) 화면은 지금 범위의 엔터티만 그린다. 영역 삭제는 그 영역 배치만 지운다.

## 16. 변경 이력

`ModelChangeRecorder` 에 두 종류 : `LOGICAL_SUBJECT_AREA`(model·parent(Id·Name)·areaName·areaDescription·sortOrder — 등록·이름·설명·이동·순서·삭제) 와 `SUBJECT_AREA_ENTITY`(subjectArea·entity — 지정 CREATE, 이동 UPDATE, 해제 DELETE, 목록 이름 "엔터티 ∈ 영역"). 분류는 `LOGICAL_ENTITY` 이력을 만들지 않는다. 일괄 분류는 한 변경 그룹에 엔터티마다 한 건, 롤백된 요청·변경 없는 재지정은 이력이 없다. 개인 배치는 이력 대상이 아니다.

## 17. 모델 버전

스냅샷 형식 **7** : 대상 종류 `LOGICAL_SUBJECT_AREA`·`SUBJECT_AREA_ENTITY` 추가 (같은 필드 목록). 형식 6 이하 버전에는 주제 영역 정보가 없다(빈 것이 아니라 담지 않았다) — 예전 버전과 비교하면 지금의 영역·소속은 모두 추가로 보인다 (VIEW 를 더할 때와 같은 규칙, 기존 버전을 고치지 않는다). 버전 복원 기능은 없다 (확인 — 비교만). 개인 배치는 버전에 담지 않는다 (기존 정책).

## 18. Import·Export 형식 4

- Export : `TYPES` 에 두 종류, 내보낸 사용자의 영역별 배치 `layouts.subjectAreas: [{ subjectAreaId, nodes, viewport }]` (모델 배치를 이미 담던 계약과 같게 — 파일에 들어간 영역·엔터티 좌표만).
- Import : 형식 1·2·3 은 그대로 (주제 영역 없이, 기본 영역을 만들지 않는다). 형식 4 의 영역·소속은 **새로 만드는 논리 모델과 함께만** 만든다 (가져오기는 원래 같은 이름 모델이 있으면 CONFLICT — 기존 모델에 병합하지 않으므로 기존 영역을 지울 일이 없다). 상위부터 새 ID 로 만들고 소속을 새 엔터티·영역에 잇는다. 파일 ID 를 대상 PK 로 쓰지 않는다.
- 파일 검사(어기면 파일 전체 거절, `MODEL_EXCHANGE_INVALID`) : 형식 3 이하에 영역·영역 배치, 파일에 없는 모델, 이름 형식, 없는 상위·자기 상위·다른 모델 상위, 순환, 3단계 초과, 같은 상위 아래 이름 중복(대소문자 무시), 없는 영역·엔터티를 가리키는 소속, 영역·엔터티의 모델이 다른 소속, 엔터티 중복 소속, 영역 배치 번호 중복·좌표 한도.
- 계획 : 논리 모델 항목의 `counts.subjectAreas`, `counts.LOGICAL_SUBJECT_AREA`, 영역 배치 항목 `ERD_LAYOUT`(key `SUBJECT_AREA:모델:번호`). 반영 결과 `created.LOGICAL_SUBJECT_AREA`·`SUBJECT_AREA_ENTITY`.
- 왕복 : 모델 A → 형식 4 → 다른 프로젝트 B → 다시 내보내기 → 영역 경로·소속(엔터티 → 경로)이 같다 (`exchangeFormat4`).

## 19. 성능 결과 (2026-11-20 측정)

임시 앱(단일 JAR, 9953) + 임시 PostgreSQL DB, 같은 프로젝트에 모델 4개(엔터티/영역 100/10 · 1,000/50 · 3,000/200 · 10,000/500, 엔터티당 속성 4, 관계 약 2, 90% 분류). HTTP 왕복 중앙값(5회, 예열 1회 제외, 로컬), 응답 크기.

| 조회 | 100 | 1,000 | 3,000 | 10,000 |
|---|---|---|---|---|
| 영역 목록·집계 | 9.3ms 2.5KB | 10.6ms 12.3KB | 11.4ms 49.6KB | 17.5ms 124.7KB |
| 엔터티 목록 (전체, 100행) | 11.5 | 9.3 | 9.3 | 9.7ms |
| 미분류 | 10.0 | 11.2 | 11.5 | 12.6ms |
| 영역 직접 소속만 | 9.9 | 7.9 | 8.5 | 7.2ms |
| 영역 + 하위 | 12.1 | 11.6 | 12.2 | 10.4ms |
| 영역 + 하위 마지막 페이지 (설명 내림차순) | 12.1 | 9.8 | 11.3 | 11.7ms |
| 영역 + 검색 | 11.1 | 11.7 | 13.5 | 23.5ms |
| 관계 목록 (영역, 정렬) | 22.3 | 19.0 | 43.9 | 47.0ms |
| ERD 영역 100 / 300 | 24.6 / 23.3 | 23.3 / 22.0 | 18.4 / 18.2 | 24.7 / 20.5ms |
| ERD 모델 전체 300 (비교) | 34.7 | 62.7 | 54.1 | 51.3ms (388KB) |
| 이웃 3단계 (영역 무관) | 18.8 | 16.6 | 15.2 | 15.2ms |
| 엔터티 찾기 (영역, 50) | 9.3 | 10.3 | 11.9 | 22.6ms |
| 영역 배치 조회 / 저장(300 좌표) | 8.4 / 6.4 | 6.7 / 8.0 | 6.7 / 9.2 | 8.4 / 10.8ms |
| 일괄 분류 (10~20건 왕복) | 16.4 | 13.3 | 11.6 | 12.4ms |

- SQL 수 : 영역 목록 4 (모델 확인, 영역, 영역별 직접 소속 수, 모델 엔터티 수 — 영역 수와 무관, N+1 없음; 설계의 "3개 내외" + 모델 확인), 엔터티 페이지 7, 관계 페이지 7 (각 +1 = 소속 영역 한 번) — 테스트로 고정.
- Export 형식 4 (프로젝트 전체 : 엔터티 14,197 · 영역 775 · 소속 12,700) 824ms, 27.8MB. 같은 크기의 Import 는 파일 객체 한도(50,000)를 넘어 측정하지 않았다.
- 브라우저 렌더링 시간·메모리·자동 배치 시간은 따로 재지 않았다 (기존 ERD 경로 그대로). 번들 : JS 1,686.94 kB (gzip 480.56) — 토스트 작업 직후 1,659.36 kB 에서 +27.6 kB.

## 20. 테스트 결과

- 신규 : `LogicalSubjectAreaIntegrationTest` 11개 (계층·이름, 이동, 분류·HTTP 본문 null/필드 없음, 원자성·등록 트랜잭션, 삭제·무결성, 범위 조회(목록·관계·ERD·찾기·이웃), 독립 배치 10단계 시나리오 + 상하위·사용자·충돌·미표시 좌표, 이력·버전, 교환 형식 4 왕복·형식 3·거절 8종, 실제 독립 트랜잭션 동시성 9종(모델 행 잠금을 쥔 첫 트랜잭션이 커밋 전 대기하는 동안 둘째가 기다리는지 확인), 역할 5종·격리), `LogicalSubjectAreaMigrationTest` (V1 → V2 업그레이드·DB 제약, H2·PostgreSQL).
- 수정 : 예제 인덱스 수(137→143, 195→205)·마이그레이션 수(2→3), 스냅샷 대상 수(21→23), 형식 번호(버전 6→7, 교환 3→4, '지원하지 않는 형식' 예 4→5), SQL 수 상한(6→7), 프로젝트 삭제 대상 표, 권한 감사 허용 목록.
- 전체 결과 : H2 1,090건 실패 0 · 건너뜀 48, PostgreSQL 18.6 1,090건 실패 0 · 건너뜀 32 (`completed-work.md` 9장). 프런트엔드 `npm run typecheck`·`npm run build` 통과, `bootJar` 단일 JAR 로 임시 앱 실행(신규 설치 V0~V2).

## 21. 잔여 사항

- **Flyway** : 이번 마이그레이션은 `V2__logical_subject_area.sql`, 다음 스키마 변경은 **V3**. 인덱스에 스테이징만 남은 `V2__remove_default_admin.sql`(작업 트리에서는 삭제됨)은 **사용자가 커밋 전에 직접 처리**한다 — Claude 는 스테이징을 바꾸지 않는다 (§3). V0·V1·V2 는 수정하지 않는다.
- **MCP·AI 도구 연계 (후속 개선, 이번 범위 아님 — 기존 AI·MCP 정책 그대로)** : MCP `getLogicalModel`·AI 메타데이터 도구에서 ① 주제 영역 목록·계층 ② 엔터티 소속 ③ 주제 영역별 엔터티·관계 조회 ④ 업무 영역별 품질 진단·분석을 쓸 수 있게 하는 것.
- **미검증 (완료로 보지 않는다)** : 대규모 형식 4 Import 성능(파일 객체 한도 50,000 을 넘는 크기는 측정하지 않음), 브라우저 렌더링 시간·메모리 사용량, Safari·Firefox 호환성(Chrome 헤드리스만), 기존 토스트 전용 회귀 스크립트(UI-1 후속의 bizCheck·memApp 등 — 다시 돌리지 않음), 전체 `./gradlew build`(npm ci 포함 — 실행 중인 개발 서버의 node_modules 보호를 위해 `npm run build` + `bootJar` 로 대신), Linux CI.
- **실제 검증한 것** (§20) : H2 전체 1,090건 실패 0 · 건너뜀 48, PostgreSQL 18.6 전체 1,090건 실패 0 · 건너뜀 32, `npm run typecheck`·`npm run build`, `bootJar` 빌드와 그 JAR 로 띄운 임시 앱(신규 설치 V0~V2 적용, 브라우저·HTTP 검증).

## 22. 제외 범위

프로젝트 공통 영역, 물리 모델 영역, 논리·물리 공통 분류, 다중 소속·대표/참조 영역, 영역별 접근 제어·AI 정책, AI 자동 분류, 물리 스키마 생성·테이블 이동, 모델 간 엔터티 공유, ERD 그룹 박스·복합 노드·영역 색상, 미분류 전용 배치, 영역 공용 배치·사용자 간 공유, M-6 후속 5차(서브타입), M-2 물리 ERD 관계선, AI-6.
