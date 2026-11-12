# 프로젝트 이름 변경 : MetaMora → Lexorma

2026-11-12. 같은 저장소·같은 디렉토리에서 프로젝트 이름을 바꿨다. 기능·API·권한·데이터 모델·DB 데이터·Flyway 이력은 그대로다.

## 바꾼 것

| 항목 | 전 | 후 |
|---|---|---|
| 표시 이름 | MetaMora | Lexorma |
| Java 기본 패키지 | `com.koboolean.metamora` | `com.lexorma` |
| 메인 클래스 | `MetamoraApplication` | `LexormaApplication` |
| Gradle 루트 프로젝트 · 모듈 | `metamora`, `metamora-*` | `lexorma`, `lexorma-*` (그룹 `com.lexorma`) |
| 실행 jar | `metamora.jar` | `lexorma.jar` |
| Spring 애플리케이션 이름 | `metamora` | `lexorma` |
| 프론트엔드 | `metamora-web`, 패키지명 `metamora-web` | `lexorma-web`, 패키지명 `lexorma` |
| Compose 서비스·컨테이너 | `metamora-postgres`, `metamora-app` | `lexorma-postgres`, `lexorma-app` |
| Compose 파일 위치 | `docker/metamora/` | `docker/lexorma/` |
| Docker 이미지 이름 | `koboolean/metamora` | `lexorma/lexorma` |
| 테스트용 환경 변수 | `METAMORA_TEST_POSTGRES_URL`, `METAMORA_PERF` | `LEXORMA_TEST_POSTGRES_URL`, `LEXORMA_PERF` |
| 로그 레벨 환경 변수 | `LOGGING_LEVEL_COM_KOBOOLEAN_METAMORA` | `LOGGING_LEVEL_COM_LEXORMA` |
| 새로 만들어지는 인메모리 H2 (h2-local, 테스트) | `metamora…` | `lexorma…` |
| 브라우저 저장 키 | `metamora.columnBulk.recommend` | `lexorma.columnBulk.recommend` (자동 추천 체크 상태가 한 번 초기화된다) |

## 일부러 남긴 것 (데이터·호환)

| 남긴 이름 | 이유 |
|---|---|
| Flyway V0~V5 의 내용 (`META-MORA` 예제 프로젝트·논리 모델 이름, 예제 테이블 설명의 "MetaMora … 정보", 환영 공지의 "Metamora" 와 저장소 주소) | 적용된 마이그레이션은 체크섬 때문에 고칠 수 없다. 데이터는 V6 가 고친다 (아래 "초기 데이터") |
| DB 이름·계정 `metamora` (`local`·`dev` 프로필, `docker` 프로필의 기본값, Compose 의 `POSTGRES_*`, 테스트 접속 계정) | 기존 DB 와 데이터 디렉토리가 이 이름으로 만들어져 있다. 새로 설치할 때는 `DB_NAME`·`DB_USERNAME`·`DB_PASSWORD` 로 `lexorma` 를 준다 (README) |
| `h2-docker` 의 파일 DB 경로 `/data/metamora` 와 계정 | 기존 H2 파일 DB |
| Compose 의 데이터 디렉토리 `docker/volume/metamora`, 네트워크 `metamora-network` | 기존 데이터·환경 |
| 푸터의 `Made by koboolean`, `LICENSE` 의 저작권자 | 만든 사람·저작권 표시 |
| 문서의 지난 검증 기록에 나오는 임시 DB·컨테이너 이름, 없앤 모듈 `metamora-chat`·`metamora-design` | 과거 기록 |
| 작업 디렉토리 이름 | 지시대로 바꾸지 않았다 |


## 세션 호환 (`SessionSerializationConfig`)

