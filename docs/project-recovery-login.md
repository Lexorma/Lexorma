# 프로젝트 복구 로그인 (P1 운영 안정화)

모든 프로젝트가 비활성화되면 시스템 관리자도 로그인할 수 없던 문제를 해결한다. M-9(모델 변경 이력)와 병행한 독립 작업이며 M-9 의 코드·마이그레이션에 의존하지 않는다. **DB 변경 없음** (마이그레이션 추가·수정 없음).

- 작업일 : 2026-11-12
- 작업 위치 : 별도 worktree `../metamora-p1-recovery`, 브랜치 `p1/project-recovery-login` (기준 `dev` `7a40013`), 커밋하지 않음

## 1. 장애 원인

로그인은 2단계다 : ① `POST /api/login/projects`(아이디·비밀번호 확인 → 접근 가능한 프로젝트) ② `POST /api/auth/login`(`project_id` 포함, Security `formLogin`).

"접근 가능한 프로젝트"는 **활성 프로젝트 + 승인된 멤버**(`ProjectRepositoryImpl.findProjectsByUsername` / `isAccessibleProject`)이고 역할과 무관하다. 그래서 활성 프로젝트가 없으면

| 단계 | 결과 |
|---|---|
| ① 프로젝트 조회 | 403 `NO_ACCESSIBLE_PROJECT` (ADMIN 포함) |
| ② 로그인 | `FormAuthenticationProvider` 가 `project_id` 를 요구 → 401 `PROJECT_ACCESS_DENIED` |
| 기존 세션 | `ProjectAccessInterceptor` / `/api/auth/me` 가 세션을 끊음 (`PROJECT_ACCESS_REVOKED`) |
| 재활성화 | `PUT /api/saveProject/project` 는 로그인한 프로젝트 관리자만 → 호출할 방법이 없음 |

프로젝트 관리 화면에서 마지막 활성 프로젝트의 "활성화 여부"를 끄면 아무 경고 없이 이 상태가 됐다.

### 분석한 상황

| 상황 | 결과 |
|---|---|
| 기본 프로젝트(META-MORA, id 0) 활성 | 기존 로그인 그대로 |
| 기본 프로젝트 비활성, 다른 활성 프로젝트 있음 | 그 프로젝트의 멤버는 정상 로그인. 로그인은 기본 프로젝트에 의존하지 않는다 ("기본"은 id 0 을 삭제할 수 없다는 뜻일 뿐, 기본 프로젝트 지정 컬럼은 없다) — 수정할 것 없음, 테스트로 확인 |
| 모든 프로젝트 비활성 | **이번 작업의 대상** → 복구 로그인 |
| 프로젝트가 하나도 없음 | 화면으로는 만들 수 없는 상태 (id 0 은 삭제 불가). DB 를 직접 지운 경우 복구 로그인은 되지만 활성화할 프로젝트가 없다 → 운영 복구는 DB (7장) |
| ADMIN 이 프로젝트 멤버·관리자가 아님 | 복구 로그인은 멤버·관리자 여부와 무관 (시스템 ADMIN 역할만 본다) |
| 활성 프로젝트는 있지만 ADMIN 이 그 멤버가 아님 | ADMIN 은 로그인할 수 없다 (기존 정책 : ADMIN 에게 프로젝트 접근을 자동으로 주지 않는다). 다른 멤버는 정상 사용 중이므로 **복구 로그인 대상에서 제외** — 정책 결정 필요 (8장) |

## 2. 복구 로그인 정책

