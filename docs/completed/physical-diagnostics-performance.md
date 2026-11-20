# M-DIAG-PERF — 물리 모델 구조 진단 성능 개선

- 상태 : **완료 (2026-11-20)**. 규칙·결과·API 는 그대로, 조회 구조만 바꿨다. 마이그레이션 없음 (V0~V3 그대로, 다음 V4).
- 관련 : [physical-erd-m2-improvement.md 27장](physical-erd-m2-improvement.md) (발견), completed-work.md [M-DIAG] (DEC-09·DEC-12 진단 규칙), [model-integrity-hardening.md](model-integrity-hardening.md).
- 작업 문서는 지시서의 `docs/ongoing/physical-diagnostics-performance.md` 대신 완료 시점에 바로 `completed/` 에 두었다 (같은 목적의 기존 문서 없음).

## 1. 작업 배경

M-2 개선 측정(테이블 3,088)에서 물리 ERD 의 노드는 0.5~0.7초에 보이지만 `GET /api/physical/diagnostics` 가 약 6초 걸려 무효 FK 배지가 늦게 붙었다. 진단 의미는 바꾸지 않고 조회 구조와 계산만 개선한다.

## 2. 기존 진단 구조 (개선 전 코드)

| 항목 | 내용 |
|---|---|
| API 진입점 | `PhysicalModelRestController.diagnostics` — `GET /api/physical/diagnostics?schemaId=` (선택), `@PreAuthorize("hasRole('VIEWER')")` |
| 서비스 | `physical/diagnostic/ModelStructureDiagnosticService.diagnose(account, schemaId)` — `@Transactional(readOnly = true)` |
| 응답 | `ModelStructureDiagnosticDto.Result(Summary(tables, errors, warnings), List<Issue>)`, `Issue(id = 종류:대상키, type, severity, schemaId, schemaName, tableId, tableName, objectName, related, isApproval)` — 오류 코드 없음(빈 결과), 다른 프로젝트 스키마 ID 는 빈 결과 |
| 사용처 | 물리 ERD(`PhysicalErdPage` — 무효 FK 배지, 테이블별 ERROR 수), 모델 구조 진단 화면(`ModelDiagnosticsPage`), `ModelStructureDiagnosticIntegrationTest`(DDL 경고와 같은지). 다른 서버 내부 호출자 없음 (DDL Export·매핑·생성은 진단 서비스를 부르지 않고 같은 규칙 함수를 쓴다) |
| 조회 (테이블마다) | `TableInfoRepository.findAllByProjectId`(전체 테이블 엔티티) → 테이블마다 `table.getColumns()`(지연 로딩), `TableDesignService.referencedKeys`(참조 이름 후보 테이블 조회 + 후보의 컬럼 지연 로딩 + 확정 관계 조회), `PhysicalModelService.ddlExtras`(관계 2번·제약조건·인덱스 + 관계 대상마다 `uniqueKeys` = 제약조건·인덱스 다시), 인덱스는 프로젝트 일괄이지만 인덱스 컬럼·컬럼이 지연 로딩 |

## 3. 기존 진단 규칙 (그대로 유지)

| 규칙 | 판단 조건 | 필요한 값 | DDL 공유 |
|---|---|---|---|
| FK_TARGET_NOT_FOUND / FK_TARGET_AMBIGUOUS (오류) | 컬럼의 참조 테이블명을 `TableNameResolver` 로 정하지 못함 (없음 / 여러 스키마 — 확정 관계(`DRVN_COL_SN`) 대상이 후보면 그것) | 참조 컬럼, 같은 이름 테이블·스키마, 확정 관계 대상 | `TableDesignService.referencedKeys` 와 같은 규칙 |
| FK_TARGET_NO_PK / FK_TARGET_COMPOSITE_PK (오류) | 정한 참조 테이블의 PK 가 0개 / 2개 이상 | 대상 PK 컬럼 수 | DDL 의 참조 컬럼 FK 조건 |
| FK_RELATION_NOT_KEY (오류) | 사용자 정의 FK(REF_COLUMN 아님, 종류 FK)의 대상 컬럼 집합이 대상의 PK·단일 고유값 컬럼·UNIQUE 제약·고유 인덱스(조건 없음, 키가 모두 컬럼)가 아님 | 관계 대상 컬럼명·고유값, 대상 PK 이름, 대상 UNIQUE 키 | `TableDdlGenerator.referencesKey` |
| INDEX_SAME_DEFINITION (경고) | 같은 테이블에 키(컬럼 ID·표현식·정렬)·INCLUDE·조건이 같은 인덱스 — 뒤에 등록된 쪽 | 인덱스·구성 요소 | 등록 검사 `signature` 와 같은 기준 |
| INDEX_SAME_AS_PK (경고) | 일반 인덱스 키가 PK 와 같은 순서 | PK 순서(정렬 순서 → 컬럼) | — |
| INDEX_PREFIX_OF_OTHER (경고) | 일반 인덱스 키가 PK·다른 일반 인덱스의 앞부분 (조건·INCLUDE·내림차순·표현식 제외) | 인덱스 키 | — |