세션은 DB 에 Java 직렬화로 저장되고 그 안에 클래스 이름이 들어 있다. 패키지를 바꾸면 이름 변경 전에 만든 세션을 읽지 못해, 그 쿠키를 가진 사용자의 **모든 요청(로그인 화면 포함)이 500** 이 됐다(이전 빌드 → 새 빌드를 같은 DB 로 띄워 확인).
그래서 세션 역직렬화에서 예전 패키지 이름을 지금 클래스로 읽게 했다. 로그인 상태가 유지되고, 그래도 읽지 못하는 값은 없는 값으로 처리한다(로그인이 풀릴 뿐 오류가 나지 않는다). 예전 세션이 모두 만료된 뒤(30분)에는 지워도 되는 코드다.

## 확인

| 항목 | 결과 |
|---|---|
| H2 `./gradlew clean build` (검증용 복사본) | 401건, 실패 0, 건너뜀 2 |
| PostgreSQL 18.6 일회용 컨테이너, 전체 | 401건, 실패 0, 건너뜀 1 |
| 기존 DB 업그레이드 : 이전 빌드로 만든 DB(V0~V5, 로그인 세션 1개)를 새 빌드로 기동 | Flyway 6개 검증 통과(체크섬 그대로), 데이터 그대로, 예전 세션으로 조회·변경 API 200 |
| 브라우저 (헤드리스 Chrome + PostgreSQL) | 로그인·회원가입·헤더·푸터·문서 제목이 Lexorma, 15개 화면 표시(두 ERD 38노드), VIEWER·미승인 권한 그대로 |
| Git Remote | `https://github.com/koboolean/metamora.git` — GitHub 가 `https://github.com/Lexorma/Lexorma` 로 넘겨 준다(301). 주소는 바꾸지 않았다 |

(위 표는 이름 변경 때의 결과다. 그 뒤의 확인은 아래 "후속 보완" 에 있다.)

## 이어서 할 일

- IDE 에서 Gradle 프로젝트를 다시 불러온다(모듈 이름이 바뀌었다). 실행 중이던 Vite 개발 서버가 남긴 `metamora-web/.vite` 는 서버를 끄고 지운다 (후속 보완 때도 서버가 떠 있어 지우지 않았다).
- 예전 이름으로 떠 있는 컨테이너는 내리고 새 이름으로 올린다(`docker-compose -p lexorma up -d`). 데이터 디렉토리는 그대로 쓴다.
- 이미지 저장소 이름(`lexorma/lexorma`)이 실제 레지스트리와 맞는지 확인한다.
- Git Remote 를 새 주소로 바꿀지 정한다.
- 초기 데이터의 예전 이름을 바꿀지 정한다(새 마이그레이션 필요).

## 후속 보완

### 초기 데이터 (`V6__lexorma_seed_names.sql`)

V0~V5 는 그대로 두고, 데이터만 고치는 V6 를 더했다 (postgresql·h2 같은 내용, 스키마 변경 없음). 새로 설치하면 V0~V5 가 예전 이름으로 넣은 뒤 V6 가 고치고, 기존 DB 는 다음 기동 때 V6 만 적용된다.

| 대상 | 바꾸는 조건 |
|---|---|
| 기본 프로젝트(PRJ_SN 0) 이름 `META-MORA` → `LEXORMA` | 이름이 `META-MORA` 그대로이고 `LEXORMA` 라는 프로젝트가 없을 때 |
| 프로젝트 0 의 예제 논리 모델 이름 `META-MORA` → `LEXORMA`, 설명 | 원문 그대로일 때 (같은 이름의 모델이 없을 때) |
| 프로젝트 0 의 예제 테이블·엔터티 설명 `MetaMora <한글명> 정보` | 설명이 그 형태 그대로일 때 |
| 프로젝트 0 의 자체 도메인 설명 19개 | 도메인 이름과 설명이 원문 그대로일 때 |
| 환영 공지(PST_SN 1) 제목·본문·저장소 주소 | 제목과 본문이 **모두** 원문 그대로일 때 |