- 조건 : **활성 프로젝트가 하나도 없고**, 계정에 **실제로 부여된 역할이 `ROLE_ADMIN`** (역할 계층으로 얻은 상위 권한이 아니라 `TB_ACNT_ROLE`). 프로젝트 관리자·MANAGER·DBA·USER·VIEWER·NOT_APPROVE 는 불가.
- 인증은 기존 체계 그대로 : 같은 계정·비밀번호·`PasswordEncoder`·활성 계정 확인·초기 비밀번호 차단(`PASSWORD_CHANGE_REQUIRED`, 로그인 전 변경 API 는 프로젝트 없이 동작). 복구 전용 계정·공통 비밀번호 없음.
- 요청 : 기존 `POST /api/auth/login` 에 `recovery=true` (`project_id` 없음). 같은 Security 필터를 타므로 세션 고정 방어(`changeSessionId`), CSRF, 계정당 세션 1개(나중 로그인 우선)가 그대로 적용된다.
- 실패 응답 (401) : 아이디 없음·비밀번호 불일치·비활성 계정 → `INVALID_CREDENTIALS` (기존과 같음). 비밀번호는 맞지만 조건 불충족 → `RECOVERY_LOGIN_DENIED` — ADMIN 이 아닌지 활성 프로젝트가 있는지는 구분해 알리지 않는다.

## 3. 복구 세션

`FormAuthenticationProvider.recoveryAuthentication` 이 만드는 인증 정보 :

| | 일반 세션 | 복구 세션 |
|---|---|---|
| Principal `AccountDto.projectId` | 로그인한 프로젝트 | `null` (임의의 프로젝트를 넣지 않는다) |
| 권한 | 계정의 역할 (`ROLE_ADMIN` 등) | **`ROLE_RECOVERY` 하나** |
| details | `FormWebAuthenticationDetails` | `RecoveryAuthenticationDetails` |

`ROLE_RECOVERY` 는 역할 계층(`RoleHierarchyConfig`)과 `TB_ROLE` 에 없는, 세션에만 있는 권한이다. ADMIN 권한을 세션에 담지 않으므로 **일반 API 의 `@PreAuthorize("hasRole('…')")` 를 하나도 통과하지 못한다**. 복구 세션은 일반 세션으로 바뀌지 않는다 — 로그아웃 후 정상 로그인해야 한다.

세션 DB 에 이미 직렬화되어 있는 `FormWebAuthenticationDetails` / `AccountDto` 에는 필드를 추가하지 않았다 (배포 시 기존 세션 역직렬화가 깨지지 않게 별도 클래스로 구분).

## 4. API 접근 제어 (서버)

`SecurityConfig` (필터 체인, 허용 목록 방식) :

```
/api/auth/me, /api/auth/csrf (GET), 로그인·로그아웃   → 기존과 같음
/api/recovery/**                                     → hasRole('RECOVERY')  (일반 ADMIN 세션도 403)
/api/**                                              → 인증 + ROLE_RECOVERY 가 아님  (복구 세션은 403 ACCESS_DENIED)
```

- 복구 세션이 쓸 수 있는 것 : `GET /api/auth/me`, `GET /api/auth/csrf`, `GET /api/recovery/projects`, `POST /api/recovery/projects/{projectId}/activate`, `POST /api/auth/logout`.
- 나머지 `/api/**` 는 URL 단계에서 막으므로 `@PreAuthorize` 가 없는 핸들러, 없는 주소, **나중에 추가되는 API(M-9 이력 API 포함)** 도 별도 설정 없이 막힌다. 403 은 세션을 끊지 않는다.
- `RecoveryRestController` 는 클래스에 `@PreAuthorize("hasRole('RECOVERY')")`, `ProjectAccessInterceptor` 는 `/api/recovery/**` 를 제외한다 (프로젝트가 없는 세션).
- `/api/auth/me` : 복구 세션이면 `recovery: true`, `menus` 전부 `false`, `approvalAvailable: false`, `projectId`·`projectName` `null`. 일반 세션의 응답은 그대로다 (`recovery` 키 없음).
- 활성화할 때 서비스가 계정을 DB 에서 다시 확인한다 (활성 + `ROLE_ADMIN`). 역할 변경·사용자 삭제는 기존대로 그 사용자의 세션을 지우므로 복구 세션도 끊긴다.

### 복구 API

| API | 내용 |
|---|---|
| `GET /api/recovery/projects` | 전체 프로젝트(비활성 포함) : `id`, `projectName`, `isActive`, `isDefault`(id 0), `projectManagerName`, `updated`(수정일). 프로젝트에 설명 컬럼은 없다 |
| `POST /api/recovery/projects/{projectId}/activate` | 활성 여부만 `true` 로. 이름·관리자·멤버는 바꾸지 않는다. 이미 활성이면 그대로 200, 없는 프로젝트는 404 `PROJECT_NOT_FOUND` |