순서 : 테이블 = 스키마명 → 테이블명, 테이블 안 = 참조 테이블명 컬럼 → 사용자 정의 관계 → 인덱스(ID 순). LOGICAL 관계와 REF_COLUMN 관계는 FK_RELATION_NOT_KEY 대상이 아니다. 진단은 모델을 바꾸지 않는다.

## 4. 호출 경로

화면 → `api('/api/physical/diagnostics')` → 컨트롤러 → 서비스(읽기 전용 트랜잭션) → (개선 전) 위 반복 조회 / (개선 후) `ModelStructureDiagnosticQuery` 8개 쿼리 → 규칙 → DTO → JSON.

## 5. N+1 및 반복 계산 (개선 전, 측정)

같은 데이터에서 요청당 SQL 이 테이블 수에 비례했다 (Hibernate 통계 : 테이블 100 → 923문, 1,000 → 9,194문, 3,000 → 27,576문, 10,000 → 91,915문). 엔티티·컬렉션 적재 10,000 테이블 36만 건. 원인 : 테이블마다 ①컬럼 컬렉션 ②참조 이름 후보 테이블 + 후보 컬럼 ③확정 관계 ④관계 2번 ⑤제약조건 ⑥인덱스 ⑦관계 대상마다 제약조건·인덱스 다시(같은 대상을 여러 테이블이 가리키면 그만큼 반복), 인덱스 구성 요소·컬럼 지연 로딩. 계산 자체(앞부분 비교 등)는 테이블 안에서만 돌아 O(N²) 문제는 없었다.

## 6. 개선 전 성능 — 18장

## 7. 개선 설계

- 진단에 쓰는 값만 프로젝트 단위로 **종류마다 쿼리 1번** (QueryDSL DTO 프로젝션, 엔티티 적재 없음) → 요청 안에서만 쓰는 맵(`Snapshot`)으로 판단. 프로젝트 전체를 읽는 이유 : 참조·관계 대상은 진단 범위(스키마) 밖일 수 있다 — 범위는 메모리에서 거르고 긴 IN 목록을 만들지 않는다.
- 한 쿼리에 여러 컬렉션을 조인하지 않는다 (행 증폭 없음). 반환 행 수 = 테이블·PK 컬럼·참조 컬럼·확정 관계·FK 쌍·UNIQUE 구성·인덱스·인덱스 구성 요소의 합 (10,000 테이블 약 10만 행 — 개선 전 14.9만 행).
- 규칙은 다시 쓰지 않고 공유한다 : `TableNameResolver.pick`(엔티티 버전 `resolve` 가 같은 함수를 부른다), `TableDdlGenerator.referencesKey(이름 집합 …)`(엔티티 버전이 이것을 부른다), `TableDdlGenerator.referenceableUniqueIndex`(DDL 의 `uniqueKeys` 도 이것을 부른다).
- 트랜잭션 : 그대로 읽기 전용 (READ COMMITTED). 예전에도 여러 쿼리로 읽었으므로 일관성은 같고 조회 시간이 짧아져 진단 중 변경이 끼어들 창은 줄었다. 잠금·격리 수준 변경 없음.
- 캐시 없음, 전역 상태 없음 (요청 끝나면 `Snapshot` 은 참조가 사라진다).

## 8. 일괄 조회 구조 (`physical/diagnostic/ModelStructureDiagnosticQuery`)

