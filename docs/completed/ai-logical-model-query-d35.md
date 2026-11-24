# D-35 논리 모델 조회 확장 (AI-4 · AI-MCP) — D35-1 분석·설계, D35-2 공통 조회 구현

- 단계 : **D35-1 (분석·설계, 2026-11-24)** → 확정 정책 D35-D01~D15 (12장) → **D35-2 공통 조회 서비스·DTO (2026-11-24, 13~18장)** → 확정 정책 D35-D16~D22 → **D35-3a 조회 계약 보완 · D35-3b 내부 AI·MCP 연동 (2026-11-24, 19~26장)** → **D35-FINAL 보완·통합 검증 · 완료 (2026-11-24, 27~38장)**. 1~11장은 D35-1 당시의 분석 기록, 15장의 일부 칸(…Truncated·attributeCount 등)은 19장 계약으로 바뀌었다.
- 근거 : 코드 읽기 (파일 경로 표기) + 측정 (격리 사본의 M-8 최종 빌드 JAR = HEAD `c1404f0` 와 같은 코드(문서 경로 주석만 다름), 임시 H2, 예제 프로젝트 0 + 격리 테스트 모델, 운영 데이터 없음). 측정 스크립트·결과는 세션 scratchpad `d35/` (`measure_all.py`, `measure_all.json`).
- 관련 : [logical-subject-area.md](../completed/logical-subject-area.md), [logical-subtype.md](../completed/logical-subtype.md) (D-35 를 후속으로 둔 결정 — 0장·4장), [ai-tool-calling-ai4.md](../completed/ai-tool-calling-ai4.md), [embedded-mcp-dec31.md](../completed/embedded-mcp-dec31.md), [logical-physical-mapping-m7.md](../completed/logical-physical-mapping-m7.md), [modeling-concurrency-m8.md](../completed/modeling-concurrency-m8.md).

## 1. 선행 작업 상태 (pending-work·completed-work 기준)

| 작업 | 상태 |
|---|---|
| M-6 논리 모델링 (주제 영역 4차, 슈퍼타입·서브타입 5차) | 완료 (2026-11-20). D-35 를 명시적 후속으로 남김 |
| M-7 논리·물리 매핑 · 상속 물리 변환 (B · A·C) | 완료 (상속 변환 6차 · A·C 2026-11-22~23). M-7 최종 통합 검증만 미착수 |
| M-8 모델링 동시 편집 안정성 | 완료 (2026-11-24, 커밋 `c1404f0`) |
| AI-4 메타데이터 Tool Calling | 완료 (2026-11-15) — **논리 모델 도구 없음** |
| AI-MCP 내장 MCP (DEC-31) | 완료 — `getLogicalModel` 은 주제 영역·상속 정보 없음 |
| D-35 | **미착수** (pending-work [M-6 후속]·[AI-MCP]·[AI-4] 3번 항목). 일부 구현된 것도 없다 |

## 2. 현행 분석

### 2.1 AI-4 내부 Tool (`lexorma-ai` `ai/tool/MetadataTools.java`, `MetadataToolset.java`)

10개, 모두 물리·사전 : `listSchemas`, `searchTables`, `getTableDetail`, `getTableColumns`, `getTableRelations`, `searchStandardTerms`, `searchStandardWords`, `searchStandardDomains`, `findSimilarColumns`, `getMetadataHistory`. **논리 모델·엔터티·주제 영역·상속을 다루는 Tool 은 하나도 없다.**

| 항목 | 현행 |
|---|---|
| 등록 | Spring AI `FunctionToolCallback` (record 입력 — `-parameters` 없이 이름 고정), 요청마다 등록, 허용 목록 `MetadataToolset.NAMES` (바꾸면 `MetadataToolIntegrationTest` 고정 목록도 바꾼다) |
| 프로젝트 | 인자로 받지 않는다 — `MetadataToolSession.scope()` 가 서버 실행 컨텍스트의 프로젝트로 `AccountDto` 를 만든다. 다른 프로젝트 ID 는 서비스의 프로젝트 조건으로 '없음' |
| 권한 | Tool 호출마다 `ExecutionContextVerifier.verify(context, "VIEWER")` (프로젝트 상태·멤버십·현재 역할을 DB 에서 다시) |
| 트랜잭션 | Tool 호출마다 읽기 전용 `TransactionTemplate` (타임아웃 30초) |
| 상한 | 결과 24,000자(넘으면 `RESULT_TOO_LARGE`), 질의당 Tool 8번 · 같은 Tool 4번, 전체 180초, 검색 결과 기본 10 · 최대 20, 테이블당 컬럼 100 · 관계 50 · 인덱스 30, 설명 300자 |
| 승인 | 화면 조회 API 처럼 승인·미승인 모두, `approved` 로 구분 (AI-4 문서 44행) |
| 기록 | Tool 이름·결과 상태·시간만 (인자·결과 원문 없음) |
| 근거 검증 | 결과에 나온 ID 를 `saw()` 로 세션에 기록 → 최종 답변의 근거를 `EvidenceVerifier` 가 `EvidenceCatalog`(DB 현재 값, 수정 일시가 버전)로 판정. 근거 유형은 `EvidenceType` = SCHEMA·TABLE·COLUMN·RELATION·TERM·WORD·DOMAIN·HISTORY, 답변 스키마 `ai/metadata-answer.schema.json` 의 enum 도 같은 8개 |
| 도구 없는 모델 | `ServerContextAssembler` 가 규칙으로 질문을 해석해 같은 Tool 을 최대 5번 — 물리·사전만 |

### 2.2 내장 MCP (`ai/mcp/service/McpServerService.java`)

- 기본 꺼짐 (`lexorma.mcp.enabled` + 프로젝트 설정). Streamable HTTP(JSON 응답만), JSON-RPC 2.0, Bearer 토큰(해시·만료·폐기, 발급 계정이 지금도 관리자·ADMIN 인지 요청마다 확인), 토큰당 분당 120회, 결과 24,000자, 감사(`TB_MCP_CALL_HISTORY` : 도구·결과·일시만).
- 도구 10개 = AI-4 8개(`SHARED_TOOLS` — `findSimilarColumns`·`getMetadataHistory` 는 제외)를 `MetadataToolset.run` 으로 공유 + **MCP 전용 2개** `getProjectInfo`·`getLogicalModel`. `tools/list` 응답 6,692자(추정 2,240 토큰 — 측정).
- 전용 2개는 `verifier.verify(context, "VIEWER")` 뒤 읽기 전용 트랜잭션에서 **`McpServerService` 안의 JPQL 로 직접** 읽는다 — 업무 서비스·근거 기록을 쓰지 않는다 (공유 Tool 과 다른 구현).

**`getLogicalModel` 현재 계약** (입력 `modelName` 선택, 추가 속성 금지) :

```json
// modelName 없음 : 모델 목록
{"models": [{"modelName": "LEXORMA", "description": "", "entities": 91}]}
// modelName 있음 : 모델 전체 (이름순 엔터티, 직접 정의 속성만, 식별자, 관계)
{"modelName": "D35 상속 모델",
 "entities": [{"entityName": "개인고객", "description": "개인고객 설명", "approved": false,
               "attributes": [{"attributeName": "생년월일", "dataType": "문자", "required": false, "standardTerm": null}],
               "identifiers": []}],
 "relationships": [{"name": "고객 주문", "parent": "고객", "child": "주문", "identifying": false, "cardinality": "1..1 : 0..N"}]}
```

- ID 를 주지 않는다 (이름만). 같은 모델 이름은 프로젝트에서 유일(`UX_LGCL_MDL_01`).
- **결함 (측정으로 확인)** : 3단계 서브타입 `개인고객` 이 '생년월일' 하나·식별자 없음으로 나온다 — 상속 속성 5개와 상속 복합 주 식별자(당사자번호·당사자구분)가 빠져 사실과 다르게 보인다. 주제 영역·서브타입 그룹·상속 관계 정보가 없다.
- **크기 한계 (측정)** : 예제 모델 `LEXORMA` (엔터티 91·속성 1,010·관계 109) 의 상세는 24,000자를 넘어 **지금도 `RESULT_TOO_LARGE`** — 예제 모델 상세를 전혀 돌려주지 못한다. 같은 모델의 논리 ERD 전체(`/erd`)는 272,633자(추정 106,695 토큰).

### 2.3 논리 모델 도메인 · 기존 조회 로직 (`lexorma-data` `logical/`)

| 기능 | 기존 서비스 / DTO | 보안 컨텍스트(`AuthUtil`) 의존 | 분류 (작업 지시 7장) |
|---|---|---|---|
| 모델 목록·요약 | `LogicalModelService.selectSummary` (`Summary`) | 없음 | 1 그대로 |
| 엔터티 목록 (검색·주제 영역 범위·페이지) | `selectEntityRows(…, subjectAreaId, unclassified, includeChildren)` → `EntityRow(id, name, description, isApproval, attributeCount, relationshipCount, modifiedAt, subjectAreaId)`, 페이지 1~100 (`PageableUtil`) | 없음 | 1 그대로 (AI 용 상한만 따로) |
| 주제 영역 계층 | `LogicalSubjectAreaService.selectTree` → `Tree(modelId, entityCount, unclassifiedCount, areas[Area(id, parentId, name, description, sortOrder, depth, path, directEntityCount, totalEntityCount, childCount, modifiedAt)])` — 조회 4번 | 없음 | 1 그대로 |
| 영역 범위 규칙 | `scope(…)` : 영역은 **기본 하위 포함**, `includeChildren=false` 면 그 영역만, `unclassified=true` 는 미분류만, 둘 다면 400, 다른 모델 영역은 찾을 수 없음 | 없음 | 1 그대로 |
| 서브타입 그룹 목록·상세 | `LogicalSubtypeService.selectGroupRows`·`selectGroup` (`GroupRow`, `Group`) | 없음 | 1 그대로 |
| 모델 상속 구조 | `LogicalHierarchy` (ID 투영으로 모델 전체 — `hierarchy(modelId)`; `chain`·`ancestors`·`descendants`·`level`·`root`·`groupsOf`·`parentGroup`) | 없음 (순수 계산) | 1 그대로 |
| 엔터티 상속 정보 | `selectInheritance` → `Inheritance(level, root, parent(그룹·분류 값), ancestors, groups(구성원), descendantCount, rootPrimaryKeyMissing, attributes[EffectiveAttribute], identifiers[EffectiveIdentifier], inheritedRelationships)` | **있음** — `editable()` 이 승인된 엔터티면 `AuthUtil.isApprovalAvailable()` 를 부른다. 이 함수는 `SecurityContextHolder` 의 인증을 바로 꺼내 쓰므로 로그인 세션이 없는 MCP 호출에서는 실패한다 | **3 공통화 필요** |
| 유효 속성 | `selectEffectiveAttributes` (`EffectiveAttribute(attributeId, contextEntityId, definedEntityId, definedEntityName, inherited, depth, editable, …, primary, keyOrder, modifiedAt)`) | 같음 | **3** |
| ERD 범위 조회 | `erd/part`·`neighbors` (`subtypeGroups`·`outsideInheritances`·`inheritedKey`) | 없음 | 참고 (화면용 — 좌표·레이아웃 성격이라 Tool 에는 과다) |
| 논리·물리 매핑 (변환 결과) | `ModelMapping…` 목록 행의 `inheritance`·`physical.strategy` (M-7) | — | 5 정책 결정 (D-35 범위 밖 제안) |

상속 의미는 M-6 확정 그대로 계산되어 있다 : 루트 → 자기 순서의 유효 속성(`depth` 0 = 직접, 1 = 직접 슈퍼타입 …), 유효 주 식별자 = 루트의 주 식별자(복합이면 `keyOrder`), 조상의 보조 식별자는 상속(측정 : `고객 이메일 AK` 가 `개인고객` 에 inherited 로), 서브타입 자체 보조 식별자는 직접 속성만(D-17), 상속 관계는 복제하지 않고 조상 정의를 역할(SOURCE·TARGET·SELF)과 함께. **AI·MCP 에서 같은 계산을 새로 만들 필요가 없다.** M-7 물리 변환(B·A·C)은 별도 기록(`TB_SBTYP_GROUP_CNVR` 등)이고 논리 상속 조회와 섞지 않는다.

공통화 방안 (D35-2) : `LogicalSubtypeService` 의 상속 계산을 `editable` 없이 쓰는 경로로 나눈다 — 예 : `inheritanceView(projectId, entityId)` 가 같은 private 계산(`effectiveAttributes`·`effectiveIdentifiers`·조상·그룹)을 쓰고, 화면용 `selectInheritance` 는 그 결과에 `editable` 만 얹는다. 화면 응답·계산 결과는 바뀌지 않는다 (기존 `LogicalSubtypeIntegrationTest`·`LogicalM6FinalPerformanceTest` 로 회귀 확인). AI·MCP 가 `AuthUtil` 을 부르지 않게 하는 것이 목적이다.

## 3. 측정 (읽기 전용, 임시 H2)

추정 토큰 = 기존 `TokenEstimator` 규칙 (ASCII 문자 ÷ 3 + 그 밖의 문자마다 1 — 보수적). 시간은 같은 Mac 의 단일 요청 (참고값).

| 대상 | 크기 (문자) | 추정 토큰 | 시간 |
|---|---|---|---|
| MCP `tools/list` (도구 10개) | 6,692 | 2,240 | — |
| MCP `getLogicalModel` 목록 | 93 | 41 | 15ms |
| MCP `getLogicalModel` 예제 모델 상세 | **24,000 초과 → RESULT_TOO_LARGE** | — | 36ms |
| 예제 모델 논리 ERD 전체 (`/erd`, 엔터티 91·관계 109) | 272,633 | 106,695 | 122ms |
| 예제 엔터티 1개 상속 정보 (`/inheritance`, 속성 16) | 6,535 | 2,405 | 21ms |
| 예제 모델 엔터티 91개의 상속 정보 합 (엔터티마다 호출) | 422,699 (최대 12,136) | — | 엔터티당 최대 34ms |
| 테스트 모델 `getLogicalModel` 상세 (엔터티 6) | 1,693 | 667 | 4ms |
| 테스트 모델 주제 영역 트리 (영역 4, 3단계) | 995 | 383 | 4ms |
| 영역 '고객 관리' 엔터티 목록 (하위 포함) | 769 | 279 | 25ms |
| `개인고객` 상속 정보 (3단계, 복합 PK·AK 상속) | 3,694 | 1,332 | 9ms |
| `개인고객` 유효 속성 | 2,216 | 802 | 5ms |
| 테스트 모델 서브타입 그룹 목록 | 536 | 192 | 7ms |
| 테스트 모델 ERD 범위 조회 (`erd/part`) | 4,558 | 1,666 | 15ms |

- 예제 모델에는 주제 영역·서브타입 그룹이 없다 (0개) — 상속·영역 측정은 격리 테스트 모델(영역 3단계 + 1개, 상속 3단계 `당사자 → 고객 → 개인고객·법인고객`, 형제 `거래처`, 복합 주 식별자, 자체 보조 식별자, 관계 1, 미분류 1)로 했다.
- 화면 DTO 는 AI 에 불필요한 칸(`modifiedAt`·`editable`·`contextEntityId`·`termId`·`sortOrder` 등)을 포함한다 — AI 용으로 칸을 고르면 상속 정보는 대략 절반 이하로 준다 (추정, D35-2 에서 측정).
- 모델 전체에 유효 속성을 붙이면 상속 속성이 서브타입마다 반복된다 (측정 모델 : `개인고객` 유효 속성 6개 중 5개가 상속). 예제 규모 모델은 상속이 없어도 이미 24,000자를 넘는다 → **모델 전체 + 유효 속성 한 번에** 는 상한 안에서 불가능하다.
- 성능 : 상속 계산은 `LogicalHierarchy`(ID 투영) + 조상 체인만 읽어 엔터티당 수~수십 ms. N+1·LazyInitialization·순환 직렬화는 기존 DTO(평면 record)에서 보이지 않았다. 모델 전체를 엔터티마다 부르는 방식(91회)만 피하면 된다. 캐시·외부 저장소는 필요 없다.

## 4. Tool 구성 비교

| | A. 기존 `getLogicalModel` 응답 확장 | B. 별도 상세 Tool 추가 | C. 기존 Tool 은 요약·호환 유지 + 상세 Tool 분리 (추천) |
|---|---|---|---|
| 호환 | 칸 추가는 호환, 상속 속성을 attributes 에 넣으면 의미 변경 | 기존 그대로 | 기존 그대로 (추가 칸만) |
| 크기 | 이미 예제 모델이 상한 초과 — 더 커진다 | 필요한 범위만 | 요약은 작게, 상세는 엔터티·영역 단위 |
| 내부 AI | MCP 전용이라 내부 AI 에는 여전히 없음 | 공유 Tool 로 두면 둘 다 | 공유 Tool 로 둘 다 |
| Tool 수 | 그대로 | +3 내외 | +3 내외 (MCP 는 기존 2개 유지) |
| 호출 수 | 1번 (안 되는 규모가 많다) | 질문당 2~3번 (8번 상한 안) | 같음 |
| 비용 | 낮음 (하지만 문제 해결 안 됨) | 중간 | 중간 + `getLogicalModel` 소폭 보완 |

