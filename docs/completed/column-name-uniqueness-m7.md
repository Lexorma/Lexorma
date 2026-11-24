# M-7 컬럼명 UNIQUE 및 동시성·데이터 무결성 보완

> **2026-11-22 네 번째 Flyway 합침 (사용자 결정)** : 이 문서의 마이그레이션 V5 는 `V0__init.sql`(스키마 — 끝의 '예전 증분 V5 (… 네 번째 합침)' 구간)·`V1__seed_data.sql`(예제 — 같은 이름의 구간)로 합쳐져 지금 파일로는 없다. 아래의 V5 파일명·업그레이드 검증은 기록용이다 ([completed-work.md](../completed-work.md) 마이그레이션 기록).

- 작업일 : 2026-11-22 (분석 → 정책 확정 M7-U01~U04 → 구현·검증 → 통합 후속 M7-U05·U07 → 최종 보완 M7-U06 B3·U08 이관 → 커밋 전 마무리 M7-U09~U12)
- 관련 : [physical-name-recommendation-m7.md 10장](physical-name-recommendation-m7.md) (컬럼 등록 테이블 행 잠금), pending-work [M-7]
- 마이그레이션 : **`V5__column_data_integrity.sql`** (H2·PostgreSQL 같은 뜻 — 정규화 식만 엔진별). 개발 중에 단계별로 만든 미배포 V5(`column_name_unique`)·V6(`column_required_values`)·V7(`column_name_unicode_normalization`)을 커밋 전에 하나로 합쳤다 (M7-U09, 15장) — 2~14장의 'V5·V6·V7' 은 그 개발 이력이다. 다음 스키마 변경은 V6.

## 1. 확정 정책

| ID | 내용 |
|---|---|
| M7-U01 | 같은 물리 테이블 안 컬럼명은 `UPPER(TRIM(COL_NM))` 기준으로 하나다 (대문자 규칙은 M7-U06 B3 로 로캘 중립 확장 대문자 — 14장). 앞뒤 공백(U+0020)을 지우고 대문자로 비교한다. 다른 테이블의 같은 이름은 허용한다. 저장된 컬럼명은 바꾸지 않는다 (대문자로 고치지 않는다). 단건 등록·일괄 저장·엑셀·물리명 일괄 변경·논리 → 물리 생성·모델 가져오기/복사·원본 DB 가져오기가 같은 기준으로 판단한다. 원본 카탈로그 비교(DBMS 별 접기·따옴표 식별자)는 그대로 두고, 모델 쪽 유일성만 같은 기준을 쓴다 |
| M7-U02 | DB 생성 컬럼 `TB_COL.COL_NM_CMPR_VL` (= `upper(trim(COL_NM))`, PostgreSQL `stored`) 과 UNIQUE `UX_COL_01 (TBL_SN, COL_NM_CMPR_VL)` 가 최종 방어다 |
| M7-U03 | 생성 컬럼은 기술 컬럼이다 — 애플리케이션은 쓰지도 JPA 로 매핑하지도 않는다 (DTO·API·엑셀·모델 교환·모델 버전 스냅샷·변경 이력·사용자 DDL 에 나오지 않는다). LEXORMA 예제 메타데이터에는 실제 스키마와 같도록 일반 컬럼으로 넣었다 (예제 모델은 생성 컬럼을 표현하지 못한다) |
| M7-U04 | `TBL_SN`·`COL_NM` 에 NOT NULL 을 더하지 않았다 — 조사 결과는 7장 |

이름 맞바꾸기(A ↔ B)·순환 변경은 예전처럼 거절한다 (대상 이름을 지금 다른 컬럼이 쓰므로 충돌). 임시 이름 전략은 쓰지 않는다.

## 2. 저장 구조와 V5

> 개발 이력 (예전 단계별 V5). 최종본은 통합 V5 — 15장.


- `TB_COL` : PK `COL_SN`, FK `TBL_SN → TB_TBL`·`STD_TRM_SN → TB_STD_TRM`, 이름 `COL_NM varchar(255)` (NULL 허용), 삭제는 실제 DELETE (삭제 상태 컬럼 없음). V4 까지 컬럼명 UNIQUE·인덱스는 없었다.
- V5 순서 : ① 사전 검사 — 같은 테이블에 비교 값이 같은 컬럼이 있으면 그 목록(`PRJ_SN`·`TBL_SN`·비교 값·`COL_SN` 범위·건수)을 담은 형 변환 오류로 **DDL 전에** 멈춘다 ② 생성 컬럼 ③ UNIQUE ④ COMMENT ⑤ 예제(용어 `컬럼_명_비교_값` = 컬럼·명·비교·값, 도메인 `값V255` → TB_COL 컬럼 → 엔터티 속성 → 속성 매핑 → 예제 제약조건 `UX_COL_01` → 업무 설명).
- 실패 뒤 : PostgreSQL 은 Flyway 가 한 트랜잭션으로 돌려 아무것도 남지 않는다. H2 는 첫 문장이 DDL 앞이라 스키마가 그대로다. 둘 다 데이터를 사람이 정리한 뒤 `repair` → 다시 실행하면 적용된다 (`ColumnNameUniqueMigrationTest`). 중복 데이터를 지우거나 이름을 바꾸지 않는다.
- 이름 : 표준 사전에 '키' 단어가 없어 `COL_NM_CMPR_VL`(컬럼 명 비교 값)로 했다.
- 기존 데이터 점검 (2026-11-22, 개발 DB `lexorma` 읽기 전용 트랜잭션) : 컬럼 991건(모두 예제 프로젝트 0) — 정확 중복·대소문자만 다른 중복·공백 차이 중복·NULL/빈 이름·앞뒤 공백·소문자 포함 모두 0. **운영 DB 는 확인하지 않았다** — V5 의 사전 검사가 그 역할을 한다.

## 3. 정규화의 의미 (Java · PostgreSQL · H2)

> V5 시점 기록이다. 2026-11-22 최종 보완(M7-U06 B3, V7)으로 세 엔진 모두 로캘 중립 확장 대문자가 됐다 — 14장.


- Java `column/service/ColumnNameKey.of` : U+0020 만 앞뒤에서 지우고, 글자(code point)마다 `Character.toUpperCase` (길이가 늘지 않는다).
- PostgreSQL 18.6 (`en_US.utf8`, libc) `upper` : 글자마다 — Java 와 같다 (`ß`·`ﬁ`·`ŉ` 는 그대로, `ǆ → Ǆ`, `ı → I`).
- H2 2.4 `UPPER` : Java `String.toUpperCase` — 늘어나는 글자가 있다 (`ß → SS`, `ﬁ → FI`). ASCII·한글·일반 유니코드(é, 전각 영문 등)는 셋이 같다 (`ColumnNameUniquenessIntegrationTest.normalizationMatches`).
- 탭·줄바꿈·NBSP·전각 공백은 지우지 않는다 (DB `trim` 과 같다).
- DB 에 있는 컬럼과의 비교는 같은 식을 DB 에서 쓴다 (`ColumnInfoRepository.findSameName` : `upper(trim(c.columnName)) = upper(trim(:name))`). 파일 안·요청 안 비교만 Java 값으로 한다. 두 규칙이 다른 드문 글자는 DB 판정이 최종이다 (H2 에서 `STRASSE`·`STRAßE` 는 같은 이름 → 저장 시 DB 방어가 400 으로 돌려준다).
- 최대 길이 : 비교 값도 `varchar(255)`. PostgreSQL(libc)은 길이가 늘지 않는다. H2 에서 늘어나는 글자로 255자에 가까운 이름은 생성 컬럼 길이를 넘을 수 있다 (알려진 한계, 실제 식별자에서는 나오지 않는 경우).

## 4. 경로별 처리

| 경로 | 잠금 (기존 그대로) | 중복 판단 | 오류 |
|---|---|---|---|
| 단건 등록 `insertColumn` | 테이블 행 (대기) | DB `findSameName` (V7 부터 생성 컬럼 = Java 키) | 400 `SAVED_DATA_EXISTS` (`테이블.컬럼`) |
| 중복 확인 `GET /api/selectColumn/duplicate` | — | DB `findSameName` | `duplicated` |
| 일괄 저장 `saveColumnsBulk` | 테이블 행 → 컬럼 행 ID 순 | 남는 기존 컬럼 + 새 행 (Java) | 행 오류 (아무것도 저장 안 함) |
| 엑셀 업로드 | 청크의 테이블 행 ID 순 | 파일 안 (Java) · 기존 (V7 부터 생성 컬럼 `COL_NM_CMPR_VL in` Java 키 — 14.3) | `COLUMN_DUPLICATE_IN_FILE` / `SAVED_DATA_EXISTS`, 업로드 전체 취소 |
| 물리명 일괄 변경 (`ColumnRenameService`·`PhysicalNameRenameService`) | 프로젝트 배타 → 테이블·컬럼 NOWAIT | 같은 테이블 다른 컬럼 (Java, 예전 trim + equalsIgnoreCase) | 계획 `NAME_CONFLICT`, 쓰기 `SAVED_DATA_EXISTS` |
| 논리 → 물리 생성 (B형 상속 PK 포함) | 프로젝트 배타 | 계획 안 (Java) — 새 테이블에만 만든다 | 계획 오류 |
| 모델 가져오기·복사 | 대상 프로젝트 배타 | 파일의 테이블 안 (Java, 예전 소문자 비교) | 그 TABLE 은 `EXCLUDED`·`INVALID` |
| 원본 DB 가져오기 | 테이블 행 NOWAIT | 새 컬럼의 모델 이름이 같은 테이블의 다른 모델 컬럼·다른 원본 컬럼과 같으면 | 항목 이유 `COLUMN_NAME_TAKEN` (새) |

- 모든 쓰기 경로는 마지막에 `ColumnNameKey.flush` 로 내보내 DB 의 `UX_COL_01` 위반만 같은 중복 오류로 바꾼다 (제약 이름으로 식별 — FK·NOT NULL·다른 UNIQUE 위반은 그대로 던진다, SQL·DB 문구는 사용자에게 보이지 않는다). 트랜잭션은 롤백된다.
- 일괄 저장의 'X 삭제 + X 추가' (예전부터 허용) : Hibernate 는 커밋 때 INSERT 를 DELETE 보다 먼저 보내므로, 삭제가 있고 새 행도 있을 때 삭제 뒤 한 번 `flush` 한다. 삭제 가능 검사(승인·속성 매핑·상속 PK 매핑·관계·인덱스·제약조건 — `releaseColumns`)는 그 전에 끝나므로 매핑된 X 는 재등록으로도 우회되지 않는다. 뒤의 등록이 실패하면 삭제도 함께 롤백된다.
- 원본 가져오기 `COLUMN_NAME_TAKEN` : 원본 비교로는 짝이 없는(ADDED_IN_SOURCE) 원본 컬럼이 모델 이름으로는 겹칠 때만 — 어느 쪽도 고르지 않는다.
- 화면 : 일괄 편집기의 중복 표시·건너뛰기가 같은 기준을 쓴다 (`features/column/columnNameKey.ts`, 보조 검사). 원본 가져오기 패널에 새 이유 문구(한·영). 오류 표시는 기존 공통 오류 대화상자 그대로 (입력은 지우지 않는다, 자동 이름 변경·재저장 없음).
- 오류 계약 : 기존 코드·HTTP 상태 그대로 (`SAVED_DATA_EXISTS` 400, `COLUMN_DUPLICATE_IN_FILE` 400, 일괄 저장 행 오류, 일괄 변경 409 `MODEL_CONFLICT`). 매핑 저장의 400 `SAVED_DATA_EXISTS` 도 그대로다.

## 5. 동시성·잠금 순서

- 컬럼을 만드는 모든 경로는 그 테이블 행을 먼저 잠그고(단건·엑셀·일괄 = 대기, 일괄 변경·원본 가져오기 = 프로젝트 뒤 NOWAIT) 잠금 뒤의 최신 상태로 비교한다. 새 전역 잠금은 없다. 생성·모델 가져오기는 새 테이블만 만든다.
- 여러 테이블 : 엑셀 청크·`releaseColumns` 는 테이블 ID 순. 일괄 변경·원본 가져오기는 프로젝트 행 배타 뒤 NOWAIT 라 다른 쓰기를 기다리지 않는다 (409).
- (2026-11-22 후속 M7-U07 에서 해결 — 12장) 남은 위험이었던 것 : 청크가 둘 이상인(500행 초과) 엑셀 업로드 두 개가 테이블 집합을 반대 순서로 나눠 가지면 청크 사이에서 교착이 날 수 있다 (청크마다 ID 순이지만 업로드 전체는 한 트랜잭션 — [physical-name-recommendation-m7.md 10.3](physical-name-recommendation-m7.md)). 한 청크 안은 ID 순이라 교착이 없다 (U-05).
- 관계 동기화(`syncReferenceRelations`)는 같은 트랜잭션 안, 테이블 잠금 뒤에 돈다 — 컬럼 수정과 관계 동기화가 따로 경쟁하는 경로는 없다.

## 6. 검증

