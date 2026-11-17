# DEC-16 원본 VIEW 정의 수집 (PostgreSQL)

> 2026-11-16 구현 (5차 통합 구현). 정책 : [pending-work.md 7.2](../pending-work.md) DEC-16 — 정의는 기본적으로 AI 에 전달하지 않고, 마스킹만 믿고 원문을 저장·노출하지 않는다.
> 원본 DB 는 읽기 전용이다. 정의 SQL 은 실행하지 않는 메타데이터로만 다룬다. 마이그레이션 `V5__source_view_definition.sql` (PostgreSQL·H2 동일).

## 1. 저장 — 원문과 해시를 나눈다

| 위치 | 담는 것 | 누가 읽는가 |
|---|---|---|
| `TB_SRC_VIEW_DFN` (VIEW 마다 0~1행, `SRC_TBL_SN` UNIQUE, VIEW 행 삭제 시 CASCADE) | 정의 원문(`DFN_CN`, 키가 있으면 `SecretCipher` 암호문 — scope `source-view-definition`/프로젝트/정의 행 ID/`definition`), `ENCPT_YN`, 해시, 이전 해시, 상태(`DFN_STTS_CD`), 글자 수, 원문 수집 일시, 마지막 변경 반영 순서 | `SourceViewDefinitionService.read` 하나 (원문), 수집 반영(해시·상태만) |
| `TB_SRC_TBL_VER` 버전 JSON · `TB_SRC_CHG_HSTRY` | `definitionHash` (`d1:` + SHA-256, 줄 끝 CRLF→LF·앞뒤 공백 정리 후) — 원문 없음 | 기존 버전·이력 API, `SourceCatalogReader` |
| `TB_SRC_VIEW_DFN_IDNTY_HSTRY` (추가만, FK 없음) | 원문 확인 감사 : 누가(등록자)·언제·그때의 해시 | 관리자 (`…/viewDefinition/accesses`) |

- 키가 없는 설치(`SECRET_KEY_NOT_CONFIGURED` 상태)는 원문을 평문으로 저장한다 (`ENCPT_YN = false`) — 비밀 키가 선택 사항이라는 기존 원칙. 접근 제어는 키와 상관없이 같다.
- `definitionHash` 는 JSON 에 **있을 때만** 들어간다 : TABLE 과 정의를 아직 못 읽은 VIEW 의 JSON·해시는 FORMAT 1 그대로라 기존 버전과 비교해도 바뀐 것으로 보이지 않는다 (`SourceCatalogCanonical.FORMAT` 은 올리지 않았다).
- 기존 VIEW 의 첫 DEC-16 수집은 TABLE 수정 이력(항목 `definitionHash`)과 새 버전 1건을 남긴다 : 정의를 처음 알게 된 것 자체를 기록한다.

## 2. 수집

- 도구 `source.view-definition.discover` (`PostgresSourceTools`) : `pg_get_viewdef(oid, false)` 를 relkind `v` 에만, 수집 범위 스키마 배열 하나로 한 번 조회. 262,144 글자를 넘으면 원문 대신 길이만 받아 `TOO_LARGE`.
- `SourceRefreshService.viewDefinitions` 가 **다른 도구를 모두 돈 뒤** 따로 부른다 (PostgreSQL 은 조회 실패로 읽기 전용 트랜잭션이 끝나므로). 실패해도 수집은 COMPLETED 로 반영된다 : `SOURCE_PERMISSION_DENIED` → 모든 VIEW `NO_PRIVILEGE`, 그 밖 → `FAILED`. 취소·제한 시간 알림은 그대로 실패. 로그는 오류 코드·VIEW 수만.
- 반영 (`SourceCatalogLoader`) : 정의를 못 읽은 VIEW 는 지난 해시를 이어 써(`PreparedCollection.Table.withDefinitionHash`) 거짓 변경을 만들지 않고, 정의 행은 상태만 바꾼다 (원문·해시 유지). 정의 행은 바뀐 것만 쓴다 (변경 없는 수집은 UPDATE 없음). VIEW 가 TABLE 이 되면 정의 행을 지운다. 사라진 VIEW 의 정의는 남는다 (행이 사라짐 표시로 남는 것과 같다).
- 원본 비교·가져오기·AI(`SourceCatalogReader.tableDetails`)·모델 Export·MCP 는 이 테이블을 읽지 않는다 (경로 차단 — 정규식 마스킹을 쓰지 않는다).

## 3. API · 화면