**추천 C** : 공유 Tool 3개를 `MetadataTools` 에 더해 내부 AI 와 MCP 가 같은 구현을 쓰고(업무 서비스 재사용, 근거 기록 포함), MCP 의 `getLogicalModel` 은 계약을 유지하되 상속 누락이 오해되지 않게 칸만 더한다 (5.4).

## 5. 응답 계약 후보 (C 기준)

공통 : 프로젝트는 실행 컨텍스트(내부 AI)·토큰(MCP)에서만, 모델은 `modelId` 또는 `modelName`(정확히), 엔터티는 `entityId` 또는 `modelName+entityName`. 승인 칸은 기존처럼 `approved`. 이름·설명은 300자 자르기, 목록은 기본 10 · 최대 20 (+ `truncated`) — 기존 AI-4 상한 재사용.

### 5.1 `getLogicalModelOverview` — 모델 요약·주제 영역·상속 구조 (속성 없음)

입력 `modelId?`, `modelName?` (둘 다 없으면 모델 목록 — `getLogicalModel` 목록과 같은 내용 + `modelId`). 재사용 : `selectSummary` + `selectTree` + `LogicalHierarchy`·`selectGroupRows`.

```json
{"modelId": 2, "modelName": "D35 상속 모델", "entityCount": 6, "relationshipCount": 1, "unclassifiedEntityCount": 1,
 "subjectAreas": [
   {"subjectAreaId": 11, "name": "고객 관리", "parentId": null, "path": ["고객 관리"], "depth": 1, "directEntityCount": 2, "totalEntityCount": 4},
   {"subjectAreaId": 12, "name": "개인 고객 관리", "parentId": 11, "path": ["고객 관리", "개인 고객 관리"], "depth": 2, "directEntityCount": 1, "totalEntityCount": 2},
   {"subjectAreaId": 13, "name": "우수 고객", "parentId": 12, "path": ["고객 관리", "개인 고객 관리", "우수 고객"], "depth": 3, "directEntityCount": 1, "totalEntityCount": 1}],
 "subtypeGroups": [
   {"groupId": 7, "name": "당사자 구분", "supertype": "당사자", "disjoint": true, "complete": true, "classificationAttribute": "당사자구분", "subtypes": ["고객", "거래처"]},
   {"groupId": 8, "name": "고객 유형", "supertype": "고객", "disjoint": true, "complete": false, "classificationAttribute": null, "subtypes": ["개인고객", "법인고객"]}],
 "truncated": false}
```
- 주제 영역이 없는 모델 : `"subjectAreas": []`, `unclassifiedEntityCount` = 전체. 상속이 없는 모델 : `"subtypeGroups": []`.
- 엔터티 상세·속성은 넣지 않는다 (영역 목록에서 엔터티를 반복하지 않는다).

### 5.2 `searchLogicalEntities` — 엔터티 찾기 (영역·검색어·상속 위치)

입력 `modelId|modelName`, `keyword?`, `subjectAreaId?`, `includeChildren?`(기본 true — 기존 규칙), `unclassifiedOnly?`, `limit?`. 재사용 : `selectEntityRows` (영역 범위 규칙·검색 규칙 그대로) + `LogicalHierarchy` (역할·단계).

```json
{"entities": [
   {"entityId": 21, "entityName": "개인고객", "description": "개인고객 설명", "approved": false, "subjectArea": ["고객 관리", "개인 고객 관리"],
    "attributeCount": 1, "inheritance": {"role": "SUBTYPE", "level": 3, "supertype": "고객"}},
   {"entityId": 17, "entityName": "당사자", "approved": false, "subjectArea": ["고객 관리"], "attributeCount": 3,
    "inheritance": {"role": "SUPERTYPE", "level": 1, "subtypeGroups": ["당사자 구분"]}}],
 "totalCount": 4, "truncated": false}
```
- `attributeCount` 는 직접 정의 속성 수임을 설명에 적는다 (상속 속성은 5.3).

### 5.3 `getLogicalEntityDetail` — 엔터티 하나의 직접·상속 속성, 식별자, 상속 경로, 관계

입력 `entityId` 또는 `modelName+entityName`. 재사용 : 공통화한 상속 계산 (2.3) + 엔터티 관계 (`/entity/{id}/relationship` 의 서비스). 속성 상한 = 기존 테이블당 컬럼 100.

```json
{"entityId": 21, "entityName": "개인고객", "approved": false, "subjectArea": ["고객 관리", "개인 고객 관리"],
 "inheritance": {"level": 3, "path": ["당사자", "고객", "개인고객"], "parentGroup": {"name": "고객 유형", "supertype": "고객", "classificationValue": null},
                 "subtypeGroups": [], "rootPrimaryKeyMissing": false},
 "attributes": [
   {"attributeId": 101, "name": "당사자번호", "definedIn": "당사자", "inherited": true, "depth": 2, "required": true, "dataType": "문자", "primaryKeyOrder": 1},
   {"attributeId": 102, "name": "당사자구분", "definedIn": "당사자", "inherited": true, "depth": 2, "required": true, "dataType": "문자", "primaryKeyOrder": 2},
   {"attributeId": 103, "name": "등록일자", "definedIn": "당사자", "inherited": true, "depth": 2},
   {"attributeId": 104, "name": "고객등급", "definedIn": "고객", "inherited": true, "depth": 1},
   {"attributeId": 105, "name": "고객이메일", "definedIn": "고객", "inherited": true, "depth": 1},
   {"attributeId": 106, "name": "생년월일", "definedIn": "개인고객", "inherited": false, "depth": 0}],
 "identifiers": [
   {"name": "당사자 PK", "primary": true, "definedIn": "당사자", "inherited": true, "attributes": ["당사자번호", "당사자구분"]},
   {"name": "고객 이메일 AK", "primary": false, "definedIn": "고객", "inherited": true, "attributes": ["고객이메일"]}],
 "relationships": [{"name": "고객 주문", "parent": "고객", "child": "주문", "identifying": false, "cardinality": "1..1 : 0..N", "inheritedFrom": "고객"}],
 "truncated": false}
```
- 상속 없는 엔터티 : `"inheritance": {"level": 1, "path": ["주문"], "parentGroup": null, "subtypeGroups": []}`, 모든 속성 `inherited: false`.
- 슈퍼타입 : `subtypeGroups` 에 직접 그룹과 구성원.
- 직접 정의와 상속을 `inherited`·`definedIn`·`depth` 로 구분 — M-6 의미 그대로 (새 의미 없음).

### 5.4 MCP `getLogicalModel` (호환 유지)

목록·상세의 기존 칸은 그대로. 상세에 칸만 더하는 안 : 엔터티마다 `supertype`(직접 슈퍼타입 이름)·`subjectArea`(경로), 속성 목록이 **직접 정의 속성만**이라는 `attributesScope: "DIRECT"` 와 도구 설명 보완, 모델 수준 `subtypeGroups` 요약. 크기 문제(예제 모델 초과)는 그대로이므로 설명에서 큰 모델은 5.1~5.3 을 쓰라고 안내한다. (응답을 엔터티 단위로 잘라 주는 페이지화는 계약 변경이라 결정 항목.)

## 6. 권한 · 프로젝트 격리 · 승인 · 일관성

- **권한·격리** : 새 Tool 도 `MetadataToolset.run` 경계(호출마다 VIEWER 재검증, 실행 컨텍스트·토큰의 프로젝트만, 읽기 전용 트랜잭션)를 그대로 지난다. 모델·엔터티·영역·그룹 ID 는 기존 서비스가 프로젝트 조건으로 확인한다 (`model(projectId, modelId)`, `areaRepository.findModelId(areaId, projectId)`, `entity(projectId, entityId)` — 다른 프로젝트 ID 는 '없음'). 멤버십 제거·토큰 폐기·프로젝트 비활성은 기존 재검증으로 다음 호출부터 막힌다. 사용자 입력 프로젝트 ID 는 받지 않는다.
- **승인** : 기존 AI-4 정책(승인·미승인 모두, `approved` 표시)으로 충분하다. 주제 영역·서브타입 그룹에는 승인 상태가 없고, 상속 속성은 정의 엔터티의 승인 상태를 따른다 — 슈퍼타입·서브타입의 승인이 달라도 각 엔터티의 `approved` 와 `definedIn` 으로 표현된다 (새 정책 불필요). 논리 관계에는 AI 추정 출처가 없고, AI 제안(미확정)은 어떤 Tool 에도 노출하지 않는다 (DEC-31 정책 그대로).
- **읽기 일관성 (M-8)** : Tool 호출 하나 = 읽기 전용 트랜잭션 하나, 기본 격리(READ COMMITTED)라 한 호출 안의 여러 쿼리가 서로 다른 커밋을 볼 수 있다 (화면 조회와 같다). 잠재 사례 : 계층(그룹 링크)을 읽은 뒤 그 사이 그룹이 지워지고 엔터티가 지워지면 조상 엔터티 조회가 비어 예외가 날 수 있다 — 참여 엔터티 삭제는 거절되므로(상속 먼저 해제) 같은 트랜잭션 안의 두 커밋이 겹쳐야 하는 드문 경우. 재현은 하지 않았다. 제안 : D35-2 에서 이 경우를 '대상이 바뀌었음' 오류(다시 조회)로 돌려주고, 배타 잠금·기준값 검증은 넣지 않는다.

## 7. AI 활용 시나리오

| 질문 | 기존 Tool | D-35 뒤 |
|---|---|---|
| 고객 관리 주제 영역의 엔터티 | 불가 (논리 Tool 없음, MCP 상세는 영역 정보 없음) | `getLogicalModelOverview` → `searchLogicalEntities(subjectAreaId)` |
| 하위 영역까지 포함해 설명 | 불가 | 같음 (`includeChildren` 기본 true) |
| 엔터티의 슈퍼타입·서브타입 | 불가 | `searchLogicalEntities` 의 `inheritance` 또는 `getLogicalEntityDetail` |
| 개인·법인 고객의 공통 상속 속성 | 불가 (MCP 는 상속 속성이 없어 '공통 속성 없음' 으로 오해할 수 있다) | `getLogicalEntityDetail` 두 번 — 같은 `attributeId` 의 inherited 속성 |
| 서브타입의 유효 속성 · 상속 주 식별자 · 직접/상속 구분 | 불가 | `getLogicalEntityDetail` |
| 논리 상속이 어떤 물리 테이블로 변환됐는지 | 불가 | **D-35 범위 밖 제안** — M-7 매핑·변환 기록 조회 Tool 이 따로 필요 (결정 ⑪) |

AI 가 결과에 없는 상속·업무 의미를 만들지 않도록 : 기존 시스템 프롬프트의 "Never invent …" 규칙에 엔터티·속성·주제 영역·상속을 더하고, Tool 설명에 '직접 정의 / 상속' 과 '없는 정보는 Lexorma 에 등록되지 않은 것' 을 적는다. 내부 AI 답변 근거는 근거 유형 확장이 필요하다 (결정 ⑦).

## 8. 후속 단계 (제안)

- **D35-2 공통 조회** : 상속 계산 공통화(AuthUtil 분리, 화면 응답 불변), AI 용 읽기 DTO(5.1~5.3, 칸 선택·상한), 프로젝트 범위 검증 재사용, H2·PostgreSQL 테스트.
- **D35-3 내부 AI·MCP 연동** : 결정된 Tool 을 `MetadataTools` 에 등록(허용 목록·`MetadataToolIntegrationTest` 고정 목록), MCP `SHARED_TOOLS` 공유, `getLogicalModel` 보완(5.4), 근거 유형·답변 스키마·`EvidenceCatalog` 확장(결정 ⑦), 시스템 프롬프트·Tool 설명, `ServerContextAssembler`(도구 없는 모델) 포함 여부, 감사는 기존 그대로.
- **D35-FINAL** : 실제 AI Provider 로 Tool Calling(Provider 가 필요 — 없으면 미검증으로 보고), 외부 MCP 클라이언트 호출, 응답 크기·성능 측정(예제 + 대규모 격리 모델), 전체 회귀, 단일 JAR, 문서 정리.

## 9. 테스트 계획

재사용 : `McpEndpointIntegrationTest`(도구 목록·`getLogicalModel` 목록·감사), `McpDisabledIntegrationTest`, `MetadataToolIntegrationTest`(허용 목록·읽기 전용·프로젝트 범위), `LogicalSubtypeIntegrationTest`·`LogicalSubjectAreaIntegrationTest`(계산·범위 규칙), `LogicalM6FinalPerformanceTest`.

추가 (D35-2·3) : 주제 영역 없는 모델 / 단일·3단계 영역 / 미분류 / 영역별(하위 포함·제외) / 상속 없는 엔터티 / 단일·다단계 상속 / 복합 주 식별자 상속 순서 / 직접·상속 속성 구분 / 자체·상속 보조 식별자 / 그룹 구성원·분류 / `getLogicalModel` 기존 칸 호환(스냅샷 비교) / 잘못된 모델·엔터티 ID / 다른 프로젝트 모델·엔터티·영역·그룹 ID / 멤버십 제거·토큰 폐기 뒤 / MCP 비활성 / 민감정보(예시 값·비밀) 미노출 / 상한(`truncated`·RESULT_TOO_LARGE) / 직렬화 / 내부 AI Tool 과 MCP 결과 동일 / 화면 `selectInheritance` 응답 불변(공통화 회귀) / MCP 세션(로그인 없음)에서 승인된 엔터티 상속 조회가 `AuthUtil` 없이 동작.

## 10. 남은 위험

- 예제 규모 모델의 MCP `getLogicalModel` 상세는 지금도 상한 초과 — D-35 와 별개로 존재하는 한계 (결정 ⑨).
- 내부 AI 근거 검증 확장 없이 논리 Tool 만 넣으면 논리 근거를 인용할 수 없다 (답변 스키마 enum).
- Tool 이 늘면 요청마다 보내는 Tool 정의가 커진다 (지금 MCP 목록 2,240 토큰 추정, Tool 하나에 약 150~300 토큰 추정).

## 11. 사용자 결정 필요 사항

1. **Tool 구성** : C (추천 — 공유 Tool 3개 + `getLogicalModel` 호환 보완) / B (공유 Tool 만, `getLogicalModel` 은 그대로) / A.
2. **주제 영역 기본 응답** : 개요에 영역 계층·수만, 엔터티는 `searchLogicalEntities` 로 (추천) / 개요에 영역별 엔터티 이름까지.
3. **하위 영역 포함** : 기존 화면 규칙(기본 하위 포함, `includeChildren=false` 로 직접만) 그대로 (추천 — 새 규칙 없음). 확인만.
4. **상속 기본 응답** : 검색 결과에는 역할·단계·직접 슈퍼타입만, 유효 속성은 엔터티 상세에서만 (추천).
5. **유효 속성 상세** : 엔터티 단위만 (추천) / 모델 전체 일괄 (측정상 상한 초과 — 비추천).
6. **상속 주 식별자 표현** : 속성에 `primaryKeyOrder` + 식별자 목록의 `inherited`·`definedIn` (추천).
7. **내부 AI 포함 범위** : 이번에 내부 AI 와 MCP 둘 다 (근거 유형·답변 스키마·`EvidenceCatalog` 확장 포함 — 추천) / MCP 먼저, 내부 AI 는 후속.
8. **신규 상한** : 영역 목록·그룹 목록·엔터티 속성 수 — 기존 상한(목록 20·컬럼 100·24,000자) 재사용 (추천), 영역·그룹이 그보다 많으면 `truncated`.
9. **`getLogicalModel` 크기 한계** : 그대로 두고 설명으로 새 Tool 안내 (추천) / 상세를 엔터티 페이지 단위로 바꾸기 (계약 변경).
10. **모듈 경계** : 공통 계산은 `lexorma-data` `logical/service` (업무 서비스 옆, AuthUtil 없는 메서드), AI 용 DTO·Tool 은 `lexorma-ai` (추천 — data → ai 참조 금지 규칙 유지).
11. **물리 변환 결과 설명** (논리 상속 → 물리 테이블) : D-35 범위 밖, M-7 매핑 조회 Tool 로 후속 (추천) / D-35 에 포함.

---

## 12. 사용자 확정 정책 (D35-D01~D15, 2026-11-24)