- 단위 : `ColumnManageServiceImplTest` (새 조회 메서드, 엑셀 청크의 flush 2회).
- `ColumnNameUniquenessIntegrationTest` (7) : 정확·대소문자·공백 중복 거절, 다른 테이블 허용, 원래 이름 보존, 생성 값, 중복 확인 API, 프로젝트 격리, 직접 SQL 차단·생성 컬럼 쓰기 거절·이름 변경 뒤 갱신·NULL 이름·FK 위반 구분·255자 경계, Java ↔ DB 정규화 비교, 일괄 저장(기존·행끼리 중복, X 삭제 + X 추가, 매핑된 X 는 막힘), DB 방어 시 삭제까지 롤백(H2 — ß), 엑셀, 이름 변경(자기 대소문자 변경 허용·다른 컬럼과 충돌·맞바꾸기 거절).
- `ColumnInsertConcurrencyIntegrationTest` (+5, 별도 스레드·트랜잭션, 시작 신호/잠금 쥔 채 멈춤 — sleep 으로 순서를 정하지 않는다) : U-01 `CUSTOMER_ID`·` customer_id` 동시 등록 5회 반복 — 매번 하나만 성공·DB 하나, U-02 순서 있는 등록(대기 뒤 거절·다른 이름 대기 뒤 저장·다른 테이블 대기 없음), U-03 일괄 변경 ↔ 대소문자만 다른 등록(양쪽 순서, 거절된 쪽 버전·이름 그대로), U-04 일괄 저장 ↔ 단건 등록, U-05 두 테이블 엑셀 업로드 둘의 반대 행 순서(교착 없음)·겹치는 이름은 하나만·실패한 업로드는 다른 테이블 행도 남기지 않음·업로드 ↔ 단건.
- `ColumnNameUniqueMigrationTest` (2 × H2·PostgreSQL) : V4 → V5 업그레이드(데이터·체크섬·승인·수정 일시 그대로, NULL·부모 없는 컬럼 허용, 제약 존재, 기존 FK·PK 유지), 중복 데이터 DB 의 안전한 실패(목록 메시지, 스키마·데이터 그대로) → 정리 → repair → 적용.
- `ModelExchangeIntegrationTest.columnNameKeyInFile`, `V0SeedMigrationTest`·`LexormaIndexSeedTest`(마이그레이션 6개, 예제 = 실제 스키마).
- 전체 실행·E2E·성능 : 9장.

## 7. NULL 조사 (M7-U04, 정책 검토 사항 — 바꾸지 않음)

> 2026-11-22 후속(M7-U05)에서 해결 — V6 NOT NULL·`CK_COL_01`·서버 필수값 검사 (10장). 아래는 그 전 조사 기록이다.

- 실제 데이터 : 개발 DB 에 `TBL_SN`·`COL_NM` NULL 0건.
- 부모 없는 컬럼 : 화면·API 경로는 모두 테이블을 요구한다 (단건·일괄 = `TABLE_IS_NOT_DEFINED`, 엑셀 = 테이블명 필수, 생성·가져오기 = 항상 테이블). 부모를 비우는 코드는 없다.
- 빈 이름 : 엑셀(빈 칸 → 용어 없음으로 거절)·일괄 저장(용어 약어)·이름 변경(빈 이름 거절)은 막는다. **단건 등록 API 는 컬럼명 NULL·빈 문자열을 서버에서 막지 않는다** (화면은 용어 약어를 넣는다 — 기존 동작, 이번에 바꾸지 않았다).
- NULL 이 필요한 기능 : 찾지 못했다. NOT NULL 을 더하면 위 단건 등록 빈 이름이 DB 오류(500)로 바뀌므로, 서버 검증(400)을 먼저 둔 뒤 새 증분 마이그레이션으로 더하는 것을 제안한다 (결정 필요).

## 8. 하지 않은 것

FK 자동 보정, 보조 식별자 → UNIQUE 생성, 상속 A·C, 참조 키 판정 통일, Oracle 식별자 길이 정책, MySQL·MariaDB·Oracle 실제 DDL 검증, AI·MCP, ERD 편집, 모델 버전 복원, 인증 테스트 구조 개선, Flyway 통합. 사용자 정의 UNIQUE 제약(`TB_TBL_CNSTRT`)과 이 내부 무결성 제약은 다른 기능이다.

## 9. 전체 실행·E2E·성능

(아래 결과는 이 작업의 최종 코드 기준)

- H2 `./gradlew build --continue --rerun` (npm ci·`tsc -b`·`vite build`·bootJar 포함) : 1,262건 — 성공 1,202 · 실패 0 · 건너뜀 60.
- PostgreSQL 18.6 새 빈 임시 DB (기존 컨테이너 안 별도 DB, 끝나고 삭제) `./gradlew test --rerun --continue` : 1,263건 — 성공 1,224 · 실패 0 · 건너뜀 39. 직전 작업에서 한 번 간헐 실패한 `RequestLogStatusHttpTest` 는 이번 1회 실행에서 통과 (재발 없음 — 간헐 원인은 여전히 확인하지 못했다, 1회 결과라 해결로 보지 않는다).
- 새·바뀐 테스트는 PostgreSQL 에서 따로도 실행해 통과했다 (`ColumnNameUniquenessIntegrationTest` 7, `ColumnInsertConcurrencyIntegrationTest` 11, `ModelExchangeIntegrationTest` 4, `ColumnNameUniqueMigrationTest` 4, `V0SeedMigrationTest`·`LexormaIndexSeedTest`·`SubtypeIdentifierColumnMappingMigrationTest` 각 2).
- Chromium E2E (Playwright, 격리 단일 JAR `:9981`, h2-local 임시 데이터, 개발 앱·DB 와 별개) 20/20 : 등록 화면의 대소문자만 다른 이름 중복 안내, 서버 400 `SAVED_DATA_EXISTS`(`prj_nm`·` PRJ_NM `·`PRJ_NM`), 거절 뒤 기존 컬럼 그대로, 다른 테이블의 같은 이름 등록(입력 이름 그대로 저장), 일괄 저장 행 오류·일괄 편집 화면 '이미 추가됨', 물리명 일괄 변경(대소문자만 다른 기존 컬럼과 충돌 → 고를 수 없음, 다른 대상 적용 성공, 충돌 쪽 그대로), 매핑·물리 ERD 화면 정상, 한·영 × 라이트·다크 × 1440·375 (가로 넘침 없음·콘솔 오류 없음).
- 단일 JAR : 위 빌드의 `lexorma.jar` 를 격리 위치로 복사해 h2-local 로 실행 (V5 포함 신규 설치), E2E 를 그 앱으로 진행.

**성능 (PostgreSQL, `ColumnNameUniquePerformanceTest`·`ColumnInsertPerformanceTest`·`PhysicalNameRenamePerformanceTest`, `LEXORMA_PERF=true`, 같은 PC 에서 고치기 전(HEAD 내보내기 복사본, V4)과 후를 각각 새 빈 DB 로 두 번씩)** — 컬럼 300개 테이블 기준

| 항목 | HEAD 1회 / 2회 | 변경 후 1회 / 2회 | 준비 문장 수 (전 = 후) |
|---|---|---|---|
| 단건 등록 중앙값 (200회) | 18.8 / 18.9 ms | 18.5 / 19.3 ms | 17 / 회 |
| 일괄 저장 새 행 50 (5회 중앙값) | 60 / 61 ms | 61 / 64 ms | 68 |
| 일괄 저장 20 삭제 + 같은 이름 20 추가 | 47 / 47 ms | 46 / 53 ms | 44 |
| 엑셀 1,000행 · 테이블 10 (3회 중앙값) | 282 / 295 ms | 279 / 293 ms | 80 |
| 단건 등록 (빈 테이블, 200회 중앙값) | 12.5 / 12.8 ms | 13.9 / 11.6 ms | — |
| 같은 테이블 2스레드 × 100 (잠금 대기 포함) | 1,568 / 1,834 ms | 1,883 / 1,659 ms | — |
| 다른 테이블 2스레드 × 100 | 1,024 / 1,030 ms | 1,068 / 1,056 ms | — |
| 물리명 일괄 적용 500건 (적용, 커밋 전) | 588 ms | 448 ms | 785 (커밋 포함) |

문장 수는 모든 항목에서 같고 시간 차이는 두 회차 사이에서 방향이 바뀌는 잡음 범위다 — 성능 저하도 개선도 주장하지 않는다. 같은 테이블 동시 등록이 다른 테이블보다 느린 것은 기존 테이블 행 잠금(직렬화) 때문이며 전후가 같다. 새 전역 잠금·전체 테이블 조회는 없다 (중복 조회는 테이블 하나 범위, UX_COL_01 은 TBL_SN 으로 시작하는 인덱스). 운영 규모 데이터에서는 측정하지 않았다.

## 10. 통합 후속 보완 (2026-11-22, 사용자 확정 M7-U05~U07)

| ID | 내용 | 상태 |
|---|---|---|
| M7-U05 | `TBL_SN`·`COL_NM` NOT NULL, 빈 이름 CHECK, 서버 필수값 검증 | 구현·검증 완료 (V6) |
| M7-U06 | Java·PostgreSQL·H2 정규화 일관성, PostgreSQL 로캘, 생성 컬럼 길이 | 조사(11장) → 사용자 확정 **B3** → 구현·검증 완료 (14장, V7) |
| M7-U07 | 500행 넘는 엑셀 업로드의 청크 간 교착 | 재현(PostgreSQL 40P01) · 구현 · 검증 완료 (12장) |

### 10.1 V6 (`V6__column_required_values.sql`, H2·PostgreSQL 같은 내용 — V5 는 그대로)

> 개발 이력. 이 내용은 통합 V5 에 들어 있다 (15장).


- 첫 문장 : NULL 부모·NULL 이름·빈 이름 컬럼이 있으면 전체 수와 처음 50건(`COL_SN`·`PRJ_SN`·`TBL_SN`·이름)을 담은 형 변환 오류로 **DDL 전에** 멈춘다 (데이터를 고치지 않는다). 없는 부모 참조는 원래 `FK_COL_01` 이 막는다.
- `TBL_SN` NOT NULL, `COL_NM` NOT NULL, `CK_COL_01 check (trim(translate(COL_NM, chr(9)||chr(10)||chr(11)||chr(12)||chr(13), '     ')) <> '')`.
- **빈 이름의 뜻** : 빈 문자열 또는 ASCII 공백류(U+0020·탭·LF·VT·FF·CR)만으로 된 이름. `translate`·`trim`·`chr` 는 PostgreSQL·H2 에서 같은 뜻이고 로캘과 무관하다. 서버 `ColumnNameKey.isBlankName` 이 같은 기준이다 (테스트로 같은 입력을 DB·서버 양쪽에 넣어 확인). NBSP·전각 공백(U+3000) 등 다른 유니코드 공백만으로 된 이름은 막지 않는다 — 기존 식별자 허용 범위를 줄이지 않기 위해서다 (모델 가져오기는 예전부터 Java `isBlank` 로 더 넓게 막는다 — 바꾸지 않음). 중복 비교의 trim(U+0020 만)과는 다른 판단이다 : 가운데 탭(`A\tB`)·NBSP 가 들어간 이름은 그대로 허용한다.
- 예제(프로젝트 0) : `TB_COL.TBL_SN`·`COL_NM` 을 NULL 불가·필수로, 대응 논리 속성을 필수로, 예제 제약조건 `CK_COL_01`(컬럼 `COL_NM`) 추가. 예제의 CHECK 식은 읽기 쉬운 원래 식으로 적었다 (PostgreSQL 은 `TRIM(BOTH FROM translate((col_nm)::text, …))` 로 바꿔 저장한다 — 시드 검사는 제약 이름까지만 대조한다).
- 실패 뒤 : PostgreSQL 은 한 트랜잭션이라 아무것도 남지 않고, H2 는 첫 문장이 DDL 앞이라 스키마가 그대로다. 사람이 정리한 뒤 `repair` → 다시 실행 (`ColumnRequiredValuesMigrationTest`).
- 기존 데이터 점검 (2026-11-22, 개발 DB 읽기 전용) : 991건 중 NULL 부모·NULL/빈/공백 이름 0건. 운영 DB 는 확인하지 않았다 (V6 사전 검사가 그 역할).

### 10.2 서버 필수값 검증 (기존 오류 계약 재사용)

| 경로 | 부모 테이블 없음·없는 테이블·다른 프로젝트 | 컬럼명 NULL·빈·공백류 |
|---|---|---|
| 단건 등록 | 400 `TABLE_IS_NOT_DEFINED` (기존) | 400 `INVALID_PHYSICAL_MODEL` "컬럼명을 입력해주세요." (새 검사, 테이블·용어 검사 뒤) |
| 일괄 저장 | `TABLE_IS_NOT_DEFINED` (기존) | 새 행 = 용어 약어가 비면 행 오류 (아무것도 저장 안 함) |
| 엑셀 업로드 | `TABLE_IS_NOT_DEFINED` (기존) | 400 `INVALID_PHYSICAL_MODEL` "{테이블} : 컬럼명이 비어 있는 행이 있습니다." — 업로드 전체 취소 (예전에는 용어 없음 오류) |
| 물리명 변경 (`ColumnRenameService`) | — (잠근 컬럼) | 400 `INVALID_PHYSICAL_MODEL` (기존 문구, 같은 빈 이름 기준) |
| 논리 → 물리 생성·상속 B형 | 항상 새 테이블 | 용어 약어가 비면 계획 오류 (기존 `expectedColumnName`) |
| 모델 가져오기·복사 | 항상 새 테이블 | 파일 검사 `name()` (기존, Java `isBlank` — 더 넓다) |
| 원본 DB 가져오기 | 매핑된 테이블 | `OBJECT_NAME` 형식 검사 (기존) |

- 중복 확인 API(`GET /api/selectColumn/duplicate`)는 조회라 필수값 검사를 더하지 않았다 (빈 이름이면 중복 없음).
- 입력 누락을 중복 오류(`SAVED_DATA_EXISTS`)로 돌려주지 않는다. 중복은 그대로 `SAVED_DATA_EXISTS`.
- JPA : `ColumnInfo.columnName` `@Column(nullable = false)`, `tableInfo` `@JoinColumn(nullable = false)` (스키마는 Flyway 가 만든다 — `ddl-auto: validate`). 생성 컬럼은 여전히 매핑하지 않는다.
- 화면 : 컬럼 등록 폼이 약어 없는 용어를 저장 전에 알린다 (한·영). 이름은 용어 약어에서 오므로 따로 입력 칸은 없다.