복구 완료는 별도 API 가 아니라 **로그아웃**이다 (세션 삭제).

## 5. React 화면

- **로그인 화면** (`LoginPage`) : 공개 API `GET /api/auth/signup/projects`(활성 프로젝트, 회원가입 화면이 쓰던 것)가 비어 있으면 "현재 활성화된 프로젝트가 없습니다." 안내를 보이고 버튼이 **[관리자 복구 로그인]** 이 된다. 아이디·비밀번호만 받고 프로젝트 선택은 없다. 누가 관리자인지는 화면에서 알 수 없고(같은 화면·같은 버튼), 서버가 비밀번호를 확인한 뒤 거절한다.
- **복구 화면** `/recovery/projects` (`RecoveryPage`) : 프로젝트 목록, 활성/비활성 표시, [활성화](확인 후), [복구 완료](확인 후 로그아웃 → 로그인 화면). 사이드바·업무 메뉴·[내 정보] 없음. 헤더의 [로그아웃] 은 그대로.
- **라우팅** (`routes/guards.tsx`) : `RequireRecovery` — 비인증은 `/login`, 일반 세션은 `/`. `RequireAuth`·`GuestOnly` — 복구 세션은 어떤 주소로 들어와도 `/recovery/projects`. 화면 숨김은 보조이고 차단은 4장의 서버 설정이 한다.
- 뒤로 가기로 브라우저가 보관한 화면(bfcache)이 되살아나면 로그인 상태를 다시 받는다 (`useAuth` 의 `pageshow`) — 복구 완료 후 복구 화면이 다시 보이던 문제 (브라우저 검증에서 발견).

## 6. 마지막 활성 프로젝트 비활성화

금지하지 않는다 (복구 로그인으로 되돌릴 수 있다). 대신 `ProjectManageServiceImpl.updateProject` 가

1. 활성 프로젝트 행을 잠그고(`findActiveForUpdate`, 서로 다른 프로젝트의 동시 비활성화를 직렬화) 마지막인지 확인한다.
2. 마지막이면 활성 ADMIN 계정이 있는지 확인 → 없으면 409 `NO_RECOVERY_ADMIN` (영구 잠금 방지).
3. `confirmLastActive=true` 가 없으면 409 `LAST_ACTIVE_PROJECT` → 화면이 경고("…활성 프로젝트가 하나도 남지 않습니다. … 계속하시겠습니까?")를 띄우고, 확인하면 `?confirmLastActive=true` 로 다시 보낸다.

복구할 ADMIN 이 없어지지 않도록 사용자 관리도 보강했다 : **마지막 활성 ADMIN 은 강등할 수 없다** (삭제는 원래 막혀 있었고, 강등은 id 0 만 막혀 있었다 — id 0 이 삭제된 뒤 남은 ADMIN 을 강등하면 ADMIN 이 0명이 될 수 있었다).

## 7. 운영 복구 절차

1. 로그인 화면에 "현재 활성화된 프로젝트가 없습니다." 가 보이면 시스템 ADMIN 계정으로 [관리자 복구 로그인].
   - 초기 비밀번호 상태면 비밀번호 변경 대화상자가 먼저 뜬다.
2. 복구 화면에서 필요한 프로젝트를 [활성화] (여러 개 가능).
3. [복구 완료] → 로그인 화면에서 평소처럼 로그인.

알아둘 점

- 복구 로그인은 프로젝트 멤버십을 바꾸지 않는다. ADMIN 이 **자신이 멤버인 프로젝트**를 활성화해야 본인이 정상 로그인할 수 있다 (멤버가 아닌 프로젝트만 활성화하면 그 프로젝트의 멤버들이 로그인한다).
- 감사 기록 : 애플리케이션 로그(WARN) — `복구 로그인`, `복구 로그인 거절`, `복구 세션 프로젝트 활성화`, `마지막 활성 프로젝트 비활성화`. M-9 이력 테이블에는 기록하지 않는다.
- 복구 로그인으로도 풀 수 없는 경우(프로젝트 행이 없음, ADMIN 비밀번호 분실)는 DB 에서 직접 처리한다 : `UPDATE TB_PRJ SET ACTVTN_YN = true WHERE PRJ_SN = …`.