| ID | 확정 내용 | D35-2 반영 |
|---|---|---|
| D01 | 방안 C : 공유 Tool `getLogicalModelOverview`·`searchLogicalEntities`·`getLogicalEntityDetail` (D35-3), MCP `getLogicalModel` 유지 | 세 Tool 의 기반 서비스 `LogicalMetadataService.overview`·`searchEntities`·`entityDetail` (+ `models`) 구현. Tool 등록 없음 |
| D02 | 모델 요약에 주제 영역 ID·이름·설명·상위 ID·경로·직접/하위 포함 엔터티 수, 엔터티 상세 속성 없음 | `ModelOverview.subjectAreas` |
| D03 | 하위 영역 기본 포함, false = 직접만, 미분류 지원, 깊이 그대로 | 기존 `LogicalSubjectAreaService.scope` 그대로 호출 |
| D04 | 목록에는 직접 슈퍼타입·직접 서브타입·그룹·참여 여부만 | `EntityItem.inheritance` (`InheritanceSummary`) |
| D05 | 유효 속성은 엔터티 상세에서만 | `EntityDetail.attributes` 만 |
| D06 | 식별자 : 직접·상속 주·상속 보조·자체 보조, 정의 엔터티, 상속 여부, 구성 순서 | `IdentifierItem.kind`·`definedEntity*`·`attributes[order]` |
| D07 | 내부 AI·MCP 같은 서비스, MCP 꺼져도 내부 AI 사용, 자기 MCP 네트워크 호출 금지 | 서비스는 `lexorma-ai` `ai/tool` 의 일반 Bean (MCP 설정과 무관) |
| D08 | 기존 상한 (24,000자·8회·같은 Tool 4회·목록 20·30초) — 실행 경계가 관리 | 서비스는 목록 20·하위 항목 100·관계 50·설명 300자만 (기존 AI-4 값 재사용), 호출 수·결과 크기는 `MetadataToolset` 그대로 |
| D09 | `getLogicalModel` 은 D35-3 에서 보완 (직접 속성 명시·상속 여부·상위 엔터티·Tool 안내·RESULT_TOO_LARGE 안내), 전체 조회를 요약으로 바꾸지 않음 | 이번에 손대지 않음 (D35-3) |
| D10 | 업무 계산 공통화, AuthUtil 직접 의존 제거, 화면 editable 은 화면 서비스 책임 | `LogicalSubtypeService.inheritance(…, Editability)` (14장) |
| D11 | 물리 변환 결과 설명 제외 | 제외 |
| D12 | READ COMMITTED 유지, 실제 불일치만 '다시 조회' 오류, 잠금·기준값·재시도 없음 | 상속 사슬 엔터티·부모 그룹이 조회 중 사라진 경우만 `MODEL_CONFLICT` (16장) |
| D13 | 승인·미승인 모두, 승인 상태 표시, 상속 속성 승인 = 정의 엔터티 | `AttributeItem.approved` = 정의 엔터티의 승인 |
| D14 | 근거 유형·답변 스키마는 D35-3. 이번 DTO 는 안정적 식별 정보 | 모든 대상에 DB ID |
| D15 | 안정 정렬 + ID 보조 정렬, 최대 20, 페이지, 전체 수, 추가 결과 여부, 잘못된 범위 검증 | `Paging(page, limit, returnedCount, totalCount, hasMore, nextPage)`, 음수 page → `AI_QUERY_INVALID` |

## 13. 구현 구조 (D35-2)

```
lexorma-ai  com.lexorma.ai.tool
  LogicalMetadataService   ← D35-3 Tool 이 부를 공통 조회 (프로젝트 ID 를 받는다, 컨트롤러 없음)
  LogicalMetadataDto       ← AI·MCP 공통 결과 (화면 DTO 를 그대로 보내지 않는다)
        │ 기존 서비스 호출 (data → ai 참조 없음 — ModuleBoundaryTest 규칙 그대로)
lexorma-data  com.lexorma.data.logical
  LogicalModelService        selectModels · selectModel · selectSummary · selectEntityRows(영역 범위) · selectEntityRelationships   (변경 없음)
  LogicalSubjectAreaService  selectTree · scope (변경 없음) + selectAreaIds (신규 읽기 1개)
  LogicalSubtypeService      hierarchy · selectGroupRows (변경 없음) + selectInheritanceView · selectGroups (신규 읽기 2개), 상속 계산 공통화
  LogicalModelQueryRepository  modelCounts (신규 — 모델별 수 묶음 조회 4번)
  LogicalEntityRepository      findByNameIgnoreCase (신규 — 이름으로 엔터티)
```

- 모듈 : 새 Gradle 모듈 없음. `lexorma-ai` 가 `lexorma-data` 를 쓰는 기존 방향만 (`data` 는 `ai` 를 모른다). AI Provider·Spring AI 의존 없는 순수 읽기 서비스다 (`ai/tool` 패키지에 둔 것은 `MetadataTools` 의 상한 상수·`text()` 를 그대로 쓰기 위해서).
- 재사용 범위 : 모델 목록·요약, 엔터티 목록(검색 규칙·영역 범위·정렬·페이지·행별 수), 주제 영역 트리(경로·수), 그룹 목록, 상속 계층(ID 투영), 상속 계산(유효 속성·유효 식별자·조상·그룹·상속 관계), 엔터티 관계(속성 짝 판정). AI 전용으로 다시 만든 계산은 없다 — 새 코드는 대상 해석(ID·이름), DTO 변환, 상한 표시, 모델별 수 묶음 조회뿐이다.
- 프로젝트 범위 : 모든 메서드가 `Long projectId` (호출한 쪽이 검증한 값 — 내부 AI 는 `MetadataToolSession.scope()`, MCP 는 토큰의 프로젝트, 둘 다 `MetadataToolset` 이 호출마다 VIEWER 재검증). 서비스 안에서 프로젝트만 담은 `AccountDto` 를 만들어 기존 서비스에 넘긴다 (계정·역할 없음 — 화면 권한 계산을 하는 메서드는 부르지 않는다). `projectId == null` 이면 `PROJECT_ACCESS_DENIED`.
- 트랜잭션 : 서비스 메서드마다 `@Transactional(readOnly = true)` — 실행 경계의 읽기 전용 트랜잭션(30초)이 있으면 합류. 격리 수준·잠금 변경 없음.

## 14. 상속 계산 공통화 · AuthUtil 분리

- 예전 : `selectInheritance`·`selectEffectiveAttributes` 가 계산 중에 `editable(entity, approvedBelow)` → `AuthUtil.isApprovalAvailable()` (SecurityContext 인증) 를 불러 로그인 세션 없는 호출(MCP·비동기)에서 쓸 수 없었다.
- 지금 : 계산은 private `inheritance(projectId, entityId, Editability)` 하나. 화면 `selectInheritance(AccountDto, …)` 는 `screenEditable` (예전과 같은 규칙 : 미승인 또는 승인 권한자 + 승인된 후손 없음 — 승인된 후손 조회도 화면 경로에서만) 을 넘기고, `selectInheritanceView(projectId, …)` 는 editable 을 계산하지 않는다 (모두 false — AI DTO 는 이 칸을 쓰지 않는다). 가짜 로그인·임시 SecurityContext 없음, 예외 무시 없음.
- 상속 의미 변경 없음 : 유효 속성(루트 → 자기, 엔터티 안은 속성 순서, depth, 원본 속성 ID), 유효 주 식별자 = 루트의 주 식별자(복합 순서 = 식별자 순서), 조상 보조 식별자 상속, 자체 보조 식별자, 상속 관계(복제 없음, SOURCE·TARGET·SELF) — 같은 private 정적 계산을 그대로 쓴다.
- 화면 호환 : REST 응답 필드·의미·권한 변경 없음. `LogicalMetadataServiceIntegrationTest.screenCompatibility` 가 관리자·일반 사용자·승인된 후손에서 editable 이 예전 규칙대로인지, editable 을 뺀 나머지가 공통 경로와 같은지(엔터티 6종) 확인한다. 기존 `LogicalSubtypeIntegrationTest`(editable 포함 상속 단언)·화면 API 테스트는 전체 회귀에서 통과 (17장).
- 동작 차이 1개 (의도) : 계층을 읽은 뒤 상속 사슬 엔터티나 부모 그룹이 다른 트랜잭션에서 지워져 같은 조회의 다음 쿼리에서 없으면, 예전에는 NullPointerException·NoSuchElementException (500) 이었고 지금은 `MODEL_CONFLICT` "조회하는 동안 논리 모델의 상속 구조가 바뀌었습니다. 다시 조회해 주세요." (화면·AI·MCP 공통). 정상 데이터에서는 일어나지 않는다.

## 15. 조회 계약 (D35-3 연동 대상)

| 메서드 | 입력 | 출력 | 오류 |
|---|---|---|---|
| `models(projectId, page?, limit?)` | page 0부터, limit 1~20 (기본 10, 범위 밖은 가까운 값 — 기존 AI-4 규칙) | `ModelList(models[modelId, modelName, description, entityCount, relationshipCount, subjectAreaCount, subtypeGroupCount], paging)` — 이름 → ID 순 | 음수 page `AI_QUERY_INVALID` |
| `overview(projectId, ModelRef)` | `modelId` 또는 `modelName` (정확히 → 없으면 대소문자 무시, 2건 이상이면 후보와 함께 거절), 둘 다면 같은 모델 | `ModelOverview` : 수, 미분류 수, `subjectAreas[]` (트리 순서, ≤100 + `subjectAreasTruncated`·`subjectAreaCount`), `subtypeGroups[]` (기존 그룹 목록 순서, ≤100 + 전체 수) | 지정 없음·ID/이름 불일치·동명 다수 `AI_QUERY_INVALID`, 없음·다른 프로젝트 `LOGICAL_MODEL_IS_NOT_DEFINED` |
| `searchEntities(projectId, EntitySearch)` | 모델, `keyword?` (이름·설명, ≤100자 — 기존 규칙), `subjectAreaId?`, `includeChildren?` (기본 true), `unclassifiedOnly?`, `page?`, `limit?` | `EntityList(modelId, modelName, scope(검색 조건 되돌림), entities[EntityItem], paging)` — 엔터티명 → ID 순 (기존 목록 기본 정렬) | 영역 + 미분류 `INVALID_LOGICAL_MODEL` (기존 규칙), 다른 모델·프로젝트 영역 `LOGICAL_MODEL_IS_NOT_DEFINED`, 음수 page `AI_QUERY_INVALID` |
| `entityDetail(projectId, EntityRef)` | `entityId` 또는 모델 + `entityName` (정확히 → 대소문자 무시). 함께 준 모델·이름은 같은 대상이어야 | `EntityDetail` : 기본 정보·소속 영역 경로·`inheritance`(참여·단계·루트·조상·부모 그룹(분류 값)·슈퍼타입 그룹과 구성원 ≤100·후손 수·루트 PK 없음)·`attributes[]` (유효 속성 ≤100 + `attributeCount`·`attributesTruncated`)·`identifiers[]` (전부)·`relationships[]` (직접 → 상속, ≤50 + 전체 수) | 지정 부족·불일치 `AI_QUERY_INVALID`, 없음·다른 프로젝트 `LOGICAL_MODEL_IS_NOT_DEFINED`, 조회 중 상속 구조 변경 `MODEL_CONFLICT` |

- 오류는 `CustomException` 그대로 — `MetadataToolset` 이 `_NOT_DEFINED` → NOT_FOUND, `AI_QUERY_INVALID` → INVALID_ARGUMENT, 그 밖 → FAILED 로 바꿔 사유와 함께 모델에게 준다 (기존 경로). 다른 프로젝트 대상은 '없음' 과 같은 오류라 존재를 드러내지 않는다.
- DTO 원칙 : editable·수정 일시·정렬 순서·ERD 좌표·세션·계정·DB 접속 정보 없음. 없는 값은 칸 생략(NON_NULL), 목록은 항상 있음. 설명은 기존 `MetadataTools.text` (300자 + "…"). 상한으로 자른 목록은 전체 수와 `…Truncated` — 일부를 전체처럼 보이지 않는다.
- 속성 필드 : 논리 속성에는 길이·정밀도·자체 승인 상태가 없다 — `dataType`(속성에 적은 값), `termId`·`termName`·`domainName`(표준 용어·도메인 참조), `approved` = 정의 엔터티의 승인. 도메인 데이터 형식(`domainDataType`)은 공통 계산 결과(`EffectiveAttribute`)에 없어 넣지 않았다 (넣으려면 화면 DTO 칸 추가가 필요 — 하지 않음).
- 관계 : `source` = 부모(출발), `target` = 자식(도착), min·max 저장값 그대로, `cardinality` 요약. 상속 관계는 `inherited=true`·`inheritedFromEntity*`·`role`, 속성 짝은 직접 관계에만 (`pairStatus`·`attributePairs`). AI 추정 관계는 만들지 않는다 (논리 관계에는 저장된 사용자 관계만 있다).
- 근거 식별 정보 (D35-D14) : 모델 `modelId`, 주제 영역 `subjectAreaId`, 엔터티 `entityId`, 속성 `attributeId`(원본 ID — 상속 속성도 같은 ID), 식별자 `identifierId`, 관계 `relationshipId`, 그룹 `groupId`. 모두 DB 기본 키, 별도 저장 없음.

### 15.1 JSON 예시 (테스트 실제 직렬화 — `LogicalMetadataServiceIntegrationTest.serialization` 의 표준 출력, ID 는 테스트 실행마다 다름)

모델 목록 :
```json
{"models":[{"modelId":409,"modelName":"d35-json-…","entityCount":6,"relationshipCount":1,"subjectAreaCount":4,"subtypeGroupCount":2}],
 "paging":{"page":0,"limit":10,"returnedCount":1,"totalCount":1,"hasMore":false}}
```
모델 개요 (영역 3단계 + 최상위 1, 그룹 2 — 분류 속성이 없는 그룹은 칸 생략) :
```json
{"modelId":409,"modelName":"d35-json-…","entityCount":6,"relationshipCount":1,"unclassifiedEntityCount":1,"subjectAreaCount":4,
 "subjectAreas":[
  {"subjectAreaId":44,"name":"고객 관리","description":"고객 관리 영역","depth":1,"path":["고객 관리"],"directEntityCount":2,"totalEntityCount":4},
  {"subjectAreaId":45,"name":"개인 고객 관리","description":"개인 고객 관리 영역","parentId":44,"depth":2,"path":["고객 관리","개인 고객 관리"],"directEntityCount":1,"totalEntityCount":2},
  {"subjectAreaId":46,"name":"우수 고객","description":"우수 고객 영역","parentId":45,"depth":3,"path":["고객 관리","개인 고객 관리","우수 고객"],"directEntityCount":1,"totalEntityCount":1},
  {"subjectAreaId":47,"name":"주문 관리","description":"주문 관리 영역","depth":1,"path":["주문 관리"],"directEntityCount":1,"totalEntityCount":1}],
 "subjectAreasTruncated":false,"subtypeGroupCount":2,
 "subtypeGroups":[
  {"groupId":203,"name":"고객 유형","supertypeEntityId":5402,"supertypeEntityName":"고객","subtypeCount":2,"overlapping":false,"total":false},
  {"groupId":202,"name":"당사자 구분","supertypeEntityId":5401,"supertypeEntityName":"당사자","subtypeCount":2,"overlapping":false,"total":true,"classificationAttributeId":51713,"classificationAttributeName":"당사자구분"}],
 "subtypeGroupsTruncated":false}
```
주제 영역이 없는 모델은 `"subjectAreas":[]`·`"unclassifiedEntityCount"` = 전체, 상속이 없으면 `"subtypeGroups":[]` (테스트 `overview`).

엔터티 검색 (검색어 '고객' + 영역 '고객 관리' 하위 포함, 2건씩) :
```json
{"modelId":409,"modelName":"d35-json-…","scope":{"keyword":"고객","subjectAreaId":44,"includeChildren":true,"unclassifiedOnly":false},
 "entities":[
  {"entityId":5404,"entityName":"개인고객","approved":false,"subjectArea":{"subjectAreaId":45,"name":"개인 고객 관리","path":["고객 관리","개인 고객 관리"]},
   "directAttributeCount":1,"relationshipCount":0,
   "inheritance":{"participates":true,"level":3,"supertype":{"entityId":5402,"entityName":"고객"},"parentGroupId":203,"parentGroupName":"고객 유형","subtypeGroups":[]}},
  {"entityId":5402,"entityName":"고객","approved":false,"subjectArea":{"subjectAreaId":44,"name":"고객 관리","path":["고객 관리"]},
   "directAttributeCount":2,"relationshipCount":1,
   "inheritance":{"participates":true,"level":2,"supertype":{"entityId":5401,"entityName":"당사자"},"parentGroupId":202,"parentGroupName":"당사자 구분",
     "subtypeGroups":[{"groupId":203,"name":"고객 유형","subtypeCount":2,"subtypes":[{"entityId":5404,"entityName":"개인고객"},{"entityId":5405,"entityName":"법인고객"}],"subtypesTruncated":false}]}}],
 "paging":{"page":0,"limit":2,"returnedCount":2,"totalCount":3,"hasMore":true,"nextPage":1}}
```
상속 없는 엔터티의 요약 : `"inheritance":{"participates":false,"level":1,"subtypeGroups":[]}`.