## 11. 정규화 일관성 조사 (M7-U06 — 결정 전 기록, B3 로 확정 → 14장)

### 11.1 실제 비교 (2026-11-22)

환경 : Java 21 (`Character.toUpperCase` 글자 단위 = 지금 서버, `String.toUpperCase(Locale.ROOT)`), Node(브라우저와 같은 `String.prototype.toUpperCase`), H2 2.4.240, PostgreSQL 18.6 (DB 기본 `en_US.utf8` libc, `"C"`, 내장 `pg_c_utf8`·`pg_unicode_fast`, ICU 153 `und-x-icu`·`tr-TR-x-icu`), 그리고 ASCII 만 바꾸는 `translate`. 모든 열은 앞뒤 U+0020 만 지운 뒤의 결과.

| 입력 | 서버 Java (글자 단위) | PostgreSQL 기본 libc · pg_c_utf8 | Java ROOT · H2 · PG ICU und · pg_unicode_fast · JS | PG ICU tr-TR | PG "C" · translate (ASCII) |
|---|---|---|---|---|---|
| customer_id · Customer_Id | CUSTOMER_ID | 같음 | 같음 | CUSTOMER_**İ**D | CUSTOMER_ID |
| ß | ß | ß | **SS** | SS | ß |
| ẞ · İ | 그대로 | 그대로 | 그대로 | 그대로 | 그대로 |
| ı | I | I | I | I | **ı** |
| σ · ς | Σ | Σ | Σ | Σ | **σ · ς** |
| é · ａｂｃ · 𐐨(보조 평면) · ǆ | É · ＡＢＣ · 𐐀 · Ǆ | 같음 | 같음 | 같음 | **그대로** |
| ﬁ · ŉ · ΐ | 그대로 | 그대로 | **FI · ʼN · Ϊ́ (3글자)** | 같은 확장 | 그대로 |
| ᾳ | ᾼ | ᾼ | **ΑΙ** | ΑΙ | ᾳ |
| e + U+0301 (결합) | E + U+0301 | 같음 | 같음 | 같음 | E + U+0301 |
| 고객번호 · 顧客番号 · 客户编号 | 그대로 | 그대로 | 그대로 | 그대로 | 그대로 |
| 탭·NBSP·U+3000 이 앞뒤에 | 지우지 않음 | 같음 | 같음 | 같음 | 같음 |

- 결론 : (1) 지금 서버(Java)와 개발 PostgreSQL(libc)은 모든 입력에서 같다. (2) H2·ICU·`pg_unicode_fast`·브라우저는 **글자가 늘어나는 대문자**(ß → SS 등)를 써서 서버와 다르다. (3) 터키어 ICU 는 ASCII `i` 까지 바꾼다. (4) ASCII 전용(`translate`·`"C"`)은 모든 엔진이 같지만 é/É, σ/Σ, 전각 영문 같은 비ASCII 대소문자를 같은 이름으로 보지 않는다.
- **PostgreSQL 로캘 의존성 (실측)** : V5 의 `upper(trim(COL_NM))` 는 COLLATE 없이 DB 기본 로캘을 따른다. 기본 로캘 공급자가 ICU 인 DB(`locale_provider icu`)를 따로 만들어 보니 `upper('straße') = 'STRASSE'` — 같은 V5 가 DB 를 만든 방법에 따라 다른 판정을 한다. 개발 DB 는 libc 라 지금은 Java 와 같다. 운영 DB 의 로캘 공급자는 확인하지 않았다.
- **생성 컬럼 길이 (실측)** : `COL_NM_CMPR_VL varchar(255)`. 글자가 늘어나는 환경(H2, ICU 기본 DB)에서는 255자 이내 이름도 비교 값이 255자를 넘어 저장이 `22001 value too long` 으로 실패한다 (ß 200자 → 400자). 이 오류는 이름 충돌이 아니라 그대로 500 이 된다. PostgreSQL libc·`pg_c_utf8`·ASCII 방식에서는 늘어나지 않는다 (ß 255자 → 255자, 510바이트).
- 지금 서버의 판정은 '같은 테이블에 이미 있는 이름' 조회를 DB 의 `upper(trim())` 로 하므로 엔진별로 다르고, 요청·파일 안 비교(Java)는 글자 단위라 H2·ICU DB 에서는 서버가 통과시킨 것을 DB UNIQUE 가 막는다 (같은 400 으로 바뀐다 — 9장 롤백 테스트).

### 11.2 방안 비교 (모두 구현하지 않음 — 사용자 결정 필요)

| 방안 | 내용 | 일치 범위 | 바뀌는 판정 | 비용·위험 |
|---|---|---|---|---|
| A 유지 | 지금 그대로 + 생성 컬럼 길이만 넓힘 (`varchar(765)` — 대문자 확장은 글자당 최대 3글자, 최대 약 2,300바이트로 PostgreSQL 인덱스 한도 2,704바이트 안) | 서버 ↔ PostgreSQL libc 만 | 없음 | H2·ICU DB 에서 Java 와 DB 가 계속 다름 (DB 가 최종) — 문서화한 불일치가 남는다 |
| B1 ASCII 대소문자만 | 생성 컬럼 `translate(trim(COL_NM), 'a…z', 'A…Z')`, Java·화면도 a–z 만 | **Java·TS·H2·PostgreSQL 모든 버전·로캘 완전 일치**, 길이 그대로 | é/É, σ/Σ, ＡＢＣ/ａｂｃ, ǆ/Ǆ 등 비ASCII 대소문자쌍이 다른 이름이 된다 | 식별자 허용 범위는 그대로 (비영문 이름 계속 허용). PostgreSQL 이 따옴표 없는 식별자를 접는 규칙(ASCII 만)과 같다. MySQL·Oracle 은 é/É 를 같은 이름으로 볼 수 있어 그 DDL 에서는 충돌 가능 |
| B2 글자 단위 유니코드 (지금 Java 의 뜻) | PostgreSQL `upper(trim(COL_NM) collate "pg_c_utf8")` (로캘 무관 · 확인 : 생성 컬럼 가능) | Java ↔ PostgreSQL 일치 | 지금과 같음 (libc DB) | **PostgreSQL 17 이상 필요**. H2 는 같은 함수가 없어 Java 함수를 DB 에 등록(CREATE ALIAS)해야 하는데 생성 컬럼에서 가능한지·H2 파일 DB 가 앱 클래스에 묶이는지 미검증. 유니코드 버전 차이(JDK 21 = 15.0, PostgreSQL 내장 = 16) |
| B3 글자가 늘어나는 유니코드 | PostgreSQL `upper(trim(COL_NM) collate "und-x-icu")` (확인 : 생성 컬럼 가능), Java `toUpperCase(Locale.ROOT)`, 생성 컬럼 `varchar(765)` | **Java·TS·H2 동일 구현(JDK·JS)**, PostgreSQL ICU 와 표본 모두 일치 | ß=SS, ﬁ=FI, ᾳ=ΑΙ 가 같은 이름이 된다 (지금 libc DB 와 다름 — 기존 데이터에 이런 쌍이 있으면 V 마이그레이션 사전 검사가 막는다) | ICU 를 넣어 빌드한 PostgreSQL 필요 (공식 이미지·16 이상 기본 빌드는 포함). ICU 판 올림 때 새 글자 판정이 바뀌면 저장된 비교 값이 낡는다 (PostgreSQL 의 일반 ICU 인덱스 위험과 같음) |

권장 : **B3** — 유니코드 대소문자 무시라는 지금 정책의 뜻을 유지하면서 Java·화면·H2 가 같은 구현을 쓰고 PostgreSQL 도 DB 기본 로캘과 무관하게(명시 COLLATE) 같아진다. 판정이 바뀌는 것은 늘어나는 대문자(ß·합자·그리스 일부)뿐이다. 더 단순·견고함을 우선하면 B1 (단, 비ASCII 대소문자 무시를 포기). 결정 전까지 V5 그대로다.

### 11.3 지금 한 것 (의미를 바꾸지 않는 범위)

- 화면 `columnNameKey.ts` : JS `toUpperCase` 가 늘어나는 대문자를 써서 서버가 허용하는 이름(STRAßE ↔ STRASSE)을 '이미 있음' 으로 막았다. 글자 하나의 대문자가 한 글자일 때만 바꾸도록 고쳐, 서버(Java 글자 단위)와 같거나 덜 잡게 했다 (표본 26개 중 25개 같음, ᾳ 하나만 화면이 덜 잡음 — 서버가 최종). 서버를 입력마다 부르지 않는다.

## 12. 엑셀 업로드 잠금 순서 (M7-U07)

### 12.1 구조와 문제

- 업로드는 POI SAX 로 시트를 읽어 500행 청크마다 처리한다 (메모리에는 한 청크만). `uploadColumnExcelFile` 전체가 **한 트랜잭션**이고, 청크마다 그 청크의 테이블 행을 ID 순으로 `findForUpdate` 한 뒤 기존 컬럼 조회 → 행 검증 → `saveAll` → `ColumnNameKey.flush` → 관계 동기화 → flush·clear 한다. 오류는 업로드 전체 롤백.
- 잠금은 커밋까지 유지되므로 청크 사이에서는 순서가 뒤집힐 수 있다 : 업로드 A (청크 1 = T1, 청크 2 = T2) · 업로드 B (청크 1 = T2, 청크 2 = T1).
- **재현 (고치기 전 코드, PostgreSQL 18.6)** : `ExcelUploadLockOrderIntegrationTest.multiChunkOppositeOrder` — 테이블 행 잠금 호출에 테스트 전용 대기점(CyclicBarrier)을 걸어 각 업로드가 두 번째 테이블을 잠그기 직전에 서로를 기다리게 하면, 한 업로드가 `SQLSTATE 40P01` (deadlock detected) 로 실패하고 다른 하나는 성공했다 (반복 0회차에서 바로 재현). sleep 으로 순서를 맞추지 않았다.

### 12.2 보완

- 청크 처리 전에 파일을 한 번 더 SAX 로 읽어 **대상 테이블명만** 모은다 (행은 메모리에 두지 않는다) → 같은 테이블명 해석(`resolveUploadTables`, 청크 처리와 같은 `TableNameResolver`)으로 ID 를 구해 **업로드 전체의 대상 테이블 행을 ID 순으로 먼저 잠근다**. 그 뒤 청크 처리는 예전 그대로이며, 청크의 테이블은 이미 잠겨 있어 다시 잠그지 않는다 (먼저 모은 집합에 없는 테이블이 나오면 그때 잠근다 — 생길 수 없지만 방어).
- 잠금 뒤 기존 컬럼·중복은 예전처럼 청크마다 최신 상태로 다시 본다. 없는 테이블·여러 스키마 테이블 오류도 예전처럼 그 행에서 낸다 (처리 순서·오류 문구·청크 크기·저장 방식·전체 롤백 그대로). 새 설정·전역 잠금·재시도는 없다.
- 결과 : 두 업로드 모두 T1 → T2 순서로 잠그고 (테스트가 잠금 순서를 기록해 확인), 한쪽이 T1 에서 기다릴 뿐 교착이 없다 — H2·PostgreSQL, 3회 반복.

### 12.3 다른 경로와의 잠금 순서

| 경로 | 잠금 |
|---|---|
| 단건 등록·일괄 저장 | 테이블 행 1개 (대기) → (일괄) 컬럼 행 ID 순 |
| 컬럼 삭제 (`releaseColumns`) | 테이블 행 ID 순 (대기) |
| 엑셀 업로드 | **업로드 전체 테이블 행 ID 순 (대기)** — 이번 변경 |
| 물리명 일괄 변경·원본 가져오기·논리 → 물리 생성 | 프로젝트 행 배타 → 테이블·컬럼 NOWAIT (409) — 그대로. 기다리지 않으므로 업로드와 교착하지 않고 409 가 된다 |
| 관계 동기화 | 명시 잠금 없음 (같은 트랜잭션, 테이블 잠금 뒤) |

- 남은 위험 (바꾸지 않음 — 2026-11-22 M7-U08 로 '기존 물리 테이블 FK 보정' 후속에 이관, 14.12) : 관계 동기화가 만들거나 바꾸는 `TB_TBL_REL` 행은 FK 로 **대상 테이블 행에 KEY SHARE 잠금**을 건다 (PostgreSQL 의 FK 동작). 업로드 A 가 T1 컬럼에 '참조 테이블 = T3' 을 적고 업로드 B 가 T3 를 잠근 채 T3 컬럼에 '참조 테이블 = T1' 을 적는 식으로, 잠근 집합 밖의 참조 대상이 서로 엇갈리면 교착이 날 수 있다. 엑셀만이 아니라 단건 등록·일괄 저장에도 있는 기존 구조다. 참조 대상까지 미리 잠그면 업로드와 무관한 테이블의 다른 쓰기까지 막으므로 이번에는 하지 않았다. 관계 동기화 중 관계 행 대기([physical-name-recommendation-m7.md 9.5](physical-name-recommendation-m7.md))도 그대로다.

### 12.4 검증