## 8. 남은 위험·결정 사항

| 항목 | 내용 |
|---|---|
| 활성 프로젝트는 있지만 ADMIN 이 어느 프로젝트의 멤버도 아님 | ADMIN 이 로그인할 수 없다. 서비스는 동작 중이라 복구 로그인을 열지 않았다. 열려면 "ADMIN 이 접근 가능한 프로젝트가 없을 때"로 조건을 넓히고 복구 화면에서 멤버 추가까지 다뤄야 한다 — **정책 결정 필요** |
| 복구 세션의 권한 범위 | 시스템 ADMIN 은 자신이 관리자가 아닌 프로젝트도 활성화할 수 있다 (활성 프로젝트가 없을 때만). 의도한 동작 |
| 요청 로그 | 복구 API 요청이 `TB_LOG` 에 프로젝트 없이(`PRJ_SN` null) 남는지는 확인하지 않았다 (컬럼은 nullable). 시스템 로그 화면은 프로젝트 기준이라 보이지 않을 수 있다 — 감사 기록은 애플리케이션 로그 기준 |
| 로그인 화면의 활성 프로젝트 조회 | 회원가입용 공개 API 를 재사용한다 (이미 공개되어 있던 활성 프로젝트명 외에 새로 노출되는 정보 없음) |
| 프로젝트 저장 요청의 401 | 프로젝트 수정 저장만 공통 오류 알림을 거치지 않게 바꿨다 (경고 대화상자를 띄우려고). 세션 만료 시 알림 후 로그인 화면 이동은 다음 요청에서 일어난다 |

## 9. 테스트

| 구분 | 결과 |
|---|---|
| H2 `./gradlew clean build` | 392건 (2건 skip : 성능·PostgreSQL 전용), 실패 0 |
| PostgreSQL 18.6 전체 (`./gradlew test`, 전용 컨테이너 `metamora-p1-verify` 포트 15441 의 `metamora_p1_test`, 실행 후 삭제) | 392건 (1건 skip : 성능), 실패 0 |
| 브라우저 (헤드리스 Chrome·DevTools 프로토콜, 빌드한 jar 를 `h2-local`·포트 19951 로 띄움) | 33건 통과, 콘솔 오류 0 |

신규·변경 테스트

- `ProjectRecoveryLoginTest` (실서버 + Security 필터 + Spring Session JDBC, 전용 DB/스키마, 8건) : 활성 프로젝트가 있을 때 복구 거절·일반 ADMIN 의 복구 API 403 / 기본 프로젝트 비활성 + 다른 활성 프로젝트 로그인 / 마지막 활성 프로젝트 확인(409 → 확인 후 200, 세션 회수, ①단계 403) / 동시 비활성화 / 역할별(NOT_APPROVE·VIEWER·USER·DBA·MANAGER)·프로젝트 관리자·잘못된 비밀번호·비활성 계정·없는 계정 거절, 비인증 401 / 복구 흐름 전체(me, 일반 API 13종 403, CSRF 없는 요청 403, 없는 프로젝트 404, 순차·중복 활성화, 로그아웃 후 예전 쿠키 401, 정상 로그인) / 복구 세션 동시 로그인 1개(`DUPLICATE_LOGIN`) / 강등 시 복구 세션 삭제.
- `ProjectManageServiceImplTest` (+3), `UserManageServiceImplTest` (+1).
- `UiActionAuthorizationIntegrationTest` : USER 미만 변경 API 예외 목록에 복구 활성화 API 추가, `updateProject` 호출 인자.