| 쿼리 | 행 | 조건 |
|---|---|---|
| `tables` | 테이블 ID·스키마 ID·스키마명·테이블명 | 프로젝트, 스키마 조인 (SCHM_SN NOT NULL) |
| `pkColumns` | PK 컬럼 ID·테이블·이름·정렬 순서 | 프로젝트, `PK_YN` |
| `refColumns` | 참조 테이블명을 적은 컬럼 | 프로젝트, `RFRNC_TBL_NM` not null |
| `derivedTargets` | 확정 관계(DRVN_COL_SN) → 대상 테이블 | 프로젝트 |
| `foreignKeyRelations` | 사용자 정의 FK 관계 × 대상 컬럼 쌍(이름·고유값, columnOrder 순) — 쌍 없는 관계도 한 행 | 프로젝트, FK, REF_COLUMN 아님 |
| `uniqueConstraintMembers` | UNIQUE 제약 × 구성 컬럼명 — 구성 없는 제약도 한 행 | 프로젝트 |
| `indexes` / `indexMembers` | 인덱스 / 구성 요소(컬럼 ID·이름·표현식·정렬, columnOrder 순) | 프로젝트 |

## 9. 메모리 데이터 구조 (`ModelStructureDiagnosticService.Snapshot`, 요청 안에서만)

테이블 ID → 테이블, 테이블명(저장된 그대로) → 테이블 목록(예전 `findAllByProjectIdAndTableNameIn` 의 대문자 이름 정확 일치와 같은 후보), 테이블 → PK 컬럼·참조 컬럼·사용자 정의 관계·인덱스, 컬럼 → 확정 관계 대상, 테이블 → UNIQUE 키(컬럼명 집합). 스키마가 다른 같은 이름 테이블은 목록으로 둬 합치지 않고, 모호하면 예전처럼 정하지 않는다.

## 10. DDL 규칙 정합성

- 참조 키 유효성 : 진단·DDL·FK 등록 검사가 `TableDdlGenerator.referencesKey` 하나를 쓴다 (이름 기준). M-2 카디널리티(`erd/service/PhysicalCardinality.referencesKey`)는 컬럼 ID 기준으로 같은 조건(PK 집합·단일 고유값·UNIQUE 제약·조건 없는 컬럼 고유 인덱스)을 따로 구현하고 있다 — 같은 테이블 안에서 이름과 ID 는 1:1 이라 판단이 같다 (이번에 합치지 않음 : 카디널리티는 참조 유효성과 다른 판단이고 결과를 함께 고정하는 테스트가 각각 있다).
- 고유 인덱스 조건 : `referenceableUniqueIndex` 로 DDL(`PhysicalModelServiceImpl.uniqueKeys`)과 진단이 공유. ERD 카디널리티의 고유 키 조회(`ErdGraphRepositoryImpl.findUniqueKeys`)도 같은 조건을 SQL 로 건다.
- `ModelStructureDiagnosticIntegrationTest`(진단 = DDL 경고) 그대로 통과, DDL 골든 파일 변경 없음.

## 11. 실제 변경 내용

| 파일 | 변경 |
|---|---|
| `physical/diagnostic/ModelStructureDiagnosticQuery` (신규) | 일괄 조회 8개 |
| `physical/diagnostic/ModelStructureDiagnosticService` | 테이블별 조회 제거, `Snapshot` + 같은 규칙 |
| `table/service/TableNameResolver` | 규칙을 `pick`(값 종류와 무관)으로 옮기고 `resolve` 가 부른다 — 동작 그대로 |
| `table/ddl/TableDdlGenerator` | `referencesKey` 이름 집합 오버로드, `referenceableUniqueIndex` 추가 — 기존 메서드는 이것을 부른다 |
| `physical/service/impl/PhysicalModelServiceImpl` | `uniqueKeys` 가 `referenceableUniqueIndex` 를 쓴다 (같은 조건) |
| `lexorma-web` `PhysicalErdPage` | ERD 에서 모델을 바꾼 뒤(관계·테이블·컬럼·키 편집, [새로고침]) 진단도 다시 받는다 (19장) |
| 테스트 | `LegacyModelStructureDiagnostic`(개선 전 구현 그대로 옮긴 비교 기준, 테스트 전용), `ModelStructureDiagnosticEquivalenceTest` 3건 |

모듈 경계 그대로 (data → ai 의존 없음, `ModuleBoundaryTest` 통과). 새 AI 인터페이스·저장 테이블·스케줄러·설정 없음.