- `ExcelUploadLockOrderIntegrationTest` (H2·PostgreSQL) : A 다중 청크 반대 순서 (3회, 잠금 순서·데이터 수 확인), B 같은 테이블에 대소문자만 다른 이름을 넣는 다중 청크 업로드 둘 — 하나만 성공, 실패한 쪽은 첫 청크 500행도 남지 않음, F 첫 청크 정상·뒤 청크 빈 컬럼명 → 400 `INVALID_PHYSICAL_MODEL`·전체 롤백, C·D 겹치지 않는 테이블·다른 프로젝트 업로드는 기다리지 않고 둘 다 성공.
- `ColumnInsertConcurrencyIntegrationTest` U-06 (+1) : 업로드 ↔ 일괄 저장 (둘째 대기 뒤 최신 상태로 거절), 업로드 ↔ 컬럼 삭제 (둘째 대기 뒤 성공). 업로드 ↔ 단건 등록·물리명 변경은 기존 U-05·`uploadAndRename`.
- 잠금 대기 시간 제한과 교착은 구분해 기록한다 : 고치기 전 재현은 교착(40P01)이었고 시간 제한은 없었다. 고친 뒤 실행에서 둘 다 없었다.

## 13. 통합 후속 검증

- 새 테스트 : `ColumnRequiredValuesMigrationTest` 6 (V5 → V6 · V4 → V6 · 필수값 위반 데이터의 안전한 실패 → 정리 → repair → 재실행, H2·PostgreSQL), `ColumnRequiredValuesIntegrationTest` 3 (부모 없음·없는 테이블·다른 프로젝트 = `TABLE_IS_NOT_DEFINED`, NULL·빈·공백·탭 이름 = `INVALID_PHYSICAL_MODEL`, 한국어·일본어·중국어 이름 저장, JPA·직접 SQL 의 NOT NULL·CHECK 거절과 이름 충돌 구분, 일괄 저장 행 오류, 이름 변경 빈 이름), `ExcelUploadLockOrderIntegrationTest` 3, `ColumnInsertConcurrencyIntegrationTest` U-06. 바꾼 테스트 : 마이그레이션 수 6 → 7, V5 테스트는 V5 까지만, `ColumnNameUniquenessIntegrationTest` 의 NULL 이름 행은 이제 NOT NULL 거절을 확인, `StandardDomainDuplicateIntegrationTest` 픽스처의 부모 없는 컬럼에 빈 테이블을 둠 (V6 로 부모가 필수 — 진단 의미는 그대로).
- 엑셀 교착 : 고치기 전 코드로 PostgreSQL 에서 `SQLSTATE 40P01` 재현 → 고친 뒤 H2·PostgreSQL 에서 3회 반복 모두 교착·시간 제한 없음.

**성능 (PostgreSQL 18.6, 같은 PC, 보완 전 = V5 + 청크별 잠금 복사본 / 보완 후, 새 빈 DB 각 2회 — 1회차는 미리 읽기에서 모든 열을 만들던 첫 구현, 2회차는 테이블명 열만 만드는 최종 구현)**

| 항목 (3회 중앙값) | 보완 전 1 / 2 | 보완 후 1 (첫 구현) / 2 (최종) | 준비 문장 수 (전 → 후) | 할당 MB (전 → 후 최종) |
|---|---|---|---|---|
| 엑셀 100행 · 테이블 1 | 33 / 37 ms | 44 / 35 ms | 22 → 22 | 10 → 11 |
| 엑셀 500행 · 테이블 1 | 109 / 121 ms | 134 / 129 ms | 38 → 38 | 42 → 48 |
| 엑셀 501행 · 테이블 1 (청크 2) | 158 / 154 ms | 178 / 155 ms | 56 → 55 | 47 → 54 |
| 엑셀 1,000행 · 테이블 1 | 211 / 218 ms | 252 / 224 ms | 76 → 75 | 88 → 100 |
| 엑셀 2,000행 · 테이블 4 (청크 4) | 343 / 348 ms | 384 / 364 ms | 140 → 137 | 167 → 190 |
| 엑셀 1,000행 · 테이블 10 (컬럼 300개 테이블) | 310 / 315 ms | 295 / 300 ms | 80 → 79 | — |
| 엑셀 1,000행 단독 / 겹치지 않는 1,000행 둘 동시 | 214·204 / 215·190 ms | 240·250 / 231·216 ms | — | — |
| 단건 등록 중앙값 (컬럼 300개) | 17.7 / 18.2 ms | 19.5 / 18.1 ms | 17 → 17 | — |
| 일괄 저장 50 / 삭제 20 + 재등록 20 | 61·45 / 60·51 ms | 59·48 / 61·49 ms | 68·44 → 68·44 | — |
| 같은 테이블 2스레드 × 100 / 다른 테이블 | 1,570·1,067 / 1,654·1,042 ms | 1,879·1,030 / 1,449·1,015 ms | — | — |

- 엑셀은 파일을 한 번 더 읽어 할당이 늘었다 (첫 구현 약 +55%, 테이블명 열만 만드는 최종 구현 약 +13%). 시간 차이는 같은 회차 안에서 ±10ms 안팎으로 잡음과 구분되지 않는다 — 개선·저하를 단정하지 않는다. 문장 수는 청크마다 다시 잠그지 않아 1~3개 줄었다. 겹치지 않는 업로드 둘은 동시에 돌려도 단독과 비슷한 시간에 끝난다 (서로 직렬화되지 않음). 전체 잠금 유지 시간은 업로드 전체(첫 테이블 잠금 ~ 커밋)로 예전과 같고, 뒤 청크의 테이블을 더 일찍 잠그는 만큼만 길어진다 (따로 재지 않았다).
- 정규화 방안은 구현하지 않았으므로 그에 따른 문장 수·메모리 변화는 측정 대상이 아니다.

## 14. 최종 보완 — 유니코드 확장 대문자 (2026-11-22, 사용자 확정 M7-U06 B3 · M7-U08 이관)

### 14.1 확정 정책

| ID | 내용 | 상태 |
|---|---|---|
| M7-U06-01 | 비교 값 = 앞뒤 U+0020 만 제거 + **로캘 중립 Unicode 확장 대문자** (글자 수가 늘 수 있다 : ß → SS, ﬁ → FI, ΐ → Ϊ́). 대소문자 무시, 실제 컬럼명·식별자 허용 범위·같은 테이블 유일성·다른 테이블 허용은 그대로. Case Folding · NFC · NFKC 가 아니다 | 구현·검증 완료 |
| M7-U06-02 | `COL_NM varchar(255)` 그대로, `COL_NM_CMPR_VL varchar(765)`, `UX_COL_01` 그대로 | 구현·검증 완료 |
| M7-U06-03 | 단일 JAR · H2 Standalone · PostgreSQL · Docker · 최소 설정 유지. ICU 를 따로 설치하거나 DB 를 다시 만들지 않는다 | 검증 완료 (PostgreSQL 18.6 공식 이미지 — 14.3) |
| M7-U06-04 | Java · TypeScript · H2 · PostgreSQL 일관성, PostgreSQL 기본 로캘 무관 | 검증 완료 · 제한 있음 (Unicode 판 차이 — 14.6) |
| M7-U08 | 관계 동기화 FK KEY SHARE 교착 가능성은 구현하지 않고 '기존 물리 테이블 FK 보정' 후속으로 이관 | 기록 완료 (14.12) |

확정 비교 예 (모두 테스트로 확인) : `CUSTOMER_ID`=`customer_id`, `Customer_Id`=`CUSTOMER_ID`, ` CUST_NO `=`CUST_NO`, `STRAßE`=`STRASSE`, `ß`=`SS`, `ﬁ`=`FI`, `é`=`É`, `σ`=`Σ`, `ς`=`Σ`, `고객번호`=`고객번호`, `i`=`I`=`ı`, `ſ`=`S`, `ａｂｃ`=`ＡＢＣ` / 다름 : `고객번호`≠`주문번호`, `CUST_NO`≠`CUSTNO`, `İ`≠`I`, `ẞ`(U+1E9E)≠`SS`, `é`≠`e`+U+0301 (NFC 없음), `ＡＢＣ`≠`ABC` (NFKC 없음), `K`(켈빈 U+212A)≠`K` (Case Folding 없음), NBSP·탭이 앞뒤에 있는 이름 ≠ 없는 이름.

### 14.2 지원 환경 (확인한 것)

| 항목 | 값 | 근거 |
|---|---|---|
| PostgreSQL 지원 범위 | 문서상 "18 recommended" (README), 버전 하한은 적혀 있지 않다. Compose 2종 모두 `postgres:18.6`. CI(`dev-test.yml`)는 H2 만 (PostgreSQL 없음) | README §4·§8, `lexorma/docker-compose*.yml`, `.github/workflows/dev-test.yml` |
| 실제 검증한 PostgreSQL | 18.6 (Debian 18.6-1.pgdg13+2, aarch64) — 로컬 공식 이미지 `postgres:18.6` 로 새로 띄운 격리 컨테이너 | `select version()` |
| PostgreSQL ICU | 이미지에 `libicu76 76.1-4` (Unicode 16.0), `pg_collation` 에 ICU 콜레이션 871개, `und-x-icu`·`unicode`(ICU 153.128)·`tr-TR-x-icu` 미리 정의 | `dpkg -l`, `pg_collation` |
| Java | 프로젝트 툴체인 21, 테스트 JVM OpenJDK 21.0.2 (Unicode 15.0). Docker 실행 이미지는 `eclipse-temurin:21-jre` (판 미확인 — 이미지를 받지 않았다). Standalone 은 사용자의 Java 21+ (판마다 Unicode 판이 다를 수 있다) | `build.gradle`, `Dockerfile*` |
| H2 | 2.4.240 | Gradle 캐시 |
| Node · 브라우저 | 로컬 Node 26.13.1 (ICU 79.1), CI Node 22, Docker 빌드 Node 24.11.1, Chromium 151.0.7922.34 (Playwright) | `process.versions` |

### 14.3 PostgreSQL — ICU 와 생성 컬럼

- 식 : `COL_NM_CMPR_VL varchar(765) collate "C" generated always as (upper(trim(COL_NM) collate "und-x-icu")) stored`. `upper` 는 IMMUTABLE 이라 생성 컬럼에 쓸 수 있고(실행 확인), **대문자 변환에만** `und-x-icu`(ICU 루트 로캘)를 명시한다.
- **UNIQUE 비교 collation 은 따로** : 컬럼 자체는 `collate "C"` — `UX_COL_01` 은 변환한 문자열을 바이트로 비교한다 (`und-x-icu` 는 결정적 collation 이라 같음 판정도 바이트지만, "C" 로 두면 인덱스 순서가 ICU·libc 판에 매이지 않는다). 악센트·구두점 무시 같은 비결정적 동등성은 없다 (`é`≠`e`+U+0301 확인).
- **DB 기본 로캘과 무관 (실측)** : 같은 표본·전 코드 포인트 비교에서 기본 로캘이 libc `en_US.utf8`, ICU `und`, ICU `tr-TR`, builtin `C.UTF-8` 인 DB 네 개 모두 결과가 똑같다. COLLATE 없는 `upper()` 는 DB 마다 다르다 (Java 와 다른 코드 포인트 수 : libc 129 · builtin 129 · ICU und 27 · ICU tr-TR 28 = 27 + `i → İ`). 그래서 기존 DB 를 다시 만들 필요가 없다.
- 애플리케이션의 기존 행 조회도 DB `upper()` 를 쓰지 않는다 : `ColumnInfoRepository.findSameName`·`findTableColumnKeys` 를 네이티브 쿼리로 바꿔 **DB 가 계산해 둔 `COL_NM_CMPR_VL` 을 Java 비교 값과** 비교한다 (`UX_COL_01 (TBL_SN, COL_NM_CMPR_VL)` 인덱스 사용). 예전 JPQL `upper(trim(…))` 는 DB 기본 로캘을 따랐다. 생성 컬럼은 여전히 JPA 에 매핑하지 않는다 (조회는 ID 만 받아 `findAllById`).
- ICU 가 없는 빌드 : V7 첫 문장이 `und-x-icu` 가 없으면 이유를 담은 오류로 DDL 전에 멈춘다 (확인 : 상수 cast 는 PostgreSQL 이 계획 단계에서 미리 계산해 조건과 무관하게 실패하므로 집계식으로 썼다). PostgreSQL 16 이상 기본 빌드와 공식 이미지는 ICU 를 포함한다 — **18.6 외의 판·ICU 없는 빌드는 실행해 보지 않았다**.
- 생성 컬럼(12+)·`und-x-icu`(ICU 빌드 10+)·`collate "C"` 는 V5 가 이미 요구한 12+ 범위 안이다. B2(`pg_c_utf8`, 17+)·`pg_unicode_fast`(18+) 는 쓰지 않았다.

### 14.4 H2 · Java · TypeScript