| API | 권한 | 내용 |
|---|---|---|
| `GET /api/source/tables/{id}` | VIEWER (기존) | `viewDefinition` : 상태·원문 유무·글자 수·수집 일시·변경 반영 순서·`change`(NEW/CHANGED/SAME/UNKNOWN)·`canRead`. 원문·해시 없음. TABLE 은 null |
| `GET /api/source/tables/{id}/viewDefinition` | USER + 서비스가 지금의 DB 로 프로젝트 관리자·ADMIN 확인 (`verifyProjectManager`) | 원문 + 확인 이력 1행. 다른 프로젝트·TABLE·원문 없음 → 404 `SOURCE_OBJECT_NOT_FOUND` |
| `GET /api/source/tables/{id}/viewDefinition/accesses` | 위와 같음 | 최근 확인 이력 20건 (원문 없음) |

- `/api/source/**` 라 요청 로그(TB_LOG)에는 본문 대신 `[PROTECTED]` 가 남는다. 응답 레코드의 `toString` 은 원문을 찍지 않는다.
- 화면 : 원본 카탈로그 > VIEW 상세의 [VIEW 정의] 패널 (`SourceCatalogPage.tsx` `ViewDefinitionPanel`) — 상태·이전 수집 대비 변경·글자 수·수집 일시, 권한 없음·실패·너무 큼 안내, 관리자만 [정의 보기](원문은 화면 상태에만, 닫으면 지움)와 그 아래 최근 원문 확인 이력(일시·계정, 6차 추가 — 방금 본 것 포함, 원문 없음). 과거 시점 보기에서는 "버전에는 해시만" 안내.

## 4. 검증

| 테스트 | 내용 |
|---|---|
| `SourceViewDefinitionIntegrationTest.collectCompareAndProtect` (실제 PostgreSQL 원본) | 수집 → COLLECTED·NEW, TABLE 은 null, 일반 응답·버전 JSON·저장 칸(암호문)에 원문 없음, TABLE 버전 JSON 그대로, 관리자 원문·확인 이력, 관리자 아닌 구성원 거절(canRead=false), 다른 프로젝트 404·이력 없음, 같은 정의 재수집 버전·이력·정의 행 UPDATE 0, 정의 변경 → 버전 1·TABLE 이력 `definitionHash` 만·CHANGED·새 원문, 로그에 원문 없음 |
| `SourceViewDefinitionIntegrationTest.noPrivilege` | 제한 계정 + `revoke execute on pg_get_viewdef` → 수집 COMPLETED·버전 0·NO_PRIVILEGE·지난 해시 유지·테이블 컬럼 그대로·로그는 코드만, 권한 복구 + 정의 변경 → COLLECTED·CHANGED |
| `V5SourceViewDefinitionMigrationTest` (H2·PostgreSQL) | V4 DB(원본 카탈로그 행 있음) → V5 만 적용·validate·기존 행 유지, UNIQUE, CASCADE, 확인 이력은 남음 |
| 고정값·정리 | 예제 인덱스 127·인덱스 컬럼 182·마이그레이션 6, `ProjectDeletionIntegrationTest`(두 테이블 목록·준비 데이터), `SourceCatalogCleanup` 두 목록 |
| 브라우저 (2026-11-16, 단일 JAR `--server.port=9951`, h2-local 임시 데이터, 컨테이너 PostgreSQL 원본) | 수집 → 정의 변경 → 재수집, VIEW 상세 패널 "정의 수집됨 · 이전 수집보다 바뀜 · 99자", [정의 보기] 원문 표시·[정의 닫기], 확인 이력 1건, 상세·변경 이력 응답에 원문 없음, TB_LOG 는 `[PROTECTED]`, 앱 로그·H2 파일에 원문 없음, 콘솔 오류 없음. 한국어 다크·라이트(1440px) 확인 |

| 6차 브라우저·보안 (2026-11-17, PostgreSQL 임시 앱, 실제 PostgreSQL 원본 `dec16b_src` — 짧은 VIEW 정의 변경 + 47,321자 VIEW, 헤드리스 Chrome) | admin 한국어·다크·1440 / 영어·라이트·390 : "정의 수집됨 · 이전 수집보다 바뀜 · 157자", 원문 조회, 확인 이력 표시, 긴 SQL 은 원문 전체가 상자 안에서 스크롤(높이 358px), 페이지 가로 넘침 0, 콘솔·5xx 없음. USER(e2e_user)·VIEWER(e2e_viewer) : [정의 보기] 없음·"프로젝트 관리자만" 안내, 원문 API·확인 이력 API 403, 상세 응답에 원문 없음. TB_LOG 는 `[PROTECTED]`(원문 0건), 앱 로그 원문 0건, 저장 칸 암호화(`v1:local:`). AI(`MetadataToolset`·`ProposalVersions` 의 `SourceCatalogReader`)·MCP·모델 Export 코드에 정의 경로 없음(core 계약에 정의 없음), 실제 Claude Code MCP 응답·Export JSON 에 원문 없음 |

- 확인하지 못한 것 : MySQL·Oracle 원본(수집 자체가 PostgreSQL 만). 실제 원본 DB 에서 `NO_PRIVILEGE` 화면 문구(자동 테스트로만).