엔터티 상세 — 3단계 서브타입 (복합 주 식별자 상속, 직접 + 상속 속성, 상속 보조 식별자, 상속 관계) :
```json
{"entityId":5404,"modelId":409,"modelName":"d35-json-…","entityName":"개인고객","approved":false,
 "subjectArea":{"subjectAreaId":45,"name":"개인 고객 관리","path":["고객 관리","개인 고객 관리"]},
 "inheritance":{"participates":true,"level":3,"root":{"entityId":5401,"entityName":"당사자"},
   "ancestors":[{"entityId":5401,"entityName":"당사자","approved":true,"level":1},{"entityId":5402,"entityName":"고객","approved":false,"level":2}],
   "parentGroup":{"groupId":203,"name":"고객 유형","supertypeEntityId":5402,"supertypeEntityName":"고객","overlapping":false,"total":false},
   "subtypeGroups":[],"descendantCount":0,"rootPrimaryKeyMissing":false},
 "attributeCount":6,
 "attributes":[
  {"attributeId":51712,"name":"당사자번호","definedEntityId":5401,"definedEntityName":"당사자","inherited":true,"depth":2,"approved":true,"dataType":"문자","required":true,"primaryKeyOrder":2},
  {"attributeId":51713,"name":"당사자구분","definedEntityId":5401,"definedEntityName":"당사자","inherited":true,"depth":2,"approved":true,"dataType":"문자","required":true,"primaryKeyOrder":1},
  {"attributeId":51714,"name":"등록일자","definedEntityId":5401,"definedEntityName":"당사자","inherited":true,"depth":2,"approved":true,"dataType":"문자","required":false},
  {"attributeId":51715,"name":"고객등급","definedEntityId":5402,"definedEntityName":"고객","inherited":true,"depth":1,"approved":false,"dataType":"문자","required":false},
  {"attributeId":51716,"name":"고객이메일","definedEntityId":5402,"definedEntityName":"고객","inherited":true,"depth":1,"approved":false,"dataType":"문자","required":false},
  {"attributeId":51718,"name":"생년월일","definedEntityId":5404,"definedEntityName":"개인고객","inherited":false,"depth":0,"approved":false,"dataType":"문자","required":false}],
 "attributesTruncated":false,
 "identifiers":[
  {"identifierId":4998,"name":"당사자 PK","kind":"INHERITED_PRIMARY","primary":true,"inherited":true,"definedEntityId":5401,"definedEntityName":"당사자",
   "attributes":[{"attributeId":51713,"name":"당사자구분","order":1},{"attributeId":51712,"name":"당사자번호","order":2}]},
  {"identifierId":5000,"name":"고객 이메일 AK","kind":"INHERITED_ALTERNATE","primary":false,"inherited":true,"definedEntityId":5402,"definedEntityName":"고객",
   "attributes":[{"attributeId":51716,"name":"고객이메일","order":1}]}],
 "relationshipCount":1,
 "relationships":[{"relationshipId":5700,"name":"고객 주문","sourceEntityId":5402,"sourceEntityName":"고객","targetEntityId":5406,"targetEntityName":"주문",
   "identifying":false,"cardinality":"1:N","sourceMin":1,"sourceMax":1,"targetMin":0,"approved":false,"inherited":true,"inheritedFromEntityId":5402,"inheritedFromEntityName":"고객","role":"SOURCE"}],
 "relationshipsTruncated":false}
```
상속 없는 엔터티 상세 (발췌) : `"inheritance":{"participates":false,"level":1,"root":{"entityId":5406,"entityName":"주문"},"ancestors":[],"subtypeGroups":[],"descendantCount":0,"rootPrimaryKeyMissing":false}`, 식별자 `"kind":"PRIMARY"`, 직접 관계에는 `"pairStatus":"COMPLETE","attributePairs":[…]`, 400자 설명은 300자 + "…".

## 16. 권한 · 격리 · 승인 · 일관성

- 권한 : 이번 단계는 Tool·API 를 공개하지 않는다 (컨트롤러·Tool 등록 없음). D35-3 에서 `MetadataToolset.run` 경계(호출마다 `ExecutionContextVerifier` VIEWER, 실행 컨텍스트·토큰 프로젝트만, 읽기 전용 트랜잭션) 안에서 부른다. MCP 토큰·실행 컨텍스트 구조 변경 없음.
- 격리 (테스트) : 다른 프로젝트의 모델 ID·모델명·엔터티 ID·주제 영역 ID, 같은 프로젝트 다른 모델의 영역, 다른 프로젝트 그룹 ID(`selectGroups` 가 빼고, 다른 프로젝트 모델이면 '없음') → 모두 '없음' 또는 거절. 상속 정의 엔터티·관계는 모델 안에서만 읽힌다 (`graph(modelId, …)`, 관계는 엔터티 기준).
- 세션 없는 실행 (테스트) : SecurityContext 를 비운 뒤 승인된 루트의 상속 정보·상세·목록·개요·모델 목록 조회 성공.
- 승인 (D35-D13) : 엔터티·조상·그룹 구성원·관계마다 `approved`, 속성은 정의 엔터티의 승인 (테스트 : 루트만 승인 → 상속 속성 approved=true, 중간·자기 속성 false). 주제 영역·그룹에는 승인 상태가 없다.
- 읽기 일관성 (D35-D12) : 기본 READ COMMITTED, 한 호출 안의 쿼리들이 서로 다른 커밋을 볼 수 있다 (화면 조회와 같다). 확인한 위험 지점 = 상속 계산의 '계층 → 사슬 엔터티 → 부모 그룹' 사이 삭제 → 14장의 `MODEL_CONFLICT` 로 처리 (판정 단위 테스트 `LogicalSubtypeReadConsistencyTest`). 두 쿼리 사이에 다른 커밋을 끼워 넣는 실제 경합은 재현하지 않았다. 주제 영역 이동·그룹 구성 변경·슈퍼타입 속성 변경·식별자 구성 변경은 조회 결과의 시점이 섞일 수 있을 뿐 예외·잘못된 연결을 만들지 않는다 (각 값은 커밋된 행에서 읽는다) — 별도 처리 없음. 잠금·기준값·재시도·격리 수준 변경 없음, M-8 잠금 체계 변경 없음.

## 17. 응답 크기 · 성능 · 테스트 결과

### 17.1 응답 크기 (실측)

측정 = `LogicalMetadataSizePerformanceTest` (격리 데이터·테스트 DB 의 V1 예제 프로젝트 0 읽기만 — 운영 데이터 없음). 크기 = 기본 ObjectMapper 직렬화 문자 수 / UTF-8 바이트 (결과 상한 24,000 은 **문자 수** 기준), 추정 토큰 = 기존 `TokenEstimator` (ASCII ÷ 3 + 그 밖의 문자 1개당 1 — 보수적 추정, 실측 아님). PostgreSQL 18.6 기준 (H2 도 ±1% 안).

| 대상 | 문자 | UTF-8 바이트 | 추정 토큰 | 상한 |
|---|---|---|---|---|
| 예제 : 모델 목록 | 255 | 283 | 95 | 안 |
| 예제 : 모델 개요 (영역·그룹 0) | 289 | 317 | 106 | 안 |
| 예제 : 엔터티 목록 20건 | 4,424 | 5,145 | 1,716 | 안 |
| 예제 : 관계·속성이 가장 많은 엔터티 상세 (관계 11, 속성 32) | 12,069 | 14,063 | 4,688 | 안 |
| 대규모 : 모델 개요 (영역 130 → 100 표시, 설명 300자) | 16,435 | 21,127 | 7,043 | 안 |
| 대규모 : 엔터티 목록 20건 (긴 설명 300자) | 10,894 | 18,208 | 6,070 | 안 |
| 대규모 : 영역 엔터티 목록 20건 (하위 포함) | 10,540 | 18,172 | 6,058 | 안 |
| 대규모 : 미분류 엔터티 목록 | 7,738 | 13,196 | 4,399 | 안 |
| 대규모 : 상속 없는 엔터티 상세 | 1,626 | 2,116 | 706 | 안 |
| 대규모 : 단일 상속 상세 (유효 속성 41, 루트 속성 설명 300자) | 20,731 | 37,737 | 12,579 | 안 (근접) |
| 대규모 : 루트 슈퍼타입 상세 (복합 PK, 속성 40 × 설명 300자) | 21,137 | 38,545 | 12,849 | 안 (근접) |
| 대규모 : **3단계 말단 상세 (유효 속성 105 → 100 표시, 설명 300자)** | **49,936** | 92,644 | 30,882 | **초과** |
| 대규모 : 관계 60개 엔터티 상세 (→ 50 표시) | 15,230 | 16,154 | 5,385 | 안 |

- D35-1 비교 : D35-1 의 화면 상속 정보 응답(3,694자, 같은 구성의 테스트 모델 개인고객)과 같은 내용에 관계·영역을 더한 AI 상세 예시(15.1)가 약 3,200자로 더 작다 — editable·수정 일시·정렬 순서·contextEntityId·termId 등 화면 칸을 뺐다. 논리 ERD 전체(272,633자)와는 다른 대상이다.
- **상한 초과 원인** : 속성 설명(최대 300자)이 유효 속성마다 붙는다. 속성 100개 × 설명 300자 ≈ 30,000자 + 칸 이름. 중복 칸(정의 엔터티명 등) 제거로는 해결되지 않는다 (설명이 60% 이상). 지금 계약에서는 실행 경계가 `RESULT_TOO_LARGE`(대상 범위를 줄이라는 오류)를 돌려준다 — 일부를 잘라 전체처럼 보이게 하지 않는다. 상세 조회 계약 변경이 필요한 결정 사항 (18.2 ①).

### 17.2 조회 수 · 시간 (실측, PostgreSQL 18.6, 데운 뒤 5회 중앙값)

| 대상 | SQL 수 | 중앙값 |
|---|---|---|
| 모델 목록 | 5 | 0.9ms |
| 모델 개요 | 12 (예제 11) | 2.4ms |
| 엔터티 목록 20건 / 5건 (영역 범위) | 17 / 17 | 4.0 / 3.5ms |
| 엔터티 목록 모델 전체 20건 / 5건 | 17 / 13 (첫 페이지에 소속 영역이 있는 행이 없으면 영역 트리 4번을 건너뛴다) | 3.8 / 2.5ms |
| 상세 : 상속 없음 · 단일 · 루트 · 3단계 · 관계 60 · 짝 관계 | 20 · 15 · 18 · 15 · 16 · 20 | 4.3~4.7ms |

- 조회 수는 페이지 크기·모델 크기·상속 깊이·속성 수·관계 수와 무관하다 (테스트 단언 : 같은 범위의 5건·20건 같음, 상세 ≤ 25). 엔터티마다 상세를 반복하지 않고(목록 상속 요약 = 계층 ID 투영 + 그룹 묶음 조회), 식별자 구성은 fetch join, 그룹 구성원은 묶음 조회 — N+1 없음. 같은 조상을 여러 번 계산하지 않는다 (한 요청에서 계층 1번).
- 첫 호출(데우기 전)은 1~5ms 더 걸리고 (JIT·쿼리 계획), 직렬화는 0.01~0.2ms. H2 도 같은 SQL 수, 시간은 2~6ms.
- 캐시·외부 저장소 없음.

### 17.3 테스트

| 테스트 | 내용 |
|---|---|
| `LogicalMetadataServiceIntegrationTest` (10) | 모델 목록(격리·수·페이지·잘못된 page) · 개요(영역 3단계 경로·직접/하위 수·미분류·그룹, 영역·상속 없는 모델, ID·이름·대소문자·불일치·다른 프로젝트) · 검색(전체·영역·직접만·미분류·검색어+영역·모순 조건·다른 모델/프로젝트 영역·상속 요약·경로) · 페이지(결정적 정렬·경계·전체 수·limit 20·음수 page) · 3단계 상세(유효 속성 정의 엔터티·깊이·승인·복합 PK 순서·상속 보조 식별자·상속 관계, 화면과 같은 순서) · 그 밖의 상세(루트·중간·자체 보조 식별자·상속 없음·직접 관계 짝·미분류) · 엔터티 지정(ID·이름·불일치·다른 프로젝트·그룹 격리) · 세션 없는 조회 · 화면 호환(editable 관리자/일반 사용자/승인된 후손, editable 외 공통 경로와 같음) · 직렬화(화면 칸·계정 칸 없음, 빈 목록, 설명 자르기) |
| `LogicalMetadataSizePerformanceTest` (2) | 대규모 격리 모델·예제 모델 크기·SQL 수·시간 (17.1·17.2) |
| `LogicalSubtypeReadConsistencyTest` (1, lexorma-data 단위) | 사슬 엔터티 누락 판정 → `MODEL_CONFLICT` |

전체 회귀 (격리 사본 — 저장소 dist·개발 DB 미사용) :

| 실행 | 결과 |
|---|---|
| H2 `./gradlew build --continue --rerun-tasks` (npm ci · `tsc -b` · vite build · 모든 모듈 테스트 · bootJar) | 1,561 건, 실패 0, 건너뜀 123, BUILD SUCCESSFUL (4분 52초) |
| PostgreSQL 18.6 새 빈 DB 1차 전체 | 1,561 건, 실패 1 (신규 측정 테스트의 조회 수 단언 — 한글 정렬 차이로 모델 전체 첫 페이지에 소속 영역 행이 없어 4번 적었다. 단언을 같은 범위 비교로 고침. 기존 테스트 실패 없음) |
| H2 수정한 D35 테스트 클래스 재실행 | 실패 0 |
| PostgreSQL 18.6 새 빈 DB 2차 전체 (최종 코드) | **1,561 건, 실패 0, 건너뜀 46** |

- 기준선 M8-FINAL 1,548 건 → 1,561 건 (+13 = 신규 10 + 2 + 1, 다른 변화 없음). 건너뜀 수 기준선과 같음 (H2 123, PostgreSQL 46). M-8 간헐 실패 재발 없음 (두 PostgreSQL 실행 모두).
- 단일 JAR (위 H2 빌드 산출물, 임시 H2·포트 19962) : 기동·최초 설정·회원 가입 + 역할 USER 지정·로그인. HTTP : 상속 정보·유효 속성 응답 칸 구성 그대로, editable — 관리자 승인된 루트 직접 속성 true·일반 사용자 false·미승인 엔터티 직접 속성 true, 3단계 상속 속성·복합 PK 순서·상속 보조 식별자·상속 관계, 주제 영역 트리·영역/미분류 목록, ERD 범위 조회 그룹 2. 브라우저(Playwright 1.62.1 + Chromium, 로컬) : 주제 영역 트리·영역 목록, 개인고객 상세의 속성(1 + 상속 5)·식별자(상속 주/보조)·상속 탭(경로 당사자 → 고객 → 개인고객), 승인된 엔터티 기본 탭 읽기 전용(관리자·일반 사용자), 논리 ERD 노드 8, 페이지 오류 없음. 새 AI 화면은 없다.

## 18. D35-3 연동 지점 · 남은 결정

### 18.1 D35-3 연동 지점

- 내부 AI : `MetadataTools.callbacks()` 에 세 Tool 을 더하고(`MetadataToolset.NAMES`·`MetadataToolIntegrationTest` 허용 목록 고정 함께), Tool 함수는 `LogicalMetadataService` 를 `scope(toolContext).getProjectId()` 로 부른다. 입력 record 는 `LogicalMetadataDto.ModelRef`·`EntitySearch`·`EntityRef` 를 `@ToolParam` 설명과 함께 Tool 입력으로 쓰거나 같은 칸의 Tool 전용 record 로 감싼다. 근거 기록 `saw(…)` 는 근거 유형 추가 뒤 (D35-D14).
- MCP : `McpServerService.SHARED_TOOLS` 에 세 이름을 더하면 `MetadataToolset.run` 으로 같은 구현을 쓴다 (MCP 전용 구현 없음). `getLogicalModel` 은 D35-D09 보완만 — 응답 계약 유지.
- 근거 (D35-D14) : `EvidenceType` 과 `ai/metadata-answer.schema.json` enum 에 논리 유형(예 : LOGICAL_MODEL·SUBJECT_AREA·ENTITY·ATTRIBUTE·IDENTIFIER·LOGICAL_RELATIONSHIP·SUBTYPE_GROUP), `EvidenceCatalog` 에 그 행의 `MDFCN_DT` 버전 조회. 이번 DTO 는 그 ID 를 모두 준다.
- 시스템 프롬프트·Tool 설명 : 직접 정의/상속 구분, `…Truncated` 와 전체 수, RESULT_TOO_LARGE 때 범위를 줄이는 방법, 없는 정보를 만들지 않기.
- 결과 크기 검증 방식 (D35-3·FINAL) : 실행 경계의 실제 Tool 결과 문자열 길이(`MetadataToolset` 이 재는 값)로 같은 시나리오를 다시 잰다 — Spring AI 결과 변환기의 직렬화가 기본 ObjectMapper 와 같은지 함께 확인.