## 12. API 호환성

URL·메서드·`schemaId`·응답 DTO·필드·권한(VIEWER)·프로젝트 격리 그대로. 응답 크기 그대로 (10,000 테이블 3,142KB).

## 13. 진단 결과 비교

- **같은 데이터에서 예전 구현과 직접 비교** (측정 코드·`ModelStructureDiagnosticEquivalenceTest`) : H2 는 모든 규모(100~10,000, 컬럼 50·100)에서 목록이 그대로 같다. PostgreSQL 은 100·1,000·3,000·컬럼 50·100 그대로 같고, **10,000 에서 순서 1곳만 달랐다** — 같은 12,506건(집합 동일, 요약 동일), 한 테이블 안 사용자 정의 관계 3건의 순서.
- 원인 : 예전 구현은 테이블의 관계를 정렬 없이 읽어(`findAllByProjectIdAndSourceTable_Id`) PostgreSQL 저장 위치 순서였다 (368 → 369 → 367). 새 구현은 관계 ID 순으로 고정했다. 참조 테이블명 컬럼도 예전에는 정렬 없는 컬렉션 순서였고 이제 컬럼 ID 순이다. 테이블 순서와 테이블 안 묶음 순서(컬럼 → 관계 → 인덱스), 인덱스 순서(예전에도 ID 순)는 같다. 그 순서만 맞추면 PostgreSQL 10,000 도 그대로 같다.
- 판단 : 예전 순서는 보장된 계약이 아니었고(DB 실행 계획 의존) 화면은 이 순서를 쓰지 않는다 (ERD 는 테이블별 수, 진단 화면은 자체 표 정렬) — 정책 변경 아님, 결정 필요 없음으로 보고한다. 테스트는 "보장되지 않던 테이블 안 순서만 ID 순으로 맞춘 예전 결과 = 새 결과" 와 "새 결과가 이미 그 순서" 를 함께 확인한다.
- 예제 프로젝트(신규 설치 H2, 88 테이블) : 오류 0·경고 0 (기존 테스트와 같음).

## 14. 개선 후 성능 (서버, 격리 복사본의 측정 코드 — 저장소에 넣지 않음)

데이터 : 테이블 n(+모호 이름용 2) × 컬럼 20, 스키마 3, 7번째 복합 PK·45번째 PK 없음, 참조 테이블명(앞 테이블·없는 이름·모호·스키마 지정·확정 관계 힌트), 사용자 정의 FK(사슬·10번째 복합·허브·자기 참조·평행·LOGICAL), UNIQUE 제약, 인덱스(같은 정의·PK 와 같음·앞부분·조건부 고유·표현식 고유·INCLUDE 고유). 10,000 테이블 = 컬럼 20만·관계 2.1만·인덱스 2.4만 — 오류 4,728·경고 7,778. 중앙값(측정 15회, 1초 넘는 개선 전은 5회·3회), 로컬 macOS, PostgreSQL 18.6 Docker 빈 DB.

| 규모 | DB | 개선 전 ms | 개선 후 ms (p95) | 감소율 |
|---|---|---:|---:|---:|
| 100 | H2 | 45 | 4 (12) | 91.1% |
| 100 | PostgreSQL | 240 | 9 (17) | 96.2% |
| 1,000 | H2 | 258 | 9 (12) | 96.5% |
| 1,000 | PostgreSQL | 2,586 | 22 (33) | 99.1% |
| 3,000 | H2 | 1,224 | 18 (24) | 98.5% |
| 3,000 | PostgreSQL | 10,293 | 55 (80) | 99.5% |
| 10,000 | H2 | 8,975 | 57 (66) | 99.4% |
| 10,000 | PostgreSQL | 25,604 | 172 (192) | 99.3% |
| 1,000 × 컬럼 50 | H2 / PostgreSQL | 804 / 2,467 | 5 / 54 | 99.4% / 97.8% |
| 1,000 × 컬럼 100 | H2 / PostgreSQL | 994 / 2,707 | 5 / 61 | 99.5% / 97.7% |