- **H2 `UPPER` 는 JVM 기본 로캘을 따른다 (새로 찾음)** : H2 2.4 `StringFunction1` 은 `String.toUpperCase()` (로캘 인자 없음 — 소스 주석에도 'locale specific' TODO). JVM 이 터키어 로캘이면 `i → İ` 라 V5 의 H2 에서는 `i_x` 와 `I_X` 가 다른 이름이었다 (실측). 그래서 V7 은 H2 자체 함수 `org.h2.util.StringUtils.toUpperEnglish`(= `toUpperCase(Locale.ENGLISH)`, 대소문자 변환은 ROOT 와 같다 — Java 의 특수 규칙은 tr·az·lt 뿐)를 별칭 `FN_UPPER_ROOT` 로 등록해 쓴다. 애플리케이션 클래스가 아니라 H2 jar 안의 공개 함수라 H2 콘솔·도구로 열어도 된다 (파일 DB 를 터키어 로캘 JVM 으로 만들고 한국어 로캘로 다시 열어 같은 판정 확인). 단, H2 내부 유틸 함수라 H2 판 올림 때 이름이 바뀌면 생성 컬럼 계산이 깨진다 — H2 를 올릴 때 확인할 항목.
- Java `ColumnNameKey.of` = `trim(U+0020)` + `toUpperCase(Locale.ROOT)` (문자열 전체 — 글자 단위 `Character.toUpperCase` 가 아니다). 빈 이름 판정 `isBlankName`(ASCII 공백류, V6 `CK_COL_01`)은 그대로이고 중복 비교의 trim 과 따로다.
- TypeScript `columnNameKey.ts` : JS `toUpperCase` 도 로캘 중립 확장 대문자지만 브라우저의 Unicode 판이 서버보다 새로우면 서버가 모르는 짝을 화면만 같은 이름으로 볼 수 있다 (Chromium 151 의 `toUpperCase` 는 Java 21 과 55개 코드 포인트, Node 26 은 76개가 다르다 — 모두 Java 가 대문자를 모르는 새 글자). 그래서 오래전에 채워진 블록(Basic Latin~Latin Extended-B, 수식 문자·결합 부호, 그리스, 키릴, 아르메니아, Latin Extended Additional·Greek Extended, 합자 FB00–FB17, 전각 FF00–FFEF)의 글자이고 결과도 모두 그 블록 안일 때만 바꾼다 — 새 대소문자 짝은 새 코드 포인트(블록 밖)에 생기므로 화면은 서버보다 덜 잡을 수는 있어도 더 잡지 않는다. 문자별 예외 목록은 없다. 전 코드 포인트 실측 : Node·Chromium(ko·tr·en 로캘) 모두 서버보다 더 잡는 글자 0, 덜 잡는 글자 771 (조지아·체로키·데저렛·글라골·로마 숫자·원 문자 등 — 서버가 최종 판정). 컬럼 등록 화면의 중복 표시는 원래 서버 중복 확인 API 를 쓴다 (입력마다 부르지 않는다는 기존 구조 그대로).

### 14.5 전 코드 포인트 · 여러 글자 비교 (자동)

- 단일 코드 포인트 : 유효한 Unicode scalar value 1,112,063개 (U+0001–U+10FFFF, 서로게이트 U+D800–DFFF 2,048개 제외 — scalar value 가 아님, NUL 제외 — PostgreSQL 문자열에 저장할 수 없음). 미할당·사용자 정의·비문자 코드 포인트도 넣었다 (Lexorma 는 컬럼명 글자를 제한하지 않는다 — 빈 이름만 막는다). PostgreSQL 은 20,000개씩 배열 한 번(`unnest`)으로 조회 — 코드 포인트마다 SQL 을 보내지 않았다.

| 비교 (Java 21 `toUpperCase(Locale.ROOT)` 기준) | 다른 코드 포인트 |
|---|---|
| H2 `FN_UPPER_ROOT` (같은 JVM) | 0 |
| PostgreSQL 18.6 `upper(x collate "und-x-icu")` — libc · ICU und · ICU tr-TR · builtin 기본 로캘 DB 네 곳 | 27 (네 곳 모두 같은 27개) |
| Node 26 `toUpperCase` / Chromium 151 `toUpperCase` | 76 / 55 |
| 화면 `columnNameKey` (Node·Chromium) | 서버보다 더 잡음 0 · 덜 잡음 771 |

- 여러 글자 문자열 : 대소문자가 있는 글자·결합 부호·공백류·한중일 표본 5,529개(위 Node 차이 76개 제외)로 만든 길이 1~20 무작위 문자열 300,000개 — Java ↔ H2 별칭 0, ↔ PostgreSQL ICU (libc DB · 터키어 DB) 0, ↔ Node 0. Java ROOT·ICU 루트의 대문자 변환은 문맥 규칙이 없어(그리스어 강세 제거는 `el` 로캘에만, final sigma 는 소문자 변환에만) 글자별 결과를 이은 것과 같다 — 다만 **무작위 표본이 모든 여러 글자 문자열의 일치를 증명하지는 않는다**.

### 14.6 Unicode 판 차이와 불일치 목록

PostgreSQL ICU 76 (Unicode 16.0) 만 대문자를 아는 27개 — 모두 Unicode 16.0 에서 새로 생긴 대소문자 짝이다 (Java 21 = Unicode 15.0 은 그대로 둔다).

| 입력 | 코드 포인트 | Java 21 · H2 | PostgreSQL ICU 76 |
|---|---|---|---|
| ƛ (λ 획) | U+019B | 그대로 | U+A7DC Ƛ |
| ɤ | U+0264 | 그대로 | U+A7CB |
| ᲊ (키릴 확장) | U+1C8A | 그대로 | U+1C89 |
| ꟍ · ꟛ | U+A7CD · U+A7DB | 그대로 | U+A7CC · U+A7DA |
| 가라이 문자 소문자 22개 | U+10D70–U+10D85 | 그대로 | U+10D50–U+10D65 |

- 영향 : 이 글자를 쓴 두 이름(예 `ƛ_CD` 와 `Ƛ_CD`)은 **PostgreSQL 에서는 같은 이름, H2(Java 21)에서는 다른 이름**이다. PostgreSQL 에서 애플리케이션 검사(Java)는 통과하고 DB `UX_COL_01` 이 막아 `ColumnNameKey.flush` 가 같은 400 `SAVED_DATA_EXISTS` 로 바꾼다 — 트랜잭션 전체 롤백 (`ColumnNameUniquenessIntegrationTest.bulkRollbackOnDatabaseConflict` 가 이 글자로 재현). 화면은 두 이름을 다르게 본다 (막지 않는다). ASCII·라틴·그리스·키릴·한중일·전각·합자 등 기존 글자에는 영향이 없다. 기존 식별자 허용 범위에 들어가는 글자이고 삭제·제한하지 않았다.
- 기존 데이터 : V7 사전 검사는 PostgreSQL 이 계산한 새 값으로 중복을 찾으므로 업그레이드에서 놓치지 않는다. 개발 DB(V4, libc, 991건)는 비ASCII 이름 0건 · 새 기준 중복 0 · 최대 비교 값 27자 (읽기 전용 확인).
- 판 올림 위험 (운영 기록) : 저장된 생성 값은 쓸 때 계산되고 자동으로 다시 계산되지 않는다. PostgreSQL 이미지·OS 의 ICU 판이 올라가거나 H2 를 쓰는 JVM 의 Java 판이 올라가 새 대소문자 짝이 생기면, 이미 저장된 값과 새로 계산한 값이 달라 그 글자들에서 중복을 놓칠 수 있다 (컬럼 `collate "C"` 라 인덱스 순서는 깨지지 않는다). 대응 후보 : 판 올림 뒤 같은 V7 식으로 새 값을 계산해 중복을 점검하고, 필요하면 비교 값을 다시 계산(행을 같은 이름으로 다시 쓰기)한 뒤 `ALTER COLLATION "und-x-icu" REFRESH VERSION` — **절차는 조사만 했고 자동 재정규화 기능은 만들지 않았다 (미검증)**.

### 14.7 생성 컬럼 길이 · 최대 바이트 · 인덱스 한도

- 한 코드 포인트의 대문자는 최대 3 코드 포인트·UTF-16 3단위·UTF-8 6바이트 (전 코드 포인트 계산, 최댓값 ΐ U+0390 → U+0399 U+0308 U+0301). 그래서 255자 이름의 비교 값은 최대 765자(PostgreSQL 코드 포인트·H2 UTF-16 단위 모두)·1,530바이트다. 잘라내지 않는다.
- 길이 단위 차이 (기존 그대로) : PostgreSQL `varchar(255)` 는 코드 포인트, H2 는 UTF-16 단위라 보조 평면 글자(4바이트)는 H2 에서 127자까지다 (V7 이 줄인 것이 아니다 — 원래 허용 범위 그대로).
- 실제 저장 (H2·PostgreSQL, `ColumnNameUnicodeMigrationTest.maximumLengthAndBytes`) — 모두 저장·이름 그대로·같은 입력 재등록 거절, 256자는 예전처럼 거절 :

| 입력 (255자) | 비교 값 코드 포인트 | UTF-8 바이트 |
|---|---|---|
| ASCII · 한글 | 255 · 255 | 255 · 765 |
| ß 200자 / ß 255자 | 400 / 510 | 400 / 510 |
| ﬃ (3글자) · ᾳ (2글자) | 765 · 510 | 765 · 1,020 |
| ΐ (3글자·6바이트) | 765 | 1,530 |
| 대문자가 4바이트 이상인 BMP 글자 무작위 혼합 (압축 어려움) | 555 | 1,240 |
| 다국어 혼합 (한·일·중·전각·Σ·ß·결합) | 278 | 650 |
| 보조 평면 𐐨 (PostgreSQL 만) | 255 | 1,020 |
| `é`×255 ↔ `É`×255 | 같은 이름 (거절) | — |

- PostgreSQL 인덱스 한도 : block size 8,192, B-tree 항목 최대 2,704바이트. 비교 값 최대 1,530바이트 + `TBL_SN` 8바이트 + 머리는 한도 안이다. 압축을 끈(`storage plain`) 같은 정의의 표에 ΐ·ΰ 무작위 255자(1,530바이트, 반복 압축 효과 없음)를 넣어 인덱스에 저장·같은 값 거절을 확인했다. 한도를 넘는 값은 `index row size 2816 exceeds btree version 4 maximum 2704` 로 실패한다 (일반 컬럼 1,400자로 오류 형태만 확인 — 컬럼명으로는 만들 수 없는 크기).

### 14.8 V7 마이그레이션

> 개발 이력. 같은 식·사전 검사·예제가 통합 V5 에 들어 있다 (15장) — V7 의 '생성 컬럼 다시 만들기'(PostgreSQL 삭제 후 추가 · H2 alter) 단계는 통합 V5 에는 없다 (처음부터 `varchar(765)` 로 만든다).


| 순서 | PostgreSQL | H2 |
|---|---|---|
| ① | ICU `und-x-icu` 확인 (없으면 멈춤) | 별칭 `FN_UPPER_ROOT` (`if not exists`) |
| ② | 새 기준 중복 : 같은 테이블에서 새 비교 값이 같은 컬럼 그룹 — 전체 그룹 수 + 처음 50개 (`TBL_SN`·새 비교 값·`COL_SN [이름]`) 를 담은 오류로 멈춤 | 같음 |
| ③ | 새 비교 값 765자 초과 (255자 제한상 생길 수 없지만 방어) | 같음 |
| ④ | `UX_COL_01` 삭제 → 생성 컬럼 삭제 → 새 식·`varchar(765)`·`collate "C"` 로 추가 → `UX_COL_01` 다시 추가 (한 트랜잭션, `TB_COL` ACCESS EXCLUSIVE — 그 사이 다른 연결의 쓰기는 기다리므로 유일성을 우회하지 못한다) | `alter column … varchar(765) generated always as (FN_UPPER_ROOT(trim(COL_NM)))` 한 문장 (UX_COL_01 은 그대로 남고 값만 다시 계산 — 실행 확인) |
| ⑤ | 예제 : 도메인 `값V765` 추가, 용어 `컬럼_명_비교_값` 의 도메인 → `값V765`, 예제 `TB_COL.COL_NM_CMPR_VL` 길이 765, 업무 설명 (원래 문구일 때만) | 같음 |

- 기존 V0~V6 은 바꾸지 않았다 (체크섬 그대로 확인). 다음 스키마 변경은 V8 (→ 15장 통합 뒤에는 V6). 예제 도메인은 29 → 30 (`V0SeedMigrationTest`), 마이그레이션 수 7 → 8.
- 실패 안전성 : PostgreSQL 은 Flyway 가 한 트랜잭션으로 돌려 사전 검사·DDL 실패 모두 아무것도 남지 않는다. H2 는 DDL 이 바로 확정되므로 사전 검사 실패 시 남는 것은 ① 의 별칭뿐이다 (다시 실행해도 `if not exists`). ④ 이후 H2 에서 실패하면 그 지점까지 확정된다 — 사전 검사가 데이터 문제를 먼저 걸러 ④ 가 실패할 데이터 원인은 남지 않지만, 디스크 등 다른 원인의 부분 적용은 `repair` 만으로 되돌아가지 않으므로 백업(H2 파일 복사, [h2-file-storage.md](../reference/h2-file-storage.md))에서 되돌린 뒤 다시 실행해야 한다 (이 경우는 재현하지 않았다).

### 14.9 업그레이드 검증과 V5 경유 제약

> **15장의 통합으로 해결** : 중간 `varchar(255)` 비교 컬럼이 없어져 V4 → V5 직접 업그레이드가 ß 200·255자 등 확장 이름에서도 성공한다 (H2·PostgreSQL libc·ICU·터키어 — 15.3). 아래는 통합 전 기록이다.