브라우저 확인 : 마지막 활성 프로젝트 경고(취소·확인), 비활성화 후 세션 회수, 로그인 화면 안내, 비인증·일반 세션의 복구 화면 접근, 일반 사용자 복구 거절, ADMIN 복구 로그인, 목록·활성화·복구 완료, 복구 모드의 메뉴 미노출과 주소 직접 입력 6종, 뒤로 가기, 정상 로그인.

검증하지 않은 것

- (통합 후 확인함) M-9 이력 API `/api/modelHistory/**` 는 복구 세션에서 403 — 원래 작업 디렉터리에 통합한 뒤 `ProjectRecoveryLoginTest.recoveryFlow` 의 차단 목록에 넣어 H2 전체 빌드(398건, 실패 0)로 확인. 통합 후 PostgreSQL 전체 테스트와 브라우저 검증은 다시 돌리지 않았다.
- 세션 만료(30분 경과) 자체는 기다려 보지 않았다 (세션 삭제 후 401 은 확인).
- `TB_LOG` 기록 여부(8장), Chrome 이외 브라우저, 영어 화면 문구, PostgreSQL 에서의 브라우저 검증.

## 10. M-9 와의 통합 시 확인 사항

작업 시점(2026-11-12)에 M-9 작업 디렉터리에서 바뀌어 있던 파일(`data/history/**`, `V5__model_change_history.sql`, `ReferenceSyncBoundary`, `ModelChangeHistoryIntegrationTest`·`ModelIntegrityHardeningIntegrationTest`·`ReferenceSyncIntegrationTest`·`V0SeedMigrationTest`)과 **겹치는 파일은 없다**. DB 마이그레이션도 건드리지 않았다.

통합할 때

1. 끝에 항목을 덧붙이는 파일은 양쪽이 같이 고쳤으면 충돌한다 : `ErrorCode.java`, `errors_ko/en.properties`, `lexorma-web/src/locales/en.ts`, `docs/data-modeling-roadmap.md`, `docs/ui-action-authorization-audit.md`.
2. `UiActionAuthorizationIntegrationTest` : M-9 가 API 를 추가하며 이 테스트를 고쳤다면 `BELOW_USER_MUTATIONS` 와 `updateProject(…, false, …)` 를 함께 확인.
3. M-9 이력 API 를 `ProjectRecoveryLoginTest.recoveryFlow` 의 차단 목록에 넣어 복구 세션에서 403 인지 확인.
4. M-9 가 `MenuAuthorization.MENU_ROLES` 에 메뉴를 추가해도 복구 세션의 `menus` 는 모두 `false` 여야 한다 (같은 테스트가 확인).
5. V5 적용 후 H2·PostgreSQL 전체 테스트, Spring Session(로그인 유지·동시 로그인) 회귀.
6. `CLAUDE.md`(git 에 없는 파일)에는 통합하면서 Authorization 절에 아래 내용을 넣었다.

> **Recovery login** (all projects inactive — see `docs/project-recovery-login.md`): `POST /api/auth/login` with `recovery=true` and no `project_id` is accepted only while no project is active and only for accounts whose assigned role is `ROLE_ADMIN` (else 401 `RECOVERY_LOGIN_DENIED`). The session gets the single authority `ROLE_RECOVERY` (not in the role hierarchy or `TB_ROLE`, `projectId` null, `/api/auth/me` adds `recovery: true` with every menu false), so no `@PreAuthorize` role matches; `SecurityConfig` additionally limits recovery sessions to `/api/recovery/**` (`RecoveryRestController`: list projects, activate one) and keeps normal sessions out of it — don't add other endpoints under `/api/recovery`, and don't add fields to `FormWebAuthenticationDetails` (serialized in existing sessions; recovery uses the subclass `RecoveryAuthenticationDetails`). Recovery ends by logout, never by converting the session. Deactivating the last active project needs `?confirmLastActive=true` (409 `LAST_ACTIVE_PROJECT` otherwise, 409 `NO_RECOVERY_ADMIN` if no active ADMIN exists), and the last active ADMIN can be neither deleted nor demoted. The SPA shows only `/recovery/projects` to a recovery session (`RequireRecovery` in `routes/guards.tsx`).