첫 호출(Cold) : PostgreSQL 100 → 46ms, 10,000 → 190ms, H2 10,000 → 138ms. 계산과 조회 분리 (PostgreSQL 10,000) : 8개 쿼리 합계 약 109~131ms(인덱스 구성 요소 35~40ms 가 가장 크다), 나머지 약 40~60ms 가 매핑·규칙·DTO. 목표(3,000 테이블 1초 이내, PostgreSQL 500ms 이하) 달성.

## 15. SQL 비교 (요청당, 두 DB 같음)

| 규모 | 개선 전 SQL | 개선 후 SQL | 반환 행 (전 → 후) | 엔티티·컬렉션 적재 (전 → 후) |
|---|---:|---:|---:|---:|
| 100 | 923 | 8 | 1,469 → 1,049 | 3,646 → 0 |
| 1,000 | 9,194 | 8 | 14,866 → 10,471 | 36,104 → 0 |
| 3,000 | 27,576 | 8 | 44,640 → 31,407 | 108,227 → 0 |
| 10,000 | 91,915 | 8 | 148,857 → 104,693 | 360,693 → 0 |

SQL 은 규모와 무관하게 8문 (분할 조회 없음). 행 수는 데이터에 비례한다 (컬럼 50·100개여도 PK·참조 컬럼만 읽어 1,000 테이블 10,471행 그대로).

## 16. 메모리

- 요청당 할당(스레드 할당 바이트, 요청 평균) : PostgreSQL 10,000 1,541MB → 163MB, 3,000 464 → 49MB; H2 10,000 5,054 → 137MB.
- 반복 5회 뒤 GC 후 힙 증감 : 3,000·10,000 모두 0MB (개선 전·후). 전역 캐시 없음. 장시간 운영 메모리는 검증하지 않았다 (짧은 반복만).

## 17. 동시 요청 (같은 JVM, 프로젝트 2개 교대, 테이블 3,000)

| 동시 | 개선 전 최대 (H2 / PG) | 개선 후 최대 (H2 / PG) | 결과 |
|---|---|---|---|
| 2 | 2,799 / 7,168ms | 114 / 262ms | 모두 혼자 진단한 결과와 같음 |
| 5 | 3,106 / 11,084ms | 126 / 425ms | 같음 |
| 10 | 4,204 / 14,027ms | 219 / 582ms | 같음 |

실패 0, 결과 섞임 없음 (요청마다 새 `Snapshot`). 연결 풀·Executor·제한 설정 변경 없음.

## 18. 물리 ERD·화면 (Chromium, 임시 단일 JAR + PostgreSQL 임시 DB, 테이블 3,088 — M-2 와 같은 데이터, 같은 DB 에서 개선 전 JAR(M-2 커밋)과 비교, 3회)

| 항목 | 개선 전 | 개선 후 |
|---|---|---|
| 첫 노드 표시 | 472~520ms | 495~659ms |
| 진단 API (시작~끝) | 약 4.9초 (405~5,327ms) | 62~143ms (414~642ms) |
| 무효 FK 배지 표시 | 5,280~5,332ms | 545~712ms |
| 네트워크 유휴 | 5,778~5,830ms | 1,018~1,204ms |
| 모델 구조 진단 화면 | 5,606ms | 667ms (같은 299건) |

- 중복 요청 : ERD 진입에 1번, 검색·스키마 변경·확대·전체 보기·이웃 탐색·배치 저장으로 다시 부르지 않는다 (개선 전후 같음 — 조회 키가 프로젝트에만 묶여 있다).
- **모델 변경 뒤 갱신 (개선)** : 예전에는 ERD 에서 관계·키를 바꾸거나 [새로고침] 해도 진단을 다시 받지 않아 배지가 남았다 (측정 : 부모 PK 를 바꿔 FK 가 유효해져도 "무효 FK 1" 그대로). 이제 편집·[새로고침] 뒤 다시 받아 배지가 사라진다 (요청 +1). 진단이 빨라져 가능해진 변경이며 전역 캐시는 두지 않는다.
- 비동기 : 진입 직후 다른 메뉴 이동, 진단 도착 전 스키마 변경(+검색)·이웃 탐색 — 화면 오류 없이 지금 범위의 노드에 배지. 프로젝트는 조회 키와 화면(프로젝트별 ReactFlowProvider)이 나뉘어 이전 프로젝트 결과가 섞이지 않는다 (기존 방어, 변경 없음).
- M-2 회귀 (같은 브라우저 스크립트) : 찾기, 이웃 151/300 화면 맞춤, 복합 FK 선 1개, 평행 FK, 자기 참조(끝 기호·툴팁 + **고리를 직접 눌러 관계 상세 열기 — M-2 미검증 항목 확인**), 범위 밖 관계 목록, 이동 경고·로그아웃·세션 만료·409, 깊은 링크, PNG, 논리 ERD.
- 8조합 (한·영 × 라이트·다크 × 1440·375) : ERD 배지("무효 FK 1"·"Invalid FK 1")·진단 화면 299행·가로 넘침 없음.