전체 치환은 하지 않는다. 사용자가 한 글자라도 고친 행, 다른 프로젝트의 행은 그대로 남는다 (`V0SeedMigrationTest.renamesOnlyUntouchedSeedData` 가 H2·PostgreSQL 에서 확인). `EnglishMessages` 의 환영 공지 원문·영문과 테스트는 V6 뒤의 문구로 바꿨다. 공지를 고쳐 쓴 설치에서는 전과 같이 영어판이 연결되지 않는다.
주의 : 예제 논리 모델 이름이 새 설치에서는 `LEXORMA`, 업그레이드한 설치에서는 다를 수 있다. 예제 행을 더하는 다음 마이그레이션은 이름 하나를 가정하면 안 된다.

### 브랜드 리소스

`lexorma-web/public/images` 의 4개가 전부다 (`/images/**` 로 제공). `favicon.ico`, Manifest, PWA 아이콘은 원래 없고 더하지 않았다.

| 파일 | 내용 |
|---|---|
| `favicon.svg` | 원본. 브라우저 탭, 로그인·회원가입·헤더·푸터의 로고 |
| `favicon.png` (512×512), `apple-touch-icon.png` (180×180) | `favicon.svg` 를 그린 것 |
| `og-image.png` (1200×630) | 공유 이미지 : 아이콘 + Lexorma + Data Standards & Modeling Platform |

예전 아이콘은 MetaMora 의 "m" 이었고 공유 이미지에는 MetaMora 글자가 있었다. 확정된 Lexorma 브랜드 자산이 없어 **임시 아이콘**을 넣었다 : 예전 아이콘의 배경·색·점을 그대로 두고 글자만 "L" 로 바꾼 것. 최종 디자인은 따로 승인받아 4개 파일을 함께 바꾼다. `og:url` 은 배포 도메인이 정해지지 않아 넣지 않았다.

### 세션 호환 테스트

`SessionSerializationConfigTest` (lexorma-core). 픽스처 `session/legacy-security-context.b64` 는 이름을 바꾸기 전의 빌드(커밋 7a40013)를 PostgreSQL 에 띄워 로그인한 뒤 `SPRING_SESSION_ATTRIBUTES` 에서 꺼낸 실제 값이다 (비밀번호 없음). 이전 세션에서 사용자·권한·프로젝트가 읽히는지, 다시 저장하면 새 이름으로 저장되는지, 잘린 값·쓰레기 값·없어진 클래스는 예외 없이 없는 값이 되는지, 치환이 예전 기본 패키지로 시작하는 이름에만 적용되는지 본다.
역직렬화할 수 있는 클래스의 범위는 Spring Session 기본(JDK 직렬화)과 같다 — 허용 목록은 없다. 세션 값은 애플리케이션이 직접 쓴 DB 값이고, 호환 코드는 범위를 넓히지 않는다 (이름만 바꿔 읽는다).

### Git Remote

`origin` 을 `https://github.com/Lexorma/Lexorma.git` 로 바꿨다 (`git remote set-url`). 브랜치와 추적 관계(`dev` → `origin/dev`, `main` → `origin/main`)는 그대로다. fetch·pull·push 는 하지 않았다.

### Docker

- 이미지 이름 `lexorma/lexorma` 는 Docker Hub 에 없다 (예전 `koboolean/metamora` 도 없다). 직접 빌드해 쓰는 이름이며, 레지스트리에 올릴 이름은 정해지지 않았다.
- `docker/lexorma/docker-compose.yml` : 빈 데이터 디렉토리로 처음 올리면 PostgreSQL 초기화가 끝나기 전에 앱이 접속하다 종료됐다 (이름 변경 전부터 있던 문제). PostgreSQL 에 `healthcheck` 를 넣고 앱이 `service_healthy` 를 기다리게 했다. DB 이름·계정, 데이터 디렉토리, 네트워크 이름은 그대로다.

### 후속 보완 확인