- `ColumnNameUnicodeMigrationTest` (H2·PostgreSQL) : 신규 설치 (8개), V6 → V7 (ASCII·한글·앞뒤 공백·그리스·합자·탭·NBSP·STRAßE/STRASSE 다른 테이블 — ID·이름·승인·수정 일시·제약조건 컬럼·예제 컬럼 수·V0~V6 체크섬 그대로, 비교 값은 Java 와 같게 다시 계산, 새 기준 중복 거절, 이름 변경 뒤 갱신, 생성 컬럼 쓰기 거절), V5 → V7 · V4 → V7, 새 기준 중복 데이터의 안전한 실패 → 이름 정리 → `repair` → 재실행 (PostgreSQL libc DB = `STRAßE`·`STRASSE`, H2 = 터키어 로캘 JVM 에서 넣은 `i_x`·`I_X`, 터키어 ICU 기본 로캘 PostgreSQL = `i_x`·`I_X`).
- **V5 경유 제약 (확인, 바꾸지 않음)** : V4 이하 DB 에 대문자 확장으로 255자를 넘는 이름(예 ß 200자)이 있으면 **H2 와 ICU 기본 로캘 PostgreSQL 에서는 V5 가 `22001` 로 멈춰** V7 까지 갈 수 없다 (V5 의 `varchar(255)` 생성 컬럼). libc 기본 로캘 PostgreSQL 은 V5 를 통과하고 V7 에서 400자가 된다. 그런 이름을 줄이거나 바꾼 뒤 업그레이드해야 한다 — V5 를 고치려면 사용자 승인이 필요하다 (V5·V6·V7 은 아직 커밋 전이고 개발 DB 는 V4 까지 적용 — 14.15 결정 사항).

### 14.10 저장 경로 · 오류 계약 · 관계

- 모든 쓰기 경로가 같은 `ColumnNameKey` 를 쓰고 기존 행 조회는 생성 컬럼과 비교한다 (4장 표의 경로 그대로). 확장 대문자 경로별 통합 테스트 : 단건 등록·중복 확인 API·이름 변경 (`unicodeInsertRenameRules`), 일괄 저장 (`bulkRollbackOnDatabaseConflict`), 엑셀 파일 안·기존 (`excelRules`), 논리 → 물리 생성·물리명 일괄 변경 미리보기·적용 (`unicodeGenerationAndPhysicalRename`), 모델 가져오기·복사 (`ModelExchangeIntegrationTest.columnNameKeyInFile`), 원본 가져오기 `COLUMN_NAME_TAKEN` (`SourceImportIntegrationTest.unicodeColumnNameTaken` — 원본 비교 `strasse` 는 짝짓기·식별자 해석 그대로, 모델 이름 `STRASSE` 가 기존 `STRAßE` 와 겹쳐 막힘), DB 직접 저장 (`normalizationMatches` — 표본 32개 Java = DB, 쌍 24개의 같음/다름을 DB UNIQUE 로도 확인). 상속 B형 생성은 같은 계획 검사 코드(`PhysicalGenerationService` 의 `columnNames`)를 쓴다.
- 오류 계약 그대로 : 단건 400 `SAVED_DATA_EXISTS`, 엑셀 파일 안 `COLUMN_DUPLICATE_IN_FILE`·기존 `SAVED_DATA_EXISTS`, 원본 `COLUMN_NAME_TAKEN`, 빈 이름 `INVALID_PHYSICAL_MODEL`, 매핑 저장 400, 물리명 변경 잠금 409. `UX_COL_01` 위반만 이름 충돌로 바꾸고 FK·NOT NULL·CHECK 위반은 그대로다.
- 관계·매핑·모델 버전·스냅샷 : 생성 컬럼만 다시 계산하고 컬럼명·ID·관계·매핑은 건드리지 않는다 (V6 → V7 테스트). 일괄 변경 정책(자기 대소문자 변경 허용·맞바꾸기·순환 거절·삭제 후 재등록·전체 롤백)은 그대로 — 기존 테스트 통과.

### 14.11 엑셀 · 동시성

- 엑셀 잠금 구조(업로드 전체 테이블 ID 순 선잠금, 500행 청크, 전체 원자성)는 그대로 — 정규화 때문에 파일을 더 읽지 않는다 (`ExcelUploadLockOrderIntegrationTest` 통과).
- `ColumnInsertConcurrencyIntegrationTest` U-07 (실제 별도 스레드·트랜잭션, 시작 신호로 같은 순간에, 3회 반복) : A `STRAßE` ↔ `STRASSE`, B `ﬁ_n` ↔ `FI_n` — 같은 테이블은 하나만 성공·다른 하나 `SAVED_DATA_EXISTS`·DB 에 하나, C 다른 테이블은 둘 다 성공, D 엑셀 업로드(두 테이블) ↔ 단건 등록 — 하나만 성공하고 실패한 업로드는 다른 테이블 행도 남기지 않는다. H2·PostgreSQL (libc · ICU und · ICU tr-TR 기본 로캘 DB) 모두 통과.

### 14.12 관계 동기화 KEY SHARE 교착 — 후속 이관 (M7-U08, 구현하지 않음)

> **후속 (2026-11-24, M8-C4b-R1 — [ongoing/modeling-concurrency-m8.md 18장](../ongoing/modeling-concurrency-m8.md))** : 동기화가 같은 KEY SHARE 를 대상을 읽기 전에 명시적으로 잡도록 바꿨다 (일반 저장끼리의 잠금 호환은 그대로 — 양방향 참조 회귀 통과).

> **후속 결과 (2026-11-22, [physical-fk-correction-m7.md](physical-fk-correction-m7.md) 8~10장)** : 아래에 적은 '잠근 집합 밖 참조 대상의 엇갈림' 은 이름 변경이 없는 등록·일괄·엑셀끼리에서는 교착이 나지 않았다 (부모 행 잠금 = FOR NO KEY UPDATE, KEY SHARE 와 함께 잡힘). 실제 교착은 키(이름·스키마) 변경이 끼는 경우였다 — S4(테이블 이름 변경끼리)·S6b(물리명 적용 ↔ 다중 청크 엑셀)는 해소했고, 일반 일괄 저장끼리의 PK 컬럼 삭제 교차(40P01 재현)는 같은 날 후속(M7-FK-D11, [physical-fk-correction-m7.md 14장](physical-fk-correction-m7.md))에서 PK 컬럼 삭제를 구조 변경 경로(프로젝트 배타 + NOWAIT)로 보내 해결했다 — 컬럼 등록·수정·엑셀·PK 가 아닌 삭제의 잠금은 바뀌지 않았다. 아래 원래 기록은 그대로 둔다.

- 경로 : 컬럼 저장(단건·일괄·엑셀) 트랜잭션 끝의 관계 동기화(`ReferenceSyncBoundary` → `syncReferenceRelations`)가 `TB_TBL_REL` 행을 만들거나 바꾼다. 그 행의 FK(대상 테이블 `TB_TBL`)는 PostgreSQL 에서 **대상 테이블 행에 KEY SHARE 잠금**을 건다. 컬럼 쓰기는 자기 테이블 행을 FOR UPDATE 로 잡는다 (KEY SHARE 와 FOR UPDATE 는 충돌).
- 조건 : 트랜잭션 A 가 T1 을 잠그고 T1 컬럼에 '참조 테이블 = T3' 을, 트랜잭션 B 가 T3 를 잠그고 T3 컬럼에 '참조 테이블 = T1' 을 쓰면, 각자 상대가 잠근 테이블 행에 KEY SHARE 를 기다릴 수 있다 (잠근 집합 밖의 참조 대상이 엇갈림).
- 재현 : **하지 않았다 — 확정된 장애가 아닌 잠재 위험**이다. 영향 가능 기능 : 단건 등록·일괄 저장·엑셀 업로드·컬럼 수정 중 참조 테이블명을 바꾸는 쓰기.
- 다음 작업 '기존 물리 테이블 FK 보정' 에서 검토할 것 : 관계 동기화 잠금 구조 조사, KEY SHARE 교착 재현, 부모 테이블 ↔ 참조 대상 테이블 잠금 순서 (예 : 참조 대상까지 ID 순으로 함께 잠글지, 비용), 기존 컬럼 저장 경로와의 잠금 호환. 이번 엑셀 청크 교착(M7-U07, 해결)과 다른 위험이다. pending-work [M-7] 에 같은 내용으로 남겼다.

### 14.13 전체 실행 · E2E · 단일 JAR · Docker

(모두 최종 코드 기준 — 전체 실행 뒤 바꾼 것은 문서·README 뿐이다)

- H2 `./gradlew build --continue --rerun` (npm ci·`tsc -b`·`vite build`·bootJar 포함) : 1,293건 — 성공 1,222 · 실패 0 · 건너뜀 71.
- PostgreSQL 18.6 (새로 띄운 격리 컨테이너의 새 빈 DB, libc 기본 로캘) `./gradlew test --rerun --continue` : 1,293건 — 성공 1,253 · 실패 0 · 건너뜀 40. `RequestLogStatusHttpTest` 는 이번 1회 통과 (간헐 실패 원인은 여전히 모른다 — 해결로 보지 않는다).
- 로캘 독립성 : `ColumnNameUnicodeMigrationTest`·`ColumnNameUniquenessIntegrationTest`·`ColumnInsertConcurrencyIntegrationTest`·`ColumnRequiredValuesMigrationTest` 를 ICU `und` 기본 로캘 DB 와 ICU `tr-TR` 기본 로캘 DB 에서도 실행 — 모두 통과 (건너뜀은 ICU und DB 의 'libc 전용 V6 공존 데이터' 시나리오 1건).
- 테스트 JVM 힙 : 작업 트리 `build.gradle` 의 `maxHeapSize = '2g'` 로 실행됐다 (이 작업에서 바꾸지 않음).
- Chromium E2E (Playwright, 최종 `lexorma.jar` 를 격리 위치 `:9981` 에서 h2-local 임시 데이터로 실행) : 기존 컬럼명 회귀 33/33 + 유니코드 27/27 — 등록 화면의 `strasse` ↔ 기존 `STRAßE` 중복 안내(서버 중복 확인 API), 서버 400 `SAVED_DATA_EXISTS`(`STRASSE`·`strasse`·` Straße `), 거절 뒤 이름 `STRAßE` 보존, 다른 테이블 허용, 고객번호, 빈 이름 `INVALID_PHYSICAL_MODEL`, 일괄 편집 '이미 추가됨'·서버 행 오류, `ƛ_CD` 옆 `Ƛ_CD` 저장(화면 오차단 없음 — 그 Chromium 의 `toUpperCase` 는 둘을 같게 만든다), 물리명 일괄 변경 미리보기 적용 가능 0개, 엑셀 기존 중복·파일 안 중복 전체 취소, 매핑·물리 ERD·논리 ERD, 한·영 × 라이트·다크 × 1440·375.
- 단일 JAR · Standalone 업그레이드 : V6 상태 복사본으로 만든 JAR 를 **터키어 로캘 JVM** 으로 h2-local 새 데이터 폴더에 띄워 V6 DB 를 만들고(데이터 `straße_x`·다른 테이블 `STRASSE_X`) 최종 JAR 로 같은 폴더를 열어 V7 적용 (0.08초) — 데이터 그대로, 길이 765·`FN_UPPER_ROOT`, 같은 테이블 `STRASSE_X` 거절.
- Docker PostgreSQL : 공식 이미지 `postgres:18.6` 로 띄운 격리 컨테이너(tmpfs, 기존 컨테이너·볼륨과 별개)에서 최종 JAR 신규 설치 (libc DB · 터키어 ICU DB — 별도 ICU 설치·DB 옵션·수동 SQL 없음), V6 JAR 로 만든 DB(`straße_x` = 비교 값 `STRAßE_X`) → 최종 JAR 로 V7 (`STRASSE_X`), 터키어 DB 의 `i_x`·`I_X` 거절. **Docker 앱 이미지(`Dockerfile`·`Dockerfile-h2`) 빌드·실행은 하지 않았다** — 기반 이미지(`node:24.11.1-alpine3.22`·`eclipse-temurin:21`)를 받아야 해서다 (같은 JAR 를 쓰므로 DB 동작은 위와 같을 것으로 보지만 미검증).

### 14.14 성능 · 운영 영향

**PostgreSQL 18.6, 같은 PC, B3 전(V6 + 글자 단위 Java 키 + JPQL `upper(trim())` 조회 복사본) / 후, 새 빈 DB 로 전·후·전·후 번갈아 2회** (`ColumnNameUniquePerformanceTest`, `LEXORMA_PERF=true`)

| 항목 (중앙값) | 전 1 / 2 | 후 1 / 2 | 준비 문장 수 (전 = 후) | 할당 MB (전 → 후) |
|---|---|---|---|---|
| 단건 등록 (컬럼 300개 테이블, 200회) | 21.3 / 20.4 ms | 20.6 / 19.4 ms | 17 | — |
| 일괄 저장 새 행 50 | 69 / 69 ms | 66 / 66 ms | 68 | — |
| 일괄 저장 삭제 20 + 재등록 20 | 47 / 44 ms | 48 / 45 ms | 44 | — |
| 엑셀 1,000행 · 테이블 10 | 282 / 302 ms | 292 / 294 ms | 79 | — |
| 엑셀 501행 (청크 2) | 153 / 158 ms | 161 / 169 ms | 55 | 54 → 53 |
| 엑셀 1,000행 | 224 / 230 ms | 231 / 223 ms | 75 | 99·100 → 99·98 |
| 엑셀 2,000행 · 테이블 4 | 376 / 379 ms | 380 / 376 ms | 137 | 190 → 188 |
| 엑셀 겹치지 않는 1,000행 둘 동시 | 220 / 227 ms | 238 / 227 ms | — | — |