### 18.2 사용자 결정 필요

1. **유효 속성이 많은 엔터티 상세의 상한 초과** (17.1 — 속성 100 × 설명 300자 ≈ 50,000자, 단일 상속 41개도 20,700자로 근접) : 지금은 RESULT_TOO_LARGE 로 끝나 AI 가 그 엔터티 상세를 볼 수 없다. 방안 — (a) 상세에 속성 페이지 입력(`attributePage`) 추가, (b) 상세 기본은 속성 설명 없이, 설명은 선택 입력(`includeDescriptions`)일 때만, (c) 상세 속성 상한을 100 보다 낮게 (새 상한값), (d) 지금대로 두고 Tool 설명으로 안내. **추천 (b)+(a)** : 설명 없이 100개 ≈ 15,000자로 상한 안, 설명이 필요하면 페이지로 나눠 받는다 — 둘 다 새 입력이라 계약 결정이 필요하다.
2. **개요의 영역·그룹 표시 상한 100** 과 **목록 상속 요약의 직접 서브타입 20** : 기존 상한 값(테이블당 컬럼 100, 목록 20)을 재사용했다. 새 값은 만들지 않았지만 '하위 항목' 에 이 값을 쓰는 것을 확인받고 싶다.
3. (확인) 화면 상속 정보 조회의 '조회 중 상속 구조 변경' 이 500 대신 409 `MODEL_CONFLICT` 로 바뀐 것 (14장) — 정상 데이터에서는 일어나지 않는 경우다.

### 18.3 남은 위험

- 정렬 : 이름순은 DB 정렬 규칙을 따른다 (H2 와 PostgreSQL 의 한글 순서가 다를 수 있다 — 같은 DB 안에서는 결정적, 동명은 없음, 보조 정렬 ID).
- 페이지는 호출마다 따로 읽는다 — 페이지 사이에 편집이 있으면 겹치거나 빠질 수 있다 (스냅숏 아님, D35-D15).
- 읽기 일관성의 실제 경합(두 쿼리 사이 다른 커밋)은 재현하지 않았다 (판정만 단위 테스트).
- 크기는 기본 ObjectMapper 기준 — Tool 결과 변환기 기준 측정은 D35-3.

---

## 19. 사용자 확정 정책 (D35-D16~D22, 2026-11-24) 과 반영

| ID | 확정 내용 | 반영 |
|---|---|---|
| D16 | 유효 속성 설명은 기본 제외, 선택 입력으로 포함. 제외와 미등록을 구분, 엔터티 설명은 그대로 | `includeAttributeDescriptions` (ALL·ATTRIBUTES 만), 응답 `attributeDescriptions` = INCLUDED·EXCLUDED, 속성마다 `hasDescription` |
| D17 | `getLogicalEntityDetail` 에 조회 항목 선택 (새 Tool 없음) | `section` = ALL·ATTRIBUTES·IDENTIFIERS·RELATIONS·INHERITANCE (+ INHERITANCE 의 `subtypeGroupId`) |
| D18 | 상한 유지 (모델·엔터티 목록 20, 유효 속성 100, 직접 서브타입 20, 관계 50) — 나머지도 받을 수 있어야 함 | 목록마다 `Paging.next` (정확한 다음 호출). 식별자·영역·그룹의 한 번 최대는 기존 100 |
| D19 | 최종 Tool 결과 기준 24,000자, 내부 AI·MCP 각각 확인, 초과 시 RESULT_TOO_LARGE + 대체 안내 | 실행 경계가 결과 문자열 길이로 판정(변경 없음), 오류에 `tool`·`resultChars`·`maxChars`·`hint` 추가 (23장) |
| D20 | 조회 중 구성 불일치 409 유지 | 14장 그대로 |
| D21 | 전체·페이지·상한에 따른 일부·선택적 제외·데이터 없음 구분 | `Paging.complete`·`hasMore`·`next`, 조회하지 않은 항목은 칸 없음 / 없으면 `[]`, `attributeDescriptions` |
| D22 | 호출 상한 유지, 서버가 다음 페이지를 이어 읽지 않음 | 서버는 한 호출에 한 페이지만. 이어 읽기는 모델이 `next` 로 (질의당 8번 안) |

## 20. D35-3a 조회 계약 (LogicalMetadataService · LogicalMetadataDto)

**공통 페이지 표현** `Paging(page, limit, returnedCount, totalCount, hasMore, nextPage, complete, next)` :
`complete` = 이 응답에 그 목록 전체가 있음 (첫 페이지이고 반환 수 = 전체 수), `hasMore`·`nextPage` = 같은 조건·같은 limit 의 다음 페이지,
`next` = 상세·개요 목록의 정확한 다음 호출 `NextCall(tool, modelId?, entityId?, section, subtypeGroupId?, includeAttributeDescriptions?, page, limit)` — ALL 이 100개를 주면
next 는 `section=ATTRIBUTES, page=1, limit=100` 이라 101번째부터 이어진다 (limit 이 달라 건너뛰는 일이 없다). 엔터티 목록은 같은 조건 + `nextPage`.

**엔터티 상세 `entityDetail(projectId, EntityRef)`**

| section | 주는 것 | page·limit | 기본 limit / 최대 |
|---|---|---|---|
| ALL (기본) | 기본 정보·소속 영역·상속 구조·유효 속성·유효 식별자·관계, 목록마다 첫 묶음 + next | 받지 않음 (주면 400) | 속성·식별자 100, 관계 50, 그룹·직접 서브타입 20 |
| ATTRIBUTES | 유효 속성 (루트 → 자기, 속성 순서 그대로) | 속성 목록 | 20 / 100 |
| IDENTIFIERS | 유효 식별자 (주 → 상속 먼저, 구성 속성은 나누지 않음) | 식별자 목록 | 20 / 100 |
| RELATIONS | 직접(관계 ID 순) → 상속(관계 ID 순) | 관계 목록 | 20 / 50 |
| INHERITANCE | 상속 구조 + 이 엔터티가 슈퍼타입인 그룹 | 그룹 목록 (각 그룹 직접 서브타입은 첫 20 + next) | 20 / 20 |
| INHERITANCE + subtypeGroupId | 그 그룹 하나의 직접 서브타입 | 서브타입 목록 | 20 / 20 |

- 항목 조회는 그 항목 칸만 있다 (`section` 으로 되돌려 준다). 기본 정보(엔터티 ID·이름·모델·승인)는 늘 있고, 소속 영역·엔터티 설명은 ALL 에서만.
- 입력 검증 (`AI_QUERY_INVALID`) : 모르는 section (대소문자 무시), ALL 과 page·limit, ALL·ATTRIBUTES 밖의 `includeAttributeDescriptions=true`, INHERITANCE 밖의 `subtypeGroupId`, 음수 page,
  page × limit 이 int 를 넘는 page. limit 은 기존 AI-4 규칙대로 범위 밖이면 가까운 값 (거절하지 않는다). 이 엔터티가 슈퍼타입이 아닌 그룹 ID 는 '없음'.
- 속성 설명 : 기본 제외 (`attributeDescriptions: "EXCLUDED"`, 속성마다 `hasDescription`), 포함하면 300자 + "…" (기존 `MetadataTools.text`). 화면 속성 조회는 변경 없음.

**모델 개요 `overview(projectId, OverviewRequest)`** : section = ALL(기본 — 영역·그룹 각 첫 100 + next) · SUBJECT_AREAS · SUBTYPE_GROUPS (그 목록만, 기본 20 / 최대 100). 영역을 페이지로 받아도
`parentId`·`path` 로 계층을 알 수 있고, 페이지 결과는 `complete=false` 라 전체 트리로 보이지 않는다. 그룹 페이지는 기존 그룹 목록 조회의 서버 페이지.

**엔터티 목록** : 변경 없음 (최대 20). 목록 상속 요약의 그룹·직접 서브타입은 각 20 + `subtypesPaging.next` (→ 상세 INHERITANCE + subtypeGroupId).

## 21. 응답 크기 (D35-3a 재측정 · 실제 Tool 결과)

측정 조건은 17장과 같다 (격리 데이터, 문자 수 = 결과 상한 기준, 추정 토큰 = `TokenEstimator`). H2 실행 값 (PostgreSQL 도 ±1% 안 — 전체 회귀 실행의 측정 줄).

| 대상 | D35-2 | D35-3 | 비고 |
|---|---|---|---|
| 3단계 말단 상세 ALL (유효 속성 105 → 100) | 49,936 (상한 초과) | **20,835** (설명 제외) | 상한 안 |
| 같은 상세 ALL + 설명 포함 | — | 52,671 | **상한 초과 → RESULT_TOO_LARGE (420자, 안내 포함)** |
| ATTRIBUTES + 설명 limit 20 | — | 10,565 | 6페이지로 105개 전부 (테스트로 확인) |
| ATTRIBUTES 설명 제외 limit 100 | — | 19,142 | |
| ATTRIBUTES + 설명 limit 100 | — | 50,978 | 초과 → limit 20 안내 |
| 단일 상속 상세 (유효 속성 41, 루트 설명 300자) | 20,731 | 9,297 | |
| 루트 상세 (속성 40 × 설명 300자) | 21,133 | 9,741 | |
| 관계 60 엔터티 ALL (→ 50) | 15,228 | 15,750 | next 로 나머지 10 |
| RELATIONS limit 50 | — | 15,007 | |
| IDENTIFIERS | — | 508 | |
| 직접 서브타입 25 : ALL / INHERITANCE 그룹 하나 / 목록 요약 | — | 2,274 / 1,871 / 1,463 | 20 + next |
| 개요 ALL (영역 130 → 100, 일부 설명 300자) | 16,435 | 16,836 | next 로 나머지 30 |
| 개요 SUBJECT_AREAS limit 100 | — | 16,312 | |
| 엔터티 목록 20건 (긴 설명) | 10,894 | 6,478~10,555 | 페이지 구성에 따라 |

- 항목별 기여 (3단계 말단, 설명 포함 52,671자 기준) : 속성 설명 약 31,800자(60%), 속성 나머지 칸(ID·이름·정의 엔터티·깊이·승인·유형·필수) 약 19,000자, 상속 구조·식별자·관계·기본 정보 합계 약 1,800자. 정의 엔터티 이름(`"definedEntityName":"…"`, 속성마다 약 27자)을 빼도 약 2,700자(5%)라 설명 제외가 가장 큰 효과였다 (이름은 근거 확인에 필요해 남겼다 — 추정치).
- **실제 Tool 결과 = 공통 DTO 직렬화** : 내부 AI(`MetadataToolset.run` → Spring AI `FunctionToolCallback` 결과 변환)의 결과 문자열과 DTO 직렬화가 같은 JSON (테스트 `sameResultAsService` — 6가지, 문자 수 동일). MCP 의 안쪽 결과(`content[0].text`)도 같은 문자열 (`McpLogicalToolIntegrationTest`).
  결과 상한은 두 경로 모두 **이 안쪽 결과 문자열의 문자 수**로 판정한다 (MCP 공유 Tool 은 실행 경계의 판정을 그대로 받는다). MCP HTTP 본문은 JSON-RPC 감싸기·이스케이프로 더 크다 :

| MCP 호출 | 안쪽 결과 | HTTP 본문 (문자 / UTF-8 바이트) |
|---|---|---|
| 상세 기본 (3단계 서브타입) | 3,088 | 3,607 / 3,863 |
| 상세 기본 (대형 말단, 속성 100) | 20,190 | 23,083 / 25,163 |
| ATTRIBUTES + 설명 limit 20 | 10,564 | 11,315 / 19,763 |
| 엔터티 목록 20건 | 9,627 | 10,314 / 20,784 |
| RESULT_TOO_LARGE (설명 포함 ALL) | 420 | 528 / 528 |
| getLogicalModel RESULT_TOO_LARGE | 377 | 483 / 483 |
| getLogicalModel 상세 (호환 보완, 엔터티 41) | 14,206 | 16,324 / 18,172 |

- 한 항목이 혼자 상한을 넘는 경우는 찾지 못했다 : 설명은 300자로 잘리고(기존), 식별자 하나는 구성 속성 수만큼, 그룹 하나의 직접 서브타입은 페이지로 나뉜다. 상세의 기본 정보(엔터티 설명 300자)는 1,000자 이하.
- 측정하지 않은 조합 : 주제 영역 100개 모두에 설명 300자가 있는 모델의 개요 ALL (추정 약 40,000자 → RESULT_TOO_LARGE, SUBJECT_AREAS limit 20 이면 약 8,000자 — 추정).

## 22. D35-3b 내부 AI · MCP 연동

- **Tool 등록** : `MetadataTools.callbacks()` 에 `getLogicalModelOverview`·`searchLogicalEntities`·`getLogicalEntityDetail` (입력 record — `LogicalOverviewRequest`·`LogicalEntitySearchRequest`·`LogicalEntityDetailRequest`, 프로젝트·계정 칸 없음), `MetadataToolset.NAMES` 10 → 13.
  모두 `LogicalMetadataService` 를 `scope(toolContext).getProjectId()` (실행 컨텍스트) 로 부른다 — 내부 AI·MCP 가 같은 구현. `getLogicalModelOverview` 는 모델을 주지 않으면 모델 목록 (section 을 주면 400).
- **MCP** : `McpServerService.SHARED_TOOLS` 8 → 11 (전체 도구 10 → 13), `MetadataToolset.run` 그대로 (호출마다 VIEWER 재검증·읽기 전용 트랜잭션·결과 상한·근거 경계 동일). 인증·전송·토큰·활성화 정책 변경 없음. 내부 AI 는 MCP 를 거치지 않는다 (MCP 꺼짐 상태에서 내부 Tool 테스트).
- **`getLogicalModel` 호환 보완 (D35-D09)** : 기존 칸·형식·의미 그대로 (목록 / 직접 정의 속성·자체 식별자·관계), 상세에 칸만 더함 — 모델 수준 `attributeScope: "DIRECT"`·`hasInheritance`·`note`(직접 정의만 나열, 서브타입은 상속 속성과 루트 주 식별자를 가지므로 빈 식별자 목록이 '주 식별자 없음'이 아님, `getLogicalEntityDetail` 안내), 상속 참여 엔터티에 `supertype`(직접 슈퍼타입 이름)·`inheritanceLevel`. 상속 정보는 기존 `LogicalSubtypeService.hierarchy` (ID 투영) 로 — 별도 계산 없음. 결과 상한 초과는 예전 오류 형식에 대체 Tool 안내·`alternatives` 를 더했다 (서버가 다른 Tool 을 대신 부르지 않는다). 도구 설명에 직접 정의 범위와 대체 Tool 을 적었다. 결과 최상위 키 순서가 고정 순서(LinkedHashMap)로 바뀌었다 (JSON 의미 동일).
- **결과 상한 오류** (D35-D19) : `{"error":"RESULT_TOO_LARGE","message":…,"tool":…,"resultChars":…,"maxChars":24000,"hint":…}` — 기존 Tool 에도 `tool`·`resultChars`·`maxChars` 가 더해진다 (hint 는 논리 Tool 만). 오류 자체 약 400자.
- **근거 경계 보완** : Tool 이 결과를 돌려주지 못하면(결과 상한 초과·조회 오류) 그 호출이 기록한 근거를 지운다 (`MetadataToolSession.evidenceCheckpoint`/`discardSince`, 기존 Tool 포함). 예전에는 상한 초과로 모델에게 가지 않은 결과의 대상도 근거로 인정될 수 있었다.

## 23. 논리 모델 답변 근거