| 항목 | 결과 |
|---|---|
| H2 전체 (`./gradlew clean build`, 검증용 복사본) | 407건, 실패 0, 건너뜀 3 |
| PostgreSQL 18.6 일회용 컨테이너, 전체 | 407건, 실패 0, 건너뜀 1 |
| 새 설치 (빈 PostgreSQL) | V0~V6 7개 적용, 초기 데이터에 예전 이름 없음 |
| 기존 DB 업그레이드 (이전 빌드로 만든 V0~V5 DB + 로그인 세션) | V6 만 적용, V0~V5 체크섬 그대로, 테이블 38개·사용자가 만든 스키마 그대로, 예전 세션으로 조회·변경 200, 로그아웃 204 뒤 401 |
| Docker | `Dockerfile` 이미지 빌드, Compose(포트·데이터 디렉토리만 바꾼 임시 사본)로 빈 DB 기동 → 정지 → 재기동(Flyway 7개 검증, 데이터 유지), 오류 로그 0 |
| 브라우저 (헤드리스 Chrome, Docker 로 띄운 앱) | 아이콘 3종·공유 이미지 200, 로그인·회원가입·헤더·푸터에 새 아이콘, 17개 화면 표시(두 ERD 38노드), 화면에 예전 이름 0건 |

확인하지 못한 것 : `Dockerfile-h2`·`Dockerfile.dev` 빌드, GitHub Actions 실행, IDE 에서의 다시 가져오기, 실제 브라우저 탭·메신저 미리보기에서의 아이콘 모양.

## 마이그레이션 통합 (V0~V6 → V0 + V1)

소유자 요청으로 Flyway 파일을 DB 별 두 개로 합쳤다. 위 "초기 데이터 (`V6__lexorma_seed_names.sql`)" 절은 그 전의 기록이다 — V6 는 없어졌고 초기 데이터가 처음부터 Lexorma 이름으로 들어간다.

| 파일 | 내용 | 옮겨 온 곳 |
|---|---|---|
| `V0__init.sql` | 스키마만 : 시퀀스 31, 테이블 40, 인덱스 40, 외래 키, 세션 테이블, COMMENT 407 | 옛 V0 의 스키마, V2, V5 의 스키마, V3·V5 의 COMMENT |
| `V1__seed_data.sql` | 데이터만 (INSERT 1,831) : 기본 데이터, 예제 데이터 사전·물리 모델·논리 모델, 매핑·변경 이력 테이블의 예제, 예제 매핑 | 옛 V0 의 초기 데이터, V1, V3·V5 의 예제 행, V4 |

뺀 것 : V6 의 UPDATE 46개(이름을 처음부터 새 이름으로 넣는다), V5 에 한 번 더 있던 매핑 문장(V4 와 같은 문장 — 마지막에 한 번만 실행한다), 파일마다 있던 "기존 DB 에서도 안전하게" 설명. PostgreSQL 의 역할 INSERT 표기를 H2 와 같게 맞췄다.

확인 : PostgreSQL 18.6 에서 옛 7개 파일로 만든 DB 와 새 2개 파일로 만든 DB 를 비교했다. 스키마 덤프(4,899줄)는 한 줄도 다르지 않고, 데이터는 2,268행 중 매핑 두 테이블의 일련번호만 다르다(매핑을 한 번에 넣어 번호 순서가 달라졌다. 엔터티↔테이블 38건, 속성↔컬럼 369건의 짝은 같다).

**기존 DB** : 이전 빌드로 만든 DB 는 Flyway 이력(V0~V5 또는 V6)이 새 파일과 맞지 않아 기동 때 검증에 실패한다. 테이블 구조는 같으므로 이력만 다시 맞추면 되지만(백업 뒤 baseline/repair), 승인 없이 하지 않는다. 그런 DB 의 초기 데이터 이름(`META-MORA` 등)은 바뀌지 않는다.