- 문장 수는 모든 항목에서 같고 시간 차이는 회차 사이에서 방향이 바뀌는 잡음 범위다 — 개선·저하를 주장하지 않는다. 엑셀은 정규화 때문에 파일을 더 읽지 않는다.
- 기존 행 조회 : 새 네이티브 조회 두 개 모두 `ux_col_01` 인덱스의 두 열(`TBL_SN`, `COL_NM_CMPR_VL`)로 찾는다 (`EXPLAIN`, 약 48,000행). 예전 JPQL `upper(trim(COL_NM))` 는 식 인덱스가 없어 테이블 범위에서 거르는 방식이었다.
- Java 키 함수 (JIT 뒤 100만 건) : ASCII 22자 114 → 30 ns/건, 한글·ß·ﬁ·σ 혼합 245 → 143 ns/건 (문자열 전체 `toUpperCase` 가 글자 단위 스트림보다 빠르다).
- 물리명 일괄 변경 : 바뀐 것은 Java 비교 함수뿐이라 따로 재지 않았다.
- 마이그레이션 운영 영향 (PostgreSQL, `TB_COL` 207,967행 · 표 23MB · `UX_COL_01` 13MB, 트랜잭션 안에서 실행 후 되돌림) : 생성 컬럼 추가(표 다시 쓰기) 167 ms + UNIQUE 다시 만들기 50 ms — 그동안 `TB_COL` 은 ACCESS EXCLUSIVE 라 컬럼 읽기·쓰기가 기다린다. 표를 다시 쓰므로 표 크기만큼의 디스크가 잠시 더 필요하다 (지운 생성 컬럼 자리는 다음 VACUUM FULL 전까지 남는다, 23 → 24MB). H2 와 실제 운영 규모·운영 DB 에서는 재지 않았다. 실패 뒤 복구 : PostgreSQL 은 전부 롤백되므로 원인 정리 → 다시 시작, H2 는 14.8.

### 14.15 남은 제한 · 결정 사항

1. **Unicode 판 차이 (제한)** : Java 21 ↔ PostgreSQL ICU 76 은 27개 글자에서 다르다 (14.6). PostgreSQL 은 DB UNIQUE 가 같은 400 으로 막고, H2 는 허용한다. Java·ICU·브라우저 판이 바뀌면 이 목록이 바뀐다 — 완전한 일치는 이번 판들의 조합에서만 확인했다.
2. **판 올림 뒤 저장 값 (제한)** : 비교 값은 자동으로 다시 계산되지 않는다. 재점검·재계산 절차는 조사만 했다 (14.6, 미검증).
3. **V5 경유 업그레이드 (→ 15장 M7-U09 로 해결)** : V4 이하 DB 에 확장 대문자로 255자를 넘는 이름이 있으면 H2·ICU 기본 로캘 PostgreSQL 에서 V5 가 멈춘다 (14.9). V5·V6·V7 이 아직 커밋 전이므로 V5 를 고치는 선택지(예 : V5 의 길이를 처음부터 765로)가 있지만 V5 를 이미 적용한 DB 의 체크섬이 달라진다 — 사용자 결정 전에는 바꾸지 않았다. 개발 DB 는 V4 까지 적용돼 있다 (읽기 전용 확인).
4. **물리명 추천 목록의 상태 판정 (→ 15장 M7-U10 으로 해결 — 아래는 그 전 기록)** : 추천 목록·요약·상태 필터의 `CONFLICT` 와 목록의 적용 가능 표시는 기존 표준화 규칙(DB `upper(trim())` + 대소문자 무시 비교 — `PhysicalNameRecommendationQueryRepository`·`StandardNames.sameColumnName`)을 그대로 쓴다. 그래서 확장 대문자로만 같은 충돌(`ROLE_CLASS_CD` ↔ `role_claß_cd`)은 목록에서 '적용 가능' 으로 보이고 **미리보기·적용이 `NAME_CONFLICT` 로 막는다** (E2E 확인 — 저장되지 않는다). 터키어 기본 로캘 PostgreSQL 에서는 목록 SQL 의 `upper()` 가 `i → İ` 라 ASCII 소문자 `i` 가 든 충돌도 목록에서 놓칠 수 있다 (미리보기·적용은 막는다). 목록 판정 SQL 을 생성 컬럼 기준으로 바꿀지는 결정 필요 (엔진별 식이 달라 이번 범위에서 하지 않았다).
5. **JVM 기본 로캘 (제한, 이번 범위 밖)** : 컬럼명 비교 값은 로캘과 무관해졌지만, 테이블명·데이터 타입 등 다른 이름 처리의 `toUpperCase()` (로캘 인자 없음, `lexorma-data`·`common`·`source` main 에 27곳)는 그대로다 — 터키어 로캘 JVM 에서 다른 결과가 날 수 있다.
6. **미검증** : PostgreSQL 18.6 외 판, ICU 없는 빌드, Docker 앱 이미지, Chromium 외 브라우저, 운영 DB 데이터·로캘, H2 판 올림 때 `toUpperEnglish` 유지 여부.
7. **이관 (M7-U08)** : 관계 동기화 KEY SHARE 교착 가능성 — 14.12, pending-work [M-7] '기존 물리 테이블 FK 보정'.

## 15. 커밋 전 최종 마무리 (2026-11-22, 사용자 확정 M7-U09~U12)

| ID | 내용 | 상태 |
|---|---|---|
| M7-U09 | 미배포 V5·V6·V7 → 통합 `V5__column_data_integrity.sql` | 구현·검증 완료 |
| M7-U10 | 물리명 추천 목록·미리보기·적용의 컬럼명 충돌 판정 통일 | 구현·검증 완료 |
| M7-U11 | Unicode 판 차이·운영 제한 관리 (B3 구조 그대로) | 문서화 완료 · 제한 유지 |
| M7-U12 | Docker 앱 이미지 빌드·실행 (기존 Dockerfile·Compose) | 검증 완료 |

### 15.1 통합 전제 확인 (읽기 전용)

- V5·V6·V7 은 어떤 커밋에도 없다 (`git log --all` 에 이 파일명이 없음, HEAD `ba7524c` = `origin/dev`, HEAD 를 담은 태그 없음). 작업 트리에만 있었다.
- 개발 DB(`lexorma-postgres` 의 `lexorma`) Flyway 최고 버전 4 (작업 시작 시 다시 확인), H2 Standalone 기본 데이터 폴더 `~/.lexorma` 없음. 그 밖에 접근할 수 있는 보존 대상 DB 는 없었다 — **운영·외부 배포 환경은 확인하지 못했다** (저장소에 커밋되지 않은 파일이라 배포될 경로는 없다고 판단).
- V5~V7 을 적용한 DB 는 모두 검증용으로 만들고 지운 격리 DB 다.

### 15.2 통합 V5 구조

1. PostgreSQL : ICU `und-x-icu` 확인 (없으면 멈춤) / H2 : 별칭 `FN_UPPER_ROOT`
2. **사전 검사 한 문장** — 최종 제약 기준으로 기존 V4 데이터를 직접 검사한다 (중간 V5·V6·V7 기준을 차례로 쓰지 않는다) : 필수값 위반(부모 없음·이름 없음·빈 이름) 건수 · 새 기준(B3) 중복 그룹 수 · 비교 값 765자 초과 건수를 한꺼번에 세고, 처음 50개 항목(`[필수값] COL_SN=…`, `[중복] TBL_SN=… 새 비교 값=… : COL_SN … [이름] · …`, `[길이] …`)과 함께 표 변경 전에 멈춘다. 데이터를 고치거나 지우지 않는다.
3. DDL : `TBL_SN`·`COL_NM` NOT NULL → `CK_COL_01` → 생성 컬럼 `COL_NM_CMPR_VL varchar(765)` (PostgreSQL `collate "C" … upper(trim(COL_NM) collate "und-x-icu") stored`, H2 `FN_UPPER_ROOT(trim(COL_NM))`) → `UX_COL_01` → COMMENT. **중간 `varchar(255)` 비교 컬럼은 없다.**
4. 예제 메타데이터 (최종 상태로 한 번에) : 도메인 `값V765` → 용어 `컬럼_명_비교_값` (도메인 `값V765`) → `TB_COL.COL_NM_CMPR_VL` (길이 765) → 속성 → 속성 매핑 → `UX_COL_01` → 업무 설명(최종 문구) → `TBL_SN`·`COL_NM` 필수 → `CK_COL_01`. 테이블마다 시퀀스를 예전 V5 → V6 → V7 과 같은 순서로 써서 ID 가 같다.

### 15.3 동일성 검증 (환경 A = 예전 V0~V4 → V5 → V6 → V7, 환경 B = V0~V4 → 통합 V5)

같은 V4 데이터(ASCII·한중일·앞뒤 공백·합자·그리스·탭·NBSP·`straße`/`STRASSE` 다른 테이블)를 넣고 H2·PostgreSQL 각각에서 두 경로의 최종 상태를 덤프해 비교했다 (임시 테스트, 검증 뒤 삭제) — 모든 표의 컬럼 정의(타입·길이·NULL·기본값·생성 여부), 인덱스, 모든 행(시각 컬럼 제외 — 예제 데이터·ID·관계·매핑·정규화 결과 포함), 시퀀스 현재 값, 제약조건 정의, 생성식·collation, H2 함수.

| | 줄 수 (A = B) | 다른 줄 | 차이의 뜻 |
|---|---|---|---|
| H2 | 7,689 | 3 쌍 | 자동 생성 인덱스 이름 접미사 (`PRIMARY_KEY_A888` ↔ `_A88`, `UX_COL_01_INDEX_A` ↔ `_9`) — 같은 열·같은 UNIQUE, 표현만 다름 |
| PostgreSQL | 8,380 | 1 쌍 | `col_nm_cmpr_vl` 의 `ordinal_position` 33 ↔ 32 — 예전 V7 이 컬럼을 지우고 다시 만들어 번호에 구멍이 남은 것. 컬럼 순서·정의는 같음 |

- Flyway 이력 : B 는 버전 5 하나 (V6·V7 행 없음, V5 체크섬 다름) — 통합에 따른 의도된 차이. **V0~V4 체크섬은 같다** (`ColumnNameUnicodeMigrationTest.upgradeFromV4` 등에서 확인).
- 지원 경로 : 신규 설치 → V5, V4 → V5 (V4 이전 버전은 2026-11-18 합침 이후 없다). 다음 마이그레이션은 **V6**.

### 15.4 V4 직접 업그레이드 · 실패 복구

- `ColumnNameUnicodeMigrationTest.upgradeFromV4` (H2 · PostgreSQL libc · ICU und · ICU tr-TR 기본 로캘 DB) : ß 200자 → 400자, ß 255자 → 510자, ΐ 255자 → 765자 (1,530바이트), 다국어 혼합 최대 길이 이름이 모두 업그레이드되고 이름·ID·승인·수정 일시·제약조건 컬럼·매핑(+예제 1)·V0~V4 체크섬 그대로. **14.9 의 'V5 경유 22001' 문제는 해결됐다** (H2·ICU 기본 로캘 DB 에서도 통과).
- `violationsStopSafely` : V4 에 `STRAßE`·`STRASSE`, 예전 터키어 로캘에서 공존하던 `i_x`·`I_X`, `ﬁ_cd`·`FI_CD`, NULL 이름, 공백·탭 이름, 부모 없는 컬럼을 넣으면 "필수값 위반 3건 · 새 기준 중복 3개 그룹 · 비교 값 765자 초과 0건" 과 목록으로 멈춘다 — 데이터 그대로, 표 변경 없음(비교 컬럼·`UX_COL_01`·`CK_COL_01` 없음, `COL_NM` NULL 허용 그대로), V5 성공 이력 없음. H2 는 사전 검사 앞의 별칭 `FN_UPPER_ROOT` 만 남는다 (다시 실행하면 `if not exists`). 이름 정리 → `repair` → 재실행으로 적용. `ColumnRequiredValuesMigrationTest`·`ColumnNameUniqueMigrationTest` 도 V4 → V5 기준으로 바꿨다 (검증 목적 그대로).
- H2 에서 사전 검사 뒤 DDL 도중 다른 원인으로 실패하면 그 지점까지 확정된다 — `repair` 만으로 되돌아가지 않으므로 H2 파일 백업에서 되돌려야 한다 (재현하지 않았다, 14.8 과 같음). PostgreSQL 은 전체 롤백.

### 15.5 물리명 추천 충돌 판정 통일 (M7-U10)

- 예전 : 목록의 상태·필터·요약(`CONFLICT`)은 SQL `upper(trim(COL_NM)) = upper(trim(약어))` (DB 기본 로캘), 행의 충돌 사유는 같은 SQL 로 고른 컬럼을 Java `equalsIgnoreCase` 로, 미리보기·적용은 테이블의 모든 컬럼을 Java `ColumnNameKey` 로 판정 — 확장 대문자·터키어 DB·Unicode 판 차이에서 서로 달랐다 (`ROLE_CLASS_CD` ↔ `role_claß_cd` : 목록 '적용 가능', 미리보기 `NAME_CONFLICT`).
- 지금 : 세 단계 모두 **저장된 `COL_NM_CMPR_VL` ↔ 추천명의 DB 비교 값** (그 DB 의 `UX_COL_01` 과 같은 기준, 자기 컬럼 ID 제외, 같은 테이블만).
  - DB 별 식은 한 곳 `column/repository/ColumnNameKeySql` (PostgreSQL `(upper(trim(x) collate "und-x-icu") collate "C")`, H2 `FN_UPPER_ROOT(trim(x))` — 방언은 Hibernate 가 정한 `Dialect` 로 고른다). 기존 프로젝트에 DBMS 분기 구조가 없어 Hibernate `FunctionContributor`(같은 클래스, `META-INF/services` 등록)로 HQL 함수 `column_name_taken(tableId, name, excludeColumnId)` 하나만 더했다 — 목록 SQL 의 `CONFLICT` 식 (필터·요약·페이징이 서버에서 그대로).
  - 행의 충돌 사유·추천끼리 겹침(목록 `RECOMMENDATION_DUPLICATE`)·미리보기의 `NAME_CONFLICT`·선택끼리 겹침은 `ColumnNameDbKeys` 의 네이티브 조회 한 문장 (`values` 로 후보 이름을 넘기고 `UX_COL_01` 로 찾는다 — 행마다 조회하지 않는다).
  - 바꾸지 않은 것 : 추천명·추천 근거·대상 산정, 표준화 '일치'(`ALREADY_MATCHED` — DB `upper(trim())` 와 `StandardNames.sameColumnName`), 테이블명 충돌(스키마·VIEW 이름 공간, 대소문자 무시), DBMS 식별자·예약어 정책, 오류 계약 (`NAME_CONFLICT`·`RECOMMENDATION_DUPLICATE`·409·`SAVED_DATA_EXISTS`).