- `EvidenceType` 에 7개 추가 : `LOGICAL_MODEL`·`SUBJECT_AREA`·`LOGICAL_ENTITY`·`LOGICAL_ATTRIBUTE`·`LOGICAL_IDENTIFIER`·`LOGICAL_RELATIONSHIP`·`SUBTYPE_GROUP` (물리의 `RELATION` kind LOGICAL 과 구분하려고 접두사). 답변 스키마 enum·설명, 구조화 단계 지시, 화면 근거 표시(`AiAssistant` 유형 이름 7개 + Tool 이름 3개) 에 반영. 새 답변 형식·저장 구조·마이그레이션 없음.
- `EvidenceCatalog` : 종류마다 `findAllById` 한 번, 버전 = 행의 `MDFCN_DT`, 이름 = 모델·영역·엔터티·속성·식별자·관계(이름이 없으면 양 끝 엔터티 이름)·그룹 이름. 속성·식별자는 원본 행이라 프로젝트·소속(parentId) = **정의한 엔터티** — 서브타입에서 본 상속 속성·상속 주 식별자도 서브타입 직접 정의로 기록되지 않는다.
- 기록 (`MetadataTools.saw`) : 그 호출의 응답에 실린 대상만 — 모델·영역·엔터티(조회 대상·조상·슈퍼타입·그룹 구성원)·그룹·속성·식별자·관계. 다음 페이지·조회하지 않은 항목·실패한 호출의 대상은 근거가 아니다 (검증 결과 `UNVERIFIABLE`/`NOT_RETRIEVED`).
- 검증 규칙은 기존 그대로 (존재 → 프로젝트 → 이 질의의 Tool 결과에 있었는지 → 버전 → 이름). 우회 없음.
- 한계 (최소 보완 검토 결과) : 근거 기록은 대상 단위라 '속성이 있다' 와 '속성 설명을 읽었다' 를 나누지 못한다. 근거 저장 구조를 바꾸지 않고, 시스템 프롬프트에 '돌려받은 설명으로만 속성 의미를 설명' 규칙을 두었다 — 서버 강제는 아니다 (24장 위험).

## 24. 시스템 프롬프트 · Tool 정의 크기 · 토큰

- Tool 단계 지시에 규칙 8 하나 (논리 모델 범위 추가 + 상속 구분·키 순서·승인은 정의 엔터티·부분 목록 확인·설명 근거·RESULT_TOO_LARGE 안내 따르기). 구조화 단계 지시에 논리 ID 이름과 상속 속성 근거 규칙. 서버 맥락(도구 없는 모델) 흐름은 논리 모델을 조회하지 않는다 (변경 없음).
- **토큰 예산 영향 (측정)** : Tool 정의(이름+설명+입력 스키마)는 요청마다 모델 입력에 들어간다. 내부 AI 10개 8,201자 → 13개 11,348자 (+3,147자, 추정 약 1,050 토큰), 규칙 8 약 300자. 처음 쓴 설명(+5,376자, 규칙 4개)으로는 기존 회귀 테스트(`MetadataToolIntegrationTest.limitsAndUnsupportedModel` — 같은 Tool 반복 시나리오, 컨텍스트 16,384 · 출력 4,096)의 다섯 번째 요청 입력 추정이 13,136 > 12,288 토큰이 되어 호출 상한보다 토큰 예산이 먼저 걸렸다. 설명을 줄여(인자 이름으로 뜻이 분명한 칸의 설명 생략, 규칙 하나로) 테스트가 그대로 통과한다 — 줄인 뒤 직전 측정이 12,290 이었으므로 남은 여유는 추정 약 200 토큰. 토큰 예산 설정·테스트 단언은 바꾸지 않았다.
- MCP `tools/list` 본문 6,692 → 9,756자 (논리 3개 정의 3,447자, 추정 3,261 토큰 전체).

## 25. 권한 · 격리 · 감사 (테스트)

- 내부 AI : VIEWER·USER 멤버·프로젝트 관리자·ADMIN 조회 가능, 비멤버·NOT_APPROVE·멤버십 해제 뒤 호출은 실행 경계에서 거절 (`CustomException` — 질의 전체 종료, 기존 정책). 다른 프로젝트의 모델 ID·이름·엔터티 ID·모델+이름·주제 영역·그룹 → `NOT_FOUND` 결과, 근거 기록 없음. 잘못된 입력 → `INVALID_ARGUMENT`. 질의당 8번 상한 (9번째 `AI_TOOL_CALL_LIMIT_EXCEEDED`). 로그인 세션 없이 실행 (실행 컨텍스트만).
- MCP : 토큰 프로젝트만, 잘못된 입력·다른 프로젝트는 `isError` (내용 없음), 프로젝트 MCP 끔·토큰 폐기 뒤 401, 감사 `TB_MCP_CALL_HSTRY` 에 도구·결과만 (OK·ERROR — 입력 없음). `getLogicalModel` 은 예전부터 감사 대상 (변경 없음).
- 공통 서비스는 컨트롤러가 없다. `AuthUtil` 을 공통 계산에 다시 넣지 않았다. 가짜 로그인·임시 SecurityContext 없음.

## 26. D35-3 테스트 · 검증 결과 · D35-FINAL 계획

### 26.1 신규·수정 테스트

| 테스트 | 내용 |
|---|---|
| `LogicalMetadataServiceIntegrationTest` (+3 → 13) | 속성 설명 포함·제외·미등록 구분, 항목별 조회(칸 없음 = 조회 안 함, 페이지 합집합 = 전체·순서, next 정확성, 그룹 하나의 직접 서브타입, 다른 그룹 '없음'), 입력 검증(section·ALL+page·관계없는 인자·음수·넘치는 page·limit 맞춤·개요 항목 조회) |
| `LogicalMetadataSizePerformanceTest` (2, 확장) | 21장 크기·SQL 수, next 를 따라 속성 105·관계 60·직접 서브타입 25·영역 130 을 빠짐·겹침 없이 받기 |
| `LogicalMetadataToolIntegrationTest` (신규 6) | 등록 10→13·입력 칸·Tool 정의 크기, 실제 Tool 결과 = 공통 서비스 결과(6가지)·크기, RESULT_TOO_LARGE·안내·근거 없음·안내대로 105개·질의당 8번, 역할(VIEWER·USER·관리자·ADMIN 허용 / 비멤버·NOT_APPROVE·멤버십 해제 거절)·격리·잘못된 입력, 근거 7종(VERIFIED·다음 페이지·다른 프로젝트·없는 ID·조회 안 한 대상·이름 불일치·STALE·상속 속성/식별자의 정의 엔터티 소속·물리 근거 그대로), 답변 스키마 |
| `McpLogicalToolIntegrationTest` (신규 1) | 실제 HTTP JSON-RPC : 목록 13, 같은 구현 결과, 세션 없는 상속 조회, 크기(안쪽·HTTP), 공유 Tool·getLogicalModel 상한 안내, getLogicalModel 호환 칸, 잘못된 입력·격리, 감사, MCP 끔·토큰 폐기 401 |
| `MetadataToolIntegrationTest`·`McpEndpointIntegrationTest` (수정) | 허용 목록·MCP 목록 고정값 10 → 13 (기존 단언은 그대로) |

### 26.2 전체 회귀 (최종 운영·테스트 코드, 격리 사본 — 저장소 dist·개발 DB 미사용)

| 실행 | 결과 |
|---|---|
| H2 `./gradlew build --continue --rerun-tasks` (npm ci · `tsc -b` · vite build · 모든 모듈 테스트 · bootJar) | **1,571 건, 실패 0, 건너뜀 123**, BUILD SUCCESSFUL (4분 43초) |
| PostgreSQL 18.6 새 빈 DB 1 | **1,571 건, 실패 0, 건너뜀 46** (6분 51초) |
| PostgreSQL 18.6 새 빈 DB 2 | **1,571 건, 실패 0, 건너뜀 46** (6분 47초) |

- D35-2 기준 1,561 → 1,571 (+10 = 서비스 3 + Tool 6 + MCP 1). 건너뜀 수 같음. 기존 간헐 위험(`entityApprovalVsEdit`·`s4TwoRenames`·H2 커밋 직전 재확인) 세 실행 모두 재발 없음 — 해결 처리하지 않는다.
- 개발 중 실패와 원인 (최종 실행 전에 모두 고침) : 새 테스트의 데이터 의존 단언(한글 정렬로 페이지 구성이 달라 SQL 수 비교가 틀림 — 같은 범위 비교로), ALL 의 관계 상한이 50 이 아닌 100 이던 구현 결함, 새 픽스처의 관리자 비멤버, 기존 `limitsAndUnsupportedModel` 의 토큰 예산 선행 (24장).

### 26.3 단일 JAR (위 H2 빌드 산출물, 임시 H2·임시 포트)

- MCP 운영 설정 꺼짐 : 기동·최초 설정·로그인, 화면 상속 정보 editable(관리자 직접 속성 true·상속 false), `/api/mcp` 200, `/mcp` 404.
- MCP 켬(`LEXORMA_MCP_ENABLED=true`) : 프로젝트 MCP 켬·토큰 발급(원문은 출력·저장 안 함) → `tools/list` 13개(9,756자), `getLogicalEntityDetail` 3단계 서브타입(상속 속성 5 + 직접 1, 상속 주 식별자 구분 → 번호, 설명 EXCLUDED), ATTRIBUTES + 설명 limit 2 (next 정확), `getLogicalModelOverview` 모델 목록, `searchLogicalEntities` 모델명+검색어, `getLogicalModel` 호환 칸(`attributeScope` DIRECT·`hasInheritance`·개인고객 supertype 고객·inheritanceLevel 3·직접 속성만), 잘못된 section → isError INVALID_ARGUMENT.
- 브라우저 (Playwright 1.62.1 + Chromium, 로컬) 두 모드 : 논리 모델 관리·논리 ERD·AI Provider 설정·MCP 연결 화면 표시, 페이지 오류 없음, 기본 Provider 미설정이라 AI 도우미 숨김. **AI 도우미의 새 근거 유형 표시는 실제 답변이 없어 화면에서 보지 못했다** (타입 검사·빌드만).

### 26.4 실행 검증 수준 (작업 지시 30장)

1 공통 서비스 직접 호출 ✓ · 2 `MetadataToolset` 실행 ✓ · 3 내부 AI Tool 결과 변환기(Spring AI `FunctionToolCallback`) ✓ · 4 MCP 실제 프로토콜 ✓ (테스트 + 단일 JAR) · 5 Provider 대체 통제 테스트 : 기존 `MetadataToolIntegrationTest`·`AiToolCompletionIntegrationTest`(가짜 Ollama) 회귀만 — 논리 Tool 을 부르는 가짜 모델 대화는 만들지 않았다 · 6 실제 AI Provider : 하지 않음 (D35-FINAL).

### 26.5 D35-FINAL 검증 계획

- 환경 : 실제로 쓸 수 있는 AI Provider(로컬 Ollama 등 Tool Calling 지원 모델)와 외부 MCP 클라이언트를 확인한 뒤 정한다 — 사용자 API Key·외부 서비스를 임의로 설정하지 않는다.
- 시나리오 (내부 AI 와 MCP 클라이언트 각각) : 논리 모델 목록 / 고객 관리 주제 영역의 엔터티 / 하위 영역 포함 / 미분류 엔터티 / 엔터티의 슈퍼타입 / 유효 속성 / 직접·상속 구분 / 상속 주 식별자와 구성 순서 / 속성의 업무 의미(설명 포함 조회) / 관계가 많은 엔터티(RELATIONS 페이지) / 일부 페이지만 본 경우 확인 범위 표현 / 승인되지 않은 엔터티 구분 / 물리 모델·데이터 사전 기존 질문 회귀.
- 확인 : Tool 선택·인자(section·next 사용), 호출 수(8번 안), RESULT_TOO_LARGE 뒤 안내 따르기, 근거 검증 결과(논리 유형 VERIFIED 비율·NOT_RETRIEVED), 토큰 사용량(서버 보고 실측과 추정 구분), 답변의 상속·승인 표현.
- 정리 : 결과 기록 뒤 이 문서를 `completed/` 로 옮기고 completed-work.md 에 D-35 기록.

### 26.6 남은 위험 · 미검증

- 토큰 예산 여유 : 논리 Tool 정의로 요청마다 약 1,050 토큰(추정)이 늘었다. 기존 반복 시나리오 테스트의 여유는 추정 약 200 토큰 — 이후 Tool 설명·프롬프트를 늘리면 다시 예산이 먼저 걸릴 수 있다 (결정 항목 ②).
- '속성 설명을 읽었다' 는 근거로 서버가 확인하지 못한다 (23장, 프롬프트 규칙만).
- 주제 영역 100개 모두에 긴 설명이 있는 개요 ALL 은 측정하지 않았다 (추정 상한 초과 — SUBJECT_AREAS 페이지로 받을 수 있음).
- 실제 LLM 이 next·section 을 제대로 쓰는지, 새 근거 유형의 화면 표시는 D35-FINAL 에서 확인.
- 페이지 사이 편집은 고정하지 않는다 (D35-D15).

---

## 27. D35-FINAL 확정 정책 (D35-D23~D29) 과 반영

| ID | 내용 | 반영 |
|---|---|---|
| D23 | 기본 컨텍스트·토큰 예산 유지, 실제 사용량 측정 | 설정·판정 그대로. 가짜 Provider 요청 본문 실측(추정 규칙)과 실제 Ollama 보고 사용량(실측)을 나눠 기록 (28장) |
| D24 | Tool 라우팅 구조 도입 보류, 설명 중복만 정리 | 구조 변경 없음. 실제 모델 결과로 확인한 설명 빈틈 두 곳만 보완 (`searchLogicalEntities` 의 keyword·subjectAreaId 출처 — 33장) |
| D25 | 속성 존재와 설명 조회를 구분해 검증 | 질의 세션에 설명 조회 기록, 근거의 선택 칸 `usesDescription` 검증 (29장) |
| D26 | 근거 영속화 유지, 마이그레이션 없음 | AI-4 답변은 저장하지 않는 구조 그대로 — 세션 안 기록만 더했다. 답변 스키마는 선택 칸 추가(호환) |
| D27 | RESULT_TOO_LARGE 추가 정보 승인 | `tool`·`resultChars`·`maxChars`·`hint`(논리 Tool)·`alternatives`(getLogicalModel) 유지, 기존 Tool 에도 같은 칸 (31장) |
| D28 | 호출 제한 유지 (8·4·24,000·30초) | 변경 없음. 실제 대화 경로(Spring AI 호출 관리자)에서 확인 (30장) |
| D29 | 기존 Provider 활용 | 이미 설치·실행 중인 로컬 Ollama `gemma4:e4b` 사용 (외부 전송·설치·가입 없음, 33장) |

## 28. 토큰 예산 분석

**계산 구조 (코드 확인)** : `TokenBudget` — 컨텍스트 = min(설정 16,384, 모델이 알려 준 길이), 출력(num_predict) 4,096, 요청 입력 상한 = 컨텍스트 − 출력 = 12,288, 질의 누적 상한 100,000.
`TokenMeter.admit` 이 모델 호출마다 보내기 전에 추정 입력(메시지마다 8 + 본문, Tool 호출 인자, Tool 결과, **Tool 정의 이름·설명·입력 스키마**)을 상한과 비교하고, 누적에는 서버가 보고한 사용량(없으면 추정·출력 한도)을 더한다.
구조화 단계는 Tool 정의 없이 시스템 지시 + **답변 형식(스키마 문구, `parser.format()`)** 을 메시지로 보내므로 스키마도 추정에 들어간다 — 누락·중복 계산은 찾지 못했다 (수정 없음).

**요청 구성별 크기** (`LogicalMetadataToolIntegrationTest.tokenComponents`, 추정 = TokenEstimator 규칙 — 실측 아님)

| 구성 | 문자 | 추정 토큰 |
|---|---|---|
| Tool 단계 시스템 지시 | 1,858 | 620 |
| Tool 정의 13개 (이름+설명+입력 스키마, 메시지 비용 포함) | 11,481 | 3,957 |
| Tool 단계 고정 부분 합 (질문 제외) | — | 약 4,585 / 입력 상한 12,288 |
| 구조화 지시 + 답변 형식 | 4,288 | 1,430 |
| 질문 (예 : "개인고객의 주 식별자는?") | 수십 자 | 수십 |
| 상세 결과 (3단계 서브타입) / 설명 포함 속성 20개 | 3,088 / 10,571 | 약 1,100 / 6,340 |

가짜 Provider 흐름의 요청 본문 (추정 규칙을 HTTP 본문 전체에 적용 — 참고값) : 첫 요청 약 4,200, 개요→검색→상세 네 번째 요청 6,936, 상한 9번 시나리오의 9번째 요청 6,122, 구조화 요청 약 4,400 (`LogicalAiQueryFlowIntegrationTest` 측정 줄).

**실제 사용량 (Ollama 보고 — 실측)** : 33장 표. D-35 이전 JAR(HEAD) 과 같은 질문 R1~R8 비교에서 질문당 프롬프트 토큰이 **약 1,440 늘었다** (예 R1 5,123 → 6,566 — 모델 호출 3번 중 Tool 정의를 싣는 2번에 약 720씩). 같은 변경의 추정 증가는 약 2,700 으로, 추정 규칙은 실제보다 1.6~1.9배 크게 잡는다 (보수적 — 의도).