## 19. 테스트

| 실행 | 결과 |
|---|---|
| `./gradlew build` (H2, npm ci·tsc·vite 포함) | 1,127건, 실패 0, 건너뜀 50 (PostgreSQL 전용·`LEXORMA_PERF` 측정 — 직전과 같은 사유) |
| PostgreSQL 전체 (`--rerun`, 빈 DB) | 1,127건, 실패 0, 건너뜀 33 |
| 증가분 +3 | `ModelStructureDiagnosticEquivalenceTest` : 섞인 모델(200 테이블, 8종 모두 발생)의 전체·스키마별·다른 스키마 ID 진단이 예전 구현과 같음 / 경계 사례(대소문자 섞인 이름·스키마명 지정·공백·같은 이름을 적은 두 컬럼 중 하나만 확정 힌트·쌍 없는 관계·빈 UNIQUE 제약·구성 없는 고유 인덱스·내림차순·INCLUDE·다른 프로젝트 격리·빈 프로젝트) / 쿼리 수 8 고정·엔티티 0 (예전 구현은 200 테이블에 200문 초과) |
| 회귀 | `ModelStructureDiagnosticIntegrationTest`(진단 = DDL 경고), DDL 골든(`TableDdlGeneratorTest`), `DdlExportIntegrationTest`, 물리 생성·매핑·M-7 테스트, `ErdIntegrationTest`·`ErdM2ImprovementIntegrationTest`, `ModuleBoundaryTest` 모두 전체 실행에 포함되어 통과 |
| 권한 (HTTP) | ADMIN·MANAGER·DBA·USER·VIEWER 200 (같은 결과), 비로그인 401, 다른 프로젝트 = 그 프로젝트만(0 테이블), 다른 프로젝트 스키마 ID = 빈 결과. 권한 회수 뒤 요청은 기존 `ProjectAccessInterceptor` 경로 (이번 변경 밖, 기존 테스트 통과) |
| 설치 | 최종 JAR 신규 설치 PostgreSQL·H2 (V0~V3 적용), 체크섬이 기존 DB 와 같음, 마이그레이션 파일 변경 없음 |

## 20. 미검증 사항

Safari·Firefox·실제 모바일 기기(Chromium 만), 장시간 운영 메모리, 다른 서버 사양. 진단 중 동시 쓰기(테이블·FK 변경)의 결과는 예전과 같은 READ COMMITTED 여러 문 조회라 따로 재현하지 않았다.

## 21. 후속 검토 사항 (이번에 하지 않음)

- 순서 : 테이블 안 참조 컬럼·관계 순서가 ID 순으로 고정된 것을 화면·문서 계약으로 삼을지 (지금은 내부 순서).
- M-2 카디널리티의 참조 키 판단(컬럼 ID)과 DDL `referencesKey`(이름)를 한 함수로 합칠지 — M-7 물리 변환 검증·AI-6 품질 진단에서 같은 규칙을 부를 때 결정. 진단 규칙은 지금 `ModelStructureDiagnosticService` 안의 정적 함수 + 공유 규칙(`TableNameResolver.pick`, `TableDdlGenerator`)이라 다른 기능이 같은 일괄 조회·규칙을 재사용할 수 있다.
- DDL Export(M-11)도 테이블마다 `referencedKeys`·`ddlExtras` 를 부른다 — 큰 프로젝트 내보내기 성능은 측정하지 않았다 (이번 범위 밖, 필요하면 별도 과제).

## 22. 최종 상태

완료 — 완료 조건(구조 분석·규칙 목록·병목·N+1 제거·결과 유지·API·DDL 정합성·격리·규모별 성능·메모리·동시성·ERD·DDL·매핑 회귀·H2/PostgreSQL 전체·단일 JAR·마이그레이션 없음) 충족.