- 검증 `PhysicalNameRecommendationConflictIntegrationTest` (H2 · PostgreSQL libc · ICU und · ICU tr-TR) : 대소문자(`CUSTOMER_ID`↔`customer_id`)·`ROLE_CLASS_CD`↔`role_claß_cd`·`FI_CD`↔`ﬁ_cd`·앞뒤 U+0020 = 목록 충돌·적용 불가·충돌 필터·미리보기 `NAME_CONFLICT`·적용 거절(이름 그대로), 다른 테이블·`É`↔`e`+U+0301·전각↔ASCII·자기 자신(`STRAßE_CD` → `STRASSE_CD`) = 충돌 아님, **Unicode 판 차이 `Ƛ_CD`↔`ƛ_CD` = PostgreSQL 은 충돌·H2 는 허용 (각 DB 의 UNIQUE 와 같다)**. 요약 = 필터 건수 = 행 상태 수, 2건씩 페이지를 나눠도 충돌 건수·순서 같음, 이름 검색, 다른 프로젝트 제외, 정상 항목 적용. 미리보기 뒤 생긴 충돌은 적용 거절, 함께 고른 `MULTI_SS`·`multi_ß` 는 `RECOMMENDATION_DUPLICATE`, 맞바꾸기·순환은 `NAME_CONFLICT`. 터키어 기본 로캘 DB 에서도 `CUSTOMER_ID`↔`customer_id` 를 목록이 충돌로 보인다 (예전 SQL 은 `i → İ` 로 놓쳤다).
- 남은 표준화 규칙 차이 (바꾸지 않음, 보고) : H2 목록 SQL 의 '일치' 판정은 H2 `UPPER`(ß → SS, JVM 기본 로캘)라 `STRAßE_CD` 컬럼·약어 `STRASSE_CD` 를 '일치' 로 세고 행 판정(Java `equalsIgnoreCase`)은 '추천 가능' 으로 본다 — 표준화 일치 규칙의 문제이고 충돌 판정과는 별개다 (적용 가능 필터의 건수만 그 행에서 다를 수 있다). → pending-work [M-7] 후속 '물리명 추천 목록의 표준화 일치 판정 일관성' 으로 이관 (수정하지 않음).

**성능 (PostgreSQL 18.6, `PhysicalNameRecommendationPerformanceTest` — 충돌 필터·적용 가능 필터·요약·미리보기 100건 측정을 더했다, 앞쪽 20개 테이블에 충돌 컬럼, 변경 전 복사본 / 후를 새 DB 로 번갈아 2회, 5회 중앙값 ms (준비 문장 수))**

| 규모 (엔터티×속성, 컬럼) | 목록 1쪽 전 → 후 | 충돌 필터 1쪽 | 적용 가능 필터 1쪽 | 요약 | 미리보기 100건 |
|---|---|---|---|---|---|
| 10×10 (100) | 18·21 (13) → 19·19 (14) | 12·15 → 14·15 | 15·15 → 9·13 | 10·8 (5) → 8·8 (5) | 9·9 (18) → 9·10 (19) |
| 100×20 (2,000) | 5·5 (13) → 5·5 (14) | 38·38 → 25·23 | 48·48 → 21·22 | 48·48 → 21·21 | 8·9 → 9·9 |
| 300×30 (9,000) | 7·8 (13) → 7·8 (14) | 170·170 → 71·74 | 241·242 → 67·68 | 272·275 → 91·94 | 18·19 → 19·19 |

- 상태 필터·요약은 2.4~3배 빨라졌다 (예전 식은 `upper(trim(COL_NM))` 비교라 인덱스를 쓰지 못했다 — 지금은 `ux_col_01 (tbl_sn, col_nm_cmpr_vl)` 인덱스 조회, `EXPLAIN` 확인). 목록·미리보기는 DB 비교 값 일괄 조회 1문장이 늘었고 시간은 같다. 측정하지 않은 운영 규모에 대해 단정하지 않는다.

### 15.6 Unicode 제한 (M7-U11 — 구조 그대로)

- B3 구조 그대로 : Java `toUpperCase(Locale.ROOT)`, PostgreSQL `und-x-icu` 대문자 + 컬럼 `collate "C"`, H2 `FN_UPPER_ROOT`, `varchar(765)` 생성 컬럼, 화면의 보수적 사전 판단, 저장 시 서버 검증 + DB UNIQUE. Case Folding·NFC·NFKC 는 적용하지 않는다.
- 기록 그대로 (14.5·14.6) : Java 21 ↔ PostgreSQL ICU 76 단일 코드 포인트 불일치 27개, 화면이 서버보다 덜 잡는 글자 771개 · 더 잡는 글자 0개(확인한 Node 26·Chromium 151), 단일 코드 포인트 전수 + 무작위 문자열 30만 개 — **모든 문자열의 완전한 일치를 보장하지 않는다**. PostgreSQL 과 H2 의 허용 범위는 그 27개 글자에서 다르다 (PostgreSQL 은 같은 이름으로 막고 H2 는 허용) — UNIQUE 는 각 DB 안의 무결성을 지킬 뿐 두 DB 의 결과가 같다는 뜻이 아니다. M7-U10 이후 물리명 추천 목록·미리보기도 각 DB 의 기준을 따른다.
- 런타임 업그레이드 검토 절차 (운영 기록 — **절차 자체는 검증하지 않았다**, 자동 재정규화 기능 없음) : ① 업그레이드 전 백업 (PostgreSQL 덤프 · H2 파일 복사) ② 새 런타임(JVM·PostgreSQL 이미지/ICU)에서 같은 식으로 비교 값을 계산해 본다 (`upper(trim(COL_NM) collate "und-x-icu")` / 새 JVM 의 H2) ③ 새 값 기준 중복 검사 (V5 사전 검사와 같은 그룹 쿼리) ④ 저장된 `COL_NM_CMPR_VL` 과 새 계산 값이 다른 행 확인 ⑤ 필요하면 생성 컬럼 재계산·재구성 검토 (stored 생성 컬럼은 행을 다시 쓸 때만 다시 계산된다) ⑥ `UX_COL_01` 유효성 확인 ⑦ 문제 시 ① 에서 복구.
- PostgreSQL 지원 범위 : 실제 검증 18.6 (Debian 공식 이미지, ICU 76) / 문서 권장 "18" / Compose `postgres:18.6` / ICU 를 넣어 빌드한 PostgreSQL 이 필요 (없으면 V5 첫 문장이 이유와 함께 멈춘다) / 18.6 외 판은 검증하지 않았다 — 하한은 새로 정하지 않았다. 공식 이미지에서는 별도 ICU 설치·DB 재생성 없이 동작했다 (libc 기본 로캘 DB, 15.7).

### 15.7 Docker 앱 이미지 (M7-U12)

- 기존 `Dockerfile` 그대로 (Node `node:24.11.1-alpine3.22` → JDK `eclipse-temurin:21-jdk` → JRE `eclipse-temurin:21-jre`, React 빌드 → `:lexorma-app:bootJar` → 단일 JAR) 로 이미지 `lexorma-m7u12/lexorma:test` 빌드 성공. 런타임 Java Temurin 21.0.13, 이미지 690MB, ICU 설치 단계 없음.
- 빌드 환경 메모 : 첫 시도는 로컬 Docker 자격 증명 도우미(`docker-credential-osxkeychain`)가 응답하지 않아 기반 이미지 메타데이터 단계에서 시간 초과로 실패했다 — 사용자 설정은 바꾸지 않고 그 명령에만 도우미 없는 임시 `DOCKER_CONFIG`·`PATH` 로 익명 pull 해 빌드했다.
- **`.dockerignore` 문제 (발견, 바꾸지 않음)** : `docker/volume` 만 빼고 실제 개발 DB 볼륨 `lexorma/volume` (약 471MB) 은 빼지 않아, 저장소 루트에서 그대로 빌드하면 개발 DB 파일이 빌드 컨텍스트와 빌드 단계 이미지(`COPY . .`)에 들어간다. 이번 검증은 `lexorma/volume`·빌드 산출물을 뺀 격리 복사본을 빌드 컨텍스트로 썼다. → 2026-11-22 마무리 정리에서 `.dockerignore` 에 `/lexorma/volume/` 을 더했다 (`docker/volume` 유지). 저장소 루트를 컨텍스트로 한 확인용 빌드(`FROM scratch`, 이미지 받지 않음)에서 컨텍스트 26.69MB, `lexorma/volume` 없음, 소스 포함 확인. Dockerfile·Compose 는 그대로.
- 실행 : 기존 `lexorma/docker-compose-exam/docker-compose.yml` 그대로 + 컨테이너 이름만 바꾸는 덮어쓰기 파일 + 임시 `.env` (포트 25440·9984, 임시 데이터 폴더), Compose 프로젝트 `lexorma-m7u12`. 기존 `lexorma-postgres` 컨테이너·볼륨과 별개.
  - 신규 설치 : Flyway V0~V5 (6개) 적용 → 첫 관리자 만들기 → 로그인·프로젝트 선택 → 예제 테이블·컬럼 → 정상 등록 → `STRASSE`·` Straße ` 중복 400 → 빈 이름 400 → 추천 목록 `O_FILE_NM`(추천 `FILE_NM` ↔ `ﬁle_nm`) 충돌·적용 불가, `O_FILE_PATH_NM` 적용 가능 → 충돌 필터 = 요약 → 미리보기 `NAME_CONFLICT` → 적용 거절 → 화면에서 고를 수 없음 → 논리·물리 ERD (14/14). 앱 컨테이너 재시작 뒤 'up to date' · 12/12.
  - V4 업그레이드 : HEAD(`ba7524c`, V4) JAR 로 같은 PostgreSQL 에 V4 DB 를 만들고 (확장 이름 ß 200·255자, ΐ 255자, 다국어 혼합, 테스트 용어·컬럼) 최종 이미지로 전환 → V5 적용 (0.02초). 기존 컬럼 ID·이름·테이블의 해시 같음, 매핑 991 → 992 (예제 1), 관계 103 그대로, `NOT NULL`·`CK_COL_01`·`UX_COL_01`·765. 업그레이드 뒤 13/13, 재시작 뒤 12/12.
- 미검증 : H2 Docker 이미지(`Dockerfile-h2`), 운영 환경, 18.6 외 PostgreSQL.

### 15.8 전체 실행 · E2E (최종 코드)

| 항목 | 결과 |
|---|---|
| H2 `./gradlew build --continue --rerun` | 1,291건 — 성공 1,222 · 실패 0 · 건너뜀 69 (직전 1,293 → 마이그레이션 테스트 재구성 −4 : V6→V7·V5/V4→V7·V5 경유·V6 중복 4종 → V4→V5 2종, 새 충돌 판정 테스트 +2) |
| PostgreSQL 18.6 새 빈 격리 DB `./gradlew test --rerun --continue` | 1,291건 — 성공 1,251 · 실패 0 · 건너뜀 40, `RequestLogStatusHttpTest` 1회 통과 (간헐 원인 미확인 — 해결로 보지 않음) |
| PostgreSQL ICU und · ICU tr-TR 기본 로캘 DB | 마이그레이션 3종 · 추천/물리명 변경 테스트 · `ColumnNameUniquenessIntegrationTest` 통과 |
| 동시성 회귀 | `ColumnInsertConcurrencyIntegrationTest`(U-01~U-07)·`ExcelUploadLockOrderIntegrationTest` — H2·PostgreSQL 전체 실행에 포함, 통과 |
| Chromium E2E (최종 단일 JAR, 격리 :9981) | 기존 회귀 33/33 + 유니코드 27/27 (U5 를 '목록에서도 고를 수 없음' 으로 바꿈) + **새 추천 목록 16/16** (충돌 표시·적용 가능 표시·충돌/적용 가능 필터·요약 카드·선택 불가·정상 적용·이름 보존·품질 진단 화면·미리보기 뒤 충돌 거절·한영×라이트다크×1440/375) = 76/76 |
| 단일 JAR · Standalone | JAR 안의 마이그레이션 = V0~V5 (예전 V6·V7 없음). h2-local 신규 설치(V5)·재시작('up to date'), HEAD JAR(터키어 로캘 JVM)로 만든 V4 파일 DB → 최종 JAR 로 V5 (ß 200·255자 → 400·510, `i_x` = `I_X`), 같은 테이블 `I_X` 거절, 재시작 |
| Docker | 15.7 (신규 14/14·재시작 12/12·V4 업그레이드 13/13·재시작 12/12) |
| 테스트 JVM 힙 | 작업 트리 `build.gradle` `maxHeapSize = '2g'` (바꾸지 않음) |

- 품질 진단 E2E(R9)는 화면이 열리고 요약 API 가 응답하는지와, 적용·추가한 테스트 컬럼 외에 `columnNameMismatch` 등이 그대로인지만 봤다 — 표준화 규칙이 그대로라는 근거는 규칙 코드를 바꾸지 않았다는 것과 전체 실행의 `StandardizationFindingGoldenTest`(모든 진단 결과 고정) 통과다.