**D35-3 의 '여유 200 토큰' 테스트** (`MetadataToolIntegrationTest.limitsAndUnsupportedModel`) : 테스트 설정은 **실제 기본값과 같다** (컨텍스트 16,384·출력 4,096 — 모델 컨텍스트가 더 커도 설정값이 상한). 같은 물리 Tool(searchTables)을 반복해 Tool 결과가 쌓이는 시나리오로, 다섯 번째 요청 추정이 상한 바로 아래다. 즉 테스트 전용 문제가 아니라 **실제 환경에서도 Tool 결과가 약 7,700 추정 토큰(고정 부분을 뺀 나머지)을 넘게 쌓이면 예산에서 멈춘다** — 원인은 Tool 정의 증가(추정 +1,300)와 결과 누적이 함께 쓰는 상한이다. 물리 질문 하나(Tool 2번)는 실측 6,500~8,300 프롬프트 토큰으로 여유가 있고, 논리 상세 여러 번·설명 포함 페이지는 빨리 닿는다 (아래).
- 설명 포함 속성 20개 페이지 하나가 추정 약 6,300 — 같은 질의에서 두 번째 페이지를 받으면 다음 요청이 상한을 넘는다 (`descriptionPagesAgainstBudget` : 1페이지 20/105 뒤 `AI_TOKEN_BUDGET_EXCEEDED`). 실제 모델 S12 도 같은 이유로 끝났다 (추정 12,431 > 12,288).
- 예산을 넘으면 그 요청을 보내지 않고 질의가 `AI_TOKEN_BUDGET_EXCEEDED`(422, "질문을 좁혀 다시 시도") 로 끝난다 — 잘라 보내기·우회·추정 답변 없음 (확인). 다만 **받은 만큼으로 부분 답변을 만들지는 않는다** (기존 정책) — 결정 사항 ①.
- 예산 관련 코드 수정 : 없음 (결함 없음, 정책 변경은 결정 사항).

## 29. 속성 설명 근거 검증 (D35-D25)

- 현행 한계 (D35-3) : 근거 기록은 대상 단위라 '속성이 결과에 있었다' 와 '속성 설명을 읽었다' 를 구분하지 못했다.
- **세션 기록** (`MetadataToolSession`) : `getLogicalEntityDetail` 이 설명을 실제로 실은 응답(`attributeDescriptions: "INCLUDED"`)에서만, 실린 속성마다 `DescriptionView(그때 행 버전, 설명 등록 여부)` 를 남긴다. 설명을 뺀 응답·실패한 호출(상한 초과·오류)·다른 프로젝트·페이지에 없던 속성은 기록하지 않는다. 같은 속성을 다시 설명과 함께 받으면 최신 기록으로 바꾸고(버전이 다른 기록을 합치지 않는다), 실패한 호출은 `discardSince` 로 그 호출 직전 상태로 되돌린다 (그 전 성공 호출의 대상·설명 기록은 같은 대상이어도 남는다). 상속 속성은 원본 행 기준 (정의 엔터티).
- **답변 계약** : 근거 항목에 선택 칸 `usesDescription` (boolean, LOGICAL_ATTRIBUTE 만). 없거나 false 면 예전과 같다 (호환 — 기존 답변·AI-5 경로 영향 없음). 구조화 지시에 '돌려받은 설명을 쓴 속성에만 true' 를 넣었다.
- **검증** (`EvidenceVerifier`) : 기존 판정(존재·프로젝트·이 질의의 결과·버전·이름)을 통과한 뒤 `usesDescription=true` 면 — 그 속성의 설명 기록이 없거나 지금 버전과 다르면 `UNVERIFIABLE`/`DESCRIPTION_NOT_RETRIEVED`, 설명이 등록돼 있지 않았으면 `MISMATCH`/`DESCRIPTION_NOT_REGISTERED`, 아니면 VERIFIED. 속성이 아닌 근거의 표시는 확인할 수 없어 `UNVERIFIABLE`. 화면 사유 이름 2개 추가 (한국어·영어).
- 테스트 : `LogicalMetadataToolIntegrationTest.descriptionRecords`(제외 → 포함 누적 → 실패 호출 뒤 유지 → 오류 호출 → 버전 변경 STALE), `LogicalAiQueryFlowIntegrationTest.descriptionEvidence`(실제 질의 흐름 : 설명 조회 VERIFIED · 미등록 MISMATCH · 설명 없이 본 속성 UNVERIFIABLE).
- **해결하지 못한 범위** : 서버는 답변 문장이 설명을 썼는지 읽지 않는다. 모델이 설명을 쓰고도 `usesDescription` 을 빼면 검출하지 못하고, 표시가 맞아도 문장이 설명과 같은 뜻인지 판정하지 않는다 (키워드 검사도 하지 않는다). 실제 모델은 S7·S11 에서 설명을 썼지만 표시를 하지 않았고, S4 에서는 엔터티에 표시했다 (33장) — 결정 사항 ②.

## 30. 크기 · 호출 제한 · next (D35-FINAL 측정)

| 대상 | 결과 |
|---|---|
| 개요 ALL (영역 133, 설명 1,000자 → 300자) | RESULT_TOO_LARGE (45,688자) — hint 에 SUBJECT_AREAS |
| SUBJECT_AREAS limit 20 / 40 / 60 / 100 | 8,543 / 17,640 / 초과 26,722 / 초과 44,958 — limit 40 + next 4번으로 133개 모두 (같은 Tool 4번 안, 테스트) |
| 설명 포함 ATTRIBUTES limit 20 / 40 / 45 / 46 / 50 | 10,571 / 20,671 / 23,191 / 23,695 / 초과 25,711 (설명 280~300자, 105개 중) — 결과 상한만 보면 limit 45 × 3번이면 4번 안에 105개 |
| 같은 105개를 한 질의에서 | 토큰 예산이 먼저 : limit 20 한 페이지(추정 약 6,300) 뒤 다음 요청이 입력 상한 초과 → 한 질의로는 설명 포함 105개를 다 받을 수 없다 (실제 모델 S12 동일) |
| 관계 60·직접 서브타입 25·속성 105(설명 제외)·영역 130 | next 로 누락·중복 없음 (D35-3 테스트 그대로 통과) |

- 호출 제한 (실제 대화 경로 — 가짜 Provider) : 같은 Tool 5번째 요청 → 4번만 실행 후 `AI_TOOL_CALL_LIMIT_EXCEEDED`, Tool 3종 9번 → 8번 실행 후 같은 오류. 질의는 답 없이 끝나 '전부 조회했다' 고 답하지 않는다. 실패·상한 초과 호출도 호출 수에 들어간다 (상한 초과 시나리오 : OK·FAILED·OK 세 번 기록). 참고 : MCP·서버 맥락이 쓰는 `MetadataToolset.run` 경로는 전체 8번만 보고 같은 Tool 4번은 Spring AI 대화에서만 적용된다 (기존 구조).
- next : ALL → ATTRIBUTES(같은 limit 100), section 별 페이지, includeAttributeDescriptions·entityId·modelId·subtypeGroupId 유지, 마지막 페이지 complete=false·hasMore=false·next 없음, 잘못된 page 인자 400 — D35-3 테스트 + D35-FINAL 흐름 테스트. 실제 모델이 next 를 그대로 쓴 사례는 S12 가 예산에서 멈춰 확인하지 못했다 (33장).

## 31. RESULT_TOO_LARGE · 근거 경계

- 오류 코드·`message` 의미 그대로, 추가 칸 `tool`·`resultChars`·`maxChars` (모든 공유 Tool), `hint` (논리 Tool), `alternatives` (MCP getLogicalModel). 기존 물리 Tool 확인 : `getTableColumns` (컬럼 100 × 설명 250자 + 업무 설명 300자) → 74,681자 결과가 오류 약 200자로, hint 없음 (`physicalTooLarge`). 데이터 사전 Tool 은 결과가 목록 20건·설명 300자로 잘려 상한에 닿는 데이터를 만들 수 없어 같은 코드 경로(실행 경계 공통)로만 확인했다.
- 기존 클라이언트 호환 : 오류 JSON 에 칸만 더했다 (기존 `error`·`message` 그대로). 내부 AI 는 문자열로 모델에게 주고, MCP 는 `isError=true` 와 같은 텍스트 — 기존 MCP 테스트(McpEndpointIntegrationTest) 그대로 통과. 외부 MCP 클라이언트로는 확인하지 못했다 (36장).
- 실패 호출의 근거 : 성공 → 상한 초과 → 축소 흐름에서 앞 호출의 근거(모델) 유지, 실패 호출에만 있던 속성은 `UNVERIFIABLE`(NOT_RETRIEVED), 잘못된 입력·없는 대상 호출도 근거 없음 (`tooLargeThenNarrow`·`descriptionRecords`). 결과 변환 실패는 Tool 함수 예외와 같은 경로(오류 처리 후 되돌림)라 따로 만들지 않았다.
- **상속 문맥과 근거 버전** : 그룹을 지워 법인고객의 상속이 바뀐 뒤에도 속성 근거(당사자번호 — 행 그대로)와 엔터티 근거는 VERIFIED, 그룹 근거만 STALE (`inheritanceContextVersion`). 개별 행의 수정 일시로는 상속 문맥 변경(슈퍼타입·그룹·루트 주 식별자 구성·상속 관계)을 그 행의 근거에서 검출하지 못한다 — 복합 버전이 필요해 도입하지 않았다 (결정 사항 ③).

## 32. 읽기 일관성

- 정책 그대로 (READ COMMITTED, 잠금·재시도·격리 변경 없음). 상속 조회 중 엔터티 삭제·그룹 변경, 검색 중 영역 변경, 상세 중 속성·식별자 변경의 실제 경합은 **재현하지 않았다** (두 쿼리 사이에 다른 커밋을 끼우는 장치를 운영 코드에 넣지 않았다). 감지 판정(`requireLoaded` → 409)은 D35-2 단위 테스트 그대로.

## 33. 가짜 Provider 통합 흐름 · 실제 AI Provider 검증

### 33.1 가짜 Provider (결정적, `LogicalAiQueryFlowIntegrationTest` 7) — 실제 LLM 능력 검증이 아니다

질문 → `AiMetadataQueryService` → 가짜 Ollama 의 Tool 호출 → 실제 실행 경계·결과 변환 → Tool 결과를 담은 다음 모델 요청 → 구조화 → 근거 검증 → 결과. 시나리오 : 개요 → 영역 검색 → 상세(상속 주 식별자, 7종 근거 VERIFIED, Tool 결과가 다음 요청의 tool 메시지로) · 설명 근거(VERIFIED / 미등록 MISMATCH / 설명 없이 본 속성 UNVERIFIABLE) · RESULT_TOO_LARGE 뒤 축소(안내가 모델에게 가고, 실패 호출에만 있던 근거 거절, 앞 근거 유지) · 같은 Tool 5번째 · 전체 9번째 (모두 `AI_TOOL_CALL_LIMIT_EXCEEDED`) · 설명 포함 페이지 이어 받기(1페이지 뒤 `AI_TOKEN_BUDGET_EXCEEDED`) · 물리 질문 회귀 (논리 Tool 호출 없음, 근거 VERIFIED). 기존 `MetadataToolIntegrationTest`·`AiToolCompletionIntegrationTest`(가짜 Provider) 그대로 통과.

### 33.2 실제 Provider 환경 (D35-D29)

| 항목 | 값 |
|---|---|
| Provider | 이 Mac 에 이미 설치·실행 중인 Ollama 0.40.18 (127.0.0.1:11434 — 외부 전송 없음, 새 설치·가입·키 없음) |
| 모델 | `gemma4:e4b` (gemma4, 7.5B, Q4_K_M, 모델 컨텍스트 131,072, capabilities completion·tools·thinking·vision·audio) |
| Lexorma 설정 | 기본값 그대로 : 컨텍스트 16,384 (모델보다 작은 설정값), 출력 4,096, 질의 100,000. SEC-1 허용 규칙 AI_HTTP 127.0.0.1/32:11434 만 |
| Tool Calling·구조화 | 지원 (Tool 단계 → 구조화 단계 정상, 구조화 재요청 0회) |
| 사용량 | Ollama 가 prompt·completion 토큰을 보고 — 아래 '실측' |
| 실행 | 단일 JAR(최종 코드), 임시 H2, 격리 예제 데이터(엔터티 12·영역 5·그룹 4·관계 2·유효 속성 105 엔터티). 프로젝트는 예제 프로젝트 0 (LEXORMA 예제 모델 포함, 모두 로컬) |

질문마다 **한 번만** 묻고 결과를 그대로 기록했다 (성공할 때까지 다시 묻지 않음). 1차 결과 뒤 설명·지시 빈틈을 보완(33.4)하고, 영향을 받는 질문만 **보완 뒤 1회** 다시 물어 따로 기록했다. 원인 진단을 위해 S3·S9 를 DEBUG 로그로 1회, R4 의 출력 길이 변동을 보려고 기준선(HEAD)·최종 각 1회를 더 물었다 — 모두 아래에 남긴다.

### 33.3 실제 질문 결과 (1차, 최종 코드 직전 — 설명 보완 전)

| # | 질문 요지 | Tool (상태) | 결과 | 판정 · 근거 |
|---|---|---|---|---|
| S1 | 논리 모델 목록·개요 | 개요 OK | 모델 2개 · 수(엔터티 91/12, 관계 109/2, 영역 0/5, 그룹 0/4) 정확, 다른 프로젝트 없음 | **PASS** · 근거 2/2 |
| S2 | 주제 영역 계층 | 개요 OK | 계층·깊이·하위 포함 수 정확 | **FAIL(근거)** : 영역을 LOGICAL_ENTITY 로 인용 → 0/5 (서버가 걸러 냄) |
| S3 | 고객 관리 영역 엔터티(하위 포함) | 검색 OK | "등록되어 있지 않음" — **틀림** (정답 4개) | **FAIL** : 진단 1회도 같음. SQL 에 영역 조건 없음 → 모델이 영역 이름을 keyword 로 보냄 (subjectAreaId 를 모름) |
| S4 | 미분류 엔터티 | 검색 OK | 거래처·계약 정확 | **PARTIAL** : 엔터티에 usesDescription 표시 → UNVERIFIABLE 2 |
| S5 | 주문의 속성·식별자 | 상세 OK | 속성 4·필수·주 식별자 정확 | **FAIL(근거)** : 속성을 COLUMN 으로 인용 → 2/6 |
| S6 | 개인고객의 슈퍼타입·루트 | 검색·상세 OK | 개인고객 → 고객 → 당사자(루트) 정확 | **PASS** · 3/3 |
| S7 | 직접·상속 속성 구분 | 상세 OK | 직접 1·상속 5, 설명·없음 정확 | **PASS** · 6/6 (설명을 썼지만 usesDescription 표시 없음 — 설명 근거는 확인되지 않은 채) |
| S8 | 상속 주 식별자 | 상세 OK | 당사자 PK, 구분 → 번호 순서, 당사자에서 상속 정확 | **PASS** · 6/6 |
| S9 | 법인고객의 상속 보조 식별자 | 상세 OK·INVALID_ARGUMENT·상세 OK | "없음" — **틀림** (고객 이메일 AK) | **FAIL** (진단 1회는 정답 — 모델 비결정) |
| S10 | 고객의 직접·상속 관계 | 상세 OK | 고객 주문(직접)·당사자 계약(상속) 맞음, "고객이 고객 유형 그룹에 속한다" 부정확 | **FAIL** : 부분 사실 오류 + 관계를 RELATION 으로 인용 1/2 |
| S11 | 속성 설명 (등록·미등록) | 상세 OK | 당사자번호 설명 정확, 등록일자 "없음" 정확 | **PASS** · 2/2 (usesDescription 표시 없음) |
| S12 | 대형말단 105개를 설명과 함께 | — | `AI_TOKEN_BUDGET_EXCEEDED` (추정 12,431 > 12,288) — 답 없음, 거짓 주장 없음 | **PARTIAL** : 기존 예산 정책대로 중단 (부분 답변 없음 — 결정 ①) |
| R1 | 테이블 찾기 | searchTables OK | 정확 | PASS |
| R2 | 인덱스·제약 | getTableDetail OK | UX_LGCL_ENTT_01, 등록 제약 없음 (예제는 PK 를 컬럼 PK 표시로 둠) | PASS |
| R3 | 컬럼 | getTableColumns OK | 정확 · 11/11 | PASS |
| R4 | 테이블 관계 | — | `AI_TOKEN_BUDGET_EXCEEDED` — **응답이 출력 한도 4,096 에서 잘림** | **FAIL** (33.5) |
| R5 | 표준 용어 | searchStandardTerms OK | 정확 · 9/9 | PASS |
| R6 | 표준 도메인 | searchStandardDomains OK | 정확 · 3/3 | PASS |
| R7 | 표준 단어 | searchStandardWords OK | 정확 | PASS |
| R8 | 업무 설명 | getTableDetail OK | 정확 | PASS |

물리·사전 질문에서 논리 Tool 을 부른 경우는 없다. 질문당 시간 7~42초.

### 33.4 1차 결과로 보완한 것 (D35-D24 범위 — 설명·지시·기록)

1. `searchLogicalEntities` 입력 설명 : keyword = 엔터티 이름·설명만, subjectAreaId = getLogicalModelOverview 에서 (S3 원인).
2. 구조화 지시 : 논리 ID → 근거 유형 대응표(modelId → LOGICAL_MODEL … groupId → SUBTYPE_GROUP, COLUMN·TABLE·RELATION 금지), usesDescription 은 돌려받은 설명을 쓴 LOGICAL_ATTRIBUTE 에만 (S2·S4·S5·S10).
3. **근거 기록 결함 수정** : 상세 응답에 ID·이름이 실려 있지만 근거로 기록되지 않던 참조 — 속성·식별자의 정의 엔터티, 식별자 구성 속성, 관계 양 끝·상속 출처·속성 짝 (화면 검증에서 '당사자' 근거가 '조회하지 않은 정보' 로 나온 것, S9 보완 뒤 구성 속성 근거). 테스트 `referencedEvidence`.
4. 화면 도우미 안내문에 '논리 모델' 추가 (한국어·영어).
Tool 정의 +133자(추정 +44 토큰) — 기존 예산 회귀 테스트 그대로 통과.

### 33.5 보완 뒤 1회 (같은 질문 — 1차 결과는 바꾸지 않는다)

| # | Tool | 결과 | 판정 |
|---|---|---|---|
| S2 | 개요 | 계층 정확 | **FAIL(근거)** : 여전히 영역을 LOGICAL_ENTITY 로 0/5 — 모델이 대응표를 따르지 않음 |
| S3 | 개요 → 검색(subjectAreaId) | 당사자·고객·개인고객·법인고객 정확 | **PASS** 4/4 |
| S4 | 검색 | 정확 | **PASS** 2/2 |
| S5 | 검색 → 상세 | 정확 | **PASS** 6/6 (LOGICAL_ATTRIBUTE 로 인용) |
| S7 | 상세 | 정확 | PASS 7/7 (usesDescription 표시 여전히 없음) |
| S9 | 검색 → 상세 | 고객 이메일 AK 정확 | PARTIAL 2/3 (구성 속성 근거 — 3 의 수정 전 JAR) |
| S10 | 상세 2 | 직접·상속 관계 정확, 그룹 표현 부정확 | **FAIL(근거)** RELATION 인용 4/6 |
| S11 | 상세 | 정확 | PASS 3/3 |
| R4 | — | 출력 한도 4,096 에서 잘림 | FAIL |

R4 출력 길이 (같은 질문, 모두 기록) : 기준선 HEAD(D-35 이전) 2회 성공 (completion 4,736 · 4,913 — 질의 합계), 최종 코드 3회 중 1회 성공 (completion 3,243) · 2회 한 응답이 4,096 초과. 출력 한도는 D-35 가 바꾸지 않았고(입력 예산과 별개) 원인을 확정하지 못했다 — 표본이 작아 D-35 영향 여부를 판단할 수 없다. 관계가 많은 테이블의 긴 답변이 출력 한도 근처라는 위험으로 남긴다 (결정 ④).

### 33.6 실제 토큰 사용량 (Ollama 보고 — 실측) · 추정과의 차이

| 질문 | 기준선 HEAD prompt / completion | 최종 prompt / completion | 최종 추정 입력 합 |
|---|---|---|---|
| R1 | 5,123 / 985 | 6,566 / 1,111 | 11,157 |
| R2 | 5,118 / 423 | 6,553 / 907 | 11,095 |
| R3 | 6,797 / 1,989 | 8,250 / 1,310 | 13,244 |
| R5 | 6,296 / 2,771 | 7,770 / 1,142 | 12,296 |
| R6 | 5,396 / 1,635 | 6,853 / 1,393 | 11,483 |
| R7 | 4,929 / 641 | 6,362 / 731 | 10,926 |
| R8 | 5,116 / 887 | 6,548 / 909 | 11,148 |

- 같은 물리 질문의 프롬프트 토큰 +1,433~1,474 (Tool 정의를 싣는 Tool 단계 요청 2번 × 약 720) — 질의 예산(100,000)·요청 상한(12,288 추정) 안. 논리 질문은 실측 6,400~13,000 (Tool 2~3번이면 추정 합 17,000~22,000).
- 추정 대 실측 : 추정 입력 합이 실측 프롬프트 합의 약 1.5~1.7배 (보수적). 요청 하나의 상한 판정은 추정으로 한다 — 그래서 실제로는 여유가 있어도 추정으로 막힌다 (S12 : 추정 12,431).

## 34. MCP · 화면 · 단일 JAR (D35-FINAL)

- **MCP (실제 HTTP JSON-RPC, 단일 JAR)** : 운영 설정 끔 → `/mcp` 404 (D35-3 과 같음, 내부 AI 는 MCP 와 무관하게 동작 — 위 실제 질문 모두 MCP 를 거치지 않음). 켬 : `initialize`(2025-06-18) · `tools/list` 13개 · 개요 · 검색 2페이지(nextPage) · 상세 section 4종 · 설명 포함 ALL → RESULT_TOO_LARGE(49,841자, hint) · ATTRIBUTES limit 40(19,779자, next) · 잘못된 section → isError INVALID_ARGUMENT · 없는 엔터티 → NOT_FOUND · `getLogicalModel` 목록·상세(attributeScope DIRECT·hasInheritance·개인고객 supertype 고객·inheritanceLevel 3·직접 식별자 0, 당사자 식별자 1) · 프로젝트 MCP 끔 401 → 켬 200 → 토큰 폐기 401. 다른 프로젝트 대상은 통합 테스트(McpLogicalToolIntegrationTest)로만 확인. **실제 MCP 클라이언트(Claude Code 등)로는 확인하지 않았다** — 설치된 클라이언트 설정을 바꾸지 않았다.
- **화면 (Playwright 1.62.1 + Chromium, 실제 AI 답변)** : 한국어·라이트·1440 (개인고객 주 식별자·상속 경로 — 답변·근거 4개, 이 때 '당사자' 근거가 '조회하지 않은 정보' 로 나와 33.4-3 을 고침), 한국어·다크·1024 (당사자번호 설명 — 근거 1 확인), 영어·라이트·375 (법인고객 슈퍼타입 — 근거 4 확인, 유형 이름 Entity·Subtype groups), 진행 표시·Tool 이름(논리 엔터티 검색·상세)·모델·시간·토큰, 가로 넘침 0px, 페이지 오류 없음. 근거 이동(링크) 기능은 기존에도 없어 더하지 않았다. 오류 안내는 S12·R4 의 422 (HTTP 로 확인 — 화면 확인 안 함).
- 스크린샷·기록 : 세션 scratchpad `d35/shots/final-*.png`, `d35/final_ui.log`·`final_ui.json`·`mcp_recheck.json`.

- **화면 재확인 (최종 JAR, 33.4-3 수정 뒤 1회)** : 한국어·라이트·1440 — '당사자'(식별자 정의 엔터티) 근거 VERIFIED. 모델이 설명을 받지 않은 속성 두 개에 usesDescription 을 달았고 서버가 '확인 불가 · AI 가 설명을 조회하지 않음' 으로 표시 (D35-D25 검증이 실제로 동작). 다크·1024 근거 3/3, 영어·375 는 모델이 논리 모델을 엔터티로 인용해 '없음(None)' 으로 거절. 기록 `d35/final_ui_run2.log` (1차는 `final_ui_run1.log`·`shots/run1/`). 이 스크립트의 끝 MCP 끔·폐기 줄은 같은 계정 브라우저 로그인이 스크립트 세션을 끊어(한 계정 한 세션) 요청이 거절된 것이라 MCP 검증으로 쓰지 않는다 — MCP 끔·폐기는 별도 재확인(`mcp_recheck.json` : 끔 401 → 켬 200 → 폐기 401).

## 35. 테스트 · 전체 회귀 (D35-FINAL)

| 테스트 | 내용 |
|---|---|
| `LogicalAiQueryFlowIntegrationTest` (신규 7) | 33.1 가짜 Provider 전체 흐름 |
| `LogicalMetadataToolIntegrationTest` (+6 → 13) | 설명 근거 기록(누적·실패 유지·버전), 참조 근거(정의 엔터티·구성 속성·관계 양 끝·짝), 상속 문맥과 근거 버전, 긴 영역 설명·limit 별 크기, 물리 Tool RESULT_TOO_LARGE 호환, 토큰 구성 |

| 실행 (최종 운영·테스트 코드, 격리 사본) | 결과 |
|---|---|
| H2 `./gradlew build --continue --rerun-tasks` (npm ci · `tsc -b` · vite build · 모든 모듈 테스트 · bootJar) | **1,584 건, 실패 0, 건너뜀 123** (4분 46초) |
| PostgreSQL 18.6 새 빈 DB 1 | **1,584 건, 실패 0, 건너뜀 46** |
| PostgreSQL 18.6 새 빈 DB 2 | **1,584 건, 실패 0, 건너뜀 46** |

- 1,571 → 1,584 (+13 = 흐름 7 + Tool 경계 6). 기존 테스트 변경 없음 (D35-FINAL 에서). 기존 간헐 위험(`entityApprovalVsEdit`·`s4TwoRenames`·H2 커밋 직전 재확인) 세 실행 모두 재발 없음 — 해결 처리하지 않는다.
- 단일 JAR (위 빌드 산출물) : 기동·최초 설정·로그인·프로젝트 선택·논리 모델 관리·논리 ERD·AI Provider 설정·MCP 연결 화면·내부 AI 질의(실제 모델)·MCP 설정과 조회·정상 종료 (34장).

## 36. 최종 Tool 계약 (D-35)

| Tool | 경로 | 입력 | 결과 |
|---|---|---|---|
| `getLogicalModelOverview` | 내부 AI · MCP 공유 | modelId·modelName (없으면 모델 목록), section ALL·SUBJECT_AREAS·SUBTYPE_GROUPS, page·limit | 20장 |
| `searchLogicalEntities` | 내부 AI · MCP 공유 | modelId·modelName, keyword(엔터티 이름·설명), subjectAreaId(개요에서), includeChildren(기본 true), unclassifiedOnly, page, limit(1~20) | 15·20장 |
| `getLogicalEntityDetail` | 내부 AI · MCP 공유 | entityId 또는 모델+entityName, section ALL·ATTRIBUTES·IDENTIFIERS·RELATIONS·INHERITANCE, includeAttributeDescriptions, subtypeGroupId, page, limit | 20장 |
| `getLogicalModel` | MCP 전용 (기존) | modelName? | 기존 칸 + attributeScope·hasInheritance·note·supertype·inheritanceLevel, 상한 초과 시 alternatives |

공통 : 프로젝트는 실행 컨텍스트·토큰에서만, 호출마다 VIEWER 재검증, 읽기 전용 트랜잭션, 결과 24,000자(초과 시 RESULT_TOO_LARGE + tool·resultChars·maxChars·hint), 질의당 8번·같은 Tool 4번(내부 AI 대화), 근거 유형 7개 + `usesDescription`.

## 37. 남은 비차단 위험 · 사용자 결정

**릴리스 차단 결함** : 찾지 못했다 (발견한 제품 결함 — 참조 근거 미기록, 검색 입력 설명 빈틈 — 은 고쳤다).

**사용자 결정 필요**
1. **토큰 예산 초과 시 부분 답변** : 지금은 예산을 넘는 요청을 보내지 않고 질의 전체가 `AI_TOKEN_BUDGET_EXCEEDED` 로 끝난다 (설명 포함 속성 20개 한 페이지면 다음 요청이 상한). 받은 결과로 '확인한 범위' 답변을 만드는 동작은 정책 변경이라 하지 않았다. 선택 : (a) 지금대로 (b) 예산 초과 직전까지의 결과로 구조화 단계를 실행해 부분 답변 + 미조회 범위 명시 (추천 — 단 구조화 단계 자체의 예산을 남겨야 함) (c) 설명 포함 상세의 기본 limit 을 더 작게.
2. **설명 근거의 의미 검증 한계** : 서버는 usesDescription 표시와 실제 설명 조회만 대조한다. 모델이 표시를 빼면(S7·S11) 설명 사용을 모르고, 문장이 설명과 같은 뜻인지 보지 않는다. 선택 : (a) 지금대로 한계 명시 (추천) (b) 답변 스키마에 '설명 인용 문구' 칸을 더해 서버가 실제 설명과 문자열 대조 — 스키마 확장·화면 변경이 필요해 별도 과제.
3. **상속 문맥의 근거 버전** : 속성·엔터티 근거는 행의 수정 일시만 본다 — 조회 뒤 상속 그룹·슈퍼타입·루트 주 식별자 구성이 바뀌어도 그 근거는 VERIFIED (그룹 근거만 STALE). 선택 : (a) 지금대로 (추천 — 그룹·식별자 근거가 변경을 드러낸다) (b) 엔터티 근거에 상속 사슬 버전(조상·그룹 수정 일시 묶음)을 더하는 복합 버전 — 별도 과제.
4. **R4 출력 한도** : 관계가 많은 테이블 질문의 답이 출력 한도 4,096 을 넘는 일이 최종 코드 3회 중 2회 (기준선 2회 중 0회) — 원인 미확정, 출력 한도는 D-35 가 바꾸지 않았다. 선택 : (a) 지금대로 관찰 (b) 출력 한도(`lexorma.ai.query.output-tokens`) 운영 설정 검토 — D35-D23 에 따라 이번에 바꾸지 않았다.
5. **소형 로컬 모델의 근거 유형 혼동** (S2·S10 : 영역·논리 관계를 다른 유형으로 인용) — 서버가 걸러 내 잘못 인정되지는 않는다. 지시·설명 보완 뒤에도 남았다 — 모델 한계로 기록 (조치 없음 추천).

**비차단 위험 · 미검증**
- 실제 MCP 클라이언트(Claude Code 등) 연결 검증 안 함 (HTTP 프로토콜만).
- 실제 읽기 경합 미재현 (32장).
- 데이터 사전 Tool 의 결과 상한 초과는 데이터로 만들 수 없어 공통 경로로만 확인.
- 페이지 사이 편집은 고정하지 않는다 (D35-D15).
- 토큰 추정이 실제보다 1.5~1.9배 커서 실제 여유가 있어도 추정으로 막힐 수 있다 (의도된 보수성).
- 실제 모델 평가는 질문당 1회(+보완 뒤 1회) — 비결정적 모델의 통계적 정확도가 아니다.

## 38. D-35 완료 판단 (2026-11-24)

| 기준 | 결과 |
|---|---|
| D35-D01~D29 정책 유지 | ✓ |
| 공통 조회 서비스 · 내부 AI·MCP 공통 Tool | ✓ (테스트 + 실제 모델 + MCP 프로토콜) |
| getLogicalModel 호환 | ✓ |
| 주제 영역·상속 정확성 | ✓ (서비스·Tool·흐름 테스트, 실제 모델 답변은 모델 품질에 따라 33장) |
| 대규모 크기 제어 · 분할 조회 · next | ✓ |
| 호출 제한 유지 | ✓ (실제 대화 경로) |
| 토큰 예산 실제 분석 | ✓ (실측·추정 구분, 28·33.6장) |
| 설명 근거 검증 범위·한계 | ✓ (구현 + 한계 기록, 결정 ②) |
| 실패 호출 근거 제거 | ✓ |
| 가짜 Provider 전체 흐름 | ✓ |
| 실제 AI Provider 필수 시나리오 | ✓ 실행·평가 (1차 20문항 PASS 12 · PARTIAL 2 · FAIL 6 — 논리 12문항 PASS 5 · PARTIAL 2 · FAIL 5, 물리·사전 8문항 PASS 7 · FAIL 1. 보완 뒤 9문항 재실행은 33.5) |
| 기존 물리·사전 회귀 | ✓ 실행 (7 PASS, R4 출력 한도 FAIL — 결정 ④) |
| MCP 실제 프로토콜 | ✓ (클라이언트 미검증) |
| 신규 근거 화면 | ✓ (ko·en, 라이트·다크, 1440·1024·375) |
| 권한·격리 | ✓ (테스트, MCP 끔·폐기는 JAR 에서도) |
| H2 실패 0 · PostgreSQL 2회 실패 0 · 프런트엔드 빌드 · 단일 JAR | ✓ |
| 마이그레이션 · 운영 의존성 | 없음 |

**D-35 를 완료로 처리한다.** 구현과 계획된 검증을 모두 실행했고 릴리스 차단 결함이 없다. 실제 모델 답변의 FAIL 은 소형 로컬 모델의 해석·인용 오류로, 서버의 근거 검증이 잘못된 근거를 인정하지 않았다 — 37장의 결정 사항과 위험은 후속(pending-work [AI-MCP])으로 남긴다.
